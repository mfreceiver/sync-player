package cn.vectory.syncplayer.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "video_metadata_cache",
    indices = [Index(value = ["localAbsolutePath", "fileName", "fileSize"], unique = true)]
)
data class VideoMetadataCacheEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val localAbsolutePath: String,
    val fileName: String,
    val fileSize: Long,
    val duration: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val frameRate: Float? = null,
    val bitrate: Long? = null,
    val thumbnailPath: String? = null,
    val lastUpdated: Long = System.currentTimeMillis()
)
