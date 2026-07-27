package cn.vectory.syncplayer.browser

import android.content.Context
import android.util.Log
import cn.vectory.syncplayer.R
import cn.vectory.syncplayer.data.model.SyncRelationWithSource
import cn.vectory.syncplayer.data.repository.PlaybackRepository
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.storage.volume.EncryptedMediaStore
import cn.vectory.syncplayer.sync.SyncErrorCode
import cn.vectory.syncplayer.sync.SyncException
import cn.vectory.syncplayer.sync.SyncOperation
import cn.vectory.syncplayer.sync.syncErrorMessage
import dagger.Lazy
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
     * Hard delete (cipher + thumb + DB).
     * The deletion is NOT executed immediately — it is deferred until [UndoDeleteUseCase.commitDeferredDelete]
     * is called (when the Snackbar expires without undo). Undo simply cancels the pending deletion.
     */
    data class HardDelete(override val id: Long) : UndoableDelete()
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
 * - Sequential hard deletes auto-commit the old pending delete before setting the new one,
 *   so no pending delete is ever silently lost.
 *
 * Only the most recent delete operation is retained (latest-first semantics per task requirement 6).
 * Volume ownership fail-closed: undo of [TrashToggle] checks that the entity belongs to the
 * active volume before restoring. If the volume no longer matches, undo returns [UndoResult.Failed]
 * rather than pretending success.
 */
@Singleton
class UndoDeleteUseCase @Inject constructor(
    private val fileOps: FileOperationsUseCase,
    private val playbackRepository: PlaybackRepository,
    private val videoRepository: VideoRepository,
    private val encryptedMediaStoreLazy: Lazy<EncryptedMediaStore>,
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "UndoDeleteUseCase"
    }

    private val mediaStore: EncryptedMediaStore get() = encryptedMediaStoreLazy.get()
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
    ) {
        scope.launch {
            try {
                mutex.withLock {
                    // Capture previous state before toggle (under the lock so a concurrent
                    // toggle cannot interleave between read and apply).
                    val record = playbackRepository.getByVideoId(videoItemId)
                    val previousTrashed = record?.isTrashed ?: false

                    when (val result = fileOps.toggleTrashedChecked(videoItemId, basePaths)) {
                        is ToggleTrashResult.Success -> {
                            undoState.set(UndoableDelete.TrashToggle(videoItemId, previousTrashed))
                            onSuccess()
                        }
                        is ToggleTrashResult.NotAllowed ->
                            errorFlow.value = context.getString(R.string.error_only_base_dir_trash)
                        is ToggleTrashResult.Failed ->
                            errorFlow.value = syncErrorMessage(result.error, context)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to perform trash toggle with undo id=$videoItemId", e)
                errorFlow.value = syncErrorMessage(e, context)
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

                    playbackRepository.toggleTrashed(videoItemId)
                    undoState.set(UndoableDelete.TrashToggle(videoItemId, previousTrashed))
                    onSuccess()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to perform trash toggle (no guard) id=$videoItemId", e)
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
    ) {
        scope.launch {
            mutex.withLock {
                val existing = undoState.get()
                if (existing is UndoableDelete.HardDelete && existing.id != videoItemId) {
                    // Auto-commit the old pending delete before recording the new one.
                    undoState.set(null)
                    try {
                        val result = withContext(Dispatchers.IO) {
                            fileOps.deleteMediaById(existing.id)
                        }
                        when (result) {
                            is DeleteMediaResult.Success,
                            is DeleteMediaResult.SkippedNotFound -> { /* committed cleanly */ }
                            is DeleteMediaResult.Failed -> {
                                // Old pending delete failed (e.g. IO/permission). Do NOT silently
                                // drop it — surface to the error flow so the user sees the failure.
                                Log.e(TAG, "Auto-commit of old pending delete failed id=${existing.id}: ${result.error.code}", result.error)
                                errorFlow.value = syncErrorMessage(result.error, context)
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to auto-commit old pending delete id=${existing.id}", e)
                        errorFlow.value = syncErrorMessage(e, context)
                    }
                }
                undoState.set(UndoableDelete.HardDelete(videoItemId))
            }
            onSuccess()
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
        undoState.set(null)
        return when (state) {
            is UndoableDelete.TrashToggle -> undoTrashToggle(state)
            is UndoableDelete.HardDelete -> {
                // Deferred delete was never executed — just cancel it (idempotent)
                UndoResult.Success
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
        if (state !is UndoableDelete.HardDelete) {
            return UndoResult.NothingToUndo
        }
        // Clear the state before executing (so a concurrent undo won't double-clear)
        undoState.set(null)
        return try {
            val result = withContext(Dispatchers.IO) {
                fileOps.deleteMediaById(state.id)
            }
            when (result) {
                is DeleteMediaResult.Success, is DeleteMediaResult.SkippedNotFound -> UndoResult.Success
                is DeleteMediaResult.Failed -> UndoResult.Failed(result.error)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to commit deferred delete id=${state.id}", e)
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
                    // cannot surface to a UI flow — but we MUST NOT silently drop it. Log it for
                    // developer diagnostics (no user-facing path/id leak; logs are dev-only).
                    Log.e(TAG, "commitPendingSafely failed for expectedId=$expectedId: ${result.error.code}", result.error)
                }
            }
        }
    }

    /** Clear any pending undo state without committing (e.g., on navigation away where undo is preferred). */
    fun clearUndoState() {
        undoState.set(null)
    }

    /** Whether there is a pending undo state. */
    fun hasUndoState(): Boolean = undoState.get() != null

    /** Get the id of the current pending operation, or null if none. */
    fun getPendingId(): Long? = undoState.get()?.let { it.id }

    /**
     * Test-only: directly set the undo state, bypassing the normal perform methods.
     * Allows tests to exercise undo/commit without mocking the full perform flow.
     */
    @Suppress("unused")
    fun simulateSetUndoState(state: UndoableDelete?) {
        undoState.set(state)
    }

    /**
     * Undo a trash-toggle operation with volume ownership check.
     * If the entity's volume no longer matches the active volume, undo fails safely
     * (fail-closed: don't pretend success when we can't verify ownership).
     */
    private suspend fun undoTrashToggle(state: UndoableDelete.TrashToggle): UndoResult {
        return try {
            // Volume ownership check: only restore if the entity belongs to the active volume
            val video = videoRepository.getById(state.id)
            if (video == null) {
                // Entity gone (e.g., already deleted by another operation) — nothing to restore.
                // This is a benign case: the user's intent (undo the toggle) is moot because
                // the entity no longer exists. Report success to avoid confusing the user.
                Log.i(TAG, "undo trash toggle: id=${state.id} entity gone; nothing to restore")
                return UndoResult.Success
            }
            val currentVolumeId = mediaStore.volumeId
            if (video.volumeId != currentVolumeId) {
                Log.w(TAG, "undo trash toggle: id=${state.id} volume='${video.volumeId}' " +
                    "not confirmed on active='$currentVolumeId'; skip restore (fail-closed)")
                return UndoResult.Failed(SyncException(
                    SyncErrorCode.STORAGE_ERROR,
                    SyncOperation.DELETE,
                    path = video.relativePath
                ))
            }
            playbackRepository.setTrashed(state.id, state.previousTrashed)
            UndoResult.Success
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to undo trash toggle id=${state.id}", e)
            UndoResult.Failed(SyncException(
                SyncErrorCode.DATABASE_ERROR,
                SyncOperation.DELETE,
                cause = e
            ))
        }
    }
}
