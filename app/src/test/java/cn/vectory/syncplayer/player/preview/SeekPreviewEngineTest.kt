package cn.vectory.syncplayer.player.preview

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.coroutines.CoroutineContext
import kotlin.system.measureTimeMillis

/**
 * Concurrency/lifecycle proof for [SeekPreviewEngine] — pure JVM, real latches,
 * real dedicated lane threads (DaemonLanePool), no Robolectric.
 *
 * The fakes simulate the only thing that matters about the real
 * MediaMetadataRetriever: native calls that can ignore thread interruption and
 * coroutine cancellation. Latch gates emulate "native parked forever" while
 * keeping the test itself bounded (all awaitLatch calls have hard timeouts and
 * every gate is opened in finally).
 */
class SeekPreviewEngineTest {

    // ---------- fakes ----------

    /**
     * A decoder whose native calls can be parked inside real latches.
     * `closedLatch` counts down only after the (possibly throwing) close body
     * has fully run — it never signals early.
     */
    private class FakeDecoder(
        val openBlock: CountDownLatch? = null,
        val decodeBlock: CountDownLatch? = null,
        val closeBlock: CountDownLatch? = null,
        val openThrows: Throwable? = null,
        val decodeThrows: Throwable? = null,
        val closeThrows: Throwable? = null,
        val decodeResult: Int? = 42,
        val onOpen: () -> Unit = {},
        val onClose: () -> Unit = {},
    ) : PreviewDecoder<Int> {
        val openEntered = CountDownLatch(1)
        val decodeEntered = CountDownLatch(1)
        val closeEntered = CountDownLatch(1)
        val closedLatch = CountDownLatch(1)
        val openCount = AtomicInteger(0)
        val decodeCount = AtomicInteger(0)
        val closeCount = AtomicInteger(0)
        val decodePositions = CopyOnWriteArrayList<Long>()

        override fun open() {
            try {
                openEntered.countDown()
                openBlock?.await()
            } finally {
                openCount.incrementAndGet()
            }
            onOpen()
            openThrows?.let { throw it }
        }

        override fun decode(positionMs: Long): Int? {
            try {
                decodeEntered.countDown()
                decodeBlock?.await()
            } finally {
                decodeCount.incrementAndGet()
            }
            decodeThrows?.let { throw it }
            decodePositions.add(positionMs)
            return decodeResult
        }

        override fun close() {
            try {
                closeEntered.countDown()
                onClose()
                closeBlock?.await()
            } finally {
                closeCount.incrementAndGet()
            }
            try {
                closeThrows?.let { throw it }
            } finally {
                // closedLatch fires only after close body + throw decision
                closedLatch.countDown()
            }
        }
    }

    /** Records max concurrent native depth across create/open/decode/close. */
    private class DepthProbe(val inner: FakeDecoder) : PreviewDecoder<Int> {
        companion object {
            val depth = AtomicInteger(0)
            val maxDepth = AtomicInteger(0)
        }

        private fun <T> track(block: () -> T): T {
            val d = depth.incrementAndGet()
            maxDepth.updateAndGet { if (d > it) d else it }
            try {
                return block()
            } finally {
                depth.decrementAndGet()
            }
        }

        override fun open() = track { inner.open() }
        override fun decode(positionMs: Long) = track { inner.decode(positionMs) }
        override fun close() = track { inner.close() }
    }

    /**
     * Factory recorder: hands out `planned` decoders in order, then fresh
     * defaults. Every created decoder is tracked so [assertAllClosedOnce] can
     * prove created == closed exactly once.
     */
    private class Recorder(vararg planned: PreviewDecoder<Int>) {
        val created = CopyOnWriteArrayList<PreviewDecoder<Int>>()
        val callCount = AtomicInteger(0)
        val allCreated = CountDownLatch(planned.size)
        private val queue = ArrayDeque(planned.toList())

        val factory: (String) -> PreviewDecoder<Int> = { _ ->
            val d = synchronized(queue) {
                if (queue.isNotEmpty()) queue.removeFirst() else FakeDecoder()
            }
            created.add(d)
            callCount.incrementAndGet()
            allCreated.countDown()
            d
        }

        fun assertAllClosedOnce() {
            for (d in created) {
                val f = if (d is DepthProbe) d.inner else d as FakeDecoder
                assertEquals("every created decoder must close exactly once", 1, f.closeCount.get())
            }
        }
    }

    /** Pool probe: counts giveBack calls so tests can prove lane isolation. */
    private class CountingPool(val delegate: PreviewLanePool) : PreviewLanePool {
        val givenBack = AtomicInteger(0)
        val lanes = CopyOnWriteArrayList<PreviewLane>()

        override fun tryAcquire(): PreviewLane? =
            delegate.tryAcquire()?.also { lanes.add(it) }

        override fun giveBack(lane: PreviewLane) {
            givenBack.incrementAndGet()
            delegate.giveBack(lane)
        }
    }

    /**
     * Lane whose first N submit attempts throw (saturated executor); later
     * submits run the task inline so the engine's retry path is provable.
     */
    private class RejectFirstSubmitsPool(times: Int) : PreviewLanePool {
        private val remaining = AtomicInteger(times)

        override fun tryAcquire(): PreviewLane? = object : PreviewLane {
            override fun submit(task: () -> Unit) {
                if (remaining.getAndDecrement() > 0) throw IllegalStateException("submit rejected")
                task()
            }
        }

        override fun giveBack(lane: PreviewLane): Unit = Unit
    }

    /**
     * SINGLE-lane pool for rejection scenarios with CONTRACT-COMPLIANT
     * scheduling: accepted submits are ENQUEUED to a dedicated daemon worker
     * thread (enqueue-and-return — never executed inline on the caller
     * thread, matching [PreviewLane.submit]'s contract); every submit from
     * [rejectFrom] on throws (saturated executor). [workerIdle] counts down
     * once the accepted worker has fully RETURNED from its task, so tests can
     * prove the engine is genuinely idle — not merely "submit returned" —
     * before signalling end/release. tryAcquire exclusively leases its one
     * lane; [givenBack] is observable so tests can prove a rejected engine
     * never returns its lease (pool-bound honesty).
     */
    private class ScriptedRejectPool(private val rejectFrom: Int) : PreviewLanePool {
        val givenBack = AtomicInteger(0)

        /** Fires after the accepted worker finished its task (lane idle). */
        val workerIdle = CountDownLatch(1)

        private val queue = LinkedBlockingQueue<() -> Unit>()
        private val counter = AtomicInteger(0)
        private val gate = Any()
        private var leasedOut = false
        private val lane = object : PreviewLane {
            override fun submit(task: () -> Unit) {
                if (counter.getAndIncrement() >= rejectFrom) throw IllegalStateException("submit rejected")
                queue.put(task) // enqueue-and-return — the worker runs it
            }
        }

        init {
            thread(isDaemon = true, name = "scripted-reject-lane") {
                while (true) {
                    val task = queue.take()
                    try {
                        task()
                    } finally {
                        workerIdle.countDown()
                    }
                }
            }
        }

        override fun tryAcquire(): PreviewLane? = synchronized(gate) {
            if (leasedOut) null else {
                leasedOut = true
                lane
            }
        }

        override fun giveBack(lane: PreviewLane) {
            synchronized(gate) {
                givenBack.incrementAndGet()
                leasedOut = false
            }
        }
    }

    /**
     * Test-only dispatcher whose continuations are HELD in a queue until the
     * test releases them. This makes the "caller cancelled AFTER install but
     * BEFORE its begin resumed" window deterministic: the open completes on
     * the real lane, the awaiter's resumption sits in the held queue, and
     * only then is the caller's job cancelled.
     */
    private class HeldDispatcher : CoroutineDispatcher() {
        private val queue = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()

        @Volatile
        var held = true
            private set

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (held) queue.add(block) else block.run()
        }

        /** Runs exactly one held block (e.g. the initial launch). */
        fun step() {
            val block = queue.poll() ?: error("nothing held to step")
            block.run()
        }

        fun hasPending(): Boolean = queue.isNotEmpty()

        fun releaseAll() {
            held = false
            while (true) {
                val block = queue.poll() ?: break
                block.run()
            }
        }
    }

    private fun awaitLatch(latch: CountDownLatch, what: String, seconds: Long = 10) {
        assertTrue("$what (timed out)", latch.await(seconds, TimeUnit.SECONDS))
    }

    /**
     * kotlinx-coroutines stack-trace recovery may replace an exception that
     * crosses a coroutine boundary with a COPY of the same class (same
     * message, `_COROUTINE._BOUNDARY` frames added, the engine's REAL
     * instance kept as its cause). The engine's identity contract is about
     * the instance it delivered — unwrap the diagnostic re-wrapping so
     * `assertSame` assertions stay about engine behavior, not debug
     * decoration.
     */
    private fun Throwable.engineReal(): Throwable {
        var e: Throwable = this
        while (e.stackTrace.any { it.className == "_COROUTINE._BOUNDARY" } &&
            e.cause != null &&
            e.cause!!.javaClass == e.javaClass &&
            e.cause!!.message == e.message
        ) {
            e = e.cause!!
        }
        return e
    }

    private fun newEngine(
        recorder: Recorder,
        pool: PreviewLanePool = DaemonLanePool(1),
        logger: (String) -> Unit = {},
    ) = SeekPreviewEngine(decoderFactory = recorder.factory, lanePool = pool, logger = logger)

    private fun CoroutineScope.extractAsync(
        engine: SeekPreviewEngine<Int>,
        key: String,
        pos: Long,
    ): Deferred<SeekPreviewEngine.Extracted<Int>?> =
        async { engine.extract(key, pos) }

    // ---------- tests ----------

    @Test
    fun `begin extract end closes session decoder exactly once`() = runBlocking {
        val recorder = Recorder(FakeDecoder())
        val engine = newEngine(recorder)

        assertSame(SeekPreviewEngine.InitResult.Opened, engine.begin("uriA"))
        val ex = engine.extract("uriA", 1000L)
        assertEquals(42, ex!!.frame)
        val endSignal = engine.end()
        val sd = engine.release()
        awaitLatch(recorder.created[0].let { (it as FakeDecoder).closedLatch }, "session close")
        endSignal.await()
        sd.await()

        val d = recorder.created[0] as FakeDecoder
        assertEquals(1, d.decodeCount.get())
        assertEquals(1, d.closeCount.get())
        recorder.assertAllClosedOnce()
    }

    @Test
    fun `ephemeral extract without session creates and closes decoder`() = runBlocking {
        val recorder = Recorder(FakeDecoder())
        val engine = newEngine(recorder)

        val ex = engine.extract("uriB", 500L)
        assertEquals(42, ex!!.frame)
        engine.release().await()

        recorder.assertAllClosedOnce()
    }

    @Test
    fun `extract after release returns null and creates nothing`() = runBlocking {
        val recorder = Recorder()
        val engine = newEngine(recorder)

        engine.release().await()
        assertNull(engine.extract("uriA", 0L))
        assertEquals(0, recorder.callCount.get())
    }

    @Test
    fun `open failure is delivered to begin and created decoder is closed once`() = runBlocking {
        val failure = RuntimeException("native setDataSource failed")
        val created = FakeDecoder(openThrows = failure)
        val recorder = Recorder(created)
        val engine = newEngine(recorder)

        try {
            engine.begin("uriA")
            fail("begin must throw the real open failure")
        } catch (t: Throwable) {
            assertSame(failure, t.engineReal())
        }
        assertEquals(1, recorder.callCount.get())
        awaitLatch(created.closedLatch, "created decoder must close")
        assertEquals(1, created.closeCount.get())
        engine.release().await()
    }

    @Test
    fun `factory cancellation exception is delivered to begin not swallowed`() = runBlocking {
        val ce = CancellationException("native cancel")
        var calls = 0
        val engine = SeekPreviewEngine<Int>(
            decoderFactory = { calls++; if (calls == 1) throw ce else FakeDecoder() },
            lanePool = DaemonLanePool(1),
        )
        try {
            engine.begin("uriA")
            fail("factory CE must reach begin caller")
        } catch (t: CancellationException) {
            assertSame(ce, t.engineReal())
        }
        engine.release().await()
    }

    @Test
    fun `factory failure reaches begin unmasked and engine stays retryable`() = runBlocking {
        val boom = RuntimeException("factory exploded")
        var calls = 0
        val engine = SeekPreviewEngine<Int>(
            decoderFactory = { calls++; if (calls == 1) throw boom else FakeDecoder() },
            lanePool = DaemonLanePool(1),
        )
        try {
            engine.begin("uriA")
            fail("factory failure must reach begin caller, not Superseded")
        } catch (t: Throwable) {
            assertSame("original failure delivered, never masked", boom, t.engineReal())
        }
        // No resource ever existed → nothing to close, no lane isolation, no
        // seal: the engine must stay retryable and a second attempt works.
        assertSame(SeekPreviewEngine.InitResult.Opened, engine.begin("uriA"))
        assertEquals(42, engine.extract("uriA", 0L)!!.frame)
        engine.release().await()
    }

    @Test
    fun `decode cancellation exception propagates to extract caller`() = runBlocking {
        val ce = CancellationException("decode cancelled natively")
        val recorder = Recorder(FakeDecoder(decodeThrows = ce))
        val engine = newEngine(recorder)

        assertSame(SeekPreviewEngine.InitResult.Opened, engine.begin("uriA"))
        try {
            engine.extract("uriA", 0L)
            fail("decode CE must reach extract caller")
        } catch (t: CancellationException) {
            assertSame(ce, t.engineReal())
        }
        // poisoned session decoder closed exactly once; engine still alive
        awaitLatch((recorder.created[0] as FakeDecoder).closedLatch, "poisoned decoder close")
        engine.release().await()
    }

    @Test
    fun `stale begin returns Superseded immediately while older open is parked`() = runBlocking {
        val openGate = CountDownLatch(1)
        val decA = FakeDecoder(openBlock = openGate)
        val decB = FakeDecoder()
        val recorder = Recorder(decA, decB)
        val engine = newEngine(recorder)

        val scope = CoroutineScope(Dispatchers.IO)
        val bInit: Deferred<SeekPreviewEngine.InitResult>
        try {
            // A's open parks on the lane
            val aInit = scope.async { engine.begin("uriA") }
            awaitLatch(decA.openEntered, "A open must have started")

            // B's begin supersedes A's ACTIVE open — B's registration is
            // PROVEN by aInit resolving while A's native open is still
            // parked. B itself cannot resolve until the lane frees, so
            // awaiting B inside this try would deadlock on the closed gate.
            bInit = scope.async { engine.begin("uriB") }
            assertSame(
                "active open init must resolve Superseded before native returns",
                SeekPreviewEngine.InitResult.Superseded,
                withTimeout(10_000) { aInit.await() },
            )
            assertFalse("B must still be unresolved while A's gate is closed", bInit.isCompleted)
            // Open the gate and resolve B INSIDE the try: awaiting the
            // scope-registered bInit after finally's scope.cancel() would
            // kill it with a JobCancellationException instead of Opened.
            openGate.countDown()
            // After unblocking: A is orphan-closed, B installed.
            assertSame(
                "B begin resolves once its open installs",
                SeekPreviewEngine.InitResult.Opened,
                withTimeout(10_000) { bInit.await() },
            )
            awaitLatch(decA.closedLatch, "superseded A decoder must close")
            assertEquals(1, decA.closeCount.get())
            assertEquals(42, engine.extract("uriB", 0L)!!.frame)
            engine.release().await()
            recorder.assertAllClosedOnce()
        } finally {
            openGate.countDown()
            scope.cancel()
        }
    }

    @Test
    fun `double release returns same deferred and stays incomplete while native parked`() =
        runBlocking {
            val decodeGate = CountDownLatch(1)
            val dec = FakeDecoder(decodeBlock = decodeGate)
            val recorder = Recorder(dec)
            val engine = newEngine(recorder)

            assertSame(SeekPreviewEngine.InitResult.Opened, engine.begin("uriA"))
            val scope = CoroutineScope(Dispatchers.IO)
            try {
                val parked = scope.async { engine.extract("uriA", 0L) }
                awaitLatch(dec.decodeEntered, "decode must be parked in native")

                val r1 = engine.release()
                val r2 = engine.release()
                assertSame("second release must return the SAME deferred", r1, r2)
                assertFalse("shutdown must not complete while native parked", r1.isCompleted)
                assertEquals(0, dec.closeCount.get())
            } finally {
                decodeGate.countDown()
                scope.cancel()
            }
            withTimeout(10_000) { engine.shutdown.await() }
            awaitLatch(dec.closedLatch, "decoder must close after unblock")
            assertEquals(1, dec.closeCount.get())
            recorder.assertAllClosedOnce()
        }

    @Test
    fun `parked close keeps repeated end signals pending, 10k ends share one deferred`() =
        runBlocking {
            val closeGate = CountDownLatch(1)
            val dec = FakeDecoder(closeBlock = closeGate)
            val recorder = Recorder(dec)
            val engine = newEngine(recorder)

            assertSame(SeekPreviewEngine.InitResult.Opened, engine.begin("uriA"))
            val e1: Deferred<Unit>
            try {
                e1 = engine.end()
                awaitLatch(dec.closeEntered, "close must be entered and parked")
                assertFalse("end signal must not complete while close parked", e1.isCompleted)
                val e2 = engine.end()
                assertSame("same generation must share the close deferred", e1, e2)
                var probe: Deferred<Unit> = e1
                repeat(10_000) { probe = engine.end() }
                assertSame("10k ends stay constant-state", e1, probe)
                assertFalse(e1.isCompleted)
            } finally {
                closeGate.countDown()
            }
            withTimeout(10_000) { e1.await() }
            engine.release().await()
            recorder.assertAllClosedOnce()
        }

    @Test
    fun `close failure seals engine - exceptional shutdown, begin Closed, lane isolated`() =
        runBlocking {
            val closeGate = CountDownLatch(1)
            val boom = RuntimeException("native release failed")
            val dec = FakeDecoder(closeBlock = closeGate, closeThrows = boom)
            val recorder = Recorder(dec)
            val pool = CountingPool(DaemonLanePool(2))
            val engine = newEngine(recorder, pool)

            assertSame(SeekPreviewEngine.InitResult.Opened, engine.begin("uriA"))
            val endSignal = engine.end()
            try {
                awaitLatch(dec.closeEntered, "close must be entered and parked")
            } finally {
                closeGate.countDown()
            }
            // close runs once, throws once
            awaitLatch(dec.closedLatch, "close attempt completed")
            assertEquals(1, dec.closeCount.get())
            try {
                endSignal.await()
                fail("end signal must complete exceptionally on close failure")
            } catch (t: Throwable) {
                assertSame(boom, t.engineReal())
            }
            try {
                engine.shutdown.await()
                fail("shutdown must complete exceptionally on close failure")
            } catch (t: Throwable) {
                assertSame(boom, t.engineReal())
            }
            assertSame(
                "sealed engine begin must be Closed",
                SeekPreviewEngine.InitResult.Closed,
                engine.begin("uriB"),
            )
            // Unknown native release state → lane quota consumed, never returned
            assertEquals("stranded lane must not be given back", 0, pool.givenBack.get())
        }

    @Test
    fun `close cancellation exception also seals - once, exceptional shutdown, isolated lane`() =
        runBlocking {
            val closeGate = CountDownLatch(1)
            val ce = CancellationException("native close cancelled")
            val dec = FakeDecoder(closeBlock = closeGate, closeThrows = ce)
            val recorder = Recorder(dec)
            val pool = CountingPool(DaemonLanePool(2))
            val engine = newEngine(recorder, pool)

            assertSame(SeekPreviewEngine.InitResult.Opened, engine.begin("uriA"))
            val endSignal = engine.end()
            try {
                awaitLatch(dec.closeEntered, "close parked")
            } finally {
                closeGate.countDown()
            }
            awaitLatch(dec.closedLatch, "close attempt completed")
            assertEquals(1, dec.closeCount.get())
            try {
                endSignal.await()
                fail()
            } catch (t: CancellationException) {
                assertSame(ce, t.engineReal())
            }
            try {
                engine.shutdown.await()
                fail()
            } catch (t: CancellationException) {
                assertSame(ce, t.engineReal())
            }
            assertEquals(0, pool.givenBack.get())
        }

    @Test
    fun `end for old resource completes as soon as A closes, even while B open is parked`() =
        runBlocking {
            val openGateB = CountDownLatch(1)
            val decA = FakeDecoder()
            val decB = FakeDecoder(openBlock = openGateB)
            val recorder = Recorder(decA, decB)
            val engine = newEngine(recorder)

            assertSame(SeekPreviewEngine.InitResult.Opened, engine.begin("uriA"))
            val scope = CoroutineScope(Dispatchers.IO)
            try {
                val endA = engine.end() // signal attaches to A's generation
                val bInit = scope.async { engine.begin("uriB") }
                awaitLatch(decA.closeEntered, "A close runs before B open")
                awaitLatch(decB.openEntered, "B open parked")

                // endA completes as soon as A's close finishes — it must not
                // wait for B's still-parked open.
                kotlinx.coroutines.withTimeout(10_000) { endA.await() }
                assertFalse("B init still unresolved while open parked", bInit.isCompleted)
            } finally {
                openGateB.countDown()
                scope.cancel()
            }
            engine.release().await()
            recorder.assertAllClosedOnce()
        }

    @Test
    fun `caller cancel of queued begin drops token without creating a decoder`() = runBlocking {
        val decodeGate = CountDownLatch(1)
        val decA = FakeDecoder(decodeBlock = decodeGate)
        val recorder = Recorder(decA) // B must NEVER be created
        val engine = newEngine(recorder)

        assertSame(SeekPreviewEngine.InitResult.Opened, engine.begin("uriA"))
        val scope = CoroutineScope(Dispatchers.IO)
        try {
            val parkedExtract = scope.async { engine.extract("uriA", 0L) }
            awaitLatch(decA.decodeEntered, "decode parked so B stays queued")

            // UNDISPATCHED guarantees registration on this thread before launch
            // returns — submission order is deterministic.
            val bInit = scope.async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                engine.begin("uriB")
            }
            // B is queued as latest control (not yet executing). cancelAndJoin
            // proves the CE cleanup fully finished before the native gate
            // opens below — a bare cancel()+await() could race the cleanup.
            bInit.cancelAndJoin()
            try {
                bInit.await()
                fail("cancelled begin await must throw CE")
            } catch (_: CancellationException) {
            }
        } finally {
            decodeGate.countDown()
            scope.cancel()
        }
        engine.release().await()
        // Only A's decoder was ever created — B's queued token was dropped.
        assertEquals(1, recorder.callCount.get())
        recorder.assertAllClosedOnce()
    }

    @Test
    fun `caller cancel of active open forbids install, orphan closes, newer begin unaffected`() =
        runBlocking {
            val openGate = CountDownLatch(1)
            val decA = FakeDecoder(openBlock = openGate)
            val decB = FakeDecoder()
            val recorder = Recorder(decA, decB)
            val engine = newEngine(recorder)

            val scope = CoroutineScope(Dispatchers.IO)
            val aInit: Deferred<SeekPreviewEngine.InitResult>
            try {
                aInit = scope.async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    engine.begin("uriA")
                }
                awaitLatch(decA.openEntered, "A open parked mid-native")
                // cancelAndJoin: the CE cleanup (installForbidden) is fully
                // registered before the gate opens and open() returns.
                aInit.cancelAndJoin()
                try {
                    aInit.await()
                    fail("cancelled begin await must throw CE")
                } catch (_: CancellationException) {
                }
            } finally {
                openGate.countDown()
                scope.cancel()
            }
            // A's decoder returns from open, is forbidden → closed, not installed
            awaitLatch(decA.closedLatch, "cancelled open's decoder must close")
            assertEquals(1, decA.closeCount.get())
            // Newer begin is unaffected and installs fine
            assertSame(SeekPreviewEngine.InitResult.Opened, engine.begin("uriB"))
            assertEquals(42, engine.extract("uriB", 0L)!!.frame)
            engine.release().await()
            recorder.assertAllClosedOnce()
        }

    @Test
    fun `cancel after install retires only that session, queued newer begin survives`() =
        runBlocking {
            val openGateA = CountDownLatch(1)
            val decodeGate = CountDownLatch(1)
            val decA = FakeDecoder(openBlock = openGateA, decodeBlock = decodeGate)
            val decB = FakeDecoder()
            val recorder = Recorder(decA, decB)
            val engine = newEngine(recorder)

            // HeldDispatcher makes the "installed but not yet resumed" window
            // deterministic. A's OPEN gate guarantees begin actually suspends
            // at init.await() — a fast lane can never complete the open before
            // the first step, so the held queue is provably non-empty later.
            val held = HeldDispatcher()
            val heldScope = CoroutineScope(held)
            val ioScope = CoroutineScope(Dispatchers.IO)
            val aInit = heldScope.async { engine.begin("uriA") }
            try {
                held.step() // begin registers + suspends (open gate still closed)
                openGateA.countDown() // lane: open returns → installs A → init fires

                // A is INSTALLED and its resumption is parked in the held queue.
                val deadline = System.currentTimeMillis() + 10_000
                while (!(decA.openCount.get() >= 1 && held.hasPending())) {
                    assertTrue(
                        "install + queued resumption must settle",
                        System.currentTimeMillis() < deadline,
                    )
                    Thread.sleep(10)
                }

                // Park A's session decode on the lane…
                val parkedExtract = ioScope.async { engine.extract("uriA", 0L) }
                awaitLatch(decA.decodeEntered, "session decode parks the lane")

                // …register a NEWER begin B deterministically behind it…
                val bInit = ioScope.async(start = CoroutineStart.UNDISPATCHED) {
                    engine.begin("uriB")
                }
                assertFalse("B must still be parked while the lane is busy", bInit.isCompleted)

                // …and only NOW cancel A (installed, resumption still held). The
                // old-cancel must NOT supersede/drop B nor bump the generation.
                aInit.cancel()
                held.releaseAll()
                try {
                    aInit.await()
                    fail("cancelled begin must rethrow CE")
                } catch (_: CancellationException) {
                }

                // Free the lane: the stale decode replies null; B's open then
                // closes retired A as its predecessor and installs B.
                decodeGate.countDown()
                assertSame(
                    "newer queued begin survives the old caller's cancel",
                    SeekPreviewEngine.InitResult.Opened,
                    withTimeout(10_000) { bInit.await() },
                )
                assertNull(
                    "A's in-flight extract is stale (B bumped the generation), " +
                        "but the REQUEST itself was never erased by A's cancel",
                    withTimeout(10_000) { parkedExtract.await() },
                )

                awaitLatch(decA.closedLatch, "retired A closed exactly once by B's open")
                assertEquals(1, decA.closeCount.get())
                // B's session is fully functional afterwards.
                assertEquals(42, withTimeout(10_000) { engine.extract("uriB", 0L) }!!.frame)
                engine.release().await()
                recorder.assertAllClosedOnce()
            } finally {
                openGateA.countDown()
                decodeGate.countDown()
                // HeldDispatcher cleanup order: cancel the scope, drain the
                // held queue, then join with a bound — a stuck continuation
                // must never strand the test JVM.
                ioScope.cancel()
                heldScope.cancel()
                held.releaseAll()
                withTimeout(10_000) { aInit.join() }
            }
        }

    @Test
    fun `newer submitted target makes parked in-flight decode unpublishable immediately`() =
        runBlocking {
            val decodeGate = CountDownLatch(1)
            val dec = FakeDecoder(decodeBlock = decodeGate)
            val recorder = Recorder(dec)
            val engine = newEngine(recorder)

            assertSame(SeekPreviewEngine.InitResult.Opened, engine.begin("uriA"))
            val scope = CoroutineScope(Dispatchers.IO)
            try {
                val first = scope.async { engine.extract("uriA", 100L) }
                awaitLatch(dec.decodeEntered, "first decode parked")
                // UNDISPATCHED: the newer target registers synchronously, so
                // the invalidation below is deterministic, not scheduling luck.
                val second = scope.async(start = CoroutineStart.UNDISPATCHED) {
                    engine.extract("uriA", 200L)
                }
                // Old in-flight reply completes null immediately (newer target
                // submitted), even though its decode is still parked.
                assertNull(withTimeout(10_000) { first.await() })
            } finally {
                decodeGate.countDown()
                scope.cancel()
            }
            engine.release().await()
        }

    @Test
    fun `conflated pending requests decode only the newest target`() = runBlocking {
        val decodeGate = CountDownLatch(1)
        val dec = FakeDecoder(decodeBlock = decodeGate)
        val recorder = Recorder(dec)
        val engine = newEngine(recorder)

        assertSame(SeekPreviewEngine.InitResult.Opened, engine.begin("uriA"))
        val scope = CoroutineScope(Dispatchers.IO)
        val r1: Deferred<SeekPreviewEngine.Extracted<Int>?>
        val r2: Deferred<SeekPreviewEngine.Extracted<Int>?>
        val r3: Deferred<SeekPreviewEngine.Extracted<Int>?>
        try {
            r1 = scope.extractAsync(engine, "uriA", 1000L)
            awaitLatch(dec.decodeEntered, "first decode parked")
            // Submit while inflight parked → conflation keeps only the newest
            r2 = scope.async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                engine.extract("uriA", 2000L)
            }
            r3 = scope.async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                engine.extract("uriA", 3000L)
            }
            // Resolve INSIDE the try — awaiting scope children after the
            // finally's scope.cancel() would kill them with
            // JobCancellationException instead of the real outcomes.
            decodeGate.countDown()
            assertNull(withTimeout(10_000) { r1.await() }) // older in-flight invalidated
            assertNull(withTimeout(10_000) { r2.await() }) // superseded pending never decoded
            assertEquals(42, withTimeout(10_000) { r3.await() }?.frame)
            assertEquals(listOf(1000L, 3000L), dec.decodePositions.toList())
            engine.release().await()
            recorder.assertAllClosedOnce()
        } finally {
            decodeGate.countDown()
            scope.cancel()
        }
    }

    @Test
    fun `cancelled extract awaiter removes its own request and does not poison the mailbox`() =
        runBlocking {
            val decodeGate = CountDownLatch(1)
            val dec = FakeDecoder(decodeBlock = decodeGate)
            val recorder = Recorder(dec)
            val engine = newEngine(recorder)

            assertSame(SeekPreviewEngine.InitResult.Opened, engine.begin("uriA"))
            val scope = CoroutineScope(Dispatchers.IO)
            val first: Deferred<SeekPreviewEngine.Extracted<Int>?>
            try {
                first = scope.extractAsync(engine, "uriA", 100L)
                awaitLatch(dec.decodeEntered, "decode parked")
                // UNDISPATCHED: doomed registers before launch returns, so
                // invalidating the parked decode is deterministic.
                val doomed = scope.async(start = CoroutineStart.UNDISPATCHED) {
                    engine.extract("uriA", 200L)
                }
                // Join the cancellation so its CE cleanup (slot removal) has
                // fully registered BEFORE the native gate opens below.
                doomed.cancelAndJoin()
            } finally {
                decodeGate.countDown()
                scope.cancel()
            }
            assertNull(withTimeout(10_000) { first.await() }) // parked decode invalidated
            // Engine still functional
            assertEquals(42, withTimeout(10_000) { engine.extract("uriA", 300L) }?.frame)
            engine.release().await()
        }

    @Test
    fun `control storm during parked decode keeps queue and live decoders bounded`() =
        runBlocking {
            val decodeGate = CountDownLatch(1)
            val dec = FakeDecoder(decodeBlock = decodeGate)
            val recorder = Recorder(dec)
            val engine = newEngine(recorder)

            assertSame(SeekPreviewEngine.InitResult.Opened, engine.begin("uriA"))
            val scope = CoroutineScope(Dispatchers.IO)
            val finalInit: Deferred<SeekPreviewEngine.InitResult>
            try {
                val parked = scope.async { engine.extract("uriA", 0L) }
                awaitLatch(dec.decodeEntered, "decode parked")

                // Storm: 50 extracts + 20 begin/end pairs while native parked.
                // Registration order must be deterministic → UNDISPATCHED.
                repeat(50) { i ->
                    scope.async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                        engine.extract("uriA", i * 10L)
                    }
                }
                repeat(20) { i ->
                    val b = scope.async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                        engine.begin("uri$i")
                    }
                    engine.end()
                    b // stale begins resolve immediately
                }
            finalInit = scope.async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                engine.begin("uriZ")
            }
            // Resolve INSIDE the try — the finally's scope.cancel() would
            // kill the scope-registered finalInit mid-await.
            decodeGate.countDown()
            // At most one live decoder throughout: the parked session decode
            // blocked everything else; stale opens were skipped.
            assertSame(
                "last registered begin must resolve as the final session",
                SeekPreviewEngine.InitResult.Opened,
                withTimeout(10_000) { finalInit.await() },
            )
            assertTrue(
                "factory calls must stay bounded during a storm: ${recorder.callCount.get()}",
                recorder.callCount.get() <= 3,
            )
            engine.release().await()
            recorder.assertAllClosedOnce()
        } finally {
            decodeGate.countDown()
            scope.cancel()
        }
    }

    @Test
    fun `normal decode failure propagates, closes session decoder, next extract recovers`() =
        runBlocking {
            val fail = RuntimeException("decode error")
            val decA = FakeDecoder(decodeThrows = fail)
            val decB = FakeDecoder()
            val recorder = Recorder(decA, decB)
            val engine = newEngine(recorder)

            assertSame(SeekPreviewEngine.InitResult.Opened, engine.begin("uriA"))
            try {
                engine.extract("uriA", 0L)
                fail("decode error must reach the caller")
            } catch (t: Throwable) {
                assertSame(fail, t.engineReal())
            }
            awaitLatch(decA.closedLatch, "poisoned session decoder must close")
            assertEquals(1, decA.closeCount.get())
            // Same session key falls back to an ephemeral decoder
            assertEquals(42, engine.extract("uriA", 5L)!!.frame)
            engine.release().await()
            recorder.assertAllClosedOnce()
        }

    @Test
    fun `ephemeral decode success but close failure returns no frame and seals`() = runBlocking {
        val boom = RuntimeException("ephemeral close failed")
        val dec = FakeDecoder(closeThrows = boom)
        val recorder = Recorder(dec)
        val pool = CountingPool(DaemonLanePool(2))
        val engine = newEngine(recorder, pool)

        try {
            engine.extract("uriA", 0L) // ephemeral decode succeeds natively
            fail("close failure must not deliver a frame")
        } catch (t: Throwable) {
            assertSame("close failure delivered instead of the frame", boom, t.engineReal())
        }
        // close attempted exactly once, never retried
        assertEquals(1, dec.closeCount.get())
        // sealed: exceptional shutdown, begin Closed, lane isolated
        try {
            engine.shutdown.await()
            fail("shutdown must complete exceptionally")
        } catch (t: Throwable) {
            assertSame(boom, t.engineReal())
        }
        assertSame(SeekPreviewEngine.InitResult.Closed, engine.begin("uriB"))
        assertEquals("unknown native release state → lane never reused", 0, pool.givenBack.get())
        recorder.assertAllClosedOnce()
    }

    @Test
    fun `ephemeral decode failure delivers fast, close failure surfaces via shutdown`() =
        runBlocking {
            val decodeBoom = RuntimeException("decode failed")
            val closeBoom = RuntimeException("close failed too")
            val dec = FakeDecoder(decodeThrows = decodeBoom, closeThrows = closeBoom)
            val recorder = Recorder(dec)
            val engine = newEngine(recorder)

            try {
                engine.extract("uriA", 0L)
                fail("decode failure must reach the caller")
            } catch (t: Throwable) {
                assertSame("decode failure is delivered as-is (fast-fail primary)", decodeBoom, t.engineReal())
                assertFalse(
                    "fast-fail contract: the cleanup failure is NOT synchronously " +
                        "attached — it is observable via shutdown instead",
                    t.suppressed.contains(closeBoom),
                )
            }
            // The cleanup close runs on the lane thread AFTER the reply was
            // delivered — do not assert its count synchronously. Awaiting the
            // exceptional shutdown first guarantees the close (and its
            // failure-triggered seal) has finished.
            try {
                engine.shutdown.await()
                fail("close failure must seal shutdown exceptionally")
            } catch (t: Throwable) {
                assertSame(closeBoom, t.engineReal())
            }
            assertEquals(1, dec.closeCount.get())
            recorder.assertAllClosedOnce()
        }

    @Test
    fun `close failure during post-release drain never strands the parked caller`() =
        runBlocking {
            val decodeGate = CountDownLatch(1)
            val closeBoom = RuntimeException("session close failed")
            val dec = FakeDecoder(decodeBlock = decodeGate, closeThrows = closeBoom)
            val recorder = Recorder(dec)
            val engine = newEngine(recorder)
            val scope = CoroutineScope(Dispatchers.IO)

            assertSame(SeekPreviewEngine.InitResult.Opened, engine.begin("uriA"))
            try {
                val reply: Deferred<SeekPreviewEngine.Extracted<Int>?> = scope.async {
                    engine.extract("uriA", 0L)
                }
                awaitLatch(dec.decodeEntered, "decode parked in native")

                // release() returns fast while native is parked (non-blocking)
                val elapsed = measureTimeMillis { engine.release() }
                assertTrue("release must not wait for native, took ${elapsed}ms", elapsed < 1_000)
                assertFalse(engine.shutdown.isCompleted)

                decodeGate.countDown()
                // The parked caller resolves (null — released), never hangs; the
                // close failure seals but finds nothing left to strand.
                assertNull(withTimeout(10_000) { reply.await() })
                try {
                    withTimeout(10_000) { engine.shutdown.await() }
                    fail("close failure must seal shutdown exceptionally")
                } catch (t: Throwable) {
                    assertSame(closeBoom, t.engineReal())
                }
                assertEquals(1, dec.closeCount.get())
            } finally {
                // Every gate opens and the helper scope is cancelled on ANY
                // exit — a failed assertion must not strand the lane thread.
                decodeGate.countDown()
                scope.cancel()
            }
            recorder.assertAllClosedOnce()
        }

    @Test
    fun `rapid session churn never overlaps native calls and closes every created decoder`() =
        runBlocking {
            DepthProbe.depth.set(0)
            DepthProbe.maxDepth.set(0)
            // Each decoder's decode is PARKED: UNDISPATCHED only proves
            // registration, NOT that the worker hasn't decoded — the entered
            // handshake proves the decode is claimed-and-incomplete when end()
            // bumps the generation, making the later null replies guaranteed.
            val gates = Array(10) { CountDownLatch(1) }
            val decoders = Array(10) { i -> DepthProbe(FakeDecoder(decodeBlock = gates[i])) }
            val recorder = Recorder(*decoders)
            val engine = newEngine(recorder)
            val scope = CoroutineScope(Dispatchers.IO)

            val replies = mutableListOf<Deferred<SeekPreviewEngine.Extracted<Int>?>>()
            try {
                decoders.forEachIndexed { i, _ ->
                    withTimeout(10_000) { engine.begin("uri$i") }
                    replies += scope.async(start = CoroutineStart.UNDISPATCHED) {
                        engine.extract("uri$i", i * 100L)
                    }
                    awaitLatch(decoders[i].inner.decodeEntered, "extract $i decode parked")
                    engine.end() // generation bumped while decode provably parked
                    gates[i].countDown()
                }
                awaitLatch(recorder.allCreated, "all queued begins processed")
                // Resolve INSIDE the try — the finally's scope.cancel() would
                // kill the scope-registered replies mid-await.
                gates.forEach { it.countDown() }
                withTimeout(10_000) { engine.release().await() } // shutdown must really finish first
                replies.forEach { assertNull(withTimeout(10_000) { it.await() }) }
                recorder.assertAllClosedOnce()
                assertTrue(
                    "decode/close must never run concurrently: maxDepth=${DepthProbe.maxDepth.get()}",
                    DepthProbe.maxDepth.get() <= 1,
                )
                assertTrue(recorder.created.isNotEmpty())
            } finally {
                gates.forEach { it.countDown() }
                scope.cancel()
            }
        }

    @Test
    fun `double release is idempotent`() = runBlocking {
        val recorder = Recorder(FakeDecoder())
        val engine = newEngine(recorder)

        assertSame(SeekPreviewEngine.InitResult.Opened, engine.begin("uriA"))
        assertEquals(42, engine.extract("uriA", 0L)!!.frame)
        val r1 = engine.release()
        val r2 = engine.release()
        assertSame(r1, r2)
        r1.await()

        recorder.assertAllClosedOnce()
    }

    @Test
    fun `released engine begin is Closed, new engine recovers capacity`() = runBlocking {
        val pool = DaemonLanePool(1)
        val recA = Recorder(FakeDecoder())
        val a = newEngine(recA, pool)

        assertSame(SeekPreviewEngine.InitResult.Opened, a.begin("uA"))
        a.release().await()
        // Same engine must NOT resurrect
        assertSame(SeekPreviewEngine.InitResult.Closed, a.begin("uB"))
        // New engine on same pool acquires the returned lane
        val recC = Recorder(FakeDecoder())
        val c = newEngine(recC, pool)
        assertSame(SeekPreviewEngine.InitResult.Opened, c.begin("uC"))
        c.release().await()
        recA.assertAllClosedOnce()
        recC.assertAllClosedOnce()
    }

    @Test
    fun `NoCapacity engine stays unqueued and can retry after a lane frees`() = runBlocking {
        val pool = DaemonLanePool(1)
        val recA = Recorder(FakeDecoder())
        val a = newEngine(recA, pool)
        val recB = Recorder(FakeDecoder())
        val b = newEngine(recB, pool)

        assertSame(SeekPreviewEngine.InitResult.Opened, a.begin("uA"))
        assertSame(SeekPreviewEngine.InitResult.NoCapacity, b.begin("uB"))
        assertNull(b.extract("uB", 0L))
        assertEquals(0, recB.callCount.get())
        // Not released — engine B must be retryable once capacity frees
        a.release().await()
        assertSame(SeekPreviewEngine.InitResult.Opened, b.begin("uB"))
        b.release().await()
        recA.assertAllClosedOnce()
        recB.assertAllClosedOnce()
    }

    @Test
    fun `end on engine that never acquired a lane completes immediately`() = runBlocking {
        val pool = DaemonLanePool(0) // zero capacity
        val recorder = Recorder()
        val engine = newEngine(recorder, pool)

        val t = measureTimeMillis {
            engine.end().await()
        }
        assertTrue("end with nothing to close must be immediate", t < 5_000)
        engine.release().await()
        assertEquals(0, recorder.callCount.get())
    }

    // ---------- fast-fail cleanup contract (B5) ----------

    @Test
    fun `session decode failure delivers fast, close failure surfaces via fixed signal and shutdown`() =
        runBlocking {
            val closeGate = CountDownLatch(1)
            val decodeBoom = RuntimeException("session decode failed")
            val closeBoom = RuntimeException("session close failed")
            val dec = FakeDecoder(decodeThrows = decodeBoom, closeBlock = closeGate, closeThrows = closeBoom)
            val recorder = Recorder(dec)
            val engine = newEngine(recorder)

            try {
                assertSame(
                    SeekPreviewEngine.InitResult.Opened,
                    withTimeout(10_000) { engine.begin("uriA") },
                )
                try {
                    withTimeout(10_000) { engine.extract("uriA", 0L) }
                    fail("decode failure must reach the caller")
                } catch (t: Throwable) {
                    assertSame("original decode failure delivered immediately", decodeBoom, t.engineReal())
                    assertFalse(t.suppressed.contains(closeBoom))
                }

                // end() AFTER the failure relates this caller to the poisoned
                // resource's FIXED close signal (whether it lands before or after
                // the detach+claim lock section, the same deferred comes back).
                val endSignal = engine.end()
                try {
                    awaitLatch(dec.closeEntered, "poisoned close entered + parked")
                    // Reply already resolved with the ORIGINAL failure while the
                    // cleanup close is still parked — fast-fail, no waiting.
                    assertFalse("shutdown must not complete while cleanup close parked", engine.shutdown.isCompleted)
                    assertFalse("end signal must not complete while cleanup close parked", endSignal.isCompleted)
                } finally {
                    closeGate.countDown()
                }
                try {
                    withTimeout(10_000) { endSignal.await() }
                    fail("close failure must reach end waiters")
                } catch (t: Throwable) {
                    assertSame(closeBoom, t.engineReal())
                }
                try {
                    withTimeout(10_000) { engine.shutdown.await() }
                    fail("close failure must seal shutdown exceptionally")
                } catch (t: Throwable) {
                    assertSame(closeBoom, t.engineReal())
                }
                assertEquals(1, dec.closeCount.get())
                recorder.assertAllClosedOnce()
            } finally {
                // Whole-body cleanup: even if a fast-fail assertion above hangs
                // or fails, the parked native gate must still be opened.
                closeGate.countDown()
            }
        }

    @Test
    fun `session open failure delivers fast, close failure surfaces via shutdown`() = runBlocking {
        val closeGate = CountDownLatch(1)
        val openBoom = RuntimeException("native setDataSource failed")
        val closeBoom = RuntimeException("cleanup close failed")
            val dec = FakeDecoder(openThrows = openBoom, closeBlock = closeGate, closeThrows = closeBoom)
            val recorder = Recorder(dec)
            val engine = newEngine(recorder)

            try {
                try {
                    withTimeout(10_000) { engine.begin("uriA") }
                    fail("open failure must reach begin caller")
                } catch (t: Throwable) {
                    assertSame("original open failure delivered immediately", openBoom, t.engineReal())
                    assertFalse(t.suppressed.contains(closeBoom))
                }
                try {
                    // init already delivered while the orphan cleanup close is parked
                    awaitLatch(dec.closeEntered, "orphan close entered + parked")
                    assertFalse("shutdown must not complete while cleanup close parked", engine.shutdown.isCompleted)
                } finally {
                    closeGate.countDown()
                }
                try {
                    withTimeout(10_000) { engine.shutdown.await() }
                    fail("close failure must seal shutdown exceptionally")
                } catch (t: Throwable) {
                    assertSame(closeBoom, t.engineReal())
                }
                assertEquals(1, dec.closeCount.get())
                recorder.assertAllClosedOnce()
            } finally {
                closeGate.countDown()
            }
        }

    @Test
    fun `ephemeral open failure delivers fast while cleanup close is parked`() = runBlocking {
        val closeGate = CountDownLatch(1)
        val openBoom = RuntimeException("ephemeral open failed")
        val closeBoom = RuntimeException("ephemeral close failed")
            val dec = FakeDecoder(openThrows = openBoom, closeBlock = closeGate, closeThrows = closeBoom)
            val recorder = Recorder(dec)
            val engine = newEngine(recorder)

            try {
                try {
                    withTimeout(10_000) { engine.extract("uriA", 0L) }
                    fail("open failure must reach extract caller")
                } catch (t: Throwable) {
                    assertSame("original open failure delivered immediately", openBoom, t.engineReal())
                    assertFalse(t.suppressed.contains(closeBoom))
                }
                try {
                    awaitLatch(dec.closeEntered, "ephemeral cleanup close entered + parked")
                    assertFalse("shutdown must not complete while cleanup close parked", engine.shutdown.isCompleted)
                } finally {
                    closeGate.countDown()
                }
                try {
                    withTimeout(10_000) { engine.shutdown.await() }
                    fail("close failure must seal shutdown exceptionally")
                } catch (t: Throwable) {
                    assertSame(closeBoom, t.engineReal())
                }
                assertEquals(1, dec.closeCount.get())
                recorder.assertAllClosedOnce()
            } finally {
                closeGate.countDown()
            }
        }

    // ---------- adopted close-signal matrix (B5) ----------

    @Test
    fun `end during parked factory adopts command signal, completes when resource closes`() =
        runBlocking {
            val factoryGate = CountDownLatch(1)
            val factoryEntered = CountDownLatch(1)
            val dec = FakeDecoder()
            val engine = SeekPreviewEngine<Int>(
                decoderFactory = { _ ->
                    factoryEntered.countDown()
                    factoryGate.await()
                    dec
                },
                lanePool = DaemonLanePool(1),
            )
            val scope = CoroutineScope(Dispatchers.IO)
            val aInit = scope.async { engine.begin("uriA") }
            awaitLatch(factoryEntered, "factory parked before any resource exists")

            try {
                // end BEFORE the factory returned: attaches to the OPEN
                // COMMAND's signal; repeated ends share it (constant state).
                val e1 = engine.end()
                val e2 = engine.end()
                assertSame("repeated ends before adoption share the command signal", e1, e2)
                assertFalse(e1.isCompleted)
                factoryGate.countDown()
                // factory returns → res created → signal ADOPTED by the res →
                // open runs but the end-bumped epoch supersedes it → orphan
                // close completes the ADOPTED signal.
                assertSame(
                    "open superseded by the pre-adoption end",
                    SeekPreviewEngine.InitResult.Superseded,
                    withTimeout(10_000) { aInit.await() },
                )
                withTimeout(10_000) { e1.await() } // completes Unit at close
            } finally {
                factoryGate.countDown()
                scope.cancel()
            }
            assertEquals(1, dec.closeCount.get())
            engine.release().await()
        }

    @Test
    fun `repeated end across adoption keeps constant signals that complete together`() =
        runBlocking {
            val factoryGate = CountDownLatch(1)
            val factoryEntered = CountDownLatch(1)
            val closeGate = CountDownLatch(1)
            val dec = FakeDecoder(closeBlock = closeGate)
            val engine = SeekPreviewEngine<Int>(
                decoderFactory = { _ ->
                    factoryEntered.countDown()
                    factoryGate.await()
                    dec
                },
                lanePool = DaemonLanePool(1),
            )
            val scope = CoroutineScope(Dispatchers.IO)
            val aInit = scope.async { engine.begin("uriA") }
            awaitLatch(factoryEntered, "factory parked")

            try {
                val pre1 = engine.end()
                val pre2 = engine.end()
                assertSame("command-level signal is constant pre-adoption", pre1, pre2)
                assertFalse(pre1.isCompleted)
                factoryGate.countDown()

                // open superseded → orphan close parks; aInit resolves in the
                // open's finally, BEFORE the post-finally orphan close —
                // post-adoption ends then target the res's FIXED signal.
                assertSame(
                    SeekPreviewEngine.InitResult.Superseded,
                    withTimeout(10_000) { aInit.await() },
                )
                awaitLatch(dec.closeEntered, "orphan close entered + parked")
                val post1 = engine.end()
                val post2 = engine.end()
                assertSame("resource-level fixed signal is constant post-adoption", post1, post2)
                assertTrue(
                    "pre-adoption and post-adoption are distinct per-stage constants",
                    pre1 !== post1,
                )
                assertFalse(pre1.isCompleted)
                assertFalse(post1.isCompleted)

                closeGate.countDown()
                // Both constants complete together at the single real close.
                withTimeout(10_000) { pre1.await() }
                withTimeout(10_000) { post1.await() }
            } finally {
                factoryGate.countDown()
                closeGate.countDown()
                scope.cancel()
            }
            assertEquals(1, dec.closeCount.get())
            engine.release().await()
        }

    @Test
    fun `adopted end signal completes exceptionally when adopted close fails`() =
        runBlocking {
            val factoryGate = CountDownLatch(1)
            val factoryEntered = CountDownLatch(1)
            val closeGate = CountDownLatch(1)
            val closeBoom = RuntimeException("adopted close failed")
            val dec = FakeDecoder(closeBlock = closeGate, closeThrows = closeBoom)
            val engine = SeekPreviewEngine<Int>(
                decoderFactory = { _ ->
                    factoryEntered.countDown()
                    factoryGate.await()
                    dec
                },
                lanePool = DaemonLanePool(1),
            )
            val scope = CoroutineScope(Dispatchers.IO)
            val aInit = scope.async { engine.begin("uriA") }
            awaitLatch(factoryEntered, "factory parked")

            try {
                val adoptedEnd = engine.end() // pre-adoption command signal
                factoryGate.countDown()
                assertSame(
                    SeekPreviewEngine.InitResult.Superseded,
                    withTimeout(10_000) { aInit.await() },
                )
                awaitLatch(dec.closeEntered, "orphan close entered + parked")
                assertFalse(adoptedEnd.isCompleted)
                closeGate.countDown()
                try {
                    withTimeout(10_000) { adoptedEnd.await() }
                    fail("adopted signal must complete exceptionally on close failure")
                } catch (t: Throwable) {
                    assertSame(closeBoom, t.engineReal())
                }
                try {
                    withTimeout(10_000) { engine.shutdown.await() }
                    fail("close failure must seal shutdown exceptionally")
                } catch (t: Throwable) {
                    assertSame(closeBoom, t.engineReal())
                }
            } finally {
                factoryGate.countDown()
                closeGate.countDown()
                scope.cancel()
            }
            assertEquals(1, dec.closeCount.get())
        }

    // ---------- claimed-orphan / retirement invariants (B7) ----------

    @Test
    fun `session decode failure then end and release closes exactly once and blocks signals`() =
        runBlocking {
            // The claimed-orphan invariant: a poisoned session is detached and
            // CLAIMED in one lock, so end() and release() attach to its fixed
            // close signal and only complete after the real close — never
            // "no resource" while a close is imminent, never a second close.
            val closeGate = CountDownLatch(1)
            val decodeBoom = RuntimeException("session decode failed")
            val dec = FakeDecoder(decodeThrows = decodeBoom, closeBlock = closeGate)
            val recorder = Recorder(dec)
            val engine = newEngine(recorder)

            try {
                assertSame(
                    SeekPreviewEngine.InitResult.Opened,
                    withTimeout(10_000) { engine.begin("uriA") },
                )
                try {
                    withTimeout(10_000) { engine.extract("uriA", 0L) }
                    fail("decode failure must reach the caller")
                } catch (t: Throwable) {
                    assertSame(decodeBoom, t.engineReal())
                }
                // Attach BEFORE the parked close is released: under the claimed
                // contract the resource is already CLOSING-registered when the
                // reply returns, so end() gets the resource's fixed signal.
                val endSignal = engine.end()
                awaitLatch(dec.closeEntered, "poisoned close entered + parked")
                assertFalse("end signal must not complete while close parked", endSignal.isCompleted)
                val sd = engine.release()
                assertFalse("shutdown must not complete before close finishes", sd.isCompleted)

                closeGate.countDown()
                awaitLatch(dec.closedLatch, "poisoned close completed")
                assertEquals("exactly one native close", 1, dec.closeCount.get())
                withTimeout(10_000) { endSignal.await() }
                withTimeout(10_000) { sd.await() }
                recorder.assertAllClosedOnce()
            } finally {
                closeGate.countDown()
            }
        }

    @Test
    fun `open failure delivers init then end and release wait for the claimed orphan close`() =
        runBlocking {
            // Init is delivered while the orphan's native close is still
            // parked; the orphan was claimed BEFORE init fired, so end() and
            // release() attach to the orphan's fixed signal and only complete
            // after the real close — including a close FAILURE delivered
            // exceptionally.
            val closeGate = CountDownLatch(1)
            val openBoom = RuntimeException("native open failed")
            val closeBoom = RuntimeException("orphan close failed")
            val dec = FakeDecoder(openThrows = openBoom, closeBlock = closeGate, closeThrows = closeBoom)
            val recorder = Recorder(dec)
            val engine = newEngine(recorder)

            try {
                try {
                    withTimeout(10_000) { engine.begin("uriA") }
                    fail("open failure must reach begin caller")
                } catch (t: Throwable) {
                    assertSame("original open failure delivered immediately", openBoom, t.engineReal())
                    assertFalse(t.suppressed.contains(closeBoom))
                }
                // Claim happened before init delivery → end() sees the orphan.
                val endSignal = engine.end()
                awaitLatch(dec.closeEntered, "orphan close entered + parked")
                assertFalse("end signal must not complete while orphan close parked", endSignal.isCompleted)
                assertFalse("shutdown must not complete while orphan close parked", engine.shutdown.isCompleted)

                closeGate.countDown()
                try {
                    withTimeout(10_000) { endSignal.await() }
                    fail("orphan close failure must reach end waiters")
                } catch (t: Throwable) {
                    assertSame(closeBoom, t.engineReal())
                }
                try {
                    withTimeout(10_000) { engine.release().await() }
                    fail("orphan close failure must seal shutdown exceptionally")
                } catch (t: Throwable) {
                    assertSame(closeBoom, t.engineReal())
                }
                assertEquals(1, dec.closeCount.get())
                recorder.assertAllClosedOnce()
            } finally {
                closeGate.countDown()
            }
        }

    @Test
    fun `retired session closes before the pending request takes the ephemeral path`() =
        runBlocking {
            // Directional retirement must not starve even under pressure:
            // A's begin-awaiter is cancelled after install while a PENDING
            // extract is already registered behind a parked session decode.
            // When the lane frees, the drain must run the retired close
            // BEFORE servicing the pending request — the pending request
            // never decodes on the retired session (it takes the ephemeral
            // path) and the close never waits for the request stream.
            val openGateA = CountDownLatch(1)
            val decodeGate = CountDownLatch(1)
            val events = CopyOnWriteArrayList<String>()
            val decA = FakeDecoder(
                openBlock = openGateA,
                decodeBlock = decodeGate, // the session decode must STAY parked
                onClose = { events.add("A-close") },
            )
            // The ephemeral decoder for the pending request — its open is
            // observable in the same event log.
            val decEphemeral = FakeDecoder(onOpen = { events.add("ephemeral-open") })
            val recorder = Recorder(decA, decEphemeral)
            val engine = newEngine(recorder)
            val held = HeldDispatcher()
            val heldScope = CoroutineScope(held)
            val ioScope = CoroutineScope(Dispatchers.IO)

            // HeldDispatcher reproduces the "installed but not yet resumed"
            // window. The CLOSED open gate guarantees begin REALLY suspended
            // at init.await() before any install — without it the lane could
            // complete the whole open before the awaiter suspends, leaving
            // the held queue empty and the settle poll timing out.
            val aInit = heldScope.async { engine.begin("uriA") }
            try {
                held.step() // begin registers + suspends (open gate still closed)
                openGateA.countDown() // lane: open returns → installs A
                val deadline = System.currentTimeMillis() + 10_000
                while (!(decA.openCount.get() >= 1 && held.hasPending())) {
                    assertTrue(
                        "install + held resumption must settle",
                        System.currentTimeMillis() < deadline,
                    )
                    Thread.sleep(10)
                }

                // Park a decode of the installed session on the lane…
                val parkedExtract = ioScope.async { engine.extract("uriA", 0L) }
                awaitLatch(decA.decodeEntered, "session decode parks the lane")

                // …register the PENDING extract deterministically behind it…
                val pendingExtract = ioScope.async(start = CoroutineStart.UNDISPATCHED) {
                    engine.extract("uriA", 1_000L)
                }
                assertFalse(
                    "pending request must stay queued while the lane is busy",
                    pendingExtract.isCompleted,
                )

                // …and only NOW cancel A: retirement is requested, but the
                // worker is busy, so no close can run ahead of the pending
                // registration. The drain ordering is then what decides.
                aInit.cancel()
                held.releaseAll()
                try {
                    withTimeout(10_000) { aInit.await() }
                    fail("cancelled begin must rethrow CE")
                } catch (_: CancellationException) {
                }

                // Free the lane: parked decode replies null (superseded), the
                // SAME worker continues drain → retired close → pending.
                decodeGate.countDown()
                assertNull(
                    "parked in-flight decode is stale (pending bumped the target)",
                    withTimeout(10_000) { parkedExtract.await() },
                )
                assertEquals(
                    "pending request falls back to an ephemeral decoder",
                    42,
                    withTimeout(10_000) { pendingExtract.await() }?.frame,
                )
                assertEquals(
                    "retired close must run BEFORE the pending ephemeral open",
                    listOf("A-close", "ephemeral-open"),
                    events.toList(),
                )
                assertEquals(
                    "retired session must never serve ANOTHER decode (its only decode was the parked one)",
                    1,
                    decA.decodeCount.get(),
                )
                assertEquals("session + ephemeral decoders created", 2, recorder.created.size)
                engine.release().await()
                recorder.assertAllClosedOnce()
            } finally {
                // Whole-body cleanup: every gate opens, the helper scopes are
                // cancelled and the held queue is drained on ANY exit.
                openGateA.countDown()
                decodeGate.countDown()
                ioScope.cancel()
                heldScope.cancel()
                held.releaseAll()
                withTimeout(10_000) { aInit.join() }
            }
        }

    @Test
    fun `CloseSession failure seals engine and terminates queued B with the real failure`() =
        runBlocking {
            // Entry point: the queued CloseSession command FAILS while B's
            // open is already registered behind it. The close failure seals
            // the engine and is never swallowed — B's init gets the REAL
            // close exception (never a masked Closed), B's decoder is never
            // created, A's generation end signal completes exceptionally, and
            // the engine seals terminally.
            //
            // Determinism: the old mixed-interleaving version raced — between
            // end(A) and begin(B) the lane could run the close to completion
            // and seal first, so B saw Closed. Here A's close is PARKED inside
            // the worker (gated) when B registers, so the seal cannot happen
            // before B is parked at init.await().
        val closeGate = CountDownLatch(1)
        val closeBoom = RuntimeException("A close failed")
        val decA = FakeDecoder(closeBlock = closeGate, closeThrows = closeBoom)
        val decB = FakeDecoder() // must NEVER be created
        val recorder = Recorder(decA, decB)
        val engine = newEngine(recorder)
        val scope = CoroutineScope(Dispatchers.IO)

        assertSame(
            SeekPreviewEngine.InitResult.Opened,
            withTimeout(10_000) { engine.begin("uriA") },
        )
        // end attaches to A's FIXED per-generation close signal before any
        // close ran; repeated ends keep returning that same signal.
        val endSignal = engine.end()
        try {
            awaitLatch(decA.closeEntered, "A's close entered + parked")
            assertFalse("end signal must not complete while close parked", endSignal.isCompleted)
            assertSame("repeated end shares A's fixed close signal", endSignal, engine.end())

            // B registers while the predecessor close is parked — provably
            // AFTER the close was claimed, BEFORE any seal. UNDISPATCHED
            // guarantees the registration completed on this thread before
            // the gate opens below (a lazy IO dispatch could lose the race
            // against the seal and turn B's outcome into a benign Closed).
            val bInit = scope.async(start = CoroutineStart.UNDISPATCHED) {
                engine.begin("uriB")
            }
            assertFalse("B must stay parked while the lane is busy", bInit.isCompleted)

            closeGate.countDown()
            var bThrown: Throwable? = null
            try {
                withTimeout(10_000) { bInit.await() }
                fail("B must not open on top of a failed predecessor close")
            } catch (t: Throwable) {
                bThrown = t
            }
            assertSame("B receives the real predecessor close failure", closeBoom, bThrown!!.engineReal())
            assertEquals("B's decoder must never be created", 1, recorder.callCount.get())
            assertEquals(1, decA.closeCount.get())
            try {
                withTimeout(10_000) { endSignal.await() }
                fail("A's close failure must reach its generation's end waiters")
            } catch (t: Throwable) {
                assertSame(closeBoom, t.engineReal())
            }
            try {
                withTimeout(10_000) { engine.shutdown.await() }
                fail("engine must seal with the close failure")
            } catch (t: Throwable) {
                assertSame(closeBoom, t.engineReal())
            }
            assertSame(
                "sealed engine begin must be Closed",
                SeekPreviewEngine.InitResult.Closed,
                engine.begin("uriC"),
            )
            recorder.assertAllClosedOnce()
        } finally {
            closeGate.countDown()
            scope.cancel()
        }
    }

    @Test
    fun `begin after the engine sealed returns Closed without creating a decoder`() = runBlocking {
        // The OTHER deterministic interleaving of the old mixed test: the
        // predecessor close fully FAILS and seals BEFORE B ever registers —
        // B must then observe Closed (the seal is terminal) and no decoder
        // is created for it.
        val closeBoom = RuntimeException("A close failed")
        val decA = FakeDecoder(closeThrows = closeBoom)
        val decB = FakeDecoder() // must NEVER be created
        val recorder = Recorder(decA, decB)
        val engine = newEngine(recorder)

        assertSame(
            SeekPreviewEngine.InitResult.Opened,
            withTimeout(10_000) { engine.begin("uriA") },
        )
        engine.end()
        try {
            withTimeout(10_000) { engine.shutdown.await() }
            fail("close failure must seal shutdown exceptionally")
        } catch (t: Throwable) {
            assertSame(closeBoom, t.engineReal())
        }
        assertSame(SeekPreviewEngine.InitResult.Closed, engine.begin("uriB"))
        assertEquals("B's decoder must never be created", 1, recorder.callCount.get())
        assertEquals(1, decA.closeCount.get())
        recorder.assertAllClosedOnce()
    }

    // ---------- submit-rejection rollback (B8) ----------

    @Test
    fun `first-call end with rejected submit does not throw and stays retryable`() = runBlocking {
        // Fresh engine, FIRST signal ever is end() and the submit is
        // REJECTED: end() must not throw, its close signal must terminate
        // (nothing to close), and the atomic rollback must restore retryable
        // state — a later signal re-acquires a lane and the engine works.
        val recorder = Recorder(FakeDecoder())
        val engine = newEngine(recorder, RejectFirstSubmitsPool(1))

        val endSignal = engine.end() // must not throw despite the rejection
        withTimeout(10_000) { endSignal.await() } // signal terminates immediately

        assertSame(
            "rollback keeps the engine retryable",
            SeekPreviewEngine.InitResult.Opened,
            withTimeout(10_000) { engine.begin("uriA") },
        )
        assertEquals(42, withTimeout(10_000) { engine.extract("uriA", 0L) }!!.frame)
        engine.release().await()
        assertEquals(1, recorder.created.size)
        recorder.assertAllClosedOnce()
    }

    @Test
    fun `first-call release with rejected submit does not throw and shutdown terminates`() =
        runBlocking {
            // Fresh engine, FIRST signal ever is release() and every submit
            // is rejected: release() must not throw (fast), and its shutdown
            // signal must TERMINATE — exceptionally with the real rejection,
            // never hanging on a worker that was never scheduled. Terminal
            // state stays consistent afterwards.
            val engine = newEngine(Recorder(), RejectFirstSubmitsPool(Int.MAX_VALUE))

            val shutdown = engine.release() // must not throw despite the rejection
            try {
                withTimeout(10_000) { shutdown.await() }
                fail("shutdown must terminate exceptionally after submit rejection")
            } catch (t: Throwable) {
                assertTrue(t is IllegalStateException)
                assertEquals("submit rejected", t.message)
            }
            assertSame(
                "released engine answers Closed without scheduling anything",
                SeekPreviewEngine.InitResult.Closed,
                engine.begin("uriX"),
            )
            assertNull(engine.extract("uriX", 0L))
        }

    // ---------- submit rejection WITH held resources (B9 terminal policy) ----------

    @Test
    fun `end with rejected submit on an installed session terminates its close signal exceptionally`() =
        runBlocking {
            // Installed session + worker idle (drain exited) → end() must
            // schedule a worker; the submit is rejected. The session decoder
            // can never be natively closed (no worker, and signal callers
            // must never block on native calls), so the engine seals
            // TERMINALLY: the session's FIXED close signal terminates
            // exceptionally with the real rejection instead of hanging.
            val decA = FakeDecoder()
            val recorder = Recorder(decA)
            val pool = ScriptedRejectPool(rejectFrom = 1) // begin ok; end rejected
            val engine = newEngine(recorder, pool)

            assertSame(
                SeekPreviewEngine.InitResult.Opened,
                withTimeout(10_000) { engine.begin("uriA") },
            )
            // The worker must have FULLY returned from its task before the
            // next signal — not merely "submit returned" (enqueue-and-return).
            assertTrue("worker idle before end", pool.workerIdle.await(10, TimeUnit.SECONDS))
            val endSignal = engine.end() // must not throw despite the rejection
            try {
                withTimeout(10_000) { endSignal.await() }
                fail("a close that can never run must not leave its signal pending")
            } catch (t: Throwable) {
                assertTrue(t is IllegalStateException)
                assertEquals("submit rejected", t.message)
            }
            // Post-terminal ends return an ALREADY-TERMINATED signal
            // (CLOSED_SENTINEL, fresh instance by design — the per-generation
            // signal above stays the one true close observation point).
            try {
                withTimeout(10_000) { engine.end().await() }
                fail("post-terminal end must still be an already-terminated signal")
            } catch (_: Throwable) {
                // terminated — bounded, never pending
            }
            assertSame(
                "terminal engine begin answers Closed",
                SeekPreviewEngine.InitResult.Closed,
                engine.begin("uriB"),
            )
            assertNull(engine.extract("uriB", 0L))
            engine.epoch() // must not throw; released → -1
            assertEquals(-1L, engine.epoch())
            // Residual-native boundary: no worker existed, no native close ran.
            assertEquals("no native close ran after rejection", 0, decA.closeCount.get())
            assertEquals("isolated engine must NOT return its lane", 0, pool.givenBack.get())
        }

    @Test
    fun `end rejection on an installed session terminates every waiter and release stays idempotent`() =
        runBlocking {
            // INSTALLED SESSION, END is the rejected submit: end() schedules
            // the drain; the submit is rejected and the engine seals
            // terminally. shutdown terminates exceptionally with the real
            // rejection, EVERY waiter (the end-attached close signal
            // included) gets a result, and a LATER release() — which never
            // gets its own submit in this flow — is idempotent on the same
            // terminated Deferred. The direct release-rejection path is
            // covered by the dedicated test below. The lane stays leased
            // (capacity isolation).
            val decA = FakeDecoder()
            val recorder = Recorder(decA)
            val pool = ScriptedRejectPool(rejectFrom = 1) // begin ok; end rejected
            val engine = newEngine(recorder, pool)

            assertSame(
                SeekPreviewEngine.InitResult.Opened,
                withTimeout(10_000) { engine.begin("uriA") },
            )
            assertTrue("worker idle before end", pool.workerIdle.await(10, TimeUnit.SECONDS))
            // A generation waiter attached BEFORE the end: must be
            // terminated by the rollback, not left pending forever.
            val endSignal = engine.end()
            val shutdown1 = engine.release() // idempotent on the terminated shutdown
            // A second waiter resolved via runCatching: structured-concurrency
            // safe (the child never rethrows into runBlocking).
            val waiter2 = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { shutdown1.await() }
            }

            try {
                withTimeout(10_000) { shutdown1.await() }
                fail("shutdown must terminate exceptionally after submit rejection")
            } catch (t: Throwable) {
                assertTrue(t is IllegalStateException)
                assertEquals("submit rejected", t.message)
            }
            try {
                withTimeout(10_000) { endSignal.await() }
                fail("the generation close can never run — its signal must terminate exceptionally")
            } catch (t: Throwable) {
                assertTrue(t is IllegalStateException)
            }
            assertTrue("all waiters must have received a result", waiter2.isCompleted)
            assertTrue(waiter2.getCompleted().isFailure)
            assertSame(
                "repeat release returns the same terminated shutdown",
                shutdown1,
                engine.release(),
            )
            assertSame(
                "terminal engine begin answers Closed",
                SeekPreviewEngine.InitResult.Closed,
                engine.begin("uriX"),
            )
            assertNull(engine.extract("uriX", 0L))
            assertEquals("no native close ran after rejection", 0, decA.closeCount.get())
            assertEquals("isolated engine must NOT return its lane", 0, pool.givenBack.get())
        }

    @Test
    fun `release with rejected submit on an installed session terminates shutdown directly`() =
        runBlocking {
            // DIRECT release-rejection path (no end() first): installed
            // session, worker confirmed idle, then release() itself schedules
            // the drain and its submit is REJECTED. shutdown terminates
            // exceptionally with the real rejection, repeat release returns
            // the SAME terminated Deferred, begin answers Closed, extract
            // answers null, no native close runs (residual-native boundary),
            // and the lease is never returned (capacity isolation).
            val decA = FakeDecoder()
            val pool = ScriptedRejectPool(rejectFrom = 1) // begin ok; release rejected
            val engine = newEngine(Recorder(decA), pool)

            assertSame(
                SeekPreviewEngine.InitResult.Opened,
                withTimeout(10_000) { engine.begin("uriA") },
            )
            assertTrue("worker idle before release", pool.workerIdle.await(10, TimeUnit.SECONDS))

            val shutdown = engine.release() // must not throw despite the rejection
            try {
                withTimeout(10_000) { shutdown.await() }
                fail("shutdown must terminate exceptionally after submit rejection")
            } catch (t: Throwable) {
                assertTrue(t is IllegalStateException)
                assertEquals("submit rejected", t.message)
            }
            assertSame(
                "repeat release returns the same terminated shutdown",
                shutdown,
                engine.release(),
            )
            assertSame(
                "terminal engine begin answers Closed",
                SeekPreviewEngine.InitResult.Closed,
                engine.begin("uriX"),
            )
            assertNull(engine.extract("uriX", 0L))
            assertEquals("no native close ran after rejection", 0, decA.closeCount.get())
            assertEquals("isolated engine must NOT return its lane", 0, pool.givenBack.get())
        }

    @Test
    fun `rejected live-resource engine keeps its lane so the pool bound stays honest`() =
        runBlocking {
            // Capacity isolation: pool max = 1. Engine A holds a live session
            // when its submit is rejected — it keeps its lease forever, so
            // engine B degrades to NoCapacity instead of inheriting a lane
            // whose native resources may never be released.
            val decA = FakeDecoder()
            val pool = ScriptedRejectPool(rejectFrom = 1) // single lane
            val engineA = newEngine(Recorder(decA), pool)

            assertSame(
                SeekPreviewEngine.InitResult.Opened,
                withTimeout(10_000) { engineA.begin("uriA") },
            )
            assertTrue("worker idle before end", pool.workerIdle.await(10, TimeUnit.SECONDS))
            engineA.end() // rejected → engine A terminal, lane retained

            val engineB = newEngine(Recorder(), pool)
            assertSame(
                "engine B must degrade to NoCapacity — the bound is not faked",
                SeekPreviewEngine.InitResult.NoCapacity,
                engineB.begin("uriB"),
            )
            assertEquals("engine A must never give its lane back", 0, pool.givenBack.get())
        }

    @Test
    fun `a throwing logger never masks the rejection delivered to signals`() = runBlocking {
        // The rollback logs the rejection after delivering it: a logger that
        // throws must neither propagate into the signal caller nor replace
        // the rejection the signals already received.
        val pool = ScriptedRejectPool(rejectFrom = 0) // first submit already rejects
        val engine = newEngine(
            Recorder(),
            pool,
            logger = { throw RuntimeException("logger exploded") },
        )

        val shutdown = engine.release() // must not throw despite the logger
        try {
            withTimeout(10_000) { shutdown.await() }
            fail("shutdown must terminate exceptionally after submit rejection")
        } catch (t: Throwable) {
            assertEquals(
                "the REAL rejection reaches the signal, not the logger's exception",
                "submit rejected",
                t.message,
            )
        }
        // No resources were held (fresh engine) → the RETRYABLE rollback
        // applies and DOES return the lane; terminal isolation is covered by
        // the installed-session tests above.
        assertEquals(1, pool.givenBack.get())
    }
}
