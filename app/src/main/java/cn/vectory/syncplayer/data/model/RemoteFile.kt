package cn.vectory.syncplayer.data.model

/**
 * A file/directory entry on the remote server.
 */
data class RemoteFile(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val lastModified: Long = 0
)
