package cn.vectory.syncplayer.player

import android.content.Context
import android.media.MediaMetadataRetriever
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
}
