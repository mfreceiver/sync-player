package cn.vectory.syncplayer.imageviewer

import androidx.lifecycle.SavedStateHandle
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
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Regression tests for [ImageViewerViewModel.toggleTrashed] focusing on the 3rd-round review
 * (FAIL 7.6) contract:
 *
 * 1. The [onSuccess] callback receives the FIXED image id captured at call time — even when
 *    the user has already switched to another image by the time the toggle completes.
 * 2. The playback-record refresh launched after the toggle only updates `state.playbackRecord`
 *    if the user is still on the SAME image — switching to another image before the refresh
 *    completes will NOT overwrite the new item's record.
 *
 * Mirrors [cn.vectory.syncplayer.player.PlayerViewModelToggleTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ImageViewerViewModelToggleTest {

    private val videoRepository = mockk<VideoRepository>(relaxed = true)
    private val playbackRepository = mockk<PlaybackRepository>(relaxed = true)
    private val imageQueue = mockk<ImageQueue>()
    private val undoDeleteUseCase = mockk<UndoDeleteUseCase>(relaxed = true)
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = runTest {
        Dispatchers.setMain(testDispatcher)
        every { imageQueue.consumeIds() } returns emptyList()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun imageEntity(id: Long) = VideoItemEntity(
        id = id,
        relativePath = "image$id.jpg",
        fileName = "image$id.jpg",
        fileSize = 1000L,
    )

    private fun playbackRecord(id: Long, trashed: Boolean) =
        PlaybackRecordEntity(videoItemId = id, isTrashed = trashed)

    private fun createVm(): ImageViewerViewModel = ImageViewerViewModel(
        savedStateHandle = SavedStateHandle(),
        videoRepository = videoRepository,
        playbackRepository = playbackRepository,
        imageQueue = imageQueue,
        undoDeleteUseCase = undoDeleteUseCase,
    )

    @Test
    fun `toggleTrashed callback receives the captured imageId, not the current state id`() = runTest {
        val vm = createVm()
        val originalId = 100L
        val nextId = 200L

        coEvery { videoRepository.getById(originalId) } returns imageEntity(originalId)
        coEvery { playbackRepository.getByVideoId(originalId) } returns playbackRecord(originalId, false)
        vm.loadImage(originalId)
        advanceUntilIdle()
        assertEquals(originalId, vm.uiState.value.currentImage?.id)

        val onSuccessSlot = slot<() -> Unit>()
        every {
            undoDeleteUseCase.performTrashToggleNoGuard(any(), any(), any(), capture(onSuccessSlot))
        } returns Unit

        var capturedCallbackId: Long? = null
        vm.toggleTrashed { completedId -> capturedCallbackId = completedId }
        advanceUntilIdle()

        // Switch to nextId BEFORE the toggle completes.
        coEvery { videoRepository.getById(nextId) } returns imageEntity(nextId)
        coEvery { playbackRepository.getByVideoId(nextId) } returns playbackRecord(nextId, false)
        vm.loadImage(nextId)
        advanceUntilIdle()
        assertEquals(nextId, vm.uiState.value.currentImage?.id)

        // Fire the toggle's onSuccess. Lambda was constructed at toggle time → captured imageId=100.
        coEvery { playbackRepository.getByVideoId(originalId) } returns playbackRecord(originalId, true)
        onSuccessSlot.captured.invoke()
        advanceUntilIdle()

        assertEquals(
            "callback must pass the originally-captured imageId, not the current state's id",
            originalId,
            capturedCallbackId,
        )
    }

    @Test
    fun `playback-record refresh does not overwrite the new image when user switched before completion`() = runTest {
        val vm = createVm()
        val originalId = 100L
        val nextId = 200L

        coEvery { videoRepository.getById(originalId) } returns imageEntity(originalId)
        coEvery { playbackRepository.getByVideoId(originalId) } returns playbackRecord(originalId, false)
        vm.loadImage(originalId)
        advanceUntilIdle()

        val onSuccessSlot = slot<() -> Unit>()
        every {
            undoDeleteUseCase.performTrashToggleNoGuard(any(), any(), any(), capture(onSuccessSlot))
        } returns Unit

        vm.toggleTrashed { /* no-op */ }
        advanceUntilIdle()

        // Switch to nextId before onSuccess fires.
        coEvery { videoRepository.getById(nextId) } returns imageEntity(nextId)
        coEvery { playbackRepository.getByVideoId(nextId) } returns playbackRecord(nextId, false)
        vm.loadImage(nextId)
        advanceUntilIdle()
        val nextRecord = vm.uiState.value.playbackRecord
        assertEquals(nextId, nextRecord?.videoItemId)

        // Fire onSuccess; the refresh queries originalId but MUST NOT clobber nextId's record.
        coEvery { playbackRepository.getByVideoId(originalId) } returns playbackRecord(originalId, true)
        onSuccessSlot.captured.invoke()
        advanceUntilIdle()

        val finalRecord = vm.uiState.value.playbackRecord
        assertEquals(
            "playback record must still be nextId's (refresh must not clobber the new image)",
            nextId,
            finalRecord?.videoItemId,
        )
        assertNotEquals(
            "playback record must NOT be originalId's after switching to nextId",
            originalId,
            finalRecord?.videoItemId,
        )
    }

    @Test
    fun `toggleTrashed is a no-op when no image is loaded`() = runTest {
        val vm = createVm()
        var capturedCallbackId: Long? = null

        vm.toggleTrashed { capturedCallbackId = it }
        advanceUntilIdle()

        assertNull(capturedCallbackId)
        coVerify(exactly = 0) {
            undoDeleteUseCase.performTrashToggleNoGuard(any(), any(), any(), any())
        }
    }
}
