package cn.vectory.syncplayer.player

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import cn.vectory.syncplayer.browser.UndoDeleteUseCase
import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import cn.vectory.syncplayer.data.repository.PlaybackRepository
import cn.vectory.syncplayer.data.repository.UserPreferencesRepository
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.navigation.VideoQueue
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * AND-001 regression tests for [PlayerViewModel.saveProgress].
 *
 * The recents-swipe teardown path cancels `viewModelScope` before/while the
 * composition disposes. A `viewModelScope.launch` save is then a silent no-op
 * and the user's playback position is lost. The save must run on a scope that
 * is independent of `viewModelScope` so it survives that cancellation.
 *
 * Asserts on invocation counts and argument values only — never on
 * user-facing message text.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelSaveProgressTest {

    private val videoRepository = mockk<VideoRepository>(relaxed = true)
    private val playbackRepository = mockk<PlaybackRepository>(relaxed = true)
    private val userPrefs = mockk<UserPreferencesRepository>(relaxed = true)
    private val videoQueue = mockk<VideoQueue>()
    private val undoDeleteUseCase = mockk<UndoDeleteUseCase>(relaxed = true)
    private val testDispatcher = StandardTestDispatcher()

    /**
     * The save must complete even though nothing pumps the scheduler after
     * the teardown began, so the injected IO dispatcher runs eagerly
     * (Unconfined). A Standard dispatcher would leave the save queued until
     * some advanceUntilIdle() — exactly the dependency AND-001 removes.
     */
    private val ioTestDispatcher = UnconfinedTestDispatcher()

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

    private fun createVm(): PlayerViewModel = PlayerViewModel(
        savedStateHandle = SavedStateHandle(),
        videoRepository = videoRepository,
        playbackRepository = playbackRepository,
        userPrefs = userPrefs,
        videoQueue = videoQueue,
        undoDeleteUseCase = undoDeleteUseCase,
        ioDispatcher = ioTestDispatcher,
    )

    private fun TestScope.loadVideo42(vm: PlayerViewModel) {
        coEvery { videoRepository.getById(42L) } returns videoEntity(42L)
        coEvery { playbackRepository.getByVideoId(42L) } returns null
        vm.loadVideo(42L)
        advanceUntilIdle()
    }

    @Test
    fun `saveProgress persists position and duration via repository`() = runTest {
        val vm = createVm()
        loadVideo42(vm)

        vm.saveProgress(position = 12345L, duration = 60000L)
        advanceUntilIdle()

        coVerify(exactly = 1) { playbackRepository.saveProgress(42L, 12345L, 60000L) }
    }

    @Test
    fun `saveProgress survives viewModelScope cancellation`() = runTest {
        val vm = createVm()
        // Load the video while viewModelScope is still alive so state.videoItem is set.
        loadVideo42(vm)

        // Simulate the recents-swipe teardown: viewModelScope cancelled (a
        // viewModelScope.launch save would be a silent no-op from here on).
        vm.viewModelScope.cancel()

        vm.saveProgress(position = 12345L, duration = 60000L)
        advanceUntilIdle()

        coVerify(exactly = 1) { playbackRepository.saveProgress(42L, 12345L, 60000L) }
    }

    @Test
    fun `saveProgress is a no-op when no video is loaded`() = runTest {
        val vm = createVm()

        vm.saveProgress(position = 100L, duration = 200L)
        advanceUntilIdle()

        coVerify(exactly = 0) { playbackRepository.saveProgress(any(), any(), any()) }
    }
}
