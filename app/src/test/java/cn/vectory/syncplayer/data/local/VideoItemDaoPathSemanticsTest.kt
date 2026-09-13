package cn.vectory.syncplayer.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.vectory.syncplayer.NoOpTestApp
import cn.vectory.syncplayer.data.local.dao.VideoItemDao
import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Path-semantics tests for [VideoItemDao] prefix queries (BUG-011 / BUG-022 family).
 *
 * SQLite `LIKE` folds ASCII case and treats `_`/`%` as wildcards; the prefix family
 * must instead use binary-exact `substr` comparison so that:
 *  - wildcard twins (`one_piece` vs `one-piece`) never cross-match, and
 *  - ASCII case twins (`Movies` vs `movies`) never cross-match.
 *
 * This matters for deleteFolder: a too-wide prefix match deletes sibling-directory
 * ciphertext irrecoverably. Asserts counts/ids/paths only — never user-visible text.
 *
 * Runs on the JVM via Robolectric against a real in-memory Room DB (no device needed;
 * the LIKE-vs-substr semantics are pure SQLite and deterministic on any build).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = NoOpTestApp::class)
class VideoItemDaoPathSemanticsTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: VideoItemDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.videoItemDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun insert(
        syncRelationId: Long = 1L,
        relativePath: String,
        parentPath: String = "",
        fileName: String = relativePath.substringAfterLast('/'),
        fileSize: Long = 1L,
        star: Int = 0,
        mediaType: String = "VIDEO",
        audioGainDb: Float? = null
    ): Long = runBlocking {
        dao.upsert(
            VideoItemEntity(
                syncRelationId = syncRelationId,
                relativePath = relativePath,
                parentPath = parentPath,
                fileName = fileName,
                fileSize = fileSize,
                star = star,
                mediaType = mediaType,
                audioGainDb = audioGainDb,
            )
        )
    }

    /**
     * Target tree (relation 1): one_piece (self), one_piece/a.mp4, one_piece/sub/b.mp4.
     * Wildcard twin: one-piece/x.mp4 (parentPath "one-piece").
     * ASCII case twin: ONE_PIECE/x.mp4 (parentPath "ONE_PIECE").
     */
    private fun insertTwinsFixture(syncRelationId: Long = 1L): List<Long> = listOf(
        insert(
            syncRelationId = syncRelationId,
            relativePath = "one_piece", parentPath = "", fileSize = 1L
        ),
        insert(
            syncRelationId = syncRelationId,
            relativePath = "one_piece/a.mp4", parentPath = "one_piece", fileSize = 2L
        ),
        insert(
            syncRelationId = syncRelationId,
            relativePath = "one_piece/sub/b.mp4", parentPath = "one_piece/sub", fileSize = 4L
        ),
        insert(
            syncRelationId = syncRelationId,
            relativePath = "one-piece/x.mp4", parentPath = "one-piece", fileSize = 100L
        ),
        insert(
            syncRelationId = syncRelationId,
            relativePath = "ONE_PIECE/x.mp4", parentPath = "ONE_PIECE", fileSize = 100L
        )
    )

    // ----------------------------------------------------------------- getByPathPrefix

    @Test
    fun `getByPathPrefix excludes underscore wildcard twin`() = runBlocking {
        insertTwinsFixture()

        val matches = dao.getByPathPrefix("one_piece")

        assertEquals(3, matches.size)
        assertTrue(matches.none { it.relativePath.startsWith("one-piece") })
    }

    @Test
    fun `getByPathPrefix excludes ascii case twin`() = runBlocking {
        insertTwinsFixture()

        val matches = dao.getByPathPrefix("ONE_PIECE")

        assertEquals(1, matches.size)
        assertEquals("ONE_PIECE/x.mp4", matches[0].relativePath)
    }

    @Test
    fun `getByPathPrefix folder containing percent matches nothing`() = runBlocking {
        insert(syncRelationId = 1L, relativePath = "a/b.mp4", parentPath = "a", fileSize = 1L)
        insert(syncRelationId = 1L, relativePath = "top.mp4", parentPath = "", fileSize = 1L)

        val matches = dao.getByPathPrefix("%")

        assertTrue(matches.isEmpty())
    }

    @Test
    fun `getByPathPrefixAndRelation twins plus relation isolation`() = runBlocking {
        insertTwinsFixture(syncRelationId = 1L)
        insertTwinsFixture(syncRelationId = 2L)

        val matches = dao.getByPathPrefixAndRelation("one_piece", 1L)

        assertEquals(3, matches.size)
        assertTrue(matches.all { it.syncRelationId == 1L })
        assertTrue(matches.none { it.relativePath.startsWith("one-piece") })
    }

    // ----------------------------------------------------------------- getSubFoldersOnce

    @Test
    fun `getSubFoldersOnce excludes case and wildcard twins`() = runBlocking {
        insert(relativePath = "one_piece/sub/a.mp4", parentPath = "one_piece/sub", fileSize = 1L)
        insert(relativePath = "one-piece/s/x.mp4", parentPath = "one-piece/s", fileSize = 1L)
        insert(relativePath = "ONE_PIECE/s/y.mp4", parentPath = "ONE_PIECE/s", fileSize = 1L)

        val subs = dao.getSubFoldersOnce("one_piece")

        assertEquals(listOf("sub"), subs)
    }

    // ----------------------------------------------------------------- getDeepParentPaths

    @Test
    fun `getDeepParentPaths excludes twins`() = runBlocking {
        insert(
            relativePath = "one_piece/sub/deep/f.mp4",
            parentPath = "one_piece/sub/deep", fileSize = 1L
        )
        insert(
            relativePath = "one-piece/sub/deep/g.mp4",
            parentPath = "one-piece/sub/deep", fileSize = 1L
        )

        val deep = dao.getDeepParentPaths("one_piece")

        assertEquals(listOf("one_piece/sub/deep"), deep)
    }

    // ----------------------------------------------------------------- getRecursiveFolderStats

    @Test
    fun `getRecursiveFolderStats counts descendants only and excludes twins`() = runBlocking {
        // Self row must NOT be counted (descendant-only branch), twins must not leak in.
        insert(relativePath = "one_piece", parentPath = "", fileSize = 1L)
        insert(relativePath = "one_piece/a.mp4", parentPath = "one_piece", fileSize = 2L)
        insert(relativePath = "one_piece/sub/b.mp4", parentPath = "one_piece/sub", fileSize = 4L)
        insert(relativePath = "one-piece/x.mp4", parentPath = "one-piece", fileSize = 100L)
        insert(relativePath = "ONE_PIECE/x.mp4", parentPath = "ONE_PIECE", fileSize = 100L)

        val stats = dao.getRecursiveFolderStats("one_piece")

        assertEquals(2, stats.totalFileCount)
        assertEquals(2, stats.totalVideoCount)
        assertEquals(0, stats.totalImageCount)
        assertEquals(6L, stats.totalSize)
    }

    // ----------------------------------------------------------------- getChildVideoStats

    @Test
    fun `getChildVideoStats matches only direct child folder videos`() = runBlocking {
        val currentLevelId = insert(
            relativePath = "one_piece/a.mp4", parentPath = "one_piece", fileSize = 1L
        )
        val directChildId = insert(
            relativePath = "one_piece/sub/b.mp4", parentPath = "one_piece/sub", fileSize = 2L
        )
        insert(
            relativePath = "one_piece/sub/deep/c.mp4",
            parentPath = "one_piece/sub/deep", fileSize = 3L
        )
        insert(
            relativePath = "one-piece/s/x.mp4", parentPath = "one-piece/s", fileSize = 100L
        )
        insert(
            relativePath = "ONE_PIECE/s/y.mp4", parentPath = "ONE_PIECE/s", fileSize = 100L
        )

        val stats = dao.getChildVideoStats("one_piece")

        assertEquals(setOf(directChildId), stats.map { it.id }.toSet())
        assertEquals(1, stats.size)
        // Current-level row (parentPath == folder, no trailing segment) is excluded by design.
        assertTrue(stats.none { it.id == currentLevelId })
    }

    // ----------------------------------------------------------------- searchByFileNameInFolder

    @Test
    fun `searchByFileNameInFolder scopes to exact folder tree`() = runBlocking {
        insert(
            relativePath = "vacation/sub/H.mp4",
            parentPath = "vacation/sub", fileName = "H.mp4", fileSize = 1L
        )
        insert(
            relativePath = "Vacation/sub/H.mp4",
            parentPath = "Vacation/sub", fileName = "H.mp4", fileSize = 2L
        )

        val results = dao.searchByFileNameInFolder("H", "vacation").first()

        assertEquals(1, results.size)
        assertEquals("vacation/sub/H.mp4", results[0].relativePath)
    }

    // ----------------------------------------------------------------- getParentPathsInFolder

    @Test
    fun `getParentPathsInFolder excludes twins`() = runBlocking {
        insert(relativePath = "vacation/a/x.mp4", parentPath = "vacation/a", fileSize = 1L)
        insert(relativePath = "Vacation/a/y.mp4", parentPath = "Vacation/a", fileSize = 1L)

        val parents = dao.getParentPathsInFolder("vacation").first()

        assertEquals(listOf("vacation/a"), parents)
    }

    // ----------------------------------------------------------------- regression lock

    @Test
    fun `existing boundary behavior stays`() = runBlocking {
        insert(relativePath = "folder", parentPath = "", fileSize = 1L)
        insert(relativePath = "folder/a.mp4", parentPath = "folder", fileSize = 2L)
        insert(relativePath = "folder/sub/b.mp4", parentPath = "folder/sub", fileSize = 3L)
        insert(relativePath = "folderX.mp4", parentPath = "", fileSize = 4L)

        val matches = dao.getByPathPrefix("folder")

        assertEquals(3, matches.size)
        assertTrue(matches.none { it.relativePath == "folderX.mp4" })
    }

    // ----------------------------------------------------------------- BUG-022 empty segment defense

    @Test
    fun `getSubFoldersOnce filters empty segment folder names at sub level`() = runBlocking {
        insert(relativePath = "Movies//S1/x.mp4", parentPath = "Movies//S1", fileSize = 1L)
        insert(relativePath = "Movies/S2/y.mp4", parentPath = "Movies/S2", fileSize = 1L)

        val subs = dao.getSubFoldersOnce("Movies")

        assertEquals(listOf("S2"), subs)
    }

    @Test
    fun `getSubFoldersOnce root filters empty names`() = runBlocking {
        insert(relativePath = "//evil/x.mp4", parentPath = "//evil", fileSize = 1L)
        insert(relativePath = "a/y.mp4", parentPath = "a", fileSize = 1L)

        val subs = dao.getSubFoldersOnce("")

        assertEquals(listOf("a"), subs)
    }

    @Test
    fun `getSubFolders flow variant filters empty names`() = runBlocking {
        insert(relativePath = "Movies//S1/x.mp4", parentPath = "Movies//S1", fileSize = 1L)
        insert(relativePath = "Movies/S2/y.mp4", parentPath = "Movies/S2", fileSize = 1L)

        val subs = dao.getSubFolders("Movies").first()

        assertEquals(listOf("S2"), subs)
    }

    // ----------------------------------------------------------------- BUG-013 updateAudioGain

    @Test
    fun `updateAudioGain persists gain without touching other columns`() = runBlocking {
        val id = insert(
            relativePath = "gain/a.mp4", parentPath = "gain",
            fileSize = 100L, star = 3, audioGainDb = null
        )

        dao.updateAudioGain(id, 6f)

        val row = dao.getById(id)!!
        assertEquals(6f, row.audioGainDb!!, 1e-6f)
        assertEquals(3, row.star)
        assertEquals(100L, row.fileSize)
    }

    @Test
    fun `updateAudioGain on deleted id does not resurrect row`() = runBlocking {
        val id = insert(relativePath = "gain/deleted.mp4", parentPath = "gain", fileSize = 1L)
        dao.deleteById(id)

        dao.updateAudioGain(id, 6f)

        assertEquals(0, dao.getAll().size)
    }

    @Test
    fun `updateAudioGain accepts null clearing gain`() = runBlocking {
        val id = insert(
            relativePath = "gain/b.mp4", parentPath = "gain",
            fileSize = 1L, audioGainDb = 6f
        )

        dao.updateAudioGain(id, null)

        assertNull(dao.getById(id)!!.audioGainDb)
    }
}
