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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
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
 * fast-fail, close-failure visibility via shutdown, and the byte-budgeted
 * cache that never recycles bitmaps the UI may still hold.
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
    ) = SeekPreviewExtractor(
        context = context,
        retrieverFactory = { retriever },
        dispatcher = dispatcher,
        lanePool = lanePool,
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

            // Seek while paused: 2000 parks in native, then 3000 supersedes it.
            val stale = scope.async { extractor.extractFrameAtPosition("content://test/v", 2_000L) }
            val newest = scope.async { extractor.extractFrameAtPosition("content://test/v", 3_000L) }
            assertTrue(secondDecodeEntered.await(10, TimeUnit.SECONDS))
            assertNull(
                "intermediate seek target is discarded as soon as a newer one registers",
                withTimeout(5_000) { stale.await() },
            )

            secondDecodeGate.countDown()
            val published = withTimeout(5_000) { newest.await() }
            assertNotNull(published)
            // Native calls are bounded by 2..3 depending on interleaving: if
            // the worker claims the STALE 2000 seek before 3000 registers,
            // its parked decode is discarded and 3000 decodes again (3
            // calls); if 3000 registers first, the SECOND native call IS
            // 3000's and 2000 is dropped without ever decoding (2 calls).
            // Both interleavings satisfy the contract asserted below.
            assertTrue(
                "native calls must stay bounded: ${frameCalls.get()}",
                frameCalls.get() in 2..3,
            )
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
}
