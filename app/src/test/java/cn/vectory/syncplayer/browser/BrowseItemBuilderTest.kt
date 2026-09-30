package cn.vectory.syncplayer.browser

import cn.vectory.syncplayer.data.local.dao.VideoItemDao
import cn.vectory.syncplayer.data.local.dao.VideoItemDao.ChildVideoStat
import cn.vectory.syncplayer.data.local.dao.VideoItemDao.DescendantFilterStat
import cn.vectory.syncplayer.data.local.dao.VideoItemDao.FlatItemWithPlayback
import cn.vectory.syncplayer.data.local.entity.PlaybackRecordEntity
import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-data unit tests for [BrowseItemBuilder].
 *
 * No Android, no Flow, no DB — only the in-memory filter/sort/star/folder-stat combination
 * logic. Assertions are on list structure, item fields and ordering (never UI text), per the
 * project testing convention (assert on enums/sizes/counts, not user-facing strings).
 *
 * Each DIRECTORY-mode test stubs the exact DAO rows the ViewModel would otherwise gather and
 * feeds them to [BrowseItemBuilder.build]; the expected [BrowseItemBuilder.Output] is asserted.
 */
class BrowseItemBuilderTest {

    // ===================================================================================
    // Helpers — minimal fluent constructors for the four DAO/DB row types the builder reads.
    // ===================================================================================

    private fun vid(
        id: Long,
        name: String,
        fileSize: Long = 0L,
        duration: Long? = null,
        star: Int = 0,
        mediaType: String = "VIDEO",
        relativePath: String = name,
        audioGainDb: Float? = null
    ): VideoItemEntity = VideoItemEntity(
        id = id,
        relativePath = relativePath,
        fileName = name,
        fileSize = fileSize,
        duration = duration,
        star = star,
        mediaType = mediaType,
        audioGainDb = audioGainDb
    )

    private fun flat(
        id: Long,
        name: String,
        fileSize: Long = 0L,
        duration: Long? = null,
        star: Int = 0,
        mediaType: String = "VIDEO",
        audioGainDb: Float? = null,
        playbackPosition: Long = 0L,
        isCompleted: Boolean = false,
        isTrashed: Boolean = false
    ): FlatItemWithPlayback = FlatItemWithPlayback(
        id = id,
        fileName = name,
        relativePath = name,
        fileSize = fileSize,
        duration = duration,
        width = null,
        height = null,
        bitrate = null,
        frameRate = null,
        audioGainDb = audioGainDb,
        star = star,
        mediaType = mediaType,
        dateTaken = null,
        playbackPosition = playbackPosition,
        playbackDuration = 0L,
        isCompleted = isCompleted,
        isTrashed = isTrashed
    )

    private fun childStat(
        id: Long,
        relativePath: String,
        fileName: String,
        position: Long = 0L,
        isCompleted: Boolean = false,
        isTrashed: Boolean = false
    ): ChildVideoStat = ChildVideoStat(
        id = id,
        relativePath = relativePath,
        fileName = fileName,
        audioGainDb = null,
        position = position,
        isCompleted = isCompleted,
        isTrashed = isTrashed
    )

    /** U2-6 descendant filter-count row stub (DAO has already applied its SQL criteria). */
    private fun descStat(
        id: Long,
        relativePath: String,
        fileName: String,
        star: Int = 0,
        mediaType: String = "VIDEO",
        playbackPosition: Long = 0L,
        playbackDuration: Long = 0L,
        isCompleted: Boolean = false
    ): DescendantFilterStat = DescendantFilterStat(
        id = id,
        relativePath = relativePath,
        fileName = fileName,
        mediaType = mediaType,
        star = star,
        playbackPosition = playbackPosition,
        playbackDuration = playbackDuration,
        isCompleted = isCompleted
    )

    private fun record(
        videoItemId: Long,
        position: Long = 0L,
        duration: Long = 0L,
        isCompleted: Boolean = false,
        isTrashed: Boolean = false
    ): PlaybackRecordEntity = PlaybackRecordEntity(
        videoItemId = videoItemId,
        position = position,
        duration = duration,
        isCompleted = isCompleted,
        isTrashed = isTrashed
    )

    /** Default DIRECTORY-mode Input with empty DB rows; tests override only what they need. */
    private fun dirInput(
        folder: String = "",
        subFolders: List<String> = emptyList(),
        itemsInFolder: List<VideoItemEntity> = emptyList(),
        childVideoStats: List<ChildVideoStat> = emptyList(),
        descendantVideoStats: List<ChildVideoStat> = emptyList(),
        descendantFilterStats: List<DescendantFilterStat> = emptyList(),
        folderHasSubDirs: Map<String, Boolean> = emptyMap(),
        records: List<PlaybackRecordEntity> = emptyList(),
        baseDirSet: Set<String> = emptySet(),
        sortMode: BrowserViewModel.SortMode = BrowserViewModel.SortMode.FILENAME_ASC,
        filter: BrowserViewModel.BrowseFilter = BrowserViewModel.BrowseFilter.ALL,
        starMode: BrowserViewModel.StarFilterMode = BrowserViewModel.StarFilterMode.OFF,
        starValue: Int = 0
    ): BrowseItemBuilder.Input = BrowseItemBuilder.Input(
        folder = folder,
        sortMode = sortMode,
        filter = filter,
        starMode = starMode,
        starValue = starValue,
        browseMode = BrowserViewModel.BrowseMode.DIRECTORY,
        flatTotalCount = 0,
        flatItems = emptyList(),
        subFolders = subFolders,
        itemsInFolder = itemsInFolder,
        childVideoStats = childVideoStats,
        descendantVideoStats = descendantVideoStats,
        descendantFilterStats = descendantFilterStats,
        folderHasSubDirs = folderHasSubDirs,
        records = records,
        baseDirSet = baseDirSet
    )

    private fun fileInput(
        flatTotalCount: Int = 0,
        flatItems: List<FlatItemWithPlayback> = emptyList(),
        sortMode: BrowserViewModel.SortMode = BrowserViewModel.SortMode.FILENAME_ASC,
        filter: BrowserViewModel.BrowseFilter = BrowserViewModel.BrowseFilter.ALL,
        starMode: BrowserViewModel.StarFilterMode = BrowserViewModel.StarFilterMode.OFF,
        starValue: Int = 0
    ): BrowseItemBuilder.Input = BrowseItemBuilder.Input(
        folder = "",
        sortMode = sortMode,
        filter = filter,
        starMode = starMode,
        starValue = starValue,
        browseMode = BrowserViewModel.BrowseMode.FILE,
        flatTotalCount = flatTotalCount,
        flatItems = flatItems,
        subFolders = emptyList(),
        itemsInFolder = emptyList(),
        childVideoStats = emptyList(),
        descendantVideoStats = emptyList(),
        descendantFilterStats = emptyList(),
        folderHasSubDirs = emptyMap(),
        records = emptyList(),
        baseDirSet = emptySet()
    )

    // ===================================================================================
    // DIRECTORY MODE — empty / root / nested folders
    // ===================================================================================

    @Test
    fun `directory empty folder yields no items and zero hidden`() {
        val out = BrowseItemBuilder.build(dirInput(folder = "Movies"))
        assertTrue(out.items.isEmpty())
        // DIRECTORY mode never reports flatTotalCount (retains last FILE-mode value in VM).
        assertNull(out.flatTotalCount)
        assertEquals(0, out.hiddenByFilterCount)
    }

    @Test
    fun `directory root folder lists folders first then files in natural filename asc order`() {
        // subFolders returned unsorted by the DAO — builder must natural-sort them.
        val out = BrowseItemBuilder.build(
            dirInput(
                folder = "",
                subFolders = listOf("TV", "Movies"),
                itemsInFolder = listOf(
                    vid(1, "b.mkv"),
                    vid(2, "a.mp4")
                )
            )
        )
        // Folders come first (natural sort), then videos (natural sort by name).
        assertEquals(
            listOf("Movies", "TV", "a.mp4", "b.mkv"),
            out.items.map { it.name }
        )
        // Folder items are flagged as directories; file items are not.
        assertTrue(out.items[0].isDirectory)
        assertTrue(out.items[1].isDirectory)
        assertFalse(out.items[2].isDirectory)
        assertFalse(out.items[3].isDirectory)
    }

    @Test
    fun `directory nested folder strips prefix when computing child folder paths`() {
        // Browsing inside "Movies", the builder must prepend "Movies/" to child folder names
        // and to child-stat relative-path parsing.
        val out = BrowseItemBuilder.build(
            dirInput(
                folder = "Movies",
                subFolders = listOf("Action"),
                childVideoStats = listOf(
                    // A deep descendant under Movies/Action — counted into the Action subfolder.
                    childStat(10L, "Movies/Action/fight.mp4", "fight.mp4")
                ),
                // U2-4a: the mosaic ids come from the descendant scan (same prefix parsing).
                descendantVideoStats = listOf(
                    childStat(10L, "Movies/Action/fight.mp4", "fight.mp4")
                )
            )
        )
        assertEquals(1, out.items.size)
        val folder = out.items[0]
        assertTrue(folder.isDirectory)
        assertEquals("Action", folder.name)
        assertEquals("Movies/Action", folder.relativePath)
        assertEquals(1, folder.videoCount)
        // mosaic carries the descendant media id
        assertEquals(listOf(10L), folder.videoItemIds)
    }

    @Test
    fun `directory deep folder resolves a two-level path`() {
        val out = BrowseItemBuilder.build(
            dirInput(
                folder = "Movies/Action",
                itemsInFolder = listOf(vid(5, "hero.mp4"))
            )
        )
        assertEquals(1, out.items.size)
        assertEquals("hero.mp4", out.items[0].name)
        assertFalse(out.items[0].isDirectory)
    }

    // ===================================================================================
    // DIRECTORY MODE — folder stats + mosaic
    // ===================================================================================

    @Test
    fun `directory folder stats count videos played and trashed from childVideoStats`() {
        // Only VIDEO-mediaType rows contribute (the builder filters childStats by isVideoFile).
        val out = BrowseItemBuilder.build(
            dirInput(
                folder = "",
                subFolders = listOf("Series"),
                childVideoStats = listOf(
                    childStat(1L, "Series/a.mp4", "a.mp4", position = 0L),                 // unplayed video
                    childStat(2L, "Series/b.mp4", "b.mp4", position = 50L),                // played video
                    childStat(3L, "Series/c.mp4", "c.mp4", isCompleted = true),            // completed video
                    childStat(4L, "Series/d.mp4", "d.mp4", isTrashed = true),              // trashed video
                    childStat(5L, "Series/poster.jpg", "poster.jpg")                       // image — NOT counted
                )
            )
        )
        val folder = out.items[0]
        assertEquals("Series", folder.name)
        // 4 videos contribute (image filtered out by isVideoFile)
        assertEquals(4, folder.videoCount)
        // playedCount = position > 0 OR isCompleted → b (50>0), c (completed) → 2
        assertEquals(2, folder.playedCount)
        // trashedCount = 1 (d)
        assertEquals(1, folder.trashedCount)
    }

    @Test
    fun `directory folder mosaic caps at nine video ids`() {
        // U2-4a: mosaic ids are sourced from the descendant scan.
        val deep = (1..11).map { childStat(it.toLong(), "F/v$it.mp4", "v$it.mp4") }
        val out = BrowseItemBuilder.build(
            dirInput(folder = "", subFolders = listOf("F"), descendantVideoStats = deep)
        )
        assertEquals(9, out.items[0].videoItemIds.size)
        // take(9) preserves the input order
        assertEquals((1L..9L).toList(), out.items[0].videoItemIds)
    }

    // ===================================================================================
    // U2-4a: mosaic from ALL-depth descendants (folder with no direct-level videos)
    // ===================================================================================

    @Test
    fun `directory mosaic uses deep descendants when direct level has no videos`() {
        // childVideoStats (direct level) is empty — before U2-4a this folder rendered no mosaic.
        val out = BrowseItemBuilder.build(
            dirInput(
                folder = "Library",
                subFolders = listOf("Show"),
                descendantVideoStats = listOf(
                    childStat(10L, "Library/Show/s01e01.mp4", "s01e01.mp4"),
                    childStat(11L, "Library/Show/Season 1/s01e02.mkv", "s01e02.mkv")
                )
            )
        )
        assertEquals(1, out.items.size)
        val folder = out.items[0]
        assertEquals("Show", folder.name)
        assertEquals(listOf(10L, 11L), folder.videoItemIds)
    }

    @Test
    fun `directory mosaic groups deep descendants under their direct child folder`() {
        val out = BrowseItemBuilder.build(
            dirInput(
                folder = "",
                subFolders = listOf("A", "B"),
                descendantVideoStats = listOf(
                    childStat(1L, "A/x/a1.mp4", "a1.mp4"),
                    childStat(2L, "A/x/a2.mp4", "a2.mp4"),
                    childStat(3L, "B/b.mp4", "b.mp4")
                )
            )
        )
        val byName = out.items.associateBy { it.name }
        assertEquals(listOf(1L, 2L), byName.getValue("A").videoItemIds)
        assertEquals(listOf(3L), byName.getValue("B").videoItemIds)
    }

    @Test
    fun `directory mosaic caps deep descendant ids at nine`() {
        // 11 deep ids spread over two levels under one folder.
        val deep = (1..11).map { childStat(it.toLong(), "F/Sub/v$it.mp4", "v$it.mp4") }
        val out = BrowseItemBuilder.build(
            dirInput(folder = "", subFolders = listOf("F"), descendantVideoStats = deep)
        )
        assertEquals(9, out.items[0].videoItemIds.size)
        assertEquals((1L..9L).toList(), out.items[0].videoItemIds)
    }

    // ===================================================================================
    // U2-4b (root cause 1): mosaic ids must be pure video files — DAO rows include IMAGE
    // mediaType rows, which rendered as gray cells (no video thumbnail exists for them).
    // ===================================================================================

    @Test
    fun `directory mosaic excludes image descendants`() {
        val out = BrowseItemBuilder.build(
            dirInput(
                folder = "",
                subFolders = listOf("F"),
                descendantVideoStats = listOf(
                    childStat(1L, "F/v.mp4", "v.mp4"),
                    childStat(2L, "F/poster.jpg", "poster.jpg"),
                    childStat(3L, "F/deep/pic.png", "pic.png")
                )
            )
        )
        assertEquals(listOf(1L), out.items[0].videoItemIds)
    }

    @Test
    fun `directory mosaic for image-only folder yields no ids`() {
        val out = BrowseItemBuilder.build(
            dirInput(
                folder = "",
                subFolders = listOf("Pics"),
                descendantVideoStats = listOf(
                    childStat(1L, "Pics/a.jpg", "a.jpg"),
                    childStat(2L, "Pics/b.png", "b.png")
                )
            )
        )
        // Empty ids → FolderGridItem falls back to the folder icon (no gray mosaic cells).
        assertTrue(out.items[0].videoItemIds.isEmpty())
    }

    // ===================================================================================
    // U2-6: per-folder descendant counts under the current filter
    // ===================================================================================

    @Test
    fun `directory descendant count groups per folder under ALL including non-media files`() {
        // ALL semantics = 全部文件 (incl. non-media), mirroring the builder's ALL behavior
        // of keeping otherFiles visible. Rows are already star/unwatched-filtered upstream.
        val out = BrowseItemBuilder.build(
            dirInput(
                folder = "",
                subFolders = listOf("A", "B"),
                descendantFilterStats = listOf(
                    descStat(1L, "A/top.mp4", "top.mp4"),
                    descStat(2L, "A/sub/inner.mkv", "inner.mkv"),
                    descStat(3L, "A/sub/note.txt", "note.txt", mediaType = "OTHER"),
                    descStat(4L, "B/only.mp4", "only.mp4")
                )
            )
        )
        val byName = out.items.associateBy { it.name }
        assertEquals(3, byName.getValue("A").descendantCount)
        assertEquals(1, byName.getValue("B").descendantCount)
    }

    @Test
    fun `directory descendant count drops non-media rows under MEDIA_ONLY`() {
        // U2c: the DAO returns path-scope candidates WITHOUT semantics (mediaType is
        // unreliable — sync placeholders default to "VIDEO"); the builder decides by
        // extension, identical to the list rules.
        val out = BrowseItemBuilder.build(
            dirInput(
                filter = BrowserViewModel.BrowseFilter.MEDIA_ONLY,
                subFolders = listOf("A"),
                descendantFilterStats = listOf(
                    descStat(1L, "A/v1.mp4", "v1.mp4"),
                    descStat(2L, "A/v2.mkv", "v2.mkv"),
                    descStat(3L, "A/readme.txt", "readme.txt", mediaType = "VIDEO")
                )
            )
        )
        assertEquals(2, out.items[0].descendantCount)
    }

    @Test
    fun `directory descendant count for folders without rows is zero`() {
        val out = BrowseItemBuilder.build(
            dirInput(
                folder = "",
                subFolders = listOf("Empty", "Has"),
                descendantFilterStats = listOf(descStat(1L, "Has/v.mp4", "v.mp4"))
            )
        )
        val byName = out.items.associateBy { it.name }
        assertEquals(0, byName.getValue("Empty").descendantCount)
        assertEquals(1, byName.getValue("Has").descendantCount)
    }

    @Test
    fun `directory descendant count reflects the rows passed for the active filter`() {
        // U2c: the DAO returns path-scope candidates; the UNWATCHED decision (media extension
        // + zero progress/duration + not completed) is applied here by the shared predicate —
        // both rows are unwatched media, so both count.
        val out = BrowseItemBuilder.build(
            dirInput(
                filter = BrowserViewModel.BrowseFilter.UNWATCHED,
                subFolders = listOf("A"),
                descendantFilterStats = listOf(
                    descStat(1L, "A/fresh.mp4", "fresh.mp4"),
                    descStat(2L, "A/pic.jpg", "pic.jpg", mediaType = "IMAGE")
                )
            )
        )
        assertEquals(2, out.items[0].descendantCount)
    }

    // ===================================================================================
    // U2c: candidate rows + shared-decision layering — counting and the directory list
    // decide IDENTICALLY (同进同出). Production fact: sync placeholder rows do NOT set
    // mediaType, so non-media files (.txt etc.) carry the entity default "VIDEO".
    // ===================================================================================

    @Test
    fun `production-default txt is retained by count and list under ALL even with star mismatch`() {
        // 生产常态：占位行不设 mediaType → .txt 落在默认 "VIDEO"。扩展名口径下它属非媒体：
        // ALL 下仅过筛选轴（恒真）且豁免 star ⇒ 计数保留；目录列表 otherFiles 同样保留。
        val out = BrowseItemBuilder.build(
            dirInput(
                filter = BrowserViewModel.BrowseFilter.ALL,
                starMode = BrowserViewModel.StarFilterMode.EQ,
                starValue = 5,
                subFolders = listOf("A"),
                itemsInFolder = listOf(vid(1L, "notes.txt", mediaType = "VIDEO")),
                descendantFilterStats = listOf(
                    descStat(1L, "A/notes.txt", "notes.txt", mediaType = "VIDEO", star = 0)
                )
            )
        )
        assertEquals(1, out.items[0].descendantCount)
        assertTrue(out.items.map { it.name }.contains("notes.txt"))
    }

    @Test
    fun `crossClassified txt stored as VIDEO is counted under ALL regardless of star and dropped under MEDIA_ONLY`() {
        // .txt 存 VIDEO（生产默认，非罕见误分类）：扩展名非媒体 ⇒ ALL 计入且豁免 star；
        // MEDIA_ONLY ⇒ 不计入（列表语义逐一对照：otherFiles 仅 ALL 保留）。
        fun count(filter: BrowserViewModel.BrowseFilter, starMode: BrowserViewModel.StarFilterMode): Int =
            BrowseItemBuilder.build(
                dirInput(
                    filter = filter,
                    starMode = starMode,
                    starValue = 5,
                    subFolders = listOf("A"),
                    descendantFilterStats = listOf(
                        descStat(1L, "A/notes.txt", "notes.txt", mediaType = "VIDEO", star = 0)
                    )
                )
            ).items[0].descendantCount
        assertEquals(1, count(BrowserViewModel.BrowseFilter.ALL, BrowserViewModel.StarFilterMode.EQ))
        assertEquals(0, count(BrowserViewModel.BrowseFilter.MEDIA_ONLY, BrowserViewModel.StarFilterMode.OFF))
    }

    @Test
    fun `crossClassified mp4 stored as OTHER is star gated out of count AND list under ALL`() {
        // .mp4 存 OTHER：扩展名是媒体 ⇒ 受 star 门。ALL + star 不匹配 ⇒ 计数 0 且列表剔除
        // （同进同出）；star OFF ⇒ 计数 1 且列表保留。
        fun build(starMode: BrowserViewModel.StarFilterMode): BrowseItemBuilder.Output =
            BrowseItemBuilder.build(
                dirInput(
                    filter = BrowserViewModel.BrowseFilter.ALL,
                    starMode = starMode,
                    starValue = 5,
                    subFolders = listOf("A"),
                    itemsInFolder = listOf(vid(1L, "movie.mp4", mediaType = "OTHER", star = 0)),
                    descendantFilterStats = listOf(
                        descStat(1L, "A/movie.mp4", "movie.mp4", mediaType = "OTHER", star = 0)
                    )
                )
            )
        val mismatched = build(BrowserViewModel.StarFilterMode.EQ)
        assertEquals(0, mismatched.items[0].descendantCount)
        assertFalse(mismatched.items.map { it.name }.contains("movie.mp4"))
        val unstarred = build(BrowserViewModel.StarFilterMode.OFF)
        assertEquals(1, unstarred.items[0].descendantCount)
        assertTrue(unstarred.items.map { it.name }.contains("movie.mp4"))
    }

    @Test
    fun `descendant count under UNWATCHED applies extension and playback rules to candidate rows`() {
        // 候选行不再被 SQL 预过滤：未看判定完全在 builder（与列表同源）。
        // 已看视频(100)、未看视频、零进度已完成视频、已看图片(50)、未看图片、生产默认 .txt
        // ⇒ 仅未看媒体计入 = 2。
        val out = BrowseItemBuilder.build(
            dirInput(
                filter = BrowserViewModel.BrowseFilter.UNWATCHED,
                subFolders = listOf("A"),
                descendantFilterStats = listOf(
                    descStat(1L, "A/watched.mp4", "watched.mp4", playbackPosition = 100L),
                    descStat(2L, "A/fresh.mkv", "fresh.mkv"),
                    descStat(3L, "A/done.mp4", "done.mp4", isCompleted = true),
                    descStat(4L, "A/seen.jpg", "seen.jpg", mediaType = "IMAGE", playbackPosition = 50L),
                    descStat(5L, "A/new.png", "new.png", mediaType = "IMAGE"),
                    descStat(6L, "A/notes.txt", "notes.txt", mediaType = "VIDEO")
                )
            )
        )
        assertEquals(2, out.items[0].descendantCount)
    }

    @Test
    fun `descendant count star axis is strict for media-extension rows only`() {
        // star GT 2：star=2 边界剔除、star=5 计入；非媒体 .txt（生产默认 VIDEO）豁免 star，
        // ALL 下恒计入 ⇒ 计数 = high.mkv + notes.txt = 2。
        val out = BrowseItemBuilder.build(
            dirInput(
                filter = BrowserViewModel.BrowseFilter.ALL,
                starMode = BrowserViewModel.StarFilterMode.GT,
                starValue = 2,
                subFolders = listOf("A"),
                descendantFilterStats = listOf(
                    descStat(1L, "A/boundary.mp4", "boundary.mp4", star = 2),
                    descStat(2L, "A/high.mkv", "high.mkv", star = 5),
                    descStat(3L, "A/notes.txt", "notes.txt", mediaType = "VIDEO", star = 0)
                )
            )
        )
        assertEquals(2, out.items[0].descendantCount)
    }

    @Test
    fun `directory folder sync badges flag base dir containment and membership`() {
        val out = BrowseItemBuilder.build(
            dirInput(
                folder = "",
                subFolders = listOf("Synced", "Inside", "Unrelated"),
                baseDirSet = setOf("Synced")
            )
        )
        val byName = out.items.associateBy { it.name }
        // Synced itself is a base dir.
        assertTrue(byName.getValue("Synced").isSyncBaseDir)
        // "Inside" is not a base dir but lies inside one ("Synced/Inside" would start with "Synced/").
        // Here it's a top-level folder named "Inside" — it does not start with "Synced/", so the
        // isInSyncBaseDir flag is false. The third folder ("Inside" under a Synced base) is covered
        // by the next assertion.
        assertFalse(byName.getValue("Unrelated").isSyncBaseDir)
        assertFalse(byName.getValue("Unrelated").isInSyncBaseDir)
    }

    @Test
    fun `directory child of a base dir is flagged as in sync base dir`() {
        val out = BrowseItemBuilder.build(
            dirInput(
                folder = "Synced",
                subFolders = listOf("Season1"),
                baseDirSet = setOf("Synced")
            )
        )
        val season1 = out.items[0]
        assertEquals("Synced/Season1", season1.relativePath)
        // "Synced/Season1".startsWith("Synced/") → true
        assertTrue(season1.isInSyncBaseDir)
        assertFalse(season1.isSyncBaseDir)
    }

    @Test
    fun `directory folder containing a base dir is flagged as contains sync base dir`() {
        // Parent folder that has a base dir among its descendants.
        val out = BrowseItemBuilder.build(
            dirInput(
                folder = "",
                subFolders = listOf("Library"),
                baseDirSet = setOf("Library/Movies")
            )
        )
        val library = out.items[0]
        // "Library/Movies".startsWith("Library/") → containsSyncBaseDir true
        assertTrue(library.containsSyncBaseDir)
        assertFalse(library.isSyncBaseDir)
    }

    // ===================================================================================
    // DIRECTORY MODE — filter ALL / MEDIA_ONLY / UNWATCHED
    // ===================================================================================

    @Test
    fun `directory filter ALL keeps videos images and other files`() {
        val out = BrowseItemBuilder.build(
            dirInput(
                filter = BrowserViewModel.BrowseFilter.ALL,
                itemsInFolder = listOf(
                    vid(1, "movie.mp4", mediaType = "VIDEO"),
                    vid(2, "pic.jpg", mediaType = "IMAGE"),
                    vid(3, "readme.txt", mediaType = "VIDEO") // mediaType column ignored; ext decides
                )
            )
        )
        // All three survive under ALL: image sorted into finalImages, video into sortedVideos,
        // non-media ("readme.txt") into finalNonVideos. Folders-first ordering puts non-media
        // before images before videos.
        assertEquals(
            listOf("readme.txt", "pic.jpg", "movie.mp4"),
            out.items.map { it.name }
        )
        assertEquals(0, out.hiddenByFilterCount)
    }

    @Test
    fun `directory filter MEDIA_ONLY drops non-media files but keeps media`() {
        val out = BrowseItemBuilder.build(
            dirInput(
                filter = BrowserViewModel.BrowseFilter.MEDIA_ONLY,
                itemsInFolder = listOf(
                    vid(1, "movie.mp4"),
                    vid(2, "pic.jpg", mediaType = "IMAGE"),
                    vid(3, "readme.txt")
                )
            )
        )
        assertEquals(listOf("pic.jpg", "movie.mp4"), out.items.map { it.name })
        // 1 hidden (readme.txt): totalBeforeFilter = 0 folders + 3 files = 3; survivors = 2.
        assertEquals(1, out.hiddenByFilterCount)
    }

    @Test
    fun `directory filter UNWATCHED drops watched videos but keeps images`() {
        val out = BrowseItemBuilder.build(
            dirInput(
                filter = BrowserViewModel.BrowseFilter.UNWATCHED,
                itemsInFolder = listOf(
                    vid(1, "fresh.mp4"),
                    vid(2, "seen.mp4"),
                    vid(3, "poster.jpg", mediaType = "IMAGE")
                ),
                records = listOf(
                    // seen.mp4 has playback progress → filtered out by the video UNWATCHED filter.
                    record(videoItemId = 2, position = 100L)
                )
            )
        )
        assertEquals(listOf("poster.jpg", "fresh.mp4"), out.items.map { it.name })
    }

    @Test
    fun `directory filter UNWATCHED drops fully-played leaf folder but keeps folder with subdirs`() {
        // Done: vc=1, pc=1, hasSubDirs=false → !(true&&true&&!false)=false → dropped.
        // HasKids: vc=1, pc=0, hasSubDirs=true → !(true&&false&&false)=true → kept.
        val out = BrowseItemBuilder.build(
            dirInput(
                filter = BrowserViewModel.BrowseFilter.UNWATCHED,
                subFolders = listOf("Done", "HasKids"),
                childVideoStats = listOf(
                    childStat(1L, "Done/a.mp4", "a.mp4", isCompleted = true),
                    childStat(2L, "HasKids/b.mp4", "b.mp4", position = 0L)
                ),
                folderHasSubDirs = mapOf("Done" to false, "HasKids" to true)
            )
        )
        assertEquals(listOf("HasKids"), out.items.map { it.name })
    }

    @Test
    fun `directory filter UNWATCHED keeps fully-played folder when it has deeper subfolders`() {
        // Same stats as Done above but hasSubDirs=true → kept (subdirs may hide unwatched media).
        val out = BrowseItemBuilder.build(
            dirInput(
                filter = BrowserViewModel.BrowseFilter.UNWATCHED,
                subFolders = listOf("Done"),
                childVideoStats = listOf(
                    childStat(1L, "Done/a.mp4", "a.mp4", isCompleted = true)
                ),
                folderHasSubDirs = mapOf("Done" to true)
            )
        )
        assertEquals(listOf("Done"), out.items.map { it.name })
    }

    // ===================================================================================
    // DIRECTORY MODE — star filter EQ / GT / LT (boundary cases)
    // ===================================================================================

    @Test
    fun `directory star EQ keeps only videos whose star equals the value`() {
        val out = BrowseItemBuilder.build(
            dirInput(
                starMode = BrowserViewModel.StarFilterMode.EQ,
                starValue = 2,
                itemsInFolder = listOf(
                    vid(1, "zero.mp4", star = 0),
                    vid(2, "two.mp4", star = 2),
                    vid(3, "five.mp4", star = 5)
                )
            )
        )
        assertEquals(listOf("two.mp4"), out.items.map { it.name })
    }

    @Test
    fun `directory star GT keeps only videos whose star is strictly greater`() {
        val out = BrowseItemBuilder.build(
            dirInput(
                starMode = BrowserViewModel.StarFilterMode.GT,
                starValue = 2,
                itemsInFolder = listOf(
                    vid(1, "zero.mp4", star = 0),
                    vid(2, "two.mp4", star = 2),   // 2 > 2 false — boundary excluded
                    vid(3, "five.mp4", star = 5)
                )
            )
        )
        assertEquals(listOf("five.mp4"), out.items.map { it.name })
    }

    @Test
    fun `directory star LT keeps only videos whose star is strictly less`() {
        val out = BrowseItemBuilder.build(
            dirInput(
                starMode = BrowserViewModel.StarFilterMode.LT,
                starValue = 2,
                itemsInFolder = listOf(
                    vid(1, "zero.mp4", star = 0),
                    vid(2, "two.mp4", star = 2),   // 2 < 2 false — boundary excluded
                    vid(3, "five.mp4", star = 5)
                )
            )
        )
        assertEquals(listOf("zero.mp4"), out.items.map { it.name })
    }

    @Test
    fun `directory star OFF keeps all videos regardless of star value`() {
        val out = BrowseItemBuilder.build(
            dirInput(
                starMode = BrowserViewModel.StarFilterMode.OFF,
                starValue = 5,
                itemsInFolder = listOf(
                    vid(1, "zero.mp4", star = 0),
                    vid(2, "two.mp4", star = 2),
                    vid(3, "five.mp4", star = 5)
                )
            )
        )
        assertEquals(3, out.items.size)
    }

    // ===================================================================================
    // DIRECTORY MODE — sort filename / duration / size, asc and desc
    // ===================================================================================

    @Test
    fun `directory sort filename asc orders videos with natural number sort`() {
        val out = BrowseItemBuilder.build(
            dirInput(
                sortMode = BrowserViewModel.SortMode.FILENAME_ASC,
                itemsInFolder = listOf(
                    vid(1, "v10.mp4"),
                    vid(2, "v2.mp4"),
                    vid(3, "v1.mp4")
                )
            )
        )
        assertEquals(listOf("v1.mp4", "v2.mp4", "v10.mp4"), out.items.map { it.name })
    }

    @Test
    fun `directory sort filename desc reverses natural order`() {
        val out = BrowseItemBuilder.build(
            dirInput(
                sortMode = BrowserViewModel.SortMode.FILENAME_DESC,
                itemsInFolder = listOf(
                    vid(1, "v1.mp4"),
                    vid(2, "v2.mp4"),
                    vid(3, "v10.mp4")
                )
            )
        )
        assertEquals(listOf("v10.mp4", "v2.mp4", "v1.mp4"), out.items.map { it.name })
    }

    @Test
    fun `directory sort duration asc orders videos by duration ascending`() {
        val out = BrowseItemBuilder.build(
            dirInput(
                sortMode = BrowserViewModel.SortMode.DURATION_ASC,
                itemsInFolder = listOf(
                    vid(1, "long.mp4", duration = 300L),
                    vid(2, "short.mp4", duration = 100L),
                    vid(3, "mid.mp4", duration = 200L)
                )
            )
        )
        assertEquals(listOf("short.mp4", "mid.mp4", "long.mp4"), out.items.map { it.name })
    }

    @Test
    fun `directory sort duration desc orders videos by duration descending`() {
        val out = BrowseItemBuilder.build(
            dirInput(
                sortMode = BrowserViewModel.SortMode.DURATION_DESC,
                itemsInFolder = listOf(
                    vid(1, "short.mp4", duration = 100L),
                    vid(2, "long.mp4", duration = 300L),
                    vid(3, "mid.mp4", duration = 200L)
                )
            )
        )
        assertEquals(listOf("long.mp4", "mid.mp4", "short.mp4"), out.items.map { it.name })
    }

    @Test
    fun `directory sort duration treats null duration as zero`() {
        val out = BrowseItemBuilder.build(
            dirInput(
                sortMode = BrowserViewModel.SortMode.DURATION_ASC,
                itemsInFolder = listOf(
                    vid(1, "withdur.mp4", duration = 100L),
                    vid(2, "nulldur.mp4", duration = null)
                )
            )
        )
        // null → 0 → sorts first under ASC
        assertEquals(listOf("nulldur.mp4", "withdur.mp4"), out.items.map { it.name })
    }

    @Test
    fun `directory sort file size asc orders videos by size ascending`() {
        val out = BrowseItemBuilder.build(
            dirInput(
                sortMode = BrowserViewModel.SortMode.FILE_SIZE_ASC,
                itemsInFolder = listOf(
                    vid(1, "big.mp4", fileSize = 3000L),
                    vid(2, "small.mp4", fileSize = 1000L),
                    vid(3, "mid.mp4", fileSize = 2000L)
                )
            )
        )
        assertEquals(listOf("small.mp4", "mid.mp4", "big.mp4"), out.items.map { it.name })
    }

    @Test
    fun `directory sort file size desc orders videos by size descending`() {
        val out = BrowseItemBuilder.build(
            dirInput(
                sortMode = BrowserViewModel.SortMode.FILE_SIZE_DESC,
                itemsInFolder = listOf(
                    vid(1, "small.mp4", fileSize = 1000L),
                    vid(2, "big.mp4", fileSize = 3000L),
                    vid(3, "mid.mp4", fileSize = 2000L)
                )
            )
        )
        assertEquals(listOf("big.mp4", "mid.mp4", "small.mp4"), out.items.map { it.name })
    }

    @Test
    fun `directory duration and size sort still order folders by filename`() {
        // Folders always sort by filename regardless of the file sort axis.
        val out = BrowseItemBuilder.build(
            dirInput(
                sortMode = BrowserViewModel.SortMode.DURATION_DESC,
                subFolders = listOf("zFolder", "aFolder")
            )
        )
        assertEquals(listOf("aFolder", "zFolder"), out.items.map { it.name })
    }

    // ===================================================================================
    // DIRECTORY MODE — playback record mapping onto BrowseItem
    // ===================================================================================

    @Test
    fun `directory attaches playback record fields to matching video items`() {
        val out = BrowseItemBuilder.build(
            dirInput(
                itemsInFolder = listOf(vid(1, "v.mp4")),
                records = listOf(
                    record(videoItemId = 1, position = 50L, duration = 100L, isCompleted = false, isTrashed = true)
                )
            )
        )
        val item = out.items[0]
        assertEquals(50L, item.playbackPosition)
        assertEquals(100L, item.playbackDuration)
        assertFalse(item.isCompleted)
        assertTrue(item.isTrashed)
    }

    @Test
    fun `directory defaults playback fields to zero when no record exists`() {
        val out = BrowseItemBuilder.build(
            dirInput(itemsInFolder = listOf(vid(1, "v.mp4")))
        )
        val item = out.items[0]
        assertEquals(0L, item.playbackPosition)
        assertEquals(0L, item.playbackDuration)
        assertFalse(item.isCompleted)
        assertFalse(item.isTrashed)
    }

    @Test
    fun `directory hasGainAdjusted reflects audioGainDb presence on video entity`() {
        val out = BrowseItemBuilder.build(
            dirInput(
                itemsInFolder = listOf(
                    vid(1, "with.mp4", audioGainDb = 3.5f),
                    vid(2, "without.mp4", audioGainDb = null)
                )
            )
        )
        val byName = out.items.associateBy { it.name }
        assertTrue(byName.getValue("with.mp4").hasGainAdjusted)
        assertFalse(byName.getValue("without.mp4").hasGainAdjusted)
    }

    // ===================================================================================
    // FILE MODE — flat list (DAO already applied filters + duration/size sort)
    // ===================================================================================

    @Test
    fun `file mode empty flat list yields empty items and hidden equals total`() {
        val out = BrowseItemBuilder.build(fileInput(flatTotalCount = 5))
        assertTrue(out.items.isEmpty())
        assertEquals(5, out.flatTotalCount)
        assertEquals(5, out.hiddenByFilterCount)
    }

    @Test
    fun `file mode maps flat rows to browse items preserving all fields`() {
        val out = BrowseItemBuilder.build(
            fileInput(
                flatTotalCount = 1,
                flatItems = listOf(
                    FlatItemWithPlayback(
                        id = 7L,
                        fileName = "movie.mp4",
                        relativePath = "Movies/movie.mp4",
                        fileSize = 12345L,
                        duration = 999L,
                        width = 1920,
                        height = 1080,
                        bitrate = 5000L,
                        frameRate = 30f,
                        audioGainDb = -2.5f,
                        star = 4,
                        mediaType = "VIDEO",
                        dateTaken = 555L,
                        playbackPosition = 60L,
                        playbackDuration = 120L,
                        isCompleted = false,
                        isTrashed = true
                    )
                )
            )
        )
        assertEquals(1, out.items.size)
        val item = out.items[0]
        assertEquals(7L, item.videoItemId)
        assertEquals("movie.mp4", item.name)
        assertEquals("Movies/movie.mp4", item.relativePath)
        assertFalse(item.isDirectory)
        assertEquals(12345L, item.fileSize)
        assertEquals(999L, item.duration)
        assertEquals(1920, item.width)
        assertEquals(1080, item.height)
        assertEquals(5000L, item.bitrate)
        assertEquals(30f, item.frameRate!!)
        assertTrue(item.hasGainAdjusted)
        assertEquals(4, item.star)
        assertEquals("VIDEO", item.mediaType)
        assertEquals(555L, item.dateTaken)
        assertEquals(60L, item.playbackPosition)
        assertEquals(120L, item.playbackDuration)
        assertFalse(item.isCompleted)
        assertTrue(item.isTrashed)
    }

    @Test
    fun `file mode filename asc applies natural sort over the DAO rows`() {
        // Provided in non-natural order — builder re-sorts for FILENAME modes (DAO's COLLATE NOCASE
        // does not reproduce the natural number ordering the UI expects).
        val out = BrowseItemBuilder.build(
            fileInput(
                flatItems = listOf(
                    flat(1, "v10.mp4"),
                    flat(2, "v2.mp4"),
                    flat(3, "v1.mp4")
                ),
                sortMode = BrowserViewModel.SortMode.FILENAME_ASC
            )
        )
        assertEquals(listOf("v1.mp4", "v2.mp4", "v10.mp4"), out.items.map { it.name })
    }

    @Test
    fun `file mode filename desc reverses natural sort`() {
        val out = BrowseItemBuilder.build(
            fileInput(
                flatItems = listOf(
                    flat(1, "v1.mp4"),
                    flat(2, "v2.mp4"),
                    flat(3, "v10.mp4")
                ),
                sortMode = BrowserViewModel.SortMode.FILENAME_DESC
            )
        )
        assertEquals(listOf("v10.mp4", "v2.mp4", "v1.mp4"), out.items.map { it.name })
    }

    @Test
    fun `file mode duration and size sorts preserve DAO order`() {
        // The DAO already ORDER BY'd these; the builder's `else` branch leaves the order intact.
        // This documents the contract: for duration/size axes the builder does NOT re-sort.
        val daoOrder = listOf(
            flat(1, "first.mp4", fileSize = 100L, duration = 10L),
            flat(2, "second.mp4", fileSize = 200L, duration = 20L),
            flat(3, "third.mp4", fileSize = 300L, duration = 30L)
        )
        val expectedNames = listOf("first.mp4", "second.mp4", "third.mp4")

        listOf(
            BrowserViewModel.SortMode.DURATION_ASC,
            BrowserViewModel.SortMode.DURATION_DESC,
            BrowserViewModel.SortMode.FILE_SIZE_ASC,
            BrowserViewModel.SortMode.FILE_SIZE_DESC
        ).forEach { mode ->
            val out = BrowseItemBuilder.build(
                fileInput(flatItems = daoOrder, sortMode = mode)
            )
            assertEquals("DAO order preserved for $mode", expectedNames, out.items.map { it.name })
        }
    }

    @Test
    fun `file mode hidden count equals total minus result size`() {
        val out = BrowseItemBuilder.build(
            fileInput(
                flatTotalCount = 10,
                flatItems = listOf(flat(1, "a.mp4"), flat(2, "b.mp4"), flat(3, "c.mp4"))
            )
        )
        assertEquals(3, out.items.size)
        assertEquals(7, out.hiddenByFilterCount)
    }

    // ===================================================================================
    // Browse-mode routing — FILE ignores directory inputs and vice versa
    // ===================================================================================

    @Test
    fun `file mode ignores directory inputs and produces a flat list`() {
        val out = BrowseItemBuilder.build(
            BrowseItemBuilder.Input(
                folder = "anything",
                sortMode = BrowserViewModel.SortMode.FILENAME_ASC,
                filter = BrowserViewModel.BrowseFilter.ALL,
                starMode = BrowserViewModel.StarFilterMode.OFF,
                starValue = 0,
                browseMode = BrowserViewModel.BrowseMode.FILE,
                flatTotalCount = 2,
                flatItems = listOf(flat(1, "a.mp4"), flat(2, "b.mp4")),
                // Directory inputs present but must be ignored:
                subFolders = listOf("ignored"),
                itemsInFolder = listOf(vid(99, "ignored.mp4")),
                childVideoStats = listOf(childStat(99L, "ignored/x", "x")),
                descendantVideoStats = listOf(childStat(98L, "ignored/deep/x.mp4", "x.mp4")),
                descendantFilterStats = listOf(descStat(98L, "ignored/deep/x.mp4", "x.mp4")),
                folderHasSubDirs = mapOf("ignored" to true),
                records = listOf(record(videoItemId = 99)),
                baseDirSet = setOf("ignored")
            )
        )
        assertEquals(listOf("a.mp4", "b.mp4"), out.items.map { it.name })
        assertEquals(2, out.flatTotalCount)
    }

    @Test
    fun `directory mode ignores file inputs and returns null flatTotalCount`() {
        val out = BrowseItemBuilder.build(
            BrowseItemBuilder.Input(
                folder = "",
                sortMode = BrowserViewModel.SortMode.FILENAME_ASC,
                filter = BrowserViewModel.BrowseFilter.ALL,
                starMode = BrowserViewModel.StarFilterMode.OFF,
                starValue = 0,
                browseMode = BrowserViewModel.BrowseMode.DIRECTORY,
                // File inputs present but must be ignored:
                flatTotalCount = 999,
                flatItems = listOf(flat(1, "ignored.mp4")),
                subFolders = emptyList(),
                itemsInFolder = emptyList(),
                childVideoStats = emptyList(),
                descendantVideoStats = listOf(childStat(97L, "ignored/x.mp4", "x.mp4")),
                descendantFilterStats = listOf(descStat(97L, "ignored/x.mp4", "x.mp4")),
                folderHasSubDirs = emptyMap(),
                records = emptyList(),
                baseDirSet = emptySet()
            )
        )
        assertTrue(out.items.isEmpty())
        // DIRECTORY mode never reports flatTotalCount — VM retains the previous value.
        assertNull(out.flatTotalCount)
    }
}
