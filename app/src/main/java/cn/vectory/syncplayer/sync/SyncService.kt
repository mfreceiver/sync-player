package cn.vectory.syncplayer.sync

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import cn.vectory.syncplayer.data.model.SyncPhase
import cn.vectory.syncplayer.data.model.SyncProgress
import cn.vectory.syncplayer.debug.DebugLogger
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
    // BUG-018 后此 scope 只承载非协调器协程（进度采集 collector）；协调器状态一律不落此域。
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var collectorJob: Job? = null
    // E1: 跟踪「延迟停止」协程，新任务到达时取消，避免旧协程 delay 结束后 stopSelf 杀掉新任务。
    // BUG-018: 改由 drainCoordinator 的 armStop/cancelStop 闭包管理（confined 读写）。
    private var pendingStopJob: Job? = null
    // E1: 记录最近一次 onStartCommand 的 startId，供 stopSelfResult 精确停服。
    // BUG-018: 读写（onStartCommand 写 / 停服出口读）均在 Main——同一 confined 串行域。
    private var lastStartId: Int = 0

    // BUG-018（plan v3.1）: confined 串行域——协调器全部可变状态与循环调度只在此 scope
    // （= Main.immediate）上读写；onStartCommand/onTimeout/onDestroy 回调与循环调度、
    // 停服动作天然同线程同序，check-then-act 段构造性无 TOCTOU。
    private lateinit var coordinatorScope: CoroutineScope
    private lateinit var drainCoordinator: SyncServiceDrainCoordinator

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

        coordinatorScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        // BUG-018 接线前提（成文，协调器测试 #13 锁定形态）：onEligibleStop/onForcedStop 回调
        // 只调 stopService、**不得重入协调器**——停服决定经 stopTimerFired 校验后不被推翻。
        drainCoordinator = SyncServiceDrainCoordinator(
            scope = coordinatorScope,
            consumeAndExecute = { relId ->
                withContext(Dispatchers.IO) { syncEngine.consumeAndExecute(relId) }
            },
            cancelStop = { pendingStopJob?.cancel(); pendingStopJob = null },
            armStop = { delayMs, token ->
                pendingStopJob?.cancel()
                pendingStopJob = coordinatorScope.launch { // confined：到期回调与协调器同线程
                    delay(delayMs)
                    // 到期不直接停服：非挂起校验（令牌主门 + 循环存活/队列空纵深）
                    drainCoordinator.stopTimerFired(token)
                }
            },
            // 两类停服出口同构：Main 域内读 lastStartId（与 onStartCommand 写点同一串行域）。
            onEligibleStop = { stopService(lastStartId) }, // 空闲停（NO_TASK/SYNC 延迟后）
            onForcedStop = { stopService(lastStartId) },   // 用户取消延迟停（取消时无新 start ⇒ lastStartId 未变）
            stopDelayForPass = { hadTask ->                // 与既有三分支语义逐条等价
                when {
                    !hadTask -> DELAY_AFTER_NO_TASK_MS
                    !syncEngine.progress.value.isRunning -> DELAY_AFTER_SYNC_MS
                    else -> null
                }
            },
            // 复审阻断（敏感日志红线）：只留 relationId + 异常类别——不拼 path、不传原始
            // Throwable/message（DebugLogger 带 throwable 会把 stack+message 写入调试环形缓冲）。
            onPassFailed = { relationId, e ->
                DebugLogger.w(
                    "SyncService",
                    "drain pass failed: relationId=$relationId, error=${e.javaClass.simpleName}"
                )
            },
        )
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

        // BUG-018: 旧代码在此以 `syncJob?.isActive == true` 合并丢弃 start 请求——Job 收尾
        // 亚毫秒窗口内到达的请求被丢、PENDING 行滞留。协调器常驻 drain 循环 + UNLIMITED
        // 缓冲承接每个请求；入队即作废停服令牌（Main 回调内直接调用——非挂起、即返；
        // 旧 pendingStopJob 手工取消由 onSyncRequested→cancelStop 承接）。
        drainCoordinator.onSyncRequested(relationId)

        return START_REDELIVER_INTENT
    }

    /**
     * API 35 dataSync foreground-service budget expired. Stop promptly so the system does not
     * escalate the timeout into an ANR/crash. The cancellation reaches the engine's CE cleanup;
     * the service publishes a terminal notification before stopping this exact start request.
     * BUG-018: 弃置经协调器（cancel job + 排空缓冲 + 作废令牌）——预算到期无延迟语义，
     * 立即停本 start 请求。
     */
    override fun onTimeout(startId: Int) {
        SyncNotificationHelper.updateNotification(
            this,
            syncEngine.progress.value.copy(phase = SyncPhase.CANCELLED)
        )
        drainCoordinator.cancelActiveDrain()
        stopService(startId)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        onTimeout(startId)
    }

    private fun stopService(startId: Int) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfResult(startId)
    }

    /**
     * BUG-018（plan v3.1）: 用户取消 = 立即 CANCELLED 通知 + cancelActiveDrainAndArmForcedStop。
     * 旧实现的 Default 域 `launch { 通知; delay; stopService }` 绕过 confined 校验、无失效
     * 协议——用户取消后 start(D)，旧取消协程越过 delay 可取消点仍执行 stopForeground，D 的
     * 前台状态被移除。现取消延迟停服整体并入 confined 域与统一令牌失效协议：延迟窗口内任何
     * 新 start（onSyncRequested 入口）即作废 FORCED 令牌——旧取消回调任何时序都不能停掉
     * 新 drain 或移除其前台。
     */
    private fun cancelSync() {
        SyncNotificationHelper.updateNotification(
            this,
            syncEngine.progress.value.copy(phase = SyncPhase.CANCELLED)
        )
        drainCoordinator.cancelActiveDrainAndArmForcedStop(DELAY_AFTER_CANCEL_MS)
    }

    override fun onDestroy() {
        if (::coordinatorScope.isInitialized) {
            drainCoordinator.cancelActiveDrain()
            coordinatorScope.cancel() // 连带常驻循环与计时器
        }
        collectorJob?.cancel()
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
