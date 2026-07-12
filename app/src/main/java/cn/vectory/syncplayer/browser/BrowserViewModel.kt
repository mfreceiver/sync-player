package cn.vectory.syncplayer.browser

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.vectory.syncplayer.data.local.entity.PlaybackRecordEntity
import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import cn.vectory.syncplayer.data.model.BrowseItem
import cn.vectory.syncplayer.data.model.FolderStats
import cn.vectory.syncplayer.data.repository.PlaybackRepository
import cn.vectory.syncplayer.data.repository.SyncRelationRepository
import cn.vectory.syncplayer.data.repository.UserPreferencesRepository
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.data.model.SyncRelationWithSource
import cn.vectory.syncplayer.storage.StoragePaths
import cn.vectory.syncplayer.sync.VideoMetadataExtractor
import cn.vectory.syncplayer.sync.ImageMetadataExtractor
import cn.vectory.syncplayer.R
import cn.vectory.syncplayer.ui.util.extractParentPath
import cn.vectory.syncplayer.ui.util.isVideoFile
import cn.vectory.syncplayer.ui.util.isImageFile
import cn.vectory.syncplayer.ui.util.isMediaFile
import cn.vectory.syncplayer.ui.util.fileNameComparator
import cn.vectory.syncplayer.ui.components.DirectoryNode
import cn.vectory.syncplayer.ui.components.FileContextMenuActions
import cn.vectory.syncplayer.ui.components.FolderContextMenuActions
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class BrowserViewModel @Inject constructor(
    private val videoRepository: VideoRepository,
    private val playbackRepository: PlaybackRepository,
    private val syncRelationRepository: SyncRelationRepository,
    private val videoMetadataExtractor: VideoMetadataExtractor,
    private val imageMetadataExtractor: ImageMetadataExtractor,
    private val storagePaths: StoragePaths,
    private val userPrefs: UserPreferencesRepository,
    private val fileOps: FileOperationsUseCase,
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
            } catch (_: Exception) {
                SortMode.FILENAME_ASC
            }
            _filter.value = try {
                BrowseFilter.valueOf(userPrefs.getDefaultFilter().replace("VIDEO_ONLY", "MEDIA_ONLY"))
            } catch (_: Exception) {
                BrowseFilter.ALL
            }
            _viewMode.value = try {
                ViewMode.valueOf(userPrefs.getViewMode())
            } catch (_: Exception) {
                ViewMode.LIST
            }
            _gridColumns.value = userPrefs.getGridColumns()
            _browseMode.value = try {
                BrowseMode.valueOf(userPrefs.getBrowseMode())
            } catch (_: Exception) {
                BrowseMode.DIRECTORY
            }
        }
    }

    // Trigger thumbnail repair on folder change, not on sort/filter changes
    init {
        viewModelScope.launch {
            effectiveFolder.collectLatest { folder ->
                try {
                    if (folder.isNotEmpty()) {
                        withContext(Dispatchers.IO) {
                            val videos = videoRepository.getItemsInFolder(folder).first()
                            repairMissingThumbnails(videos)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Thumbnail repair failed", e)
                }
            }
        }
    }

    val hasConfig: StateFlow<Boolean> = syncRelationRepository.getAllWithSourceFlow()
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Lazily, false)

    // Playback records will be loaded on-demand per-folder to avoid loading all into memory
    // private val playbackRecords removed in favor of per-folder queries

    private val basePaths = syncRelationRepository.getAllWithSourceFlow()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

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
        val items = buildBrowseItems(
            req.data.folder, req.filters.mode, req.filters.filterVal, req.filters.starMode, req.filters.starValue,
            req.browseModeVal, req.data.records, baseDirSet
        )
        BrowseResult(req.data.folder, items)
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

    private suspend fun buildBrowseItems(
        folder: String,
        mode: SortMode,
        filterVal: BrowseFilter,
        starMode: StarFilterMode,
        starValue: Int,
        browseModeVal: BrowseMode,
        records: List<PlaybackRecordEntity>,
        baseDirSet: Set<String>
    ): List<BrowseItem> = withContext(Dispatchers.IO) {
        // === FILE MODE: flat list, no folders ===
        if (browseModeVal == BrowseMode.FILE) {
            val totalCount = videoRepository.getTotalItemCount()
            _flatTotalCount.value = totalCount

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

            val result = dbItems.map { item ->
                BrowseItem(
                    videoItemId = item.id,
                    name = item.fileName,
                    relativePath = item.relativePath,
                    isDirectory = false,
                    fileSize = item.fileSize,
                    duration = item.duration,
                    width = item.width,
                    height = item.height,
                    bitrate = item.bitrate,
                    frameRate = item.frameRate,
                    thumbnailPath = item.thumbnailPath,
                    playbackPosition = item.playbackPosition,
                    playbackDuration = item.playbackDuration,
                    isCompleted = item.isCompleted,
                    isTrashed = item.isTrashed,
                    hasGainAdjusted = item.audioGainDb != null,
                    star = item.star,
                    mediaType = item.mediaType,
                    dateTaken = item.dateTaken
                )
            }

            val sorted = when (mode) {
                SortMode.FILENAME_ASC -> result.sortedWith(compareBy(fileNameComparator) { it.name })
                SortMode.FILENAME_DESC -> result.sortedWith(compareBy(fileNameComparator) { it.name }).asReversed()
                else -> result
            }

            _hiddenByFilterCount.value = (totalCount - sorted.size).coerceAtLeast(0)

            return@withContext sorted
        }

        // === DIRECTORY MODE: original logic with star filtering added ===
        val dbFolderNames = videoRepository.getSubFolders(folder).first()

        val recordMap = records.associateBy { it.videoItemId }

        val physicalDir = if (folder.isEmpty()) storagePaths.userdataRoot else File(storagePaths.userdataRoot, folder)
        val physicalFolderNames = physicalDir.listFiles()
            ?.filter { it.isDirectory }
            ?.map { it.name }
            ?.toSet()
            ?: emptySet()

        val allFolderNames = (dbFolderNames.toSet() + physicalFolderNames).sortedWith(fileNameComparator)

        val folderHasSubDirs = allFolderNames.associateWith { folderName ->
            val fullPath = if (folder.isEmpty()) folderName else "$folder/$folderName"
            val childDir = File(storagePaths.userdataRoot, fullPath)
            childDir.isDirectory && childDir.listFiles()?.any { it.isDirectory } == true
        }

        val videoEntities = videoRepository.getItemsInFolder(folder).first()
        val dbVideoPaths = videoEntities.map { it.relativePath }.toSet()

        val diskOnlyItems = run {
            val physicalDir = if (folder.isEmpty()) storagePaths.userdataRoot else File(storagePaths.userdataRoot, folder)
            val filesOnDisk = physicalDir.listFiles()?.filter { it.isFile } ?: emptyList()
            filesOnDisk.filter { file ->
                val relPath = if (folder.isEmpty()) file.name else "$folder/${file.name}"
                relPath !in dbVideoPaths && !file.name.startsWith(".") && !file.name.endsWith(".sync_tmp")
            }.map { file ->
                val relPath = if (folder.isEmpty()) file.name else "$folder/${file.name}"
                BrowseItem(
                    videoItemId = null,
                    name = file.name,
                    relativePath = relPath,
                    isDirectory = false,
                    fileSize = file.length()
                )
            }
        }

        val childStats = videoRepository.getChildVideoStats(folder)
        val subFolderStatsMap = childStats
            .filter { isVideoFile(it.fileName) }
            .groupBy { stat ->
                val afterFolder = if (folder.isEmpty()) stat.relativePath
                    else stat.relativePath.substring(folder.length + 1)
                afterFolder.substringBefore('/')
            }

        val childThumbnailsBatch = videoRepository.getDirectChildThumbnailsBatch(folder)
        val folderThumbnailsMap = childThumbnailsBatch.groupBy { row ->
            val afterFolder = if (folder.isEmpty()) row.relativePath else row.relativePath.substring(folder.length + 1)
            afterFolder.substringBefore('/')
        }.mapValues { (_, rows) -> rows.map { it.thumbnailPath }.take(9) }

        val folders = allFolderNames.map { folderName ->
            val fullPath = if (folder.isEmpty()) folderName else "$folder/$folderName"
            val stats = subFolderStatsMap[folderName]
            val vidCount = stats?.size ?: 0
            val playedCount = stats?.count { it.position > 0 || it.isCompleted } ?: 0
            val trashedCount = stats?.count { it.isTrashed } ?: 0
            val thumbs = folderThumbnailsMap[folderName].orEmpty()
            BrowseItem(
                videoItemId = null,
                name = folderName,
                relativePath = fullPath,
                isDirectory = true,
                isSyncBaseDir = baseDirSet.contains(fullPath),
                containsSyncBaseDir = baseDirSet.any { it.startsWith("$fullPath/") },
                isInSyncBaseDir = baseDirSet.any { fullPath.startsWith("$it/") },
                videoCount = vidCount,
                playedCount = playedCount,
                trashedCount = trashedCount,
                thumbnailPaths = thumbs
            )
        }

        val videos = videoEntities
            .map { video ->
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
                    hasGainAdjusted = video.audioGainDb != null,
                    star = video.star,
                    mediaType = video.mediaType,
                    dateTaken = video.dateTaken
                )
            }

        val allVideos = videos + diskOnlyItems

        val imageFiles = allVideos.filter { isImageFile(it.name) }
        val videoFiles = allVideos.filter { isVideoFile(it.name) }
        val otherFiles = allVideos.filter { !isVideoFile(it.name) && !isImageFile(it.name) }

        val filteredVideos = when (filterVal) {
            BrowseFilter.ALL -> videoFiles
            BrowseFilter.MEDIA_ONLY -> videoFiles
            BrowseFilter.UNWATCHED -> videoFiles.filter {
                it.playbackPosition == 0L && it.playbackDuration == 0L && !it.isCompleted
            }
        }
        val filteredImages = when (filterVal) {
            BrowseFilter.ALL -> imageFiles
            BrowseFilter.MEDIA_ONLY -> imageFiles
            BrowseFilter.UNWATCHED -> imageFiles.filter {
                it.playbackPosition == 0L && it.playbackDuration == 0L && !it.isCompleted
            }
        }

        val starFilteredVideos = when (starMode) {
            StarFilterMode.OFF -> filteredVideos
            StarFilterMode.EQ -> filteredVideos.filter { it.star == starValue }
            StarFilterMode.GT -> filteredVideos.filter { it.star > starValue }
            StarFilterMode.LT -> filteredVideos.filter { it.star < starValue }
        }
        val starFilteredImages = when (starMode) {
            StarFilterMode.OFF -> filteredImages
            StarFilterMode.EQ -> filteredImages.filter { it.star == starValue }
            StarFilterMode.GT -> filteredImages.filter { it.star > starValue }
            StarFilterMode.LT -> filteredImages.filter { it.star < starValue }
        }

        val sortedFolders = when (mode) {
            SortMode.FILENAME_ASC -> folders.sortedWith(compareBy(fileNameComparator) { it.name })
            SortMode.FILENAME_DESC -> folders.sortedWith(compareBy(fileNameComparator) { it.name }).asReversed()
            else -> folders.sortedWith(compareBy(fileNameComparator) { it.name })
        }
        val sortedNonVideos = otherFiles.sortedWith(compareBy(fileNameComparator) { it.name })
        val sortedVideos = when (mode) {
            SortMode.FILENAME_ASC -> starFilteredVideos.sortedWith(compareBy(fileNameComparator) { it.name })
            SortMode.FILENAME_DESC -> starFilteredVideos.sortedWith(compareBy(fileNameComparator) { it.name }).asReversed()
            SortMode.DURATION_ASC -> starFilteredVideos.sortedBy { it.duration ?: 0L }
            SortMode.DURATION_DESC -> starFilteredVideos.sortedByDescending { it.duration ?: 0L }
            SortMode.FILE_SIZE_ASC -> starFilteredVideos.sortedBy { it.fileSize }
            SortMode.FILE_SIZE_DESC -> starFilteredVideos.sortedByDescending { it.fileSize }
        }
        val sortedImages = when (mode) {
            SortMode.FILENAME_ASC -> starFilteredImages.sortedWith(compareBy(fileNameComparator) { it.name })
            SortMode.FILENAME_DESC -> starFilteredImages.sortedWith(compareBy(fileNameComparator) { it.name }).asReversed()
            SortMode.DURATION_ASC -> starFilteredImages.sortedBy { it.dateTaken ?: 0L }
            SortMode.DURATION_DESC -> starFilteredImages.sortedByDescending { it.dateTaken ?: 0L }
            SortMode.FILE_SIZE_ASC -> starFilteredImages.sortedBy { it.fileSize }
            SortMode.FILE_SIZE_DESC -> starFilteredImages.sortedByDescending { it.fileSize }
        }

        val totalBeforeFilter = sortedFolders.size + allVideos.size
        val filteredFolders = if (filterVal == BrowseFilter.UNWATCHED) {
            sortedFolders.filter { folderItem ->
                val hasSubDirs = folderHasSubDirs[folderItem.name] ?: true
                !(folderItem.videoCount > 0 && folderItem.playedCount >= folderItem.videoCount && !hasSubDirs)
            }
        } else {
            sortedFolders
        }
        val finalNonVideos = when (filterVal) {
            BrowseFilter.ALL -> sortedNonVideos
            BrowseFilter.MEDIA_ONLY -> emptyList()
            BrowseFilter.UNWATCHED -> emptyList()
        }

        val finalImages = when (filterVal) {
            BrowseFilter.ALL -> sortedImages
            BrowseFilter.MEDIA_ONLY -> sortedImages
            BrowseFilter.UNWATCHED -> sortedImages
        }
        _hiddenByFilterCount.value = (totalBeforeFilter - filteredFolders.size - finalNonVideos.size - finalImages.size - sortedVideos.size).coerceAtLeast(0)

        (filteredFolders + finalNonVideos + finalImages + sortedVideos).distinctBy { it.relativePath }
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

    fun createFolder(parentPath: String, folderName: String) {
        viewModelScope.launch {
            try {
                if (parentPath.isNotEmpty() && isFileInBaseDir(parentPath)) {
                    _error.value = context.getString(R.string.error_cannot_create_in_base_dir)
                    return@launch
                }
                val newDir = if (parentPath.isEmpty()) {
                    File(storagePaths.userdataRoot, folderName)
                } else {
                    File(File(storagePaths.userdataRoot, parentPath), folderName)
                }
                if (newDir.exists()) {
                    _error.value = context.getString(R.string.error_folder_exists)
                    return@launch
                }
                if (!newDir.mkdirs()) {
                    _error.value = context.getString(R.string.error_create_folder_failed)
                    return@launch
                }
                _refreshTrigger.value++
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create folder", e)
                _error.value = e.message
            }
        }
    }

    override fun deleteFolder(path: String) {
        fileOps.deleteFolder(path, basePaths.value, viewModelScope, _error) { _refreshTrigger.value++ }
    }

    fun clearError() {
        _error.value = null
    }

    private fun repairMissingThumbnails(videos: List<VideoItemEntity>) {
        val needsRepair = videos.filter { video ->
            val thumbMissing = video.thumbnailPath == null || !File(video.thumbnailPath).exists()
            thumbMissing && File(video.localAbsolutePath).exists()
        }.take(5)
        if (needsRepair.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                needsRepair.forEach { video ->
                    videoMetadataExtractor.repairThumbnailIfNeeded(video)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Thumbnail repair failed", e)
            }
        }
    }

    /**
     * Ensure a VideoItemEntity exists for the given browsed item. If not, create a minimal one
     * based on the filesystem entry and trigger metadata extraction in background.
     */
    suspend fun ensureVideoRecord(item: BrowseItem): Long {
        // Double-check in case another path created it meanwhile
        val existing = videoRepository.getByRelativePath(item.relativePath)
        if (existing != null) return existing.id

        val file = File(storagePaths.userdataRoot, item.relativePath)
        if (!file.exists()) return -1L

        val entity = VideoItemEntity(
            relativePath = item.relativePath,
            parentPath = extractParentPath(item.relativePath),
            fileName = item.name,
            localAbsolutePath = file.absolutePath,
            fileSize = file.length(),
            syncRelationId = 0,
            mediaType = when {
                isImageFile(item.name) -> "IMAGE"
                isVideoFile(item.name) -> "VIDEO"
                else -> "OTHER"
            }
        )
        val id = videoRepository.upsert(entity)

        val saved = videoRepository.getById(id)
        if (saved != null) {
            withContext(Dispatchers.IO) {
                if (isImageFile(saved.fileName)) imageMetadataExtractor.extractAndSave(saved)
                else videoMetadataExtractor.extractAndSave(saved)
            }
        }
        return id
    }

    /** [includeId] lets the caller include a just-created record before [browseItems] re-emits. */
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
        fileOps.toggleTrashed(videoItemId, basePaths.value, viewModelScope, _error)
    }

    override fun isFileInBaseDir(relativePath: String): Boolean {
        return fileOps.isFileInBaseDir(relativePath, basePaths.value)
    }

    override fun deleteFile(relativePath: String) {
        fileOps.deleteFile(relativePath, viewModelScope, _error) { _refreshTrigger.value++ }
    }

    override suspend fun buildShareIntent(videoItemId: Long): Intent? =
        fileOps.buildShareIntent(videoItemId)

    companion object {
        private const val TAG = "BrowserViewModel"
    }
}
