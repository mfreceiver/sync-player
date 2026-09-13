package cn.vectory.syncplayer.player

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import cn.vectory.syncplayer.NoOpTestApp
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = NoOpTestApp::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SeekPreviewExtractorReleaseTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `release closes active retriever and completes cleanly`() {
        val mockRetriever = mockk<MediaMetadataRetriever>(relaxed = true)
        val extractor = SeekPreviewExtractor(
            context = context,
            retrieverFactory = { mockRetriever },
        )

        extractor.release()
        // No crash, cache cleared, clean exit
    }

    @Test
    fun `release bounds wait for prefetch job with timeout`() {
        val mockRetriever = mockk<MediaMetadataRetriever>(relaxed = true)
        val extractor = SeekPreviewExtractor(
            context = context,
            retrieverFactory = { mockRetriever },
        )

        // Calling release without active session completes immediately
        val elapsed = measureTimeMillis {
            extractor.release()
        }
        assertTrue("release without job should be fast (< 200ms), took $elapsed ms", elapsed < 200L)
    }

    @Test
    fun `release skips synchronous retriever release when join times out to avoid native concurrency`() {
        val mockRetriever = mockk<MediaMetadataRetriever>(relaxed = true)
        val extractor = SeekPreviewExtractor(
            context = context,
            retrieverFactory = { mockRetriever },
        )

        val uri = "content://media/1".toUri()
        val blockingLatch = CountDownLatch(1)
        val prefetchStartedLatch = CountDownLatch(1)
        val mockBitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        var callCount = 0

        every { mockRetriever.getFrameAtTime(any(), any()) } answers {
            callCount++
            if (callCount == 1) {
                // First call: initial extraction succeeds so schedulePrefetch is called
                mockBitmap
            } else {
                // Subsequent call from prefetch job blocks to trigger timeout on join
                prefetchStartedLatch.countDown()
                blockingLatch.await(2, TimeUnit.SECONDS)
                null
            }
        }

        kotlinx.coroutines.runBlocking {
            extractor.beginSession(uri)
            extractor.extractFrameAtPosition(uri.toString(), 1_000L)
        }

        // Wait for prefetch job to start and enter blocking call
        assertTrue(prefetchStartedLatch.await(1, TimeUnit.SECONDS))

        try {
            // Calling release will time out on join (500ms)
            extractor.release()
            // Verify that mockRetriever.release() was NOT called because timedOut is true
            verify(exactly = 0) { mockRetriever.release() }
        } finally {
            blockingLatch.countDown()
        }
    }
}
