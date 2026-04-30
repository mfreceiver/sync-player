package cn.vectory.syncplayer.browser

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.vectory.syncplayer.data.local.dao.VideoItemDao
import cn.vectory.syncplayer.data.model.BrowseItem
import cn.vectory.syncplayer.data.repository.PlaybackRepository
import cn.vectory.syncplayer.data.repository.SyncRelationRepository
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.data.model.SyncRelationWithSource
import cn.vectory.syncplayer.storage.StoragePaths
import cn.vectory.syncplayer.ui.components.FileContextMenuActions
import cn.vectory.syncplayer.ui.components.FolderContextMenuActions
import cn.vectory.syncplayer.ui.util.fileNameComparator
import cn.vectory.syncplayer.ui.util.getMimeType
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val videoRepository: VideoRepository,
    private val playbackRepository: PlaybackRepository,
    private val syncRelationRepository: SyncRelationRepository,
    private val storagePaths: StoragePaths,
    @ApplicationContext private val context: Context
) : ViewModel(), FileContextMenuActions, FolderContextMenuActions {

    enum class SearchScope { ALL, FILES_ONLY, FOLDERS_ONLY }

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _searchScope = MutableStateFlow(SearchScope.ALL)
    val searchScope: StateFlow<SearchScope> = _searchScope.asStateFlow()

    val searchFolder: String = savedStateHandle.get<String>("folder") ?: ""

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val playbackRecords = playbackRepository.getAllFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val basePaths = syncRelationRepository.getAllWithSourceFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val scopedFolderPaths = videoRepository.getParentPathsInFolder(searchFolder)
        .map { parentPaths ->
            val folderSet = mutableSetOf<String>()
            for (parentPath in parentPaths) {
                if (parentPath.isEmpty()) continue
                val segments = parentPath.split("/")
                var current = ""
                for (segment in segments) {
                    current = if (current.isEmpty()) segment else "$current/$segment"
                    folderSet.add(current)
                }
            }
            folderSet.toList()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val searchResults: StateFlow<List<BrowseItem>> = combine(
        _query.debounce(300),
        _searchScope,
        _query.flatMapLatest { q ->
            if (q.isBlank()) flowOf(emptyList())
            else videoRepository.searchByFileNameInFolder(q, searchFolder)
        },
        scopedFolderPaths,
        playbackRecords
    ) { query: String, scope: SearchScope, fileResults: List<cn.vectory.syncplayer.data.local.entity.VideoItemEntity>, folders: List<String>, records: List<cn.vectory.syncplayer.data.local.entity.PlaybackRecordEntity> ->
        if (query.isBlank()) return@combine emptyList<BrowseItem>()
        val recordMap = records.associateBy { it.videoItemId }
        buildSearchResults(query, scope, fileResults, folders, recordMap)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private fun buildSearchResults(
        query: String,
        scope: SearchScope,
        fileResults: List<cn.vectory.syncplayer.data.local.entity.VideoItemEntity>,
        folders: List<String>,
        recordMap: Map<Long, cn.vectory.syncplayer.data.local.entity.PlaybackRecordEntity>
    ): List<BrowseItem> {
        val result = mutableListOf<BrowseItem>()

        if (scope != SearchScope.FILES_ONLY) {
            val matchingFolders = folders.filter { folderPath ->
                val leafName = folderPath.substringAfterLast('/')
                leafName.contains(query, ignoreCase = true)
            }.map { folderPath ->
                val leafName = folderPath.substringAfterLast('/')
                BrowseItem(
                    videoItemId = null,
                    name = leafName,
                    relativePath = folderPath,
                    isDirectory = true
                )
            }.sortedWith(compareBy(fileNameComparator) { it.name })
            result.addAll(matchingFolders)
        }

        if (scope != SearchScope.FOLDERS_ONLY) {
            val matchingFiles = fileResults.map { video ->
                val record = recordMap[video.id]
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
                    isTrashed = record?.isTrashed ?: false,
                    star = video.star
                )
            }.sortedWith(compareBy(fileNameComparator) { it.name })
            result.addAll(matchingFiles)
        }

        return result
    }

    fun setQuery(query: String) { _query.value = query }
    fun setSearchScope(scope: SearchScope) { _searchScope.value = scope }
    fun clearError() { _error.value = null }

    fun getSearchResultVideoIds(): List<Long> {
        return searchResults.value
            .filter { !it.isDirectory && it.videoItemId != null }
            .mapNotNull { it.videoItemId }
    }

    override fun updateStar(videoItemId: Long, star: Int) {
        viewModelScope.launch {
            try {
                videoRepository.updateStar(videoItemId, star)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update star", e)
                _error.value = e.message
            }
        }
    }

    override fun updateStarByPath(relativePath: String, star: Int) {
        viewModelScope.launch {
            try {
                videoRepository.updateStarByPath(relativePath, star)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update star by path", e)
                _error.value = e.message
            }
        }
    }

    override fun toggleTrashed(videoItemId: Long) {
        viewModelScope.launch {
            try {
                val video = videoRepository.getById(videoItemId)
                if (video == null || !isFileInBaseDir(video.relativePath)) {
                    _error.value = "仅同步基目录内的文件可标记回收站"
                    return@launch
                }
                playbackRepository.toggleTrashed(videoItemId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to toggle trashed", e)
                _error.value = e.message
            }
        }
    }

    override fun deleteFile(relativePath: String) {
        viewModelScope.launch {
            try {
                val video = videoRepository.getByRelativePath(relativePath)
                if (video != null) {
                    playbackRepository.deleteByVideoId(video.id)
                    videoRepository.deleteByRelativePath(relativePath)
                }
                val file = File(storagePaths.userdataRoot, relativePath)
                if (file.exists()) file.delete()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete file", e)
                _error.value = e.message
            }
        }
    }

    override fun isFileInBaseDir(relativePath: String): Boolean {
        val basePathsList = basePaths.value.map { it.relation.localBasePath }
        return basePathsList.any { basePath ->
            relativePath.startsWith("$basePath/") || relativePath == basePath
        }
    }

    override suspend fun buildShareIntent(videoItemId: Long): Intent? {
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

    override fun markFolderRead(folderPath: String, read: Boolean) {
        viewModelScope.launch {
            try {
                playbackRepository.markFolderCompleted(folderPath, read)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to mark folder read", e)
                _error.value = e.message
            }
        }
    }

    override fun deleteFolder(path: String) {
        viewModelScope.launch {
            try {
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
                if (isFileInBaseDir(path)) {
                    _error.value = "同步目录内的文件夹不能手动删除"
                    return@launch
                }
                if (!dir.exists() || !dir.isDirectory) {
                    _error.value = "文件夹不存在"
                    return@launch
                }
                videoRepository.deleteByFolderPath(path)
                playbackRepository.deleteByFolderPath(path)
                dir.deleteRecursively()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete folder", e)
                _error.value = e.message
            }
        }
    }

    override suspend fun getRecursiveFolderStats(folderPath: String): VideoItemDao.RecursiveFolderStat =
        videoRepository.getRecursiveFolderStats(folderPath)

    companion object {
        private const val TAG = "SearchViewModel"
    }
}
