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
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CancellationException

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

        extractor.beginSession(Uri.parse("content://cn.vectory.syncplayer.test/preview"))
        extractor.endSession()

        assertEquals(1, retriever.releaseCount)
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
            extractor.extractFrameAtPosition("content://cn.vectory.syncplayer.test/preview", 1_000L)
            fail("CancellationException must propagate")
        } catch (_: CancellationException) {
            // Expected: cancellation is never converted to a missing preview.
        }
        assertEquals(1, retriever.releaseCount)
    }

    @Test
    fun `cancel scrub preserves committed position and target`() = runBlocking {
        val userPrefs = mockk<UserPreferencesRepository>(relaxed = true)
        coEvery { userPrefs.getVolumeBalance() } returns false
        coEvery { userPrefs.getQuickRecycle() } returns true
        val queue = mockk<VideoQueue>()
        every { queue.consumeIds(any()) } returns emptyList()
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
    override fun setDataSource(context: Context, uri: Uri) = onSetDataSource()

    override fun getFrameAtTime(microseconds: Long, option: Int): android.graphics.Bitmap? = null

    override fun release() {
        releaseCount++
    }
}
