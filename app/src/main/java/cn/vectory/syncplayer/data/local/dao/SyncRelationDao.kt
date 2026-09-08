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

    /** Synchronous variant for DocumentsProvider binder-thread use (no coroutine overhead). */
    @Query("SELECT * FROM sync_relation ORDER BY name ASC")
    fun getAllSync(): List<SyncRelationEntity>

    @Query("SELECT * FROM sync_relation WHERE id = :id")
    suspend fun getById(id: Long): SyncRelationEntity?

    /** Synchronous variant for DocumentsProvider binder-thread use (no coroutine overhead). */
    @Query("SELECT * FROM sync_relation WHERE id = :id")
    fun getByIdSync(id: Long): SyncRelationEntity?

    @Query("SELECT * FROM sync_relation WHERE localBasePath = :path")
    suspend fun getByLocalBasePath(path: String): SyncRelationEntity?

    @Query("""
        SELECT * FROM sync_relation
        WHERE localBasePath != :excludePath
        AND (:checkPath = localBasePath OR :checkPath LIKE localBasePath || '/%' OR localBasePath LIKE :checkPath || '/%')
    """)
    suspend fun findConflictingBasePaths(checkPath: String, excludePath: String = ""): List<SyncRelationEntity>

    @Query("SELECT localBasePath FROM sync_relation")
    suspend fun getAllBasePaths(): List<String>

    @Query("SELECT localBasePath FROM sync_relation")
    fun getAllBasePathsFlow(): Flow<List<String>>

    /**
     * F02: 时间 + 终态 + 错误码 + 失败数作为一个快照原子写入（单条 UPDATE）。
     * status/errorCode 用稳定字符串标识（enum name），不存 ordinal、不存本地化文案。
     */
    @Query(
        "UPDATE sync_relation SET lastSyncTime = :lastSyncTime, lastSyncStatus = :lastSyncStatus, " +
            "lastSyncErrorCode = :lastSyncErrorCode, lastSyncFailedCount = :lastSyncFailedCount " +
            "WHERE id = :id"
    )
    suspend fun updateLastSyncSnapshot(
        id: Long,
        lastSyncTime: Long,
        lastSyncStatus: String,
        lastSyncErrorCode: String?,
        lastSyncFailedCount: Int?
    )

    @Upsert
    suspend fun upsert(relation: SyncRelationEntity): Long

    @Query("DELETE FROM sync_relation WHERE id = :id")
    suspend fun deleteById(id: Long)
}
