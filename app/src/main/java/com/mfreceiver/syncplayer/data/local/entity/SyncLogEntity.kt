package com.mfreceiver.syncplayer.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sync_log")
data class SyncLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val dryRun: Boolean,
    val action: String,             // "DOWNLOAD", "DELETE_LOCAL", "SKIP"
    val relativePath: String,
    val fileSize: Long? = null,
    val status: String,             // "SUCCESS", "FAILED", "SKIPPED"
    val errorMessage: String? = null
)
