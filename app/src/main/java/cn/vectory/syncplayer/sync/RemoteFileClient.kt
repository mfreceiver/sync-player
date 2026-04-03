package cn.vectory.syncplayer.sync

import cn.vectory.syncplayer.data.model.RemoteFile
import cn.vectory.syncplayer.data.model.ServerConfig
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
