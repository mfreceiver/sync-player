package cn.vectory.syncplayer.imageviewer

import androidx.lifecycle.SavedStateHandle
import cn.vectory.syncplayer.browser.UndoDeleteUseCase
import cn.vectory.syncplayer.data.repository.PlaybackRepository
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.navigation.ImageQueue
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ImageViewerViewModelSavedStateTest {

    private val videoRepository = mockk<VideoRepository>(relaxed = true)
    private val playbackRepository = mockk<PlaybackRepository>(relaxed = true)
    private val imageQueue = mockk<ImageQueue>()
    private val undoDeleteUseCase = mockk<UndoDeleteUseCase>(relaxed = true)
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { imageQueue.consumeIds() } returns emptyList()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `saved image ids restore the queue`() = runTest {
        val viewModel = ImageViewerViewModel(
            savedStateHandle = SavedStateHandle(mapOf("imageIds" to "4,5,6")),
            videoRepository = videoRepository,
            playbackRepository = playbackRepository,
            imageQueue = imageQueue,
            undoDeleteUseCase = undoDeleteUseCase,
        )

        advanceUntilIdle()

        assertEquals(listOf(4L, 5L, 6L), viewModel.allImageIds.value)
    }
}
