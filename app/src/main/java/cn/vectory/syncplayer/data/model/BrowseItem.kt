package cn.vectory.syncplayer.data.model

/**
 * Domain model for a browsable file item in the browser.
 */
data class BrowseItem(
    val videoItemId: Long?,
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val fileSize: Long = 0,
    val duration: Long? = null,
    // New metadata fields
    val width: Int? = null,
    val height: Int? = null,
    val bitrate: Long? = null,
    val frameRate: Float? = null,
    val thumbnailPath: String? = null,
    val playbackPosition: Long = 0,
    val playbackDuration: Long = 0,
    val isCompleted: Boolean = false,
    val isFavorite: Boolean = false,
    val isSyncBaseDir: Boolean = false
)
