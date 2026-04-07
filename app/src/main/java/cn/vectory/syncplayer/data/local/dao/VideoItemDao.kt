package cn.vectory.syncplayer.data.local.dao

import androidx.room.*
import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoItemDao {
    @Query("""
        SELECT * FROM video_item
        WHERE CASE
            WHEN :folderPath = '' THEN relativePath NOT LIKE '%/%'
            ELSE relativePath LIKE :folderPath || '/%'
                 AND SUBSTR(relativePath, LENGTH(:folderPath) + 2) NOT LIKE '%/%'
        END
    """)
    fun getItemsInFolder(folderPath: String): Flow<List<VideoItemEntity>>

    @Query("""
        SELECT DISTINCT SUBSTR(
            CASE WHEN :folderPath = '' THEN relativePath
            ELSE SUBSTR(relativePath, LENGTH(:folderPath) + 2)
            END,
            1,
            INSTR(
                CASE WHEN :folderPath = '' THEN relativePath
                ELSE SUBSTR(relativePath, LENGTH(:folderPath) + 2)
                END || '/',
                '/'
            ) - 1
        )
        FROM video_item
        WHERE CASE
            WHEN :folderPath = '' THEN relativePath LIKE '%/%'
            ELSE relativePath LIKE :folderPath || '/%'
                 AND SUBSTR(relativePath, LENGTH(:folderPath) + 2) LIKE '%/%'
        END
    """)
    fun getSubFolders(folderPath: String): Flow<List<String>>

    @Query("SELECT * FROM video_item WHERE id = :id")
    suspend fun getById(id: Long): VideoItemEntity?

    @Query("SELECT * FROM video_item WHERE relativePath = :relativePath")
    suspend fun getByRelativePath(relativePath: String): VideoItemEntity?

    @Query("SELECT * FROM video_item")
    suspend fun getAll(): List<VideoItemEntity>

    @Query("SELECT * FROM video_item WHERE syncRelationId = :relationId")
    suspend fun getBySyncRelationId(relationId: Long): List<VideoItemEntity>

    @Upsert
    suspend fun upsert(item: VideoItemEntity): Long

    @Upsert
    suspend fun upsertAll(items: List<VideoItemEntity>)

    @Query("DELETE FROM video_item WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM video_item WHERE relativePath = :relativePath")
    suspend fun deleteByRelativePath(relativePath: String)

    @Query("DELETE FROM video_item")
    suspend fun deleteAll()

    @Query("DELETE FROM video_item WHERE relativePath LIKE :folderPath || '/%'")
    suspend fun deleteByFolderPath(folderPath: String)

    @Query("SELECT id FROM video_item ORDER BY fileName ASC")
    suspend fun getAllIdsSorted(): List<Long>
}
