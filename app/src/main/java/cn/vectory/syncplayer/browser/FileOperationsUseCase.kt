package cn.vectory.syncplayer.browser

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import cn.vectory.syncplayer.R
import cn.vectory.syncplayer.crypto.CryptoScheme
import cn.vectory.syncplayer.crypto.RangeDecryptor
import cn.vectory.syncplayer.data.model.FolderStats
import cn.vectory.syncplayer.data.model.SyncRelationWithSource
import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import cn.vectory.syncplayer.data.repository.PlaybackRepository
import cn.vectory.syncplayer.data.repository.SyncRelationRepository
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.storage.volume.EncryptedMediaStore
import cn.vectory.syncplayer.sync.SyncErrorCode
import cn.vectory.syncplayer.sync.SyncException
import cn.vectory.syncplayer.sync.SyncOperation
import cn.vectory.syncplayer.sync.SyncEngine
import cn.vectory.syncplayer.sync.syncErrorMessage
import cn.vectory.syncplayer.ui.util.ShareTempFiles
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
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
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

    /**
     * Build an [Intent.ACTION_SEND] chooser that shares [videoItemId] as a real, decrypted file
     * exposed via [FileProvider].
     *
     * Why a temp file (not the internal `content://...media/<id>` URI): many third-party apps
     * (WeChat, QQ, some file managers) ignore `ContentResolver.query()` for DISPLAY_NAME and just
     * take the URI's last path segment. The internal media URI ends in a bare numeric id, so the
     * recipient sees a name like `1734567890123` with no extension. Decrypting into a temp file
     * named with the entity's original [VideoItemEntity.fileName] makes the name + extension
     * visible to every recipient, regardless of how they read the URI.
     *
     * Flow:
     *  1. Refuse if the entity is missing, on a non-active volume, or has a malformed iv
     *     (fail-closed per the storage-security red lines — never decrypt ciphertext whose
     *     ownership is unconfirmed or whose format is unexpected).
     *  2. Sweep stale share temp files (>1h old) from the previous sessions.
     *  3. Decrypt the whole ciphertext into `cacheDir/share/<sanitized-name>` via [decryptWholeTo].
     *  4. Hand the [FileProvider] URI to a SEND intent with read permission.
     *
     * [CancellationException] always propagates (red line #3). Any other failure is logged
     * (without sensitive data — no fileName / path / underlying message) and surfaces as `null`,
     * which the UI maps to a "cannot share" message.
     */
    suspend fun buildShareIntent(videoItemId: Long): Intent? = withContext(Dispatchers.IO) {
        val video = videoRepository.getById(videoItemId) ?: return@withContext null

        // Fail-closed volume ownership: ciphertext lives only on the active volume; a record with
        // an empty/unknown/mismatched volumeId must not drive decryption on the active store.
        val currentVolumeId = try {
            mediaStore.volumeId
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "buildShareIntent: volumeId lookup failed for id=$videoItemId", e)
            return@withContext null
        }
        if (video.volumeId != currentVolumeId) {
            Log.w(TAG, "buildShareIntent: id=$videoItemId volume mismatch, refusing to share")
            return@withContext null
        }
        if (video.iv.size != CryptoScheme.IV_LEN) {
            Log.w(TAG, "buildShareIntent: id=$videoItemId iv length ${video.iv.size} != ${CryptoScheme.IV_LEN}")
            return@withContext null
        }

        try {
            ShareTempFiles.cleanupOldShareTempFiles(context)
            val safeName = ShareTempFiles.sanitizeFileName(video.fileName)
            val outFile = File(ShareTempFiles.shareDir(context), safeName)
            decryptWholeTo(video, outFile)

            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, outFile)
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
            Log.e(TAG, "buildShareIntent failed for id=$videoItemId", e)
            null
        }
    }

    /**
     * Decrypt the **entire** plaintext of [video] into [outFile] using the AEAD v2 chunk format.
     *
     * Reuses [EncryptedMediaStore.openCiphertextRead] + [RangeDecryptor] — the same primitives
     * the playback path ([DecryptingFdCallback] / [MediaContentProvider]) uses — but walks every
     * chunk sequentially instead of serving random ranges.
     *
     * Failure contract (security red lines #4 / #5):
     *  - [RangeDecryptor] never returns unauthenticated bytes: any header/chunk tamper, truncation,
     *    or short read throws before this method returns. We never write a half-decrypted file.
     *  - If anything throws after [outFile] is opened, [outFile] is deleted in the cleanup
     *    `catch` so a partial result can never be handed to FileProvider.
     *  - [CancellationException] propagates verbatim (the cleanup `runCatching { outFile.delete() }`
     *    runs and the exception is rethrown, not swallowed).
     *  - After a successful write we still assert the byte count matches [video.fileSize]
     *    (paranoid guard; RangeDecryptor already enforces this cryptographically).
     *
     * Runs on [Dispatchers.IO] since [EncryptedMediaStore.openCiphertextRead] and
     * [RangeDecryptor.readRange] perform blocking SAF/FD IO.
     */
    private suspend fun decryptWholeTo(video: VideoItemEntity, outFile: File) {
        require(video.iv.size == CryptoScheme.IV_LEN) {
            "iv length ${video.iv.size} != ${CryptoScheme.IV_LEN}"
        }
        val store = mediaStore
        withContext(Dispatchers.IO) {
            val handle = store.openCiphertextRead(video.id)
            try {
                val key = store.contentKey()
                val decryptor = RangeDecryptor(key, video.iv, video.fileSize)
                var written = 0L
                try {
                    FileOutputStream(outFile).use { out ->
                        var offset = 0L
                        val size = video.fileSize
                        while (offset < size) {
                            // CHUNK_SIZE fits in Int (64 KiB); (size - offset) is at most one chunk.
                            val len = minOf(CryptoScheme.CHUNK_SIZE, (size - offset).toInt())
                            val plain = decryptor.readRange(handle, offset, len)
                            out.write(plain)
                            offset += plain.size
                            written += plain.size
                        }
                    }
                    // Paranoid length check; RangeDecryptor already enforces this via GCM/truncation.
                    check(written == video.fileSize) {
                        "decryption wrote $written bytes, expected ${video.fileSize}"
                    }
                } catch (e: Throwable) {
                    // Never leave a partial file behind: delete unconditionally, then rethrow.
                    // CancellationException still propagates (runCatching swallows only the delete's
                    // own failure, not `e`).
                    runCatching { outFile.delete() }
                    throw e
                }
            } finally {
                try { handle.close() } catch (_: Exception) {}
            }
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
            Log.w(TAG, "deleteMediaById: id=$id volume not confirmed " +
                "(stored volumeId differs from or is unknown for active volume); index-only delete, " +
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
