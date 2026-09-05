package cn.vectory.syncplayer.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.vectory.syncplayer.NoOpTestApp
import cn.vectory.syncplayer.data.local.dao.PlaybackRecordDao
import cn.vectory.syncplayer.data.local.dao.VideoItemDao
import cn.vectory.syncplayer.data.local.entity.PlaybackRecordEntity
import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Folder-scoped query semantics for [PlaybackRecordDao] (BUG-011 / BUG-012 / BUG-020).
 *
 * `LIKE`-driven folder predicates fold ASCII case and treat `_` as a wildcard, so
 * `one_piece` leaks into `one-piece` / `ONE_PIECE` siblings (mark-read corrupts
 * sibling watch state). Folder queries must use binary-exact prefix comparison and
 * must handle the root folder (`""`) explicitly instead of degrading to an
 * impossible `LIKE '/%'` pattern.
 *
 * Real in-memory Room DB via Robolectric; asserts ids/booleans/counts only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = NoOpTestApp::class)
class PlaybackRecordDaoFolderSemanticsTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: VideoItemDao
    private lateinit var playbackDao: PlaybackRecordDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.videoItemDao()
        playbackDao = db.playbackRecordDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun insertVideo(
        relativePath: String,
        parentPath: String
    ): Long = runBlocking {
        dao.upsert(
            VideoItemEntity(
                syncRelationId = 1L,
                relativePath = relativePath,
                parentPath = parentPath,
                fileName = relativePath.substringAfterLast('/'),
                fileSize = 1L,
                volumeId = "vol"
            )
        )
    }

    private fun insertRecord(videoItemId: Long, isCompleted: Boolean = false): Unit = runBlocking {
        playbackDao.upsert(PlaybackRecordEntity(videoItemId = videoItemId, isCompleted = isCompleted))
    }

    /** one_piece target + one-piece / ONE_PIECE twins (videos only, no records). */
    private fun insertTwinFixture(): Triple<Long, Long, Long> {
        val targetId = insertVideo("one_piece/a.mp4", parentPath = "one_piece")
        val dashTwinId = insertVideo("one-piece/x.mp4", parentPath = "one-piece")
        val caseTwinId = insertVideo("ONE_PIECE/x.mp4", parentPath = "ONE_PIECE")
        return Triple(targetId, dashTwinId, caseTwinId)
    }

    /** Twin fixture plus a completed=false record on every row. */
    private fun insertTwinFixtureWithRecords(): Triple<Long, Long, Long> {
        val (targetId, dashTwinId, caseTwinId) = insertTwinFixture()
        insertRecord(targetId)
        insertRecord(dashTwinId)
        insertRecord(caseTwinId)
        return Triple(targetId, dashTwinId, caseTwinId)
    }

    // ----------------------------------------------------------------- BUG-012

    @Test
    fun `updateCompletedByFolder marks only exact folder descendants`() = runBlocking {
        val (targetId, dashTwinId, caseTwinId) = insertTwinFixtureWithRecords()

        playbackDao.updateCompletedByFolder("one_piece", true)

        assertTrue(playbackDao.getByVideoId(targetId)!!.isCompleted)
        assertFalse(playbackDao.getByVideoId(dashTwinId)!!.isCompleted)
        assertFalse(playbackDao.getByVideoId(caseTwinId)!!.isCompleted)
    }

    @Test
    fun `getVideoIdsWithoutRecord returns only exact folder items`() = runBlocking {
        val (targetId, _, _) = insertTwinFixture()

        val ids = playbackDao.getVideoIdsWithoutRecord("one_piece")

        assertEquals(1, ids.size)
        assertEquals(targetId, ids[0])
    }

    @Test
    fun `getByFolderFlow excludes case twins`() = runBlocking {
        val vacationId = insertVideo("vacation/a.mp4", parentPath = "vacation")
        val caseTwinId = insertVideo("Vacation/a.mp4", parentPath = "Vacation")
        insertRecord(vacationId)
        insertRecord(caseTwinId)

        val records = playbackDao.getByFolderFlow("vacation").first()

        assertEquals(1, records.size)
        assertEquals(vacationId, records[0].videoItemId)
        assertTrue(records.none { it.videoItemId == caseTwinId })
    }

    // ----------------------------------------------------------------- BUG-020 root branch

    @Test
    fun `updateCompletedByFolder root marks all`() = runBlocking {
        val rootId = insertVideo("a.mp4", parentPath = "")
        val nestedId = insertVideo("sub/b.mp4", parentPath = "sub")
        insertRecord(rootId)
        insertRecord(nestedId)

        playbackDao.updateCompletedByFolder("", true)

        assertTrue(playbackDao.getByVideoId(rootId)!!.isCompleted)
        assertTrue(playbackDao.getByVideoId(nestedId)!!.isCompleted)
    }

    @Test
    fun `getVideoIdsWithoutRecord root returns all`() = runBlocking {
        val rootId = insertVideo("a.mp4", parentPath = "")
        val nestedId = insertVideo("sub/b.mp4", parentPath = "sub")

        val ids = playbackDao.getVideoIdsWithoutRecord("")

        assertEquals(2, ids.size)
        assertEquals(setOf(rootId, nestedId), ids.toSet())
    }

    // ----------------------------------------------------------------- sanity

    @Test
    fun `insertRecord roundtrip via getByVideoId`() = runBlocking {
        val id = insertVideo("solo.mp4", parentPath = "")
        insertRecord(id, isCompleted = true)

        val record = playbackDao.getByVideoId(id)
        assertNotNull(record)
        assertTrue(record!!.isCompleted)
        assertNull(playbackDao.getByVideoId(id + 100))
    }
}
