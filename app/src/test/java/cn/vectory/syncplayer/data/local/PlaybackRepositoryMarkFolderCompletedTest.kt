package cn.vectory.syncplayer.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.vectory.syncplayer.NoOpTestApp
import cn.vectory.syncplayer.data.local.dao.PlaybackRecordDao
import cn.vectory.syncplayer.data.local.dao.VideoItemDao
import cn.vectory.syncplayer.data.local.entity.PlaybackRecordEntity
import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import cn.vectory.syncplayer.data.repository.PlaybackRepository
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
 * Composite markFolderCompleted semantics over [PlaybackRepository] backed by a real
 * in-memory Room DB (BUG-012 / BUG-020).
 *
 * markFolderCompleted = (create records for videos without one) + (batch update by
 * folder). Its correctness depends entirely on the DAO folder predicate: a too-wide
 * predicate fabricates watch state in sibling directories; a missing root branch
 * makes root-level mark-read a silent no-op. Real Repository + real DAO exercise the
 * transactional composition end to end.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = NoOpTestApp::class)
class PlaybackRepositoryMarkFolderCompletedTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: VideoItemDao
    private lateinit var playbackDao: PlaybackRecordDao
    private lateinit var repository: PlaybackRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.videoItemDao()
        playbackDao = db.playbackRecordDao()
        repository = PlaybackRepository(playbackDao, db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun insertVideo(relativePath: String, parentPath: String): Long = runBlocking {
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

    /** one_piece target + one-piece / ONE_PIECE twins. Returns ids (target, dashTwin, caseTwin). */
    private fun insertTwinFixture(): Triple<Long, Long, Long> {
        val targetId = insertVideo("one_piece/a.mp4", parentPath = "one_piece")
        val dashTwinId = insertVideo("one-piece/x.mp4", parentPath = "one-piece")
        val caseTwinId = insertVideo("ONE_PIECE/x.mp4", parentPath = "ONE_PIECE")
        return Triple(targetId, dashTwinId, caseTwinId)
    }

    // ----------------------------------------------------------------- BUG-012

    @Test
    fun `markFolderCompleted creates records only for exact folder`() = runBlocking {
        val (targetId, dashTwinId, caseTwinId) = insertTwinFixture()

        repository.markFolderCompleted("one_piece", true)

        val targetRecord = playbackDao.getByVideoId(targetId)
        assertNotNull(targetRecord)
        assertTrue(targetRecord!!.isCompleted)
        assertNull("sibling must not gain a fabricated record", playbackDao.getByVideoId(dashTwinId))
        assertNull("sibling must not gain a fabricated record", playbackDao.getByVideoId(caseTwinId))
    }

    @Test
    fun `markFolderCompleted unsets only exact folder`() = runBlocking {
        val (targetId, dashTwinId, caseTwinId) = insertTwinFixture()
        insertRecord(targetId, isCompleted = true)
        insertRecord(dashTwinId, isCompleted = true)
        insertRecord(caseTwinId, isCompleted = true)

        repository.markFolderCompleted("one_piece", false)

        assertFalse(playbackDao.getByVideoId(targetId)!!.isCompleted)
        assertTrue(playbackDao.getByVideoId(dashTwinId)!!.isCompleted)
        assertTrue(playbackDao.getByVideoId(caseTwinId)!!.isCompleted)
    }

    // ----------------------------------------------------------------- BUG-020 root branch

    @Test
    fun `markFolderCompleted root covers all items`() = runBlocking {
        val rootId = insertVideo("a.mp4", parentPath = "")
        val nestedId = insertVideo("sub/b.mp4", parentPath = "sub")

        repository.markFolderCompleted("", true)

        val rootRecord = playbackDao.getByVideoId(rootId)
        val nestedRecord = playbackDao.getByVideoId(nestedId)
        assertNotNull(rootRecord)
        assertNotNull(nestedRecord)
        assertTrue(rootRecord!!.isCompleted)
        assertTrue(nestedRecord!!.isCompleted)
        assertEquals(2, playbackDao.getAllFlow().first().size)
    }
}
