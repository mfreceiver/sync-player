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
import cn.vectory.syncplayer.data.repository.UserPreferencesRepository
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.storage.StoragePaths
import cn.vectory.syncplayer.sync.VideoMetadataExtractor
import cn.vectory.syncplayer.ui.util.getMimeType
import cn.vectory.syncplayer.ui.util.isVideoFile
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@HiltViewModel
class BrowserViewModel @Inject constructor(
    private val videoRepository: VideoRepository,
    private val playbackRepository: PlaybackRepository,
    private val syncRelationRepository: SyncRelationRepository,
    private val videoMetadataExtractor: VideoMetadataExtractor,
    private val storagePaths: StoragePaths,
    private val userPrefs: UserPreferencesRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    enum class SortMode { FILENAME_ASC, FILENAME_DESC, DURATION_ASC, DURATION_DESC }
    enum class BrowseFilter { ALL, VIDEO_ONLY, TRASHED_ONLY }
    enum class ViewMode { LIST, GRID }

    private val _currentFolder = MutableStateFlow("")
    val currentFolder: StateFlow<String> = _currentFolder.asStateFlow()

    private val _sortMode = MutableStateFlow(SortMode.FILENAME_ASC)
    val sortMode: StateFlow<SortMode> = _sortMode.asStateFlow()

    private val _filter = MutableStateFlow(BrowseFilter.ALL)
    val filter: StateFlow<BrowseFilter> = _filter.asStateFlow()

    private val _viewMode = MutableStateFlow(ViewMode.LIST)
    val viewMode: StateFlow<ViewMode> = _viewMode.asStateFlow()

    private val _gridColumns = MutableStateFlow(3)
    val gridColumns: StateFlow<Int> = _gridColumns.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _refreshTrigger = MutableStateFlow(0L)

    private val effectiveFolder = combine(_currentFolder, _refreshTrigger) { folder, _ -> folder }

    init {
        viewModelScope.launch {
            _sortMode.value = try {
                SortMode.valueOf(userPrefs.getDefaultSortMode())
            } catch (_: Exception) {
                SortMode.FILENAME_ASC
            }
            _filter.value = try {
                BrowseFilter.valueOf(userPrefs.getDefaultFilter())
            } catch (_: Exception) {
                BrowseFilter.ALL
            }
            _viewMode.value = try {
                ViewMode.valueOf(userPrefs.getViewMode())
            } catch (_: Exception) {
                ViewMode.LIST
            }
            _gridColumns.value = userPrefs.getGridColumns()
        }
    }

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

    val currentRelationId: StateFlow<Long?> = combine(effectiveFolder, basePathToRelationId) { folder, pathMap ->
        pathMap.entries.firstOrNull { (basePath, _) ->
            folder == basePath || folder.startsWith("$basePath/")
        }?.value
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val browseItems: StateFlow<List<BrowseItem>> = combine(
        effectiveFolder,
        _sortMode,
        _filter,
        playbackRecords,
        basePaths
    ) { folder, mode, filterVal, records, relations ->
        val baseDirSet = relations.map { it.relation.localBasePath }.toSet()
        buildBrowseItems(folder, mode, filterVal, records, baseDirSet)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private suspend fun buildBrowseItems(
        folder: String,
        mode: SortMode,
        filterVal: BrowseFilter,
        records: List<PlaybackRecordEntity>,
        baseDirSet: Set<String>
    ): List<BrowseItem> = withContext(Dispatchers.IO) {
        val dbFolderNames = videoRepository.getSubFolders(folder).first()

        val physicalDir = if (folder.isEmpty()) storagePaths.userdataRoot else File(storagePaths.userdataRoot, folder)
        val physicalFolderNames = physicalDir.listFiles()
            ?.filter { it.isDirectory }
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
                isSyncBaseDir = baseDirSet.contains(fullPath),
                containsSyncBaseDir = baseDirSet.any { it.startsWith("$fullPath/") }
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
                    isTrashed = record?.isTrashed ?: false
                )
            }

        repairMissingThumbnails(videoRepository.getItemsInFolder(folder).first())

        val filteredVideos = when (filterVal) {
            BrowseFilter.ALL -> videos
            BrowseFilter.VIDEO_ONLY -> videos.filter { isVideoFile(it.name) }
            BrowseFilter.TRASHED_ONLY -> videos.filter { it.isTrashed }
        }
        val sortedFolders = folders.sortedBy { it.name.lowercase() }
        val sortedVideos = when (mode) {
            SortMode.FILENAME_ASC -> filteredVideos.sortedBy { it.name.lowercase() }
            SortMode.FILENAME_DESC -> filteredVideos.sortedByDescending { it.name.lowercase() }
            SortMode.DURATION_ASC -> filteredVideos.sortedBy { it.duration ?: 0L }
            SortMode.DURATION_DESC -> filteredVideos.sortedByDescending { it.duration ?: 0L }
        }
        (sortedFolders + sortedVideos).distinctBy { it.relativePath }
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
        _sortMode.value = mode
    }

    fun setFilter(filter: BrowseFilter) {
        _filter.value = filter
    }

    fun setViewMode(mode: ViewMode) {
        _viewMode.value = mode
        viewModelScope.launch { userPrefs.setViewMode(mode.name) }
    }

    fun setGridColumns(columns: Int) {
        _gridColumns.value = columns.coerceIn(1, 5)
        viewModelScope.launch { userPrefs.setGridColumns(columns) }
    }

    fun createFolder(parentPath: String, folderName: String) {
        viewModelScope.launch {
            if (parentPath.isNotEmpty() && isFileInBaseDir(parentPath)) {
                _error.value = "同步基目录内无法手动创建文件夹"
                return@launch
            }
            val newDir = if (parentPath.isEmpty()) {
                File(storagePaths.userdataRoot, folderName)
            } else {
                File(File(storagePaths.userdataRoot, parentPath), folderName)
            }
            if (newDir.exists()) {
                _error.value = "文件夹已存在"
                return@launch
            }
            if (!newDir.mkdirs()) {
                _error.value = "创建文件夹失败"
                return@launch
            }
            _refreshTrigger.value++
        }
    }

    fun deleteFolder(path: String) {
        viewModelScope.launch {
            val dir = File(storagePaths.userdataRoot, path)
            val isBase = syncRelationRepository.getByLocalBasePath(path) != null
            if (isBase) {
                _error.value = "同步基目录不能删除，请先删除对应的同步关系"
                return@launch
            }
            val allBasePaths = syncRelationRepository.getAllBasePaths()
            val hasDescendantBase = allBasePaths.any { basePath ->
                basePath.startsWith("$path/")
            }
            if (hasDescendantBase) {
                _error.value = "文件夹内包含同步基目录，请先删除对应的同步关系"
                return@launch
            }
            if (!dir.exists() || !dir.isDirectory) {
                _error.value = "文件夹不存在"
                return@launch
            }
            videoRepository.deleteByFolderPath(path)
            playbackRepository.deleteByFolderPath(path)
            dir.deleteRecursively()
            _refreshTrigger.value++
        }
    }

    fun clearError() {
        _error.value = null
    }

    private fun repairMissingThumbnails(videos: List<VideoItemEntity>) {
        val needsRepair = videos.filter { video ->
            val thumbMissing = video.thumbnailPath == null || !File(video.thumbnailPath).exists()
            thumbMissing && File(video.localAbsolutePath).exists()
        }
        if (needsRepair.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            needsRepair.forEach { video ->
                videoMetadataExtractor.repairThumbnailIfNeeded(video)
            }
        }
    }

    fun getFolderVideoIds(): List<Long> {
        return browseItems.value
            .filter { !it.isDirectory && it.videoItemId != null && isVideoFile(it.name) }
            .mapNotNull { it.videoItemId }
    }

    fun toggleTrashed(videoItemId: Long) {
        viewModelScope.launch {
            val video = videoRepository.getById(videoItemId)
            if (video == null || !isFileInBaseDir(video.relativePath)) {
                _error.value = "仅同步基目录内的文件可标记回收站"
                return@launch
            }
            playbackRepository.toggleTrashed(videoItemId)
        }
    }

    fun isFileInBaseDir(relativePath: String): Boolean {
        val basePathsList = basePaths.value.map { it.relation.localBasePath }
        return basePathsList.any { basePath ->
            relativePath.startsWith("$basePath/") || relativePath == basePath
        }
    }

    fun deleteFile(relativePath: String) {
        viewModelScope.launch {
            val video = videoRepository.getByRelativePath(relativePath)
            if (video != null) {
                playbackRepository.deleteByVideoId(video.id)
                videoRepository.deleteByRelativePath(relativePath)
            }
            val file = File(storagePaths.userdataRoot, relativePath)
            if (file.exists()) file.delete()
            _refreshTrigger.value++
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

            Intent.createChooser(shareIntent, "分享文件")
        } catch (e: Exception) {
            null
        }
    }
}
