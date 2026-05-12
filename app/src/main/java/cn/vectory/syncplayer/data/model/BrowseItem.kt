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
    val isTrashed: Boolean = false,
    val isSyncBaseDir: Boolean = false,
    val containsSyncBaseDir: Boolean = false,
    val isInSyncBaseDir: Boolean = false,
    // Folder statistics (non-zero only for directory items)
    val videoCount: Int = 0,
    val playedCount: Int = 0,
    val trashedCount: Int = 0,
    // Video metadata
    val hasGainAdjusted: Boolean = false,
    val star: Int = 0,
    val thumbnailPaths: List<String> = emptyList(),
    // Image metadata
    val mediaType: String = "VIDEO",
    val dateTaken: Long? = null
)
