package cn.vectory.syncplayer.data.model

data class ActiveDownload(
    val fileName: String,
    val progress: Float = 0f,
    val transferredBytes: Long = 0,
    val totalBytes: Long = 0
)

/**
 * Real-time sync progress state.
 */
data class SyncProgress(
    val isRunning: Boolean = false,
    val totalFiles: Int = 0,
    val completedFiles: Int = 0,
    val failedFiles: Int = 0,
    val totalBytes: Long = 0,
    val transferredBytes: Long = 0,
    val currentFileName: String = "",
    val currentFileProgress: Float = 0f,
    val activeDownloads: List<ActiveDownload> = emptyList(),
    val speedBytesPerSec: Long = 0,
    val phase: SyncPhase = SyncPhase.IDLE,
    val relationId: Long = 0,
    val relationName: String = "",
    val localBasePath: String = "",
    val startTimeMs: Long = 0,
    val completedPaths: Set<String> = emptySet()
) {
    val downloadPercent: Int
        get() = if (totalBytes > 0) (transferredBytes * 100 / totalBytes).toInt() else 0

    val etaSeconds: Long
        get() {
            if (transferredBytes <= 0 || totalBytes <= 0 || startTimeMs <= 0) return -1
            val elapsed = System.currentTimeMillis() - startTimeMs
            if (elapsed <= 0) return -1
            val speed = transferredBytes.toDouble() / (elapsed / 1000.0)
            if (speed <= 0) return -1
            val remaining = (totalBytes - transferredBytes).toDouble()
            return (remaining / speed).toLong()
        }

    fun etaFormatted(): String {
        val secs = etaSeconds
        if (secs < 0) return "--:--"
        val h = secs / 3600
        val m = (secs % 3600) / 60
        val s = secs % 60
        return if (h > 0) "%02d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }
}

enum class SyncPhase {
    IDLE,
    SCANNING,       // Comparing remote vs local file lists
    CONFIRMING,     // Waiting for user confirmation (dry run results shown)
    DOWNLOADING,    // Downloading files
    DELETING,       // Deleting local files
    COMPLETED,
    PARTIAL_FAILED, // Some files completed, some failed
    FAILED,
    CANCELLED
}
