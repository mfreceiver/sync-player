package cn.vectory.syncplayer.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "video_item",
    indices = [Index(value = ["relativePath", "syncRelationId"], unique = true)]
)
data class VideoItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val syncRelationId: Long = 0,
    val relativePath: String,
    val fileName: String,
    val localAbsolutePath: String,
    val fileSize: Long,
    val duration: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val bitrate: Long? = null,
    val frameRate: Float? = null,
    val thumbnailPath: String? = null,
    val audioGainDb: Float? = null,
    val dateSynced: Long = System.currentTimeMillis(),
    val remoteModifiedTime: Long? = null
)
