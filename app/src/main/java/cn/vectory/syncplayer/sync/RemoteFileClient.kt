package cn.vectory.syncplayer.sync

import cn.vectory.syncplayer.data.model.RemoteFile
import cn.vectory.syncplayer.data.model.SyncSource
import java.io.File

interface RemoteFileClient {
    suspend fun connect(source: SyncSource, remotePath: String)
    suspend fun disconnect()
    suspend fun close()
    suspend fun testConnection(source: SyncSource, remotePath: String): Boolean
    suspend fun listFiles(path: String): List<RemoteFile>
    suspend fun listDirectories(path: String): List<RemoteFile>
    suspend fun downloadFile(
        remotePath: String,
        localFile: File,
        onProgress: ((transferred: Long, total: Long) -> Unit)? = null
    ): Boolean
    suspend fun deleteFile(remotePath: String): Boolean
}
