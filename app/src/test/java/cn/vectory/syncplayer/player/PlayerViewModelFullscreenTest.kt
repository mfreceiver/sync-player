package cn.vectory.syncplayer.player

import androidx.lifecycle.SavedStateHandle
import cn.vectory.syncplayer.browser.UndoDeleteUseCase
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
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression tests for the FULLSCREEN entry contract:
 *
 * 1. Entering fullscreen (false→true) atomically resets chrome to
 *    (isFullscreen=true, showControls=false) inside a single StateFlow update —
 *    no UI frame can observe fullscreen with stale visible controls.
 * 2. While fullscreen, the user can still toggle controls back on.
 * 3. Exiting fullscreen keeps the user's current showControls value.
 * 4. Re-asserting setFullscreen(true) while already fullscreen never resets controls.
 *
 * Asserts booleans only — never user-facing text.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelFullscreenTest {

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
    fun `entering fullscreen atomically hides controls`() = runTest {
        val vm = createVm()
        advanceUntilIdle()
        // Default chrome: not fullscreen, controls visible.
        assertFalse(vm.uiState.value.isFullscreen)
        assertTrue(vm.uiState.value.showControls)

        vm.setFullscreen(true)
        advanceUntilIdle()

        // Both flags flipped within one state snapshot — no (true, true) frame.
        assertTrue(vm.uiState.value.isFullscreen)
        assertFalse(vm.uiState.value.showControls)
    }

    @Test
    fun `toggleFullscreen reuses the same atomic entry reset`() = runTest {
        val vm = createVm()
        advanceUntilIdle()

        vm.toggleFullscreen()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.isFullscreen)
        assertFalse(vm.uiState.value.showControls)
    }

    @Test
    fun `controls can be re-enabled while fullscreen`() = runTest {
        val vm = createVm()
        advanceUntilIdle()
        vm.setFullscreen(true)
        advanceUntilIdle()

        vm.toggleControls()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.isFullscreen)
        assertTrue(vm.uiState.value.showControls)
    }

    @Test
    fun `exiting fullscreen keeps the current controls value`() = runTest {
        val vm = createVm()
        advanceUntilIdle()
        vm.setFullscreen(true)
        advanceUntilIdle()
        vm.toggleControls() // controls back on while fullscreen
        advanceUntilIdle()

        vm.setFullscreen(false)
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isFullscreen)
        // Exit does NOT force-hide; the user's last choice is preserved.
        assertTrue(vm.uiState.value.showControls)
    }

    @Test
    fun `re-asserting fullscreen does not reset controls`() = runTest {
        val vm = createVm()
        advanceUntilIdle()
        vm.setFullscreen(true)
        advanceUntilIdle()
        vm.toggleControls() // controls back on while fullscreen
        advanceUntilIdle()

        vm.setFullscreen(true)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.isFullscreen)
        assertTrue(vm.uiState.value.showControls)
    }
}
