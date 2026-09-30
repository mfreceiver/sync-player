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
import io.mockk.verify
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
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

    private fun positionInfo(positionMs: Long) = Player.PositionInfo(
        null, 0, null, 0, positionMs, positionMs, -1, -1,
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
        assertEquals(-1L, viewModel.uiState.value.seekTarget)
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

    @Test
    fun `cancel and repeated up cannot commit twice`() = runTest {
        val vm = createViewModel()
        vm.updatePlaybackProgress(12_000L, 60_000L)
        val canceled = vm.onProgressScrubStarted(12_000L)
        vm.onProgressScrubChanged(20_000L, 1f, false, canceled)
        vm.onProgressScrubCancelled(canceled)
        assertEquals(null, vm.onProgressScrubFinished(20_000L, canceled))
        assertEquals(12_000L, vm.currentPosition.value)

        val session = vm.onProgressScrubStarted(15_000L)
        assertEquals(15_000L, vm.onProgressScrubFinished(15_000L, session))
        val record = vm.pendingSeekCommit!!
        assertEquals(null, vm.onProgressScrubFinished(15_000L, session))
        assertEquals(record.commitId, vm.seekPresentation.value.commitId)
        vm.acknowledgeSeek(record, 15_000L)
        assertEquals(SeekPhase.Idle, vm.seekPresentation.value.phase)
        assertEquals(-1L, vm.uiState.value.seekTarget)
    }

    @Test
    fun `new input owns target and old gesture cannot overwrite it`() = runTest {
        val vm = createViewModel()
        vm.updatePlaybackProgress(10_000L, 60_000L)
        val gesture = vm.onGestureSeekStarted(10_000L)
        vm.onGestureSeekChanged(20_000L, 60_000L, gesture)
        val track = vm.onProgressScrubStarted(30_000L)
        vm.onGestureSeekChanged(40_000L, 60_000L, gesture)
        assertEquals(null, vm.onGestureSeekFinished(40_000L, gesture))
        assertEquals(30_000L, vm.seekPresentation.value.targetMs)
        assertEquals(SeekInput.Track, vm.seekPresentation.value.input)
        assertEquals(30_000L, vm.onProgressScrubFinished(30_000L, track))
    }

    @Test
    fun `same duration media switch invalidates session and target`() = runTest {
        val vm = createViewModel()
        vm.updatePlaybackProgress(10_000L, 60_000L)
        val priorGeneration = vm.seekPresentation.value.mediaGeneration
        val session = vm.onProgressScrubStarted(10_000L)
        val item = VideoItemEntity(id = 77L, relativePath = "video77.mp4", fileName = "video77.mp4", fileSize = 1_000L)
        coEvery { videoRepository.getById(77L) } returns item
        coEvery { playbackRepository.getByVideoId(77L) } returns null
        vm.loadVideo(77L)
        advanceUntilIdle()
        assertTrue(priorGeneration != vm.seekPresentation.value.mediaGeneration)
        assertEquals(null, vm.onProgressScrubFinished(20_000L, session))
        assertEquals(SeekPhase.Idle, vm.seekPresentation.value.phase)
    }

    @Test
    fun `precision changes preserve target and reject stale frame`() = runTest {
        val vm = createViewModel()
        vm.updatePlaybackProgress(10_000L, 60_000L)
        val session = vm.onProgressScrubStarted(10_000L)
        vm.onProgressScrubChanged(12_000L, 1f, false, session)
        vm.selectSeekPrecision(SeekPrecision.Quarter)
        assertEquals(12_000L, vm.seekPresentation.value.targetMs)
        vm.onProgressScrubChanged(13_000L, 0.25f, true, session)
        vm.updateSeekFrame(session, 12_000L, SeekFrame.CurrentBucket)
        assertEquals(SeekFrame.Pending, vm.seekPresentation.value.frame)
        assertEquals(SeekPrecision.Quarter, vm.seekPresentation.value.precision)
    }

    @Test
    fun `A adjustment after A seek cannot claim B even if near B target`() = runTest {
        val vm = createViewModel()
        vm.updatePlaybackProgress(1_000L, 60_000L)
        val player = mockk<ExoPlayer>(relaxed = true)
        var actualPosition = 1_000L
        every { player.currentPosition } answers { actualPosition }
        every { player.currentMediaItem } returns null
        val dispatcher: Player.Listener = SeekCommitListener(vm)
        val listener = dispatcher as SeekCommitListener
        val first = vm.onProgressScrubStarted(10_000L)
        vm.onProgressScrubFinished(10_000L, first)
        listener.seek(player, 10_000L)
        val second = vm.onProgressScrubStarted(20_000L)
        vm.onProgressScrubFinished(20_000L, second)
        val secondRecord = vm.pendingSeekCommit
        listener.seek(player, 20_000L)
        assertEquals(2, listener.activeRecordCount) // one flight and one latest intent
        assertTrue(listener.provenanceUncertain)
        val old = positionInfo(1_000L)
        val aSeek = positionInfo(10_000L)
        actualPosition = 10_000L
        dispatcher.onPositionDiscontinuity(old, aSeek, Player.DISCONTINUITY_REASON_SEEK)
        assertEquals(secondRecord, vm.pendingSeekCommit)
        assertEquals(SeekPhase.Settling, vm.seekPresentation.value.phase)
        advanceTimeBy(2_001L)
        runCurrent()
        assertEquals(1, listener.activeRecordCount)
        verify(exactly = 1) { player.seekTo(20_000L) }
        val aAdjustment = positionInfo(19_800L)
        actualPosition = 19_800L
        dispatcher.onPositionDiscontinuity(old, aAdjustment, Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT)
        assertEquals(secondRecord, vm.pendingSeekCommit)
        assertEquals(19_800L, vm.currentPosition.value)
        val actual = positionInfo(20_600L)
        actualPosition = 20_600L
        dispatcher.onPositionDiscontinuity(old, actual, Player.DISCONTINUITY_REASON_SEEK)
        assertEquals(SeekPhase.Settling, vm.seekPresentation.value.phase)
        advanceTimeBy(2_001L)
        runCurrent()
        assertEquals(SeekPhase.Idle, vm.seekPresentation.value.phase)
        assertEquals(20_600L, vm.currentPosition.value)
        verify(exactly = 2) { player.seekTo(any<Long>()) }
        listener.close()
    }

    @Test
    fun `adjustment from acknowledged A while B is issued cannot acknowledge B`() = runTest {
        val vm = createViewModel()
        vm.updatePlaybackProgress(1_000L, 60_000L)
        val player = mockk<ExoPlayer>(relaxed = true)
        every { player.currentMediaItem } returns null
        every { player.currentPosition } returns 1_000L
        val listener = SeekCommitListener(vm)
        val a = vm.onProgressScrubStarted(10_000L)
        vm.onProgressScrubFinished(10_000L, a)
        listener.seek(player, 10_000L)
        val aSeek = positionInfo(10_000L)
        listener.onPositionDiscontinuity(aSeek, aSeek, Player.DISCONTINUITY_REASON_SEEK)
        assertEquals(SeekPhase.Idle, vm.seekPresentation.value.phase)
        val b = vm.onProgressScrubStarted(20_000L)
        vm.onProgressScrubFinished(20_000L, b)
        listener.seek(player, 20_000L)
        advanceTimeBy(101L)
        runCurrent()
        val aAdjustment = positionInfo(20_000L)
        listener.onPositionDiscontinuity(aSeek, aAdjustment, Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT)
        assertEquals(SeekPhase.Settling, vm.seekPresentation.value.phase)
        assertEquals(20_000L, vm.currentPosition.value) // actual head updated, but B not acknowledged
        val bSeek = positionInfo(20_700L)
        listener.onPositionDiscontinuity(aAdjustment, bSeek, Player.DISCONTINUITY_REASON_SEEK)
        assertEquals(SeekPhase.Idle, vm.seekPresentation.value.phase)
        assertEquals(20_700L, vm.currentPosition.value)
        verify(exactly = 2) { player.seekTo(any<Long>()) }
        listener.close()
    }

    @Test
    fun `paused player seek converges without position polling`() = runTest {
        val vm = createViewModel()
        vm.updatePlaybackProgress(10_000L, 60_000L)
        val player = mockk<ExoPlayer>(relaxed = true)
        every { player.currentMediaItem } returns null
        every { player.currentPosition } returns 10_000L
        val session = vm.onProgressScrubStarted(30_000L)
        vm.onProgressScrubFinished(30_000L, session)
        val listener: Player.Listener = SeekCommitListener(vm)
        (listener as SeekCommitListener).seek(player, 30_000L)
        assertEquals(SeekPhase.Settling, vm.seekPresentation.value.phase)
        val position = positionInfo(30_600L)
        listener.onPositionDiscontinuity(position, position, Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT)
        assertEquals(SeekPhase.Idle, vm.seekPresentation.value.phase)
        assertFalse((listener as SeekCommitListener).provenanceUncertain)
        assertEquals(30_600L, vm.currentPosition.value)
        verify(exactly = 1) { player.seekTo(30_000L) }
        (listener as SeekCommitListener).close()
    }

    @Test
    fun `pre-submit no-op A without callback cannot misattribute B`() = runTest {
        val vm = createViewModel()
        vm.updatePlaybackProgress(10_000L, 60_000L)
        val player = mockk<ExoPlayer>(relaxed = true)
        every { player.currentMediaItem } returns null
        every { player.currentPosition } returns 10_000L
        val listener = SeekCommitListener(vm)
        val a = vm.onProgressScrubStarted(10_000L)
        vm.onProgressScrubFinished(10_000L, a)
        listener.seek(player, 10_000L)
        assertEquals(SeekPhase.Idle, vm.seekPresentation.value.phase)
        assertEquals(0, listener.activeRecordCount)
        verify(exactly = 0) { player.seekTo(any<Long>()) }
        val b = vm.onProgressScrubStarted(20_000L)
        vm.onProgressScrubFinished(20_000L, b)
        listener.seek(player, 20_000L)
        val actual = positionInfo(20_000L)
        listener.onPositionDiscontinuity(actual, actual, Player.DISCONTINUITY_REASON_SEEK)
        assertEquals(SeekPhase.Idle, vm.seekPresentation.value.phase)
        assertFalse(listener.provenanceUncertain)
        assertEquals(20_000L, vm.currentPosition.value)
        assertEquals(1, listener.activeRecordCount) // short quiet window, never an unbounded queue
        verify(exactly = 1) { player.seekTo(20_000L) }
        listener.close()
    }

    @Test
    fun `A timeout then late SEEK cannot confirm issued B`() = runTest {
        val vm = createViewModel()
        vm.updatePlaybackProgress(1_000L, 60_000L)
        val player = mockk<ExoPlayer>(relaxed = true)
        var actual = 1_000L
        every { player.currentPosition } answers { actual }
        every { player.currentMediaItem } returns null
        val listener = SeekCommitListener(vm)
        val a = vm.onProgressScrubStarted(10_000L)
        vm.onProgressScrubFinished(10_000L, a)
        listener.seek(player, 10_000L)
        val b = vm.onProgressScrubStarted(20_000L)
        vm.onProgressScrubFinished(20_000L, b)
        listener.seek(player, 20_000L)
        advanceTimeBy(2_001L)
        runCurrent()
        assertTrue(listener.provenanceUncertain)
        assertEquals(1, listener.activeRecordCount)
        verify(exactly = 1) { player.seekTo(20_000L) }
        val late = positionInfo(19_900L)
        actual = 19_900L
        listener.onPositionDiscontinuity(late, late, Player.DISCONTINUITY_REASON_SEEK)
        assertEquals(SeekPhase.Settling, vm.seekPresentation.value.phase)
        assertEquals(19_900L, vm.currentPosition.value)
        advanceTimeBy(2_001L)
        runCurrent()
        assertEquals(0, listener.activeRecordCount)
        assertEquals(SeekPhase.Idle, vm.seekPresentation.value.phase)
        assertEquals(19_900L, vm.currentPosition.value)
        listener.close()
    }

    @Test
    fun `A timeout then late SEEK_ADJUSTMENT cannot confirm issued B`() = runTest {
        val vm = createViewModel()
        vm.updatePlaybackProgress(1_000L, 60_000L)
        val player = mockk<ExoPlayer>(relaxed = true)
        var actual = 1_000L
        every { player.currentPosition } answers { actual }
        every { player.currentMediaItem } returns null
        val listener = SeekCommitListener(vm)
        val a = vm.onProgressScrubStarted(10_000L)
        vm.onProgressScrubFinished(10_000L, a)
        listener.seek(player, 10_000L)
        val b = vm.onProgressScrubStarted(20_000L)
        vm.onProgressScrubFinished(20_000L, b)
        listener.seek(player, 20_000L)
        advanceTimeBy(2_001L)
        runCurrent()
        assertTrue(listener.provenanceUncertain)
        verify(exactly = 1) { player.seekTo(20_000L) }
        val late = positionInfo(21_000L)
        actual = 21_000L
        listener.onPositionDiscontinuity(late, late, Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT)
        assertEquals(SeekPhase.Settling, vm.seekPresentation.value.phase)
        assertEquals(21_000L, vm.currentPosition.value)
        advanceTimeBy(2_001L)
        runCurrent()
        assertEquals(SeekPhase.Idle, vm.seekPresentation.value.phase)
        assertEquals(21_000L, vm.currentPosition.value)
        listener.close()
    }

    @Test
    fun `adjustment-only confirmation retains predecessor guard for a later adjustment`() = runTest {
        val vm = createViewModel()
        vm.updatePlaybackProgress(1_000L, 60_000L)
        val player = mockk<ExoPlayer>(relaxed = true)
        var actual = 1_000L
        every { player.currentPosition } answers { actual }
        every { player.currentMediaItem } returns null
        val listener = SeekCommitListener(vm)
        val a = vm.onProgressScrubStarted(10_000L)
        vm.onProgressScrubFinished(10_000L, a)
        listener.seek(player, 10_000L)
        val aAdjustment = positionInfo(10_500L)
        listener.onPositionDiscontinuity(aAdjustment, aAdjustment, Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT)
        assertEquals(SeekPhase.Idle, vm.seekPresentation.value.phase)
        advanceTimeBy(101L)
        runCurrent()
        assertFalse(listener.provenanceUncertain) // A was confirmed, not timed out
        val b = vm.onProgressScrubStarted(20_000L)
        vm.onProgressScrubFinished(20_000L, b)
        listener.seek(player, 20_000L)
        val lateA = positionInfo(20_100L)
        actual = 20_100L
        listener.onPositionDiscontinuity(aAdjustment, lateA, Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT)
        assertEquals(SeekPhase.Settling, vm.seekPresentation.value.phase)
        assertEquals(20_100L, vm.currentPosition.value)
        assertFalse(listener.provenanceUncertain) // ambiguity is guarded, not invented as a timeout
        advanceTimeBy(2_001L)
        runCurrent()
        assertEquals(SeekPhase.Idle, vm.seekPresentation.value.phase)
        assertEquals(20_100L, vm.currentPosition.value)
        assertTrue(listener.provenanceUncertain) // B ended without an attributable ACK
        listener.close()
    }

    @Test
    fun `repeated timeouts and failed seek clear both slots before next seek`() = runTest {
        val vm = createViewModel()
        vm.updatePlaybackProgress(1_000L, 60_000L)
        val player = mockk<ExoPlayer>(relaxed = true)
        every { player.currentMediaItem } returns null
        var actualPosition = 1_000L
        every { player.currentPosition } answers { actualPosition }
        val listener = SeekCommitListener(vm)
        repeat(3) { index ->
            val session = vm.onProgressScrubStarted(10_000L + index)
            vm.onProgressScrubFinished(10_000L + index, session)
            listener.seek(player, 10_000L + index)
            if (index == 0) advanceTimeBy(900L)
            val newer = vm.onProgressScrubStarted(20_000L + index)
            vm.onProgressScrubFinished(20_000L + index, newer)
            listener.seek(player, 20_000L + index)
            if (index == 0) advanceTimeBy(900L)
            val latest = vm.onProgressScrubStarted(30_000L + index)
            vm.onProgressScrubFinished(30_000L + index, latest)
            listener.seek(player, 30_000L + index)
            assertEquals(2, listener.activeRecordCount)
            advanceTimeBy(if (index == 0) 201L else 2_001L)
            runCurrent()
            // A's ORIGINAL issuance deadline advances latest C, not B; no
            // overwrite can postpone A or grow the state beyond two slots.
            verify(exactly = 1) { player.seekTo(30_000L + index) }
            assertEquals(1, listener.activeRecordCount)
            assertTrue(listener.provenanceUncertain)
            advanceTimeBy(2_001L)
            runCurrent()
            assertEquals(0, listener.activeRecordCount)
            assertEquals(SeekPhase.Idle, vm.seekPresentation.value.phase)
        }
        every { player.seekTo(40_000L) } throws IllegalStateException("unseekable")
        val failed = vm.onProgressScrubStarted(40_000L)
        vm.onProgressScrubFinished(40_000L, failed)
        assertTrue(runCatching { listener.seek(player, 40_000L) }.isFailure)
        assertEquals(0, listener.activeRecordCount)
        assertEquals(SeekPhase.Idle, vm.seekPresentation.value.phase)
        val finalSession = vm.onProgressScrubStarted(50_000L)
        vm.onProgressScrubFinished(50_000L, finalSession)
        listener.seek(player, 50_000L)
        val actual = positionInfo(50_000L)
        listener.onPositionDiscontinuity(actual, actual, Player.DISCONTINUITY_REASON_SEEK)
        assertEquals(SeekPhase.Settling, vm.seekPresentation.value.phase)
        assertEquals(50_000L, vm.currentPosition.value)
        actualPosition = 50_000L
        advanceTimeBy(2_001L)
        runCurrent()
        assertEquals(SeekPhase.Idle, vm.seekPresentation.value.phase)
        assertEquals(50_000L, vm.currentPosition.value)
        verify(exactly = 1) { player.seekTo(50_000L) }
        listener.close()
    }

    @Test
    fun `two loaded media with same duration retire old session`() = runTest {
        val vm = createViewModel()
        listOf(41L, 42L).forEach { id ->
            coEvery { videoRepository.getById(id) } returns VideoItemEntity(
                id = id, relativePath = "video$id.mp4", fileName = "video$id.mp4", fileSize = 1_000L)
            coEvery { playbackRepository.getByVideoId(id) } returns PlaybackRecordEntity(
                videoItemId = id, duration = 60_000L)
        }
        vm.loadVideo(41L)
        advanceUntilIdle()
        vm.updatePlaybackProgress(5_000L, 60_000L, 41L)
        val generationA = vm.seekPresentation.value.mediaGeneration
        val sessionA = vm.onProgressScrubStarted(10_000L)
        vm.loadVideo(42L)
        advanceUntilIdle()
        assertEquals(60_000L, vm.uiState.value.duration)
        assertEquals(42L, vm.uiState.value.videoItem?.id)
        assertTrue(vm.seekPresentation.value.mediaGeneration != generationA)
        assertEquals(null, vm.onProgressScrubFinished(12_000L, sessionA))
    }
}
