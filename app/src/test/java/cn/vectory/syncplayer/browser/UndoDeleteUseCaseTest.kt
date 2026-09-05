package cn.vectory.syncplayer.browser

import cn.vectory.syncplayer.data.local.entity.PlaybackRecordEntity
import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import cn.vectory.syncplayer.data.model.SyncRelationWithSource
import cn.vectory.syncplayer.data.repository.PlaybackRepository
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.storage.volume.EncryptedMediaStore
import cn.vectory.syncplayer.sync.SyncErrorCode
import cn.vectory.syncplayer.sync.SyncException
import cn.vectory.syncplayer.sync.SyncOperation
import dagger.Lazy
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [UndoDeleteUseCase].
 *
 * Covers: successful undo of trash toggle (via real performTrashToggle), successful undo of
 * deferred hard delete, volume-mismatch fail-closed, double-undo idempotency, commit of
 * deferred delete, expectedId protection, and the NothingToUndo cases.
 *
 * Per project testing convention: assert on enums, call counts, and result types —
 * never on user-facing message text.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UndoDeleteUseCaseTest {

    private val fileOps = mockk<FileOperationsUseCase>(relaxed = true)
    private val playbackRepository = mockk<PlaybackRepository>(relaxed = true)
    private val videoRepository = mockk<VideoRepository>(relaxed = true)
    private val encryptedMediaStore = mockk<EncryptedMediaStore>(relaxed = true)
    private val encryptedMediaStoreLazy = mockk<Lazy<EncryptedMediaStore>>()

    private lateinit var useCase: UndoDeleteUseCase
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { encryptedMediaStoreLazy.get() } returns encryptedMediaStore
        every { encryptedMediaStore.volumeId } returns "vol-active"
        useCase = UndoDeleteUseCase(
            fileOps = fileOps,
            playbackRepository = playbackRepository,
            videoRepository = videoRepository,
            encryptedMediaStoreLazy = encryptedMediaStoreLazy,
            context = mockk(relaxed = true)
        )
    }

    // ===================================================================================
    // Helpers
    // ===================================================================================

    private fun videoEntity(id: Long, volumeId: String = "vol-active", relativePath: String = "test.mp4") =
        VideoItemEntity(
            id = id,
            relativePath = relativePath,
            fileName = "test.mp4",
            fileSize = 1000L,
            volumeId = volumeId
        )

    private fun playbackRecord(id: Long, isTrashed: Boolean) =
        PlaybackRecordEntity(videoItemId = id, isTrashed = isTrashed)

    // ===================================================================================
    // Tests — Trash toggle via real performTrashToggle
    // ===================================================================================

    @Test
    fun `performTrashToggle records previous state and undo restores it`() = runTest {
        val id = 1L
        val basePaths = emptyList<SyncRelationWithSource>()
        val errorFlow = MutableStateFlow<String?>(null)
        val scope = this

        coEvery { playbackRepository.getByVideoId(id) } returns playbackRecord(id, false)
        coEvery { playbackRepository.setTrashed(id, false) } returns Unit
        coEvery { videoRepository.getById(id) } returns videoEntity(id)
        // performTrashToggle now calls fileOps.toggleTrashedChecked synchronously inside the
        // mutex; mock it to return Success so onSuccess fires.
        coEvery { fileOps.toggleTrashedChecked(id, basePaths) } returns ToggleTrashResult.Success

        useCase.performTrashToggle(id, basePaths, scope, errorFlow)
        advanceUntilIdle()

        assertTrue(useCase.hasUndoState())

        val result = useCase.undo()

        assertEquals(UndoResult.Success, result)
        coVerify(exactly = 1) { playbackRepository.setTrashed(id, false) }
        assertFalse(useCase.hasUndoState())
    }

    @Test
    fun `performTrashToggleNoGuard records previous state and undo restores it`() = runTest {
        val id = 5L
        val errorFlow = MutableStateFlow<String?>(null)
        val scope = this

        coEvery { playbackRepository.getByVideoId(id) } returns playbackRecord(id, true)
        coEvery { playbackRepository.toggleTrashed(id) } returns Unit
        coEvery { playbackRepository.setTrashed(id, true) } returns Unit
        coEvery { videoRepository.getById(id) } returns videoEntity(id)

        useCase.performTrashToggleNoGuard(id, scope, errorFlow)
        advanceUntilIdle()

        assertTrue(useCase.hasUndoState())

        val result = useCase.undo()

        assertEquals(UndoResult.Success, result)
        coVerify(exactly = 1) { playbackRepository.setTrashed(id, true) }
    }

    @Test
    fun `hard delete debt query excludes a pending trash toggle`() = runTest {
        val id = 6L

        useCase.simulateSetUndoState(UndoableDelete.TrashToggle(id, false))

        assertNull(useCase.getPendingHardDeleteId())

        useCase.simulateSetUndoState(UndoableDelete.HardDelete(id))
        assertEquals(id, useCase.getPendingHardDeleteId())

        useCase.simulateSetUndoState(UndoableDelete.CommittingHardDelete(id))
        assertEquals(id, useCase.getPendingHardDeleteId())
    }

    @Test
    fun `successful trash toggle followed by hard delete enters the deferred delete flow`() = runBlocking {
        val trashId = 40L
        val hardDeleteId = 41L
        val errorFlow = MutableStateFlow<String?>(null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        coEvery { playbackRepository.getByVideoId(trashId) } returns null
        coEvery {
            fileOps.toggleTrashedChecked(trashId, emptyList())
        } returns ToggleTrashResult.Success

        useCase.performTrashToggle(trashId, emptyList(), scope, errorFlow)
        assertTrue("trash toggle did not settle", pollUntil(3000L) {
            useCase.getPendingId() == trashId
        })
        assertNull(useCase.getPendingHardDeleteId())

        useCase.performHardDelete(hardDeleteId, scope, errorFlow)

        assertTrue("hard delete did not enter the deferred flow", pollUntil(3000L) {
            useCase.getPendingId() == hardDeleteId
        })
        assertEquals(hardDeleteId, useCase.getPendingHardDeleteId())
        coVerify(exactly = 0) { fileOps.deleteMediaById(hardDeleteId) }

        scope.cancel()
    }

    @Test
    fun `undo trash toggle fails closed when volume mismatches`() = runTest {
        val id = 2L
        coEvery { videoRepository.getById(id) } returns videoEntity(id, volumeId = "vol-other")

        useCase.simulateSetUndoState(UndoableDelete.TrashToggle(id, false))

        val result = useCase.undo()

        assertTrue(result is UndoResult.Failed)
        assertEquals(SyncErrorCode.STORAGE_ERROR, (result as UndoResult.Failed).error.code)
        verify { playbackRepository wasNot Called }
    }

    @Test
    fun `undo trash toggle succeeds when entity is gone`() = runTest {
        val id = 3L
        coEvery { videoRepository.getById(id) } returns null

        useCase.simulateSetUndoState(UndoableDelete.TrashToggle(id, true))

        val result = useCase.undo()

        assertEquals(UndoResult.Success, result)
        verify { playbackRepository wasNot Called }
    }

    @Test
    fun `undo trash toggle returns Failed when setTrashed throws`() = runTest {
        val id = 4L
        coEvery { videoRepository.getById(id) } returns videoEntity(id)
        coEvery { playbackRepository.setTrashed(id, true) } throws RuntimeException("DB error")

        useCase.simulateSetUndoState(UndoableDelete.TrashToggle(id, true))

        val result = useCase.undo()

        assertTrue(result is UndoResult.Failed)
        assertEquals(SyncErrorCode.DATABASE_ERROR, (result as UndoResult.Failed).error.code)
    }

    // ===================================================================================
    // Tests — Hard delete (deferred) undo
    // ===================================================================================

    @Test
    fun `performHardDelete does not call deleteMediaById`() = runTest {
        val id = 10L
        val errorFlow = MutableStateFlow<String?>(null)

        useCase.performHardDelete(id, this, errorFlow)
        advanceUntilIdle()

        assertTrue(useCase.hasUndoState())
        coVerify(exactly = 0) { fileOps.deleteMediaById(any()) }
    }

    @Test
    fun `undo hard delete cancels pending deletion without calling deleteMediaById`() = runTest {
        val id = 11L
        val errorFlow = MutableStateFlow<String?>(null)

        useCase.performHardDelete(id, this, errorFlow)
        advanceUntilIdle()
        val result = useCase.undo()

        assertEquals(UndoResult.Success, result)
        coVerify(exactly = 0) { fileOps.deleteMediaById(any()) }
        assertFalse(useCase.hasUndoState())
    }

    @Test
    fun `commitDeferredDelete actually calls deleteMediaById`() = runTest {
        val id = 12L
        val errorFlow = MutableStateFlow<String?>(null)

        useCase.performHardDelete(id, this, errorFlow)
        advanceUntilIdle()
        coEvery { fileOps.deleteMediaById(id) } returns DeleteMediaResult.Success(id)

        val result = useCase.commitDeferredDelete()

        assertEquals(UndoResult.Success, result)
        coVerify(exactly = 1) { fileOps.deleteMediaById(id) }
    }

    @Test
    fun `commitDeferredDelete returns Failed when delete fails`() = runTest {
        val id = 13L
        val errorFlow = MutableStateFlow<String?>(null)
        val syncException = SyncException(SyncErrorCode.STORAGE_ERROR, SyncOperation.DELETE)

        useCase.performHardDelete(id, this, errorFlow)
        advanceUntilIdle()
        coEvery { fileOps.deleteMediaById(id) } returns DeleteMediaResult.Failed(id, syncException)

        val result = useCase.commitDeferredDelete()

        assertTrue(result is UndoResult.Failed)
        assertEquals(SyncErrorCode.STORAGE_ERROR, (result as UndoResult.Failed).error.code)
    }

    // ===================================================================================
    // Tests — expectedId protection
    // ===================================================================================

    @Test
    fun `undo with expectedId only acts when id matches`() = runTest {
        val id = 20L
        coEvery { videoRepository.getById(id) } returns videoEntity(id)

        useCase.simulateSetUndoState(UndoableDelete.TrashToggle(id, false))

        // Wrong id — should be no-op
        val wrongResult = useCase.undo(expectedId = 999L)
        assertEquals(UndoResult.NothingToUndo, wrongResult)
        assertTrue(useCase.hasUndoState())

        // Correct id — should undo
        val correctResult = useCase.undo(expectedId = id)
        assertEquals(UndoResult.Success, correctResult)
        assertFalse(useCase.hasUndoState())
    }

    @Test
    fun `commitDeferredDelete with expectedId only acts when id matches`() = runTest {
        val id = 21L
        val errorFlow = MutableStateFlow<String?>(null)

        useCase.performHardDelete(id, this, errorFlow)
        advanceUntilIdle()
        coEvery { fileOps.deleteMediaById(id) } returns DeleteMediaResult.Success(id)

        // Wrong id — should be no-op
        val wrongResult = useCase.commitDeferredDelete(expectedId = 999L)
        assertEquals(UndoResult.NothingToUndo, wrongResult)
        assertTrue(useCase.hasUndoState())
        coVerify(exactly = 0) { fileOps.deleteMediaById(any()) }

        // Correct id — should commit
        val correctResult = useCase.commitDeferredDelete(expectedId = id)
        assertEquals(UndoResult.Success, correctResult)
        coVerify(exactly = 1) { fileOps.deleteMediaById(id) }
    }

    // ===================================================================================
    // Tests — Idempotency / NothingToUndo
    // ===================================================================================

    @Test
    fun `undo returns NothingToUndo when no state recorded`() = runTest {
        val result = useCase.undo()

        assertEquals(UndoResult.NothingToUndo, result)
    }

    @Test
    fun `double undo is idempotent — second undo returns NothingToUndo`() = runTest {
        val id = 22L
        coEvery { videoRepository.getById(id) } returns videoEntity(id)

        useCase.simulateSetUndoState(UndoableDelete.TrashToggle(id, false))

        val first = useCase.undo()
        val second = useCase.undo()

        assertEquals(UndoResult.Success, first)
        assertEquals(UndoResult.NothingToUndo, second)
        coVerify(exactly = 1) { playbackRepository.setTrashed(any(), any()) }
    }

    @Test
    fun `commitDeferredDelete returns NothingToUndo when no state recorded`() = runTest {
        val result = useCase.commitDeferredDelete()

        assertEquals(UndoResult.NothingToUndo, result)
        coVerify(exactly = 0) { fileOps.deleteMediaById(any()) }
    }

    @Test
    fun `latest operation wins — hard delete overwrites trash toggle`() = runTest {
        val id = 30L
        val errorFlow = MutableStateFlow<String?>(null)

        // First: trash toggle
        useCase.simulateSetUndoState(UndoableDelete.TrashToggle(id, false))
        // Then: hard delete overwrites
        useCase.performHardDelete(id, this, errorFlow)
        advanceUntilIdle()

        // Undo should cancel the hard delete (not restore trash toggle)
        val result = useCase.undo()

        assertEquals(UndoResult.Success, result)
        verify { playbackRepository wasNot Called }
    }

    @Test
    fun `clearUndoState removes pending state`() = runTest {
        val id = 40L

        useCase.simulateSetUndoState(UndoableDelete.TrashToggle(id, false))
        assertTrue(useCase.hasUndoState())

        useCase.clearUndoState()
        assertFalse(useCase.hasUndoState())

        val result = useCase.undo()
        assertEquals(UndoResult.NothingToUndo, result)
    }

    @Test
    fun `sequential hard delete auto commits old pending`() = runBlocking {
        val idA = 50L
        val idB = 51L
        val errorFlow = MutableStateFlow<String?>(null)
        // Real (Unconfined) scope: the auto-commit path runs fileOps.deleteMediaById under
        // withContext(Dispatchers.IO), which runTest's virtual-time scheduler cannot drive.
        // runBlocking lets the real IO hop resolve naturally.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        // First hard delete for A
        useCase.performHardDelete(idA, scope, errorFlow)
        pollUntil(timeoutMs = 1000L) { useCase.getPendingId() == idA }
        assertTrue(useCase.hasUndoState())
        assertEquals(idA, useCase.getPendingId())

        // Second hard delete for B — should auto-commit A
        coEvery { fileOps.deleteMediaById(idA) } returns DeleteMediaResult.Success(idA)
        useCase.performHardDelete(idB, scope, errorFlow)

        // A's auto-commit runs on Dispatchers.IO; wait until B becomes the pending id.
        val settled = pollUntil(timeoutMs = 3000L) { useCase.getPendingId() == idB }
        assertTrue("sequential hard delete did not settle within timeout", settled)

        // A should have been committed, B should be pending
        coVerify(exactly = 1) { fileOps.deleteMediaById(idA) }
        assertEquals(idB, useCase.getPendingId())

        scope.cancel()
    }

    // ===================================================================================
    // Tests — Page-leave safety (commitPendingSafely)
    // ===================================================================================

    /**
     * Page-leave safety guarantee: [UndoDeleteUseCase.commitPendingSafely] commits a
     * pending hard delete using its OWN internal safety scope, independent of any caller
     * scope. This is the property that makes `BrowserViewModel.onCleared()` correct —
     * by the time onCleared runs, viewModelScope is already cancelled, so launching into
     * it (the old `commitPendingOnScope(viewModelScope)` path) was a silent no-op that
     * leaked the pending delete forever. `commitPendingSafely` takes no scope argument,
     * so no caller scope state can prevent the commit.
     */
    @Test
    fun `commitPendingSafely commits pending delete via its own safety scope`() = runBlocking {
        val id = 60L
        useCase.simulateSetUndoState(UndoableDelete.HardDelete(id))
        assertTrue(useCase.hasUndoState())
        assertEquals(id, useCase.getPendingId())

        coEvery { fileOps.deleteMediaById(id) } returns DeleteMediaResult.Success(id)

        // No scope argument: this is the whole point — it cannot depend on a caller scope.
        useCase.commitPendingSafely()

        // The commit runs on the use case's internal safety scope (Dispatchers.IO).
        // Bounded poll: fileOps is mocked so the IO work is instant; the poll is reliable.
        val committed = pollUntil(timeoutMs = 3000L) { !useCase.hasUndoState() }
        assertTrue("commitPendingSafely did not commit within timeout", committed)
        assertFalse(useCase.hasUndoState())
        coVerify(exactly = 1) { fileOps.deleteMediaById(id) }
    }

    @Test
    fun `commitPendingSafely is a safe no-op when there is nothing pending`() = runBlocking {
        // No pending state → must not call deleteMediaById and must not throw.
        useCase.commitPendingSafely()
        // Give the safety scope a moment to run its (no-op) coroutine.
        delay(150)
        coVerify(exactly = 0) { fileOps.deleteMediaById(any()) }
        assertFalse(useCase.hasUndoState())
    }

    @Test
    fun `commitPendingSafely commits even when caller scope is already cancelled`() = runBlocking {
        // Reproduces the exact onCleared() scenario: the caller scope (viewModelScope
        // stand-in) is cancelled BEFORE the safety commit is requested. The old code
        // passed this dead scope to launch() and silently dropped the commit.
        val id = 61L
        val deadCallerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val performed = CompletableDeferred<Unit>()
        useCase.performHardDelete(id, deadCallerScope, MutableStateFlow(null)) {
            performed.complete(Unit)
        }
        performed.await()
        assertTrue(useCase.hasUndoState())
        assertEquals(id, useCase.getPendingId())

        // Cancel the caller scope first — mimics viewModelScope being cancelled in onCleared.
        deadCallerScope.cancel()

        coEvery { fileOps.deleteMediaById(id) } returns DeleteMediaResult.Success(id)

        // commitPendingSafely ignores the (dead) caller scope and uses its own scope.
        useCase.commitPendingSafely()

        val committed = pollUntil(timeoutMs = 3000L) { !useCase.hasUndoState() }
        assertTrue("commit did not run despite the caller scope being cancelled", committed)
        coVerify(exactly = 1) { fileOps.deleteMediaById(id) }
    }

    // ===================================================================================
    // Tests — CancellationException propagation (review E)
    // ===================================================================================

    /**
     * CancellationException must propagate, NOT be swallowed into the error flow. If it were
     * caught by the generic `catch (e: Exception)`, errorFlow would be set and onSuccess would
     * be skipped silently — breaking structured concurrency. This is the guarantee that lets
     * ViewModel scope cancellation actually cancel an in-flight toggle.
     */
    @Test
    fun `performTrashToggleNoGuard rethrows CancellationException instead of swallowing it`() = runTest {
        val id = 70L
        val errorFlow = MutableStateFlow<String?>(null)
        var onSuccessCalled = false

        coEvery { playbackRepository.getByVideoId(id) } returns playbackRecord(id, false)
        coEvery { playbackRepository.toggleTrashed(id) } throws CancellationException("test cancel")

        useCase.performTrashToggleNoGuard(id, this, errorFlow) { onSuccessCalled = true }
        advanceUntilIdle()

        // CancellationException propagated → onSuccess NOT called, errorFlow NOT set (would be
        // non-null if swallowed by the generic catch), undoState NOT recorded.
        assertFalse(onSuccessCalled)
        assertNull(errorFlow.value)
        assertFalse(useCase.hasUndoState())
    }

    /**
     * A FAILED toggle (non-cancellation exception) must not call onSuccess — this is the
     * use-case-level guarantee behind "toggle failure shows no success Snackbar": the Snackbar
     * trigger lives in onSuccess, so a failed toggle never arms it. The error lands in errorFlow.
     */
    @Test
    fun `performTrashToggleNoGuard does not call onSuccess when toggle fails`() = runTest {
        val id = 71L
        val errorFlow = MutableStateFlow<String?>(null)
        var onSuccessCalled = false

        coEvery { playbackRepository.getByVideoId(id) } returns playbackRecord(id, false)
        coEvery { playbackRepository.toggleTrashed(id) } throws RuntimeException("DB down")

        useCase.performTrashToggleNoGuard(id, this, errorFlow) { onSuccessCalled = true }
        advanceUntilIdle()

        assertFalse(onSuccessCalled)
        assertFalse(useCase.hasUndoState())
        // Error is surfaced (localized message resolved by the use case; we only assert non-null).
        assertNotNull(errorFlow.value)
    }

    /**
     * commitDeferredDelete must rethrow CancellationException rather than masking it as
     * UndoResult.Failed. (Uses runBlocking + Unconfined so the real withContext(IO) hop and
     * the CancellationException propagate deterministically.)
     */
    @Test
    fun `commitDeferredDelete rethrows CancellationException instead of masking it as Failed`() = runBlocking {
        val id = 72L
        useCase.simulateSetUndoState(UndoableDelete.HardDelete(id))
        coEvery { fileOps.deleteMediaById(id) } throws CancellationException("test cancel")

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var propagated = false
        val job = scope.launch {
            try {
                useCase.commitDeferredDelete()
            } catch (e: CancellationException) {
                propagated = true
            }
        }
        job.join()
        assertTrue("CancellationException was swallowed (masked as Failed)", propagated)
        scope.cancel()
    }

    // ===================================================================================
    // Tests — Failed handling (review E: Failed not reported as success)
    // ===================================================================================

    /**
     * Auto-committing an old pending delete whose deleteMediaById returns Failed must surface
     * the error and retain the uncertain debt instead of recording a new operation over it.
     */
    @Test
    fun `sequential hard delete retains failed auto-committed debt and blocks new id`() = runBlocking {
        val idA = 80L
        val idB = 81L
        var surfacedError: String? = null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        useCase.performHardDelete(idA, scope, { surfacedError = it })
        pollUntil(timeoutMs = 1000L) { useCase.getPendingId() == idA }

        val failed = DeleteMediaResult.Failed(idA, SyncException(SyncErrorCode.STORAGE_ERROR, SyncOperation.DELETE))
        coEvery { fileOps.deleteMediaById(idA) } returns failed

        useCase.performHardDelete(idB, scope, { surfacedError = it })
        pollUntil(timeoutMs = 3000L) { useCase.getPendingId() == idA }

        // Old pending's failure surfaced (not silently dropped)…
        assertTrue(
            "auto-commit Failed must surface to onError",
            pollUntil(timeoutMs = 3000L) { surfacedError != null }
        )
        // The new id must not overwrite an already-started, uncertain delete for A.
        assertEquals(idA, useCase.getPendingId())
        coVerify(exactly = 1) { fileOps.deleteMediaById(idA) }
        scope.cancel()
    }

    /**
     * commitPendingSafely(expectedId) only commits the matching pending id; a stale dispose
     * (expectedId = old id) must NOT commit a newer operation (new id). This is the "consecutive
     * delete does not mis-commit the new id" guarantee for the page-leave safety path.
     */
    @Test
    fun `commitPendingSafely with stale expectedId does not commit a newer pending delete`() = runBlocking {
        val oldId = 90L
        val newId = 91L

        // A newer operation now holds the slot.
        useCase.simulateSetUndoState(UndoableDelete.HardDelete(newId))

        coEvery { fileOps.deleteMediaById(any()) } returns DeleteMediaResult.Success(0L)

        // Stale dispose carries the OLD id — must be a no-op against the newer pending delete.
        useCase.commitPendingSafely(expectedId = oldId)

        // Give the safety scope a moment; the new id must remain untouched.
        delay(150)
        coVerify(exactly = 0) { fileOps.deleteMediaById(any()) }
        assertTrue(useCase.hasUndoState())
        assertEquals(newId, useCase.getPendingId())
    }

    @Test
    fun `commitPendingSafely with matching expectedId commits the pending delete`() = runBlocking {
        val id = 92L
        useCase.simulateSetUndoState(UndoableDelete.HardDelete(id))
        coEvery { fileOps.deleteMediaById(id) } returns DeleteMediaResult.Success(id)

        useCase.commitPendingSafely(expectedId = id)

        val committed = pollUntil(timeoutMs = 3000L) { !useCase.hasUndoState() }
        assertTrue("matching expectedId should have committed", committed)
        coVerify(exactly = 1) { fileOps.deleteMediaById(id) }
    }

    /** Bounded real-time poll for safety-scope work (runs on Dispatchers.IO, not the test scheduler). */
    private suspend fun pollUntil(
        timeoutMs: Long,
        intervalMs: Long = 25L,
        condition: () -> Boolean
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        do {
            if (condition()) return true
            delay(intervalMs)
        } while (System.currentTimeMillis() < deadline)
        return condition()
    }

    // ===================================================================================
    // Tests — Concurrent toggle serialization (review E: rapid trash toggles)
    // ===================================================================================

    /**
     * Rapid consecutive toggles of the same id MUST serialize under [UndoDeleteUseCase]'s mutex.
     * The 3rd-round review (FAIL 7.6) flagged that the previous code performed `read previous →
     * toggle → record undo state` as separate non-atomic steps, so two concurrent toggles could
     * both observe the SAME `previousTrashed` and then both record the SAME undo state — making
     * undo restore the wrong final state.
     *
     * With mutex serialization, the second toggle observes the row mutated by the first, and the
     * recorded undo state captures the state just before the second toggle. Undoing restores the
     * state right before the second toggle.
     */
    @Test
    fun `two rapid toggles of same id serialize — undo restores the state before the second toggle`() = runBlocking {
        val id = 100L
        val errorFlow = MutableStateFlow<String?>(null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        // Real mutable state so we can simulate the DB row flipping per toggle.
        var currentTrashed = false
        coEvery { playbackRepository.getByVideoId(id) } answers { playbackRecord(id, currentTrashed) }
        coEvery { playbackRepository.toggleTrashed(id) } answers { currentTrashed = !currentTrashed }
        coEvery { playbackRepository.setTrashed(id, any()) } answers { currentTrashed = secondArg<Boolean>() }
        coEvery { videoRepository.getById(id) } returns videoEntity(id)

        // Fire two toggles back-to-back.
        useCase.performTrashToggleNoGuard(id, scope, errorFlow)
        useCase.performTrashToggleNoGuard(id, scope, errorFlow)
        // Both toggles ran under the mutex; second one's undo state should now be recorded.
        pollUntil(timeoutMs = 1000L) { useCase.hasUndoState() }
        assertTrue("second toggle should have recorded undo state", useCase.hasUndoState())

        // After two toggles the DB value flipped twice: false → true → false. Undoing restores
        // the state captured BEFORE the second toggle, i.e. true (the state set by the first toggle).
        val undoResult = useCase.undo()
        assertEquals(UndoResult.Success, undoResult)
        assertEquals("undo restores the second-toggle's previousTrashed (true)", true, currentTrashed)

        scope.cancel()
    }

    /**
     * Same scenario via [UndoDeleteUseCase.performTrashToggle] (the base-path-guarded path) to
     * ensure the [FileOperationsUseCase.toggleTrashedChecked] call is invoked under the mutex.
     */
    @Test
    fun `two rapid guarded toggles of same id serialize via toggleTrashedChecked`() = runBlocking {
        val id = 101L
        val basePaths = emptyList<SyncRelationWithSource>()
        val errorFlow = MutableStateFlow<String?>(null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        var currentTrashed = false
        coEvery { playbackRepository.getByVideoId(id) } answers { playbackRecord(id, currentTrashed) }
        coEvery { playbackRepository.setTrashed(id, any()) } answers { currentTrashed = secondArg<Boolean>() }
        // toggleTrashedChecked is the actual mutator in the guarded path — simulate its DB flip.
        coEvery { fileOps.toggleTrashedChecked(id, basePaths) } answers {
            currentTrashed = !currentTrashed
            ToggleTrashResult.Success
        }
        // undoTrashToggle does a volume-ownership check via videoRepository.getById — return
        // an entity on the active volume so undo is permitted.
        coEvery { videoRepository.getById(id) } returns videoEntity(id)

        useCase.performTrashToggle(id, basePaths, scope, errorFlow)
        useCase.performTrashToggle(id, basePaths, scope, errorFlow)
        pollUntil(timeoutMs = 1000L) { useCase.hasUndoState() }
        assertTrue("second guarded toggle should have recorded undo state", useCase.hasUndoState())

        val undoResult = useCase.undo()
        assertEquals(UndoResult.Success, undoResult)
        assertEquals("undo restores second-toggle's previousTrashed (true)", true, currentTrashed)

        scope.cancel()
    }

    // ===================================================================================
    // Tests — BUG-016: trash toggle auto-commits pending hard delete
    // ===================================================================================
    //
    // A pending (deferred) hard delete is a delete the user already confirmed — only undo
    // can cancel it. Before BUG-016, both trash-toggle entries overwrote `undoState`
    // unconditionally: a toggle after a hard delete silently cancelled that pending delete
    // (the file was never deleted, but also never undoable-committed — silently lost).
    // These tests pin the fix: any new operation auto-commits the pending hard delete first
    // (same contract the sequential-hard-delete path :358 already had), with failures
    // surfaced via the actual error flow WITHOUT blocking the new operation.

    @Test
    fun `trash toggle auto commits pending hard delete instead of overwriting it`() = runBlocking {
        val idA = 10L
        val idB = 20L
        val errorFlow = MutableStateFlow<String?>(null)
        // Real (Unconfined) scope: the auto-commit path runs fileOps.deleteMediaById under
        // withContext(Dispatchers.IO), which runTest's virtual-time scheduler cannot drive.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        // First: pending hard delete for A.
        useCase.performHardDelete(idA, scope, errorFlow)
        pollUntil(timeoutMs = 1000L) { useCase.getPendingId() == idA }

        coEvery { fileOps.deleteMediaById(idA) } returns DeleteMediaResult.Success(idA)
        coEvery { playbackRepository.getByVideoId(idB) } returns null
        coEvery { playbackRepository.toggleTrashed(idB) } returns Unit

        // Then: trash toggle for B — must auto-commit A instead of overwriting it.
        useCase.performTrashToggleNoGuard(idB, scope, errorFlow)
        val settled = pollUntil(timeoutMs = 3000L) { useCase.getPendingId() == idB }
        assertTrue("trash toggle did not settle within timeout", settled)

        // A's deferred delete was actually executed (current code: 0 — RED).
        coVerify(exactly = 1) { fileOps.deleteMediaById(idA) }
        // B's toggle is recorded as the new pending operation.
        assertEquals(idB, useCase.getPendingId())
        // A's slot is gone — a stale snackbar commit for A can no longer resurrect it.
        assertEquals(UndoResult.NothingToUndo, useCase.commitDeferredDelete(expectedId = idA))

        scope.cancel()
    }

    @Test
    fun `guarded trash toggle auto commits pending hard delete instead of overwriting it`() = runBlocking {
        val idA = 10L
        val idB = 21L
        val basePaths = emptyList<SyncRelationWithSource>()
        val errorFlow = MutableStateFlow<String?>(null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        useCase.performHardDelete(idA, scope, errorFlow)
        pollUntil(timeoutMs = 1000L) { useCase.getPendingId() == idA }

        coEvery { fileOps.deleteMediaById(idA) } returns DeleteMediaResult.Success(idA)
        coEvery { playbackRepository.getByVideoId(idB) } returns null
        // The guarded path mutates through toggleTrashedChecked — stub Success so the
        // toggle completes and records undo state.
        coEvery { fileOps.toggleTrashedChecked(idB, basePaths) } returns ToggleTrashResult.Success

        useCase.performTrashToggle(idB, basePaths, scope, errorFlow)
        val settled = pollUntil(timeoutMs = 3000L) { useCase.getPendingId() == idB }
        assertTrue("guarded trash toggle did not settle within timeout", settled)

        coVerify(exactly = 1) { fileOps.deleteMediaById(idA) }
        assertEquals(idB, useCase.getPendingId())

        scope.cancel()
    }

    @Test
    fun `uncertain failed auto commit is retained and blocks a new toggle`() = runBlocking {
        val idA = 10L
        val idB = 22L
        val realFlow = MutableStateFlow<String?>(null)
        val errorCount = AtomicInteger(0)
        // Counting adapter over the REAL flow: performTrashToggleNoGuard reports via
        // `errorFlow.value = …`, so the adapter counts actual error publications.
        val countingFlow = object : MutableStateFlow<String?> by realFlow {
            override var value: String?
                get() = realFlow.value
                set(newValue) {
                    errorCount.incrementAndGet()
                    realFlow.value = newValue
                }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        useCase.performHardDelete(idA, scope, countingFlow)
        pollUntil(timeoutMs = 1000L) { useCase.getPendingId() == idA }

        val failed = DeleteMediaResult.Failed(
            idA, SyncException(SyncErrorCode.STORAGE_ERROR, SyncOperation.DELETE)
        )
        coEvery { fileOps.deleteMediaById(idA) } returns failed
        coEvery { playbackRepository.getByVideoId(idB) } returns null
        coEvery { playbackRepository.toggleTrashed(idB) } returns Unit

        useCase.performTrashToggleNoGuard(idB, scope, countingFlow)
        val settled = pollUntil(timeoutMs = 3000L) { useCase.getPendingId() == idA }
        assertTrue("uncertain auto-commit debt did not remain", settled)

        // The auto-commit failure surfaced through the actual error flow (not swallowed)…
        assertTrue(
            "auto-commit Failed must publish one error",
            pollUntil(timeoutMs = 3000L) { errorCount.get() == 1 }
        )
        // The new toggle must not overwrite an already-started, uncertain delete.
        coVerify(exactly = 0) { playbackRepository.toggleTrashed(idB) }

        scope.cancel()
    }

    @Test
    fun `successful committing drain followed by rejected trash lets the next delete enter`() = runBlocking {
        val idA = 32L
        val idB = 33L
        val idC = 34L
        val basePaths = emptyList<SyncRelationWithSource>()
        val errorFlow = MutableStateFlow<String?>(null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        useCase.simulateSetUndoState(UndoableDelete.CommittingHardDelete(idA))

        coEvery { fileOps.deleteMediaById(idA) } returns DeleteMediaResult.Success(idA)
        coEvery {
            fileOps.toggleTrashedChecked(idB, basePaths)
        } returns ToggleTrashResult.NotAllowed

        useCase.performTrashToggle(idB, basePaths, scope, errorFlow)
        assertTrue("rejected trash operation did not settle", pollUntil(3000L) {
            useCase.getPendingId() == null && errorFlow.value != null
        })
        coVerify(exactly = 1) { fileOps.deleteMediaById(idA) }
        coVerify(exactly = 1) { fileOps.toggleTrashedChecked(idB, basePaths) }

        useCase.performHardDelete(idC, scope, errorFlow)
        assertTrue("next delete was not recorded after debt drain", pollUntil(3000L) {
            useCase.getPendingId() == idC
        })
        coVerify(exactly = 0) { fileOps.deleteMediaById(idC) }
        scope.cancel()
    }

    @Test
    fun `failed delete debt is retried by the next delete before recording it`() = runBlocking {
        val idA = 23L
        val idB = 24L
        val errorFlow = MutableStateFlow<String?>(null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        coEvery { fileOps.deleteMediaById(idA) } returnsMany listOf(
            DeleteMediaResult.Failed(
                idA,
                SyncException(SyncErrorCode.STORAGE_ERROR, SyncOperation.DELETE)
            ),
            DeleteMediaResult.Success(idA)
        )

        useCase.performHardDelete(idA, scope, errorFlow)
        assertTrue("initial hard delete was not recorded", pollUntil(3000L) {
            useCase.getPendingId() == idA
        })

        useCase.performHardDelete(idB, scope, errorFlow)
        assertTrue("failed retry must remain observable as debt", pollUntil(3000L) {
            useCase.getPendingId() == idA
        })
        assertTrue("retry failure must be surfaced", pollUntil(3000L) {
            errorFlow.value != null
        })
        coVerify(exactly = 1) { fileOps.deleteMediaById(idA) }
        coVerify(exactly = 0) { fileOps.deleteMediaById(idB) }

        useCase.performHardDelete(idB, scope, errorFlow)
        assertTrue("successful retry must allow the new delete", pollUntil(3000L) {
            useCase.getPendingId() == idB
        })
        coVerify(exactly = 2) { fileOps.deleteMediaById(idA) }
        scope.cancel()
    }

    @Test
    fun `same-id hard delete twice still keeps pending without auto commit`() = runBlocking {
        // Green guard: re-deleting the SAME id replaces nothing — the pending delete for
        // that id stays pending (same-id overwrite is not a "would lose a delete" case).
        val id = 10L
        val errorFlow = MutableStateFlow<String?>(null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        useCase.performHardDelete(id, scope, errorFlow)
        pollUntil(timeoutMs = 1000L) { useCase.getPendingId() == id }
        useCase.performHardDelete(id, scope, errorFlow)
        pollUntil(timeoutMs = 3000L) { useCase.getPendingId() == id }

        coVerify(exactly = 0) { fileOps.deleteMediaById(any()) }
        assertEquals(id, useCase.getPendingId())

        scope.cancel()
    }

    @Test
    fun `same-id hard delete retries an already committing debt before remaining non-undoable`() = runBlocking {
        val id = 25L
        val errorFlow = MutableStateFlow<String?>(null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        useCase.simulateSetUndoState(UndoableDelete.CommittingHardDelete(id))
        coEvery { fileOps.deleteMediaById(id) } returns DeleteMediaResult.Success(id)

        useCase.performHardDelete(id, scope, errorFlow)

        assertTrue("same-id committing debt was not drained", pollUntil(3000L) {
            useCase.getPendingId() == id
        })
        coVerify(exactly = 1) { fileOps.deleteMediaById(id) }
        assertEquals(UndoResult.Success, useCase.undo(expectedId = id))
        scope.cancel()
    }

    @Test
    fun `cancelled auto commit keeps the pending delete drainable`() = runBlocking {
        // Once auto-commit starts, its outcome is uncertain if cancellation interrupts it.
        val idA = 10L
        val idB = 23L
        val errorFlow = MutableStateFlow<String?>(null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        useCase.performHardDelete(idA, scope, errorFlow)
        pollUntil(timeoutMs = 1000L) { useCase.getPendingId() == idA }

        // Gate the auto-commit delete: it starts, then hangs until the test cancels the caller.
        val deleteStarted = CountDownLatch(1)
        val releaseDelete = CompletableDeferred<Unit>()
        coEvery { fileOps.deleteMediaById(idA) } coAnswers {
            deleteStarted.countDown()
            releaseDelete.await()
            DeleteMediaResult.Success(idA)
        }
        coEvery { playbackRepository.getByVideoId(idB) } returns null

        useCase.performTrashToggleNoGuard(idB, scope, errorFlow)
        assertTrue("auto-commit delete never started", deleteStarted.await(5, TimeUnit.SECONDS))

        scope.cancel() // caller cancelled mid-delete → CE must retain non-undoable debt
        scope.coroutineContext[Job]?.children?.forEach { it.join() }

        val restored = pollUntil(timeoutMs = 3000L) { useCase.getPendingId() == idA }
        assertTrue(
            "cancelled auto-commit must keep the non-undoable debt (got ${useCase.getPendingId()})",
            restored,
        )
        // Cancellation is not an error — nothing may be published to the error flow.
        assertNull(errorFlow.value)
        // The cancelled toggle never reached its own record step.
        coVerify(exactly = 0) { playbackRepository.toggleTrashed(idB) }

        assertEquals(UndoResult.NothingToUndo, useCase.undo(expectedId = idA))

        // The retained uncertain delete is still drainable by the safety path.
        coEvery { fileOps.deleteMediaById(idA) } returns DeleteMediaResult.Success(idA)
        useCase.commitPendingSafely(expectedId = idA)
        val drained = pollUntil(timeoutMs = 3000L) { !useCase.hasUndoState() }
        assertTrue("restored pending delete must be drainable", drained)
        coVerify(exactly = 2) { fileOps.deleteMediaById(idA) }
    }

    @Test
    fun `cancelled commitDeferredDelete keeps the pending delete drainable`() = runBlocking {
        // Same contract on the snackbar-expire commit path: once the delete starts, a mid-delete
        // cancellation must retain a non-undoable pending debt.
        val id = 30L
        useCase.simulateSetUndoState(UndoableDelete.HardDelete(id))

        val deleteStarted = CountDownLatch(1)
        val releaseDelete = CompletableDeferred<Unit>()
        coEvery { fileOps.deleteMediaById(id) } coAnswers {
            deleteStarted.countDown()
            releaseDelete.await()
            DeleteMediaResult.Success(id)
        }
        val commitScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val cancellation = CancellationException("cancel deferred commit")
        val result = CompletableDeferred<Throwable?>()
        val job = commitScope.launch {
            try {
                useCase.commitDeferredDelete(expectedId = id)
                result.complete(null)
            } catch (t: Throwable) {
                result.complete(t)
            }
        }

        assertTrue("commit delete never started", deleteStarted.await(5, TimeUnit.SECONDS))
        job.cancel(cancellation)
        job.join()

        assertSame(cancellation, result.await())
        assertTrue("cancelled commit must keep the pending state", useCase.hasUndoState())
        assertEquals(id, useCase.getPendingId())
        assertEquals(UndoResult.NothingToUndo, useCase.undo(expectedId = id))

        // Still drainable: a retry commit runs the delete to a confirmed terminal.
        coEvery { fileOps.deleteMediaById(id) } returns DeleteMediaResult.Success(id)
        assertEquals(UndoResult.Success, useCase.commitDeferredDelete(expectedId = id))
        coVerify(exactly = 2) { fileOps.deleteMediaById(id) }
    }

    @Test
    fun `partial delete cancellation is not undoable and safe drain retries the delete`() = runBlocking {
        val id = 31L
        useCase.simulateSetUndoState(UndoableDelete.HardDelete(id))

        val deleteStarted = CountDownLatch(1)
        val thumbAndDbStage = CompletableDeferred<Unit>()
        val cancellation = CancellationException("cancel after ciphertext")
        var ciphertextDeleted = false
        coEvery { fileOps.deleteMediaById(id) } coAnswers {
            ciphertextDeleted = true
            deleteStarted.countDown()
            thumbAndDbStage.await()
            DeleteMediaResult.Success(id)
        } andThen DeleteMediaResult.Success(id)

        val commitScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val failure = CompletableDeferred<Throwable?>()
        val job = commitScope.launch {
            try {
                useCase.commitDeferredDelete(expectedId = id)
                failure.complete(null)
            } catch (t: Throwable) {
                failure.complete(t)
            }
        }

        assertTrue("staged delete never started", deleteStarted.await(5, TimeUnit.SECONDS))
        assertTrue(ciphertextDeleted)
        job.cancel(cancellation)
        job.join()

        assertSame(cancellation, failure.await())
        assertEquals(UndoResult.NothingToUndo, useCase.undo(expectedId = id))
        assertEquals(id, useCase.getPendingId())
        assertEquals(UndoResult.Success, useCase.commitDeferredDelete(expectedId = id))
        coVerify(exactly = 2) { fileOps.deleteMediaById(id) }
        commitScope.cancel()
    }
}
