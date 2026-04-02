package com.mfreceiver.syncplayer.sync

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.mfreceiver.syncplayer.data.model.SyncProgress
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
            SyncNotificationHelper.createNotification(this, SyncProgress())
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelSync()
            return START_NOT_STICKY
        }

        syncJob?.cancel()
        syncJob = serviceScope.launch {
            val (toDownload, toDelete) = syncEngine.dryRun()

            _syncProgress.value = SyncProgress(
                isRunning = true,
                phase = com.mfreceiver.syncplayer.data.model.SyncPhase.DOWNLOADING,
                totalFiles = toDownload.size + toDelete.size
            )

            syncEngine.executeSync(toDownload, toDelete)

            _syncProgress.value = syncEngine.progress.asStateFlow().value
            SyncNotificationHelper.updateNotification(this@SyncService, _syncProgress.value)

            delay(2000)
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
                SyncProgress(phase = com.mfreceiver.syncplayer.data.model.SyncPhase.CANCELLED)
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
        const val ACTION_CANCEL = "com.mfreceiver.syncplayer.ACTION_CANCEL_SYNC"

        fun start(context: Context) {
            val intent = Intent(context, SyncService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, SyncService::class.java)
            intent.action = ACTION_CANCEL
            context.startService(intent)
        }
    }
}
