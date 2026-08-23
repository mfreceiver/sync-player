package cn.vectory.syncplayer.browser

import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.vectory.syncplayer.data.model.BrowseItem
import cn.vectory.syncplayer.data.model.FolderStats
import cn.vectory.syncplayer.data.repository.SyncRelationRepository
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.ui.components.FileContextMenuActions
import cn.vectory.syncplayer.ui.components.FolderContextMenuActions
import cn.vectory.syncplayer.ui.util.fileNameComparator
import cn.vectory.syncplayer.ui.util.isImageFile
import cn.vectory.syncplayer.ui.util.isVideoFile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val videoRepository: VideoRepository,
    private val syncRelationRepository: SyncRelationRepository,
    private val fileOps: FileOperationsUseCase
) : ViewModel(), FileContextMenuActions, FolderContextMenuActions {

    enum class SearchScope { ALL, FILES_ONLY, FOLDERS_ONLY }

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _searchScope = MutableStateFlow(SearchScope.ALL)
    val searchScope: StateFlow<SearchScope> = _searchScope.asStateFlow()

    val searchFolder: String = savedStateHandle.get<String>("folder") ?: ""

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

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
        scopedFolderPaths
    ) { query: String, scope: SearchScope, fileResults: List<cn.vectory.syncplayer.data.local.dao.VideoItemDao.VideoItemWithPlayback>, folders: List<String> ->
        if (query.isBlank()) return@combine emptyList<BrowseItem>()
        buildSearchResults(query, scope, fileResults, folders)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private fun buildSearchResults(
        query: String,
        scope: SearchScope,
        fileResults: List<cn.vectory.syncplayer.data.local.dao.VideoItemDao.VideoItemWithPlayback>,
        folders: List<String>
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
                    playbackPosition = video.playbackPosition,
                    playbackDuration = video.playbackDuration,
                    isCompleted = video.isCompleted,
                    isTrashed = video.isTrashed,
                    hasGainAdjusted = video.audioGainDb != null,
                    star = video.star,
                    mediaType = video.mediaType,
                    dateTaken = video.dateTaken
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
            .filter { !it.isDirectory && it.videoItemId != null && isVideoFile(it.name) }
            .mapNotNull { it.videoItemId }
    }

    fun getSearchResultImageIds(): List<Long> {
        return searchResults.value
            .filter { !it.isDirectory && it.videoItemId != null && isImageFile(it.name) }
            .mapNotNull { it.videoItemId }
    }

    override fun updateStar(videoItemId: Long, star: Int) {
        fileOps.updateStar(videoItemId, star, viewModelScope, _error)
    }

    override fun toggleTrashed(videoItemId: Long) {
        fileOps.toggleTrashed(videoItemId, basePaths.value, viewModelScope, _error)
    }

    override fun deleteFile(videoItemId: Long) {
        fileOps.deleteFile(videoItemId, viewModelScope, _error)
    }

    override fun isFileInBaseDir(relativePath: String): Boolean {
        return fileOps.isFileInBaseDir(relativePath, basePaths.value)
    }

    override suspend fun buildShareIntent(videoItemId: Long): Intent? =
        fileOps.buildShareIntent(videoItemId)

    override fun markFolderRead(folderPath: String, read: Boolean) {
        fileOps.markFolderRead(folderPath, read, viewModelScope, _error)
    }

    override fun deleteFolder(path: String) {
        fileOps.deleteFolder(path, basePaths.value, viewModelScope, _error)
    }

    override suspend fun getRecursiveFolderStats(folderPath: String): FolderStats =
        fileOps.getRecursiveFolderStats(folderPath)

    companion object {
        private const val TAG = "SearchViewModel"
    }
}
