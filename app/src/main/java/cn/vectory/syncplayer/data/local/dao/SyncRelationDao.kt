package cn.vectory.syncplayer.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import cn.vectory.syncplayer.data.local.entity.SyncRelationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncRelationDao {
    @Query("SELECT * FROM sync_relation ORDER BY name ASC")
    fun getAllFlow(): Flow<List<SyncRelationEntity>>

    @Query("SELECT * FROM sync_relation ORDER BY name ASC")
    suspend fun getAll(): List<SyncRelationEntity>

    @Query("SELECT * FROM sync_relation WHERE id = :id")
    suspend fun getById(id: Long): SyncRelationEntity?

    @Query("SELECT * FROM sync_relation WHERE localBasePath = :path")
    suspend fun getByLocalBasePath(path: String): SyncRelationEntity?

    @Query("""
        SELECT * FROM sync_relation
        WHERE localBasePath != :excludePath
        AND (:checkPath LIKE localBasePath || '/%' OR localBasePath LIKE :checkPath || '/%')
    """)
    suspend fun findConflictingBasePaths(checkPath: String, excludePath: String = ""): List<SyncRelationEntity>

    @Query("SELECT localBasePath FROM sync_relation")
    suspend fun getAllBasePaths(): List<String>

    @Query("SELECT localBasePath FROM sync_relation")
    fun getAllBasePathsFlow(): Flow<List<String>>

    @Insert(entity = SyncRelationEntity): Long
    suspend fun upsert(relation: SyncRelationEntity): Long

    @Query("DELETE FROM sync_relation WHERE id = :id")
    suspend fun deleteById(id: Long)
}
