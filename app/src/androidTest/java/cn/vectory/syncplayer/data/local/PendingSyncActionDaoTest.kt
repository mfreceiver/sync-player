package cn.vectory.syncplayer.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.vectory.syncplayer.data.local.dao.PendingSyncActionDao
import cn.vectory.syncplayer.data.local.entity.PendingSyncActionEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented DAO tests for [PendingSyncActionDao] against a real in-memory Room DB.
 *
 * Covers: replace (transactional delete+insert), getPendingByRelationId, all status-mark
 * transitions (markRunning / resetRunningToPending / markCompleted / markFailed /
 * markCancelled), deleteByRelationId isolation, and getAndDeleteByRelationId atomic handoff.
 */
@RunWith(AndroidJUnit4::class)
class PendingSyncActionDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: PendingSyncActionDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .build()
        dao = db.pendingSyncActionDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun action(
        id: Long = 0,
        relationId: Long,
        relativePath: String,
        actionType: String = "DOWNLOAD",
        actionGroup: Int = 0,
        status: String = "PENDING"
    ) = PendingSyncActionEntity(
        id = id,
        relationId = relationId,
        relationName = "rel-$relationId",
        localBasePath = "/local",
        actionGroup = actionGroup,
        actionType = actionType,
        relativePath = relativePath,
        fileSize = 100L,
        remoteModifiedTime = 0L,
        status = status
    )

    private fun seed(relationId: Long, count: Int, status: String = "PENDING") = runBlocking {
        dao.insertAll((1..count).map { i ->
            action(relationId = relationId, relativePath = "f$i.mp4", status = status)
        })
    }

    private fun statusesOf(relationId: Long): Map<String, String> = runBlocking {
        dao.getByRelationId(relationId).associate { it.relativePath to it.status }
    }

    // ===========================================================================
    // INSERT + GET
    // ===========================================================================

    @Test
    fun `insertAll then getByRelationId returns all rows ordered by id`() {
        seed(relationId = 5L, count = 3)
        val rows = runBlocking { dao.getByRelationId(5L) }
        assertEquals(3, rows.size)
        assertEquals(listOf("f1.mp4", "f2.mp4", "f3.mp4"), rows.map { it.relativePath })
    }

    // ===========================================================================
    // REPLACE (transactional)
    // ===========================================================================

    @Test
    fun `replaceForRelation deletes old rows and inserts new ones atomically`() {
        seed(relationId = 1L, count = 2)
        runBlocking {
            dao.replaceForRelation(
                1L,
                listOf(action(relationId = 1L, relativePath = "new1.mp4"))
            )
        }
        val rows = runBlocking { dao.getByRelationId(1L) }
        assertEquals(1, rows.size)
        assertEquals("new1.mp4", rows[0].relativePath)
    }

    @Test
    fun `replaceForRelation for one relation does not affect another relation`() {
        seed(relationId = 1L, count = 2)
        seed(relationId = 2L, count = 3)
        runBlocking { dao.replaceForRelation(1L, listOf(action(relationId = 1L, relativePath = "x.mp4"))) }
        assertEquals(1, runBlocking { dao.getByRelationId(1L) }.size)
        assertEquals(3, runBlocking { dao.getByRelationId(2L) }.size)
    }

    @Test
    fun `replaceForRelation with empty list removes all rows for relation`() {
        seed(relationId = 1L, count = 2)
        runBlocking { dao.replaceForRelation(1L, emptyList()) }
        assertEquals(0, runBlocking { dao.getByRelationId(1L) }.size)
    }

    // ===========================================================================
    // getPendingByRelationId
    // ===========================================================================

    @Test
    fun `getPendingByRelationId returns only PENDING rows`() {
        runBlocking {
            dao.insertAll(
                listOf(
                    action(relationId = 1L, relativePath = "p1.mp4", status = "PENDING"),
                    action(relationId = 1L, relativePath = "c1.mp4", status = "COMPLETED"),
                    action(relationId = 1L, relativePath = "r1.mp4", status = "RUNNING"),
                    action(relationId = 1L, relativePath = "p2.mp4", status = "PENDING")
                )
            )
        }
        val pending = runBlocking { dao.getPendingByRelationId(1L) }
        assertEquals(2, pending.size)
        assertTrue(pending.all { it.status == "PENDING" })
    }

    // ===========================================================================
    // STATUS TRANSITIONS
    // ===========================================================================

    @Test
    fun `markRunning sets all PENDING to RUNNING for relation`() {
        runBlocking {
            dao.insertAll(
                listOf(
                    action(relationId = 1L, relativePath = "a.mp4", status = "PENDING"),
                    action(relationId = 1L, relativePath = "b.mp4", status = "PENDING")
                )
            )
        }
        runBlocking { dao.markRunning(1L) }
        val st = statusesOf(1L)
        assertEquals("RUNNING", st["a.mp4"])
        assertEquals("RUNNING", st["b.mp4"])
    }

    @Test
    fun `resetRunningToPending reverts only RUNNING rows`() {
        runBlocking {
            dao.insertAll(
                listOf(
                    action(relationId = 1L, relativePath = "a.mp4", status = "RUNNING"),
                    action(relationId = 1L, relativePath = "b.mp4", status = "COMPLETED"),
                    action(relationId = 1L, relativePath = "c.mp4", status = "RUNNING")
                )
            )
        }
        runBlocking { dao.resetRunningToPending(1L) }
        val st = statusesOf(1L)
        assertEquals("PENDING", st["a.mp4"])
        assertEquals("COMPLETED", st["b.mp4"])
        assertEquals("PENDING", st["c.mp4"])
    }

    @Test
    fun `markCompleted sets all rows for relation to COMPLETED`() {
        seed(relationId = 1L, count = 2) // PENDING
        runBlocking { dao.insertAll(listOf(action(relationId = 1L, relativePath = "x.mp4", status = "FAILED"))) }
        runBlocking { dao.markCompleted(1L) }
        val rows = runBlocking { dao.getByRelationId(1L) }
        assertEquals(3, rows.size)
        assertTrue(rows.all { it.status == "COMPLETED" })
    }

    @Test
    fun `markFailed sets all rows for relation to FAILED`() {
        seed(relationId = 1L, count = 2)
        runBlocking { dao.markFailed(1L) }
        assertTrue(runBlocking { dao.getByRelationId(1L) }.all { it.status == "FAILED" })
    }

    @Test
    fun `markCancelled sets all rows for relation to CANCELLED`() {
        seed(relationId = 1L, count = 2)
        runBlocking { dao.markCancelled(1L) }
        assertTrue(runBlocking { dao.getByRelationId(1L) }.all { it.status == "CANCELLED" })
    }

    // ===========================================================================
    // STATUS FLOW — full lifecycle
    // ===========================================================================

    @Test
    fun `status flow PENDING to RUNNING to COMPLETED`() {
        seed(relationId = 1L, count = 1)
        assertEquals("PENDING", statusesOf(1L)["f1.mp4"])
        runBlocking { dao.markRunning(1L) }
        assertEquals("RUNNING", statusesOf(1L)["f1.mp4"])
        runBlocking { dao.markCompleted(1L) }
        assertEquals("COMPLETED", statusesOf(1L)["f1.mp4"])
    }

    @Test
    fun `status flow PENDING to RUNNING reset back to PENDING`() {
        seed(relationId = 1L, count = 1)
        runBlocking { dao.markRunning(1L) }
        assertEquals("RUNNING", statusesOf(1L)["f1.mp4"])
        runBlocking { dao.resetRunningToPending(1L) }
        assertEquals("PENDING", statusesOf(1L)["f1.mp4"])
    }

    @Test
    fun `markRunning only affects target relation`() {
        seed(relationId = 1L, count = 1)
        seed(relationId = 2L, count = 1)
        runBlocking { dao.markRunning(1L) }
        assertEquals("RUNNING", statusesOf(1L)["f1.mp4"])
        assertEquals("PENDING", statusesOf(2L)["f1.mp4"])
    }

    // ===========================================================================
    // DELETE
    // ===========================================================================

    @Test
    fun `deleteByRelationId removes only that relation rows`() {
        seed(relationId = 1L, count = 2)
        seed(relationId = 2L, count = 3)
        runBlocking { dao.deleteByRelationId(1L) }
        assertEquals(0, runBlocking { dao.getByRelationId(1L) }.size)
        assertEquals(3, runBlocking { dao.getByRelationId(2L) }.size)
    }

    @Test
    fun `getAndDeleteByRelationId returns rows then deletes them`() {
        seed(relationId = 1L, count = 2)
        val handed = runBlocking { dao.getAndDeleteByRelationId(1L) }
        assertEquals(2, handed.size)
        assertEquals(0, runBlocking { dao.getByRelationId(1L) }.size)
    }

    @Test
    fun `getAndDeleteByRelationId on empty relation returns empty and deletes nothing`() {
        val handed = runBlocking { dao.getAndDeleteByRelationId(99L) }
        assertEquals(0, handed.size)
    }

    @Test
    fun `deleteAll clears every relation`() {
        seed(relationId = 1L, count = 1)
        seed(relationId = 2L, count = 1)
        runBlocking { dao.deleteAll() }
        assertEquals(0, runBlocking { dao.getByRelationId(1L) }.size)
        assertEquals(0, runBlocking { dao.getByRelationId(2L) }.size)
    }
}
