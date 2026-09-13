package cn.vectory.syncplayer.player

import androidx.lifecycle.SavedStateHandle
import cn.vectory.syncplayer.browser.UndoDeleteUseCase
import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import cn.vectory.syncplayer.data.repository.PlaybackRepository
import cn.vectory.syncplayer.data.repository.UserPreferencesRepository
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.navigation.VideoQueue
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * AND-014 regression tests for the gesture-seek commit point.
 *
 * The seek overlay during a horizontal gesture previews `frozenBase + offset`
 * (base captured at gesture start). The commit previously recomputed the
 * target from the LIVE playback position, which keeps advancing while the
 * finger is down — the committed target overshot what the overlay showed.
 *
 * The commit API now receives the FINAL TARGET computed from the frozen base
 * (`base + offset`, clamped in the UI layer that owns both values). The
 * ViewModel must record exactly that target and must never re-add the live
 * [PlayerViewModel.currentPosition] to the argument.
 *
 * Asserts on numeric targets and state shape only — never on user-facing text.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelGestureSeekTest {

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
    )

    private fun createVm(): PlayerViewModel = PlayerViewModel(
        savedStateHandle = SavedStateHandle(),
        videoRepository = videoRepository,
        playbackRepository = playbackRepository,
        userPrefs = userPrefs,
        videoQueue = videoQueue,
        undoDeleteUseCase = undoDeleteUseCase,
        ioDispatcher = testDispatcher,
    )

    private fun TestScope.loadVideo42(vm: PlayerViewModel) {
        coEvery { videoRepository.getById(42L) } returns videoEntity(42L)
        coEvery { playbackRepository.getByVideoId(42L) } returns null
        vm.loadVideo(42L)
        advanceUntilIdle()
    }

    @Test
    fun `gesture seek commit uses frozen base not live position`() = runTest {
        val vm = createVm()
        loadVideo42(vm)

        // Gesture starts with the player at 10s — the FROZEN base the overlay
        // is computed from.
        vm.updatePlaybackProgress(10_000L, 60_000L)
        vm.onGestureSeekStarted()
        // While the gesture is active, playback keeps advancing (position poll
        // still runs) and the live position reaches 20s. The commit must NOT
        // use this live value.
        vm.updatePlaybackProgress(20_000L, 60_000L)
        // The overlay previewed base(10s) + offset(5s) = 15s; the UI commits
        // that FINAL TARGET. (The old offset-based formula would land on the
        // live 20s + 15s = 35s.)
        vm.onGestureSeekFinished(15_000L)
        advanceUntilIdle()

        assertEquals(15_000L, vm.currentPosition.value)
        assertEquals(15_000L, vm.uiState.value.seekTarget)
        assertFalse(vm.gestureSeekState.value.isSeeking)
    }

    @Test
    fun `gesture seek commit clamps target into duration`() = runTest {
        val vm = createVm()
        loadVideo42(vm)
        vm.updatePlaybackProgress(0L, 60_000L)

        // A committed target beyond the media duration must clamp to the end.
        vm.onGestureSeekFinished(90_000L)
        advanceUntilIdle()

        assertEquals(60_000L, vm.currentPosition.value)
        assertEquals(60_000L, vm.uiState.value.seekTarget)
    }
}
