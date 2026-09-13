package cn.vectory.syncplayer.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.vectory.syncplayer.NoOpTestApp
import cn.vectory.syncplayer.data.local.dao.SyncRelationDao
import cn.vectory.syncplayer.data.local.dao.SyncSourceDao
import cn.vectory.syncplayer.data.local.entity.SyncRelationEntity
import cn.vectory.syncplayer.data.local.entity.SyncSourceEntity
import cn.vectory.syncplayer.data.model.SyncProtocol
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression tests for AND-041: [SyncRelationDao.findConflictingBasePaths]
 * must properly escape LIKE wildcards (`_`, `%`, `\`) in both localBasePath and checkPath.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = NoOpTestApp::class)
class SyncRelationDaoConflictEscapingTest {

    private lateinit var db: AppDatabase
    private lateinit var relationDao: SyncRelationDao
    private lateinit var sourceDao: SyncSourceDao
    private var sourceId: Long = 0L

    @Before
    fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        relationDao = db.syncRelationDao()
        sourceDao = db.syncSourceDao()

        sourceId = sourceDao.upsert(
            SyncSourceEntity(
                name = "test_src",
                protocol = SyncProtocol.SFTP.name,
                host = "127.0.0.1",
                port = 22,
                username = "u",
                encryptedPassword = "enc"
            )
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun insertRelation(name: String, localBasePath: String): Long {
        return relationDao.upsert(
            SyncRelationEntity(
                name = name,
                syncSourceId = sourceId,
                remotePath = "/remote",
                localBasePath = localBasePath
            )
        )
    }

    @Test
    fun `underscore in existing localBasePath does not match different character in checkPath`() = runBlocking {
        insertRelation("one_piece", "videos/one_piece")

        // checkPath has 'one-piece', should NOT conflict with 'one_piece'
        val conflicts1 = relationDao.findConflictingBasePaths("videos/one-piece")
        assertTrue("videos/one-piece should not conflict with videos/one_piece", conflicts1.isEmpty())

        // checkPath has subpath of 'one-piece', should NOT conflict
        val conflicts2 = relationDao.findConflictingBasePaths("videos/one-piece/ep1")
        assertTrue("videos/one-piece/ep1 should not conflict with videos/one_piece", conflicts2.isEmpty())

        // checkPath has subpath of 'one_piece', SHOULD conflict
        val conflicts3 = relationDao.findConflictingBasePaths("videos/one_piece/ep1")
        assertEquals(1, conflicts3.size)
        assertEquals("videos/one_piece", conflicts3[0].localBasePath)
    }

    @Test
    fun `underscore in checkPath does not match different character in existing localBasePath`() = runBlocking {
        insertRelation("one-piece", "videos/one-piece/season1")

        // checkPath is parent with underscore: 'videos/one_piece' should NOT conflict with 'videos/one-piece/season1'
        val conflicts = relationDao.findConflictingBasePaths("videos/one_piece")
        assertTrue("videos/one_piece should not conflict with videos/one-piece/season1", conflicts.isEmpty())

        // checkPath with hyphen SHOULD conflict
        val conflictsMatch = relationDao.findConflictingBasePaths("videos/one-piece")
        assertEquals(1, conflictsMatch.size)
        assertEquals("videos/one-piece/season1", conflictsMatch[0].localBasePath)
    }

    @Test
    fun `percent and backslash in paths are treated as literal characters`() = runBlocking {
        insertRelation("percent", "videos/100%_special")
        insertRelation("backslash", "videos/foo\\bar")

        // '100X_special' should not match '100%_special'
        val confPercentMismatch = relationDao.findConflictingBasePaths("videos/100X_special")
        assertTrue(confPercentMismatch.isEmpty())

        // Subpath under 100%_special should match
        val confPercentMatch = relationDao.findConflictingBasePaths("videos/100%_special/extra")
        assertEquals(1, confPercentMatch.size)
        assertEquals("videos/100%_special", confPercentMatch[0].localBasePath)

        // Backslash path exact and subpath
        val confBackslash = relationDao.findConflictingBasePaths("videos/foo\\bar/sub")
        assertEquals(1, confBackslash.size)
        assertEquals("videos/foo\\bar", confBackslash[0].localBasePath)
    }
}
