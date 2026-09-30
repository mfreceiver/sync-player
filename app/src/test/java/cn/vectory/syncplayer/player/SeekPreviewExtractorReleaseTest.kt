package cn.vectory.syncplayer.player

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import cn.vectory.syncplayer.player.preview.DaemonLanePool
import cn.vectory.syncplayer.player.preview.PreviewLane
import cn.vectory.syncplayer.player.preview.PreviewLanePool
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.coroutines.CoroutineContext
import kotlin.system.measureTimeMillis

/**
 * Facade-level regression tests for [SeekPreviewExtractor].
 *
 * The mock retriever's `getFrameAtTime` uses a real latch to simulate the
 * real-world case that matters: a blocking native decode that does NOT
 * respond to coroutine cancellation. Covers: signal calls return without
 * waiting for native, no concurrent release, exactly-once release after
 * unblock, cache lifecycle identity (epoch isolation + release disabling
 * reads + 1KB minimum billing), CE-first propagation, the facade publish
 * barrier (old session/target results never land after a newer intent —
 * including pure cache-hit target switches), NoCapacity/submit-rejection
 * fast-fail, close-failure visibility via shutdown, the byte-budgeted
 * cache that never recycles bitmaps the UI may still hold, and the U3b
 * dedicated prefetch channel: UI/prefetch identity isolation (mailbox and
 * decoded-unpublished-window latches), session lifecycle (token barrier,
 * end→begin resume), synchronous single-slot admission, and full-pipeline
 * exception boundaries.
 */
@RunWith(RobolectricTestRunner::class)
// targetSdk 35 exceeds this Robolectric's max supported SDK, and the real
// Hilt Application needs AndroidKeyStore (unavailable on the JVM). The facade
// under test is constructed directly — boot the plain framework Application
// on SDK 34 instead of the app's DI graph.
@Config(sdk = [34], application = android.app.Application::class)
class SeekPreviewExtractorReleaseTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun newExtractor(
        retriever: MediaMetadataRetriever,
        poolSize: Int = 1,
        dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
        lanePool: PreviewLanePool = DaemonLanePool(poolSize),
        prefetchRetriever: MediaMetadataRetriever = retriever,
        /** Per-test isolated prefetch pool by default, so tests never couple
         * through the process-wide shared pool. The shared-pool tests inject
         * [SeekPreviewExtractor.sharedPrefetchPoolForTest] explicitly. */
        prefetchLanePool: PreviewLanePool = DaemonLanePool(maxLanes = 1),
        scale: ((Bitmap) -> Bitmap)? = null,
    ) = SeekPreviewExtractor(
        context = context,
        retrieverFactory = { retriever },
        prefetchRetrieverFactory = { prefetchRetriever },
        dispatcher = dispatcher,
        lanePool = lanePool,
        prefetchLanePool = prefetchLanePool,
        scale = scale,
    )

    /** Pool that never grants a lane — forces the engine's NoCapacity path. */
    private object NoLanePool : PreviewLanePool {
        override fun tryAcquire(): PreviewLane? = null
        override fun giveBack(lane: PreviewLane): Unit = error("never acquired")
    }

    /** Lane whose submit always rejects — forces fast-fail on the facade. */
    private class RejectingLane : PreviewLane {
        override fun submit(task: () -> Unit): Unit = throw IllegalStateException("submit rejected")
    }

    private object RejectingLanePool : PreviewLanePool {
        override fun tryAcquire(): PreviewLane? = RejectingLane()
        override fun giveBack(lane: PreviewLane): Unit = Unit
    }

    /**
     * Dispatcher that accepts tasks but never runs them: with a frozen
     * facade scope, a burst of prefetch calls can only be admitted by the
     * SYNCHRONOUS gate — every admitted call shows up as exactly one queued
     * task, every dropped call shows up as none.
     */
    private class HeldDispatcher : CoroutineDispatcher() {
        val tasks = ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks.add(block)
        }

        override fun isDispatchNeeded(context: CoroutineContext): Boolean = true
    }

    /**
     * Dispatcher that queues tasks until the test drains them, so the
     * decoded-but-unpublished window of a UI extraction can be observed
     * deterministically (the scale step is a queued task, not a race).
     */
    private class SteppingDispatcher : CoroutineDispatcher() {
        val pending = ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            pending.add(block)
        }

        override fun isDispatchNeeded(context: CoroutineContext): Boolean = true

        /** Runs queued tasks (including ones enqueued while draining) until quiet. */
        fun drainOnce() {
            var n = 0
            while (n < 10_000) {
                val task = pending.poll() ?: return
                task.run()
                n++
            }
        }
    }

    @Test
    fun `release without resources is a fast no-op`() = runBlocking {
        val retriever = mockk<MediaMetadataRetriever>(relaxed = true)
        val extractor = newExtractor(retriever)

        val elapsed = measureTimeMillis { extractor.release() }
        assertTrue("release() must return promptly, took ${elapsed}ms", elapsed < 200)
        withTimeout(5_000) { extractor.shutdownResult.await() }
        verify(exactly = 0) { retriever.release() }
    }

    @Test
    fun `release returns while decode is parked and releases exactly once after unblock`() = runBlocking {
        val decodeGate = CountDownLatch(1)
        val decodeEntered = CountDownLatch(1)
        val releaseCount = AtomicInteger(0)
        val retriever = mockk<MediaMetadataRetriever> {
            every { setDataSource(any<Context>(), any<Uri>()) } returns Unit
            every { getFrameAtTime(any(), any()) } answers {
                decodeEntered.countDown()
                // Explicit gate: block until the test opens it. A timed await
                // would silently release the decode after 10s and mask a
                // gate that was mistakenly never opened.
                decodeGate.await()
                Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            }
            every { release() } answers { releaseCount.incrementAndGet(); Unit }
        }
        val extractor = newExtractor(retriever)
        val scope = CoroutineScope(Dispatchers.IO)
        try {
            extractor.beginSession(Uri.parse("content://test/video"))
            val extractCall = scope.async {
                extractor.extractFrameAtPosition("content://test/video", 1_000L)
            }
            assertTrue("decode must enter native", decodeEntered.await(10, TimeUnit.SECONDS))

            // Non-blocking while native is parked; no concurrent release.
            val elapsed = measureTimeMillis { extractor.release() }
            assertTrue("release() must not wait for native, took ${elapsed}ms", elapsed < 1_000)
            assertEquals(0, releaseCount.get())

            // Cache reads disabled immediately upon release.
            assertNull(extractor.getCachedFrame("content://test/video", 1_000L))

            decodeGate.countDown()
            // Exactly once, eventually — after native returns.
            withTimeout(5_000) { extractor.shutdownResult.await() }
            verify(exactly = 1, timeout = 5_000) { retriever.release() }
            assertNull(extractCall.await())
        } finally {
            decodeGate.countDown()
            extractor.release()
            scope.cancel()
        }
    }

    @Test
    fun `endSession returns while decode is parked then releases exactly once`() = runBlocking {
        val decodeGate = CountDownLatch(1)
        val decodeEntered = CountDownLatch(1)
        val retriever = mockk<MediaMetadataRetriever> {
            every { setDataSource(any<Context>(), any<Uri>()) } returns Unit
            every { getFrameAtTime(any(), any()) } answers {
                decodeEntered.countDown()
                // Explicit gate — the test's finally releases it. A timed
                // await would silently let the decode through and mask a
                // gate mistakenly left closed.
                decodeGate.await()
                Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            }
            every { release() } returns Unit
        }
        val extractor = newExtractor(retriever)
        val scope = CoroutineScope(Dispatchers.IO)
        try {
            extractor.beginSession(Uri.parse("content://test/video"))
            scope.async { extractor.extractFrameAtPosition("content://test/video", 2_000L) }
            assertTrue("decode must enter native", decodeEntered.await(10, TimeUnit.SECONDS))

            val elapsed = measureTimeMillis { extractor.endSession() }
            assertTrue("endSession() must not wait for native, took ${elapsed}ms", elapsed < 1_000)

            decodeGate.countDown()
            verify(exactly = 1, timeout = 5_000) { retriever.release() }
        } finally {
            decodeGate.countDown()
            extractor.release()
            scope.cancel()
        }
    }

    @Test
    fun `double release is idempotent`() = runBlocking {
        val retriever = mockk<MediaMetadataRetriever>(relaxed = true)
        val extractor = newExtractor(retriever)

        extractor.release()
        extractor.release()
        withTimeout(5_000) { extractor.shutdownResult.await() }
        verify(exactly = 0) { retriever.release() }
    }

    @Test
    fun `cache is isolated per session and disabled after release`() = runBlocking {
        var frameCalls = 0
        val retriever = mockk<MediaMetadataRetriever> {
            every { setDataSource(any<Context>(), any<Uri>()) } returns Unit
            every { getFrameAtTime(any(), any()) } answers {
                frameCalls++
                Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            }
            every { release() } returns Unit
        }
        val extractor = newExtractor(retriever)
        try {
            // Ephemeral extract caches under session epoch 0.
            val first = extractor.extractFrameAtPosition("content://test/v", 1_000L)
            assertNotNull(first)
            assertSame(first, extractor.getCachedFrame("content://test/v", 1_000L))
            assertSame(first, extractor.getCachedFrame("content://test/v", 1_200L)) // 500ms bucket
            assertEquals(1, frameCalls)

            // Beginning a session isolates the cache: old entry is unreachable.
            extractor.beginSession(Uri.parse("content://test/v"))
            assertNull(extractor.getCachedFrame("content://test/v", 1_000L))
            val second = extractor.extractFrameAtPosition("content://test/v", 1_000L)
            assertNotNull(second)
            assertSame(second, extractor.getCachedFrame("content://test/v", 1_000L))
            assertEquals(2, frameCalls)

            // Release flips identity: hits are disabled immediately.
            extractor.release()
            assertNull(extractor.getCachedFrame("content://test/v", 1_000L))
            withTimeout(5_000) { extractor.shutdownResult.await() }
            verify(atLeast = 1) { retriever.release() }
        } finally {
            extractor.release()
        }
    }

    @Test
    fun `scaled bitmap obeys width height and total-pixel caps`() = runBlocking {
        val retriever = mockk<MediaMetadataRetriever> {
            every { setDataSource(any<Context>(), any<Uri>()) } returns Unit
            every { release() } returns Unit
            every { getFrameAtTime(any(), any()) } returns Bitmap.createBitmap(
                400, 300, Bitmap.Config.ARGB_8888,
            )
        }
        val extractor = newExtractor(retriever)
        try {
            val frame = extractor.extractFrameAtPosition("content://test/video", 0L)
            assertNotNull(frame)
            assertTrue(frame!!.width <= SeekPreviewExtractor.MAX_WIDTH)
            assertTrue(frame.height <= SeekPreviewExtractor.MAX_HEIGHT)
            assertTrue(frame.width.toLong() * frame.height <= SeekPreviewExtractor.MAX_PIXELS)
        } finally {
            extractor.release()
        }
    }

    @Test
    fun `cache bills at least 1KB so tiny bitmaps count`() = runBlocking {
        val retriever = mockk<MediaMetadataRetriever> {
            every { setDataSource(any<Context>(), any<Uri>()) } returns Unit
            every { release() } returns Unit
            every { getFrameAtTime(any(), any()) } returns Bitmap.createBitmap(
                1, 1, Bitmap.Config.ARGB_8888,
            )
        }
        val extractor = newExtractor(retriever)
        try {
            extractor.extractFrameAtPosition("content://test/video", 0L)
            // Byte-unit billing with a 1 KB floor: a 256-byte 1x1 bitmap
            // bills its real bytes' ceiling — never less than 1024.
            assertTrue(
                "1x1 bitmap must bill >= 1024 bytes",
                extractor.cacheSizeForTest() >= 1024,
            )
        } finally {
            extractor.release()
        }
    }

    @Test
    fun `setDataSource failure releases the created retriever exactly once`() = runBlocking {
        val retriever = mockk<MediaMetadataRetriever> {
            every { setDataSource(any<Context>(), any<Uri>()) } throws RuntimeException("no metadata")
            every { release() } returns Unit
        }
        val extractor = newExtractor(retriever)
        try {
            extractor.beginSession(Uri.parse("content://test/video"))
            assertNull(extractor.extractFrameAtPosition("content://test/video", 1_000L))
            // Two owned decoders were created (session + ephemeral retry);
            // each failed-open decoder is still closed exactly once.
            verify(exactly = 2, timeout = 5_000) { retriever.release() }
        } finally {
            extractor.release()
        }
    }

    // ---------- B6: CancellationException propagation ----------

    @Test
    fun `beginSession propagates CancellationException from native open`() = runBlocking {
        val retriever = mockk<MediaMetadataRetriever> {
            every { setDataSource(any<Context>(), any<Uri>()) } throws CancellationException("native cancel")
            every { release() } returns Unit
        }
        val extractor = newExtractor(retriever)
        try {
            try {
                extractor.beginSession(Uri.parse("content://test/video"))
                fail("CancellationException must reach the caller of beginSession")
            } catch (_: CancellationException) {
                // CE propagates FIRST — cancellation is never an ordinary init failure.
            }
            // The failed-open decoder is still reclaimed exactly once (async cleanup).
            verify(exactly = 1, timeout = 5_000) { retriever.release() }
        } finally {
            extractor.release()
            withTimeout(5_000) { extractor.shutdownResult.await() }
        }
    }

    @Test
    fun `extractFrameAtPosition propagates CancellationException from native decode`() = runBlocking {
        val retriever = mockk<MediaMetadataRetriever> {
            every { setDataSource(any<Context>(), any<Uri>()) } returns Unit
            every { getFrameAtTime(any(), any()) } throws CancellationException("native cancel")
            every { release() } returns Unit
        }
        val extractor = newExtractor(retriever)
        try {
            val outcome = withTimeout(5_000) {
                runCatching { extractor.extractFrameAtPosition("content://test/video", 1_000L) }
            }
            assertTrue(
                "CE must reach the caller, got $outcome",
                outcome.exceptionOrNull() is CancellationException,
            )
            // The poisoned ephemeral decoder is closed exactly once.
            verify(exactly = 1, timeout = 5_000) { retriever.release() }
        } finally {
            extractor.release()
            withTimeout(5_000) { extractor.shutdownResult.await() }
        }
    }

    // ---------- B6: publish barrier / linearization ----------

    @Test
    fun `late result from an old session never publishes after a newer session began`() = runBlocking {
        val decodeEntered = CountDownLatch(1)
        val decodeGate = CountDownLatch(1)
        val frameCalls = AtomicInteger(0)
        val retriever = mockk<MediaMetadataRetriever> {
            every { setDataSource(any<Context>(), any<Uri>()) } returns Unit
            every { getFrameAtTime(any(), any()) } answers {
                if (frameCalls.incrementAndGet() == 1) {
                    decodeEntered.countDown()
                    decodeGate.await(10, TimeUnit.SECONDS)
                }
                Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            }
            every { release() } returns Unit
        }
        val extractor = newExtractor(retriever)
        val scope = CoroutineScope(Dispatchers.IO)
        try {
            extractor.beginSession(Uri.parse("content://test/v"))
            val oldExtract = scope.async { extractor.extractFrameAtPosition("content://test/v", 1_000L) }
            assertTrue("old decode must enter native", decodeEntered.await(10, TimeUnit.SECONDS))

            // Session churn while the old decode is parked in native. The gate
            // opens FIRST: beginSession(B) suspends until B is installed,
            // which needs the lane the old decode is parking on.
            extractor.endSession()
            decodeGate.countDown()
            extractor.beginSession(Uri.parse("content://test/v"))
            assertNull(
                "stale-session result must never publish",
                withTimeout(5_000) { oldExtract.await() },
            )
            // Nothing was cached for the old artifact under the new session.
            assertNull(extractor.getCachedFrame("content://test/v", 1_000L))
            // The new session decodes fresh and publishes its own frame.
            val fresh = withTimeout(5_000) { extractor.extractFrameAtPosition("content://test/v", 1_000L) }
            assertNotNull(fresh)
            assertEquals(2, frameCalls.get())
            assertSame(fresh, extractor.getCachedFrame("content://test/v", 1_000L))
        } finally {
            decodeGate.countDown()
            extractor.release()
            scope.cancel()
        }
    }

    @Test
    fun `cache hit registers the current target so an older in-flight extraction is discarded`() = runBlocking {
        val secondDecodeEntered = CountDownLatch(1)
        val secondDecodeGate = CountDownLatch(1)
        val frameCalls = AtomicInteger(0)
        val retriever = mockk<MediaMetadataRetriever> {
            every { setDataSource(any<Context>(), any<Uri>()) } returns Unit
            every { getFrameAtTime(any(), any()) } answers {
                when (frameCalls.incrementAndGet()) {
                    1 -> Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
                    2 -> {
                        secondDecodeEntered.countDown()
                        secondDecodeGate.await(10, TimeUnit.SECONDS)
                        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
                    }
                    else -> Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
                }
            }
            every { release() } returns Unit
        }
        val extractor = newExtractor(retriever)
        val scope = CoroutineScope(Dispatchers.IO)
        try {
            extractor.beginSession(Uri.parse("content://test/v"))
            // Warm bucket 1000.
            val warm = extractor.extractFrameAtPosition("content://test/v", 1_000L)
            assertNotNull(warm)
            assertEquals(1, frameCalls.get())

            // Start an extraction for 2000 and park it inside native decode.
            val stale = scope.async { extractor.extractFrameAtPosition("content://test/v", 2_000L) }
            assertTrue("second decode must enter native", secondDecodeEntered.await(10, TimeUnit.SECONDS))

            // User scrubs back to 1000: a PURE cache hit — it must still
            // register the current target identity.
            val hit = extractor.getCachedFrame("content://test/v", 1_000L)
            assertSame(warm, hit)

            secondDecodeGate.countDown()
            assertNull(
                "the engine may still deliver, but the facade must discard the stale target",
                withTimeout(5_000) { stale.await() },
            )
            // The stale pass must not have cached bucket 2000.
            assertNull(extractor.getCachedFrame("content://test/v", 2_000L))
            // A retry decodes fresh and publishes.
            val retried = withTimeout(5_000) { extractor.extractFrameAtPosition("content://test/v", 2_000L) }
            assertNotNull(retried)
            assertEquals(3, frameCalls.get())
        } finally {
            secondDecodeGate.countDown()
            extractor.release()
            scope.cancel()
        }
    }

    @Test
    fun `in-flight result is discarded after endSession even without a newer session`() = runBlocking {
        val decodeEntered = CountDownLatch(1)
        val decodeGate = CountDownLatch(1)
        val retriever = mockk<MediaMetadataRetriever> {
            every { setDataSource(any<Context>(), any<Uri>()) } returns Unit
            every { getFrameAtTime(any(), any()) } answers {
                decodeEntered.countDown()
                decodeGate.await(10, TimeUnit.SECONDS)
                Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            }
            every { release() } returns Unit
        }
        val extractor = newExtractor(retriever)
        val scope = CoroutineScope(Dispatchers.IO)
        try {
            extractor.beginSession(Uri.parse("content://test/v"))
            val pending = scope.async { extractor.extractFrameAtPosition("content://test/v", 1_000L) }
            assertTrue(decodeEntered.await(10, TimeUnit.SECONDS))

            extractor.endSession()
            decodeGate.countDown()
            assertNull(withTimeout(5_000) { pending.await() })
            assertEquals("nothing may be cached from a dead session", 0, extractor.cacheSizeForTest())
            assertNull(extractor.getCachedFrame("content://test/v", 1_000L))
        } finally {
            decodeGate.countDown()
            extractor.release()
            scope.cancel()
        }
    }

    @Test
    fun `three-session churn A B C discards in-flight results and publishes only the newest`() = runBlocking {
        val firstEntered = CountDownLatch(1)
        val firstGate = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val secondGate = CountDownLatch(1)
        val frameCalls = AtomicInteger(0)
        val retriever = mockk<MediaMetadataRetriever> {
            every { setDataSource(any<Context>(), any<Uri>()) } returns Unit
            every { getFrameAtTime(any(), any()) } answers {
                when (frameCalls.incrementAndGet()) {
                    1 -> {
                        firstEntered.countDown()
                        firstGate.await(10, TimeUnit.SECONDS)
                        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
                    }
                    2 -> {
                        secondEntered.countDown()
                        secondGate.await(10, TimeUnit.SECONDS)
                        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
                    }
                    else -> Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
                }
            }
            every { release() } returns Unit
        }
        val extractor = newExtractor(retriever)
        val scope = CoroutineScope(Dispatchers.IO)
        try {
            // Session A with an in-flight extraction parked in native.
            extractor.beginSession(Uri.parse("content://test/v"))
            val a = scope.async { extractor.extractFrameAtPosition("content://test/v", 1_000L) }
            assertTrue(firstEntered.await(10, TimeUnit.SECONDS))

            // Churn to session B: A's parked result must die with its session.
            extractor.endSession()
            firstGate.countDown()
            extractor.beginSession(Uri.parse("content://test/v"))
            assertNull("A result must not publish into session B", withTimeout(5_000) { a.await() })

            // Session B with an in-flight extraction parked in native.
            val b = scope.async { extractor.extractFrameAtPosition("content://test/v", 2_000L) }
            assertTrue(secondEntered.await(10, TimeUnit.SECONDS))

            // Churn to session C: B's parked result must die the same way.
            extractor.endSession()
            secondGate.countDown()
            extractor.beginSession(Uri.parse("content://test/v"))
            assertNull("B result must not publish into session C", withTimeout(5_000) { b.await() })

            // Only session C's fresh decode publishes and caches.
            val c = withTimeout(5_000) { extractor.extractFrameAtPosition("content://test/v", 3_000L) }
            assertNotNull(c)
            assertEquals(3, frameCalls.get())
            assertNull(extractor.getCachedFrame("content://test/v", 1_000L))
            assertNull(extractor.getCachedFrame("content://test/v", 2_000L))
            assertSame(c, extractor.getCachedFrame("content://test/v", 3_000L))
        } finally {
            firstGate.countDown()
            secondGate.countDown()
            extractor.release()
            scope.cancel()
        }
    }

    @Test
    fun `switching media with identical duration publishes only the new media frame`() = runBlocking {
        val uriA = "content://test/media-a"
        val uriB = "content://test/media-b"
        val lastUri = AtomicReference<String?>(null)
        val decodeEntered = CountDownLatch(1)
        val decodeGate = CountDownLatch(1)
        val frameCalls = AtomicInteger(0)
        val retriever = mockk<MediaMetadataRetriever> {
            every { setDataSource(any<Context>(), any<Uri>()) } answers {
                lastUri.set(secondArg<Uri>().toString())
                Unit
            }
            every { getFrameAtTime(any(), any()) } answers {
                if (frameCalls.incrementAndGet() == 1) {
                    decodeEntered.countDown()
                    decodeGate.await(10, TimeUnit.SECONDS)
                }
                if (lastUri.get() == uriA) {
                    Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
                } else {
                    Bitmap.createBitmap(600, 200, Bitmap.Config.ARGB_8888)
                }
            }
            every { release() } returns Unit
        }
        val extractor = newExtractor(retriever)
        val scope = CoroutineScope(Dispatchers.IO)
        try {
            extractor.beginSession(Uri.parse(uriA))
            val a = scope.async { extractor.extractFrameAtPosition(uriA, 1_000L) }
            assertTrue(decodeEntered.await(10, TimeUnit.SECONDS))

            // Same duration, different media: session churn must isolate both
            // the publish path and the cache.
            extractor.endSession()
            decodeGate.countDown()
            extractor.beginSession(Uri.parse(uriB))
            assertNull("old media result must not publish", withTimeout(5_000) { a.await() })

            val b = withTimeout(5_000) { extractor.extractFrameAtPosition(uriB, 1_000L) }
            assertNotNull(b)
            // 600x200 scales to 180x60; media A would scale to 180x135 — the
            // published shape proves the identity of the served media.
            assertEquals(60, b!!.height)
            assertNull("old media entries are unreachable in the new session", extractor.getCachedFrame(uriA, 1_000L))
            assertSame(b, extractor.getCachedFrame(uriB, 1_000L))
        } finally {
            decodeGate.countDown()
            extractor.release()
            scope.cancel()
        }
    }

    @Test
    fun `pause seek churn publishes only the newest target`() = runBlocking {
        val secondDecodeEntered = CountDownLatch(1)
        val secondDecodeGate = CountDownLatch(1)
        val frameCalls = AtomicInteger(0)
        val retriever = mockk<MediaMetadataRetriever> {
            every { setDataSource(any<Context>(), any<Uri>()) } returns Unit
            every { getFrameAtTime(any(), any()) } answers {
                if (frameCalls.incrementAndGet() == 2) {
                    secondDecodeEntered.countDown()
                    secondDecodeGate.await(10, TimeUnit.SECONDS)
                }
                Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            }
            every { release() } returns Unit
        }
        val extractor = newExtractor(retriever)
        val scope = CoroutineScope(Dispatchers.IO)
        try {
            extractor.beginSession(Uri.parse("content://test/v"))
            val first = extractor.extractFrameAtPosition("content://test/v", 1_000L)
            assertNotNull(first)

            // Seek while paused with a FIXED interleave: the 2000 seek is
            // launched first and OBSERVED to be in native decode before the
            // 3000 seek is submitted. The previous version launched both
            // asyncs back-to-back with no ordering, so 3000 could register
            // first — the test then exercised a different latest-wins path
            // than the one its assertions describe, and the accepted native
            // call count drifted with load (flake).
            val stale = scope.async { extractor.extractFrameAtPosition("content://test/v", 2_000L) }
            assertTrue("the 2000 seek must enter native decode first", secondDecodeEntered.await(10, TimeUnit.SECONDS))
            val newest = scope.async { extractor.extractFrameAtPosition("content://test/v", 3_000L) }
            assertNull(
                "intermediate seek target is discarded as soon as a newer one registers",
                withTimeout(5_000) { stale.await() },
            )

            secondDecodeGate.countDown()
            val published = withTimeout(5_000) { newest.await() }
            assertNotNull(published)
            // Deterministic now: the parked native decode IS the 2000 target
            // (it entered before 3000 was submitted); the supersede discards
            // it and 3000 decodes again — exactly 3 native calls, no
            // interleaving slack.
            assertEquals("native calls: parked 2000 decode + 3000 decode", 3, frameCalls.get())
            assertSame(first, extractor.getCachedFrame("content://test/v", 1_000L))
            assertNull("discarded target must not be cached", extractor.getCachedFrame("content://test/v", 2_000L))
            assertSame(published, extractor.getCachedFrame("content://test/v", 3_000L))
        } finally {
            secondDecodeGate.countDown()
            extractor.release()
            scope.cancel()
        }
    }

    @Test
    fun `a result arriving after endSession during the post-decode window is discarded`() = runBlocking {
        val scaleEntered = CountDownLatch(1)
        val scaleGate = CountDownLatch(1)
        val parkingDispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                thread(name = "post-decode-window") {
                    scaleEntered.countDown()
                    check(scaleGate.await(10, TimeUnit.SECONDS)) { "scale gate failed" }
                    block.run()
                }
            }
        }
        val retriever = mockk<MediaMetadataRetriever> {
            every { setDataSource(any<Context>(), any<Uri>()) } returns Unit
            every { getFrameAtTime(any(), any()) } returns Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            every { release() } returns Unit
        }
        val extractor = newExtractor(retriever, dispatcher = parkingDispatcher)
        val scope = CoroutineScope(Dispatchers.IO)
        try {
            extractor.beginSession(Uri.parse("content://test/v"))
            val pending = scope.async { extractor.extractFrameAtPosition("content://test/v", 1_000L) }
            assertTrue("decode finished; parked between decode and publish", scaleEntered.await(10, TimeUnit.SECONDS))

            // Invalidation lands INSIDE the check/publish window.
            extractor.endSession()
            scaleGate.countDown()

            assertNull(withTimeout(5_000) { pending.await() })
            assertEquals("no cache write may land after invalidation", 0, extractor.cacheSizeForTest())
        } finally {
            scaleGate.countDown()
            extractor.release()
            scope.cancel()
        }
    }

    // ---------- B6: close failure / capacity / submit rejection ----------

    @Test
    fun `close failure surfaces via shutdown without blocking the caller`() = runBlocking {
        val retriever = mockk<MediaMetadataRetriever> {
            every { setDataSource(any<Context>(), any<Uri>()) } returns Unit
            every { getFrameAtTime(any(), any()) } returns Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            every { release() } throws RuntimeException("close boom")
        }
        val extractor = newExtractor(retriever)
        try {
            // Ephemeral decode succeeded but its close failed: the caller
            // fast-fails (null, bounded time) — never hangs waiting on native.
            val frame = withTimeout(5_000) {
                runCatching { extractor.extractFrameAtPosition("content://test/video", 1_000L) }
            }
            assertNull("a frame whose close failed must not be delivered", frame.getOrNull())

            // The cleanup failure is observable on the shutdown channel.
            extractor.release()
            val shutdownFailure = runCatching {
                withTimeout(5_000) { extractor.shutdownResult.await() }
            }.exceptionOrNull()
            assertTrue(
                "shutdown must deliver the close failure, got $shutdownFailure",
                shutdownFailure is RuntimeException,
            )
        } finally {
            extractor.release()
        }
    }

    @Test
    fun `engine NoCapacity degrades fast without throwing or lingering`() = runBlocking {
        val retriever = mockk<MediaMetadataRetriever>(relaxed = true)
        val extractor = newExtractor(retriever, lanePool = NoLanePool)
        try {
            // NoCapacity is a logged degradation, not an exception.
            val elapsed = measureTimeMillis { extractor.beginSession(Uri.parse("content://test/v")) }
            assertTrue("beginSession must stay fast, took ${elapsed}ms", elapsed < 1_000)
            assertNull(withTimeout(1_000) { extractor.extractFrameAtPosition("content://test/v", 1_000L) })
            assertNull(extractor.getCachedFrame("content://test/v", 1_000L))
        } finally {
            extractor.release()
        }
        // Nothing was ever acquired → shutdown completes immediately.
        withTimeout(5_000) { extractor.shutdownResult.await() }
        verify(exactly = 0) { retriever.release() }
    }

    @Test
    fun `submit rejection fast-fails signal calls and shutdown terminates exceptionally`() = runBlocking {
        val retriever = mockk<MediaMetadataRetriever>(relaxed = true)
        val extractor = newExtractor(retriever, lanePool = RejectingLanePool)
        try {
            // The engine rolls a rejected submit back: the facade call never
            // throws into the UI and no worker flag is left dangling.
            val beginElapsed = measureTimeMillis { extractor.beginSession(Uri.parse("content://test/v")) }
            assertTrue("beginSession must fast-fail, took ${beginElapsed}ms", beginElapsed < 1_000)
            // Signal calls stay non-blocking; the facade keeps no queued
            // state of its own that could stall the UI thread.
            val endElapsed = measureTimeMillis { extractor.endSession() }
            assertTrue("endSession must stay fast, took ${endElapsed}ms", endElapsed < 1_000)
            val releaseElapsed = measureTimeMillis { extractor.release() }
            assertTrue("release must stay non-blocking, took ${releaseElapsed}ms", releaseElapsed < 1_000)
            // The real rejection is observable — shutdown terminates
            // exceptionally instead of hanging on a worker that never ran.
            try {
                withTimeout(5_000) { extractor.shutdownResult.await() }
                fail("shutdown must terminate exceptionally after submit rejection")
            } catch (t: Throwable) {
                assertTrue(t is IllegalStateException)
                assertEquals("submit rejected", t.message)
            }
            // No later call waits on the never-scheduled worker: a released
            // engine answers immediately.
            assertNull(extractor.extractFrameAtPosition("content://test/v", 1_000L))
        } finally {
            extractor.release()
        }
    }

    // ---------- B6: cache budget & explicit UI release ----------

    @Test
    fun `cache evicts under the byte budget and never recycles evicted bitmaps`() = runBlocking {
        // 180x180 ARGB = 129,600 bytes billed per entry (byte-unit billing —
        // no rounding gap); 48 distinct buckets ≈ 6.2MB against the 4MB
        // budget → eviction must occur.
        val frame = Bitmap.createBitmap(180, 180, Bitmap.Config.ARGB_8888)
        val retriever = mockk<MediaMetadataRetriever> {
            every { setDataSource(any<Context>(), any<Uri>()) } returns Unit
            every { getFrameAtTime(any(), any()) } returns frame
            every { release() } returns Unit
        }
        val extractor = newExtractor(retriever)
        val held = mutableListOf<Bitmap>()
        try {
            for (i in 0 until 48) {
                val f = extractor.extractFrameAtPosition("content://test/v", i * 500L)
                assertNotNull(f)
                held.add(f!!)
            }
            assertTrue(
                "cache must respect the 4MB byte budget",
                extractor.cacheSizeForTest() <= 4 * 1024 * 1024,
            )
            // Billing must track the REAL byte counts: billed size is exactly
            // k entries × the real per-entry byteCount (no floor drift).
            assertEquals(
                "billed size must be a whole multiple of the real byteCount",
                0,
                extractor.cacheSizeForTest() % frame.byteCount,
            )
            assertNull("oldest entries must have been evicted", extractor.getCachedFrame("content://test/v", 0L))
            assertNotNull(
                "newest entry must have survived eviction",
                extractor.getCachedFrame("content://test/v", 47 * 500L),
            )
            assertTrue(
                "evicted bitmaps the UI may still hold are never recycled",
                held.all { !it.isRecycled },
            )
        } finally {
            extractor.release()
        }
    }

    @Test
    fun `releaseFrame drops the cache entry idempotently and never recycles`() = runBlocking {
        val retriever = mockk<MediaMetadataRetriever> {
            every { setDataSource(any<Context>(), any<Uri>()) } returns Unit
            every { getFrameAtTime(any(), any()) } returns Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            every { release() } returns Unit
        }
        val extractor = newExtractor(retriever)
        try {
            val frame = extractor.extractFrameAtPosition("content://test/v", 1_000L)
            assertNotNull(frame)
            assertSame(frame, extractor.getCachedFrame("content://test/v", 1_000L))

            // Explicit UI release: drops the cache reference, idempotent.
            extractor.releaseFrame(frame)
            assertNull(extractor.getCachedFrame("content://test/v", 1_000L))
            extractor.releaseFrame(frame)
            extractor.releaseFrame(null)
            assertTrue("the UI-held bitmap is never recycled by the facade", !frame!!.isRecycled)
        } finally {
            extractor.release()
        }
    }

    // ---------- U3: bounded neighbor prefetch ----------

    /**
     * Bounded busy-wait on a fire-and-forget prefetch side effect (prefetch
     * runs on its own scope; tests must never await it unboundedly).
     */
    private fun awaitUntil(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!condition()) {
            if (System.nanoTime() > deadline) fail("condition not met within ${timeoutMs}ms")
            Thread.sleep(10)
        }
    }

    @Test
    fun `prefetchNeighbors warms adjacent buckets into the cache only`() = runBlocking {
        val askedUs = CopyOnWriteArrayList<Long>()
        val retriever = mockk<MediaMetadataRetriever> {
            every { setDataSource(any<Context>(), any<Uri>()) } returns Unit
            every { getFrameAtTime(any(), any()) } answers {
                askedUs.add(firstArg())
                Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            }
            every { release() } returns Unit
        }
        val extractor = newExtractor(retriever)
        try {
            extractor.beginSession(Uri.parse("content://test/video"))
            assertNotNull(extractor.extractFrameAtPosition("content://test/video", 1_000L))
            // cacheSizeForTest() reports the billed byte total; an 8x8 ARGB
            // frame (256 bytes) is billed at MIN_BILLABLE (1024 bytes).
            assertEquals(1024, extractor.cacheSizeForTest())
            askedUs.clear() // drop the target decode — only prefetch decodes count below

            // 1234ms → bucket 1000; ±1 neighbour = 500 and 1500 (target is
            // already cached and must NOT re-decode).
            extractor.prefetchNeighbors("content://test/video", 1_234L)

            awaitUntil { extractor.cacheSizeForTest() >= 3 * 1024 }
            assertEquals("exactly the two neighbours may decode", 2, askedUs.size)
            assertTrue("the previous bucket (500ms) must be warmed", 500_000L in askedUs)
            assertTrue("the next bucket (1500ms) must be warmed", 1_500_000L in askedUs)

            // Edge handling: 600ms → bucket 500; ±1 neighbour = 0 and 1000.
            // Bucket 0 is a legal neighbour (only negatives are clamped) and
            // must decode; 1000 is already warm and must be skipped.
            askedUs.clear()
            extractor.prefetchNeighbors("content://test/video", 600L)
            awaitUntil { extractor.cacheSizeForTest() >= 4 * 1024 }
            assertEquals(listOf(0L), askedUs.toList())

            // At the very start (100ms → bucket 0) the backward neighbour is
            // clamped away and the forward one is already warm: nothing at
            // all may decode. Bounded settle, then assert stability.
            extractor.prefetchNeighbors("content://test/video", 100L)
            Thread.sleep(200)
            assertEquals("clamped+beyond-warm prefetch must decode nothing", 1, askedUs.size)
        } finally {
            extractor.release()
        }
    }

    @Test
    fun `prefetchNeighbors is idempotent and never queues duplicate work`() = runBlocking {
        val decodeGate = CountDownLatch(1)
        val decodeEntered = CountDownLatch(1)
        val askedUs = CopyOnWriteArrayList<Long>()
        val retriever = mockk<MediaMetadataRetriever> {
            every { setDataSource(any<Context>(), any<Uri>()) } returns Unit
            every { getFrameAtTime(any(), any()) } answers {
                askedUs.add(firstArg())
                decodeEntered.countDown()
                // Explicit gate — held so the pass provably owns the mutex.
                decodeGate.await()
                Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            }
            every { release() } returns Unit
        }
        val extractor = newExtractor(retriever)
        try {
            extractor.beginSession(Uri.parse("content://test/video"))
            extractor.prefetchNeighbors("content://test/video", 1_000L)
            assertTrue("first neighbour decode must be in flight", decodeEntered.await(10, TimeUnit.SECONDS))

            // A pass fired while another is running is DROPPED (tryLock),
            // never queued behind it.
            extractor.prefetchNeighbors("content://test/video", 3_000L)

            decodeGate.countDown()
            awaitUntil { extractor.cacheSizeForTest() >= 2 * 1024 } // neighbours 500 + 1500
            // A re-fire over already-warm buckets is a no-op: cache hits skip.
            extractor.prefetchNeighbors("content://test/video", 1_000L)
            assertEquals("only the two neighbours may ever decode", 2, askedUs.size)
            assertEquals(2 * 1024, extractor.cacheSizeForTest())
        } finally {
            decodeGate.countDown()
            extractor.release()
        }
    }

    @Test
    fun `prefetch stops at endSession and caches nothing from an ended session`() = runBlocking {
        val pfGate = CountDownLatch(1)
        val pfEntered = CountDownLatch(1)
        val askedPf = CopyOnWriteArrayList<Long>()
        val retriever = mockk<MediaMetadataRetriever> {
            every { setDataSource(any<Context>(), any<Uri>()) } returns Unit
            every { release() } returns Unit
        }
        val pfRetriever = mockk<MediaMetadataRetriever> {
            every { setDataSource(any<Context>(), any<Uri>()) } returns Unit
            every { getFrameAtTime(any(), any()) } answers {
                askedPf.add(firstArg())
                pfEntered.countDown()
                pfGate.await()
                Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            }
            every { release() } returns Unit
        }
        val extractor = newExtractor(retriever, prefetchRetriever = pfRetriever)
        try {
            extractor.beginSession(Uri.parse("content://test/video"))
            extractor.prefetchNeighbors("content://test/video", 1_000L)
            assertTrue("prefetch must reach native decode", pfEntered.await(10, TimeUnit.SECONDS))

            extractor.endSession() // cancels the session job + bumps the token + retires the prefetch session
            assertEquals(0, extractor.cacheSizeForTest())
            pfGate.countDown()
            // end() never completes engine.shutdown — the post-end close is
            // observable through the native release; shutdownResult only
            // settles after release() (in the finally below). BOTH channels
            // must retire their own native session exactly once.
            verify(exactly = 1, timeout = 5_000) { retriever.release() }
            verify(exactly = 1, timeout = 5_000) { pfRetriever.release() }
            assertEquals("nothing may be cached from an ended session", 0, extractor.cacheSizeForTest())
            assertEquals("the remaining neighbour must never start", 1, askedPf.size)
            assertNull(extractor.getCachedFrame("content://test/video", 500L))
            assertNull(extractor.getCachedFrame("content://test/video", 1_500L))
        } finally {
            pfGate.countDown()
            extractor.release()
            withTimeout(10_000) { extractor.shutdownResult.await() }
        }
    }

    @Test
    fun `prefetchNeighbors after release is a fast no-op`() = runBlocking {
        val retriever = mockk<MediaMetadataRetriever>(relaxed = true)
        val extractor = newExtractor(retriever)
        extractor.release()
        // Synchronous gate at the releasedFlag check — nothing is ever
        // launched, so no async settling is needed.
        extractor.prefetchNeighbors("content://test/video", 1_000L)
        assertEquals(0, extractor.cacheSizeForTest())
        verify(exactly = 0) { retriever.getFrameAtTime(any(), any()) }
    }

    @Test
    fun `prefetchNeighbors rejects any radius other than the supported rolling window`() = runBlocking {
        val retriever = mockk<MediaMetadataRetriever>(relaxed = true)
        val extractor = newExtractor(retriever)
        try {
            extractor.beginSession(Uri.parse("content://test/video"))
            for (bad in listOf(0, -2, 2)) {
                try {
                    extractor.prefetchNeighbors("content://test/video", 1_000L, radiusBuckets = bad)
                    fail("radiusBuckets=$bad must be rejected synchronously")
                } catch (expected: IllegalArgumentException) {
                    // require() contract: the caller asked for an unsupported
                    // window — fail fast instead of silently degrading.
                }
            }
            assertEquals(0, extractor.cacheSizeForTest())
            verify(exactly = 0) { retriever.getFrameAtTime(any(), any()) }
        } finally {
            extractor.release()
        }
    }

    // ---------- U3b: prefetch channel isolation & lifecycle ----------

    @Test
    fun `prefetch fired while a UI extract is in flight never supersedes the UI result`() = runBlocking {
        val uiGate = CountDownLatch(1)
        val uiEntered = CountDownLatch(1)
        val retriever = mockk<MediaMetadataRetriever> {
            every { setDataSource(any<Context>(), any<Uri>()) } returns Unit
            every { getFrameAtTime(any(), any()) } answers {
                uiEntered.countDown()
                uiGate.await()
                Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            }
            every { release() } returns Unit
        }
        val askedPf = CopyOnWriteArrayList<Long>()
        val pfRetriever = mockk<MediaMetadataRetriever> {
            every { setDataSource(any<Context>(), any<Uri>()) } returns Unit
            every { getFrameAtTime(any(), any()) } answers {
                askedPf.add(firstArg())
                Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            }
            every { release() } returns Unit
        }
        val extractor = newExtractor(retriever, prefetchRetriever = pfRetriever)
        // Independent scope: the extract must run on its own thread — an
        // async on the runBlocking queue would never start while this test
        // body blocks on the entry latch below.
        val uiScope = CoroutineScope(Dispatchers.IO)
        try {
            // Prefetch is session-scoped: without a live sessionJob the
            // admission gate drops every fire, so open the session first.
            extractor.beginSession(Uri.parse("content://test/video"))
            val ui = uiScope.async { extractor.extractFrameAtPosition("content://test/video", 1_000L) }
            assertTrue("UI decode must be in flight", uiEntered.await(10, TimeUnit.SECONDS))

            // Prefetch runs while the UI decode is parked. On the pre-U3b
            // design it went through the UI engine's latest-wins mailbox and
            // bumped the request generation, killing the parked UI result.
            extractor.prefetchNeighbors("content://test/video", 1_000L)

            awaitUntil { extractor.cacheSizeForTest() >= 2 * 1024 } // neighbours 500 + 1500
            assertEquals("both neighbours decode on the prefetch channel", 2, askedPf.size)

            uiGate.countDown()
            assertNotNull("the in-flight UI result must survive prefetch", withTimeout(10_000) { ui.await() })
        } finally {
            uiGate.countDown()
            uiScope.cancel()
            extractor.release()
            withTimeout(10_000) { extractor.shutdownResult.await() }
        }
    }

    @Test
    fun `a prefetch pass in flight cannot override a UI extract with a second neighbour`() = runBlocking {
        val pfGate = CountDownLatch(1)
        val pfEntered = CountDownLatch(1)
        val pfRetriever = mockk<MediaMetadataRetriever> {
            every { setDataSource(any<Context>(), any<Uri>()) } returns Unit
            every { getFrameAtTime(any(), any()) } answers {
                pfEntered.countDown()
                pfGate.await()
                Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            }
            every { release() } returns Unit
        }
        val retriever = mockk<MediaMetadataRetriever> {
            every { setDataSource(any<Context>(), any<Uri>()) } returns Unit
            every { getFrameAtTime(any(), any()) } returns Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            every { release() } returns Unit
        }
        val extractor = newExtractor(retriever, prefetchRetriever = pfRetriever)
        try {
            extractor.beginSession(Uri.parse("content://test/video"))
            extractor.prefetchNeighbors("content://test/video", 1_000L)
            assertTrue("prefetch decode must be in flight", pfEntered.await(10, TimeUnit.SECONDS))

            // The UI submits while neighbour #1 is parked on the prefetch
            // channel — the UI engine's mailbox is untouched, so the UI
            // publishes normally.
            val ui = extractor.extractFrameAtPosition("content://test/video", 1_000L)
            assertNotNull("UI extract must not be dropped while prefetch is in flight", ui)
            assertSame(
                "the UI cache entry must be the UI's own frame",
                ui,
                extractor.getCachedFrame("content://test/video", 1_000L),
            )

            pfGate.countDown()
            awaitUntil { extractor.cacheSizeForTest() >= 3 * 1024 } // UI + both neighbours
            assertEquals(3 * 1024, extractor.cacheSizeForTest())
        } finally {
            pfGate.countDown()
            extractor.release()
            withTimeout(10_000) { extractor.shutdownResult.await() }
        }
    }

    @Test
    fun `prefetch completing in the decoded-unpublished UI window cannot invalidate the UI result`() = runBlocking {
        val stepping = SteppingDispatcher()
        val uiGate = CountDownLatch(1)
        val uiEntered = CountDownLatch(1)
        val retriever = mockk<MediaMetadataRetriever> {
            every { setDataSource(any<Context>(), any<Uri>()) } returns Unit
            every { getFrameAtTime(any(), any()) } answers {
                uiEntered.countDown()
                uiGate.await()
                Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            }
            every { release() } returns Unit
        }
        val pfRetriever = mockk<MediaMetadataRetriever> {
            every { setDataSource(any<Context>(), any<Uri>()) } returns Unit
            every { getFrameAtTime(any(), any()) } returns Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            every { release() } returns Unit
        }
        val extractor = newExtractor(retriever, dispatcher = stepping, prefetchRetriever = pfRetriever)
        // Independent scope — same reason as the in-flight latch test: the
        // extract must not sit in the runBlocking queue while the body
        // blocks on the entry latch.
        val uiScope = CoroutineScope(Dispatchers.IO)
        try {
            extractor.beginSession(Uri.parse("content://test/video"))
            // Admit the pass first (queued on the stepping dispatcher), then
            // submit the UI extract and hold it INSIDE its native decode.
            extractor.prefetchNeighbors("content://test/video", 1_000L)
            val ui = uiScope.async { extractor.extractFrameAtPosition("content://test/video", 1_000L) }
            assertTrue("UI decode must be entered", uiEntered.await(10, TimeUnit.SECONDS))

            // Run the prefetch pass to completion while the UI result is
            // submitted but unpublished (decode parked → scale never queued).
            val deadline = System.nanoTime() + 10_000_000_000
            while (extractor.cacheSizeForTest() < 2 * 1024) {
                stepping.drainOnce()
                if (System.nanoTime() > deadline) fail("prefetch never completed in the UI window")
                Thread.sleep(10)
            }
            assertTrue(
                "the UI must still be parked inside its decode, unpublished",
                uiGate.count == 1L,
            )
            assertEquals("exactly the two neighbours are cached", 2 * 1024, extractor.cacheSizeForTest())

            // Now let the UI decode return: its scale step queues behind and
            // must still publish — prefetch invalidated nothing.
            uiGate.countDown()
            awaitUntil { stepping.pending.isNotEmpty() }
            stepping.drainOnce()
            assertNotNull(
                "the in-window UI result must survive prefetch",
                withTimeout(10_000) { ui.await() },
            )
            assertEquals(3 * 1024, extractor.cacheSizeForTest())
        } finally {
            uiGate.countDown()
            uiScope.cancel()
            extractor.release()
            withTimeout(10_000) { extractor.shutdownResult.await() }
        }
    }

    @Test
    fun `prefetch resumes in a new session and a late old-session result never caches`() = runBlocking {
        val pfGate = CountDownLatch(1)
        val pfEntered = CountDownLatch(1)
        val askedPf = CopyOnWriteArrayList<Long>()
        val pfRetriever = mockk<MediaMetadataRetriever> {
            every { setDataSource(any<Context>(), any<Uri>()) } returns Unit
            every { getFrameAtTime(any(), any()) } answers {
                askedPf.add(firstArg())
                pfEntered.countDown()
                pfGate.await()
                Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            }
            every { release() } returns Unit
        }
        val retriever = mockk<MediaMetadataRetriever>(relaxed = true)
        val extractor = newExtractor(retriever, prefetchRetriever = pfRetriever)
        try {
            extractor.beginSession(Uri.parse("content://test/video"))
            extractor.prefetchNeighbors("content://test/video", 1_000L)
            assertTrue(pfEntered.await(10, TimeUnit.SECONDS))

            extractor.endSession()
            pfGate.countDown() // session 1's decode completes into a dead caller
            verify(exactly = 1, timeout = 5_000) { pfRetriever.release() }
            assertEquals(
                "the late old-session decode must not have cached anything",
                0,
                extractor.cacheSizeForTest(),
            )

            extractor.beginSession(Uri.parse("content://test/video"))
            // Re-fire until it sticks: right after session 1's lane close
            // there is a microscopic window where the prefetch lane is still
            // being given back and a begin would log a `NoCapacity` skip —
            // prefetch is best-effort, so the next fire is the designed
            // recovery, and the loop just makes that recovery observable.
            val deadline = System.nanoTime() + 10_000_000_000
            while (extractor.cacheSizeForTest() < 2 * 1024) {
                if (System.nanoTime() > deadline) fail("prefetch never resumed in session 2")
                extractor.prefetchNeighbors("content://test/video", 1_000L)
                Thread.sleep(10)
            }
            assertEquals(
                "session 1 decode + both session 2 decodes",
                listOf(500_000L, 500_000L, 1_500_000L),
                askedPf.toList(),
            )
            assertNotNull(extractor.getCachedFrame("content://test/video", 500L))
            assertNotNull(extractor.getCachedFrame("content://test/video", 1_500L))
        } finally {
            pfGate.countDown()
            extractor.release()
            withTimeout(10_000) { extractor.shutdownResult.await() }
        }
    }

    @Test
    fun `a burst of prefetch calls on a frozen dispatcher admits exactly one task`() = runBlocking {
        val held = HeldDispatcher()
        val retriever = mockk<MediaMetadataRetriever>(relaxed = true)
        val extractor = newExtractor(retriever, dispatcher = held)
        try {
            extractor.beginSession(Uri.parse("content://test/video"))
            repeat(50) { extractor.prefetchNeighbors("content://test/video", 1_000L) }

            assertEquals(
                "synchronous admission: one in-flight slot, zero queued calls",
                1,
                held.tasks.size,
            )
            verify(exactly = 0) { retriever.getFrameAtTime(any(), any()) }

            // Drive the single admitted pass step by step: every
            // continuation lands on the frozen dispatcher and is drained
            // here, until both neighbours are cached.
            val deadline = System.nanoTime() + 10_000_000_000
            while (extractor.cacheSizeForTest() < 2 * 1024) {
                held.tasks.poll()?.run()
                if (System.nanoTime() > deadline) fail("the admitted pass never completed")
                Thread.sleep(10)
            }
        } finally {
            extractor.release()
            withTimeout(10_000) { extractor.shutdownResult.await() }
        }
    }

    @Test
    fun `a scaling failure degrades to a log line and leaves the channel reusable`() = runBlocking {
        val hook = AtomicReference<(Bitmap) -> Bitmap>({ error("scale boom") })
        val askedPf = CopyOnWriteArrayList<Long>()
        val pfRetriever = mockk<MediaMetadataRetriever> {
            every { setDataSource(any<Context>(), any<Uri>()) } returns Unit
            every { getFrameAtTime(any(), any()) } answers {
                askedPf.add(firstArg())
                Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            }
            every { release() } returns Unit
        }
        val retriever = mockk<MediaMetadataRetriever>(relaxed = true)
        val extractor = newExtractor(retriever, prefetchRetriever = pfRetriever, scale = { hook.get()(it) })
        try {
            extractor.beginSession(Uri.parse("content://test/video"))
            extractor.prefetchNeighbors("content://test/video", 1_000L)

            awaitUntil { extractor.inFlightPrefetchCountForTest() == 0 }
            assertEquals(
                "both neighbours decoded, both scale-failed",
                listOf(500_000L, 1_500_000L),
                askedPf.toList(),
            )
            assertEquals("failed frames must never be cached", 0, extractor.cacheSizeForTest())

            hook.set({ it }) // heal the hook — the channel must be reusable
            extractor.prefetchNeighbors("content://test/video", 1_000L)
            awaitUntil { extractor.cacheSizeForTest() >= 2 * 1024 }
            assertEquals(2 * 1024, extractor.cacheSizeForTest())
        } finally {
            extractor.release()
            withTimeout(10_000) { extractor.shutdownResult.await() }
        }
    }

    // ---------- U3c: shared prefetch pool, shutdown join, begin window, publish boundary ----------

    @Test
    fun `the shared prefetch pool is reused across extractor instances`() = runBlocking {
        val shared = SeekPreviewExtractor.sharedPrefetchPoolForTest()
        val pf1 = mockk<MediaMetadataRetriever>(relaxed = true)
        val pf2 = mockk<MediaMetadataRetriever> {
            every { setDataSource(any<Context>(), any<Uri>()) } returns Unit
            every { getFrameAtTime(any(), any()) } returns Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            every { release() } returns Unit
        }
        val retriever = mockk<MediaMetadataRetriever>(relaxed = true)
        val first = newExtractor(retriever, prefetchRetriever = pf1, prefetchLanePool = shared)
        first.beginSession(Uri.parse("content://test/video"))
        first.prefetchNeighbors("content://test/video", 1_000L)
        awaitUntil { first.cacheSizeForTest() >= 2 * 1024 }
        first.release()
        withTimeout(10_000) { first.shutdownResult.await() }

        // Same process, same shared lane — the second instance reuses it
        // (its own retriever decodes; no capacity error swallowed it).
        val second = newExtractor(retriever, prefetchRetriever = pf2, prefetchLanePool = shared)
        second.beginSession(Uri.parse("content://test/video"))
        second.prefetchNeighbors("content://test/video", 1_000L)
        awaitUntil { second.cacheSizeForTest() >= 2 * 1024 }
        verify(atLeast = 2) { pf2.getFrameAtTime(any(), any()) }
        second.release()
        withTimeout(10_000) { second.shutdownResult.await() }
    }

    @Test
    fun `a stuck shared prefetch lane makes later prefetch a NoCapacity skip, never a queue`() = runBlocking {
        val shared = SeekPreviewExtractor.sharedPrefetchPoolForTest()
        val pfGate = CountDownLatch(1)
        val pfEntered = CountDownLatch(1)
        val pf1 = mockk<MediaMetadataRetriever> {
            every { setDataSource(any<Context>(), any<Uri>()) } returns Unit
            every { getFrameAtTime(any(), any()) } answers {
                pfEntered.countDown()
                pfGate.await()
                Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            }
            every { release() } returns Unit
        }
        val askedPf2 = CopyOnWriteArrayList<Long>()
        val pf2 = mockk<MediaMetadataRetriever> {
            every { setDataSource(any<Context>(), any<Uri>()) } returns Unit
            every { getFrameAtTime(any(), any()) } answers {
                askedPf2.add(firstArg())
                Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            }
            every { release() } returns Unit
        }
        val retriever = mockk<MediaMetadataRetriever>(relaxed = true)
        val stuck = newExtractor(retriever, prefetchRetriever = pf1, prefetchLanePool = shared)
        val later = newExtractor(retriever, prefetchRetriever = pf2, prefetchLanePool = shared)
        try {
            stuck.beginSession(Uri.parse("content://test/video"))
            stuck.prefetchNeighbors("content://test/video", 1_000L)
            assertTrue(pfEntered.await(10, TimeUnit.SECONDS)) // shared lane is parked forever-ish

            later.beginSession(Uri.parse("content://test/video"))
            later.prefetchNeighbors("content://test/video", 1_000L)
            // Admission is fine (per-extractor gate), but the begin must be
            // a NoCapacity SKIP — no decode, no cache, no queueing.
            awaitUntil { later.inFlightPrefetchCountForTest() == 0 }
            assertEquals(0, later.cacheSizeForTest())
            assertTrue(askedPf2.isEmpty())

            pfGate.countDown() // free the shared lane's native call
            awaitUntil { stuck.cacheSizeForTest() >= 2 * 1024 }
            // The decode is done, but the stuck engine still LEASES the
            // shared lane until it releases — capacity does not silently
            // migrate between instances mid-lease:
            later.prefetchNeighbors("content://test/video", 1_000L)
            awaitUntil { later.inFlightPrefetchCountForTest() == 0 }
            assertEquals(0, later.cacheSizeForTest())
            assertTrue(askedPf2.isEmpty())

            // Reuse is granted only after the lease returns:
            stuck.release()
            withTimeout(10_000) { stuck.shutdownResult.await() }
            later.prefetchNeighbors("content://test/video", 1_000L)
            awaitUntil { later.cacheSizeForTest() >= 2 * 1024 }
            assertTrue(askedPf2.isNotEmpty())
        } finally {
            pfGate.countDown()
            stuck.release()
            later.release()
            withTimeout(10_000) { stuck.shutdownResult.await() }
            withTimeout(10_000) { later.shutdownResult.await() }
        }
    }

    @Test
    fun `a failed UI close with prefetch parked keeps shutdownResult pending until everything settles`() = runBlocking {
        val pfGate = CountDownLatch(1)
        val pfEntered = CountDownLatch(1)
        val pfRetriever = mockk<MediaMetadataRetriever> {
            every { setDataSource(any<Context>(), any<Uri>()) } returns Unit
            every { getFrameAtTime(any(), any()) } answers {
                pfEntered.countDown()
                pfGate.await()
                Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            }
            every { release() } returns Unit
        }
        val retriever = mockk<MediaMetadataRetriever> {
            every { setDataSource(any<Context>(), any<Uri>()) } returns Unit
            every { getFrameAtTime(any(), any()) } returns Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            every { release() } throws IllegalStateException("ui close boom")
        }
        val extractor = newExtractor(retriever, prefetchRetriever = pfRetriever)
        try {
            extractor.beginSession(Uri.parse("content://test/video"))
            assertNotNull(extractor.extractFrameAtPosition("content://test/video", 1_000L))
            extractor.prefetchNeighbors("content://test/video", 1_000L)
            assertTrue(pfEntered.await(10, TimeUnit.SECONDS))

            extractor.release()
            // The prefetch decode is parked mid-native: cleanup is NOT done —
            // the merged port must stay pending, never settle early.
            var settledEarly = false
            val graceEnd = System.nanoTime() + 500_000_000
            while (System.nanoTime() < graceEnd) {
                if (extractor.shutdownResult.isCompleted) { settledEarly = true; break }
                Thread.sleep(50)
            }
            assertFalse("shutdownResult must stay pending while prefetch is parked", settledEarly)

            pfGate.countDown()
            awaitUntil { extractor.shutdownResult.isCompleted }
            val err = runCatching { extractor.shutdownResult.await() }.exceptionOrNull()
            assertNotNull("the UI close failure must surface", err)
        } finally {
            pfGate.countDown()
            extractor.release()
        }
    }

    @Test
    fun `both engines failing close surface the first as primary and the second as suppressed`() = runBlocking {
        val retriever = mockk<MediaMetadataRetriever> {
            every { setDataSource(any<Context>(), any<Uri>()) } returns Unit
            every { getFrameAtTime(any(), any()) } returns Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            every { release() } throws IllegalStateException("ui close boom")
        }
        val pfRetriever = mockk<MediaMetadataRetriever> {
            every { setDataSource(any<Context>(), any<Uri>()) } returns Unit
            every { getFrameAtTime(any(), any()) } returns Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            every { release() } throws IllegalStateException("pf close boom")
        }
        val extractor = newExtractor(retriever, prefetchRetriever = pfRetriever)
        try {
            extractor.beginSession(Uri.parse("content://test/video"))
            assertNotNull(extractor.extractFrameAtPosition("content://test/video", 1_000L))
            extractor.prefetchNeighbors("content://test/video", 1_000L)
            awaitUntil { extractor.cacheSizeForTest() >= 2 * 1024 }

            extractor.release()
            awaitUntil { extractor.shutdownResult.isCompleted }
            val err = runCatching { extractor.shutdownResult.await() }.exceptionOrNull()
            assertNotNull(err)
            assertTrue(
                "the UI engine failure is primary, got: ${err!!.message}",
                err.message!!.contains("ui close boom"),
            )
            // The primary/suppressed pairing is attached to the ORIGINAL
            // terminal causes; a Deferred-await view hands back a stack
            // -recovery copy that does not carry suppressed exceptions, so
            // the pairing is asserted at its source.
            val uiOriginal = extractor.uiShutdownForTest().getCompletionExceptionOrNull()
            val pfOriginal = extractor.prefetchShutdownForTest().getCompletionExceptionOrNull()
            assertNotNull("the UI engine terminal cause is observable", uiOriginal)
            assertNotNull("the prefetch engine terminal cause is observable", pfOriginal)
            assertTrue(
                "the UI engine cause is the primary",
                uiOriginal!!.message!!.contains("ui close boom"),
            )
            assertTrue(
                "the prefetch engine failure must be attached as suppressed",
                uiOriginal.suppressed.any { it.message!!.contains("pf close boom") },
            )
            assertTrue(
                "the prefetch engine cause is real",
                pfOriginal!!.message!!.contains("pf close boom"),
            )
        } finally {
            extractor.release()
        }
    }

    @Test
    fun `begin A then begin B without an intermediate end retires A and lets B prefetch`() = runBlocking {
        val pfGate = CountDownLatch(1)
        val pfEntered = CountDownLatch(1)
        val askedPf = CopyOnWriteArrayList<Long>()
        val pfRetriever = mockk<MediaMetadataRetriever> {
            every { setDataSource(any<Context>(), any<Uri>()) } returns Unit
            every { getFrameAtTime(any(), any()) } answers {
                askedPf.add(firstArg())
                pfEntered.countDown()
                pfGate.await()
                Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            }
            every { release() } returns Unit
        }
        val retriever = mockk<MediaMetadataRetriever>(relaxed = true)
        val extractor = newExtractor(retriever, prefetchRetriever = pfRetriever)
        try {
            extractor.beginSession(Uri.parse("content://test/video"))
            extractor.prefetchNeighbors("content://test/video", 1_000L)
            assertTrue(pfEntered.await(10, TimeUnit.SECONDS)) // session A's pass parked mid-decode

            // Begin B with NO intermediate end: A's passes must die here, and
            // B must be able to prefetch right away.
            extractor.beginSession(Uri.parse("content://test/video"))
            pfGate.countDown()
            extractor.prefetchNeighbors("content://test/video", 1_000L)
            awaitUntil { extractor.cacheSizeForTest() >= 2 * 1024 }

            // A's native session was retired (superseded when B's pass began).
            verify(exactly = 1) { pfRetriever.release() }
            // B's own neighbours are cached under B's session identity.
            assertNotNull(extractor.getCachedFrame("content://test/video", 500L))
            assertNotNull(extractor.getCachedFrame("content://test/video", 1_500L))
            assertEquals(
                "A's parked decode + both of B's decodes",
                listOf(500_000L, 500_000L, 1_500_000L),
                askedPf.toList(),
            )
        } finally {
            pfGate.countDown()
            extractor.release()
            withTimeout(10_000) { extractor.shutdownResult.await() }
        }
    }

    @Test
    fun `a publish fault degrades to a log line and never cancels the session`() = runBlocking {
        val poisoned = mockk<Bitmap>()
        every { poisoned.byteCount } throws IllegalStateException("publish boom")
        val hook = AtomicReference<(Bitmap) -> Bitmap>({ poisoned })
        val askedPf = CopyOnWriteArrayList<Long>()
        val pfRetriever = mockk<MediaMetadataRetriever> {
            every { setDataSource(any<Context>(), any<Uri>()) } returns Unit
            every { getFrameAtTime(any(), any()) } answers {
                askedPf.add(firstArg())
                Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            }
            every { release() } returns Unit
        }
        val retriever = mockk<MediaMetadataRetriever>(relaxed = true)
        val extractor = newExtractor(retriever, prefetchRetriever = pfRetriever, scale = { hook.get()(it) })
        try {
            extractor.beginSession(Uri.parse("content://test/video"))
            extractor.prefetchNeighbors("content://test/video", 1_000L)

            awaitUntil { extractor.inFlightPrefetchCountForTest() == 0 }
            assertEquals(
                "both buckets were attempted through the poisoned publish",
                listOf(500_000L, 1_500_000L),
                askedPf.toList(),
            )
            assertEquals("nothing cached through the fault", 0, extractor.cacheSizeForTest())

            // The fault must NOT have escaped the launch and killed the
            // session job — the channel is still admitted and working.
            hook.set({ it })
            extractor.prefetchNeighbors("content://test/video", 1_000L)
            awaitUntil { extractor.cacheSizeForTest() >= 2 * 1024 }
        } finally {
            extractor.release()
            withTimeout(10_000) { extractor.shutdownResult.await() }
        }
    }

    @Test
    fun `prefetch completing while the UI is parked at its scale step never invalidates the UI publish`() = runBlocking {
        val uiRaw = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val scaleGate = CountDownLatch(1)
        val scaleEntered = CountDownLatch(1)
        val hook: (Bitmap) -> Bitmap = { source ->
            if (source === uiRaw) {
                scaleEntered.countDown()
                scaleGate.await()
            }
            source
        }
        val pfRetriever = mockk<MediaMetadataRetriever> {
            every { setDataSource(any<Context>(), any<Uri>()) } returns Unit
            every { getFrameAtTime(any(), any()) } returns Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            every { release() } returns Unit
        }
        val retriever = mockk<MediaMetadataRetriever> {
            every { setDataSource(any<Context>(), any<Uri>()) } returns Unit
            every { getFrameAtTime(any(), any()) } returns uiRaw
            every { release() } returns Unit
        }
        val extractor = newExtractor(retriever, prefetchRetriever = pfRetriever, scale = hook)
        val uiScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            extractor.beginSession(Uri.parse("content://test/video"))
            // UI decodes instantly (single native call), then PARKS at its
            // CPU scale step — the publish never happened yet.
            val ui = uiScope.async { extractor.extractFrameAtPosition("content://test/video", 1_000L) }
            assertTrue("UI must reach its scale step", scaleEntered.await(10, TimeUnit.SECONDS))

            // Prefetch runs to completion inside that window.
            extractor.prefetchNeighbors("content://test/video", 1_000L)
            awaitUntil { extractor.cacheSizeForTest() >= 2 * 1024 }
            assertEquals("only the two neighbours so far", 2 * 1024, extractor.cacheSizeForTest())

            // Release the UI scale: it must publish normally.
            scaleGate.countDown()
            val uiFrame = withTimeout(10_000) { ui.await() }
            assertNotNull("the UI result must survive prefetch in the scale window", uiFrame)
            assertSame(
                "the UI's own frame is what got published and cached",
                uiFrame,
                extractor.getCachedFrame("content://test/video", 1_000L),
            )
            assertEquals(3 * 1024, extractor.cacheSizeForTest())
        } finally {
            scaleGate.countDown()
            uiScope.cancel()
            extractor.release()
            withTimeout(10_000) { extractor.shutdownResult.await() }
        }
    }

    @Test
    fun `the pass gate is the only reuse signal and the completion handler is the only cleaner`() = runBlocking {
        val pfGate = CountDownLatch(1)
        val pfEntered = CountDownLatch(1)
        val askedPf = CopyOnWriteArrayList<Long>()
        val pfRetriever = mockk<MediaMetadataRetriever> {
            every { setDataSource(any<Context>(), any<Uri>()) } returns Unit
            every { getFrameAtTime(any(), any()) } answers {
                askedPf.add(firstArg())
                pfEntered.countDown()
                pfGate.await()
                Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            }
            every { release() } returns Unit
        }
        val retriever = mockk<MediaMetadataRetriever>(relaxed = true)
        val extractor = newExtractor(retriever, prefetchRetriever = pfRetriever)
        try {
            extractor.beginSession(Uri.parse("content://test/video"))
            extractor.prefetchNeighbors("content://test/video", 1_000L)
            assertTrue(pfEntered.await(10, TimeUnit.SECONDS))
            assertEquals("both buckets registered by the in-flight pass", 2, extractor.inFlightPrefetchCountForTest())

            // In-flight: further calls are dropped (pass-gated, not queueable).
            extractor.prefetchNeighbors("content://test/video", 1_000L)
            extractor.prefetchNeighbors("content://test/video", 1_000L)
            assertEquals("registrations unchanged by dropped calls", 2, extractor.inFlightPrefetchCountForTest())

            pfGate.countDown()
            awaitUntil { extractor.inFlightPrefetchCountForTest() == 0 }
            awaitUntil { extractor.cacheSizeForTest() >= 2 * 1024 }

            // Reuse is granted by the released gate: the next pass is
            // admitted and decodes its (uncached) neighbours again. A same
            // position re-fire would be a cache hit and legitimately decode
            // nothing, so the reuse proof fires at a fresh target.
            extractor.prefetchNeighbors("content://test/video", 3_000L)
            awaitUntil { extractor.cacheSizeForTest() >= 4 * 1024 }
            assertEquals(
                "pass 1 decode + pass 2 decode",
                listOf(500_000L, 1_500_000L, 2_500_000L, 3_500_000L),
                askedPf.toList(),
            )
        } finally {
            pfGate.countDown()
            extractor.release()
            withTimeout(10_000) { extractor.shutdownResult.await() }
        }
    }

    @Test
    fun `a completing pass never deletes an overlapping refire's registration`() = runBlocking {
        // Bounded race net for cleanup-vs-admission atomicity. Each cycle
        // fires the next pass IMMEDIATELY across the previous pass's
        // completion boundary — the exact admission-vs-handler interleave —
        // and then soak-asserts that the parked pass's ledger stays exact.
        // The fixed order (clear BEFORE the gate opens, one critical section
        // shared with admission) makes the old handler structurally unable to
        // delete the new pass's registration; the previous order (gate
        // released first, clear second) let exactly that happen, dropping the
        // parked pass's ledger to zero. Whether the interleave is hit is
        // probabilistic per machine — but if the broken order is reintroduced
        // this net fails instead of silently passing.
        val cycles = 6
        val pfEntered = List(cycles) { CountDownLatch(1) }
        val pfGates = List(cycles) { CountDownLatch(1) }
        val decodeIndex = AtomicInteger(0)
        val askedPf = CopyOnWriteArrayList<Long>()
        val pfRetriever = mockk<MediaMetadataRetriever> {
            every { setDataSource(any<Context>(), any<Uri>()) } returns Unit
            every { getFrameAtTime(any(), any()) } answers {
                askedPf.add(firstArg())
                val i = decodeIndex.getAndIncrement()
                if (i % 2 == 0) { // first bucket of a pass parks it mid-decode
                    val c = i / 2
                    pfEntered[c].countDown()
                    pfGates[c].await()
                }
                Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            }
            every { release() } returns Unit
        }
        val retriever = mockk<MediaMetadataRetriever>(relaxed = true)
        val extractor = newExtractor(retriever, prefetchRetriever = pfRetriever)
        try {
            extractor.beginSession(Uri.parse("content://test/video"))
            // Every cycle warms a fresh, non-overlapping position so each
            // pass really decodes (and can really park): 1000 → 3000 → 5000…
            extractor.prefetchNeighbors("content://test/video", 1_000L)
            for (c in 0 until cycles) {
                assertTrue("pass $c must reach its parked decode", pfEntered[c].await(10, TimeUnit.SECONDS))
                assertEquals("parked pass $c ledger exact", 2, extractor.inFlightPrefetchCountForTest())
                pfGates[c].countDown()
                if (c + 1 < cycles) {
                    val next = "content://test/video"
                    val position = 1_000L + 2_000L * (c + 1)
                    val admitDeadline = System.nanoTime() + 10_000_000_000
                    // Keep firing until the next pass is admitted: calls that
                    // land before the previous handler opens the gate are
                    // legitimately dropped, calls that land in the broken
                    // handler's window get their registration deleted — the
                    // assertion below exists for exactly that case.
                    while (pfEntered[c + 1].getCount() > 0L) {
                        extractor.prefetchNeighbors(next, position)
                        if (System.nanoTime() > admitDeadline) fail("pass ${c + 1} never admitted")
                        Thread.sleep(2)
                    }
                    assertEquals(
                        "the new pass's registration must survive the old handler",
                        2,
                        extractor.inFlightPrefetchCountForTest(),
                    )
                    Thread.sleep(50) // brief soak: the parked ledger must hold
                    assertEquals("parked pass ${c + 1} ledger must hold", 2, extractor.inFlightPrefetchCountForTest())
                }
            }
            awaitUntil { extractor.inFlightPrefetchCountForTest() == 0 }
            assertEquals(0, extractor.inFlightPrefetchCountForTest())
        } finally {
            pfGates.forEach { it.countDown() }
            extractor.release()
            withTimeout(10_000) { extractor.shutdownResult.await() }
        }
    }

    @Test
    fun `an end linearized before a newer begin never retires the newer session`() = runBlocking {
        // Direction 1 of control-commit linearization. beginSession commits
        // the engine Open inside the SAME critical section that installs the
        // facade identity (UNDISPATCHED through to the first suspension), and
        // endSession commits the MAIN engine retirement inside the same
        // section that kills the identity — its prefetch retirement follows
        // in phase 2, after the parked work is dead, and only retires an
        // UNOWNED prefetch session (a newer session's installed claim is
        // left alone). Consequence pinned here: an end that fully
        // precedes a newer begin cannot retire the newer session, no matter
        // where that begin later suspends — it parks below at its native
        // open, after its Open command is already committed.
        val dsEntered = CountDownLatch(1)
        val dsGate = CountDownLatch(1)
        val askedPf = CopyOnWriteArrayList<Long>()
        val retriever = mockk<MediaMetadataRetriever> {
            every { setDataSource(any<Context>(), any<Uri>()) } returns Unit
            every { getFrameAtTime(any(), any()) } answers {
                Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            }
            every { release() } returns Unit
        }
        val pfRetriever = mockk<MediaMetadataRetriever> {
            every { setDataSource(any<Context>(), any<Uri>()) } returns Unit
            every { getFrameAtTime(any(), any()) } answers {
                askedPf.add(firstArg())
                Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            }
            every { release() } returns Unit
        }
        val extractor = newExtractor(retriever, prefetchRetriever = pfRetriever)
        var s2: Deferred<Unit>? = null
        try {
            extractor.beginSession(Uri.parse("content://test/video")) // S1 opens instantly
            extractor.endSession() // fully committed: identity + both engine ends, atomically

            // S2's begin parks at its native open (after its Open commit).
            every { retriever.setDataSource(any<Context>(), any<Uri>()) } answers {
                dsEntered.countDown()
                dsGate.await()
            }
            s2 = async(Dispatchers.IO) { extractor.beginSession(Uri.parse("content://test/video")) }
            assertTrue(dsEntered.await(10, TimeUnit.SECONDS))
            awaitUntil { extractor.hasLiveSessionForTest() } // identity live while open pending
            dsGate.countDown()
            s2.await()

            assertTrue("the newer session must be live after the older end", extractor.hasLiveSessionForTest())
            // The engines genuinely serve it: prefetch warms through the
            // generation barrier — an end that had (wrongly) retired S2 would
            // have bumped the epoch and silently dropped these results.
            extractor.prefetchNeighbors("content://test/video", 1_000L)
            awaitUntil { extractor.cacheSizeForTest() >= 2 * 1024 }
            assertEquals(listOf(500_000L, 1_500_000L), askedPf.toList())
        } finally {
            dsGate.countDown()
            s2?.cancel()
            extractor.release()
            withTimeout(10_000) { extractor.shutdownResult.await() }
        }
    }

    @Test
    fun `a begin parked after its commit is retired by a later end without an orphan session`() = runBlocking {
        // Direction 2 of control-commit linearization. S2's Open is already
        // committed (identity live, native open parked) when endSession runs.
        // Because the retirement commits in the SAME critical section that
        // kills the identity, the parked begin can never complete behind the
        // end and install an engine session the facade no longer owns: the
        // end resolves the parked Open (superseded/cancelled) and the facade
        // identity is gone — facade and both engines agree.
        val dsEntered = CountDownLatch(1)
        val dsGate = CountDownLatch(1)
        val askedPf = CopyOnWriteArrayList<Long>()
        val retriever = mockk<MediaMetadataRetriever> {
            every { setDataSource(any<Context>(), any<Uri>()) } returns Unit
            every { getFrameAtTime(any(), any()) } answers {
                Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            }
            every { release() } returns Unit
        }
        val pfRetriever = mockk<MediaMetadataRetriever> {
            every { setDataSource(any<Context>(), any<Uri>()) } returns Unit
            every { getFrameAtTime(any(), any()) } answers {
                askedPf.add(firstArg())
                Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            }
            every { release() } returns Unit
        }
        val extractor = newExtractor(retriever, prefetchRetriever = pfRetriever)
        var s2: Deferred<Unit>? = null
        try {
            extractor.beginSession(Uri.parse("content://test/video")) // S1 opens instantly

            // S2's begin parks at its native open (after its Open commit).
            every { retriever.setDataSource(any<Context>(), any<Uri>()) } answers {
                dsEntered.countDown()
                dsGate.await()
            }
            s2 = async(Dispatchers.IO) { extractor.beginSession(Uri.parse("content://test/video")) }
            assertTrue(dsEntered.await(10, TimeUnit.SECONDS))
            assertTrue("S2 committed: identity live before its open resolves", extractor.hasLiveSessionForTest())

            extractor.endSession() // retires whatever is installed — S2's Open included
            dsGate.countDown()
            val s2Outcome = runCatching { s2.await() }
            // Resolves as cancellation (session job cancelled) or a normal
            // superseded degradation — never hangs, never reports live.
            assertTrue(
                "parked begin must resolve, not hang",
                s2Outcome.isSuccess || s2Outcome.exceptionOrNull() is CancellationException,
            )
            assertFalse("no orphan facade session after the end", extractor.hasLiveSessionForTest())

            // The facade stays fully functional: a fresh session installs
            // (fresh generation) and its prefetch warms normally.
            every { retriever.setDataSource(any<Context>(), any<Uri>()) } returns Unit
            extractor.beginSession(Uri.parse("content://test/video"))
            assertTrue(extractor.hasLiveSessionForTest())
            extractor.prefetchNeighbors("content://test/video", 1_000L)
            awaitUntil { extractor.cacheSizeForTest() >= 2 * 1024 }
            assertEquals(listOf(500_000L, 1_500_000L), askedPf.toList())
        } finally {
            dsGate.countDown()
            s2?.cancel()
            extractor.release()
            withTimeout(10_000) { extractor.shutdownResult.await() }
        }
    }

    @Test
    fun `a caller cancelled while its native open is in flight retires its own session`() = runBlocking {
        // Cancellation-ownership, case 1: the caller dies while its Open is
        // parked in native decode. Rethrowing the CancellationException alone
        // would leak the begin child (it belongs to the session job, not the
        // caller) and whatever it goes on to install — the reclaim must
        // cancel the child and retire the session, but ONLY if this begin
        // still owns the facade identity.
        val dsEntered = CountDownLatch(1)
        val dsGate = CountDownLatch(1)
        val retriever = mockk<MediaMetadataRetriever> {
            every { setDataSource(any<Context>(), any<Uri>()) } answers {
                dsEntered.countDown()
                dsGate.await(10, TimeUnit.SECONDS)
            }
            every { getFrameAtTime(any(), any()) } answers {
                Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            }
            every { release() } returns Unit
        }
        val pfRetriever = mockk<MediaMetadataRetriever> {
            every { setDataSource(any<Context>(), any<Uri>()) } returns Unit
            every { getFrameAtTime(any(), any()) } answers {
                Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            }
            every { release() } returns Unit
        }
        val extractor = newExtractor(retriever, prefetchRetriever = pfRetriever)
        val scope = CoroutineScope(Dispatchers.IO)
        var caller: Deferred<Unit>? = null
        try {
            caller = scope.async { extractor.beginSession(Uri.parse("content://test/video")) }
            assertTrue(dsEntered.await(10, TimeUnit.SECONDS))
            caller.cancel()
            dsGate.countDown()
            runCatching { withTimeout(5_000) { caller.await() } } // the reclaim ran before this returns

            assertFalse("a cancelled caller must not own a live session", extractor.hasLiveSessionForTest())
            // The open it consumed is retired exactly once — no decoder leak.
            verify(exactly = 1, timeout = 5_000) { retriever.release() }

            // The reclaim is NOT a global retirement: a newer session still
            // installs and serves.
            every { retriever.setDataSource(any<Context>(), any<Uri>()) } returns Unit
            extractor.beginSession(Uri.parse("content://test/video"))
            assertTrue(extractor.hasLiveSessionForTest())
            val frame = withTimeout(5_000) { extractor.extractFrameAtPosition("content://test/video", 1_000L) }
            assertNotNull(frame)
        } finally {
            dsGate.countDown()
            caller?.cancel()
            scope.cancel()
            extractor.release()
            withTimeout(10_000) { extractor.shutdownResult.await() }
        }
    }

    @Test
    fun `a caller cancelled after the open completed but before its await resumes retires its own session`() = runBlocking {
        // Cancellation-ownership, case 2 — the window the plain
        // submit.cancel() cannot cover: the Open child has ALREADY completed
        // (decoder installed) and the outer await's resume is sitting in the
        // caller's dispatcher queue when the caller is cancelled. Pinned
        // deterministically with a SteppingDispatcher: the queued task IS the
        // await resume, observable before it ever runs.
        val opened = CountDownLatch(1)
        val retriever = mockk<MediaMetadataRetriever> {
            every { setDataSource(any<Context>(), any<Uri>()) } answers { opened.countDown() }
            every { getFrameAtTime(any(), any()) } answers {
                Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            }
            every { release() } returns Unit
        }
        val pfRetriever = mockk<MediaMetadataRetriever> {
            every { setDataSource(any<Context>(), any<Uri>()) } returns Unit
            every { getFrameAtTime(any(), any()) } answers {
                Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            }
            every { release() } returns Unit
        }
        val extractor = newExtractor(retriever, prefetchRetriever = pfRetriever)
        val stepping = SteppingDispatcher()
        val caller = CoroutineScope(stepping).async { extractor.beginSession(Uri.parse("content://test/video")) }
        try {
            stepping.drainOnce() // beginSession commits + suspends at its await
            awaitUntil { opened.count == 0L } // native open done
            awaitUntil { stepping.pending.size >= 1 } // child resolved; the await RESUME is queued, not run

            caller.cancel()
            stepping.drainOnce() // the queued resume delivers the cancellation → reclaim

            assertFalse("the completed install must be reclaimed with the caller", extractor.hasLiveSessionForTest())
            // submit.cancel() alone could not undo this install: the decoder
            // opened fully, so the ownership-checked retirement closes it.
            verify(exactly = 1, timeout = 5_000) { retriever.release() }

            every { retriever.setDataSource(any<Context>(), any<Uri>()) } returns Unit
            extractor.beginSession(Uri.parse("content://test/video"))
            assertTrue(extractor.hasLiveSessionForTest())
            val frame = withTimeout(5_000) { extractor.extractFrameAtPosition("content://test/video", 1_000L) }
            assertNotNull(frame)
        } finally {
            caller.cancel()
            extractor.release()
            withTimeout(10_000) { extractor.shutdownResult.await() }
        }
    }

    @Test
    fun `cancelling a superseded begin never retires the newer session`() = runBlocking {
        // Cancellation-ownership, case 3: S1's caller is cancelled only AFTER
        // S2 has taken the facade identity (S2 committed and parked at its
        // own native open first). The reclaim's ownership check must see
        // sessionJob !== S1's job and retire NOTHING — S2's session and both
        // engines' state stay untouched by S1's cancellation.
        val firstEntered = CountDownLatch(1)
        val firstGate = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val secondGate = CountDownLatch(1)
        val opens = AtomicInteger(0)
        val retriever = mockk<MediaMetadataRetriever> {
            every { setDataSource(any<Context>(), any<Uri>()) } answers {
                when (opens.incrementAndGet()) {
                    1 -> {
                        firstEntered.countDown()
                        firstGate.await(10, TimeUnit.SECONDS)
                    }
                    else -> {
                        secondEntered.countDown()
                        secondGate.await(10, TimeUnit.SECONDS)
                    }
                }
            }
            every { getFrameAtTime(any(), any()) } answers {
                Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            }
            every { release() } returns Unit
        }
        val pfRetriever = mockk<MediaMetadataRetriever> {
            every { setDataSource(any<Context>(), any<Uri>()) } returns Unit
            every { getFrameAtTime(any(), any()) } answers {
                Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            }
            every { release() } returns Unit
        }
        val extractor = newExtractor(retriever, prefetchRetriever = pfRetriever)
        val stepping = SteppingDispatcher()
        val s1 = CoroutineScope(stepping).async { extractor.beginSession(Uri.parse("content://test/video")) }
        val s2Scope = CoroutineScope(Dispatchers.IO)
        var s2: Deferred<Unit>? = null
        try {
            stepping.drainOnce() // S1 commits + parks at its native open
            assertTrue(firstEntered.await(10, TimeUnit.SECONDS))

            s2 = s2Scope.async { extractor.beginSession(Uri.parse("content://test/video")) }
            // S2's Open COMMIT is observable without the lane: the engine
            // supersedes S1's parked open, which resolves S1's begin child —
            // its await resume lands in the stepped queue. (S2's native open
            // itself cannot start before gate 1 opens: S1's decode occupies
            // the single lane, so waiting for the second open here would
            // deadlock the scenario.)
            awaitUntil { stepping.pending.size >= 1 } // S2's Open superseded S1's parked begin
            assertTrue(extractor.hasLiveSessionForTest())

            s1.cancel()
            stepping.drainOnce() // S1's caller resumes with CE → reclaim sees S2's identity → retires nothing
            runCatching { withTimeout(5_000) { s1.await() } }

            firstGate.countDown() // S1's parked open resolves into its supersession
            // S1's decoder is closed by the supersession itself; S2's decoder
            // must still be OPEN (one close total, and it is not S2's).
            verify(exactly = 1, timeout = 5_000) { retriever.release() }

            secondGate.countDown()
            assertTrue("S2 must survive the older begin's cancellation", extractor.hasLiveSessionForTest())
            val frame = withTimeout(5_000) { extractor.extractFrameAtPosition("content://test/video", 1_000L) }
            assertNotNull(frame)
        } finally {
            firstGate.countDown()
            secondGate.countDown()
            s1.cancel()
            s2?.cancel()
            s2Scope.cancel()
            extractor.release()
            withTimeout(10_000) { extractor.shutdownResult.await() }
        }
    }

    @Test
    fun `a cancelled begin whose reclaim runs after a newer install leaves the newer session untouched`() = runBlocking {
        // rev-1 gap: the caller-cancellation reclaim used to check session
        // ownership under the publish lock and only THEN commit engine.end()
        // after the unlock. In that split a newer begin could install between
        // the check and the commit, and the older catch's global end (which
        // is not bound to the caller's identity) retired the newer session.
        // The fix commits the retirement inside the same critical section, so
        // check + commit are atomic against the newer begin's install.
        //
        // The fully distinguishing interleave (check passes → newer session
        // installs → end commits) has no natural suspension point to latch
        // and is not black-box schedulable, so this test pins the nearest
        // deterministic ordering with the reclaim catch running only after
        // the newer session is FULLY installed — identity swap, engine Open
        // commit, and completed native open, i.e. beginSession already
        // returned. S2 therefore runs on its own IO scope (not the stepped
        // queue, whose FIFO order could otherwise run the older catch first,
        // and whose closed gates would keep S2 parked mid-open), the native
        // gates are opened while the reclaim is still parked in the queue,
        // and only then is the queue drained to run the older catch. The
        // invariants: the newer session survives with its own decoder never
        // released, and the cancelled caller's CancellationException still
        // propagates.
        val stepping = SteppingDispatcher()
        val firstGate = CountDownLatch(1)
        val firstEntered = CountDownLatch(1)
        val secondGate = CountDownLatch(1)
        // One retriever per open, handed out in order, so "the newer
        // session's decoder was never released" is directly observable.
        val firstRetriever = mockk<MediaMetadataRetriever> {
            every { setDataSource(any<Context>(), any<Uri>()) } answers {
                firstEntered.countDown()
                firstGate.await()
                Unit
            }
            every { getFrameAtTime(any(), any()) } answers {
                Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            }
            every { release() } returns Unit
        }
        val secondRetriever = mockk<MediaMetadataRetriever> {
            every { setDataSource(any<Context>(), any<Uri>()) } answers {
                secondGate.await()
                Unit
            }
            every { getFrameAtTime(any(), any()) } answers {
                Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            }
            every { release() } returns Unit
        }
        val retrievers = ArrayDeque(listOf(firstRetriever, secondRetriever))
        val extractor = SeekPreviewExtractor(
            context = context,
            retrieverFactory = { retrievers.removeFirst() },
            prefetchRetrieverFactory = { retrievers.first() },
            dispatcher = Dispatchers.Unconfined,
            lanePool = DaemonLanePool(1),
            prefetchLanePool = DaemonLanePool(maxLanes = 1),
            scale = null,
        )
        val uri = Uri.parse("content://test/video")
        val s1 = CoroutineScope(stepping).async { extractor.beginSession(uri) }
        var s2: Deferred<Unit>? = null
        try {
            stepping.drainOnce() // S1 commits and parks at its native open
            assertTrue(firstEntered.await(10, TimeUnit.SECONDS))

            // Cancel the caller; the reclaim catch is queued on the stepped
            // dispatcher and stays there — the queue is NOT drained yet.
            s1.cancel()

            // Install the newer session on an independent scope: beginSession
            // is synchronous up to its first suspension, so S2's identity
            // swap and engine Open commit happen on its own thread, then it
            // parks waiting for its native open.
            s2 = CoroutineScope(Dispatchers.IO).async { extractor.beginSession(uri) }
            val s2Job = s2

            // Open the native gates while the reclaim is still parked: S1's
            // superseded open unwinds fail-closed and frees the lane, S2's
            // open then completes and its beginSession returns — the newer
            // session is fully installed before the old catch has run once.
            firstGate.countDown()
            secondGate.countDown()
            withTimeout(10_000) { s2Job.await() }
            assertTrue("the newer session must survive the older begin's cancellation", extractor.hasLiveSessionForTest())

            // Only now run the old caller's reclaim: with the fix it observes
            // the newer identity under the same lock and commits no retirement.
            stepping.drainOnce()
            val s1Outcome = runCatching { withTimeout(5_000) { s1.await() } }
            assertTrue(
                "the cancelled caller must surface CancellationException, got ${s1Outcome.exceptionOrNull()?.javaClass?.simpleName}",
                s1Outcome.exceptionOrNull() is CancellationException,
            )

            assertTrue("the newer session must survive the older begin's cancellation", extractor.hasLiveSessionForTest())
            val frame = withTimeout(5_000) { extractor.extractFrameAtPosition("content://test/video", 1_000L) }
            assertNotNull(frame)
            verify(exactly = 0) { secondRetriever.release() }
        } finally {
            runCatching { s1.cancel() }
            runCatching { s2?.cancel() }
            firstGate.countDown()
            secondGate.countDown()
            extractor.release()
            withTimeout(10_000) { extractor.shutdownResult.await() }
        }
    }

    @Test
    fun `a newer session's prefetch session survives an older end racing its warm-up`() = runBlocking {
        // The phase-2 ownership gap (rev-1): endSession reaps the identity in
        // phase 1 and only afterwards cancels the parked work and retires the
        // prefetch session (phase 2). A newer session can begin and warm up
        // inside that gap. If the older phase 2 retired the NEWER claim
        // unconditionally, the newer session's ensurePrefetchSession fast
        // path would keep trusting the stale token and degrade to per-extract
        // ephemeral opens (open/close per bucket). Pinned by the observable
        // that distinguishes a persistent session from ephemeral fallback:
        // the number of native prefetch installs stays at exactly two
        // (S1's + S2's) no matter how the race resolves.
        val uri = Uri.parse("content://test/video")
        repeat(12) { round ->
            val pfInstalls = AtomicInteger(0)
            val askedPf = CopyOnWriteArrayList<Long>()
            val retriever = mockk<MediaMetadataRetriever> {
                every { setDataSource(any<Context>(), any<Uri>()) } returns Unit
                every { getFrameAtTime(any(), any()) } answers {
                    Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
                }
                every { release() } returns Unit
            }
            val pfRetriever = mockk<MediaMetadataRetriever> {
                every { setDataSource(any<Context>(), any<Uri>()) } answers { pfInstalls.incrementAndGet() }
                every { getFrameAtTime(any(), any()) } answers {
                    askedPf.add(firstArg())
                    Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
                }
                every { release() } returns Unit
            }
            val extractor = newExtractor(retriever, prefetchRetriever = pfRetriever)
            var endRace: Deferred<Unit>? = null
            try {
                extractor.beginSession(uri) // S1
                extractor.prefetchNeighbors("content://test/video", 1_000L)
                awaitUntil { extractor.cacheSizeForTest() >= 2 * 1024 }

                // Start the end only after S1 is fully warmed. An eager launch
                // would let phase 1 slide into the window where S1's identity
                // is swapped but its open-await has not returned, so phase 2's
                // stale cancel kills S1's own begin — a scheduling artifact,
                // not the ownership scenario under test. Launched here, S1 is
                // immune, and S2's begin + warm-up races exactly the
                // cancel/phase-2 tail of the older end (the gap).
                val endScope = CoroutineScope(Dispatchers.IO)
                endRace = endScope.async { extractor.endSession() }

                // Wait until its phase 1 has reaped the identity, and only
                // then let S2 take over.
                awaitUntil { !extractor.hasLiveSessionForTest() }
                extractor.beginSession(uri) // S2
                extractor.prefetchNeighbors("content://test/video", 3_000L)
                awaitUntil { extractor.cacheSizeForTest() >= 4 * 1024 } // S2 warmed through its own pf session
                endRace.join()

                // The invariant: whatever the race resolved to, the prefetch
                // engine must hold exactly S1's + S2's installs. A third open
                // means S2's claim was retired underneath it and its next
                // bucket fell back to a fresh ephemeral session.
                extractor.prefetchNeighbors("content://test/video", 5_000L)
                awaitUntil { extractor.cacheSizeForTest() >= 6 * 1024 }
                assertEquals(
                    "round $round: prefetch native installs must stay at S1+S2 (ephemeral fallback detected): ${pfInstalls.get()}",
                    2,
                    pfInstalls.get(),
                )
            } finally {
                runCatching { endRace?.cancel() }
                extractor.release()
                withTimeout(10_000) { extractor.shutdownResult.await() }
            }
        }
    }
}
