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

    // E2: 服务级协程改用 Dispatchers.Default——引擎内部大量 CPU/IO 工作，主线程只承担
    // 系统回调。需要主线程的地方（无，通知 API 本身线程安全）再显式切。
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var syncJob: Job? = null
    private var collectorJob: Job? = null
    // E1: 跟踪「延迟停止」协程，新任务到达时取消，避免旧协程 delay 结束后 stopSelf 杀掉新任务。
    private var pendingStopJob: Job? = null
    // E1: 记录最近一次 onStartCommand 的 startId，供 stopSelfResult 精确停服。
    private var lastStartId: Int = 0

    private val _syncProgress = MutableStateFlow(SyncProgress())
    val syncProgress = _syncProgress.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        SyncNotificationHelper.createNotificationChannel(this)
        startForeground(
            SyncNotificationHelper.NOTIFICATION_ID,
            SyncNotificationHelper.createOngoingNotification(this, SyncProgress())
        )

        collectorJob = serviceScope.launch {
            syncEngine.progress.collect { progress ->
                _syncProgress.value = progress
                SyncNotificationHelper.updateNotification(this@SyncService, progress)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // E1: 每次 onStartCommand（含 redeliver）都重新确立前台状态，
        // 不能只依赖 onCreate——系统在重建/重复拉起场景下可能跳过 onCreate。
        startForeground(
            SyncNotificationHelper.NOTIFICATION_ID,
            SyncNotificationHelper.createOngoingNotification(this, _syncProgress.value)
        )
        lastStartId = startId

        if (intent?.action == ACTION_CANCEL) {
            cancelSync()
            return START_REDELIVER_INTENT
        }

        val relationId = intent?.getLongExtra(EXTRA_RELATION_ID, 0L) ?: 0L

        if (syncJob?.isActive == true) {
            return START_REDELIVER_INTENT
        }

        // E1: 新任务到达——取消任何残留的延迟 stop，否则旧 delay 结束会 stopSelf 杀掉本次新任务。
        pendingStopJob?.cancel()
        pendingStopJob = null

        syncJob = serviceScope.launch {
            // 🔴 Atomic consume + execute under storageOperationMutex — eliminates TOCTOU
            // race where reset/switch could intervene between consume and execute.
            val (task, _) = withContext(Dispatchers.IO) {
                syncEngine.consumeAndExecute(relationId)
            }

            if (task == null) {
                scheduleStop(DELAY_AFTER_NO_TASK_MS)
                return@launch
            }

            val finalProgress = syncEngine.progress.value
            if (!finalProgress.isRunning) {
                scheduleStop(DELAY_AFTER_SYNC_MS)
            }
        }

        return START_REDELIVER_INTENT
    }

    /**
     * API 35 dataSync foreground-service budget expired. Stop promptly so the system does not
     * escalate the timeout into an ANR/crash. The cancellation reaches the engine's CE cleanup;
     * the service publishes a terminal notification before stopping this exact start request.
     */
    override fun onTimeout(startId: Int) {
        syncJob?.cancel()
        syncJob = null
        pendingStopJob?.cancel()
        SyncNotificationHelper.updateNotification(
            this,
            SyncProgress(phase = SyncPhase.CANCELLED)
        )
        stopService(startId)
    }

    /**
     * E1: 统一的延迟停止入口。所有 stopSelf 都走这里 → stopSelfResult(lastStartId)，
     * 精确移除最近的 start，并复用 pendingStopJob 句柄在新任务到达时可被取消。
     */
    private fun scheduleStop(delayMs: Long) {
        pendingStopJob?.cancel()
        pendingStopJob = serviceScope.launch {
            delay(delayMs)
            stopService(lastStartId)
        }
    }

    private fun stopService(startId: Int) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfResult(startId)
    }

    private fun cancelSync(stopStartId: Int = lastStartId) {
        syncJob?.cancel()
        syncJob = null
        pendingStopJob?.cancel()
        pendingStopJob = serviceScope.launch {
            SyncNotificationHelper.updateNotification(
                this@SyncService,
                SyncProgress(phase = SyncPhase.CANCELLED)
            )
            delay(DELAY_AFTER_CANCEL_MS)
            stopService(stopStartId)
        }
    }

    override fun onDestroy() {
        syncJob?.cancel()
        collectorJob?.cancel()
        pendingStopJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_CANCEL = "cn.vectory.syncplayer.ACTION_CANCEL_SYNC"
        const val EXTRA_RELATION_ID = "relationId"

        // E11: 三个延迟值提为常量并注释取值理由。
        // 同步完成后通知停留 1.5s，给用户看清结果再消失。
        private const val DELAY_AFTER_SYNC_MS = 1500L
        // 取消后通知停留 1s。
        private const val DELAY_AFTER_CANCEL_MS = 1000L
        // 无待执行任务时延迟 0.5s 再停服，吸收 startService 的短时抖动，避免立刻 kill。
        private const val DELAY_AFTER_NO_TASK_MS = 500L

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
