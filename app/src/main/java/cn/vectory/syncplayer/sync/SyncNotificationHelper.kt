package cn.vectory.syncplayer.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import cn.vectory.syncplayer.ui.util.formatFileSize
import cn.vectory.syncplayer.MainActivity
import cn.vectory.syncplayer.data.model.SyncPhase
import cn.vectory.syncplayer.data.model.SyncProgress

object SyncNotificationHelper {

    private const val CHANNEL_ID = "sync_channel"
    const val NOTIFICATION_ID = 1001
    const val COMPLETE_NOTIFICATION_ID = 1002

    private const val ACTION_NAVIGATE_SYNC = "cn.vectory.syncplayer.NAVIGATE_SYNC"
    private const val ACTION_NAVIGATE_FOLDER = "cn.vectory.syncplayer.NAVIGATE_FOLDER"
    private const val EXTRA_RELATION_ID = "notification_relation_id"
    private const val EXTRA_LOCAL_BASE_PATH = "notification_local_base_path"

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

    fun createOngoingNotification(context: Context, progress: SyncProgress): android.app.Notification {
        val name = progress.relationName.ifEmpty { "同步" }
        val percent = progress.downloadPercent
        val eta = progress.etaFormatted()

        val title = when (progress.phase) {
            SyncPhase.SCANNING -> "$name - 正在扫描…"
            SyncPhase.DOWNLOADING -> "$name 已下载 $percent%"
            SyncPhase.DELETING -> "$name - 正在清理…"
            else -> "$name - 正在同步…"
        }

        val content = buildString {
            if (progress.activeDownloads.size > 1) {
                append("正在下载 ${progress.activeDownloads.size} 个文件")
            } else {
                append(progress.currentFileName.takeIf { it.isNotEmpty() } ?: "准备中…")
            }
            append(" (${progress.completedFiles}/${progress.totalFiles})")
            append("  剩余 $eta")
        }

        val pendingIntent = createSyncPendingIntent(context, progress.relationId)

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(content)
            .setOngoing(true)
            .setProgress(100, percent, false)
            .setContentIntent(pendingIntent)
            .build()
    }

    fun createCompleteNotification(context: Context, progress: SyncProgress): android.app.Notification {
        val name = progress.relationName.ifEmpty { "同步" }
        val title = "$name 已下载完成"

        val content = buildString {
            append("共 ${progress.completedFiles} 个文件")
            if (progress.transferredBytes > 0) {
                append("  ${formatFileSize(progress.transferredBytes)}")
            }
        }

        val pendingIntent = createFolderPendingIntent(context, progress.localBasePath)

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle(title)
            .setContentText(content)
            .setAutoCancel(true)
            .setOngoing(false)
            .setContentIntent(pendingIntent)
            .build()
    }

    fun updateNotification(context: Context, progress: SyncProgress) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (progress.phase == SyncPhase.COMPLETED) {
            manager.notify(COMPLETE_NOTIFICATION_ID, createCompleteNotification(context, progress))
            manager.cancel(NOTIFICATION_ID)
        } else if (isTerminalFailurePhase(progress.phase)) {
            manager.notify(COMPLETE_NOTIFICATION_ID, createPartialFailedNotification(context, progress))
            manager.cancel(NOTIFICATION_ID)
        } else {
            manager.notify(NOTIFICATION_ID, createOngoingNotification(context, progress))
        }
    }

    private fun isTerminalFailurePhase(phase: SyncPhase): Boolean =
        phase == SyncPhase.PARTIAL_FAILED || phase == SyncPhase.FAILED || phase == SyncPhase.CANCELLED

    private fun createPartialFailedNotification(context: Context, progress: SyncProgress): android.app.Notification {
        val name = progress.relationName.ifEmpty { "同步" }
        val title = when (progress.phase) {
            SyncPhase.PARTIAL_FAILED -> "$name 同步完成(部分失败)"
            SyncPhase.FAILED -> "$name 同步失败"
            SyncPhase.CANCELLED -> "$name 同步已取消"
            else -> "$name 已结束"
        }

        val content = buildString {
            if (progress.phase == SyncPhase.PARTIAL_FAILED) {
                append("成功 ${progress.completedFiles - progress.failedFiles}")
                append(" / 失败 ${progress.failedFiles}")
                append(" / 共 ${progress.totalFiles}")
            } else {
                append("共处理 ${progress.completedFiles} 个文件")
            }
        }

        val pendingIntent = createSyncPendingIntent(context, progress.relationId)

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(content)
            .setAutoCancel(true)
            .setOngoing(false)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createSyncPendingIntent(context: Context, relationId: Long): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_NAVIGATE_SYNC
            putExtra("navigateTo", "syncPreview")
            putExtra(EXTRA_RELATION_ID, relationId)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            (relationId % Int.MAX_VALUE).toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createFolderPendingIntent(context: Context, localBasePath: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_NAVIGATE_FOLDER
            putExtra("navigateTo", "browser")
            putExtra(EXTRA_LOCAL_BASE_PATH, localBasePath)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            COMPLETE_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }


}
