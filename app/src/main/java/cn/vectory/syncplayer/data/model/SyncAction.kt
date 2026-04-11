package cn.vectory.syncplayer.data.model

/**
 * Represents a single planned action during sync (dry run or real).
 */
data class SyncAction(
    val type: SyncActionType,
    val relativePath: String,
    val fileSize: Long = 0,
    val remoteModifiedTime: Long = 0
)

enum class SyncActionType {
    DOWNLOAD,       // Remote has, local missing or different
    DELETE_LOCAL,   // Local has, remote missing
    DELETE_BOTH,    // Delete from both local and remote
    SKIP,           // Both identical, no action needed
    RECONCILE       // File exists on disk, only need DB record (no download)
}
