package cn.vectory.syncplayer.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import cn.vectory.syncplayer.data.local.entity.SyncSourceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncSourceDao {
    @Query("SELECT * FROM sync_source ORDER BY name ASC")
    fun getAllFlow(): Flow<List<SyncSourceEntity>>

    @Query("SELECT * FROM sync_source ORDER BY name ASC")
    suspend fun getAll(): List<SyncSourceEntity>

    @Query("SELECT * FROM sync_source WHERE id = :id")
    suspend fun getById(id: Long): SyncSourceEntity?

    @Query("SELECT COUNT(*) FROM sync_relation WHERE syncSourceId = :sourceId")
    suspend fun getRelationCount(sourceId: Long): Int

    @Upsert
    suspend fun upsert(source: SyncSourceEntity): Long

    @Query("DELETE FROM sync_source WHERE id = :id")
    suspend fun deleteById(id: Long)
}
