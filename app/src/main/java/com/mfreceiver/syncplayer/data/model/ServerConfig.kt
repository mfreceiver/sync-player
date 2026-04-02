package com.mfreceiver.syncplayer.data.model

/**
 * Domain model for server configuration.
 */
data class ServerConfig(
    val protocol: SyncProtocol,
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val remotePath: String,
    val lastSyncTime: Long? = null,
    val lastSyncResult: String? = null
)
