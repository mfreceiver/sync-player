package cn.vectory.syncplayer.browser

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import cn.vectory.syncplayer.data.model.FolderStats
import cn.vectory.syncplayer.R
import cn.vectory.syncplayer.data.model.SyncRelationWithSource
import cn.vectory.syncplayer.data.repository.PlaybackRepository
import cn.vectory.syncplayer.data.repository.SyncRelationRepository
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.storage.StoragePaths
import cn.vectory.syncplayer.ui.util.getMimeType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileOperationsUseCase @Inject constructor(
    private val videoRepository: VideoRepository,
    private val playbackRepository: PlaybackRepository,
    private val syncRelationRepository: SyncRelationRepository,
    private val storagePaths: StoragePaths,
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "FileOperations"
    }

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
        relativePath: String,
        scope: CoroutineScope,
        errorFlow: MutableStateFlow<String?>,
        onSuccess: () -> Unit = {}
    ) {
        scope.launch {
            try {
                val video = videoRepository.getByRelativePath(relativePath)
                if (video != null) {
                    playbackRepository.deleteByVideoId(video.id)
                    videoRepository.deleteByRelativePath(relativePath)
                }
                val file = File(storagePaths.userdataRoot, relativePath)
                if (file.exists()) file.delete()
                onSuccess()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete file", e)
                errorFlow.value = e.message
            }
        }
    }

    suspend fun buildShareIntent(videoItemId: Long): Intent? {
        val video = videoRepository.getById(videoItemId) ?: return null
        val file = File(video.localAbsolutePath)
        if (!file.exists()) return null
        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val mimeType = getMimeType(video.fileName)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            Intent.createChooser(shareIntent, context.getString(R.string.context_share_file))
        } catch (e: Exception) {
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
                val dir = File(storagePaths.userdataRoot, path)
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
                if (!dir.exists() || !dir.isDirectory) {
                    errorFlow.value = context.getString(R.string.error_folder_not_exist)
                    return@launch
                }
                videoRepository.deleteByFolderPath(path)
                playbackRepository.deleteByFolderPath(path)
                dir.deleteRecursively()
                onSuccess()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete folder", e)
                errorFlow.value = e.message
            }
        }
    }

    suspend fun getRecursiveFolderStats(folderPath: String): FolderStats =
        videoRepository.getRecursiveFolderStats(folderPath)
}
