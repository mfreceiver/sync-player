package cn.vectory.syncplayer.data.repository

import cn.vectory.syncplayer.data.local.dao.SyncRelationDao
import cn.vectory.syncplayer.data.local.dao.SyncSourceDao
import cn.vectory.syncplayer.data.local.entity.SyncRelationEntity
import cn.vectory.syncplayer.data.model.SyncOutcome
import cn.vectory.syncplayer.data.model.SyncRelation
import io.mockk.CapturingSlot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * F02/T6: [SyncRelationRepository] 的结构化终态快照协议。
 *
 * - updateLastSync 接收类型化 [SyncOutcome]（不接收字符串），DAO 单条 UPDATE 原子写入；
 * - 列 → domain outcome 的映射（含旧文案/外来值 → UNKNOWN、全 NULL → 无记录）；
 * - save() 往返 outcome，且不再写 deprecated 的 lastSyncResult 文案列。
 *
 * 断言枚举/调用次数，绝不断言用户文案。
 */
class SyncRelationRepositoryTest {

    private val syncRelationDao = mockk<SyncRelationDao>(relaxed = true)
    private val syncSourceDao = mockk<SyncSourceDao>(relaxed = true)
    private val repository = SyncRelationRepository(syncRelationDao, syncSourceDao)

    // =========================================================================
    // 原子快照写入（时间 + 终态 + 错误码 + 计数 = 单条 UPDATE）
    // =========================================================================

    @Test
    fun `updateLastSync writes one atomic snapshot per typed outcome`() = runTest {
        repository.updateLastSync(7L, SyncOutcome(SyncOutcome.Status.PARTIAL_FAILED, "TIMEOUT", 3))

        // 恰好一次 DAO 调用 = 单条 UPDATE 原子快照；字符串标识为 enum name。
        coVerify(exactly = 1) {
            syncRelationDao.updateLastSyncSnapshot(
                id = 7L,
                lastSyncTime = any(),
                lastSyncStatus = "PARTIAL_FAILED",
                lastSyncErrorCode = "TIMEOUT",
                lastSyncFailedCount = 3
            )
        }
    }

    @Test
    fun `updateLastSync success snapshot carries null code and null count`() = runTest {
        repository.updateLastSync(1L, SyncOutcome(SyncOutcome.Status.SUCCESS))

        coVerify(exactly = 1) {
            syncRelationDao.updateLastSyncSnapshot(1L, any(), "SUCCESS", null, null)
        }
    }

    @Test
    fun `updateLastSync failed outcome with unknown count keeps null not zero`() = runTest {
        repository.updateLastSync(2L, SyncOutcome(SyncOutcome.Status.FAILED, "INSUFFICIENT_SPACE"))

        coVerify(exactly = 1) {
            syncRelationDao.updateLastSyncSnapshot(2L, any(), "FAILED", "INSUFFICIENT_SPACE", null)
        }
    }

    // =========================================================================
    // 列 → domain 映射
    // =========================================================================

    private fun entity(
        status: String? = null,
        errorCode: String? = null,
        failedCount: Int? = null
    ) = SyncRelationEntity(
        id = 5L,
        name = "rel",
        syncSourceId = 1L,
        remotePath = "/remote",
        localBasePath = "/local",
        lastSyncStatus = status,
        lastSyncErrorCode = errorCode,
        lastSyncFailedCount = failedCount
    )

    @Test
    fun `getById maps stored columns to typed outcome`() = runTest {
        coEvery { syncRelationDao.getById(5L) } returns entity("FAILED", "REMOTE_DELETE_FAILED", null)

        val outcome = repository.getById(5L)?.lastSyncOutcome
        assertEquals(SyncOutcome.Status.FAILED, outcome?.status)
        assertEquals("REMOTE_DELETE_FAILED", outcome?.errorCode)
        assertNull(outcome?.failedCount)
    }

    @Test
    fun `getById with all null outcome columns means no record`() = runTest {
        coEvery { syncRelationDao.getById(5L) } returns entity()

        assertNull(repository.getById(5L)?.lastSyncOutcome)
    }

    @Test
    fun `getById with unparsable legacy status string maps to UNKNOWN`() = runTest {
        coEvery { syncRelationDao.getById(5L) } returns entity(status = "成功：下载 2 个")

        assertEquals(SyncOutcome.Status.UNKNOWN, repository.getById(5L)?.lastSyncOutcome?.status)
    }

    // =========================================================================
    // save() 往返
    // =========================================================================

    @Test
    fun `save round-trips outcome and never writes legacy text column`() = runTest {
        val captured: CapturingSlot<SyncRelationEntity> = slot()
        coEvery { syncRelationDao.upsert(capture(captured)) } returns 5L

        val relation = SyncRelation(
            id = 5L,
            name = "rel",
            syncSourceId = 1L,
            remotePath = "/remote",
            localBasePath = "/local",
            lastSyncOutcome = SyncOutcome(SyncOutcome.Status.PARTIAL_FAILED, null, 2)
        )
        repository.save(relation)

        assertEquals("PARTIAL_FAILED", captured.captured.lastSyncStatus)
        assertNull(captured.captured.lastSyncErrorCode)
        assertEquals(2, captured.captured.lastSyncFailedCount)
        // deprecated 文案列不再由代码写入
        assertNull(captured.captured.lastSyncResult)
    }
}
