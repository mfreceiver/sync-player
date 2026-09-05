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
import cn.vectory.syncplayer.storage.volume.SeekableReadHandle
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
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of deleting one media id (ciphertext + DB). */
sealed class DeleteMediaResult {
    data class Success(val id: Long) : DeleteMediaResult()
    data class SkippedNotFound(val id: Long) : DeleteMediaResult()
    data class Failed(val id: Long, val error: SyncException) : DeleteMediaResult()
}

/**
 * Structured, leak-free diagnostic detail for log statements (security red line #6):
 * [SyncException] → its [SyncErrorCode] name; anything else → the exception class simple
 * name. NEVER the throwable itself (its message/stack chain can carry paths, filenames,
 * ids or backend messages), NEVER ids/paths interpolated from the caller.
 */
internal fun safeErrorDetail(e: Throwable): String =
    (e as? SyncException)?.code?.name ?: e.javaClass.simpleName

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
    ) = updateStar(videoItemId, star, scope, { errorFlow.value = it }, onSuccess)

    fun updateStar(
        videoItemId: Long, star: Int,
        scope: CoroutineScope,
        onError: (String) -> Unit,
        onSuccess: () -> Unit = {}
    ) {
        scope.launch {
            try {
                videoRepository.updateStar(videoItemId, star)
                onSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update star: ${safeErrorDetail(e)}")
                onError(syncErrorMessage(e, context))
            }
        }
    }

    fun toggleTrashed(
        videoItemId: Long,
        basePaths: List<SyncRelationWithSource>,
        scope: CoroutineScope,
        errorFlow: MutableStateFlow<String?>,
        onSuccess: () -> Unit = {}
    ) = toggleTrashed(videoItemId, basePaths, scope, { errorFlow.value = it }, onSuccess)

    fun toggleTrashed(
        videoItemId: Long,
        basePaths: List<SyncRelationWithSource>,
        scope: CoroutineScope,
        onError: (String) -> Unit,
        onSuccess: () -> Unit = {}
    ) {
        scope.launch {
            when (val result = toggleTrashedChecked(videoItemId, basePaths)) {
                is ToggleTrashResult.Success -> onSuccess()
                is ToggleTrashResult.NotAllowed ->
                    onError(context.getString(R.string.error_only_base_dir_trash))
                is ToggleTrashResult.Failed ->
                    onError(syncErrorMessage(result.error, context))
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
            Log.e(TAG, "Failed to toggle trashed: ${safeErrorDetail(e)}")
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
    ) = deleteFile(videoItemId, scope, { errorFlow.value = it }, onSuccess)

    fun deleteFile(
        videoItemId: Long,
        scope: CoroutineScope,
        onError: (String) -> Unit,
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
                        onError(syncErrorMessage(result.error, context))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete file: ${safeErrorDetail(e)}")
                onError(syncErrorMessage(e, context))
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
    suspend fun buildShareIntent(videoItemId: Long): Intent? {
        var outFile: File? = null
        var deferredCancellation: CancellationException? = null
        return try {
            val result = withContext(Dispatchers.IO) {
        val video = videoRepository.getById(videoItemId) ?: return@withContext null

        // Fail-closed volume ownership: ciphertext lives only on the active volume; a record with
        // an empty/unknown/mismatched volumeId must not drive decryption on the active store.
        val currentVolumeId = try {
            mediaStore.volumeId
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "buildShareIntent: volumeId lookup failed: ${safeErrorDetail(e)}")
            return@withContext null
        }
        if (video.volumeId != currentVolumeId) {
            Log.w(TAG, "buildShareIntent: volume mismatch, refusing to share (fail-closed)")
            return@withContext null
        }
        if (video.iv.size != CryptoScheme.IV_LEN) {
            Log.w(TAG, "buildShareIntent: iv length ${video.iv.size} != ${CryptoScheme.IV_LEN}")
            return@withContext null
        }

        ShareTempFiles.cleanupOldShareTempFiles(context)
            val safeName = ShareTempFiles.sanitizeFileName(video.fileName)
            // BUG-017: id-suffixed collision-free name — a second share of the same (or a
            // same-named) video gets its OWN file instead of truncating the first recipient's.
            outFile = ShareTempFiles.uniqueShareFile(context, safeName, video.id)
            try {
                val closeCancellation = decryptWholeTo(video, outFile!!)
                if (closeCancellation != null) {
                    deferredCancellation = closeCancellation
                    return@withContext null
                }
            } catch (t: Throwable) {
                // Cleanup responsibility must span the whole "file created → decrypt returned"
                // window: decryptWholeTo's inner outFile.delete() only guards failures after
                // FileOutputStream opens — openCiphertextRead / contentKey failures happen
                // before that and would otherwise leave the pre-created empty file behind.
                // Deleting twice is a harmless no-op; CancellationException is cleaned up and
                // then rethrown verbatim (red line #3 — runCatching only swallows the delete's
                // own failure, never `t`).
                outFile?.let { runCatching { it.delete() } }
                if (t is CancellationException) {
                    deferredCancellation = t
                    null
                } else {
                    throw t
                }
            }
            if (deferredCancellation != null) return@withContext null

            // Cleanup responsibility spans the WHOLE "file created → chooser returned"
            // window, not just the decrypt: a file that decrypted successfully is still
            // garbage if URI/Intent construction fails — nothing can ever consume it.
            // Exactly the file THIS call created (outFile) is removed; in-use files from
            // earlier shares are never touched. Throwable (incl. CancellationException)
            // is cleaned up and then rethrown verbatim (red line #3 — runCatching only
            // swallows the delete's own failure, never `t`).
            val chooser = try {
                val authority = "${context.packageName}.fileprovider"
                val uri = FileProvider.getUriForFile(context, authority, outFile!!)
                val mimeType = getMimeType(video.fileName)
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = mimeType
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                Intent.createChooser(shareIntent, context.getString(R.string.context_share_file))
            } catch (t: Throwable) {
                outFile?.let { runCatching { it.delete() } }
                throw t
            }
            chooser
            }
            deferredCancellation?.let { throw it }
            result
        } catch (e: CancellationException) {
            outFile?.let { runCatching { it.delete() } }
            throw e
        } catch (e: Exception) {
            outFile?.let { runCatching { it.delete() } }
            Log.e(TAG, "buildShareIntent failed: ${safeErrorDetail(e)}")
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
    private suspend fun decryptWholeTo(video: VideoItemEntity, outFile: File): CancellationException? {
        require(video.iv.size == CryptoScheme.IV_LEN) {
            "iv length ${video.iv.size} != ${CryptoScheme.IV_LEN}"
        }
        val store = mediaStore
        var closeFailure: Throwable? = null
        withContext(Dispatchers.IO) {
            val handle = store.openCiphertextRead(video.id)
            var primaryFailure: Throwable? = null
            try {
                val key = store.contentKey()
                val decryptor = RangeDecryptor(key, video.iv, video.fileSize)
                var written = 0L
                try {
                    FileOutputStream(outFile).use { out ->
                        written = drainDecryptor(decryptor, handle, out, video.fileSize)
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
                    primaryFailure = e
                    throw e
                }
            } finally {
                try {
                    handle.close()
                } catch (failure: Throwable) {
                    if (primaryFailure == null) {
                        runCatching { outFile.delete() }
                        // Defer throwing until after withContext returns so its dispatcher
                        // boundary cannot replace the handle's exact exception instance.
                        closeFailure = failure
                    }
                    if (failure !== primaryFailure && primaryFailure != null) {
                        primaryFailure.addSuppressed(failure)
                    }
                }
            }
        }
        return when (val failure = closeFailure) {
            null -> null
            is CancellationException -> failure
            else -> throw failure
        }
    }

    /**
     * Walks the full [size]-byte plaintext of a v2 ciphertext through [decryptor] into [out],
     * returning the total bytes written.
     *
     * Extracted from [decryptWholeTo] so the chunk-walk loop is directly unit-testable:
     * a fake [RangeDecryptor] can drive multi-GiB sizes without materializing real
     * ciphertext, and loop-safety contracts (request-length bounding, no-progress
     * detection) get first-class tests.
     */
    internal fun drainDecryptor(
        decryptor: RangeDecryptor,
        handle: SeekableReadHandle,
        out: OutputStream,
        size: Long,
    ): Long {
        var written = 0L
        var offset = 0L
        while (offset < size) {
            // Long-domain min: for files ≥ 2 GiB the remaining (size - offset) exceeds
            // Int.MAX_VALUE — casting FIRST overflows into a negative request length.
            // The Int cast happens only on the already-bounded result.
            val len = minOf(CryptoScheme.CHUNK_SIZE.toLong(), size - offset).toInt()
            val plain = decryptor.readRange(handle, offset, len)
            // Inside the loop bytes provably remain, so a zero-byte read means the source
            // stalled — a loop here would spin forever with offset never advancing. Fail
            // explicitly instead (RangeDecryptor's contract is all-or-nothing anyway).
            check(plain.isNotEmpty()) {
                "no-progress read at offset $offset with ${size - offset} bytes remaining"
            }
            out.write(plain)
            offset += plain.size
            written += plain.size
        }
        return written
    }

    fun markFolderRead(
        folderPath: String, read: Boolean,
        scope: CoroutineScope,
        errorFlow: MutableStateFlow<String?>,
        onSuccess: () -> Unit = {}
    ) = markFolderRead(folderPath, read, scope, { errorFlow.value = it }, onSuccess)

    fun markFolderRead(
        folderPath: String, read: Boolean,
        scope: CoroutineScope,
        onError: (String) -> Unit,
        onSuccess: () -> Unit = {}
    ) {
        scope.launch {
            try {
                playbackRepository.markFolderCompleted(folderPath, read)
                onSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to mark folder read: ${safeErrorDetail(e)}")
                onError(syncErrorMessage(e, context))
            }
        }
    }

    fun deleteFolder(
        path: String,
        basePaths: List<SyncRelationWithSource>,
        scope: CoroutineScope,
        errorFlow: MutableStateFlow<String?>,
        onSuccess: () -> Unit = {}
    ) = deleteFolder(path, basePaths, scope, { errorFlow.value = it }, onSuccess)

    fun deleteFolder(
        path: String,
        basePaths: List<SyncRelationWithSource>,
        scope: CoroutineScope,
        onError: (String) -> Unit,
        onSuccess: () -> Unit = {}
    ) {
        scope.launch {
            try {
                val isBase = syncRelationRepository.getByLocalBasePath(path) != null
                if (isBase) {
                    onError(context.getString(R.string.error_cannot_delete_base_dir))
                    return@launch
                }
                val allBasePaths = syncRelationRepository.getAllBasePaths()
                val hasDescendantBase = allBasePaths.any { basePath ->
                    basePath.startsWith("$path/")
                }
                if (hasDescendantBase) {
                    onError(context.getString(R.string.error_base_dir_inside))
                    return@launch
                }
                if (isFileInBaseDir(path, basePaths)) {
                    onError(context.getString(R.string.error_cannot_delete_sync_dir))
                    return@launch
                }
                val summary = withContext(Dispatchers.IO) {
                    syncEngine.storageOperationMutex.withLock { deleteFolderContentsLocked(path) }
                }
                if (summary.failed > 0) {
                    // M3: partial failure — do not claim full success (skip onSuccess so UI doesn't
                    // refresh as if everything was gone).
                    onError(context.getString(
                        R.string.error_folder_delete_partial,
                        summary.success, summary.skipped, summary.failed
                    ))
                } else {
                    onSuccess()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete folder: ${safeErrorDetail(e)}")
                onError(syncErrorMessage(e, context))
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
            Log.w(TAG, "deleteMediaById: volume not confirmed " +
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
            Log.w(TAG, "exists() failed: ${safeErrorDetail(e)}")
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
                        Log.w(TAG, "exists() after delete failed: ${safeErrorDetail(e)}")
                        return DeleteMediaResult.Failed(
                            id, SyncException(SyncErrorCode.STORAGE_ERROR, SyncOperation.DELETE, cause = e)
                        )
                    }
                    if (stillThere) {
                        Log.e(TAG, "ciphertext still present after delete returned false")
                        return DeleteMediaResult.Failed(
                            id, SyncException(SyncErrorCode.STORAGE_ERROR, SyncOperation.DELETE)
                        )
                    }
                    // Race / already gone → idempotent ok
                    Log.i(TAG, "ciphertext already gone")
                }
            } catch (e: FileNotFoundException) {
                Log.i(TAG, "ciphertext already gone")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isNotFound(e)) {
                    Log.i(TAG, "ciphertext not found")
                } else {
                    Log.e(TAG, "Failed to delete ciphertext: ${safeErrorDetail(e)}")
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
                    Log.w(TAG, "thumbExists() after delete failed: ${safeErrorDetail(e)}")
                    return DeleteMediaResult.Failed(
                        id, SyncException(SyncErrorCode.THUMBNAIL_ERROR, SyncOperation.DELETE, cause = e)
                    )
                }
                if (stillThere) {
                    Log.e(TAG, "thumb still present after delete returned false")
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
                Log.e(TAG, "Failed to delete thumb: ${safeErrorDetail(e)}")
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
            Log.e(TAG, "Failed to delete DB rows: ${safeErrorDetail(e)}")
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
            Log.w(TAG, "deleteOtherVolumeIndex failed: ${safeErrorDetail(e)}")
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
