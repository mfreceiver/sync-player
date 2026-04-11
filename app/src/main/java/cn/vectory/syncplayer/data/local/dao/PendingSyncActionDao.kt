package cn.vectory.syncplayer.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import cn.vectory.syncplayer.data.local.entity.PendingSyncActionEntity

@Dao
interface PendingSyncActionDao {
    @Insert
    suspend fun insertAll(actions: List<PendingSyncActionEntity>)

    @Query("SELECT * FROM pending_sync_action WHERE relationId = :relationId ORDER BY id ASC")
    suspend fun getByRelationId(relationId: Long): List<PendingSyncActionEntity>

    @Query("DELETE FROM pending_sync_action WHERE relationId = :relationId")
    suspend fun deleteByRelationId(relationId: Long)
}
