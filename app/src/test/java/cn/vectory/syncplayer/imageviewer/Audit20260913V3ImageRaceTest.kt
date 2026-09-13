package cn.vectory.syncplayer.imageviewer

import androidx.lifecycle.SavedStateHandle
import cn.vectory.syncplayer.browser.UndoDeleteUseCase
import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import cn.vectory.syncplayer.data.repository.PlaybackRepository
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.navigation.ImageQueue
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
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

/**
 * V3 / CQ-003: ImageViewerViewModel stale DB read race in loadImage guarded by loadGeneration.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Audit20260913V3ImageRaceTest {

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
        relativePath = "image$id.jpg",
        fileName = "image$id.jpg",
        fileSize = 1000L,
    )

    private fun createVm(vararg gates: Pair<Long, CompletableDeferred<Unit>>): ImageViewerViewModel {
        gates.forEach { (id, gate) ->
            coEvery { videoRepository.getById(id) } coAnswers {
                gate.await()
                imageEntity(id)
            }
        }
        return ImageViewerViewModel(
            savedStateHandle = SavedStateHandle(),
            videoRepository = videoRepository,
            playbackRepository = playbackRepository,
            imageQueue = imageQueue,
            undoDeleteUseCase = undoDeleteUseCase,
            ioDispatcher = testDispatcher,
        )
    }

    /** Test 1 (REPRODUCTION): stale read completing last must not win. */
    @Test
    fun `out-of-order completion keeps the newest requested image`() = runTest {
        val gate1 = CompletableDeferred<Unit>()
        val gate2 = CompletableDeferred<Unit>()
        val vm = createVm(1L to gate1, 2L to gate2)

        // User navigates: image 1 -> image 2 (both reads in flight).
        vm.loadImage(1L)
        advanceUntilIdle() // load(1) now suspended at gate1
        vm.loadImage(2L)
        advanceUntilIdle() // load(2) now suspended at gate2

        // load(2) completes FIRST (fast read of the newer request).
        gate2.complete(Unit)
        advanceUntilIdle()
        assertEquals(2L, vm.uiState.value.currentImage?.id)

        // Then the STALE load(1) completes (slow read of the older request).
        gate1.complete(Unit)
        advanceUntilIdle()

        assertEquals(
            "currentImage must still be image 2 (stale load(1) must not overwrite it); " +
                "actual currentImage.id=${vm.uiState.value.currentImage?.id}",
            2L,
            vm.uiState.value.currentImage?.id
        )
    }

    /** Test 2 (control): in-order completion. */
    @Test
    fun `in-order completion shows the last requested image`() = runTest {
        val gate1 = CompletableDeferred<Unit>()
        val gate2 = CompletableDeferred<Unit>()
        val vm = createVm(1L to gate1, 2L to gate2)

        vm.loadImage(1L)
        advanceUntilIdle()
        vm.loadImage(2L)
        advanceUntilIdle()

        gate1.complete(Unit)
        advanceUntilIdle()
        gate2.complete(Unit)
        advanceUntilIdle()

        assertEquals(2L, vm.uiState.value.currentImage?.id)
    }
}
