package cn.vectory.syncplayer.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.vectory.syncplayer.data.model.BrowseItem
import cn.vectory.syncplayer.data.repository.PlaybackRepository
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.player.PlayerNavigationStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val videoRepository: VideoRepository,
    private val playbackRepository: PlaybackRepository
) : ViewModel() {

    enum class SearchScope { ALL, FILES_ONLY, FOLDERS_ONLY }

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _searchScope = MutableStateFlow(SearchScope.ALL)
    val searchScope: StateFlow<SearchScope> = _searchScope.asStateFlow()

    private val playbackRecords = playbackRepository.getAllFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val allFolderPaths = videoRepository.getAllRelativePaths()
        .map { paths ->
            val folderSet = mutableSetOf<String>()
            for (path in paths) {
                val segments = path.split("/")
                if (segments.size > 1) {
                    for (i in 0 until segments.size - 1) {
                        folderSet.add(segments.subList(0, i + 1).joinToString("/"))
                    }
                }
            }
            folderSet.toList()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val searchResults: StateFlow<List<BrowseItem>> = combine(
        _query.debounce(150),
        _searchScope,
        _query.flatMapLatest { q ->
            if (q.isBlank()) flowOf(emptyList())
            else videoRepository.searchByFileName(q)
        },
        allFolderPaths,
        playbackRecords
    ) { query, scope, fileResults, folders, records ->
        if (query.isBlank()) return@combine emptyList<BrowseItem>()
        buildSearchResults(query, scope, fileResults, folders, records)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private fun buildSearchResults(
        query: String,
        scope: SearchScope,
        fileResults: List<cn.vectory.syncplayer.data.local.entity.VideoItemEntity>,
        folders: List<String>,
        records: List<cn.vectory.syncplayer.data.local.entity.PlaybackRecordEntity>
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
            }.sortedBy { it.name.lowercase() }
            result.addAll(matchingFolders)
        }

        if (scope != SearchScope.FOLDERS_ONLY) {
            val matchingFiles = fileResults.map { video ->
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
            }.sortedBy { it.name.lowercase() }
            result.addAll(matchingFiles)
        }

        return result
    }

    fun setQuery(query: String) { _query.value = query }
    fun setSearchScope(scope: SearchScope) { _searchScope.value = scope }

    fun getSearchResultVideoIds(): List<Long> {
        return searchResults.value
            .filter { !it.isDirectory && it.videoItemId != null }
            .mapNotNull { it.videoItemId }
    }

    fun setFolderVideoIdsForNavigation(ids: List<Long>) {
        PlayerNavigationStore.folderVideoIds = ids
    }
}
