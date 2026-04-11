package cn.vectory.syncplayer.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "pending_sync_action",
    indices = [Index(value = ["relationId"])]
)
data class PendingSyncActionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val relationId: Long,
    val relationName: String,
    val localBasePath: String,
    val actionGroup: Int,
    val actionType: String,
    val relativePath: String,
    val fileSize: Long = 0,
    val remoteModifiedTime: Long = 0
)
