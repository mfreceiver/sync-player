package cn.vectory.syncplayer.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import cn.vectory.syncplayer.MainActivity
import cn.vectory.syncplayer.R
import cn.vectory.syncplayer.data.model.SyncPhase
import cn.vectory.syncplayer.data.model.SyncProgress
import cn.vectory.syncplayer.ui.util.formatFileSize

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
            context.getString(R.string.sync_notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.sync_notif_channel_desc)
            setShowBadge(false)
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    fun createOngoingNotification(context: Context, progress: SyncProgress): android.app.Notification {
        val name = progress.relationName.ifEmpty { context.getString(R.string.sync_notif_default_name) }
        val percent = progress.downloadPercent
        val eta = progress.etaFormatted()

        val title = when (progress.phase) {
            SyncPhase.SCANNING -> context.getString(R.string.sync_notif_scanning, name)
            SyncPhase.DOWNLOADING -> context.getString(R.string.sync_notif_downloading, name, percent)
            SyncPhase.DELETING -> context.getString(R.string.sync_notif_deleting, name)
            else -> context.getString(R.string.sync_notif_syncing, name)
        }

        val content = buildString {
            if (progress.activeDownloads.size > 1) {
                append(context.getString(R.string.sync_notif_downloading_n, progress.activeDownloads.size))
            } else {
                append(progress.currentFileName.takeIf { it.isNotEmpty() }
                    ?: context.getString(R.string.sync_notif_preparing))
            }
            append(context.getString(R.string.sync_notif_progress, progress.completedFiles, progress.totalFiles))
            append(context.getString(R.string.sync_notif_eta, eta))
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
        val name = progress.relationName.ifEmpty { context.getString(R.string.sync_notif_default_name) }
        val title = context.getString(R.string.sync_notif_complete_title, name)

        val content = buildString {
            append(context.getString(R.string.sync_notif_complete_files, progress.completedFiles))
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
        val name = progress.relationName.ifEmpty { context.getString(R.string.sync_notif_default_name) }
        val title = when (progress.phase) {
            SyncPhase.PARTIAL_FAILED -> context.getString(R.string.sync_notif_partial_title, name)
            SyncPhase.FAILED -> context.getString(R.string.sync_notif_failed_title, name)
            SyncPhase.CANCELLED -> context.getString(R.string.sync_notif_cancelled_title, name)
            else -> context.getString(R.string.sync_notif_ended_title, name)
        }

        val content = if (progress.phase == SyncPhase.PARTIAL_FAILED) {
            // E3 后 completedFiles 只统计成功项（失败项不再同时自增 completed），
            // 因此这里不能再减 failedFiles —— 否则会重复扣减、把成功数显示得偏小。
            context.getString(
                R.string.sync_notif_partial_content,
                progress.completedFiles, progress.failedFiles, progress.totalFiles
            )
        } else {
            context.getString(R.string.sync_notif_summary_content, progress.completedFiles, progress.totalFiles)
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
            // AND-069: key the request code by the target path so each completion
            // notification keeps its own PendingIntent — a fixed code made every
            // older notification navigate to the newest synced folder.
            COMPLETE_NOTIFICATION_ID + localBasePath.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }


}
