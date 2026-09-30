package cn.vectory.syncplayer.browser

import cn.vectory.syncplayer.data.local.dao.VideoItemDao
import cn.vectory.syncplayer.data.local.dao.VideoItemDao.ChildVideoStat
import cn.vectory.syncplayer.data.local.dao.VideoItemDao.FlatItemWithPlayback
import cn.vectory.syncplayer.data.local.entity.PlaybackRecordEntity
import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import cn.vectory.syncplayer.data.model.BrowseItem
import cn.vectory.syncplayer.ui.util.fileNameComparator
import cn.vectory.syncplayer.ui.util.isImageFile
import cn.vectory.syncplayer.ui.util.isVideoFile

/**
 * Pure data builder for the browser list, extracted from [BrowserViewModel.buildBrowseItems].
 *
 * The builder is intentionally side-effect free: it takes already-fetched DB rows plus the
 * user's UI selections and produces the final ordered [List] of [BrowseItem], together with the
 * two scalar counts the ViewModel surfaces to the UI (`flatTotalCount`, `hiddenByFilterCount`).
 * All I/O (DAO queries, Flow timing, state mutation) stays in [BrowserViewModel]; this object
 * only runs in-memory filter/sort/star/folder-stat combination logic, so it can be unit-tested
 * in isolation with plain JUnit (no Android, no Flow, no coroutines).
 *
 * The filter/sort/star/folder logic here is a verbatim port of the original
 * [BrowserViewModel.buildBrowseItems]; the only change is that inputs come in as parameters and
 * the two scalar counts come out as [Output] fields instead of being written to StateFlow.
 *
 * Enum coupling: the four selection enums ([BrowserViewModel.SortMode],
 * [BrowserViewModel.BrowseFilter], [BrowserViewModel.StarFilterMode],
 * [BrowserViewModel.BrowseMode]) remain nested on [BrowserViewModel] to avoid touching the UI
 * layer (which references them as `BrowserViewModel.*`). Referencing them here is purely nominal
 * and adds no weight to the ViewModel.
 */
object BrowseItemBuilder {

    /**
     * Everything the builder needs. The ViewModel gathers these before invoking [build].
     *
     * Mode-specific fields:
     * - In [BrowserViewModel.BrowseMode.FILE] only [flatTotalCount] and [flatItems] are read;
     *   the directory-mode fields are ignored (pass placeholders).
     * - In [BrowserViewModel.BrowseMode.DIRECTORY] only [subFolders], [itemsInFolder],
     *   [childVideoStats], [descendantVideoStats], [descendantFilterStats], [folderHasSubDirs],
     *   [records] and [baseDirSet] are read; the file-mode fields are ignored.
     */
    data class Input(
        val folder: String,
        val sortMode: BrowserViewModel.SortMode,
        val filter: BrowserViewModel.BrowseFilter,
        val starMode: BrowserViewModel.StarFilterMode,
        val starValue: Int,
        val browseMode: BrowserViewModel.BrowseMode,
        // === FILE mode ===
        /** Total media-item count in the DB (drives the flat scrollbar + flat hidden-count). */
        val flatTotalCount: Int,
        /** Flat rows already filtered + ordered by the DAO for the duration/size sort keys. */
        val flatItems: List<FlatItemWithPlayback>,
        // === DIRECTORY mode ===
        /** Direct child folder names of [folder], as returned by the DB (order is irrelevant). */
        val subFolders: List<String>,
        /** Media rows whose parentPath == [folder]. */
        val itemsInFolder: List<VideoItemEntity>,
        /** Direct-level child stats under [folder], used for per-subfolder video/played/trashed counts. */
        val childVideoStats: List<ChildVideoStat>,
        /**
         * U2-4a: ALL-depth descendant media rows under [folder] (single DAO scan). Source for the
         * per-folder thumbnail mosaic, so folders with no direct-level videos but deep descendants
         * still render thumbnails. Ignored in FILE mode.
         */
        val descendantVideoStats: List<ChildVideoStat>,
        /**
         * U2c: descendant rows under [folder] — path-scope CANDIDATES only (single DAO scan;
         * mediaType is unreliable in production, so no SQL-side filter/star gates). The
         * per-folder "(N)" counts apply the SAME extension-based rules as the list via the
         * shared [passesFilterAxis]/[passesStarAxis]/[isMediaByName] predicates. Ignored in
         * FILE mode.
         */
        val descendantFilterStats: List<VideoItemDao.DescendantFilterStat>,
        /** Direct-child folder name → whether it has deeper subfolders (DB-derived lookup). */
        val folderHasSubDirs: Map<String, Boolean>,
        /** Playback records for media in [folder] (joined at the ViewModel layer). */
        val records: List<PlaybackRecordEntity>,
        /** Configured sync-relation local base paths, for the sync-base-dir badges on folders. */
        val baseDirSet: Set<String>
    )

    /**
     * Result of a build.
     *
     * [flatTotalCount] is nullable on purpose: in DIRECTORY mode the original code never wrote
     * `_flatTotalCount`, so the ViewModel only forwards this field when non-null, preserving the
     * "retain the last FILE-mode value while browsing folders" behavior exactly.
     */
    data class Output(
        val items: List<BrowseItem>,
        val flatTotalCount: Int?,
        val hiddenByFilterCount: Int
    )

    /**
     * Build the browser list. Pure: no I/O, no state mutation, deterministic in [Input].
     */
    fun build(input: Input): Output {
        if (input.browseMode == BrowserViewModel.BrowseMode.FILE) {
            return buildFileMode(input)
        }
        return buildDirectoryMode(input)
    }

    // ===================================================================================
    // FILE MODE — flat list, no folders. The DAO already applied star/unwatched/media filters
    // and the duration/size ORDER BY; the builder only remaps rows to BrowseItem and applies
    // the natural-order filename sort (which the DAO's COLLATE NOCASE does not reproduce).
    // ===================================================================================
    private fun buildFileMode(input: Input): Output {
        val totalCount = input.flatTotalCount
        val result = input.flatItems.map { item ->
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

        val sorted = when (input.sortMode) {
            BrowserViewModel.SortMode.FILENAME_ASC ->
                result.sortedWith(compareBy(fileNameComparator) { it.name })
            BrowserViewModel.SortMode.FILENAME_DESC ->
                result.sortedWith(compareBy(fileNameComparator) { it.name }).asReversed()
            else -> result // duration/size already handled by the DAO ORDER BY
        }

        val hidden = (totalCount - sorted.size).coerceAtLeast(0)
        return Output(
            items = sorted,
            flatTotalCount = totalCount,
            hiddenByFilterCount = hidden
        )
    }

    // ===================================================================================
    // DIRECTORY MODE — DB is the sole truth source (no physical disk scan).
    // ===================================================================================
    private fun buildDirectoryMode(input: Input): Output {
        val folder = input.folder
        val mode = input.sortMode
        val filterVal = input.filter
        val starMode = input.starMode
        val starValue = input.starValue

        val recordMap = input.records.associateBy { it.videoItemId }
        val allFolderNames = input.subFolders.sortedWith(fileNameComparator)
        val videoEntities = input.itemsInFolder
        val childStats = input.childVideoStats
        val folderHasSubDirs = input.folderHasSubDirs
        val baseDirSet = input.baseDirSet

        // Per-direct-child video stats: only VIDEO mediaType contributes to counts/mosaic
        // (matches the original `isVideoFile(it.fileName)` filter).
        val subFolderStatsMap = childStats
            .filter { isVideoFile(it.fileName) }
            .groupBy { directChildName(folder, it.relativePath) }

        // U2-4a/4b: folder thumbnail mosaic from ALL-depth descendants (single DAO scan), so a
        // folder with videos only in deeper subfolders still gets a mosaic. IDs are filtered to
        // pure video files by extension — the DAO's mediaType gate alone admits IMAGE rows (and
        // mis-typed rows), which rendered as permanent gray cells before the fix.
        val folderVideoIdsMap = input.descendantVideoStats
            .filter { isVideoFile(it.fileName) }
            .groupBy { directChildName(folder, it.relativePath) }
            .mapValues { (_, rows) -> rows.map { it.id }.take(9) }

        // U2c: per-direct-child count of descendant files matching the CURRENT filter. The DAO
        // returns path-scope candidates without semantics (mediaType is unreliable in
        // production: sync placeholder rows default to "VIDEO" and are never corrected), so
        // counting applies the SAME extension-based rules as the file lists below — a row
        // shown in the list is always counted toward its ancestor folder, and vice versa.
        // star gates media-extension rows only; non-media rows survive only under ALL and are
        // star-exempt (otherFiles semantics).
        val folderDescendantCountMap = input.descendantFilterStats
            .filter { row ->
                val media = isMediaByName(row.fileName)
                passesFilterAxis(filterVal, media, row.playbackPosition, row.playbackDuration, row.isCompleted) &&
                    (!media || passesStarAxis(row.star, starMode, starValue))
            }
            .groupBy { directChildName(folder, it.relativePath) }
            .mapValues { (_, rows) -> rows.size }

        val folders = allFolderNames.map { folderName ->
            val fullPath = if (folder.isEmpty()) folderName else "$folder/$folderName"
            val stats = subFolderStatsMap[folderName]
            val vidCount = stats?.size ?: 0
            val playedCount = stats?.count { it.position > 0 || it.isCompleted } ?: 0
            val trashedCount = stats?.count { it.isTrashed } ?: 0
            val childIds = folderVideoIdsMap[folderName].orEmpty()
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
                descendantCount = folderDescendantCountMap[folderName] ?: 0,
                videoItemIds = childIds
            )
        }

        val videos = videoEntities.map { video ->
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

        val allVideos = videos
        val imageFiles = allVideos.filter { isImageFile(it.name) }
        val videoFiles = allVideos.filter { isVideoFile(it.name) }
        val otherFiles = allVideos.filter { !isVideoFile(it.name) && !isImageFile(it.name) }

        // U2c: list filter/star rules run through the SAME shared predicates the descendant
        // counting uses ([passesFilterAxis]/[passesStarAxis]) — one source of truth, no drift.
        val filteredVideos = videoFiles.filter {
            passesFilterAxis(filterVal, isMedia = true, it.playbackPosition, it.playbackDuration, it.isCompleted)
        }
        val filteredImages = imageFiles.filter {
            passesFilterAxis(filterVal, isMedia = true, it.playbackPosition, it.playbackDuration, it.isCompleted)
        }

        val starFilteredVideos = filteredVideos.filter { passesStarAxis(it.star, starMode, starValue) }
        val starFilteredImages = filteredImages.filter { passesStarAxis(it.star, starMode, starValue) }

        val sortedFolders = when (mode) {
            BrowserViewModel.SortMode.FILENAME_ASC ->
                folders.sortedWith(compareBy(fileNameComparator) { it.name })
            BrowserViewModel.SortMode.FILENAME_DESC ->
                folders.sortedWith(compareBy(fileNameComparator) { it.name }).asReversed()
            else -> folders.sortedWith(compareBy(fileNameComparator) { it.name })
        }
        val sortedNonVideos = otherFiles.sortedWith(compareBy(fileNameComparator) { it.name })
        val sortedVideos = when (mode) {
            BrowserViewModel.SortMode.FILENAME_ASC ->
                starFilteredVideos.sortedWith(compareBy(fileNameComparator) { it.name })
            BrowserViewModel.SortMode.FILENAME_DESC ->
                starFilteredVideos.sortedWith(compareBy(fileNameComparator) { it.name }).asReversed()
            BrowserViewModel.SortMode.DURATION_ASC ->
                starFilteredVideos.sortedBy { it.duration ?: 0L }
            BrowserViewModel.SortMode.DURATION_DESC ->
                starFilteredVideos.sortedByDescending { it.duration ?: 0L }
            BrowserViewModel.SortMode.FILE_SIZE_ASC ->
                starFilteredVideos.sortedBy { it.fileSize }
            BrowserViewModel.SortMode.FILE_SIZE_DESC ->
                starFilteredVideos.sortedByDescending { it.fileSize }
        }
        val sortedImages = when (mode) {
            BrowserViewModel.SortMode.FILENAME_ASC ->
                starFilteredImages.sortedWith(compareBy(fileNameComparator) { it.name })
            BrowserViewModel.SortMode.FILENAME_DESC ->
                starFilteredImages.sortedWith(compareBy(fileNameComparator) { it.name }).asReversed()
            // For images, the "duration" axis reuses dateTaken (no duration on stills).
            BrowserViewModel.SortMode.DURATION_ASC ->
                starFilteredImages.sortedBy { it.dateTaken ?: 0L }
            BrowserViewModel.SortMode.DURATION_DESC ->
                starFilteredImages.sortedByDescending { it.dateTaken ?: 0L }
            BrowserViewModel.SortMode.FILE_SIZE_ASC ->
                starFilteredImages.sortedBy { it.fileSize }
            BrowserViewModel.SortMode.FILE_SIZE_DESC ->
                starFilteredImages.sortedByDescending { it.fileSize }
        }

        val totalBeforeFilter = sortedFolders.size + allVideos.size
        val filteredFolders = if (filterVal == BrowserViewModel.BrowseFilter.UNWATCHED) {
            sortedFolders.filter { folderItem ->
                val hasSubDirs = folderHasSubDirs[folderItem.name] ?: true
                !(folderItem.videoCount > 0 && folderItem.playedCount >= folderItem.videoCount && !hasSubDirs)
            }
        } else {
            sortedFolders
        }
        val finalNonVideos = when (filterVal) {
            BrowserViewModel.BrowseFilter.ALL -> sortedNonVideos
            BrowserViewModel.BrowseFilter.MEDIA_ONLY -> emptyList()
            BrowserViewModel.BrowseFilter.UNWATCHED -> emptyList()
        }
        val finalImages = when (filterVal) {
            BrowserViewModel.BrowseFilter.ALL -> sortedImages
            BrowserViewModel.BrowseFilter.MEDIA_ONLY -> sortedImages
            BrowserViewModel.BrowseFilter.UNWATCHED -> sortedImages
        }
        val hidden = (totalBeforeFilter - filteredFolders.size - finalNonVideos.size -
            finalImages.size - sortedVideos.size).coerceAtLeast(0)

        val items = (filteredFolders + finalNonVideos + finalImages + sortedVideos)
            .distinctBy { it.relativePath }

        return Output(
            items = items,
            // DIRECTORY mode never wrote _flatTotalCount in the original code; null signals the
            // ViewModel to leave the retained value untouched.
            flatTotalCount = null,
            hiddenByFilterCount = hidden
        )
    }

    /**
     * First path segment of [relativePath] relative to [folder] — i.e. the direct child folder
     * name a descendant row belongs to. Root ([folder] == '') uses the path as-is.
     */
    private fun directChildName(folder: String, relativePath: String): String {
        val afterFolder = if (folder.isEmpty()) relativePath
        else relativePath.substring(folder.length + 1)
        return afterFolder.substringBefore('/')
    }

    // ===================================================================================
    // U2c: shared filter semantics — the DIRECTORY list and the descendant "(N)" counting
    // MUST decide identically (a row shown in the list ⇔ counted toward its ancestor
    // folder). These predicates are the single source of truth; the DAO returns
    // path-scope candidates without semantics because mediaType is unreliable in
    // production (sync placeholder rows default to "VIDEO" and are never corrected).
    // ===================================================================================

    /** 扩展名媒体分类：video/image 扩展名算媒体（与列表 videoFiles/imageFiles/otherFiles 分类同源）。 */
    private fun isMediaByName(fileName: String): Boolean = isVideoFile(fileName) || isImageFile(fileName)

    /** 筛选轴判定（ALL/MEDIA_ONLY/UNWATCHED）。[isMedia] 按扩展名分类给出。 */
    private fun passesFilterAxis(
        filter: BrowserViewModel.BrowseFilter,
        isMedia: Boolean,
        playbackPosition: Long,
        playbackDuration: Long,
        isCompleted: Boolean
    ): Boolean = when (filter) {
        BrowserViewModel.BrowseFilter.ALL -> true
        BrowserViewModel.BrowseFilter.MEDIA_ONLY -> isMedia
        BrowserViewModel.BrowseFilter.UNWATCHED ->
            isMedia && playbackPosition == 0L && playbackDuration == 0L && !isCompleted
    }

    /** star 轴判定：仅作用于媒体扩展名行（非媒体行由调用方豁免，otherFiles 语义）。 */
    private fun passesStarAxis(star: Int, starMode: BrowserViewModel.StarFilterMode, starValue: Int): Boolean =
        when (starMode) {
            BrowserViewModel.StarFilterMode.OFF -> true
            BrowserViewModel.StarFilterMode.EQ -> star == starValue
            BrowserViewModel.StarFilterMode.GT -> star > starValue
            BrowserViewModel.StarFilterMode.LT -> star < starValue
        }
}
