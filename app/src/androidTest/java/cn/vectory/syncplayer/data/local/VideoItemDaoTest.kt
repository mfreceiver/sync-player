package cn.vectory.syncplayer.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.vectory.syncplayer.data.local.dao.PlaybackRecordDao
import cn.vectory.syncplayer.data.local.dao.VideoItemDao
import cn.vectory.syncplayer.data.local.entity.PlaybackRecordEntity
import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented DAO tests for [VideoItemDao] against a real in-memory Room DB.
 *
 * Uses Room.inMemoryDatabaseBuilder + ApplicationProvider (no Robolectric — the project
 * has no Robolectric dependency and adding one is high-cost/low-value for this personal-use
 * scope). This exercises the real SQL queries, indices, and constraints — not mocks.
 *
 * Covers: root/sub/deep directory queries, same-name different-relation, parentPath index,
 * relation filtering, sort order, star update, LIKE search, and delete isolation/cascade.
 */
@RunWith(AndroidJUnit4::class)
class VideoItemDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: VideoItemDao
    private lateinit var playbackDao: PlaybackRecordDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .build()
        dao = db.videoItemDao()
        playbackDao = db.playbackRecordDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun insert(
        id: Long = 0,
        syncRelationId: Long,
        relativePath: String,
        parentPath: String = "",
        fileName: String = relativePath.substringAfterLast('/'),
        fileSize: Long,
        star: Int = 0,
        mediaType: String = "VIDEO"
    ): Long = runBlocking {
        dao.upsert(
            VideoItemEntity(
                id = id,
                syncRelationId = syncRelationId,
                relativePath = relativePath,
                parentPath = parentPath,
                fileName = fileName,
                fileSize = fileSize,
                star = star,
                mediaType = mediaType
            )
        )
    }

    // ===========================================================================
    // ROUNDTRIP + UPSERT SEMANTICS
    // ===========================================================================

    @Test
    fun `upsert inserts then getById returns the row`() {
        val rowId = insert(syncRelationId = 1L, relativePath = "a.mp4", fileSize = 100L)
        val got = runBlocking { dao.getById(rowId) }
        assertNotNull(got)
        assertEquals("a.mp4", got!!.relativePath)
        assertEquals(100L, got.fileSize)
        assertEquals("a.mp4", got.fileName)
    }

    @Test
    fun `upsert with same id updates existing row`() {
        val rowId = insert(syncRelationId = 1L, relativePath = "a.mp4", fileSize = 100L)
        runBlocking { dao.upsert(VideoItemEntity(id = rowId, syncRelationId = 1L, relativePath = "a.mp4", fileName = "a.mp4", fileSize = 999L)) }
        val got = runBlocking { dao.getById(rowId) }
        assertEquals(999L, got!!.fileSize)
    }

    // ===========================================================================
    // DIRECTORY QUERIES (parentPath index)
    // ===========================================================================

    @Test
    fun `getItemsInFolder root returns only root files`() {
        insert(syncRelationId = 1L, relativePath = "a.mp4", parentPath = "", fileSize = 10L)
        insert(syncRelationId = 1L, relativePath = "b.mp4", parentPath = "", fileSize = 20L)
        insert(syncRelationId = 1L, relativePath = "sub/x.mp4", parentPath = "sub", fileSize = 30L)
        val root = runBlocking { dao.getItemsInFolder("").first() }
        assertEquals(2, root.size)
        assertTrue(root.all { it.parentPath == "" })
    }

    @Test
    fun `getItemsInFolder subfolder returns only that folder files`() {
        insert(syncRelationId = 1L, relativePath = "sub/x.mp4", parentPath = "sub", fileSize = 30L)
        insert(syncRelationId = 1L, relativePath = "sub/y.mp4", parentPath = "sub", fileSize = 40L)
        insert(syncRelationId = 1L, relativePath = "a.mp4", parentPath = "", fileSize = 10L)
        insert(syncRelationId = 1L, relativePath = "other/z.mp4", parentPath = "other", fileSize = 50L)
        val sub = runBlocking { dao.getItemsInFolder("sub").first() }
        assertEquals(2, sub.size)
        assertTrue(sub.all { it.parentPath == "sub" })
    }

    @Test
    fun `getItemsInFolder deep nested path returns only that folder`() {
        insert(syncRelationId = 1L, relativePath = "a/b/c/d.mp4", parentPath = "a/b/c", fileSize = 1L)
        insert(syncRelationId = 1L, relativePath = "a/b/c/e.mp4", parentPath = "a/b/c", fileSize = 2L)
        insert(syncRelationId = 1L, relativePath = "a/b/f.mp4", parentPath = "a/b", fileSize = 3L)
        insert(syncRelationId = 1L, relativePath = "a/g.mp4", parentPath = "a", fileSize = 4L)
        val deep = runBlocking { dao.getItemsInFolder("a/b/c").first() }
        assertEquals(2, deep.size)
        assertTrue(deep.all { it.parentPath == "a/b/c" })
    }

    @Test
    fun `getItemsInFolder empty folder returns empty list`() {
        insert(syncRelationId = 1L, relativePath = "a.mp4", parentPath = "", fileSize = 10L)
        val empty = runBlocking { dao.getItemsInFolder("nonexistent").first() }
        assertEquals(0, empty.size)
    }

    // ===========================================================================
    // SAME NAME — DIFFERENT RELATION (unique index on relativePath + syncRelationId)
    // ===========================================================================

    @Test
    fun `same relativePath different relationId both stored`() {
        insert(syncRelationId = 1L, relativePath = "a.mp4", parentPath = "", fileSize = 10L)
        insert(syncRelationId = 2L, relativePath = "a.mp4", parentPath = "", fileSize = 20L)
        val all = runBlocking { dao.getAll() }
        assertEquals(2, all.size)
    }

    @Test
    fun `getByRelativePathAndRelationId returns the correct relation row`() {
        insert(syncRelationId = 1L, relativePath = "a.mp4", parentPath = "", fileSize = 10L)
        insert(syncRelationId = 2L, relativePath = "a.mp4", parentPath = "", fileSize = 20L)
        val r1 = runBlocking { dao.getByRelativePathAndRelationId("a.mp4", 1L) }
        val r2 = runBlocking { dao.getByRelativePathAndRelationId("a.mp4", 2L) }
        assertEquals(10L, r1!!.fileSize)
        assertEquals(20L, r2!!.fileSize)
    }

    @Test
    fun `same relativePath in same relation violates unique constraint`() {
        insert(syncRelationId = 1L, relativePath = "duplicate.mp4", parentPath = "", fileSize = 10L)

        var thrown: Throwable? = null
        try {
            insert(syncRelationId = 1L, relativePath = "duplicate.mp4", parentPath = "", fileSize = 20L)
        } catch (t: Throwable) {
            thrown = t
        }

        // Room 2.7 @Upsert generates a bare INSERT with a runtime UPDATE(id=?) fallback; on a
        // natural-key conflict with id=0 the SQLiteConstraintException may not reach the caller.
        // The contract actually guaranteed by the unique(relativePath, syncRelationId) index is:
        // at most one row per (relation, path), with the original row intact.
        val all = runBlocking { dao.getAll() }
        assertEquals("at most one row per (syncRelationId, relativePath)", 1, all.size)
        assertTrue(
            "duplicate must be rejected (thrown) or leave the original row intact",
            thrown != null || all[0].fileSize == 10L
        )
    }

    // ===========================================================================
    // RELATION FILTERING
    // ===========================================================================

    @Test
    fun `getBySyncRelationId returns only that relation items`() {
        insert(syncRelationId = 1L, relativePath = "a.mp4", fileSize = 10L)
        insert(syncRelationId = 1L, relativePath = "b.mp4", fileSize = 20L)
        insert(syncRelationId = 2L, relativePath = "c.mp4", fileSize = 30L)
        val r1 = runBlocking { dao.getBySyncRelationId(1L) }
        assertEquals(2, r1.size)
        assertTrue(r1.all { it.syncRelationId == 1L })
    }

    // ===========================================================================
    // SORT ORDER (COLLATE NOCASE ASC)
    // ===========================================================================

    @Test
    fun `getItemsInFolder sorts case-insensitively by fileName`() {
        insert(syncRelationId = 1L, relativePath = "B.mp4", parentPath = "", fileName = "B.mp4", fileSize = 1L)
        insert(syncRelationId = 1L, relativePath = "a.mp4", parentPath = "", fileName = "a.mp4", fileSize = 2L)
        insert(syncRelationId = 1L, relativePath = "C.mp4", parentPath = "", fileName = "C.mp4", fileSize = 3L)
        val items = runBlocking { dao.getItemsInFolder("").first() }
        assertEquals(listOf("a.mp4", "B.mp4", "C.mp4"), items.map { it.fileName })
    }

    // ===========================================================================
    // STAR UPDATE
    // ===========================================================================

    @Test
    fun `updateStar persists`() {
        val rowId = insert(syncRelationId = 1L, relativePath = "a.mp4", fileSize = 10L)
        runBlocking { dao.updateStar(rowId, 5) }
        assertEquals(5, runBlocking { dao.getById(rowId) }!!.star)
    }

    // ===========================================================================
    // LIKE SEARCH
    // ===========================================================================

    @Test
    fun `searchByFileName matches substring case-insensitively`() {
        insert(syncRelationId = 1L, relativePath = "Holiday2024.mp4", parentPath = "", fileName = "Holiday2024.mp4", fileSize = 1L)
        insert(syncRelationId = 1L, relativePath = "holiday_clip.mp4", parentPath = "", fileName = "holiday_clip.mp4", fileSize = 2L)
        insert(syncRelationId = 1L, relativePath = "Work.mp4", parentPath = "", fileName = "Work.mp4", fileSize = 3L)
        val results = runBlocking { dao.searchByFileName("Holiday").first() }
        assertEquals(2, results.size)
        assertTrue(results.all { it.fileName.contains("oliday") })
    }

    @Test
    fun `searchByFileNameInFolder scopes to folder tree`() {
        insert(syncRelationId = 1L, relativePath = "vacation/Holiday2024.mp4", parentPath = "vacation", fileName = "Holiday2024.mp4", fileSize = 1L)
        insert(syncRelationId = 1L, relativePath = "work/Holiday2024.mp4", parentPath = "work", fileName = "Holiday2024.mp4", fileSize = 2L)
        val rootResults = runBlocking { dao.searchByFileNameInFolder("Holiday2024", "").first() }
        assertEquals(2, rootResults.size)
        val vacationResults = runBlocking { dao.searchByFileNameInFolder("Holiday2024", "vacation").first() }
        assertEquals(1, vacationResults.size)
        assertEquals("vacation/Holiday2024.mp4", vacationResults[0].relativePath)
    }

    // ===========================================================================
    // PARENT PATH PREFIX QUERY (getByPathPrefix)
    // ===========================================================================

    @Test
    fun `getByPathPrefix returns exact and descendant matches`() {
        insert(syncRelationId = 1L, relativePath = "folder", parentPath = "", fileSize = 1L)
        insert(syncRelationId = 1L, relativePath = "folder/a.mp4", parentPath = "folder", fileSize = 2L)
        insert(syncRelationId = 1L, relativePath = "folder/sub/b.mp4", parentPath = "folder/sub", fileSize = 3L)
        insert(syncRelationId = 1L, relativePath = "folderX.mp4", parentPath = "", fileSize = 4L)
        val matches = runBlocking { dao.getByPathPrefix("folder") }
        assertEquals(3, matches.size)
        // Must NOT match folderX.mp4 (path-boundary safe).
        assertTrue(matches.none { it.relativePath == "folderX.mp4" })
    }

    // ===========================================================================
    // SUBFOLDER LISTING
    // ===========================================================================

    @Test
    fun `getSubFoldersOnce returns direct children only`() {
        insert(syncRelationId = 1L, relativePath = "a/x.mp4", parentPath = "a", fileSize = 1L)
        insert(syncRelationId = 1L, relativePath = "a/b/y.mp4", parentPath = "a/b", fileSize = 2L)
        insert(syncRelationId = 1L, relativePath = "c/z.mp4", parentPath = "c", fileSize = 3L)
        val rootSubs = runBlocking { dao.getSubFoldersOnce("") }
        assertEquals(listOf("a", "c"), rootSubs.sorted())
        val aSubs = runBlocking { dao.getSubFoldersOnce("a") }
        assertEquals(listOf("b"), aSubs)
    }

    // ===========================================================================
    // DELETE — ISOLATION & CASCADE
    // ===========================================================================

    @Test
    fun `deleteByRelativePathAndRelationId only deletes matching relation`() {
        insert(syncRelationId = 1L, relativePath = "a.mp4", fileSize = 10L)
        insert(syncRelationId = 2L, relativePath = "a.mp4", fileSize = 20L)
        runBlocking { dao.deleteByRelativePathAndRelationId("a.mp4", 1L) }
        val remaining = runBlocking { dao.getAll() }
        assertEquals(1, remaining.size)
        assertEquals(2L, remaining[0].syncRelationId)
    }

    @Test
    fun `deleteById removes the row`() {
        val rowId = insert(syncRelationId = 1L, relativePath = "a.mp4", fileSize = 10L)
        runBlocking { dao.deleteById(rowId) }
        assertNull(runBlocking { dao.getById(rowId) })
    }

    @Test
    fun `deleteAll clears the table`() {
        insert(syncRelationId = 1L, relativePath = "a.mp4", fileSize = 10L)
        insert(syncRelationId = 1L, relativePath = "b.mp4", fileSize = 20L)
        runBlocking { dao.deleteAll() }
        assertEquals(0, runBlocking { dao.getAll() }.size)
    }

    @Test
    fun `upsertAll batch inserts`() {
        runBlocking {
            dao.upsertAll(
                listOf(
                    VideoItemEntity(syncRelationId = 1L, relativePath = "a.mp4", fileName = "a.mp4", fileSize = 1L),
                    VideoItemEntity(syncRelationId = 1L, relativePath = "b.mp4", fileName = "b.mp4", fileSize = 2L),
                    VideoItemEntity(syncRelationId = 1L, relativePath = "c.mp4", fileName = "c.mp4", fileSize = 3L)
                )
            )
        }
        assertEquals(3, runBlocking { dao.getAll() }.size)
    }

    @Test
    fun `deleting video item cascades to playback record`() {
        val videoId = insert(
            syncRelationId = 1L,
            relativePath = "cascade.mp4",
            parentPath = "",
            fileSize = 10L
        )

        runBlocking {
            playbackDao.upsert(
                PlaybackRecordEntity(
                    videoItemId = videoId,
                    position = 123L,
                    duration = 456L
                )
            )

            assertNotNull(playbackDao.getByVideoId(videoId))

            dao.deleteById(videoId)

            assertNull(playbackDao.getByVideoId(videoId))
        }
    }
}
