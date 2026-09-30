package cn.vectory.syncplayer.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.vectory.syncplayer.NoOpTestApp
import cn.vectory.syncplayer.data.local.dao.VideoItemDao
import cn.vectory.syncplayer.data.local.entity.PlaybackRecordEntity
import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * U2-4a / U2-6: Robolectric DB tests for the two descendant-scan queries added to
 * [VideoItemDao]:
 * - [VideoItemDao.getDescendantVideoStats]: all-depth descendants for the folder mosaic.
 * - [VideoItemDao.getDescendantFilterStats]: descendant rows pre-filtered with the current
 *   MEDIA_ONLY / UNWATCHED / star criteria (fragments ported verbatim from
 *   getFlatItemsFiltered; non-media rows bypass the star gate for ALL-mode parity).
 *
 * Assertions are on row membership / counts / joined playback values — never UI text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = NoOpTestApp::class)
class VideoItemDaoDescendantStatsTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: VideoItemDao

    /** fileName → inserted row id, for playback-record wiring. */
    private val ids = mutableMapOf<String, Long>()

    @Before
    fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.videoItemDao()

        fun row(
            relativePath: String,
            parentPath: String,
            fileName: String,
            mediaType: String,
            star: Int = 0
        ) = VideoItemEntity(
            syncRelationId = 1L,
            relativePath = relativePath,
            parentPath = parentPath,
            fileName = fileName,
            fileSize = 100L,
            mediaType = mediaType,
            star = star
        )

        ids["a.mp4"] = dao.upsert(row("Movies/a.mp4", "Movies", "a.mp4", "VIDEO", star = 0))
        ids["b.mp4"] = dao.upsert(row("Movies/Sub/b.mp4", "Movies/Sub", "b.mp4", "VIDEO", star = 2))
        ids["c.mkv"] = dao.upsert(
            row("Movies/Sub/deep/c.mkv", "Movies/Sub/deep", "c.mkv", "VIDEO", star = 5)
        )
        ids["pic.jpg"] = dao.upsert(row("Movies/pic.jpg", "Movies", "pic.jpg", "IMAGE"))
        ids["readme.txt"] = dao.upsert(
            row("Movies/readme.txt", "Movies", "readme.txt", "OTHER", star = 9)
        )
        ids["s.mp4"] = dao.upsert(row("Shows/s.mp4", "Shows", "s.mp4", "VIDEO"))

        // Playback: b.mp4 partially watched, c.mkv completed, pic.jpg has a zero-progress
        // record (unwatched boundary: position=0, duration=0, not completed).
        db.playbackRecordDao().upsertAll(
            listOf(
                PlaybackRecordEntity(videoItemId = ids.getValue("b.mp4"), position = 100L),
                PlaybackRecordEntity(
                    videoItemId = ids.getValue("c.mkv"),
                    position = 500L,
                    duration = 500L,
                    isCompleted = true
                ),
                PlaybackRecordEntity(videoItemId = ids.getValue("pic.jpg"), position = 0L)
            )
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** U2b: small insert helper for per-test fixtures (keeps setup's 6 canonical rows intact). */
    private suspend fun insertRow(
        relativePath: String,
        parentPath: String,
        fileName: String,
        mediaType: String,
        star: Int = 0
    ): Long = dao.upsert(
        VideoItemEntity(
            syncRelationId = 1L,
            relativePath = relativePath,
            parentPath = parentPath,
            fileName = fileName,
            fileSize = 1L,
            mediaType = mediaType,
            star = star
        )
    )

    // ===================================================================================
    // getDescendantVideoStats (U2-4a) — all depths, mediaType gate, path isolation
    // ===================================================================================

    @Test
    fun `descendantVideoStats returns rows at every depth inside the folder only`() = runBlocking {
        val names = dao.getDescendantVideoStats("Movies").map { it.fileName }.toSet()
        // Direct (a), depth-2 (b), depth-3 (c) media rows; image admitted (builder refines).
        assertEquals(setOf("a.mp4", "b.mp4", "c.mkv", "pic.jpg"), names)
        // Rows outside the folder never leak in.
        assertFalse(names.contains("s.mp4"))
    }

    @Test
    fun `descendantVideoStats gates by mediaType VIDEO and IMAGE`() = runBlocking {
        val names = dao.getDescendantVideoStats("Movies").map { it.fileName }
        assertFalse(names.contains("readme.txt"))
    }

    @Test
    fun `descendantVideoStats at root returns every media row with a non-empty parent`() = runBlocking {
        // U2b: real loose root file (parentPath="") — the root arm must exclude it.
        insertRow("loose.mp4", parentPath = "", fileName = "loose.mp4", mediaType = "VIDEO")
        val names = dao.getDescendantVideoStats("").map { it.fileName }.toSet()
        assertEquals(setOf("a.mp4", "b.mp4", "c.mkv", "pic.jpg", "s.mp4"), names)
        assertFalse(names.contains("loose.mp4"))
    }

    @Test
    fun `descendantVideoStats left-joins playback defaults for unplayed rows`() = runBlocking {
        val byName = dao.getDescendantVideoStats("Movies").associateBy { it.fileName }
        val a = byName.getValue("a.mp4")
        assertEquals(0L, a.position)
        assertFalse(a.isCompleted)
        val b = byName.getValue("b.mp4")
        assertEquals(100L, b.position)
    }

    // ===================================================================================
    // getDescendantFilterStats (U2c) — path-scope CANDIDATES only. No media/star/unwatched
    // SQL gates: mediaType is unreliable in production (sync placeholder rows do NOT set it,
    // so non-media files land on the entity default "VIDEO" and are never corrected), so the
    // filter/star semantics live in BrowseItemBuilder — see the layered builder tests there.
    // ===================================================================================

    @Test
    fun `descendantFilterStats returns every path-scope candidate regardless of type or playback state`() = runBlocking {
        val names = dao.getDescendantFilterStats("Movies").map { it.fileName }.toSet()
        // 候选行不分类型/播放状态：非媒体 readme.txt、已看 b/c、未看 a/pic 全部原样返回。
        assertEquals(setOf("a.mp4", "b.mp4", "c.mkv", "pic.jpg", "readme.txt"), names)
        // 文件夹外部行不可见。
        assertFalse(names.contains("s.mp4"))
    }

    @Test
    fun `descendantFilterStats returns candidates for production-default rows without explicit mediaType`() = runBlocking {
        // 生产占位插入方式（SyncDownloadPipeline 占位行）：不设 mediaType，落到实体默认 "VIDEO"。
        dao.upsert(
            VideoItemEntity(
                syncRelationId = 1L,
                relativePath = "Movies/notes.txt",
                parentPath = "Movies",
                fileName = "notes.txt",
                fileSize = 1L
            )
        )
        val row = dao.getDescendantFilterStats("Movies").first { it.fileName == "notes.txt" }
        // 正是移除 mediaType SQL 门的原因：非媒体 .txt 的 mediaType 同为 "VIDEO"。
        assertEquals("VIDEO", row.mediaType)
        assertEquals(0, row.star)
        assertEquals(0L, row.playbackPosition)
        assertFalse(row.isCompleted)
    }

    @Test
    fun `descendantFilterStats maps playback join columns with coalesced defaults`() = runBlocking {
        val byName = dao.getDescendantFilterStats("Movies").associateBy { it.fileName }
        val a = byName.getValue("a.mp4")
        assertEquals(0L, a.playbackPosition)
        assertEquals(0L, a.playbackDuration)
        assertFalse(a.isCompleted)
        val b = byName.getValue("b.mp4")
        assertEquals(100L, b.playbackPosition)
        assertEquals(2, b.star)
        assertEquals("VIDEO", b.mediaType)
    }

    @Test
    fun `descendantFilterStats at root returns candidates from all folders but excludes loose root files`() = runBlocking {
        // U2b: real loose root file (parentPath="") — the root arm must exclude it even though
        // every row is an admissible candidate now.
        insertRow("loose.mp4", parentPath = "", fileName = "loose.mp4", mediaType = "VIDEO")
        val names = dao.getDescendantFilterStats("").map { it.fileName }.toSet()
        // Root ('')：所有文件夹的候选行（含已看/非媒体），根散落文件被根臂排除。
        assertEquals(setOf("a.mp4", "b.mp4", "c.mkv", "pic.jpg", "readme.txt", "s.mp4"), names)
        assertFalse(names.contains("loose.mp4"))
    }

    @Test
    fun `descendant queries treat percent and underscore in folder paths literally`() = runBlocking {
        // U2c: 通配符文件夹真实用例（两后代查询复用）——substr 前缀匹配按字面字节比较，
        // "a%" 不吸收 "abc"、"a_" 不吸收 "ab"、"x/a" 不属于 "a"。
        insertRow("a%/v.mp4", "a%", "v.mp4", "VIDEO")
        insertRow("abc/v.mp4", "abc", "abc.mp4", "VIDEO")
        insertRow("a_/w.mp4", "a_", "w.mp4", "VIDEO")
        insertRow("ab/w2.mp4", "ab", "w2.mp4", "VIDEO")
        insertRow("x/a/middle.mp4", "x/a", "middle.mp4", "VIDEO")
        val expectedByQuery = mapOf("a%" to setOf("a%/v.mp4"), "a_" to setOf("a_/w.mp4"))
        for ((query, expected) in expectedByQuery) {
            assertEquals(expected, dao.getDescendantVideoStats(query).map { it.relativePath }.toSet())
            assertEquals(expected, dao.getDescendantFilterStats(query).map { it.relativePath }.toSet())
        }
        // 中间路径段：查 "a" 不得命中 x/a/middle.mp4（两查询同口径）。
        assertFalse(dao.getDescendantVideoStats("a").map { it.relativePath }.contains("x/a/middle.mp4"))
        assertFalse(dao.getDescendantFilterStats("a").map { it.relativePath }.contains("x/a/middle.mp4"))
    }

    // ===================================================================================
    // U2b-2: sort determinism — ORDER BY (fileName NOCASE, relativePath BINARY, id)
    // ===================================================================================

    @Test
    fun `descendantVideoStats orders same-named videos deterministically and first nine are stable`() = runBlocking {
        // 12 same-named videos ("dup.mp4") spread over three depths x four branches.
        val paths = mutableListOf<String>()
        for (round in 0..3) {
            paths += "Movies/D$round/dup.mp4"
            paths += "Movies/D$round/L$round/dup.mp4"
            paths += "Movies/D$round/L$round/M$round/dup.mp4"
        }
        val insertedIds = paths.associateWith { path ->
            insertRow(path, path.substringBeforeLast('/'), "dup.mp4", "VIDEO")
        }
        val rows = dao.getDescendantVideoStats("Movies").filter { it.fileName == "dup.mp4" }
        assertEquals(12, rows.size)
        // fileName ties break by relativePath COLLATE BINARY (Kotlin String compare is binary).
        val expectedOrder = paths.sortedWith(compareBy { it })
        assertEquals(expectedOrder, rows.map { it.relativePath })
        assertEquals(expectedOrder.map { insertedIds.getValue(it) }, rows.map { it.id })
        // take(9) mosaic source must be identity-stable across repeated queries.
        val again = dao.getDescendantVideoStats("Movies").filter { it.fileName == "dup.mp4" }
        assertEquals(rows.take(9).map { it.id }, again.take(9).map { it.id })
    }

    // ===================================================================================
    // U2b test-gap: path isolation — prefix boundary, middle path segments, wildcard chars
    // ===================================================================================

    @Test
    fun `descendant queries keep folder prefix boundaries exact`() = runBlocking {
        // "a" must not absorb "ab"; "Movies" must not absorb "Movies2" (non-'/' continuation).
        insertRow("ab/x.mp4", "ab", "x.mp4", "VIDEO")
        insertRow("Movies2/w.mp4", "Movies2", "w.mp4", "VIDEO")
        insertRow("a/y.mp4", "a", "y.mp4", "VIDEO")
        insertRow("a/mid/z.mp4", "a/mid", "z.mp4", "VIDEO")
        val a = dao.getDescendantVideoStats("a").map { it.fileName }.toSet()
        assertEquals(setOf("y.mp4", "z.mp4"), a)
        val ab = dao.getDescendantVideoStats("ab").map { it.fileName }.toSet()
        assertEquals(setOf("x.mp4"), ab)
        val movies = dao.getDescendantVideoStats("Movies").map { it.fileName }.toSet()
        assertFalse(movies.contains("w.mp4"))
        // Middle path segment: only rows strictly below a/mid (candidate scan, same predicate).
        val mid = dao.getDescendantFilterStats("a/mid").map { it.fileName }.toSet()
        assertEquals(setOf("z.mp4"), mid)
    }

    @Test
    fun `descendant queries match percent and underscore names binary-exactly`() = runBlocking {
        // Substr matching is not LIKE: '%' and '_' inside names are literal bytes; a sibling
        // folder "a_x" must not leak into folder "a".
        insertRow("a/pct%100.mp4", "a", "pct%100.mp4", "VIDEO")
        insertRow("a/under_score.mp4", "a", "under_score.mp4", "VIDEO")
        insertRow("a_x/y.mp4", "a_x", "y.mp4", "VIDEO")
        val a = dao.getDescendantVideoStats("a").map { it.fileName }.toSet()
        assertEquals(setOf("pct%100.mp4", "under_score.mp4"), a)
        assertFalse(a.contains("y.mp4"))
    }

    // ===================================================================================
    // U2c: candidate-layer — playback facts are carried through verbatim; the "watched ⇒
    // not unwatched" DECISION is builder-side (see BrowseItemBuilderTest).
    // ===================================================================================

    @Test
    fun `descendantFilterStats returns zero-progress completed rows as candidates`() = runBlocking {
        val id = insertRow("Movies/edge.mp4", "Movies", "edge.mp4", "VIDEO")
        db.playbackRecordDao().upsert(
            PlaybackRecordEntity(videoItemId = id, position = 0L, duration = 0L, isCompleted = true)
        )
        val edge = dao.getDescendantFilterStats("Movies").first { it.fileName == "edge.mp4" }
        // 零进度但 isCompleted=true 的行原样带出，判定交给 builder 的共享判定函数。
        assertEquals(0L, edge.playbackPosition)
        assertEquals(0L, edge.playbackDuration)
        assertTrue(edge.isCompleted)
    }
}
