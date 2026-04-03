package cn.vectory.syncplayer.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import cn.vectory.syncplayer.data.local.entity.VideoMetadataCacheEntity

@Dao
interface VideoMetadataCacheDao {
    @Query("SELECT * FROM video_metadata_cache WHERE localAbsolutePath = :path AND fileName = :name AND fileSize = :size LIMIT 1")
    suspend fun getByKey(path: String, name: String, size: Long): VideoMetadataCacheEntity?

    @Upsert
    suspend fun upsert(entity: VideoMetadataCacheEntity)

    @Query("DELETE FROM video_metadata_cache WHERE localAbsolutePath = :path AND fileName = :name AND fileSize = :size")
    suspend fun deleteByKey(path: String, name: String, size: Long)

    @Query("DELETE FROM video_metadata_cache")
    suspend fun deleteAll()

    @Query("SELECT * FROM video_metadata_cache")
    suspend fun getAll(): List<VideoMetadataCacheEntity>
}
