package cn.vectory.syncplayer.sync

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import cn.vectory.syncplayer.data.model.SyncPhase
import cn.vectory.syncplayer.data.model.SyncProgress
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@AndroidEntryPoint
class SyncService : Service() {

    @Inject lateinit var syncEngine: SyncEngine

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var syncJob: Job? = null

    private val _syncProgress = MutableStateFlow(SyncProgress())
    val syncProgress = _syncProgress.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        SyncNotificationHelper.createNotificationChannel(this)
        startForeground(
            SyncNotificationHelper.NOTIFICATION_ID,
            SyncNotificationHelper.createOngoingNotification(this, SyncProgress())
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelSync()
            return START_NOT_STICKY
        }

        val relationId = intent?.getLongExtra(EXTRA_RELATION_ID, 0L) ?: 0L

        if (syncJob?.isActive == true) {
            return START_NOT_STICKY
        }

        syncJob?.cancel()
        syncJob = serviceScope.launch {
            val task = syncEngine.consumePendingTask()
            if (task != null && task.relationId == relationId) {
                _syncProgress.value = SyncProgress(
                    isRunning = true,
                    phase = SyncPhase.DOWNLOADING,
                    totalFiles = task.toDownload.size + task.toDelete.size,
                    relationId = relationId,
                    relationName = task.relationName,
                    localBasePath = task.localBasePath
                )
                SyncNotificationHelper.updateNotification(this@SyncService, _syncProgress.value)

                syncEngine.executeSync(
                    relationId,
                    task.toDownload,
                    task.toDelete,
                    task.relationName,
                    task.localBasePath
                )
            } else {
                val (toDownload, toDelete) = syncEngine.dryRun(relationId)
                _syncProgress.value = SyncProgress(
                    isRunning = true,
                    phase = SyncPhase.DOWNLOADING,
                    totalFiles = toDownload.size + toDelete.size,
                    relationId = relationId
                )
                syncEngine.executeSync(relationId, toDownload, toDelete)
            }

            _syncProgress.value = syncEngine.progress.value
            SyncNotificationHelper.updateNotification(this@SyncService, _syncProgress.value)

            delay(3000)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }

        serviceScope.launch {
            syncEngine.progress.collect { progress ->
                _syncProgress.value = progress
                SyncNotificationHelper.updateNotification(this@SyncService, progress)
            }
        }

        return START_NOT_STICKY
    }

    private fun cancelSync() {
        syncJob?.cancel()
        syncJob = null
        serviceScope.launch {
            SyncNotificationHelper.updateNotification(
                this@SyncService,
                SyncProgress(phase = SyncPhase.CANCELLED)
            )
            delay(1000)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        syncJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_CANCEL = "cn.vectory.syncplayer.ACTION_CANCEL_SYNC"
        const val EXTRA_RELATION_ID = "relationId"

        fun start(context: Context, relationId: Long = 0L) {
            val intent = Intent(context, SyncService::class.java)
            intent.putExtra(EXTRA_RELATION_ID, relationId)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, SyncService::class.java)
            intent.action = ACTION_CANCEL
            context.startService(intent)
        }
    }
}
