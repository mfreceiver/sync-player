package cn.vectory.syncplayer.sync

import cn.vectory.syncplayer.data.local.dao.PendingSyncActionDao
import cn.vectory.syncplayer.data.local.entity.PendingSyncActionEntity
import cn.vectory.syncplayer.data.model.SyncAction
import cn.vectory.syncplayer.data.model.SyncActionType
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Pending task 持久化的簿记封装（从 [SyncEngine] 上帝类渐进拆分，plan 12.2.0 Task 4）。
 *
 * 职责边界（刻意保持窄）：
 *  - 只封装 pending_sync_action 表的读写与终态标记；
 *  - **不持有任何锁**——所有 `*Locked` 方法的互斥由 [SyncEngine] 的
 *    `storageOperationMutex` 持有后调用（等价迁移：锁编排留在 Engine，
 *    一次同步的生命周期不允许被隐藏到私有协程里）；
 *  - 不编排同步执行本身（`consumeAndExecute` 的 锁 + 执行 + CE 清理 + 终态
 *    顺序仍由 Engine 负责，本类只提供其中 DAO 段的等价实现）。
 *
 * 语义与原 [SyncEngine] 内联实现**完全一致**：
 *  - entity 字段映射：actionGroup 0 = toDownload，1 = toDelete；
 *  - `SyncActionType.valueOf` 失败回退：group 0 → DOWNLOAD，group 1 → DELETE_LOCAL；
 *  - [cancelCleanup] 在 [NonCancellable] 中执行 markCancelled + deleteByRelationId，
 *    且逐项吞 DAO 异常（取消清理不得因 DB 故障升级为崩溃，也不得吞掉原 CancellationException）；
 *  - [finalize] 不在 NonCancellable 中执行（与原 `consumeAndExecute` 尾段一致），
 *    DAO 异常照常上抛。
 */
internal class SyncPendingTaskManager(
    private val pendingSyncActionDao: PendingSyncActionDao
) {

    /** 迁自原 `SyncEngine.setPendingTask` 锁内体（互斥由 Engine 的 storageOperationMutex 持有）。 */
    suspend fun setPendingTaskLocked(task: PendingSyncTask) {
        val entities = mutableListOf<PendingSyncActionEntity>()
        for (action in task.toDownload) {
            entities.add(PendingSyncActionEntity(
                relationId = task.relationId,
                relationName = task.relationName,
                localBasePath = task.localBasePath,
                actionGroup = 0,
                actionType = action.type.name,
                relativePath = action.relativePath,
                fileSize = action.fileSize,
                remoteModifiedTime = action.remoteModifiedTime
            ))
        }
        for (action in task.toDelete) {
            entities.add(PendingSyncActionEntity(
                relationId = task.relationId,
                relationName = task.relationName,
                localBasePath = task.localBasePath,
                actionGroup = 1,
                actionType = action.type.name,
                relativePath = action.relativePath,
                fileSize = action.fileSize,
                remoteModifiedTime = action.remoteModifiedTime
            ))
        }
        pendingSyncActionDao.replaceForRelation(task.relationId, entities)
    }

    /** 迁自原 `SyncEngine.consumePendingTaskLocked`（无锁版本，互斥由调用方持有）。 */
    suspend fun consumePendingTaskLocked(relationId: Long): PendingSyncTask? {
        pendingSyncActionDao.resetRunningToPending(relationId)
        val entities = pendingSyncActionDao.getPendingByRelationId(relationId)
        if (entities.isEmpty()) return null
        pendingSyncActionDao.markRunning(relationId)
        val first = entities.first()
        val toDownload = entities.filter { it.actionGroup == 0 }.map {
            SyncAction(
                type = try { SyncActionType.valueOf(it.actionType) } catch (_: Exception) { SyncActionType.DOWNLOAD },
                relativePath = it.relativePath,
                fileSize = it.fileSize,
                remoteModifiedTime = it.remoteModifiedTime
            )
        }
        val toDelete = entities.filter { it.actionGroup == 1 }.map {
            SyncAction(
                type = try { SyncActionType.valueOf(it.actionType) } catch (_: Exception) { SyncActionType.DELETE_LOCAL },
                relativePath = it.relativePath,
                fileSize = it.fileSize,
                remoteModifiedTime = it.remoteModifiedTime
            )
        }
        return PendingSyncTask(
            relationId = relationId,
            relationName = first.relationName,
            localBasePath = first.localBasePath,
            toDownload = toDownload,
            toDelete = toDelete
        )
    }

    /**
     * 迁自原 `SyncEngine.consumeAndExecute` 的 CancellationException 清理块：
     * NonCancellable 窄域内 markCancelled + deleteByRelationId，逐项吞 DAO 异常，
     * 随后由 Engine rethrow 原 CancellationException。
     */
    suspend fun cancelCleanup(relationId: Long) {
        withContext(NonCancellable) {
            try {
                pendingSyncActionDao.markCancelled(relationId)
            } catch (_: Exception) {
            }
            try {
                pendingSyncActionDao.deleteByRelationId(relationId)
            } catch (_: Exception) {
            }
        }
    }

    /** 迁自原 `consumeAndExecute` 尾段：成功 → markCompleted；失败 → markFailed；随后清行。 */
    suspend fun finalize(relationId: Long, success: Boolean) {
        if (success) pendingSyncActionDao.markCompleted(relationId)
        else pendingSyncActionDao.markFailed(relationId)
        pendingSyncActionDao.deleteByRelationId(relationId)
    }
}
