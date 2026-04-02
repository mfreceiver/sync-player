package com.mfreceiver.syncplayer.sync

import com.mfreceiver.syncplayer.data.model.RemoteFile
import com.mfreceiver.syncplayer.data.model.ServerConfig
import java.io.File

interface RemoteFileClient {
    suspend fun connect(config: ServerConfig): Boolean
    suspend fun disconnect()
    suspend fun testConnection(config: ServerConfig): Boolean
    suspend fun listFiles(path: String): List<RemoteFile>
    suspend fun downloadFile(
        remotePath: String,
        localFile: File,
        onProgress: ((transferred: Long, total: Long) -> Unit)? = null
    ): Boolean
}
