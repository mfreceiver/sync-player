package cn.vectory.syncplayer.data.local.dao

import androidx.room.*
import cn.vectory.syncplayer.data.local.entity.SyncLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncLogDao {
    @Insert
    suspend fun insert(log: SyncLogEntity): Long

    @Insert
    suspend fun insertAll(logs: List<SyncLogEntity>)

    @Query("SELECT * FROM sync_log ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 100): List<SyncLogEntity>

    @Query("SELECT * FROM sync_log WHERE timestamp > :sinceTimestamp ORDER BY timestamp DESC")
    fun getSince(sinceTimestamp: Long): Flow<List<SyncLogEntity>>

    @Query("DELETE FROM sync_log WHERE timestamp < :beforeTimestamp")
    suspend fun deleteOlderThan(beforeTimestamp: Long)
}
