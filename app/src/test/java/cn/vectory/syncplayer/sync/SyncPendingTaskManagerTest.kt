package cn.vectory.syncplayer.sync

import cn.vectory.syncplayer.data.local.dao.PendingSyncActionDao
import cn.vectory.syncplayer.data.local.entity.PendingSyncActionEntity
import cn.vectory.syncplayer.data.model.SyncAction
import cn.vectory.syncplayer.data.model.SyncActionType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Task 4 迁移测试：[SyncPendingTaskManager] 从 [SyncEngine] 迁出后的行为等价性。
 *
 * 覆盖 plan 12.2.0 remediation T4-C1：
 *  - actionGroup 0=download / 1=delete 实体映射；
 *  - 坏 actionType 回退 DOWNLOAD / DELETE_LOCAL；
 *  - `consumePendingTaskLocked` 空表 → null 且不 markRunning；非空 → markRunning；
 *  - `cancelCleanup` 吞 DAO 异常（NonCancellable 清理不得把 DB 故障升级为崩溃）；
 *  - `finalize` 成功 → markCompleted + deleteByRelationId；失败 → markFailed + deleteByRelationId。
 */
class SyncPendingTaskManagerTest {

    private val dao = mockk<PendingSyncActionDao>(relaxed = true)
    private val sut = SyncPendingTaskManager(dao)

    private val task = PendingSyncTask(
        relationId = 1L,
        relationName = "r",
        localBasePath = "videos",
        toDownload = listOf(SyncAction(SyncActionType.DOWNLOAD, "a.mp4", 10L, 5L)),
        toDelete = listOf(SyncAction(SyncActionType.DELETE_LOCAL, "b.mp4", 20L, 0L))
    )

    private fun pendingEntity(group: Int, actionType: String, path: String) = PendingSyncActionEntity(
        relationId = 1L, relationName = "r", localBasePath = "videos", actionGroup = group,
        actionType = actionType, relativePath = path, fileSize = 1L, remoteModifiedTime = 0L
    )

    @Test
    fun `setPendingTaskLocked maps download to group 0 and delete to group 1`() = runTest {
        sut.setPendingTaskLocked(task)

        coVerify {
            dao.replaceForRelation(1L, match { e ->
                e.size == 2 &&
                    e[0].actionGroup == 0 && e[0].actionType == "DOWNLOAD" &&
                    e[1].actionGroup == 1 && e[1].actionType == "DELETE_LOCAL"
            })
        }
    }

    @Test
    fun `consumePendingTaskLocked returns null when no pending rows`() = runTest {
        coEvery { dao.getPendingByRelationId(1L) } returns emptyList()

        assertNull(sut.consumePendingTaskLocked(1L))
        coVerify(exactly = 0) { dao.markRunning(any()) }
    }

    @Test
    fun `consumePendingTaskLocked marks running and falls back on bad actionType`() = runTest {
        coEvery { dao.getPendingByRelationId(1L) } returns listOf(
            pendingEntity(0, "GARBAGE", "a.mp4"),
            pendingEntity(1, "ALSO_BAD", "b.mp4")
        )

        val consumed = sut.consumePendingTaskLocked(1L)

        assertNotNull(consumed)
        assertEquals(SyncActionType.DOWNLOAD, consumed!!.toDownload[0].type)
        assertEquals(SyncActionType.DELETE_LOCAL, consumed.toDelete[0].type)
        coVerify(exactly = 1) { dao.markRunning(1L) }
    }

    @Test
    fun `cancelCleanup swallows DAO failures`() = runTest {
        coEvery { dao.markCancelled(1L) } throws RuntimeException("db down")
        coEvery { dao.deleteByRelationId(1L) } throws RuntimeException("db down")

        sut.cancelCleanup(1L) // 不得抛出

        coVerify(exactly = 1) { dao.markCancelled(1L) }
        coVerify(exactly = 1) { dao.deleteByRelationId(1L) }
    }

    @Test
    fun `finalize marks completed on success and failed otherwise`() = runTest {
        sut.finalize(1L, true)
        coVerify(exactly = 1) { dao.markCompleted(1L) }
        coVerify(exactly = 1) { dao.deleteByRelationId(1L) }

        sut.finalize(1L, false)
        coVerify(exactly = 1) { dao.markFailed(1L) }
        coVerify(exactly = 2) { dao.deleteByRelationId(1L) }
    }
}
