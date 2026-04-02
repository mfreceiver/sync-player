package com.mfreceiver.syncplayer.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "video_item",
    indices = [Index(value = ["relativePath"], unique = true)]
)
data class VideoItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val relativePath: String,
    val fileName: String,
    val localAbsolutePath: String,
    val fileSize: Long,
    val duration: Long? = null,
    val dateSynced: Long = System.currentTimeMillis(),
    val remoteModifiedTime: Long? = null
)
