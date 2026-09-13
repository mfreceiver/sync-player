package cn.vectory.syncplayer.browser

import android.content.Context
import android.util.Log
import cn.vectory.syncplayer.R
import cn.vectory.syncplayer.data.model.SyncRelationWithSource
import cn.vectory.syncplayer.data.repository.PlaybackRepository
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.sync.SyncErrorCode
import cn.vectory.syncplayer.sync.SyncException
import cn.vectory.syncplayer.sync.SyncOperation
import cn.vectory.syncplayer.sync.syncErrorMessage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/** The kind of delete operation that can be undone. */
sealed class UndoableDelete {
    /** The video item id this operation targets. */
    abstract val id: Long

    /**
     * Toggle trashed flag (soft delete).
     * [previousTrashed] captures the state before the toggle so undo can restore it exactly
     * (rather than blindly toggling again, which would be wrong if the user toggled twice).
     */
    data class TrashToggle(override val id: Long, val previousTrashed: Boolean) : UndoableDelete()

    /**
     * Hard delete (file + thumb + DB).
     * The deletion is NOT executed immediately — it is deferred until [UndoDeleteUseCase.commitDeferredDelete]
     * is called (when the Snackbar expires without undo). Undo simply cancels the pending deletion.
     */
    data class HardDelete(override val id: Long) : UndoableDelete()

    /**
     * A deferred hard delete whose commit has been attempted and whose final outcome is not yet
     * confirmed. It is deliberately not undoable: the delete may have partially completed, so
     * it remains owed and can only be retried by a safe drain.
     */
    data class CommittingHardDelete(override val id: Long) : UndoableDelete()
}

/** Result of an undo attempt. */
sealed class UndoResult {
    data object Success : UndoResult()
    data class Failed(val error: SyncException) : UndoResult()
    data object NothingToUndo : UndoResult()
}

/**
 * Encapsulates undo support for delete operations across Browser, Player, and ImageViewer.
 *
 * Design:
 * - [performTrashToggle] records the previous trashed state, then performs the toggle.
 *   Undo restores the exact previous state via [PlaybackRepository.setTrashed].
 * - [performHardDelete] does NOT execute the deletion immediately — it records a pending
 *   deletion and notifies the UI to refresh (removing the item from the list). The actual
 *   deletion is deferred until [commitDeferredDelete] (called when the Snackbar expires).
 *   Undo cancels the pending deletion (the file was never actually deleted).
 *
 * Race-condition protection:
 * - [undo]/[commitDeferredDelete] accept an optional [expectedId]. When provided, they only
 *   act if the current pending operation matches that id — preventing a stale snackbar from
 *   committing/cancelling a newer operation.
 * - Any new undoable operation that would overwrite a pending hard delete (another hard
 *   delete, or a trash toggle) auto-commits it first, so no pending delete is ever silently
 *   lost.
 *
 * Only the most recent delete operation is retained (latest-first semantics per task requirement 6).
 */
@Singleton
class UndoDeleteUseCase @Inject constructor(
    private val fileOps: FileOperationsUseCase,
    private val playbackRepository: PlaybackRepository,
    private val videoRepository: VideoRepository,
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "UndoDeleteUseCase"
    }

    private val undoState = AtomicReference<UndoableDelete?>(null)
    private val mutex = Mutex()

    /**
     * Dedicated "must-complete" scope owned by this Singleton for page-leave safety
     * commits. It survives cancellation of any caller's scope (e.g. [androidx.lifecycle.viewModelScope]
     * being cancelled in `ViewModel.onCleared()` — by the time `onCleared` runs, that
     * scope is already cancelled and launching into it silently drops the coroutine,
     * which would leak a pending hard delete forever).
     *
     * This is a controlled, named scope — explicitly NOT [kotlinx.coroutines.GlobalScope].
     * The Singleton owns its lifetime; on process death it is abandoned (no leak).
     */
    private val safetyScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Perform a trash-toggle operation with undo support (Browser context — with base-path guard).
     * Records the previous trashed state so undo can restore it exactly.
     *
     * The full sequence (read previous state → toggle via [FileOperationsUseCase.toggleTrashedChecked]
     * → record undo state) runs under [mutex] so two rapid toggles of the same id serialize: the
     * second toggle observes the row state mutated by the first, and each undo restores the exact
     * state that preceded that specific toggle. The toggle itself is no longer fired into an
     * inner `launch` — errors flow synchronously into [errorFlow], and a successful toggle invokes
     * [onSuccess] before releasing the lock.
     */
    fun performTrashToggle(
        videoItemId: Long,
        basePaths: List<SyncRelationWithSource>,
        scope: CoroutineScope,
        errorFlow: MutableStateFlow<String?>,
        onSuccess: () -> Unit = {}
    ) = performTrashToggle(videoItemId, basePaths, scope, { errorFlow.value = it }, onSuccess)

    fun performTrashToggle(
        videoItemId: Long,
        basePaths: List<SyncRelationWithSource>,
        scope: CoroutineScope,
        onError: (String) -> Unit,
        onSuccess: () -> Unit = {}
    ) {
        scope.launch {
            try {
                mutex.withLock {
                    // Capture previous state before toggle (under the lock so a concurrent
                    // toggle cannot interleave between read and apply).
                    val record = playbackRepository.getByVideoId(videoItemId)
                    val previousTrashed = record?.isTrashed ?: false

                    // BUG-016: a trash toggle must not silently cancel a pending hard delete —
                    // commit it first; an uncertain result blocks the toggle so the debt stays drainable.
                    val canProceed = autoCommitPendingHardDeleteLocked(
                        videoItemId, incomingIsHardDelete = false, onError
                    )
                    if (!canProceed) return@withLock

                    when (val result = fileOps.toggleTrashedChecked(videoItemId, basePaths)) {
                        is ToggleTrashResult.Success -> {
                            undoState.set(UndoableDelete.TrashToggle(videoItemId, previousTrashed))
                            onSuccess()
                        }
                        is ToggleTrashResult.NotAllowed ->
                            onError(context.getString(R.string.error_only_base_dir_trash))
                        is ToggleTrashResult.Failed ->
                            onError(syncErrorMessage(result.error, context))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to perform trash toggle with undo: ${safeErrorDetail(e)}")
                onError(syncErrorMessage(e, context))
            }
        }
    }

    /**
     * Perform a trash-toggle operation with undo support (Player/ImageViewer context — no base-path guard).
     * Records the previous trashed state so undo can restore it exactly.
     *
     * As with [performTrashToggle], the read → toggle → record-sequence runs atomically under
     * [mutex] so rapid consecutive toggles serialize and each undo restores the correct prior state.
     */
    fun performTrashToggleNoGuard(
        videoItemId: Long,
        scope: CoroutineScope,
        errorFlow: MutableStateFlow<String?>,
        onSuccess: () -> Unit = {}
    ) {
        scope.launch {
            try {
                mutex.withLock {
                    val record = playbackRepository.getByVideoId(videoItemId)
                    val previousTrashed = record?.isTrashed ?: false

                    // BUG-016: a trash toggle must not silently cancel a pending hard delete —
                    // commit it first; an uncertain result blocks the toggle so the debt stays drainable.
                    val canProceed = autoCommitPendingHardDeleteLocked(
                        videoItemId, incomingIsHardDelete = false
                    ) {
                        errorFlow.value = it
                    }
                    if (!canProceed) return@withLock

                    playbackRepository.toggleTrashed(videoItemId)
                    undoState.set(UndoableDelete.TrashToggle(videoItemId, previousTrashed))
                    onSuccess()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to perform trash toggle (no guard): ${safeErrorDetail(e)}")
                errorFlow.value = syncErrorMessage(e, context)
            }
        }
    }

    /**
     * Perform a hard delete with undo support (deferred execution).
     *
     * The deletion is NOT executed immediately — it is recorded as pending until
     * [commitDeferredDelete] is called (when the Snackbar expires without undo).
     * Undo cancels the pending deletion (the file was never actually deleted).
     *
     * If there is already a pending hard delete for a DIFFERENT id, it is auto-committed
     * (executed) before the new one is recorded — ensuring no pending delete is silently lost.
     */
    fun performHardDelete(
        videoItemId: Long,
        scope: CoroutineScope,
        errorFlow: MutableStateFlow<String?>,
        onSuccess: () -> Unit = {}
    ) = performHardDelete(videoItemId, scope, { errorFlow.value = it }, onSuccess)

    fun performHardDelete(
        videoItemId: Long,
        scope: CoroutineScope,
        onError: (String) -> Unit,
        onSuccess: () -> Unit = {}
    ) {
        scope.launch {
            val recorded = mutex.withLock {
                // BUG-016: auto-commit any pending hard delete for a DIFFERENT id before
                // recording the new one (extracted to autoCommitPendingHardDeleteLocked, now
                // shared by all entries so a trash toggle cannot silently lose one either).
                val canProceed = autoCommitPendingHardDeleteLocked(
                    videoItemId, incomingIsHardDelete = true, onError
                )
                if (!canProceed) return@withLock false
                undoState.set(UndoableDelete.HardDelete(videoItemId))
                true
            }
            if (recorded) onSuccess()
        }
    }

    /**
     * Auto-commit the currently pending hard delete so the next undoable operation can take
     * the undo slot without silently losing it (BUG-016 — previously only a sequential hard
     * delete did this; trash toggles just overwrote the pending delete).
     *
     * MUST be called while [mutex] is held (naming per the repo's `*Locked` convention).
     * Skips when there is nothing to protect: no pending state, a pending trash toggle
     * (nothing is deferred — overwrite is safe), or a not-yet-started hard delete for the SAME id
     * being hard-deleted again (same id replaces itself; an already-started delete is drained).
     *
     * An uncertain failure is surfaced via [onError] and blocks the incoming operation. The
     * existing debt must remain drainable rather than being overwritten.
     */
    private suspend fun autoCommitPendingHardDeleteLocked(
        incomingId: Long,
        incomingIsHardDelete: Boolean,
        onError: (String) -> Unit,
    ): Boolean {
        val existing = undoState.get()
        val pendingHardDelete = when (existing) {
            is UndoableDelete.HardDelete,
            is UndoableDelete.CommittingHardDelete -> existing
            else -> return true
        }
        if (pendingHardDelete is UndoableDelete.HardDelete &&
            incomingIsHardDelete && incomingId == pendingHardDelete.id
        ) return true
        // pending → committing: the delete is now OWED. Only a confirmed successful outcome may
        // clear the slot. An uncertain result must remain drainable and cannot be overwritten.
        if (pendingHardDelete is UndoableDelete.HardDelete) {
            undoState.set(UndoableDelete.CommittingHardDelete(pendingHardDelete.id))
        }
        return try {
            val result = withContext(Dispatchers.IO) {
                fileOps.deleteMediaById(pendingHardDelete.id)
            }
            when (result) {
                is DeleteMediaResult.Success,
                is DeleteMediaResult.SkippedNotFound -> {
                    undoState.set(null)
                    true
                }
                is DeleteMediaResult.Failed -> {
                    // The attempt may have partially completed. Keep the debt in the
                    // non-undoable state so a later safe drain can retry it.
                    Log.e(TAG, "Auto-commit of old pending delete failed: code=${result.error.code}")
                    onError(syncErrorMessage(result.error, context))
                    false
                }
            }
        } catch (e: CancellationException) {
            // The attempt may already have deleted one or more stages. Keep it explicitly
            // non-undoable and drainable, then propagate CE verbatim.
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to auto-commit old pending delete: ${safeErrorDetail(e)}")
            onError(syncErrorMessage(e, context))
            false
        }
    }

    /**
     * Undo the most recent delete operation.
     *
     * @param expectedId If provided, only undo if the current pending operation matches this id.
     *   Prevents a stale snackbar from cancelling a newer operation.
     * - [UndoableDelete.TrashToggle]: restore the previous trashed state (with volume ownership check).
     * - [UndoableDelete.HardDelete]: cancel the pending deletion (file was never actually deleted).
     *
     * Idempotent: calling undo when there is no pending state returns [UndoResult.NothingToUndo].
     * Double-undo is safe: the second call finds no state and returns [UndoResult.NothingToUndo].
     */
    suspend fun undo(expectedId: Long? = null): UndoResult = mutex.withLock {
        val state = undoState.get()
        if (state == null) return UndoResult.NothingToUndo
        // expectedId protection: only act if the current operation matches
        if (expectedId != null && state.id != expectedId) {
            return UndoResult.NothingToUndo
        }
        if (state is UndoableDelete.CommittingHardDelete) {
            // A commit may already have removed the ciphertext or thumbnail. It is not safe to
            // claim undo success or clear the debt; only a confirmed drain may clear it.
            return UndoResult.NothingToUndo
        }
        undoState.set(null)
        return when (state) {
            is UndoableDelete.TrashToggle -> undoTrashToggle(state)
            is UndoableDelete.HardDelete -> {
                // Deferred delete was never executed — just cancel it (idempotent)
                UndoResult.Success
            }
            is UndoableDelete.CommittingHardDelete -> {
                // Defensive: unreachable through the public API (commits run under [mutex],
                // and undo re-acquires it before reading the slot). If it were ever
                // observed, undoing a delete that is already executing would lie about
                // the file's fate — report NothingToUndo instead.
                UndoResult.NothingToUndo
            }
        }
    }

    /**
     * Commit the deferred hard delete (called when Snackbar expires without undo).
     * Executes the actual deletion of cipher + thumb + DB.
     *
     * @param expectedId If provided, only commit if the current pending operation matches this id.
     *   Prevents a stale snackbar from committing a newer operation.
     */
    suspend fun commitDeferredDelete(expectedId: Long? = null): UndoResult = mutex.withLock {
        val state = undoState.get()
        if (state == null) return UndoResult.NothingToUndo
        // expectedId protection: only act if the current operation matches
        if (expectedId != null && state.id != expectedId) {
            return UndoResult.NothingToUndo
        }
        if (state !is UndoableDelete.HardDelete && state !is UndoableDelete.CommittingHardDelete) {
            return UndoResult.NothingToUndo
        }
        // A CommittingHardDelete is an already-started, uncertain attempt. Retrying it is the
        // only safe operation; it must never become undoable again.
        if (state is UndoableDelete.HardDelete) {
            undoState.set(UndoableDelete.CommittingHardDelete(state.id))
        }
        return try {
            val result = withContext(Dispatchers.IO) {
                fileOps.deleteMediaById(state.id)
            }
            when (result) {
                is DeleteMediaResult.Success, is DeleteMediaResult.SkippedNotFound -> {
                    undoState.set(null)
                    UndoResult.Success
                }
                is DeleteMediaResult.Failed -> UndoResult.Failed(result.error)
            }
        } catch (e: CancellationException) {
            // Keep CommittingHardDelete: the operation may have partially completed.
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to commit deferred delete: ${safeErrorDetail(e)}")
            UndoResult.Failed(SyncException(
                SyncErrorCode.STORAGE_ERROR,
                SyncOperation.DELETE,
                cause = e
            ))
        }
    }

    /**
     * Commit any pending hard delete using the internal [safetyScope], with a [NonCancellable]
     * body so the commit runs to completion even if the safety scope observes cancellation.
     *
     * This is the page-leave safety path: it MUST be independent of the caller's scope
     * (e.g. a ViewModel's `viewModelScope`), because by the time `ViewModel.onCleared()` runs
     * that scope is already cancelled and a `launch` into it is a no-op — the pending hard
     * delete would never execute and be silently lost.
     *
     * Note on the [NonCancellable] placement: the launch itself runs as a normal child of the
     * safety scope's [SupervisorJob] (preserving structured concurrency); only the commit body
     * is wrapped in [withContext]`(NonCancellable)` so the SupervisorJob remains the parent.
     *
     * @param expectedId If provided, only commit if the current pending operation matches this id.
     *   Prevents a stale dispose (from an earlier delete) from committing a newer operation.
     *
     * NOT [kotlinx.coroutines.GlobalScope]: [safetyScope] is an owned, named scope tied to this Singleton.
     */
    fun commitPendingSafely(expectedId: Long? = null) {
        safetyScope.launch {
            withContext(NonCancellable) {
                val result = commitDeferredDelete(expectedId)
                if (result is UndoResult.Failed) {
                    // The commit failed. The screen is gone (this is the page-leave path) so we
                    // cannot surface to a UI flow — but we MUST NOT silently drop it. Log a
                    // structured code only for developer diagnostics (no throwable, no ids).
                    Log.e(TAG, "commitPendingSafely failed: code=${result.error.code}")
                }
            }
        }
    }

    /** Clear any pending undo state without committing (e.g., on navigation away where undo is preferred). */
    fun clearUndoState() {
        while (true) {
            val state = undoState.get() ?: return
            if (state is UndoableDelete.CommittingHardDelete) return
            if (undoState.compareAndSet(state, null)) return
        }
    }

    /** Whether there is a pending undo state. */
    fun hasUndoState(): Boolean = undoState.get() != null

    /** Get the id of the current pending operation, or null if none. */
    fun getPendingId(): Long? = undoState.get()?.let { it.id }

    /** Get the id of current deferred hard-delete debt, ignoring a pending trash toggle. */
    fun getPendingHardDeleteId(): Long? = when (val state = undoState.get()) {
        is UndoableDelete.HardDelete,
        is UndoableDelete.CommittingHardDelete -> state.id
        is UndoableDelete.TrashToggle,
        null -> null
    }

    /** Whether [id] is an uncertain hard-delete debt awaiting a safe drain. */
    fun hasCommittingDelete(id: Long): Boolean {
        val state = undoState.get()
        return state is UndoableDelete.CommittingHardDelete && state.id == id
    }

    /**
     * Test-only: directly set the undo state, bypassing the normal perform methods.
     * Allows tests to exercise undo/commit without mocking the full perform flow.
     */
    @Suppress("unused")
    fun simulateSetUndoState(state: UndoableDelete?) {
        undoState.set(state)
    }

    /**
     * Undo a trash-toggle operation.
     */
    private suspend fun undoTrashToggle(state: UndoableDelete.TrashToggle): UndoResult {
        return try {
            val video = videoRepository.getById(state.id)
            if (video == null) {
                // Entity gone (e.g., already deleted by another operation) — nothing to restore.
                // This is a benign case: the user's intent (undo the toggle) is moot because
                // the entity no longer exists. Report success to avoid confusing the user.
                Log.i(TAG, "undo trash toggle: entity gone; nothing to restore")
                return UndoResult.Success
            }
            playbackRepository.setTrashed(state.id, state.previousTrashed)
            UndoResult.Success
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to undo trash toggle: ${safeErrorDetail(e)}")
            UndoResult.Failed(SyncException(
                SyncErrorCode.DATABASE_ERROR,
                SyncOperation.DELETE,
                cause = e
            ))
        }
    }
}
