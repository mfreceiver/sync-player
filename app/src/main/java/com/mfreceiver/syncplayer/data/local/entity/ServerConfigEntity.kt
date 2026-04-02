package com.mfreceiver.syncplayer.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "server_config")
data class ServerConfigEntity(
    @PrimaryKey val id: Long = 1,
    val protocol: String,       // "SMB" or "SFTP"
    val host: String,
    val port: Int,
    val username: String,
    val encryptedPassword: String,
    val remotePath: String,
    val lastSyncTime: Long? = null,
    val lastSyncResult: String? = null
)
