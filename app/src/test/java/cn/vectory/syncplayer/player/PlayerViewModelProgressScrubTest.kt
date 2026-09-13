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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelProgressScrubTest {
    private val videoRepository = mockk<VideoRepository>(relaxed = true)
    private val playbackRepository = mockk<PlaybackRepository>(relaxed = true)
    private val userPrefs = mockk<UserPreferencesRepository>(relaxed = true)
    private val videoQueue = mockk<VideoQueue>()
    private val undoDeleteUseCase = mockk<UndoDeleteUseCase>(relaxed = true)
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = runTest {
        Dispatchers.setMain(dispatcher)
        every { videoQueue.consumeIds() } returns emptyList()
        every { videoQueue.getIds() } returns emptyList()
        coEvery { userPrefs.getVolumeBalance() } returns false
        coEvery { userPrefs.getQuickRecycle() } returns true
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel() = PlayerViewModel(
        savedStateHandle = SavedStateHandle(),
        videoRepository = videoRepository,
        playbackRepository = playbackRepository,
        userPrefs = userPrefs,
        videoQueue = videoQueue,
        undoDeleteUseCase = undoDeleteUseCase,
        ioDispatcher = dispatcher,
    )

    @Test
    fun `move emits only progress scrub state not player ui state`() = runTest {
        val viewModel = createViewModel()
        viewModel.updatePlaybackProgress(10_000L, 60_000L)
        viewModel.onProgressScrubStarted(10_000L)
        val playerStates = mutableListOf<PlayerUiState>()
        val scrubStates = mutableListOf<ProgressScrubUiState>()
        val playerJob: Job = launch { viewModel.uiState.collect { playerStates += it } }
        val scrubJob: Job = launch { viewModel.progressScrubState.collect { scrubStates += it } }
        runCurrent()
        playerStates.clear()
        scrubStates.clear()

        viewModel.onProgressScrubChanged(25_000L, factor = 0.25f, isFineMode = true)
        runCurrent()

        assertTrue(playerStates.isEmpty())
        assertEquals(1, scrubStates.size)
        assertEquals(25_000L, scrubStates.single().targetPositionMs)
        assertEquals(0.25f, scrubStates.single().factor)
        assertTrue(scrubStates.single().isFineMode)
        playerJob.cancel()
        scrubJob.cancel()
    }

    @Test
    fun `finish commits once while cancel keeps last committed position`() = runTest {
        val viewModel = createViewModel()
        viewModel.updatePlaybackProgress(12_000L, 60_000L)

        viewModel.onProgressScrubStarted(12_000L)
        viewModel.onProgressScrubChanged(30_000L, factor = 1f, isFineMode = false)
        viewModel.onProgressScrubFinished(30_000L)

        assertEquals(30_000L, viewModel.currentPosition.value)
        assertEquals(30_000L, viewModel.uiState.value.seekTarget)
        assertFalse(viewModel.uiState.value.isUserSeeking)
        assertFalse(viewModel.progressScrubState.value.isActive)

        viewModel.onProgressScrubStarted(30_000L)
        viewModel.onProgressScrubChanged(45_000L, factor = 0.1f, isFineMode = true)
        viewModel.onProgressScrubCancelled()

        assertEquals(30_000L, viewModel.currentPosition.value)
        assertEquals(30_000L, viewModel.uiState.value.seekTarget)
        assertFalse(viewModel.uiState.value.isUserSeeking)
        assertFalse(viewModel.progressScrubState.value.isActive)
    }

    @Test
    fun `load video resets both scrub state and user seeking flag`() = runTest {
        val viewModel = createViewModel()
        val item = VideoItemEntity(
            id = 42L,
            relativePath = "video42.mp4",
            fileName = "video42.mp4",
            fileSize = 1_000L,
        )
        coEvery { videoRepository.getById(42L) } returns item
        coEvery { playbackRepository.getByVideoId(42L) } returns null

        viewModel.updatePlaybackProgress(10_000L, 60_000L)
        viewModel.onProgressScrubStarted(10_000L)
        viewModel.onProgressScrubChanged(25_000L, factor = 0.25f, isFineMode = true)
        viewModel.loadVideo(42L)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isUserSeeking)
        assertEquals(ProgressScrubUiState(), viewModel.progressScrubState.value)
    }
}
