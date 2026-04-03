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
    SKIP            // Both identical, no action needed
}
