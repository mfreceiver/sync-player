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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Verifies the player-error non-leak contract: `onPlayerError()` MUST NOT accept
 * or carry the underlying PlaybackException.message. The UI state exposes only a
 * boolean flag (`hasPlayerError`); the actual display text is resolved from
 * `R.string.player_error_generic` by the Composable.
 *
 * Asserts on the state shape only — never on user-facing string text.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelErrorTest {

    private val videoRepository = mockk<VideoRepository>(relaxed = true)
    private val playbackRepository = mockk<PlaybackRepository>(relaxed = true)
    private val userPrefs = mockk<UserPreferencesRepository>(relaxed = true)
    private val videoQueue = mockk<VideoQueue>()
    private val undoDeleteUseCase = mockk<UndoDeleteUseCase>(relaxed = true)
    private val ioTestDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
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
        ioDispatcher = ioTestDispatcher,
    )

    @Test
    fun `onPlayerError sets flag and does not accept a message`() {
        val vm = createVm()

        // The API no longer takes a message parameter — a PlaybackException whose
        // message carries a secret path must not be passable into UI state.
        vm.onPlayerError()

        assertTrue("hasPlayerError should be true after onPlayerError", vm.uiState.value.hasPlayerError)
        assertFalse("isPlaying should be false after error", vm.uiState.value.isPlaying)
    }

    @Test
    fun `clearPlayerError resets flag`() {
        val vm = createVm()
        vm.onPlayerError()
        assertTrue(vm.uiState.value.hasPlayerError)

        vm.clearPlayerError()

        assertFalse("hasPlayerError should be false after clear", vm.uiState.value.hasPlayerError)
    }

    @Test
    fun `identical consecutive errors get distinct seq`() {
        val vm = createVm()
        val mappedError = "mapped-error"

        vm.showError(mappedError)
        assertTrue("first error event should be visible", vm.uiState.value.error != null)
        val firstSeq = vm.uiState.value.error!!.seq

        vm.showError(mappedError)
        assertTrue("second error event should be visible", vm.uiState.value.error != null)
        val secondSeq = vm.uiState.value.error!!.seq

        assertTrue("each error event must advance its sequence", secondSeq > firstSeq)
    }
}
