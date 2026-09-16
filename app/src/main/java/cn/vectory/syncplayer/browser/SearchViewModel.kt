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
import java.util.concurrent.atomic.AtomicLong
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

    private val errorSeq = AtomicLong(0L)
    private val _error = MutableStateFlow<ErrorEvent?>(null)
    val error: StateFlow<ErrorEvent?> = _error.asStateFlow()

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
    val searchResults: StateFlow<List<BrowseItem>> = _query
        .debounce(300)
        .flatMapLatest { query ->
            if (query.isBlank()) {
                // 空 query 短路：不建管道、不触发 repo 查询，直接清空结果。
                flowOf(emptyList())
            } else {
                // F03 单管道化：同一防抖后的 query 捕获进整个下游——文件结果、
                // 文件夹过滤、scope 全部绑定同一个 query；flatMapLatest 天然取消
                // 旧查询管道，杜绝「query A 的状态配 query B 的结果」交叉配对。
                combine(
                    videoRepository.searchByFileNameInFolder(query, searchFolder),
                    scopedFolderPaths,
                    _searchScope
                ) { fileResults, folders, scope ->
                    buildSearchResults(query, scope, fileResults, folders)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
    fun showError(message: String) {
        _error.update { ErrorEvent(message, errorSeq.incrementAndGet()) }
    }

    fun clearError(expectedSeq: Long? = null) {
        // AND-068: seq-guarded clear — see BrowserViewModel.clearError.
        _error.update { if (expectedSeq == null || it?.seq == expectedSeq) null else it }
    }

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
        fileOps.updateStar(videoItemId, star, viewModelScope, ::showError)
    }

    override fun toggleTrashed(videoItemId: Long) {
        fileOps.toggleTrashed(videoItemId, basePaths.value, viewModelScope, ::showError)
    }

    override fun deleteFile(videoItemId: Long) {
        fileOps.deleteFile(videoItemId, viewModelScope, ::showError)
    }

    override fun isFileInBaseDir(relativePath: String): Boolean {
        return fileOps.isFileInBaseDir(relativePath, basePaths.value)
    }

    override suspend fun buildShareIntent(videoItemId: Long): Intent? =
        fileOps.buildShareIntent(videoItemId)

    override fun markFolderRead(folderPath: String, read: Boolean) {
        fileOps.markFolderRead(folderPath, read, viewModelScope, ::showError)
    }

    override fun deleteFolder(path: String) {
        fileOps.deleteFolder(path, basePaths.value, viewModelScope, ::showError)
    }

    override suspend fun getRecursiveFolderStats(folderPath: String): FolderStats =
        fileOps.getRecursiveFolderStats(folderPath)

    companion object {
        private const val TAG = "SearchViewModel"
    }
}
