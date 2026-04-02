package com.mfreceiver.syncplayer.data.local.dao

import androidx.room.*
import com.mfreceiver.syncplayer.data.local.entity.VideoItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoItemDao {
    @Query("SELECT * FROM video_item WHERE relativePath LIKE :folderPath || '%' AND relativePath NOT LIKE :folderPath || '%/%'")
    fun getItemsInFolder(folderPath: String): Flow<List<VideoItemEntity>>

    @Query("SELECT DISTINCT SUBSTR(relativePath, LENGTH(:folderPath) + 1, INSTR(SUBSTR(relativePath, LENGTH(:folderPath) + 1) || '/', '/') - 1) FROM video_item WHERE relativePath LIKE :folderPath || '%/_%'")
    fun getSubFolders(folderPath: String): Flow<List<String>>

    @Query("SELECT * FROM video_item WHERE id = :id")
    suspend fun getById(id: Long): VideoItemEntity?

    @Query("SELECT * FROM video_item WHERE relativePath = :relativePath")
    suspend fun getByRelativePath(relativePath: String): VideoItemEntity?

    @Query("SELECT * FROM video_item")
    suspend fun getAll(): List<VideoItemEntity>

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
}
