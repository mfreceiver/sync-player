package cn.vectory.syncplayer.player

import androidx.lifecycle.SavedStateHandle
import cn.vectory.syncplayer.browser.UndoDeleteUseCase
import cn.vectory.syncplayer.data.local.entity.PlaybackRecordEntity
import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import cn.vectory.syncplayer.data.repository.PlaybackRepository
import cn.vectory.syncplayer.data.repository.UserPreferencesRepository
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.navigation.VideoQueue
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
 * Regression tests for [PlayerViewModel.toggleTrashed] focusing on the 3rd-round review
 * (FAIL 7.6) contract:
 *
 * 1. The [onSuccess] callback receives the FIXED video id captured at call time — even when
 *    the user has already switched to another video by the time the toggle completes. The
 *    caller (PlayerScreen) uses this id as the undo Snackbar's `expectedId`, so a stale
 *    Snackbar can never undo a newer toggle on a different video.
 * 2. The playback-record refresh launched after the toggle only updates `state.playbackRecord`
 *    if the user is still on the SAME video — switching to another video (e.g. double-tap next)
 *    before the refresh completes will NOT overwrite the new item's record.
 *
 * Asserts on ids and state shape only — never on user-facing message text.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelToggleTest {

    private val videoRepository = mockk<VideoRepository>(relaxed = true)
    private val playbackRepository = mockk<PlaybackRepository>(relaxed = true)
    private val userPrefs = mockk<UserPreferencesRepository>(relaxed = true)
    private val videoQueue = mockk<VideoQueue>()
    private val undoDeleteUseCase = mockk<UndoDeleteUseCase>(relaxed = true)
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = runTest {
        Dispatchers.setMain(testDispatcher)
        every { videoQueue.consumeIds() } returns emptyList()
        every { videoQueue.getIds() } returns emptyList()
        coEvery { userPrefs.getVolumeBalance() } returns false
        coEvery { userPrefs.getQuickRecycle() } returns true
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun videoEntity(id: Long) = VideoItemEntity(
        id = id,
        relativePath = "video$id.mp4",
        fileName = "video$id.mp4",
        fileSize = 1000L,
        volumeId = "vol-active"
    )

    private fun playbackRecord(id: Long, trashed: Boolean) =
        PlaybackRecordEntity(videoItemId = id, isTrashed = trashed)

    private fun createVm(): PlayerViewModel = PlayerViewModel(
        savedStateHandle = SavedStateHandle(),
        videoRepository = videoRepository,
        playbackRepository = playbackRepository,
        userPrefs = userPrefs,
        videoQueue = videoQueue,
        undoDeleteUseCase = undoDeleteUseCase,
        ioDispatcher = testDispatcher,
    )

    @Test
    fun `toggleTrashed callback receives the captured videoId, not the current state id`() = runTest {
        val vm = createVm()
        val originalId = 100L
        val nextId = 200L

        // Load originalId first.
        coEvery { videoRepository.getById(originalId) } returns videoEntity(originalId)
        coEvery { playbackRepository.getByVideoId(originalId) } returns playbackRecord(originalId, false)
        vm.loadVideo(originalId)
        advanceUntilIdle()
        assertEquals(originalId, vm.uiState.value.videoItem?.id)

        // Capture the onSuccess lambda that VM passes into performTrashToggleNoGuard.
        val onSuccessSlot = slot<() -> Unit>()
        every {
            undoDeleteUseCase.performTrashToggleNoGuard(any(), any(), any(), capture(onSuccessSlot))
        } returns Unit

        // Fire toggle; the captured id should be originalId (=100).
        var capturedCallbackId: Long? = null
        vm.toggleTrashed { completedId -> capturedCallbackId = completedId }
        advanceUntilIdle()

        // Simulate the user double-tapping "next" BEFORE the toggle-use-case completes: load
        // a different video so state.videoItem.id is now nextId (=200).
        coEvery { videoRepository.getById(nextId) } returns videoEntity(nextId)
        coEvery { playbackRepository.getByVideoId(nextId) } returns playbackRecord(nextId, false)
        vm.loadVideo(nextId)
        advanceUntilIdle()
        assertEquals(nextId, vm.uiState.value.videoItem?.id)

        // Now the toggle-use-case completes and fires its onSuccess lambda. The lambda was
        // constructed at toggle time and captured videoId=100, so onSuccess(100) must run —
        // NOT onSuccess(200) (which would let a stale Snackbar undo a toggle on the new video).
        coEvery { playbackRepository.getByVideoId(originalId) } returns playbackRecord(originalId, true)
        onSuccessSlot.captured.invoke()
        advanceUntilIdle()

        assertEquals(
            "callback must pass the originally-captured videoId, not the current state's id",
            originalId,
            capturedCallbackId,
        )
    }

    @Test
    fun `playback-record refresh does not overwrite the new video when user switched before completion`() = runTest {
        val vm = createVm()
        val originalId = 100L
        val nextId = 200L

        // Load originalId; its record says isTrashed=false.
        coEvery { videoRepository.getById(originalId) } returns videoEntity(originalId)
        coEvery { playbackRepository.getByVideoId(originalId) } returns playbackRecord(originalId, false)
        vm.loadVideo(originalId)
        advanceUntilIdle()

        val onSuccessSlot = slot<() -> Unit>()
        every {
            undoDeleteUseCase.performTrashToggleNoGuard(any(), any(), any(), capture(onSuccessSlot))
        } returns Unit

        vm.toggleTrashed { /* no-op */ }
        advanceUntilIdle()

        // Switch to nextId BEFORE the toggle-use-case fires onSuccess.
        coEvery { videoRepository.getById(nextId) } returns videoEntity(nextId)
        coEvery { playbackRepository.getByVideoId(nextId) } returns playbackRecord(nextId, false)
        vm.loadVideo(nextId)
        advanceUntilIdle()
        val nextRecord = vm.uiState.value.playbackRecord
        assertEquals(nextId, nextRecord?.videoItemId)

        // Fire the toggle's onSuccess now. The refresh coroutine will query originalId's record
        // (which is now isTrashed=true post-toggle), but MUST NOT overwrite nextId's record.
        coEvery { playbackRepository.getByVideoId(originalId) } returns playbackRecord(originalId, true)
        onSuccessSlot.captured.invoke()
        advanceUntilIdle()

        val finalRecord = vm.uiState.value.playbackRecord
        assertEquals(
            "playback record must still be nextId's (refresh must not clobber the new video)",
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
    fun `toggleTrashed is a no-op when no video is loaded`() = runTest {
        val vm = createVm()
        // No loadVideo call → state.videoItem is null → toggle must early-return without
        // touching the use case. The callback is also never invoked.
        var capturedCallbackId: Long? = null

        vm.toggleTrashed { capturedCallbackId = it }
        advanceUntilIdle()

        assertNull(capturedCallbackId)
        // Verify performTrashToggleNoGuard was never called.
        coVerify(exactly = 0) {
            undoDeleteUseCase.performTrashToggleNoGuard(any(), any(), any(), any())
        }
    }
}
