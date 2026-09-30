package cn.vectory.syncplayer.browser

import android.util.Log
import cn.vectory.syncplayer.browser.BrowserViewModel.BrowseFilter
import cn.vectory.syncplayer.browser.BrowserViewModel.BrowseMode
import cn.vectory.syncplayer.browser.BrowserViewModel.SortMode
import cn.vectory.syncplayer.browser.BrowserViewModel.StarFilterMode
import cn.vectory.syncplayer.browser.BrowserViewModel.ViewMode
import cn.vectory.syncplayer.data.local.entity.PlaybackRecordEntity
import cn.vectory.syncplayer.data.model.BrowseItem
import cn.vectory.syncplayer.data.model.SyncRelationWithSource
import cn.vectory.syncplayer.data.repository.PlaybackRepository
import cn.vectory.syncplayer.data.repository.SyncRelationRepository
import cn.vectory.syncplayer.data.repository.UserPreferencesRepository
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.ui.components.DirectoryNode
import cn.vectory.syncplayer.ui.util.fileNameComparator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the browser content pipeline previously embedded in [BrowserViewModel]:
 * folder / filter / sort / star / view state, the Flow chain that turns DAO rows into
 * [BrowseItem] lists, and the hydration + persistence of the five browser preferences.
 *
 * This is an equivalence extraction (remediation plan Task 8): every flow, setter and
 * query below is a verbatim port of the original ViewModel code, with exactly two
 * mechanical substitutions — `viewModelScope` becomes the injected [scope] (the
 * ViewModel passes its own viewModelScope, so sharing/cancellation semantics are
 * unchanged) and `Dispatchers.IO` becomes the injected [ioDispatcher] (overridable in
 * tests; production keeps the IO default).
 *
 * Contract:
 * - The selection enums stay nested on [BrowserViewModel] for UI compatibility; this
 *   class references them nominally only and never holds or depends on a ViewModel
 *   instance (androidx.lifecycle is not on this class's dependency set).
 * - No mutable flow is exposed: every public member is a read-only StateFlow, a
 *   plain method, or the [basePathsSnapshot] read-only accessor.
 * - The error channel ([BrowserViewModel.error]) and scroll-position cache intentionally
 *   remain in the ViewModel — they belong to its file-operation callbacks, not content.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
internal class BrowserContentCoordinator(
    private val videoRepository: VideoRepository,
    private val playbackRepository: PlaybackRepository,
    private val syncRelationRepository: SyncRelationRepository,
    private val userPrefs: UserPreferencesRepository,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private val _currentFolder = MutableStateFlow("")
    val currentFolder: StateFlow<String> = _currentFolder.asStateFlow()

    private val _sortMode = MutableStateFlow(SortMode.FILENAME_ASC)
    val sortMode: StateFlow<SortMode> = _sortMode.asStateFlow()

    private val _filter = MutableStateFlow(BrowseFilter.ALL)
    val filter: StateFlow<BrowseFilter> = _filter.asStateFlow()

    // Star and browse mode related state
    private val _starFilterMode = MutableStateFlow(StarFilterMode.OFF)
    val starFilterMode: StateFlow<StarFilterMode> = _starFilterMode.asStateFlow()

    private val _starFilterValue = MutableStateFlow(0)
    val starFilterValue: StateFlow<Int> = _starFilterValue.asStateFlow()

    private val _browseMode = MutableStateFlow(BrowseMode.DIRECTORY)
    val browseMode: StateFlow<BrowseMode> = _browseMode.asStateFlow()

    private val _flatTotalCount = MutableStateFlow(0)
    val flatTotalCount: StateFlow<Int> = _flatTotalCount.asStateFlow()

    private val _viewMode = MutableStateFlow(ViewMode.LIST)
    val viewMode: StateFlow<ViewMode> = _viewMode.asStateFlow()

    private val _gridColumns = MutableStateFlow(3)
    val gridColumns: StateFlow<Int> = _gridColumns.asStateFlow()

    private val _refreshTrigger = MutableStateFlow(0L)

    private val effectiveFolder = combine(_currentFolder, _refreshTrigger) { folder, _ -> folder }

    private val _browseItemsFolder = MutableStateFlow("")
    val browseItemsFolder: StateFlow<String> = _browseItemsFolder.asStateFlow()

    private val _hiddenByFilterCount = MutableStateFlow(0)
    val hiddenByFilterCount: StateFlow<Int> = _hiddenByFilterCount.asStateFlow()

    init {
        scope.launch {
            _sortMode.value = try {
                SortMode.valueOf(userPrefs.getDefaultSortMode())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                SortMode.FILENAME_ASC
            }
            _filter.value = try {
                BrowseFilter.valueOf(userPrefs.getDefaultFilter().replace("VIDEO_ONLY", "MEDIA_ONLY"))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                BrowseFilter.ALL
            }
            _viewMode.value = try {
                ViewMode.valueOf(userPrefs.getViewMode())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ViewMode.LIST
            }
            _gridColumns.value = try {
                userPrefs.getGridColumns()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                3 // AND-059: keep parity with the default — an unguarded read here crashed the launch.
            }
            _browseMode.value = try {
                BrowseMode.valueOf(userPrefs.getBrowseMode())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                BrowseMode.DIRECTORY
            }
        }
    }

    private val basePaths = syncRelationRepository.getAllWithSourceFlow()
        .stateIn(scope, SharingStarted.Lazily, emptyList())

    /** ID of the first sync relation, used as the sync target in the NEEDS_SYNC card. */
    val firstRelationId: StateFlow<Long?> = basePaths
        .map { it.firstOrNull()?.relation?.id }
        .stateIn(scope, SharingStarted.Lazily, null)

    private val basePathToRelationId = basePaths.map { list ->
        list.associate { it.relation.localBasePath to it.relation.id }
    }.stateIn(scope, SharingStarted.Lazily, emptyMap())

    val currentRelationId: StateFlow<Long?> = combine(effectiveFolder, basePathToRelationId) { folder, pathMap ->
        pathMap.entries.firstOrNull { (basePath, _) ->
            folder == basePath || folder.startsWith("$basePath/")
        }?.value
    }.stateIn(scope, SharingStarted.Lazily, null)

    // Debounce only sort/filter/star (rapid user input). Folder switches are discrete events
    // and must pass through, otherwise every navigation pays a fixed 300ms latency floor.
    private data class FilterBundle(
        val mode: SortMode,
        val filterVal: BrowseFilter,
        val starMode: StarFilterMode,
        val starValue: Int
    )

    @OptIn(FlowPreview::class)
    private val debouncedFilters: Flow<FilterBundle> = combine(
        _sortMode, _filter, _starFilterMode, _starFilterValue
    ) { mode, filterVal, starMode, starValue ->
        FilterBundle(mode, filterVal, starMode, starValue)
    }.debounce(300)

    // flatMapLatest ties records+relations to each folder emission, preventing the
    // "new folder + stale records" race that used to trigger two builds per switch.
    private data class FolderData(
        val folder: String,
        val records: List<PlaybackRecordEntity>,
        val relations: List<SyncRelationWithSource>
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private val folderData: Flow<FolderData> = effectiveFolder.flatMapLatest { folder ->
        combine(
            playbackRepository.getByFolderFlow(folder),
            basePaths
        ) { records, relations ->
            FolderData(folder, records, relations)
        }
    }

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val browseItems: StateFlow<List<BrowseItem>> = combine(
        folderData,
        debouncedFilters,
        _browseMode
    ) { data, filters, browseModeVal ->
        BuildRequest(data, filters, browseModeVal)
    }
    // mapLatest cancels the previous build when a new folder switch arrives, freeing IO and
    // preventing stale results from overwriting fresher ones during rapid navigation.
    .mapLatest { req ->
        val baseDirSet = req.data.relations.map { it.relation.localBasePath }.toSet()
        val output = buildBrowseItems(
            folder = req.data.folder,
            mode = req.filters.mode,
            filterVal = req.filters.filterVal,
            starMode = req.filters.starMode,
            starValue = req.filters.starValue,
            browseModeVal = req.browseModeVal,
            records = req.data.records,
            baseDirSet = baseDirSet
        )
        // Forward the builder's scalar outputs to the StateFlows. flatTotalCount is null in
        // DIRECTORY mode on purpose — preserves the "retain last FILE-mode value" behavior.
        output.flatTotalCount?.let { _flatTotalCount.value = it }
        _hiddenByFilterCount.value = output.hiddenByFilterCount
        BrowseResult(req.data.folder, output.items)
    }
    // Stamp the folder that produced this list (NOT _currentFolder.value) to avoid scroll-restore
    // misattribution when a slow build completes after the user has already navigated away.
    .onEach { _browseItemsFolder.value = it.folder }
    .map { it.items }
    .stateIn(scope, SharingStarted.Lazily, emptyList())

    private data class BuildRequest(
        val data: FolderData,
        val filters: FilterBundle,
        val browseModeVal: BrowseMode
    )

    private data class BrowseResult(val folder: String, val items: List<BrowseItem>)

    /**
     * Gathers the DAO rows the [BrowseItemBuilder] needs for the current request, then delegates
     * the pure filter/sort/star/folder combination to it. All I/O (DB queries) and all state
     * mutation (the two scalar counts) live here; the builder itself is side-effect free.
     */
    private suspend fun buildBrowseItems(
        folder: String,
        mode: SortMode,
        filterVal: BrowseFilter,
        starMode: StarFilterMode,
        starValue: Int,
        browseModeVal: BrowseMode,
        records: List<PlaybackRecordEntity>,
        baseDirSet: Set<String>
    ): BrowseItemBuilder.Output = withContext(ioDispatcher) {
        // === FILE MODE: flat list, no folders. The DAO applies star/unwatched/media filters
        // and the duration/size ORDER BY; the builder only remaps + filename-sorts. ===
        if (browseModeVal == BrowseMode.FILE) {
            val totalCount = videoRepository.getTotalItemCount()
            val mediaOnly = filterVal == BrowseFilter.MEDIA_ONLY || filterVal == BrowseFilter.UNWATCHED
            val unwatchedOnly = filterVal == BrowseFilter.UNWATCHED
            val sortCol = when (mode) {
                SortMode.DURATION_ASC -> "duration_asc"
                SortMode.DURATION_DESC -> "duration_desc"
                SortMode.FILE_SIZE_ASC -> "fileSize_asc"
                SortMode.FILE_SIZE_DESC -> "fileSize_desc"
                else -> ""
            }
            val dbItems = videoRepository.getFlatItemsFiltered(
                mediaOnly = mediaOnly,
                unwatchedOnly = unwatchedOnly,
                starMode = starMode.name,
                starValue = starValue,
                sortCol = sortCol
            )
            return@withContext BrowseItemBuilder.build(
                BrowseItemBuilder.Input(
                    folder = folder,
                    sortMode = mode,
                    filter = filterVal,
                    starMode = starMode,
                    starValue = starValue,
                    browseMode = browseModeVal,
                    flatTotalCount = totalCount,
                    flatItems = dbItems,
                    subFolders = emptyList(),
                    itemsInFolder = emptyList(),
                    childVideoStats = emptyList(),
                    folderHasSubDirs = emptyMap(),
                    records = records,
                    baseDirSet = baseDirSet
                )
            )
        }

        // === DIRECTORY MODE: DB is the sole truth source (no physical disk scan) ===
        val dbFolderNames = videoRepository.getSubFolders(folder).first()
        val videoEntities = videoRepository.getItemsInFolder(folder).first()
        val childStats = videoRepository.getChildVideoStats(folder)
        // Subfolder presence: one deep-parentPath query, computed in memory (no N+1). Sort order
        // of directChildNames does not affect the resulting map, but matches the original call.
        val allFolderNames = dbFolderNames.sortedWith(fileNameComparator)
        val folderHasSubDirs = videoRepository.getDirectChildFolderHasSubFolders(folder, allFolderNames)

        BrowseItemBuilder.build(
            BrowseItemBuilder.Input(
                folder = folder,
                sortMode = mode,
                filter = filterVal,
                starMode = starMode,
                starValue = starValue,
                browseMode = browseModeVal,
                flatTotalCount = 0,
                flatItems = emptyList(),
                subFolders = dbFolderNames,
                itemsInFolder = videoEntities,
                childVideoStats = childStats,
                folderHasSubDirs = folderHasSubDirs,
                records = records,
                baseDirSet = baseDirSet
            )
        )
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
        scope.launch {
            try {
                userPrefs.setDefaultSortMode(mode.name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save sort mode", e)
            }
        }
    }

    fun setFilter(filter: BrowseFilter) {
        _filter.value = filter
        scope.launch {
            try {
                userPrefs.setDefaultFilter(filter.name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save filter", e)
            }
        }
    }

    fun setViewMode(mode: ViewMode) {
        _viewMode.value = mode
        scope.launch {
            try {
                userPrefs.setViewMode(mode.name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save view mode", e)
            }
        }
    }

    fun setGridColumns(columns: Int) {
        _gridColumns.value = columns.coerceIn(1, 5)
        scope.launch {
            try {
                userPrefs.setGridColumns(columns)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save grid columns", e)
            }
        }
    }

    // Star filter controls
    fun setStarFilterMode(mode: StarFilterMode) {
        _starFilterMode.value = mode
    }

    fun setStarFilterValue(value: Int) {
        _starFilterValue.value = value
    }

    fun setBrowseMode(mode: BrowseMode) {
        _browseMode.value = mode
        scope.launch {
            try {
                userPrefs.setBrowseMode(mode.name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save browse mode", e)
            }
        }
    }

    /** Intent-style refresh: re-runs every query tied to the current folder. */
    fun refresh() {
        _refreshTrigger.value++
    }

    /** Read-only snapshot of the current sync-relation base paths. */
    fun basePathsSnapshot(): List<SyncRelationWithSource> = basePaths.value

    /**
     * Reads subfolders for the picker and wraps them in [DirectoryNode]s. The Room query
     * is I/O and therefore runs on the injected [ioDispatcher] (F07: was hardwired to
     * Dispatchers.Default, which starves the shared Default pool with DB waits). The
     * trailing name-sort + node mapping stays in the same block — it is O(children) and
     * negligible next to the query it follows.
     */
    suspend fun loadSubFolders(folderPath: String): List<DirectoryNode> = withContext(ioDispatcher) {
        val subDirs = videoRepository.getSubFoldersOnce(folderPath)
        val basePathsSet = basePaths.value.map { it.relation.localBasePath }.toSet()
        subDirs.sortedWith(fileNameComparator).map { name ->
            val fullPath = if (folderPath.isEmpty()) name else "$folderPath/$name"
            DirectoryNode(
                name = name,
                fullPath = fullPath,
                isSyncBaseDir = basePathsSet.contains(fullPath)
            )
        }
    }

    companion object {
        private const val TAG = "BrowserContentCoordinator"
    }
}
