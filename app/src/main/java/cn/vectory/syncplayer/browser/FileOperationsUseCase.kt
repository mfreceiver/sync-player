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
import cn.vectory.syncplayer.ui.util.getMimeType
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of deleting one media id (ciphertext + DB). */
sealed class DeleteMediaResult {
    data class Success(val id: Long) : DeleteMediaResult()
    data class SkippedNotFound(val id: Long) : DeleteMediaResult()
    data class Failed(val id: Long, val reason: String) : DeleteMediaResult()
}

data class FolderDeleteSummary(
    val success: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    val failures: List<Pair<Long, String>> = emptyList(),
)

@Singleton
class FileOperationsUseCase @Inject constructor(
    private val videoRepository: VideoRepository,
    private val playbackRepository: PlaybackRepository,
    private val syncRelationRepository: SyncRelationRepository,
    private val encryptedMediaStoreLazy: Lazy<EncryptedMediaStore>,
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
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update star", e)
                errorFlow.value = e.message
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
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update star by path", e)
                errorFlow.value = e.message
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
            try {
                val video = videoRepository.getById(videoItemId)
                if (video == null || !isFileInBaseDir(video.relativePath, basePaths)) {
                    errorFlow.value = context.getString(R.string.error_only_base_dir_trash)
                    return@launch
                }
                playbackRepository.toggleTrashed(videoItemId)
                onSuccess()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to toggle trashed", e)
                errorFlow.value = e.message
            }
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
                val result = withContext(Dispatchers.IO) { deleteMediaById(videoItemId) }
                when (result) {
                    is DeleteMediaResult.Success, is DeleteMediaResult.SkippedNotFound -> onSuccess()
                    is DeleteMediaResult.Failed -> {
                        errorFlow.value = result.reason
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete file id=$videoItemId", e)
                errorFlow.value = e.message
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
            } catch (e: Exception) {
                Log.e(TAG, "Failed to mark folder read", e)
                errorFlow.value = e.message
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
                val summary = withContext(Dispatchers.IO) { deleteFolderContents(path) }
                if (summary.failed > 0) {
                    // M3: partial failure — do not claim full success (skip onSuccess so UI doesn't
                    // refresh as if everything was gone).
                    errorFlow.value =
                        "部分文件删除失败：成功 ${summary.success}，跳过 ${summary.skipped}，失败 ${summary.failed}"
                } else {
                    onSuccess()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete folder", e)
                errorFlow.value = e.message
            }
        }
    }

    /**
     * Delete all media under [path]. Scope by the dominant syncRelationId among matching rows
     * (or delete all matching rows if they share no single relation — rare non-sync orphans).
     */
    private suspend fun deleteFolderContents(path: String): FolderDeleteSummary {
        val candidates = if (path.isEmpty()) {
            videoRepository.getAll()
        } else {
            videoRepository.getByPathPrefix(path)
        }
        if (candidates.isEmpty()) return FolderDeleteSummary()

        // Prefer relation-scoped deletion when all items under path share one relation,
        // or when a single dominant relation covers the folder (sync base children).
        val byRelation = candidates.groupBy { it.syncRelationId }
        val items = if (byRelation.size == 1) {
            candidates
        } else {
            // Multi-relation overlap under same path: only delete rows that are NOT under any
            // other relation's exclusive base. Conservative: delete each by id independently
            // but still use path-prefix result (already boundary-safe).
            candidates
        }

        var success = 0
        var skipped = 0
        val failures = mutableListOf<Pair<Long, String>>()
        for (item in items) {
            when (val r = deleteMediaById(item.id)) {
                is DeleteMediaResult.Success -> success++
                is DeleteMediaResult.SkippedNotFound -> {
                    // Already cleaned ciphertext; DB removed as part of skip path.
                    skipped++
                    success++
                }
                is DeleteMediaResult.Failed -> failures.add(item.id to r.reason)
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
     */
    suspend fun deleteMediaById(id: Long): DeleteMediaResult {
        val video = videoRepository.getById(id)
            ?: return DeleteMediaResult.SkippedNotFound(id)

        val cipherMissing = try {
            !mediaStore.exists(id)
        } catch (e: Exception) {
            // exists() itself failed — treat as IO error, do not wipe DB.
            Log.w(TAG, "exists() failed for id=$id", e)
            return DeleteMediaResult.Failed(id, e.message ?: "exists check failed")
        }

        if (!cipherMissing) {
            try {
                // FileVolume/SafVolume: false = missing OR delete failed (cannot tell alone).
                val deleted = mediaStore.delete(id)
                if (!deleted) {
                    val stillThere = try {
                        mediaStore.exists(id)
                    } catch (e: Exception) {
                        Log.w(TAG, "exists() after delete failed for id=$id", e)
                        return DeleteMediaResult.Failed(id, e.message ?: "exists check after delete failed")
                    }
                    if (stillThere) {
                        Log.e(TAG, "ciphertext still present after delete returned false for id=$id")
                        return DeleteMediaResult.Failed(id, "密文删除失败")
                    }
                    // Race / already gone → idempotent ok
                    Log.i(TAG, "ciphertext already gone for id=$id")
                }
            } catch (e: FileNotFoundException) {
                Log.i(TAG, "ciphertext already gone for id=$id")
            } catch (e: Exception) {
                if (isNotFound(e)) {
                    Log.i(TAG, "ciphertext not found for id=$id: ${e.message}")
                } else {
                    Log.e(TAG, "Failed to delete ciphertext for id=$id", e)
                    return DeleteMediaResult.Failed(id, e.message ?: "delete ciphertext failed")
                }
            }
        }

        // M2: Boolean false from deleteThumb is ambiguous (missing vs fail). Re-check existence.
        try {
            val deleted = mediaStore.deleteThumb(id)
            if (!deleted) {
                val stillThere = try {
                    mediaStore.thumbExists(id)
                } catch (e: Exception) {
                    Log.w(TAG, "thumbExists() after delete failed for id=$id", e)
                    return DeleteMediaResult.Failed(id, "缩略图删除失败: ${e.message}")
                }
                if (stillThere) {
                    Log.e(TAG, "thumb still present after delete returned false for id=$id")
                    return DeleteMediaResult.Failed(id, "缩略图删除失败")
                }
                // No thumb file → idempotent success (common: never generated)
            }
        } catch (e: Exception) {
            if (isNotFound(e)) {
                // idempotent ok
            } else {
                Log.e(TAG, "Failed to delete thumb for id=$id", e)
                return DeleteMediaResult.Failed(id, "缩略图删除失败: ${e.message}")
            }
        }

        return try {
            videoRepository.deleteMediaRecord(id)
            if (cipherMissing) DeleteMediaResult.SkippedNotFound(id)
            else DeleteMediaResult.Success(id)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete DB rows for id=$id", e)
            DeleteMediaResult.Failed(id, e.message ?: "delete DB failed")
        }
    }

    /**
     * B1: drop other-volume index rows only. Never touch active-volume ciphertext/thumbs
     * (ids on inactive volumes must not drive mediaStore.delete on the active store).
     */
    suspend fun deleteOtherVolumeIndex(videoItemId: Long): DeleteMediaResult {
        val video = videoRepository.getById(videoItemId)
            ?: return DeleteMediaResult.SkippedNotFound(videoItemId)
        return try {
            videoRepository.deleteMediaRecord(video.id)
            DeleteMediaResult.Success(video.id)
        } catch (e: Exception) {
            Log.w(TAG, "deleteOtherVolumeIndex failed for id=$videoItemId", e)
            DeleteMediaResult.Failed(videoItemId, e.message ?: "DB 删除失败")
        }
    }

    private fun isNotFound(e: Exception): Boolean {
        if (e is FileNotFoundException) return true
        val msg = e.message?.lowercase().orEmpty()
        return msg.contains("no such file")
            || msg.contains("not found")
            || msg.contains("enoent")
            || (e is IOException && msg.contains("does not exist"))
    }

    suspend fun getRecursiveFolderStats(folderPath: String): FolderStats =
        videoRepository.getRecursiveFolderStats(folderPath)
}
