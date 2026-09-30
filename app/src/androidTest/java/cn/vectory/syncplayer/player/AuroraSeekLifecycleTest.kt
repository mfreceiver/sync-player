package cn.vectory.syncplayer.player

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.vectory.syncplayer.browser.UndoDeleteUseCase
import cn.vectory.syncplayer.data.repository.PlaybackRepository
import cn.vectory.syncplayer.data.repository.UserPreferencesRepository
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.navigation.VideoQueue
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AuroraSeekLifecycleTest {

    @Test
    fun `preview session ends by releasing its retriever`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val retriever = RecordingRetriever()
        val extractor = SeekPreviewExtractor(
            context = context,
            retrieverFactory = { retriever },
            dispatcher = Dispatchers.Unconfined,
        )

        try {
            extractor.beginSession(Uri.parse("content://cn.vectory.syncplayer.test/preview"))
            extractor.endSession()

            // Close is asynchronous — the retriever must still be released exactly once.
            assertTrue(
                "session retriever must be released after endSession",
                retriever.released.await(10, TimeUnit.SECONDS),
            )
            assertEquals(1, retriever.releaseCount)
        } finally {
            // Return the shared lane capacity and wait for full drain so this
            // test cannot pollute the shared pool for later tests.
            extractor.release()
            withTimeout(10_000) { extractor.shutdownResult.await() }
        }
    }

    @Test
    fun `preview extraction propagates cancellation instead of hiding it`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val retriever = RecordingRetriever(
            onSetDataSource = { throw CancellationException() },
        )
        val extractor = SeekPreviewExtractor(
            context = context,
            retrieverFactory = { retriever },
            dispatcher = Dispatchers.Unconfined,
        )

        try {
            try {
                extractor.extractFrameAtPosition("content://cn.vectory.syncplayer.test/preview", 1_000L)
                fail("CancellationException must propagate")
            } catch (_: CancellationException) {
                // Expected: cancellation is never converted to a missing preview.
            }
            assertTrue(
                "ephemeral retriever must be released after failed open",
                retriever.released.await(10, TimeUnit.SECONDS),
            )
            assertEquals(1, retriever.releaseCount)
        } finally {
            // Whole-body cleanup: the finally covers the entire test body so
            // ANY failure above still returns the shared lane capacity and
            // waits for full drain — this test cannot pollute the shared
            // pool for later tests.
            extractor.release()
            withTimeout(10_000) { extractor.shutdownResult.await() }
        }
    }

    @Test
    fun `cancel scrub preserves committed position and target`() = runBlocking {
        val userPrefs = mockk<UserPreferencesRepository>(relaxed = true)
        coEvery { userPrefs.getVolumeBalance() } returns false
        coEvery { userPrefs.getQuickRecycle() } returns true
        val queue = mockk<VideoQueue>()
        every { queue.consumeIds() } returns emptyList()
        every { queue.getIds() } returns emptyList()
        val viewModel = PlayerViewModel(
            savedStateHandle = SavedStateHandle(),
            videoRepository = mockk<VideoRepository>(relaxed = true),
            playbackRepository = mockk<PlaybackRepository>(relaxed = true),
            userPrefs = userPrefs,
            videoQueue = queue,
            undoDeleteUseCase = mockk<UndoDeleteUseCase>(relaxed = true),
            ioDispatcher = Dispatchers.Unconfined,
        )
        viewModel.updatePlaybackProgress(8_000L, 60_000L)
        viewModel.onProgressScrubStarted(8_000L)
        viewModel.onProgressScrubChanged(25_000L, factor = 0.1f, isFineMode = true)
        viewModel.onProgressScrubCancelled()

        assertEquals(8_000L, viewModel.currentPosition.value)
        assertEquals(-1L, viewModel.uiState.value.seekTarget)
    }
}

private class RecordingRetriever(
    var releaseCount: Int = 0,
    private val onSetDataSource: () -> Unit = {},
) : MediaMetadataRetriever() {
    /** Fires after release() — lets tests await async close deterministically. */
    val released = CountDownLatch(1)

    override fun setDataSource(context: Context, uri: Uri) = onSetDataSource()

    override fun getFrameAtTime(microseconds: Long, option: Int): android.graphics.Bitmap? = null

    override fun release() {
        releaseCount++
        released.countDown()
    }
}
