package cn.vectory.syncplayer.browser

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.util.Log
import cn.vectory.syncplayer.R
import cn.vectory.syncplayer.data.model.FolderStats
import cn.vectory.syncplayer.data.model.SyncRelationWithSource
import cn.vectory.syncplayer.data.repository.PlaybackRepository
import cn.vectory.syncplayer.data.repository.SyncRelationRepository
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.di.IoDispatcher
import cn.vectory.syncplayer.provider.MediaUris
import cn.vectory.syncplayer.storage.MediaExistence
import cn.vectory.syncplayer.storage.MediaFileStore
import cn.vectory.syncplayer.sync.SyncErrorCode
import cn.vectory.syncplayer.sync.SyncException
import cn.vectory.syncplayer.sync.SyncOperation
import cn.vectory.syncplayer.sync.SyncEngine
import cn.vectory.syncplayer.sync.syncErrorMessage
import cn.vectory.syncplayer.ui.util.getMimeType
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of deleting one media id (file + DB). */
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
    private val mediaFileStoreLazy: Lazy<MediaFileStore>,
    private val syncEngine: SyncEngine,
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    companion object {
        private const val TAG = "FileOperations"
    }

    private val mediaStore: MediaFileStore get() = mediaFileStoreLazy.get()

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
                val result = withContext(ioDispatcher) {
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
     * Build an [Intent.ACTION_SEND] chooser that shares [videoItemId] directly through
     * [cn.vectory.syncplayer.provider.MediaContentProvider] via a temporary URI grant.
     *
     * The share URI is a 3-segment media URI (`.../media/<id>/<fileName>`, see
     * [MediaUris.mediaUri]) so recipients that ignore `ContentResolver.query()` and just
     * read the URI's last path segment still see the original name with its extension.
     * No temp file is involved: the provider streams the plaintext file read-only, and
     * `FLAG_GRANT_READ_URI_PERMISSION` (plus an explicit [ClipData]) lets the recipient
     * read that single URI despite the provider's signature-level read permission.
     *
     * Failure contract: a missing entity returns `null` (UI shows "cannot share").
     * [CancellationException] always propagates (red line #3). Any other failure is logged
     * (without sensitive data — no fileName / path / underlying message) and surfaces as
     * `null`.
     */
    suspend fun buildShareIntent(videoItemId: Long): Intent? = try {
        withContext(ioDispatcher) {
            val video = videoRepository.getById(videoItemId) ?: return@withContext null
            val uri = MediaUris.mediaUri(video.id, video.fileName)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = getMimeType(video.fileName)
                putExtra(Intent.EXTRA_STREAM, uri)
                // 显式 ClipData + grant flag：确保收件方拿到对该 URI 的临时读授权
                // （provider 自身带 signature 级 readPermission，仅此 URI 被豁免）。
                clipData = ClipData.newRawUri(null, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            Intent.createChooser(shareIntent, context.getString(R.string.context_share_file))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "buildShareIntent failed: ${safeErrorDetail(e)}")
        null
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
                val summary = withContext(ioDispatcher) {
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
     * Delete media file + thumb + DB for [id].
     * - Missing file (NotFound / !exists): treat as success and still remove DB.
     * - Real IO/permission error: keep DB row, return Failed.
     */
    suspend fun deleteMediaById(id: Long): DeleteMediaResult {
        return syncEngine.storageOperationMutex.withLock { deleteMediaByIdLocked(id) }
    }

    /** Called only while [SyncEngine.storageOperationMutex] is held by a parent workflow. */
    internal suspend fun deleteMediaByIdLocked(id: Long): DeleteMediaResult {
        val video = videoRepository.getById(id)
            ?: return DeleteMediaResult.SkippedNotFound(id)

        val existence = try {
            mediaStore.checkExistence(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // checkExistence() itself threw unexpected exception — treat as IO error, do not wipe DB.
            Log.w(TAG, "checkExistence failed: ${safeErrorDetail(e)}")
            return DeleteMediaResult.Failed(
                id, SyncException(SyncErrorCode.STORAGE_ERROR, SyncOperation.DELETE, cause = e)
            )
        }

        val fileMissing = when (existence) {
            is MediaExistence.NOT_FOUND -> true
            is MediaExistence.EXISTS -> false
            is MediaExistence.IO_ERROR -> {
                Log.w(TAG, "checkExistence IO_ERROR: ${existence.reason}")
                return DeleteMediaResult.Failed(
                    id, SyncException(SyncErrorCode.STORAGE_ERROR, SyncOperation.DELETE)
                )
            }
        }

        if (!fileMissing) {
            try {
                // Boolean false = missing OR delete failed (cannot tell alone) — re-check below.
                val deleted = mediaStore.delete(id)
                if (!deleted) {
                    val stillThere = try {
                        mediaStore.checkExistence(id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "checkExistence after delete failed: ${safeErrorDetail(e)}")
                        return DeleteMediaResult.Failed(
                            id, SyncException(SyncErrorCode.STORAGE_ERROR, SyncOperation.DELETE, cause = e)
                        )
                    }
                    when (stillThere) {
                        is MediaExistence.EXISTS -> {
                            Log.e(TAG, "media still present after delete returned false")
                            return DeleteMediaResult.Failed(
                                id, SyncException(SyncErrorCode.STORAGE_ERROR, SyncOperation.DELETE)
                            )
                        }
                        is MediaExistence.IO_ERROR -> {
                            Log.w(TAG, "checkExistence after delete returned IO_ERROR: ${stillThere.reason}")
                            return DeleteMediaResult.Failed(
                                id, SyncException(SyncErrorCode.STORAGE_ERROR, SyncOperation.DELETE)
                            )
                        }
                        is MediaExistence.NOT_FOUND -> {
                            // Race / already gone → idempotent ok
                            Log.i(TAG, "media already gone")
                        }
                    }
                }
            } catch (e: FileNotFoundException) {
                Log.i(TAG, "media already gone")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isNotFound(e)) {
                    Log.i(TAG, "media not found")
                } else {
                    Log.e(TAG, "Failed to delete media: ${safeErrorDetail(e)}")
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
                    mediaStore.checkThumbExistence(id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "checkThumbExistence after delete failed: ${safeErrorDetail(e)}")
                    return DeleteMediaResult.Failed(
                        id, SyncException(SyncErrorCode.THUMBNAIL_ERROR, SyncOperation.DELETE, cause = e)
                    )
                }
                when (stillThere) {
                    is MediaExistence.EXISTS -> {
                        Log.e(TAG, "thumb still present after delete returned false")
                        return DeleteMediaResult.Failed(
                            id, SyncException(SyncErrorCode.THUMBNAIL_ERROR, SyncOperation.DELETE)
                        )
                    }
                    is MediaExistence.IO_ERROR -> {
                        Log.w(TAG, "checkThumbExistence after delete returned IO_ERROR: ${stillThere.reason}")
                        return DeleteMediaResult.Failed(
                            id, SyncException(SyncErrorCode.THUMBNAIL_ERROR, SyncOperation.DELETE)
                        )
                    }
                    is MediaExistence.NOT_FOUND -> {
                        // No thumb file → idempotent success (common: never generated)
                    }
                }
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
            if (fileMissing) DeleteMediaResult.SkippedNotFound(id)
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

    private fun isNotFound(e: Exception): Boolean {
        // Rely on exception TYPE only — never parse message text (fragile across locales/libs).
        return e is FileNotFoundException
    }

    suspend fun getRecursiveFolderStats(folderPath: String): FolderStats =
        videoRepository.getRecursiveFolderStats(folderPath)
}
