package cn.vectory.syncplayer.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import cn.vectory.syncplayer.data.model.SyncPhase
import cn.vectory.syncplayer.data.model.SyncProgress

object SyncNotificationHelper {

    private const val CHANNEL_ID = "sync_channel"
    const val NOTIFICATION_ID = 1001

    fun createNotificationChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "文件同步",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "显示文件同步进度"
            setShowBadge(false)
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    fun createNotification(context: Context, progress: SyncProgress): android.app.Notification {
        val title = when (progress.phase) {
            SyncPhase.SCANNING -> "正在扫描远程文件…"
            SyncPhase.DOWNLOADING -> "正在下载文件…"
            SyncPhase.DELETING -> "正在清理文件…"
            SyncPhase.COMPLETED -> "同步完成"
            SyncPhase.FAILED -> "同步失败"
            SyncPhase.CANCELLED -> "同步已取消"
            else -> "正在同步…"
        }

        val content = buildString {
            append(progress.currentFileName.takeIf { it.isNotEmpty() } ?: "准备中…")
            append(" (${progress.completedFiles}/${progress.totalFiles})")
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(content)
            .setOngoing(progress.phase != SyncPhase.COMPLETED && progress.phase != SyncPhase.FAILED)
            .setProgress(progress.totalFiles, progress.completedFiles, progress.totalFiles == 0)

        if (progress.transferredBytes > 0) {
            builder.setSubText(formatSpeed(progress.speedBytesPerSec))
        }

        return builder.build()
    }

    fun updateNotification(context: Context, progress: SyncProgress) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification(context, progress))
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        return when {
            bytesPerSec >= 1024 * 1024 -> "%.1f MB/s".format(bytesPerSec / (1024.0 * 1024))
            bytesPerSec >= 1024 -> "%.0f KB/s".format(bytesPerSec / 1024.0)
            else -> "$bytesPerSec B/s"
        }
    }
}
