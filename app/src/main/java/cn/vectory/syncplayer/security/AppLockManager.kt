package cn.vectory.syncplayer.security

import android.content.Context
import android.util.Log
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import cn.vectory.syncplayer.data.repository.UserPreferencesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppLockManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userPrefs: UserPreferencesRepository
) : LifecycleEventObserver {

    private val _isLocked = MutableStateFlow(false)
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    @Volatile private var cachedSecureLockEnabled = false
    @Volatile private var cacheInitialized = false

    /**
     * 融合写锁：缓存三元组（cachedSecureLockEnabled/cacheInitialized/cacheGeneration，
     * last-writer 计数）的读写都经此锁串行化——单写者（后台收集器）+ 抢写者（冷启动读）
     * 的代次协议原子成立。
     */
    private val cacheWriteLock = Any()
    private val cacheGeneration = AtomicLong(0)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var hasBeenInBackground = false
    private var isFirstStart = true

    fun lock() {
        _isLocked.value = true
    }

    fun unlock() {
        _isLocked.value = false
    }

    fun register() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        startPreferenceCollection(scope)
    }

    /**
     * internal 供单测注入测试 scope（register 依赖 ProcessLifecycleOwner，单测不可用，
     * 见 AppLockManagerTest 既有注释）。
     *
     * 故障语义（BUG-014）：瞬时 IOException 只作 fail-closed 标记并指数重订阅——
     * 数据源恢复后继续交付真值；外部取消（CancellationException）原样传播（红线 3，
     * 取消不重试）；其余异常按终态处理（收集器结束，缓存停留在 fail-closed 值，
     * 冷启动/温路径自行兜底）。
     */
    internal fun startPreferenceCollection(scope: CoroutineScope) {
        scope.launch {
            userPrefs.secureLockEnabled
                .retryWhen { cause, _ ->
                    if (cause !is IOException) return@retryWhen false
                    // 故障即 fail-closed：读不到偏好 = 视为启用（安全侧）。
                    publishCache(true)
                    Log.w(TAG, "secureLockEnabled read failed; failing closed", cause)
                    delay(LOCK_PREF_RETRY_BACKOFF_MS)
                    true
                }
                .collect {
                    publishCache(it)
                }
        }
    }

    /**
     * 缓存唯一写入口（BUG-014 版次协议）：
     *  - 常规路径：代次自增后写入（fresh 写入总胜出）；
     *  - [expectGeneration] 非空（冷启动抢写）：仅当快照代次仍一致才写入，否则保留
     *    先到的发布（故障期间 fail-closed 值不会被迟到的陈旧冷读覆盖）。
     *
     * 返回本次调用后生效的缓存值。
     */
    private fun publishCache(value: Boolean, expectGeneration: Long? = null): Boolean {
        synchronized(cacheWriteLock) {
            if (expectGeneration != null && cacheGeneration.get() != expectGeneration) {
                // 冷启动读挂起期间后台收集器已发布（故障→fail-closed，或恢复→真值）。
                // 首个发布者胜出；丢弃陈旧值。
                return cachedSecureLockEnabled
            }
            cacheGeneration.incrementAndGet()
            cachedSecureLockEnabled = value
            cacheInitialized = true
            return value
        }
    }

    override fun onStateChanged(source: LifecycleOwner, event: androidx.lifecycle.Lifecycle.Event) {
        when (event) {
            // 刻意使用 ON_STOP 而非 ON_PAUSE：ProcessLifecycleOwner 的 ON_PAUSE 在 resumedCounter
            // 归零后延迟分派，而生物识别弹窗在部分 OEM 上会让 Activity 走 onPause。若在 ON_PAUSE
            // 置锁，存在「认证成功解锁 → 延迟的 ON_PAUSE 分派 → 立刻重锁 → 再次弹认证」的锁死窗口。
            // 切换应用瞬间的内容泄漏（截屏/录屏/最近任务缩略图）已由 MainActivity 常开的
            // FLAG_SECURE 覆盖，无需靠提前置锁来兜。
            androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                hasBeenInBackground = true
            }
            androidx.lifecycle.Lifecycle.Event.ON_START -> {
                if (hasBeenInBackground || isFirstStart) {
                    isFirstStart = false
                    if (lockEnabledForStart()) {
                        _isLocked.value = true
                    }
                }
            }
            else -> {}
        }
    }

    /**
     * ON_START 是否应置锁（BUG-014）。
     *
     * 温路径：收集器已发布过（含故障 fail-closed 发布）→ 直接用缓存（无阻塞）。
     * 冷路径（收集器未发布过，典型为进程刚起、首个 ON_START 抢在收集器首轮之前）：
     * 在 [cacheWriteLock] 内一次完成「判定未初始化 + 快照代次」——快照后立即释放锁，
     * runBlocking 读在锁外进行，不阻塞后台收集器的发布；读完成后带 [expectGeneration]
     * 抢写，代次不符则丢弃（首发布者胜出）。
     *
     * 冷读有界：[coldStartReadTimeoutMs]（默认 2s）真实超时 + IOException/超时都
     * 收敛为 fail-closed（视为启用），不崩溃（DataStore 损坏时 App 仍可进入并要求
     * 认证）；外部 CancellationException 原样抛出（红线 3——本地超时表现为 null，
     * 与外部取消表现为异常，二者刻意区分）。
     */
    private fun lockEnabledForStart(): Boolean {
        val generationAtSnapshot = synchronized(cacheWriteLock) {
            if (cacheInitialized) {
                return cachedSecureLockEnabled
            }
            cacheGeneration.get()
        }
        val value = try {
            runBlocking {
                withTimeoutOrNull(coldStartReadTimeoutMs) { userPrefs.secureLockEnabled.first() }
            } ?: true  // 本地读超时 = 未知，按启用处理
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "secureLock unreadable at cold start; failing closed", e)
            true
        }
        return publishCache(value, expectGeneration = generationAtSnapshot)
    }

    companion object {
        private const val TAG = "AppLockManager"

        /** 偏好源故障后的重订阅退避（固定间隔；源恢复即恢复交付）。 */
        private const val LOCK_PREF_RETRY_BACKOFF_MS = 5_000L

        private const val COLD_START_READ_TIMEOUT_MS = 2_000L

        /** 测试注入点：冷启动本地读取超时（真实时间——runBlocking 内建独立事件循环，
         *  外层虚拟时钟不渗透）。生产默认 2s。 */
        @androidx.annotation.VisibleForTesting
        internal var coldStartReadTimeoutMs = COLD_START_READ_TIMEOUT_MS
    }
}
