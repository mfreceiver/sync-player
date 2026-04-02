package com.mfreceiver.syncplayer.data.model

/**
 * Real-time sync progress state.
 */
data class SyncProgress(
    val isRunning: Boolean = false,
    val totalFiles: Int = 0,
    val completedFiles: Int = 0,
    val totalBytes: Long = 0,
    val transferredBytes: Long = 0,
    val currentFileName: String = "",
    val currentFileProgress: Float = 0f,
    val speedBytesPerSec: Long = 0,
    val phase: SyncPhase = SyncPhase.IDLE
)

enum class SyncPhase {
    IDLE,
    SCANNING,       // Comparing remote vs local file lists
    CONFIRMING,     // Waiting for user confirmation (dry run results shown)
    DOWNLOADING,    // Downloading files
    DELETING,       // Deleting local files
    COMPLETED,
    FAILED,
    CANCELLED
}
