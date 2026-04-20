package cn.vectory.syncplayer.browser

import android.content.Context
import android.content.Intent
import android.util.Log
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
import cn.vectory.syncplayer.data.model.SyncRelationWithSource
import cn.vectory.syncplayer.storage.StoragePaths
import cn.vectory.syncplayer.sync.VideoMetadataExtractor
import cn.vectory.syncplayer.ui.util.extractParentPath
import cn.vectory.syncplayer.ui.util.getMimeType
import cn.vectory.syncplayer.ui.util.isVideoFile
import cn.vectory.syncplayer.ui.util.fileNameComparator
import cn.vectory.syncplayer.ui.components.DirectoryNode
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
    private val storagePaths: StoragePaths,
    private val userPrefs: UserPreferencesRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    enum class SortMode { FILENAME_ASC, FILENAME_DESC, DURATION_ASC, DURATION_DESC, FILE_SIZE_ASC, FILE_SIZE_DESC }
    enum class BrowseFilter { ALL, VIDEO_ONLY, UNWATCHED }
    enum class ViewMode { LIST, GRID }
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

    private val folderPlaybackRecords = effectiveFolder.flatMapLatest { folder ->
        playbackRepository.getByFolderFlow(folder)
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // Task A: observe all playback records
    private val allPlaybackRecords = playbackRepository.getAllFlow()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

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

    // Helper data wrapper for combined playback records + relations
    private data class PlaybackBundle(
        val records: List<PlaybackRecordEntity>,
        val allRecords: List<PlaybackRecordEntity>,
        val relations: List<SyncRelationWithSource>,
        val browseModeVal: BrowseMode
    )

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val browseItems: StateFlow<List<BrowseItem>> = combine(
        combine(effectiveFolder, _sortMode, _filter) { folder, mode, filterVal ->
            Triple(folder, mode, filterVal)
        },
        // include allPlaybackRecords as an additional trigger source
        combine(folderPlaybackRecords, allPlaybackRecords, basePaths, _browseMode) { records, allRecords, relations, browseModeVal ->
            PlaybackBundle(records, allRecords, relations, browseModeVal)
        }
    ) { (folder, mode, filterVal), bundle ->
        val (records, allRecords, relations, browseModeVal) = bundle
        val baseDirSet = relations.map { it.relation.localBasePath }.toSet()
        val starMode = _starFilterMode.value
        val starValue = _starFilterValue.value
        buildBrowseItems(folder, mode, filterVal, starMode, starValue, browseModeVal, records, allRecords, baseDirSet)
    }
    .debounce(300)
    .onEach { _browseItemsFolder.value = _currentFolder.value }
    .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private suspend fun buildBrowseItems(
        folder: String,
        mode: SortMode,
        filterVal: BrowseFilter,
        starMode: StarFilterMode,
        starValue: Int,
        browseModeVal: BrowseMode,
        records: List<PlaybackRecordEntity>,
        allRecords: List<PlaybackRecordEntity>,
        baseDirSet: Set<String>
    ): List<BrowseItem> = withContext(Dispatchers.IO) {
        // === FILE MODE: flat list, no folders ===
        if (browseModeVal == BrowseMode.FILE) {
            _flatTotalCount.value = videoRepository.getTotalItemCount()
            val videoEntities = videoRepository.getFlatItemsFlow().first()
            // use provided allRecords instead of re-reading from repo
            val recordMap = allRecords.associateBy { it.videoItemId }

            val allVideos = videoEntities.map { video ->
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
                    star = video.star
                )
            }

            val filtered = when (filterVal) {
                BrowseFilter.ALL -> allVideos
                BrowseFilter.VIDEO_ONLY -> allVideos.filter { isVideoFile(it.name) }
                BrowseFilter.UNWATCHED -> allVideos.filter {
                    isVideoFile(it.name) && it.playbackPosition == 0L && it.playbackDuration == 0L && !it.isCompleted
                }
            }

            val starFiltered = when (starMode) {
                StarFilterMode.OFF -> filtered
                StarFilterMode.EQ -> filtered.filter { it.star == starValue }
                StarFilterMode.GT -> filtered.filter { it.star > starValue }
                StarFilterMode.LT -> filtered.filter { it.star < starValue }
            }

            _hiddenByFilterCount.value = (allVideos.size - starFiltered.size).coerceAtLeast(0)

            return@withContext when (mode) {
                SortMode.FILENAME_ASC -> starFiltered.sortedWith(compareBy(fileNameComparator) { it.name })
                SortMode.FILENAME_DESC -> starFiltered.sortedWith(compareBy(fileNameComparator) { it.name }).asReversed()
                SortMode.DURATION_ASC -> starFiltered.sortedBy { it.duration ?: 0L }
                SortMode.DURATION_DESC -> starFiltered.sortedByDescending { it.duration ?: 0L }
                SortMode.FILE_SIZE_ASC -> starFiltered.sortedBy { it.fileSize }
                SortMode.FILE_SIZE_DESC -> starFiltered.sortedByDescending { it.fileSize }
            }
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
                    star = video.star
                )
            }

        val allVideos = videos + diskOnlyItems

        val nonVideoFiles = allVideos.filter { !isVideoFile(it.name) }
        val videoFiles = allVideos.filter { isVideoFile(it.name) }

        val filteredVideos = when (filterVal) {
            BrowseFilter.ALL -> videoFiles
            BrowseFilter.VIDEO_ONLY -> videoFiles
            BrowseFilter.UNWATCHED -> videoFiles.filter {
                it.playbackPosition == 0L && it.playbackDuration == 0L && !it.isCompleted
            }
        }

        val starFilteredVideos = when (starMode) {
            StarFilterMode.OFF -> filteredVideos
            StarFilterMode.EQ -> filteredVideos.filter { it.star == starValue }
            StarFilterMode.GT -> filteredVideos.filter { it.star > starValue }
            StarFilterMode.LT -> filteredVideos.filter { it.star < starValue }
        }

        val sortedFolders = when (mode) {
            SortMode.FILENAME_ASC -> folders.sortedWith(compareBy(fileNameComparator) { it.name })
            SortMode.FILENAME_DESC -> folders.sortedWith(compareBy(fileNameComparator) { it.name }).asReversed()
            else -> folders.sortedWith(compareBy(fileNameComparator) { it.name })
        }
        val sortedNonVideos = nonVideoFiles.sortedWith(compareBy(fileNameComparator) { it.name })
        val sortedVideos = when (mode) {
            SortMode.FILENAME_ASC -> starFilteredVideos.sortedWith(compareBy(fileNameComparator) { it.name })
            SortMode.FILENAME_DESC -> starFilteredVideos.sortedWith(compareBy(fileNameComparator) { it.name }).asReversed()
            SortMode.DURATION_ASC -> starFilteredVideos.sortedBy { it.duration ?: 0L }
            SortMode.DURATION_DESC -> starFilteredVideos.sortedByDescending { it.duration ?: 0L }
            SortMode.FILE_SIZE_ASC -> starFilteredVideos.sortedBy { it.fileSize }
            SortMode.FILE_SIZE_DESC -> starFilteredVideos.sortedByDescending { it.fileSize }
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
            BrowseFilter.VIDEO_ONLY -> emptyList()
            BrowseFilter.UNWATCHED -> emptyList()
        }
        _hiddenByFilterCount.value = (totalBeforeFilter - filteredFolders.size - finalNonVideos.size - sortedVideos.size).coerceAtLeast(0)

        (filteredFolders + finalNonVideos + sortedVideos).distinctBy { it.relativePath }
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

    fun updateStar(videoItemId: Long, star: Int) {
        viewModelScope.launch {
            try {
                videoRepository.updateStar(videoItemId, star)
                _refreshTrigger.value++
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update star", e)
                _error.value = e.message
            }
        }
    }

    fun updateStarByPath(relativePath: String, star: Int) {
        viewModelScope.launch {
            try {
                videoRepository.updateStarByPath(relativePath, star)
                _refreshTrigger.value++
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update star by path", e)
                _error.value = e.message
            }
        }
    }

    suspend fun getRecursiveFolderStats(folderPath: String) = videoRepository.getRecursiveFolderStats(folderPath)

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

    fun markFolderRead(folderPath: String, read: Boolean) {
        viewModelScope.launch {
            try {
                playbackRepository.markFolderCompleted(folderPath, read)
                _refreshTrigger.value++
            } catch (e: Exception) {
                Log.e(TAG, "Failed to mark folder read", e)
                _error.value = e.message
            }
        }
    }

    fun createFolder(parentPath: String, folderName: String) {
        viewModelScope.launch {
            try {
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
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create folder", e)
                _error.value = e.message
            }
        }
    }

    fun deleteFolder(path: String) {
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
                _refreshTrigger.value++
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete folder", e)
                _error.value = e.message
            }
        }
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
            syncRelationId = 0
        )
        val id = videoRepository.upsert(entity)

        // Trigger metadata extraction in the background
        val saved = videoRepository.getById(id)
        if (saved != null) {
            withContext(Dispatchers.IO) {
                videoMetadataExtractor.extractAndSave(saved)
            }
        }
        return id
    }

    fun getFolderVideoIds(): List<Long> {
        return browseItems.value
            .filter { !it.isDirectory && it.videoItemId != null && isVideoFile(it.name) }
            .mapNotNull { it.videoItemId }
    }

    fun toggleTrashed(videoItemId: Long) {
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

    fun isFileInBaseDir(relativePath: String): Boolean {
        val basePathsList = basePaths.value.map { it.relation.localBasePath }
        return basePathsList.any { basePath ->
            relativePath.startsWith("$basePath/") || relativePath == basePath
        }
    }

    fun deleteFile(relativePath: String) {
        viewModelScope.launch {
            try {
                val video = videoRepository.getByRelativePath(relativePath)
                if (video != null) {
                    playbackRepository.deleteByVideoId(video.id)
                    videoRepository.deleteByRelativePath(relativePath)
                }
                val file = File(storagePaths.userdataRoot, relativePath)
                if (file.exists()) file.delete()
                _refreshTrigger.value++
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete file", e)
                _error.value = e.message
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

            Intent.createChooser(shareIntent, "分享文件")
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "BrowserViewModel"
    }
}
