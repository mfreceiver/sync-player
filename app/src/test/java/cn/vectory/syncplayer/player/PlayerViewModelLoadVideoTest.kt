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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * BUG-015 / BUG-019 tests for [PlayerViewModel.loadVideo].
 *
 * Harness copies [PlayerViewModelSaveProgressTest]: all dependencies mocked,
 * StandardTestDispatcher as Main (loadVideo launches queue up), an eager
 * UnconfinedTestDispatcher injected as IO, `runTest` + `advanceUntilIdle`.
 *
 * Gate releases always go in `finally` so a failing assertion never strands a
 * suspended load. Asserts on enum/state values only — never on user-facing
 * message text.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelLoadVideoTest {

    private val videoRepository = mockk<VideoRepository>(relaxed = true)
    private val playbackRepository = mockk<PlaybackRepository>(relaxed = true)
    private val userPrefs = mockk<UserPreferencesRepository>(relaxed = true)
    private val videoQueue = mockk<VideoQueue>()
    private val undoDeleteUseCase = mockk<UndoDeleteUseCase>(relaxed = true)
    private val testDispatcher = StandardTestDispatcher()

    private val ioTestDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = runTest {
        Dispatchers.setMain(testDispatcher)
        every { videoQueue.consumeIds() } returns emptyList()
        every { videoQueue.getIds() } returns emptyList()
        coEvery { videoRepository.getAllIdsSorted() } returns emptyList()
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
        ioDispatcher = ioTestDispatcher,
    )

    // ------------------------------------------------------------------
    // BUG-015: load generation guard — a stale request resuming after ANY
    // suspension point must never overwrite a newer request's state.
    // ------------------------------------------------------------------

    @Test
    fun `stale load result does not overwrite newer video state`() = runTest {
        val vm = createVm()
        val gate = CompletableDeferred<Unit>()
        coEvery { videoRepository.getById(1L) } coAnswers { gate.await(); videoEntity(1L) }
        coEvery { videoRepository.getById(2L) } returns videoEntity(2L)
        coEvery { playbackRepository.getByVideoId(2L) } returns null

        try {
            vm.loadVideo(1L)
            vm.loadVideo(2L)
            advanceUntilIdle()   // #1 suspends inside the gate; #2 commits video 2
        } finally {
            gate.complete(Unit)
        }
        advanceUntilIdle()       // stale #1 resumes
        assertEquals(2L, vm.uiState.value.videoItem?.id)
    }

    @Test
    fun `load generation increments so only the latest request commits`() = runTest {
        val vm = createVm()
        val gate = CompletableDeferred<Unit>()
        coEvery { videoRepository.getById(1L) } coAnswers { gate.await(); videoEntity(1L) }
        coEvery { videoRepository.getById(2L) } returns videoEntity(2L)
        coEvery { playbackRepository.getByVideoId(2L) } returns null
        coEvery { videoRepository.getById(3L) } returns videoEntity(3L)
        coEvery { playbackRepository.getByVideoId(3L) } returns PlaybackRecordEntity(videoItemId = 3L, position = 7_777L)

        try {
            vm.loadVideo(1L)
            vm.loadVideo(2L)
            vm.loadVideo(3L)
            advanceUntilIdle()   // latest (#3) commits
        } finally {
            gate.complete(Unit)
        }
        advanceUntilIdle()       // stale #1 and #2 resume
        assertEquals(3L, vm.uiState.value.videoItem?.id)
        // The committed state is a CONSISTENT set: position follows video 3's
        // record, not a half-old/half-new mixture.
        assertEquals(7_777L, vm.currentPosition.value)
    }

    @Test
    fun `loadVideo is a no-op repo failure safe - exception publishes no state change`() = runTest {
        val vm = createVm()
        coEvery { videoRepository.getById(1L) } throws RuntimeException("db")

        vm.loadVideo(1L)
        advanceUntilIdle()

        assertNull(vm.uiState.value.videoItem)
    }

    @Test
    fun `stale result returning between the two suspension points is also gated`() = runTest {
        val vm = createVm()
        val gate2 = CompletableDeferred<Unit>()
        coEvery { videoRepository.getById(1L) } returns videoEntity(1L)
        coEvery { playbackRepository.getByVideoId(1L) } coAnswers { gate2.await(); null }
        coEvery { videoRepository.getById(2L) } returns videoEntity(2L)
        coEvery { playbackRepository.getByVideoId(2L) } returns null

        try {
            vm.loadVideo(1L)
            vm.loadVideo(2L)
            advanceUntilIdle()   // #1 suspends at the SECOND suspension point; #2 commits video 2
        } finally {
            gate2.complete(Unit)
        }
        advanceUntilIdle()       // stale #1 resumes after getByVideoId
        assertEquals(2L, vm.uiState.value.videoItem?.id)
    }

    // ------------------------------------------------------------------
    // BUG-019: skip deleted queue ids; publish the structured exhaustion
    // terminal (queueExhausted) when nothing exists ahead — UI exits exactly
    // like normal EOF. No ErrorEvent (this is not an error).
    // ------------------------------------------------------------------

    @Test
    fun `loadVideo skips a deleted id and advances to the next existing one`() = runTest {
        every { videoQueue.consumeIds() } returns listOf(1L, 2L, 3L)
        coEvery { videoRepository.getById(1L) } returns videoEntity(1L)
        coEvery { videoRepository.getById(2L) } returns null
        coEvery { videoRepository.getById(3L) } returns videoEntity(3L)
        coEvery { playbackRepository.getByVideoId(3L) } returns null
        val vm = createVm()

        vm.loadVideo(1L)
        advanceUntilIdle()
        vm.loadVideo(2L)          // deleted by background sync
        advanceUntilIdle()

        assertEquals(3L, vm.uiState.value.videoItem?.id)
        coVerify(exactly = 1) { videoRepository.getById(3L) }
    }

    @Test
    fun `loadVideo skips a run of consecutive deleted ids`() = runTest {
        every { videoQueue.consumeIds() } returns listOf(1L, 2L, 3L, 4L)
        coEvery { videoRepository.getById(2L) } returns null
        coEvery { videoRepository.getById(3L) } returns null
        coEvery { videoRepository.getById(4L) } returns videoEntity(4L)
        coEvery { playbackRepository.getByVideoId(4L) } returns null
        val vm = createVm()

        vm.loadVideo(2L)
        advanceUntilIdle()

        assertEquals(4L, vm.uiState.value.videoItem?.id)
    }

    @Test
    fun `loadVideo publishes the structured exhaustion terminal when queue is exhausted after deleted id`() = runTest {
        every { videoQueue.consumeIds() } returns listOf(1L, 2L)
        coEvery { videoRepository.getById(1L) } returns videoEntity(1L)
        coEvery { playbackRepository.getByVideoId(1L) } returns null
        coEvery { videoRepository.getById(2L) } returns null
        val vm = createVm()

        vm.loadVideo(1L)
        advanceUntilIdle()
        vm.loadVideo(2L)          // last queue id, deleted
        advanceUntilIdle()

        assertEquals(1L, vm.uiState.value.videoItem?.id)   // keeps the current video, no half-loaded state
        assertTrue(vm.uiState.value.queueExhausted)        // structured terminal — the UI's exit contract
        assertNull(vm.uiState.value.error)                 // normal EOF semantics, NOT an error
    }

    @Test
    fun `loadVideo for an id not in the queue publishes the exhaustion terminal`() = runTest {
        // Empty queue via DB fallback (consumeIds empty -> SavedState empty -> getAllIdsSorted empty)
        coEvery { videoRepository.getById(42L) } returns videoEntity(42L)
        coEvery { playbackRepository.getByVideoId(42L) } returns null
        coEvery { videoRepository.getById(99L) } returns null
        val vm = createVm()

        vm.loadVideo(42L)
        advanceUntilIdle()
        vm.loadVideo(99L)         // not in any queue, not in DB
        advanceUntilIdle()

        assertEquals(42L, vm.uiState.value.videoItem?.id)
        assertTrue(vm.uiState.value.queueExhausted)        // no queue to search = exhausted
    }

    @Test
    fun `successful load clears a previously published exhaustion flag`() = runTest {
        every { videoQueue.consumeIds() } returns listOf(1L, 2L)
        coEvery { videoRepository.getById(1L) } returns videoEntity(1L)
        coEvery { playbackRepository.getByVideoId(1L) } returns null
        coEvery { videoRepository.getById(2L) } returns null
        val vm = createVm()

        vm.loadVideo(2L)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.queueExhausted)        // precondition: terminal published

        vm.loadVideo(1L)
        advanceUntilIdle()

        assertFalse(vm.uiState.value.queueExhausted)       // terminal is resettable, not sticky
        assertEquals(1L, vm.uiState.value.videoItem?.id)
    }

    @Test
    fun `stale request resuming after a newer successful load must not publish exhaustion`() = runTest {
        every { videoQueue.consumeIds() } returns listOf(1L, 2L)
        val gate = CompletableDeferred<Unit>()
        val entered = java.util.concurrent.atomic.AtomicBoolean(false)
        // The OLD request's DB read reports entry via a side channel, then parks in
        // the gate — after release it would reach "id gone, queue tail" = exhaustion.
        coEvery { videoRepository.getById(2L) } coAnswers { entered.set(true); gate.await(); null }
        coEvery { videoRepository.getById(1L) } returns videoEntity(1L)
        coEvery { playbackRepository.getByVideoId(1L) } returns null
        val vm = createVm()

        try {
            vm.loadVideo(2L)                       // old generation: launch queued, not yet running
            runCurrent()                           // run queued tasks up to the first suspension point
            assertTrue(entered.get())              // old request IS parked in the gate (interleave leg 1)
            vm.loadVideo(1L)                       // new generation
            advanceUntilIdle()                     // new request completes; old still parked (virtual clock skips no CompletableDeferred)
            assertEquals(1L, vm.uiState.value.videoItem?.id)   // before releasing: new request committed (leg 2)
            assertFalse(vm.uiState.value.queueExhausted)       // before releasing: no terminal yet
        } finally {
            gate.complete(Unit)                    // release the old request regardless of outcome
            advanceUntilIdle()                     // it resumes, reaches the exhaustion branch, gets gated by generation
        }
        assertEquals(1L, vm.uiState.value.videoItem?.id)      // final: not overwritten by the stale request
        assertFalse(vm.uiState.value.queueExhausted)          // final: stale request must NOT publish exhaustion
    }

    @Test
    fun `AND-039 consumeRestorePosition prioritizes live currentPosition over stale playbackRecord`() = runTest {
        coEvery { videoRepository.getById(1L) } returns videoEntity(1L)
        coEvery { playbackRepository.getByVideoId(1L) } returns PlaybackRecordEntity(videoItemId = 1L, position = 10_000L, duration = 60_000L)
        val vm = createVm()

        vm.loadVideo(1L)
        advanceUntilIdle()

        // Initially restores DB position
        assertEquals(10_000L, vm.consumeRestorePosition(1L))

        // Simulated playback progress advances live currentPosition to 25s
        vm.updatePlaybackProgress(25_000L, 60_000L, videoId = 1L)
        assertEquals(25_000L, vm.currentPosition.value)

        // Activity recreation scenario: loadVideo(1L) re-invoked on same VM instance
        // must restore live 25s, not stale 10s from DB playbackRecord
        vm.loadVideo(1L)
        advanceUntilIdle()
        assertEquals(25_000L, vm.consumeRestorePosition(1L))
    }

    @Test
    fun `R1 - Activity recreation swap effect runs before load commit and restores live position`() = runTest {
        coEvery { videoRepository.getById(1L) } returns videoEntity(1L)
        coEvery { playbackRepository.getByVideoId(1L) } returns PlaybackRecordEntity(videoItemId = 1L, position = 10_000L, duration = 60_000L)
        val vm = createVm()

        // 1. Initial load and playback
        vm.loadVideo(1L)
        advanceUntilIdle()
        assertEquals(10_000L, vm.consumeRestorePosition(1L))

        // Progress advances to 45s during playback
        vm.updatePlaybackProgress(45_000L, 60_000L, videoId = 1L)
        assertEquals(45_000L, vm.currentPosition.value)

        // 2. Activity recreation: loadVideo is triggered, DB query is in-flight and suspended
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { playbackRepository.getByVideoId(1L) } coAnswers {
            gate.await()
            PlaybackRecordEntity(videoItemId = 1L, position = 10_000L, duration = 60_000L)
        }
        vm.loadVideo(1L)
        runCurrent() // Runs up to gate.await(), loadVideo has NOT committed yet

        // 3. In the same composition frame, swap effect runs consumeRestorePosition(1L)
        // Must return live 45_000L, NOT stale DB record 10_000L
        val restoredPos = vm.consumeRestorePosition(1L)
        assertEquals(45_000L, restoredPos)

        // 4. Release gate, loadVideo commits
        gate.complete(Unit)
        advanceUntilIdle()

        // Commit target position remains 45_000L
        assertEquals(45_000L, vm.currentPosition.value)
    }

    @Test
    fun `R4 - same-video commit does not set pendingRestore preventing progress rollback on subsequent recreation`() = runTest {
        coEvery { videoRepository.getById(1L) } returns videoEntity(1L)
        coEvery { playbackRepository.getByVideoId(1L) } returns PlaybackRecordEntity(videoItemId = 1L, position = 10_000L, duration = 60_000L)
        val vm = createVm()

        // 1. Cold start load: consumes initial DB record (10s)
        vm.loadVideo(1L)
        advanceUntilIdle()
        assertEquals(10_000L, vm.consumeRestorePosition(1L))

        // Playback advances to 25s
        vm.updatePlaybackProgress(25_000L, 60_000L, videoId = 1L)
        assertEquals(25_000L, vm.currentPosition.value)

        // 2. Recreation #1: loadVideo(1L) is issued and commits.
        // With R4 guard (!isSameVideo), same-video commit does NOT set _pendingRestore or overwrite currentPosition.
        vm.loadVideo(1L)
        advanceUntilIdle()

        // 3. Playback progresses forward to 80s
        vm.updatePlaybackProgress(80_000L, 60_000L, videoId = 1L)
        assertEquals(80_000L, vm.currentPosition.value)

        // 4. Double recreation sequence: consecutive consumeRestorePosition calls.
        // First consume returns new live position (80s).
        val firstConsume = vm.consumeRestorePosition(1L)
        assertEquals(80_000L, firstConsume)

        // Second consume (recreation #2) must ALSO return new live position (80s),
        // proving no stale commit target (25s) was lingering in _pendingRestore.
        val secondConsume = vm.consumeRestorePosition(1L)
        assertEquals(80_000L, secondConsume)
    }

    @Test
    fun `R1 - consumeRestorePosition strictly scopes to requested videoId and returns 0 on mismatch`() = runTest {
        coEvery { videoRepository.getById(1L) } returns videoEntity(1L)
        coEvery { playbackRepository.getByVideoId(1L) } returns PlaybackRecordEntity(videoItemId = 1L, position = 10_000L, duration = 60_000L)
        val vm = createVm()

        vm.loadVideo(1L)
        advanceUntilIdle()
        vm.updatePlaybackProgress(25_000L, 60_000L, videoId = 1L)

        // Requesting position for a different videoId (e.g. 2L) must NOT return video 1's position or record
        assertEquals(0L, vm.consumeRestorePosition(2L))
    }

    @Test
    fun `AND-039 switching video resets live position to prevent position leaking`() = runTest {
        coEvery { videoRepository.getById(1L) } returns videoEntity(1L)
        coEvery { videoRepository.getById(2L) } returns videoEntity(2L)
        coEvery { playbackRepository.getByVideoId(1L) } returns PlaybackRecordEntity(videoItemId = 1L, position = 10_000L, duration = 60_000L)
        coEvery { playbackRepository.getByVideoId(2L) } returns PlaybackRecordEntity(videoItemId = 2L, position = 5_000L, duration = 60_000L)
        val vm = createVm()

        vm.loadVideo(1L)
        advanceUntilIdle()
        vm.updatePlaybackProgress(45_000L, 60_000L, videoId = 1L)
        assertEquals(45_000L, vm.currentPosition.value)

        // Switching to video 2 resets position and restores video 2's position
        vm.loadVideo(2L)
        advanceUntilIdle()
        assertEquals(5_000L, vm.currentPosition.value)
        assertEquals(5_000L, vm.consumeRestorePosition(2L))
    }

    @Test
    fun `N1 - switching video during active playback gates polling updates and restores clean position`() = runTest {
        coEvery { videoRepository.getById(1L) } returns videoEntity(1L)
        coEvery { playbackRepository.getByVideoId(1L) } returns PlaybackRecordEntity(videoItemId = 1L, position = 10_000L, duration = 60_000L)
        val vm = createVm()

        // 1. Video 1 loads and plays up to 45s
        vm.loadVideo(1L)
        advanceUntilIdle()
        vm.consumeRestorePosition(1L)
        vm.setPlaying(true)
        vm.updatePlaybackProgress(45_000L, 60_000L)
        assertEquals(45_000L, vm.currentPosition.value)
        assertTrue(vm.uiState.value.isPlaying)

        // 2. Mock video 2 with a suspension gate
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { videoRepository.getById(2L) } coAnswers {
            gate.await()
            videoEntity(2L)
        }
        coEvery { playbackRepository.getByVideoId(2L) } returns PlaybackRecordEntity(videoItemId = 2L, position = 5_000L, duration = 60_000L)

        // 3. User switches to video 2: loadVideo(2L) initiated
        vm.loadVideo(2L)
        runCurrent() // Runs up to gate.await()

        // Gate guarantees: isPlaying is cleared (cancelling UI polling loop) and currentPosition is reset
        assertFalse(vm.uiState.value.isPlaying)
        assertEquals(0L, vm.currentPosition.value)

        // 4. Concurrent 200ms tick from old video arrives while load is in-flight:
        // Must be dropped by inFlightSwitchGeneration gate!
        vm.updatePlaybackProgress(45_200L, 60_000L)
        assertEquals(0L, vm.currentPosition.value)

        // 5. Release gate to complete loading video 2
        gate.complete(Unit)
        advanceUntilIdle()

        // 6. Assert video 2 restore position is strictly video 2's DB record (5s), NEVER 45s or 45.2s!
        assertEquals(2L, vm.uiState.value.videoItem?.id)
        assertEquals(5_000L, vm.consumeRestorePosition(2L))
        assertEquals(5_000L, vm.currentPosition.value)
    }

    @Test
    fun `N1 - switching to unplayed video restores zero even if old video was near end and polling ticked`() = runTest {
        coEvery { videoRepository.getById(1L) } returns videoEntity(1L)
        coEvery { playbackRepository.getByVideoId(1L) } returns null
        val vm = createVm()

        vm.loadVideo(1L)
        advanceUntilIdle()
        vm.consumeRestorePosition(1L)
        vm.setPlaying(true)
        vm.updatePlaybackProgress(58_000L, 60_000L)
        assertEquals(58_000L, vm.currentPosition.value)

        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { videoRepository.getById(2L) } coAnswers {
            gate.await()
            videoEntity(2L)
        }
        // Video 2 has never been played (no record in DB)
        coEvery { playbackRepository.getByVideoId(2L) } returns null

        vm.loadVideo(2L)
        runCurrent()

        // Stale tick from old video trying to sneak in
        vm.updatePlaybackProgress(58_200L, 60_000L)
        assertEquals(0L, vm.currentPosition.value)

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(2L, vm.uiState.value.videoItem?.id)
        // Video 2 must start from 0L, strictly isolated from video 1
        assertEquals(0L, vm.consumeRestorePosition(2L))
        assertEquals(0L, vm.currentPosition.value)
    }

    @Test
    fun `R3 - inFlightSwitchGeneration gate does not leak when cross-video load is superseded by same-video load`() = runTest {
        coEvery { videoRepository.getById(1L) } returns videoEntity(1L)
        coEvery { playbackRepository.getByVideoId(1L) } returns PlaybackRecordEntity(videoItemId = 1L, position = 10_000L, duration = 60_000L)
        val vm = createVm()

        // 1. Initial load of video 1
        vm.loadVideo(1L)
        advanceUntilIdle()
        vm.consumeRestorePosition(1L)
        assertEquals(1L, vm.uiState.value.videoItem?.id)

        // 2. Start cross-video loadVideo(2L) that suspends on DB
        val gateVideo2 = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { videoRepository.getById(2L) } coAnswers {
            gateVideo2.await()
            videoEntity(2L)
        }
        vm.loadVideo(2L)
        runCurrent() // Suspended in loadVideo(2L), inFlightSwitchGeneration set

        // 3. Same-video loadVideo(1L) is issued (e.g. reload/recreation) and completes immediately
        coEvery { videoRepository.getById(1L) } returns videoEntity(1L)
        vm.loadVideo(1L)
        advanceUntilIdle() // loadVideo(1L) commits and clears gate

        // 4. Release video 2's gate so stale loadVideo(2L) resumes and hits return@launch
        gateVideo2.complete(Unit)
        advanceUntilIdle()

        // 5. Assert: gate is cleared, video 1 remains active, and subsequent updatePlaybackProgress ticks are accepted
        vm.updatePlaybackProgress(33_000L, 60_000L, videoId = 1L)
        assertEquals(33_000L, vm.currentPosition.value)
    }
}
