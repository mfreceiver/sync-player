package cn.vectory.syncplayer.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import cn.vectory.syncplayer.data.local.entity.PendingSyncActionEntity

@Dao
interface PendingSyncActionDao {
    @Insert
    suspend fun insertAll(actions: List<PendingSyncActionEntity>)

    @Query("SELECT * FROM pending_sync_action WHERE relationId = :relationId ORDER BY id ASC")
    suspend fun getByRelationId(relationId: Long): List<PendingSyncActionEntity>

    @Query("DELETE FROM pending_sync_action WHERE relationId = :relationId")
    suspend fun deleteByRelationId(relationId: Long)

    @Transaction
    suspend fun replaceForRelation(relationId: Long, actions: List<PendingSyncActionEntity>) {
        deleteByRelationId(relationId)
        insertAll(actions)
    }

    @Transaction
    suspend fun getAndDeleteByRelationId(relationId: Long): List<PendingSyncActionEntity> {
        val entities = getByRelationId(relationId)
        if (entities.isNotEmpty()) {
            deleteByRelationId(relationId)
        }
        return entities
    }

    @Query("SELECT * FROM pending_sync_action WHERE relationId = :relationId AND status = 'PENDING' ORDER BY id ASC")
    suspend fun getPendingByRelationId(relationId: Long): List<PendingSyncActionEntity>

    @Query("UPDATE pending_sync_action SET status = 'PENDING' WHERE relationId = :relationId AND status = 'RUNNING'")
    suspend fun resetRunningToPending(relationId: Long)

    @Query("UPDATE pending_sync_action SET status = 'RUNNING' WHERE relationId = :relationId AND status = 'PENDING'")
    suspend fun markRunning(relationId: Long)

    @Query("UPDATE pending_sync_action SET status = 'COMPLETED' WHERE relationId = :relationId")
    suspend fun markCompleted(relationId: Long)

    @Query("UPDATE pending_sync_action SET status = 'FAILED' WHERE relationId = :relationId")
    suspend fun markFailed(relationId: Long)

    @Query("UPDATE pending_sync_action SET status = 'CANCELLED' WHERE relationId = :relationId")
    suspend fun markCancelled(relationId: Long)
}
