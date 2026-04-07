package cn.vectory.syncplayer.browser

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.vectory.syncplayer.data.local.entity.PlaybackRecordEntity
import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import cn.vectory.syncplayer.data.model.BrowseItem
import cn.vectory.syncplayer.data.repository.PlaybackRepository
import cn.vectory.syncplayer.data.repository.SyncRelationRepository
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.player.PlayerNavigationStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class BrowserViewModel @Inject constructor(
    private val videoRepository: VideoRepository,
    private val playbackRepository: PlaybackRepository,
    private val syncRelationRepository: SyncRelationRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    enum class SortMode {
        FILENAME_ASC, FILENAME_DESC, DURATION_ASC, DURATION_DESC
    }

    enum class BrowseFilter {
        ALL, VIDEO_ONLY, FAVORITES_ONLY
    }

    private val _currentFolder = MutableStateFlow("")
    val currentFolder: StateFlow<String> = _currentFolder.asStateFlow()

    private val _sortMode = MutableStateFlow(globalSortMode)
    val sortMode: StateFlow<SortMode> = _sortMode.asStateFlow()

    private val _filter = MutableStateFlow(globalFilter)
    val filter: StateFlow<BrowseFilter> = _filter.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val hasConfig: StateFlow<Boolean> = syncRelationRepository.getAllWithSourceFlow()
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val playbackRecords = playbackRepository.getAllFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val basePaths = syncRelationRepository.getAllWithSourceFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val basePathToRelationId = basePaths.map { list ->
        list.associate { it.relation.localBasePath to it.relation.id }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val currentRelationId: StateFlow<Long?> = combine(_currentFolder, basePathToRelationId) { folder, pathMap ->
        pathMap.entries.firstOrNull { (basePath, _) ->
            folder == basePath || folder.startsWith("$basePath/")
        }?.value
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val browseItems: StateFlow<List<BrowseItem>> = combine(
        _currentFolder,
        _sortMode,
        _filter,
        playbackRecords,
        basePaths
    ) { folder, mode, filterVal, records, relations ->
        val baseDirSet = relations.map { it.relation.localBasePath }.toSet()
        buildBrowseItems(folder, mode, filterVal, records, baseDirSet)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val hiddenDirNames = setOf("debug-logs")

    private suspend fun buildBrowseItems(
        folder: String,
        mode: SortMode,
        filterVal: BrowseFilter,
        records: List<PlaybackRecordEntity>,
        baseDirSet: Set<String>
    ): List<BrowseItem> {
        val dbFolderNames = videoRepository.getSubFolders(folder).first()

        val physicalDir = if (folder.isEmpty()) context.filesDir else File(context.filesDir, folder)
        val physicalFolderNames = physicalDir.listFiles()
            ?.filter { it.isDirectory && it.name !in hiddenDirNames }
            ?.map { it.name }
            ?.toSet()
            ?: emptySet()

        val allFolderNames = (dbFolderNames.toSet() + physicalFolderNames).sortedBy { it.lowercase() }

        val folders = allFolderNames.map { folderName ->
            val fullPath = if (folder.isEmpty()) folderName else "$folder/$folderName"
            BrowseItem(
                videoItemId = null,
                name = folderName,
                relativePath = fullPath,
                isDirectory = true,
                isSyncBaseDir = baseDirSet.contains(fullPath)
            )
        }

        val videos = videoRepository.getItemsInFolder(folder).first()
            .map { video ->
                val record = records.find { it.videoItemId == video.id }
                BrowseItem(
                    videoItemId = video.id,
                    name = video.fileName,
                    relativePath = video.relativePath,
                    isDirectory = false,
                    fileSize = video.fileSize,
                    duration = video.duration,
                    width = video.width,
                    height = video.height,
                    bitrate = video.bitrate,
                    frameRate = video.frameRate,
                    thumbnailPath = video.thumbnailPath,
                    playbackPosition = record?.position ?: 0,
                    playbackDuration = record?.duration ?: 0,
                    isCompleted = record?.isCompleted ?: false,
                    isFavorite = record?.isFavorite ?: false
                )
            }

        val filteredVideos = when (filterVal) {
            BrowseFilter.ALL -> videos
            BrowseFilter.VIDEO_ONLY -> videos.filter { isVideoFile(it.name) }
            BrowseFilter.FAVORITES_ONLY -> videos.filter { it.isFavorite }
        }
        val sortedFolders = folders.sortedBy { it.name.lowercase() }
        val sortedVideos = when (mode) {
            SortMode.FILENAME_ASC -> filteredVideos.sortedBy { it.name.lowercase() }
            SortMode.FILENAME_DESC -> filteredVideos.sortedByDescending { it.name.lowercase() }
            SortMode.DURATION_ASC -> filteredVideos.sortedBy { it.duration ?: 0L }
            SortMode.DURATION_DESC -> filteredVideos.sortedByDescending { it.duration ?: 0L }
        }
        return (sortedFolders + sortedVideos).distinctBy { it.relativePath }
    }

    fun setFolder(folder: String) {
        _currentFolder.value = folder
    }

    fun navigateUp(): Boolean {
        val current = _currentFolder.value
        if (current.isEmpty()) {
            return false
        }
        val lastSlash = current.lastIndexOf('/')
        _currentFolder.value = if (lastSlash > 0) {
            current.substring(0, lastSlash)
        } else {
            ""
        }
        return true
    }

    fun setSortMode(mode: SortMode) {
        globalSortMode = mode
        _sortMode.value = mode
    }

    fun setFilter(filter: BrowseFilter) {
        globalFilter = filter
        _filter.value = filter
    }

    fun createFolder(parentPath: String, folderName: String) {
        viewModelScope.launch {
            val newDir = if (parentPath.isEmpty()) {
                File(context.filesDir, folderName)
            } else {
                File(File(context.filesDir, parentPath), folderName)
            }
            if (newDir.exists()) {
                _error.value = "文件夹已存在"
                return@launch
            }
            if (!newDir.mkdirs()) {
                _error.value = "创建文件夹失败"
                return@launch
            }
            _currentFolder.value = _currentFolder.value
        }
    }

    fun deleteFolder(path: String) {
        viewModelScope.launch {
            val dir = File(context.filesDir, path)
            val isBase = syncRelationRepository.getByLocalBasePath(path) != null
            if (isBase) {
                _error.value = "同步基目录不能删除，请先删除对应的同步关系"
                return@launch
            }
            if (!dir.exists() || !dir.isDirectory) {
                _error.value = "文件夹不存在"
                return@launch
            }
            if (dir.listFiles()?.isNotEmpty() == true) {
                _error.value = "文件夹不为空，无法删除"
                return@launch
            }
            if (!dir.delete()) {
                _error.value = "删除文件夹失败"
                return@launch
            }
            _currentFolder.value = _currentFolder.value
        }
    }

    fun clearError() {
        _error.value = null
    }

    companion object {
        private var globalSortMode = SortMode.FILENAME_ASC
        private var globalFilter = BrowseFilter.ALL
    }

    fun getFolderVideoIds(): List<Long> {
        return browseItems.value
            .filter { !it.isDirectory && it.videoItemId != null && isVideoFile(it.name) }
            .mapNotNull { it.videoItemId }
    }

    fun setFolderVideoIdsForNavigation(ids: List<Long>) {
        PlayerNavigationStore.folderVideoIds = ids
    }

    fun toggleFavorite(videoItemId: Long) {
        viewModelScope.launch {
            playbackRepository.toggleFavorite(videoItemId)
        }
    }

    fun shareVideo(videoItemId: Long) {
        viewModelScope.launch {
            val video = videoRepository.getById(videoItemId) ?: return@launch
            val file = File(video.localAbsolutePath)
            if (!file.exists()) return@launch

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val mimeType = getMimeType(video.fileName)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooserIntent = Intent.createChooser(intent, "分享文件")
            chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooserIntent)
        }
    }
}

private val VIDEO_EXTENSIONS = setOf(
    "mp4", "mkv", "avi", "mov", "flv", "wmv", "webm", "ts", "m4v", "3gp", "mpeg", "mpg"
)

private fun isVideoFile(fileName: String): Boolean {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return ext in VIDEO_EXTENSIONS
}

private fun getMimeType(fileName: String): String {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return when (ext) {
        in VIDEO_EXTENSIONS -> "video/*"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "webp" -> "image/webp"
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        "aac" -> "audio/aac"
        "ogg" -> "audio/ogg"
        "srt" -> "text/plain"
        "ass" -> "text/plain"
        "nfo" -> "text/plain"
        "txt" -> "text/plain"
        else -> "application/octet-stream"
    }
}
