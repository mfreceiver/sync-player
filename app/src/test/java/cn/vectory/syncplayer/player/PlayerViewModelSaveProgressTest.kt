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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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
        every { videoQueue.consumeIds(any()) } returns emptyList()
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

    private fun createVm(
        ioDispatcher: CoroutineDispatcher = ioTestDispatcher,
    ): PlayerViewModel = PlayerViewModel(
        savedStateHandle = SavedStateHandle(),
        videoRepository = videoRepository,
        playbackRepository = playbackRepository,
        userPrefs = userPrefs,
        videoQueue = videoQueue,
        undoDeleteUseCase = undoDeleteUseCase,
        ioDispatcher = ioDispatcher,
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

    // ------------------------------------------------------------------
    // BUG-002: single-FIFO-consumer serialization. Order assertions via
    // coVerifyOrder only prove entry order into the mock; the authoritative
    // evidence is a stateful fake recording the FINAL persisted value at each
    // save's completion point (secondArg = position). Gate releases always go
    // in `finally` so a failing assertion never strands a suspended save.
    // ------------------------------------------------------------------

    @Test
    fun `saveProgress serializes concurrent saves - final persisted value equals last call`() = runTest {
        val vm = createVm()
        loadVideo42(vm)
        val persisted = AtomicLong(-1L)
        val gate = CompletableDeferred<Unit>()
        coEvery { playbackRepository.saveProgress(42L, 30_000L, 100_000L) } coAnswers {
            gate.await()
            persisted.set(30_000L)
        }
        coEvery { playbackRepository.saveProgress(42L, 100_000L, 100_000L) } coAnswers { persisted.set(100_000L) }

        vm.saveProgress(30_000L, 100_000L)
        vm.saveProgress(100_000L, 100_000L)
        try {
            // Serialized: the 100k save is still queued behind the gated 30k save.
            // (Current impl: both launched in parallel — 100k has already landed.)
            assertNotEquals(100_000L, persisted.get())
        } finally {
            gate.complete(Unit)
            advanceUntilIdle()
        }
        // FIFO completion: 30k first, then 100k — final value == last call.
        assertEquals(100_000L, persisted.get())
    }

    @Test
    fun `saveProgress keeps FIFO completion order across interleaved calls`() = runTest {
        val vm = createVm()
        loadVideo42(vm)
        val persisted = AtomicLong(-1L)
        val events = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        coEvery { playbackRepository.saveProgress(42L, 30_000L, 100_000L) } coAnswers {
            gate.await()
            persisted.set(30_000L)
            events.add("30k")
        }
        coEvery { playbackRepository.saveProgress(42L, 100_000L, 100_000L) } coAnswers {
            persisted.set(100_000L)
            events.add("100k")
        }

        vm.saveProgress(30_000L, 100_000L)
        vm.saveProgress(100_000L, 100_000L)
        try {
            assertNotEquals(100_000L, persisted.get())
        } finally {
            gate.complete(Unit)
            advanceUntilIdle()
        }
        assertEquals(listOf("30k", "100k"), events.toList())
        assertEquals(100_000L, persisted.get())
    }

    @Test
    fun `final save of previous video is not dropped when switching videos`() = runTest {
        val vm = createVm()
        val persistedById = ConcurrentHashMap<Long, Long>()
        val gate = CompletableDeferred<Unit>()
        val firstCall = AtomicBoolean(true)
        coEvery { videoRepository.getById(43L) } returns videoEntity(43L)
        coEvery { playbackRepository.getByVideoId(43L) } returns null
        coEvery { playbackRepository.saveProgress(eq(42L), any(), any()) } coAnswers {
            if (firstCall.compareAndSet(true, false)) gate.await()   // gate only the FIRST 42-save (42@30k)
            persistedById[42L] = secondArg()                          // position — NOT thirdArg (duration)
        }
        coEvery { playbackRepository.saveProgress(eq(43L), any(), any()) } coAnswers { persistedById[43L] = secondArg() }

        loadVideo42(vm)
        vm.saveProgress(30_000L, 100_000L)      // consumer takes 42@30k, blocks in gate
        vm.saveProgress(100_000L, 100_000L)     // 42's final save is queued behind it
        try {
            vm.loadVideo(43L)
            advanceUntilIdle()
            assertEquals(43L, vm.uiState.value.videoItem?.id)   // new video loaded, independent of the blocked save chain
            vm.saveProgress(1_000L, 100_000L)                   // new video's save queued too
        } finally {
            gate.complete(Unit)
        }
        advanceUntilIdle()
        // Both videos' saves executed, each at its OWN last value. A conflated
        // channel implementation would keep 42 at its gated first write (30k)
        // and drop the queued 100k final save.
        assertEquals(mapOf(42L to 100_000L, 43L to 1_000L), persistedById)
    }

    @Test
    fun `a save throwing CancellationException does not kill the consumer`() = runTest {
        val vm = createVm()
        loadVideo42(vm)
        val persisted = AtomicLong(-1L)
        coEvery { playbackRepository.saveProgress(42L, 1_000L, 100_000L) } coAnswers {
            throw CancellationException("spurious CE from repo internals")
        }
        coEvery { playbackRepository.saveProgress(42L, 2_000L, 100_000L) } coAnswers { persisted.set(2_000L) }

        vm.saveProgress(1_000L, 100_000L)
        vm.saveProgress(2_000L, 100_000L)
        advanceUntilIdle()

        // The consumer keeps serving the queue after one save's CE.
        assertEquals(2_000L, persisted.get())
    }

    @Test
    fun `consumer continues after a regular save exception`() = runTest {
        val vm = createVm()
        loadVideo42(vm)
        val persisted = AtomicLong(-1L)
        coEvery { playbackRepository.saveProgress(42L, 1_000L, 100_000L) } coAnswers { throw RuntimeException("db") }
        coEvery { playbackRepository.saveProgress(42L, 2_000L, 100_000L) } coAnswers { persisted.set(2_000L) }

        vm.saveProgress(1_000L, 100_000L)
        vm.saveProgress(2_000L, 100_000L)
        advanceUntilIdle()

        assertEquals(2_000L, persisted.get())
    }

    @Test
    fun `legal seek-backward save is not monotonicity-clamped`() = runTest {
        val vm = createVm()
        loadVideo42(vm)
        val persisted = AtomicLong(-1L)
        coEvery { playbackRepository.saveProgress(42L, any(), any()) } coAnswers { persisted.set(secondArg()) }

        vm.saveProgress(100_000L, 100_000L)
        vm.saveProgress(5_000L, 100_000L)
        advanceUntilIdle()

        // A deliberate seek-backward save takes effect per call order; no layer
        // may "monotonic-clamp" it away.
        assertEquals(5_000L, persisted.get())
    }

    @Test
    fun `saveProgress during in-flight video switch drops un-attributed save to avoid polluting target video`() = runTest {
        val vm = createVm()
        loadVideo42(vm)

        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { videoRepository.getById(43L) } coAnswers {
            gate.await()
            videoEntity(43L)
        }
        coEvery { playbackRepository.getByVideoId(43L) } returns null

        // Initiate switch to video 43
        vm.loadVideo(43L)
        runCurrent()

        // Un-attributed checkpoint save attempts to save while switch is in-flight:
        // Must be dropped so stale player position is never saved to the new video!
        vm.saveProgress(50_000L, 100_000L)
        advanceUntilIdle()

        coVerify(exactly = 0) { playbackRepository.saveProgress(43L, any(), any()) }

        gate.complete(Unit)
        advanceUntilIdle()

        // Explicit save targeting video 42 still succeeds
        vm.saveProgress(50_000L, 100_000L, videoId = 42L)
        advanceUntilIdle()

        coVerify(exactly = 1) { playbackRepository.saveProgress(42L, 50_000L, 100_000L) }
    }

    // ------------------------------------------------------------------
    // F-003: bounded flush on ViewModel teardown. onCleared() closes the
    // progressSaveChannel; the single consumer drains all buffered saves to
    // completion before cancelling progressSaveScope, ensuring the final save
    // always lands in Room without leaking coroutines or ViewModel instances.
    // ------------------------------------------------------------------

    @Test
    fun `F-003 - repeatedly creating and clearing ViewModel flushes final save, terminates consumer, and scopes become inactive`() = runTest {
        for (i in 1..5) {
            val videoId = i * 10L
            val vm = createVm()
            assertTrue("Scope must be active on creation (iteration $i)", vm.isProgressSaveScopeActive)
            assertFalse("Consumer must not be completed on creation (iteration $i)", vm.isProgressSaveConsumerCompleted)

            coEvery { videoRepository.getById(videoId) } returns videoEntity(videoId)
            coEvery { playbackRepository.getByVideoId(videoId) } returns null
            vm.loadVideo(videoId)
            advanceUntilIdle()

            // Intermediate save + final save
            vm.saveProgress(1_000L, 60_000L)
            vm.saveProgress(5_000L, 60_000L)

            // Teardown: simulate ViewModel clearance on exiting PlayerScreen
            vm.clearForTest()
            advanceUntilIdle()

            // ① Final save executed and persisted
            coVerify(exactly = 1) { playbackRepository.saveProgress(videoId, 5_000L, 60_000L) }

            // ② Consumer coroutine exited and scope is inactive
            assertFalse("Scope must be inactive after flush and teardown (iteration $i)", vm.isProgressSaveScopeActive)
            assertTrue("Consumer must be completed after teardown (iteration $i)", vm.isProgressSaveConsumerCompleted)

            // ③ Calls after channel closure do not throw and are dropped
            vm.saveProgress(99_999L, 60_000L)
            advanceUntilIdle()
            coVerify(exactly = 0) { playbackRepository.saveProgress(videoId, 99_999L, 60_000L) }
        }
    }

    @Test
    fun `F-003 - onCleared drains in-flight and queued saves before cancelling scope`() = runTest {
        val vm = createVm()
        loadVideo42(vm)

        val gate = CompletableDeferred<Unit>()
        val persisted = mutableListOf<Long>()
        coEvery { playbackRepository.saveProgress(42L, 10_000L, 60_000L) } coAnswers {
            gate.await()
            persisted.add(10_000L)
        }
        coEvery { playbackRepository.saveProgress(42L, 20_000L, 60_000L) } coAnswers {
            persisted.add(20_000L)
        }

        vm.saveProgress(10_000L, 60_000L)
        vm.saveProgress(20_000L, 60_000L)

        // Clear while save is actively in-flight
        vm.clearForTest()

        // Before gate is released: flush is still in progress, scope remains active
        assertTrue("Scope must remain active while in-flight saves drain", vm.isProgressSaveScopeActive)
        assertFalse("Consumer must not complete until channel drains", vm.isProgressSaveConsumerCompleted)

        try {
            gate.complete(Unit)
            advanceUntilIdle()
        } finally {
            if (!gate.isCompleted) gate.complete(Unit)
        }

        // Both in-flight and queued saves executed in order
        assertEquals(listOf(10_000L, 20_000L), persisted)

        // Now scope is cancelled and consumer is completed
        assertFalse("Scope must become inactive after draining", vm.isProgressSaveScopeActive)
        assertTrue("Consumer must be completed after draining", vm.isProgressSaveConsumerCompleted)
    }

    @Test
    fun `F-003 - onCleared with no pending saves exits consumer and cancels scope immediately`() = runTest {
        val vm = createVm()
        assertTrue(vm.isProgressSaveScopeActive)
        assertFalse(vm.isProgressSaveConsumerCompleted)

        vm.clearForTest()
        advanceUntilIdle()

        assertFalse("Scope must be inactive when cleared with empty channel", vm.isProgressSaveScopeActive)
        assertTrue("Consumer must be completed when cleared with empty channel", vm.isProgressSaveConsumerCompleted)

        // Post-clear save does not throw and is not executed
        vm.saveProgress(100L, 200L)
        advanceUntilIdle()
        coVerify(exactly = 0) { playbackRepository.saveProgress(any(), any(), any()) }
    }

    // ------------------------------------------------------------------
    // Per-save upper bound (conventions.md #5 residual risk): a permanently
    // hung repository call must not park the single consumer — it is abandoned
    // after saveTimeoutMs and the queue keeps draining in FIFO order.
    // ------------------------------------------------------------------

    @Test
    fun `hung saveProgress times out and consumer continues serving queued saves in order`() = runTest {
        // The consumer runs on a dispatcher sharing THIS test's scheduler so the
        // withTimeoutOrNull bound is driven by advanceTimeBy (virtual time).
        val vm = createVm(UnconfinedTestDispatcher(testScheduler))
        vm.saveTimeoutMs = 50L
        loadVideo42(vm)
        val persisted = AtomicLong(-1L)
        val events = mutableListOf<String>()
        coEvery { playbackRepository.saveProgress(42L, 1_000L, 100_000L) } coAnswers {
            awaitCancellation()          // a permanently hung repository call
        }
        coEvery { playbackRepository.saveProgress(42L, 2_000L, 100_000L) } coAnswers {
            events.add("2k")
            persisted.set(2_000L)
        }
        coEvery { playbackRepository.saveProgress(42L, 3_000L, 100_000L) } coAnswers {
            events.add("3k")
            persisted.set(3_000L)
        }

        vm.saveProgress(1_000L, 100_000L)
        vm.saveProgress(2_000L, 100_000L)
        vm.saveProgress(3_000L, 100_000L)
        runCurrent()

        // Serialization intact: the hung save still blocks the queue until its
        // timeout bound elapses — no early execution of later saves.
        assertEquals(-1L, persisted.get())

        advanceTimeBy(60)               // cross the saveTimeoutMs bound
        advanceUntilIdle()

        // The hung save was abandoned; both later saves still ran, in enqueue order.
        assertEquals(listOf("2k", "3k"), events)
        assertEquals(3_000L, persisted.get())
    }

    @Test
    fun `F-003 - hung saveProgress times out and drain still terminates consumer and scope`() = runTest {
        val vm = createVm(UnconfinedTestDispatcher(testScheduler))
        vm.saveTimeoutMs = 50L
        loadVideo42(vm)
        val persisted = AtomicLong(-1L)
        coEvery { playbackRepository.saveProgress(42L, 1_000L, 60_000L) } coAnswers {
            awaitCancellation()
        }
        coEvery { playbackRepository.saveProgress(42L, 5_000L, 60_000L) } coAnswers {
            persisted.set(5_000L)
        }

        vm.saveProgress(1_000L, 60_000L)
        vm.saveProgress(5_000L, 60_000L)
        vm.clearForTest()
        runCurrent()

        // Drain is still in progress while the hung save sits under its bound.
        assertTrue("Scope must remain active while the hung save drains", vm.isProgressSaveScopeActive)
        assertFalse("Consumer must not complete while the hung save drains", vm.isProgressSaveConsumerCompleted)

        advanceTimeBy(60)
        advanceUntilIdle()

        // Bounded drain: hung save abandoned, the queued final save still landed,
        // then the consumer exited and the scope was cancelled — no infinite leak.
        assertEquals(5_000L, persisted.get())
        assertFalse("Scope must become inactive after bounded drain", vm.isProgressSaveScopeActive)
        assertTrue("Consumer must be completed after bounded drain", vm.isProgressSaveConsumerCompleted)
    }
}
