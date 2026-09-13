package cn.vectory.syncplayer.imageviewer

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import cn.vectory.syncplayer.browser.UndoDeleteUseCase
import cn.vectory.syncplayer.data.local.entity.PlaybackRecordEntity
import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import cn.vectory.syncplayer.data.repository.PlaybackRepository
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.navigation.ImageQueue
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class Audit20260913V4ImageViewerErrorAndSaveTest {

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

    private fun imageEntity(id: Long) = VideoItemEntity(
        id = id,
        relativePath = "img$id.jpg",
        fileName = "img$id.jpg",
        fileSize = 1024L,
    )

    private fun createVm(): ImageViewerViewModel = ImageViewerViewModel(
        savedStateHandle = SavedStateHandle(),
        videoRepository = videoRepository,
        playbackRepository = playbackRepository,
        imageQueue = imageQueue,
        undoDeleteUseCase = undoDeleteUseCase,
        ioDispatcher = testDispatcher,
    )

    @Test
    fun `AND-030 consecutive identical errors have distinct sequence numbers`() = runTest {
        val vm = createVm()
        assertNull(vm.imageError.value)

        vm.showError("Failed to delete")
        val ev1 = vm.imageError.value
        assertNotNull(ev1)
        assertEquals("Failed to delete", ev1?.message)

        vm.showError("Failed to delete")
        val ev2 = vm.imageError.value
        assertNotNull(ev2)
        assertEquals("Failed to delete", ev2?.message)
        assertNotEquals("Consecutive identical errors must produce distinct seq numbers", ev1?.seq, ev2?.seq)

        vm.clearError()
        assertNull(vm.imageError.value)
    }

    @Test
    fun `AND-031 markCompleted runs on progressSaveScope and survives viewModelScope cancellation`() = runTest {
        val vm = createVm()
        val imageId = 42L
        val gate = CompletableDeferred<Unit>()
        var progressSaved = false

        coEvery { videoRepository.getById(imageId) } returns imageEntity(imageId)
        coEvery { playbackRepository.getByVideoId(imageId) } returns PlaybackRecordEntity(videoItemId = imageId)
        coEvery { playbackRepository.saveProgress(imageId, 1L, 1L) } coAnswers {
            gate.await()
            progressSaved = true
        }

        vm.loadImage(imageId)
        testDispatcher.scheduler.advanceTimeBy(100)

        // Cancel viewModelScope (simulating recents swipe-away) while saveProgress is suspended
        vm.viewModelScope.cancel()

        // Unblock saveProgress in progressSaveScope
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals("saveProgress should complete despite viewModelScope cancellation", true, progressSaved)
        coVerify(exactly = 1) { playbackRepository.saveProgress(imageId, 1L, 1L) }
    }
}
