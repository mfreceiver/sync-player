package cn.vectory.syncplayer.browser

import android.content.Context
import android.content.Intent
import android.util.Log
import cn.vectory.syncplayer.R
import cn.vectory.syncplayer.data.model.FolderStats
import cn.vectory.syncplayer.data.model.SyncRelationWithSource
import cn.vectory.syncplayer.data.repository.PlaybackRepository
import cn.vectory.syncplayer.data.repository.SyncRelationRepository
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.provider.MediaUris
import cn.vectory.syncplayer.storage.volume.EncryptedMediaStore
import cn.vectory.syncplayer.sync.SyncErrorCode
import cn.vectory.syncplayer.sync.SyncException
import cn.vectory.syncplayer.sync.SyncOperation
import cn.vectory.syncplayer.sync.SyncEngine
import cn.vectory.syncplayer.sync.syncErrorMessage
import cn.vectory.syncplayer.ui.util.getMimeType
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of deleting one media id (ciphertext + DB). */
sealed class DeleteMediaResult {
    data class Success(val id: Long) : DeleteMediaResult()
    data class SkippedNotFound(val id: Long) : DeleteMediaResult()
    data class Failed(val id: Long, val error: SyncException) : DeleteMediaResult()
}

/**
 * Outcome of a base-path-guarded trash-toggle.
 *
 * - [Success]: toggle applied; caller may record undo state and surface success.
 * - [NotAllowed]: item is missing or not under any provided base path; caller surfaces the
 *   "only base dir" message.
 * - [Failed]: a real error occurred (DB / IO); caller surfaces the resolved message.
 *
 * CancellationException is NEVER represented here — it always propagates verbatim so structured
 * concurrency is preserved (caller scope cancellation actually cancels in-flight toggles).
 */
internal sealed class ToggleTrashResult {
    data object Success : ToggleTrashResult()
    data object NotAllowed : ToggleTrashResult()
    data class Failed(val error: SyncException) : ToggleTrashResult()
}

data class FolderDeleteSummary(
    val success: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    val failures: List<Pair<Long, SyncException>> = emptyList(),
)

@Singleton
class FileOperationsUseCase @Inject constructor(
    private val videoRepository: VideoRepository,
    private val playbackRepository: PlaybackRepository,
    private val syncRelationRepository: SyncRelationRepository,
    private val encryptedMediaStoreLazy: Lazy<EncryptedMediaStore>,
    private val syncEngine: SyncEngine,
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "FileOperations"
    }

    private val mediaStore: EncryptedMediaStore get() = encryptedMediaStoreLazy.get()

    fun isFileInBaseDir(relativePath: String, basePaths: List<SyncRelationWithSource>): Boolean {
        val basePathsList = basePaths.map { it.relation.localBasePath }
        return basePathsList.any { basePath ->
            relativePath.startsWith("$basePath/") || relativePath == basePath
        }
    }

    fun updateStar(
        videoItemId: Long, star: Int,
        scope: CoroutineScope,
        errorFlow: MutableStateFlow<String?>,
        onSuccess: () -> Unit = {}
    ) {
        scope.launch {
            try {
                videoRepository.updateStar(videoItemId, star)
                onSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update star", e)
                errorFlow.value = syncErrorMessage(e, context)
            }
        }
    }

    fun updateStarByPath(
        relativePath: String, star: Int,
        scope: CoroutineScope,
        errorFlow: MutableStateFlow<String?>,
        onSuccess: () -> Unit = {}
    ) {
        scope.launch {
            try {
                videoRepository.updateStarByPath(relativePath, star)
                onSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update star by path", e)
                errorFlow.value = syncErrorMessage(e, context)
            }
        }
    }

    fun toggleTrashed(
        videoItemId: Long,
        basePaths: List<SyncRelationWithSource>,
        scope: CoroutineScope,
        errorFlow: MutableStateFlow<String?>,
        onSuccess: () -> Unit = {}
    ) {
        scope.launch {
            when (val result = toggleTrashedChecked(videoItemId, basePaths)) {
                is ToggleTrashResult.Success -> onSuccess()
                is ToggleTrashResult.NotAllowed ->
                    errorFlow.value = context.getString(R.string.error_only_base_dir_trash)
                is ToggleTrashResult.Failed ->
                    errorFlow.value = syncErrorMessage(result.error, context)
            }
        }
    }

    /**
     * Suspend, base-path-guarded trash toggle.
     *
     * Returns a typed [ToggleTrashResult]. [CancellationException] is rethrown (never swallowed
     * into Failed) so the caller's structured-concurrency cancellation actually cancels the
     * in-flight toggle. [Mutex] serialization across concurrent toggles of the same id is the
     * caller's responsibility (see [UndoDeleteUseCase]).
     */
    /**
     * Suspend, base-path-guarded trash toggle.
     *
     * Returns a typed [ToggleTrashResult]. [CancellationException] is rethrown (never swallowed
     * into Failed) so the caller's structured-concurrency cancellation actually cancels the
     * in-flight toggle. [Mutex] serialization across concurrent toggles of the same id is the
     * caller's responsibility (see [UndoDeleteUseCase]).
     */
    internal suspend fun toggleTrashedChecked(
        videoItemId: Long,
        basePaths: List<SyncRelationWithSource>
    ): ToggleTrashResult {
        return try {
            val video = videoRepository.getById(videoItemId)
            if (video == null || !isFileInBaseDir(video.relativePath, basePaths)) {
                return ToggleTrashResult.NotAllowed
            }
            playbackRepository.toggleTrashed(videoItemId)
            ToggleTrashResult.Success
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle trashed id=$videoItemId", e)
            ToggleTrashResult.Failed(
                SyncException(SyncErrorCode.UNKNOWN, SyncOperation.DELETE, cause = e)
            )
        }
    }

    fun deleteFile(
        videoItemId: Long,
        scope: CoroutineScope,
        errorFlow: MutableStateFlow<String?>,
        onSuccess: () -> Unit = {}
    ) {
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    syncEngine.storageOperationMutex.withLock { deleteMediaByIdLocked(videoItemId) }
                }
                when (result) {
                    is DeleteMediaResult.Success, is DeleteMediaResult.SkippedNotFound -> onSuccess()
                    is DeleteMediaResult.Failed -> {
                        errorFlow.value = syncErrorMessage(result.error, context)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete file id=$videoItemId", e)
                errorFlow.value = syncErrorMessage(e, context)
            }
        }
    }

    suspend fun buildShareIntent(videoItemId: Long): Intent? {
        val video = videoRepository.getById(videoItemId) ?: return null
        return try {
            val uri = MediaUris.mediaUri(videoItemId)
            val mimeType = getMimeType(video.fileName)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            Intent.createChooser(shareIntent, context.getString(R.string.context_share_file))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build share intent", e)
            null
        }
    }

    fun markFolderRead(
        folderPath: String, read: Boolean,
        scope: CoroutineScope,
        errorFlow: MutableStateFlow<String?>,
        onSuccess: () -> Unit = {}
    ) {
        scope.launch {
            try {
                playbackRepository.markFolderCompleted(folderPath, read)
                onSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to mark folder read", e)
                errorFlow.value = syncErrorMessage(e, context)
            }
        }
    }

    fun deleteFolder(
        path: String,
        basePaths: List<SyncRelationWithSource>,
        scope: CoroutineScope,
        errorFlow: MutableStateFlow<String?>,
        onSuccess: () -> Unit = {}
    ) {
        scope.launch {
            try {
                val isBase = syncRelationRepository.getByLocalBasePath(path) != null
                if (isBase) {
                    errorFlow.value = context.getString(R.string.error_cannot_delete_base_dir)
                    return@launch
                }
                val allBasePaths = syncRelationRepository.getAllBasePaths()
                val hasDescendantBase = allBasePaths.any { basePath ->
                    basePath.startsWith("$path/")
                }
                if (hasDescendantBase) {
                    errorFlow.value = context.getString(R.string.error_base_dir_inside)
                    return@launch
                }
                if (isFileInBaseDir(path, basePaths)) {
                    errorFlow.value = context.getString(R.string.error_cannot_delete_sync_dir)
                    return@launch
                }
                val summary = withContext(Dispatchers.IO) {
                    syncEngine.storageOperationMutex.withLock { deleteFolderContentsLocked(path) }
                }
                if (summary.failed > 0) {
                    // M3: partial failure — do not claim full success (skip onSuccess so UI doesn't
                    // refresh as if everything was gone).
                    errorFlow.value = context.getString(
                        R.string.error_folder_delete_partial,
                        summary.success, summary.skipped, summary.failed
                    )
                } else {
                    onSuccess()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete folder", e)
                errorFlow.value = syncErrorMessage(e, context)
            }
        }
    }

    /**
     * Delete all media under [path]. Items are already path-scoped by [VideoRepository.getByPathPrefix]
     * (or [VideoRepository.getAll] for root). Each item's volume ownership is checked inside
     * [deleteMediaById] — mismatched volumes use index-only deletion instead of ciphertext access.
     */
    private suspend fun deleteFolderContentsLocked(path: String): FolderDeleteSummary {
        val items = if (path.isEmpty()) {
            videoRepository.getAll()
        } else {
            videoRepository.getByPathPrefix(path)
        }
        if (items.isEmpty()) return FolderDeleteSummary()

        var success = 0
        var skipped = 0
        val failures = mutableListOf<Pair<Long, SyncException>>()
        for (item in items) {
            when (val r = deleteMediaByIdLocked(item.id)) {
                is DeleteMediaResult.Success -> success++
                is DeleteMediaResult.SkippedNotFound -> {
                    // Already cleaned ciphertext; DB removed as part of skip path.
                    skipped++
                    success++
                }
                is DeleteMediaResult.Failed -> failures.add(item.id to r.error)
            }
        }
        return FolderDeleteSummary(
            success = success,
            skipped = skipped,
            failed = failures.size,
            failures = failures,
        )
    }

    /**
     * Delete ciphertext + thumb + DB for [id].
     * - Missing ciphertext (NotFound / !exists): treat as success and still remove DB.
     * - Real IO/permission error: keep DB row, return Failed.
     * - Volume ownership fail-closed: ciphertext/thumb on the active volume are touched ONLY
     *   when [VideoItemEntity.volumeId] is *confirmed* equal to the current active volume id.
     *   Any other value — a different non-empty volume id, OR an empty/unknown volumeId
     *   (pre-tracking record) — routes through [deleteOtherVolumeIndex] (index-only delete,
     *   ciphertext never touched on any volume). This matches SyncEngine's strict "empty also
     *   counts as mismatch" semantics and avoids creating un-reclaimable orphans when the real
     *   file lives on a non-active volume. See decision table in the class/task notes.
     */
    suspend fun deleteMediaById(id: Long): DeleteMediaResult {
        return syncEngine.storageOperationMutex.withLock { deleteMediaByIdLocked(id) }
    }

    /** Called only while [SyncEngine.storageOperationMutex] is held by a parent workflow. */
    internal suspend fun deleteMediaByIdLocked(id: Long): DeleteMediaResult {
        val video = videoRepository.getById(id)
            ?: return DeleteMediaResult.SkippedNotFound(id)

        // N3: volume ownership guard — fail-closed. Only delete ciphertext/thumb on the active
        // volume when ownership is *proven* (volumeId == active). An empty/unknown volumeId is
        // treated as "ownership unknown", NOT as "belongs to active": deleting-by-guess on the
        // active volume when the real file is elsewhere leaves a permanent orphan the user can
        // never clean via the app. Index-only delete is the honest, recoverable choice.
        val currentVolumeId = mediaStore.volumeId
        if (video.volumeId != currentVolumeId) {
            Log.w(TAG, "deleteMediaById: id=$id volume='${video.volumeId}' not confirmed on " +
                "active='$currentVolumeId' (different or empty/unknown); index-only delete, " +
                "ciphertext untouched")
            return deleteOtherVolumeIndexLocked(id)
        }

        val cipherMissing = try {
            !mediaStore.exists(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // exists() itself failed — treat as IO error, do not wipe DB.
            Log.w(TAG, "exists() failed for id=$id", e)
            return DeleteMediaResult.Failed(
                id, SyncException(SyncErrorCode.STORAGE_ERROR, SyncOperation.DELETE, cause = e)
            )
        }

        if (!cipherMissing) {
            try {
                // FileVolume/SafVolume: false = missing OR delete failed (cannot tell alone).
                val deleted = mediaStore.delete(id)
                if (!deleted) {
                    val stillThere = try {
                        mediaStore.exists(id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "exists() after delete failed for id=$id", e)
                        return DeleteMediaResult.Failed(
                            id, SyncException(SyncErrorCode.STORAGE_ERROR, SyncOperation.DELETE, cause = e)
                        )
                    }
                    if (stillThere) {
                        Log.e(TAG, "ciphertext still present after delete returned false for id=$id")
                        return DeleteMediaResult.Failed(
                            id, SyncException(SyncErrorCode.STORAGE_ERROR, SyncOperation.DELETE)
                        )
                    }
                    // Race / already gone → idempotent ok
                    Log.i(TAG, "ciphertext already gone for id=$id")
                }
            } catch (e: FileNotFoundException) {
                Log.i(TAG, "ciphertext already gone for id=$id")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isNotFound(e)) {
                    Log.i(TAG, "ciphertext not found for id=$id")
                } else {
                    Log.e(TAG, "Failed to delete ciphertext for id=$id", e)
                    return DeleteMediaResult.Failed(
                        id, SyncException(SyncErrorCode.STORAGE_ERROR, SyncOperation.DELETE, cause = e)
                    )
                }
            }
        }

        // M2: Boolean false from deleteThumb is ambiguous (missing vs fail). Re-check existence.
        try {
            val deleted = mediaStore.deleteThumb(id)
            if (!deleted) {
                val stillThere = try {
                    mediaStore.thumbExists(id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "thumbExists() after delete failed for id=$id", e)
                    return DeleteMediaResult.Failed(
                        id, SyncException(SyncErrorCode.THUMBNAIL_ERROR, SyncOperation.DELETE, cause = e)
                    )
                }
                if (stillThere) {
                    Log.e(TAG, "thumb still present after delete returned false for id=$id")
                    return DeleteMediaResult.Failed(
                        id, SyncException(SyncErrorCode.THUMBNAIL_ERROR, SyncOperation.DELETE)
                    )
                }
                // No thumb file → idempotent success (common: never generated)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isNotFound(e)) {
                // idempotent ok
            } else {
                Log.e(TAG, "Failed to delete thumb for id=$id", e)
                return DeleteMediaResult.Failed(
                    id, SyncException(SyncErrorCode.THUMBNAIL_ERROR, SyncOperation.DELETE, cause = e)
                )
            }
        }

        return try {
            videoRepository.deleteMediaRecord(id)
            if (cipherMissing) DeleteMediaResult.SkippedNotFound(id)
            else DeleteMediaResult.Success(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete DB rows for id=$id", e)
            DeleteMediaResult.Failed(
                id, SyncException(SyncErrorCode.DATABASE_ERROR, SyncOperation.DELETE, cause = e)
            )
        }
    }

    /**
     * B1: drop other-volume index rows only. Never touch active-volume ciphertext/thumbs
     * (ids on inactive volumes must not drive mediaStore.delete on the active store).
     */
    suspend fun deleteOtherVolumeIndex(videoItemId: Long): DeleteMediaResult {
        return syncEngine.storageOperationMutex.withLock { deleteOtherVolumeIndexLocked(videoItemId) }
    }

    /** Called only while [SyncEngine.storageOperationMutex] is held by a parent workflow. */
    internal suspend fun deleteOtherVolumeIndexLocked(videoItemId: Long): DeleteMediaResult {
        val video = videoRepository.getById(videoItemId)
            ?: return DeleteMediaResult.SkippedNotFound(videoItemId)
        return try {
            videoRepository.deleteMediaRecord(video.id)
            DeleteMediaResult.Success(video.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "deleteOtherVolumeIndex failed for id=$videoItemId", e)
            DeleteMediaResult.Failed(
                videoItemId, SyncException(SyncErrorCode.DATABASE_ERROR, SyncOperation.DELETE, cause = e)
            )
        }
    }

    private fun isNotFound(e: Exception): Boolean {
        // Rely on exception TYPE only — never parse message text (fragile across locales/libs).
        return e is FileNotFoundException
    }

    suspend fun getRecursiveFolderStats(folderPath: String): FolderStats =
        videoRepository.getRecursiveFolderStats(folderPath)
}
