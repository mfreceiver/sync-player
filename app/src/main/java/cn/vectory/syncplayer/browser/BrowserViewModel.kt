package cn.vectory.syncplayer.browser

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.vectory.syncplayer.data.local.entity.PlaybackRecordEntity
import cn.vectory.syncplayer.data.model.BrowseItem
import cn.vectory.syncplayer.data.model.FolderStats
import cn.vectory.syncplayer.data.repository.PlaybackRepository
import cn.vectory.syncplayer.data.repository.SyncRelationRepository
import cn.vectory.syncplayer.data.repository.SyncSourceRepository
import cn.vectory.syncplayer.data.repository.UserPreferencesRepository
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.data.model.SyncRelationWithSource
import cn.vectory.syncplayer.R
import cn.vectory.syncplayer.sync.syncErrorMessage
import cn.vectory.syncplayer.ui.util.isVideoFile
import cn.vectory.syncplayer.ui.util.isImageFile
import cn.vectory.syncplayer.ui.util.fileNameComparator
import cn.vectory.syncplayer.ui.components.DirectoryNode
import cn.vectory.syncplayer.ui.components.FileContextMenuActions
import cn.vectory.syncplayer.ui.components.FolderContextMenuActions
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class BrowserViewModel @Inject constructor(
    private val videoRepository: VideoRepository,
    private val playbackRepository: PlaybackRepository,
    private val syncRelationRepository: SyncRelationRepository,
    private val syncSourceRepository: SyncSourceRepository,
    private val userPrefs: UserPreferencesRepository,
    private val fileOps: FileOperationsUseCase,
    private val undoDeleteUseCase: UndoDeleteUseCase,
    @ApplicationContext private val context: Context
) : ViewModel(), FileContextMenuActions, FolderContextMenuActions {

    enum class SortMode { FILENAME_ASC, FILENAME_DESC, DURATION_ASC, DURATION_DESC, FILE_SIZE_ASC, FILE_SIZE_DESC }
    enum class BrowseFilter { ALL, MEDIA_ONLY, UNWATCHED }
    enum class ViewMode { LIST, GRID, STAGGERED_GRID }
    // Star-based filtering for videos
    enum class StarFilterMode { OFF, EQ, GT, LT }
    // Browse mode controls how items are presented
    enum class BrowseMode { DIRECTORY, FILE }

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

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _refreshTrigger = MutableStateFlow(0L)

    private val effectiveFolder = combine(_currentFolder, _refreshTrigger) { folder, _ -> folder }

    private val _scrollPositions = mutableMapOf<String, Pair<Int, Int>>()

    private val _browseItemsFolder = MutableStateFlow("")
    val browseItemsFolder: StateFlow<String> = _browseItemsFolder.asStateFlow()

    private val _hiddenByFilterCount = MutableStateFlow(0)
    val hiddenByFilterCount: StateFlow<Int> = _hiddenByFilterCount.asStateFlow()

    fun saveScrollPosition(folder: String, firstVisibleItemIndex: Int, firstVisibleItemScrollOffset: Int) {
        _scrollPositions[folder] = Pair(firstVisibleItemIndex, firstVisibleItemScrollOffset)
    }

    fun getScrollPosition(folder: String): Pair<Int, Int>? = _scrollPositions[folder]

    fun clearScrollPosition(folder: String) {
        _scrollPositions.remove(folder)
    }

    init {
        viewModelScope.launch {
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
            _gridColumns.value = userPrefs.getGridColumns()
            _browseMode.value = try {
                BrowseMode.valueOf(userPrefs.getBrowseMode())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                BrowseMode.DIRECTORY
            }
        }
    }



    val hasConfig: StateFlow<Boolean> = syncRelationRepository.getAllWithSourceFlow()
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Lazily, false)

    /**
     * Coarse onboarding state shown as a guidance card on the browser root when the
     * library is empty. Combines the raw repository Flows (not the [hasConfig]
     * StateFlow) so the initial value stays null until Room has emitted at least
     * once for all three signals — this prevents a one-frame NEEDS_SOURCE flash
     * for users who already have media.
     *
     * Null until first emission; the UI treats null as "do not show card".
     */
    val firstRunState: StateFlow<FirstRunState?> = combine(
        syncSourceRepository.getAllFlow(),
        syncRelationRepository.getAllWithSourceFlow(),
        videoRepository.getAllItemsFlow()
    ) { sources, relations, media ->
        deriveFirstRunState(
            hasSource = sources.isNotEmpty(),
            hasRelation = relations.isNotEmpty(),
            hasMedia = media.isNotEmpty()
        )
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    /** Whether the user has dismissed the first-run card. Persisted across launches. */
    val onboardingDismissed: StateFlow<Boolean> = userPrefs.onboardingDismissed
        .stateIn(viewModelScope, SharingStarted.Lazily, false)

    /** Persist the dismissal so the card does not reappear on next launch. */
    fun dismissOnboarding() {
        viewModelScope.launch {
            try {
                userPrefs.setOnboardingDismissed(true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist onboarding dismissal", e)
            }
        }
    }

    /** Reset the dismissal flag so the card can be re-shown from a settings entry. */
    fun resetOnboardingDismissed() {
        viewModelScope.launch {
            try {
                userPrefs.setOnboardingDismissed(false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reset onboarding dismissal", e)
            }
        }
    }

    // Playback records will be loaded on-demand per-folder to avoid loading all into memory
    // private val playbackRecords removed in favor of per-folder queries

    private val basePaths = syncRelationRepository.getAllWithSourceFlow()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** ID of the first sync relation, used as the sync target in the NEEDS_SYNC card. */
    val firstRelationId: StateFlow<Long?> = basePaths
        .map { it.firstOrNull()?.relation?.id }
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    private val basePathToRelationId = basePaths.map { list ->
        list.associate { it.relation.localBasePath to it.relation.id }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyMap())

    val currentRelationId: StateFlow<Long?> = combine(effectiveFolder, basePathToRelationId) { folder, pathMap ->
        pathMap.entries.firstOrNull { (basePath, _) ->
            folder == basePath || folder.startsWith("$basePath/")
        }?.value
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)

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
    .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

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
     *
     * Kept as a thin orchestration shell so the Flow chain above (debounce / flatMapLatest /
     * mapLatest / stateIn timing) is unchanged — only the in-memory build logic moved out.
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
    ): BrowseItemBuilder.Output = withContext(Dispatchers.IO) {
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
        viewModelScope.launch {
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
        viewModelScope.launch {
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
        viewModelScope.launch {
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
        viewModelScope.launch {
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
        viewModelScope.launch {
            try {
                userPrefs.setBrowseMode(mode.name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save browse mode", e)
            }
        }
    }

    override fun updateStar(videoItemId: Long, star: Int) {
        fileOps.updateStar(videoItemId, star, viewModelScope, _error) { _refreshTrigger.value++ }
    }

    override fun updateStarByPath(relativePath: String, star: Int) {
        fileOps.updateStarByPath(relativePath, star, viewModelScope, _error) { _refreshTrigger.value++ }
    }

    override suspend fun getRecursiveFolderStats(folderPath: String): FolderStats =
        fileOps.getRecursiveFolderStats(folderPath)

    suspend fun loadSubFolders(folderPath: String): List<DirectoryNode> = withContext(Dispatchers.Default) {
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

    override fun markFolderRead(folderPath: String, read: Boolean) {
        fileOps.markFolderRead(folderPath, read, viewModelScope, _error) { _refreshTrigger.value++ }
    }

    override fun deleteFolder(path: String) {
        fileOps.deleteFolder(path, basePaths.value, viewModelScope, _error) { _refreshTrigger.value++ }
    }

    fun clearError() {
        _error.value = null
    }

    fun getFolderVideoIds(includeItem: BrowseItem? = null, includeId: Long = -1L): List<Long> {
        return browseItems.value
            .filter { !it.isDirectory && isVideoFile(it.name) }
            .mapNotNull { item ->
                when {
                    item.videoItemId != null -> item.videoItemId
                    includeItem != null && item.relativePath == includeItem.relativePath && includeId > 0L -> includeId
                    else -> null
                }
            }
    }

    fun getFolderImageIds(includeItem: BrowseItem? = null, includeId: Long = -1L): List<Long> {
        return browseItems.value
            .filter { !it.isDirectory && isImageFile(it.name) }
            .mapNotNull { item ->
                when {
                    item.videoItemId != null -> item.videoItemId
                    includeItem != null && item.relativePath == includeItem.relativePath && includeId > 0L -> includeId
                    else -> null
                }
            }
    }

    override fun toggleTrashed(videoItemId: Long) {
        undoDeleteUseCase.performTrashToggle(videoItemId, basePaths.value, viewModelScope, _error)
    }

    /**
     * Trash-toggle with an [onSuccess] callback invoked ONLY after the use-case has performed
     * the toggle and recorded undo state. The caller (BrowserScreen) uses this to defer showing
     * the undo Snackbar until the async toggle actually succeeds — so a failed toggle never
     * produces a misleading "moved to trash" Snackbar. On failure the error lands in [_error].
     */
    fun toggleTrashedWithUndo(videoItemId: Long, onSuccess: () -> Unit) {
        undoDeleteUseCase.performTrashToggle(videoItemId, basePaths.value, viewModelScope, _error, onSuccess)
    }

    override fun isFileInBaseDir(relativePath: String): Boolean {
        return fileOps.isFileInBaseDir(relativePath, basePaths.value)
    }

    override fun deleteFile(videoItemId: Long) {
        undoDeleteUseCase.performHardDelete(videoItemId, viewModelScope, _error) { _refreshTrigger.value++ }
    }

    /**
     * Deferred hard-delete with an [onSuccess] callback invoked ONLY after the use-case has
     * recorded the pending delete and bumped the refresh trigger. The caller defers the undo
     * Snackbar until then, so a delete that fails before recording state never shows a
     * "deleted" Snackbar.
     */
    fun deleteFileWithUndo(videoItemId: Long, onSuccess: () -> Unit) {
        undoDeleteUseCase.performHardDelete(videoItemId, viewModelScope, _error) {
            _refreshTrigger.value++
            onSuccess()
        }
    }

    /**
     * Undo the most recent delete operation (trash toggle or deferred hard delete).
     * @param expectedId If provided, only undo if the current pending operation matches this id.
     * Returns the result so the caller can show appropriate feedback.
     */
    suspend fun undoDelete(expectedId: Long? = null): UndoResult = undoDeleteUseCase.undo(expectedId)

    /**
     * Commit the deferred hard delete when the undo snackbar expires without action.
     *
     * If the commit fails, the [UndoResult.Failed] is mapped to a localized message in [_error]
     * (surfaced via the error Snackbar) — a failed commit must never be silently swallowed.
     *
     * @param expectedId If provided, only commit if the current pending operation matches this id.
     * No-op if the last operation was a trash toggle (already applied).
     */
    suspend fun commitDeferredDelete(expectedId: Long? = null): UndoResult {
        val result = undoDeleteUseCase.commitDeferredDelete(expectedId)
        if (result is UndoResult.Failed) {
            _error.value = syncErrorMessage(result.error, context)
        }
        return result
    }

    /**
     * Page-leave safety commit: fire-and-forget on the use-case's own safety scope, so a
     * deferred hard delete is never silently lost when the BrowserScreen composition disposes
     * (snackbar cancelled / navigation away) before the snackbar's Dismissed branch ran.
     *
     * [expectedId] scopes the commit to the specific pending delete observed at dispose time,
     * so a stale dispose cannot commit a newer operation.
     */
    fun commitDeferredDeleteSafely(expectedId: Long) {
        undoDeleteUseCase.commitPendingSafely(expectedId)
    }

    override suspend fun buildShareIntent(videoItemId: Long): Intent? =
        fileOps.buildShareIntent(videoItemId)

    /**
     * True when the entire library has no media (regardless of current filter/folder).
     * Used by onboarding to avoid showing the guidance card when the current view is
     * merely filtered empty while media exists elsewhere.
     */
    val libraryEmpty: StateFlow<Boolean> = videoRepository.getAllItemsFlow()
        .map { it.isEmpty() }
        .stateIn(viewModelScope, SharingStarted.Lazily, true)

    /**
     * On ViewModel clear (page leave / fragment destroy), commit any pending deferred
     * hard delete so it is never silently lost.
     *
     * NOTE: this MUST NOT launch into [viewModelScope] — by the time `onCleared()` runs,
     * that scope is already cancelled and a launch into it is a silent no-op (the pending
     * hard delete would leak forever). [UndoDeleteUseCase.commitPendingSafely] uses its
     * own owned safety scope + [NonCancellable] so the commit runs regardless.
     */
    override fun onCleared() {
        super.onCleared()
        undoDeleteUseCase.commitPendingSafely()
    }

    companion object {
        private const val TAG = "BrowserViewModel"
    }
}
