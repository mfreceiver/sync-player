package cn.vectory.syncplayer.sync

import cn.vectory.syncplayer.data.model.RemoteFile
import cn.vectory.syncplayer.data.model.SyncSource
import java.io.OutputStream

interface RemoteFileClient {
    suspend fun connect(source: SyncSource, remotePath: String)
    suspend fun disconnect()
    suspend fun close()
    suspend fun testConnection(source: SyncSource, remotePath: String): Boolean
    suspend fun listFiles(path: String): List<RemoteFile>
    suspend fun listDirectories(path: String): List<RemoteFile>
    /**
     * Download remote file content into [sink]. Does NOT close the sink; caller controls lifecycle.
     * [remotePath] is relative to the connected base path.
     * Returns true on successful full download, throws on failure.
     */
    suspend fun downloadTo(
        remotePath: String,
        sink: OutputStream,
        onProgress: ((transferred: Long, total: Long) -> Unit)? = null
    ): Boolean
    suspend fun deleteFile(remotePath: String): Boolean
}
