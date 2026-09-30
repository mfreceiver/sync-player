package cn.vectory.syncplayer.navigation

import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Collections
import java.util.WeakHashMap
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * P3–P5 导航↔队列快照协调层。
 *
 * - 导航发起处（Browser/Search 回调）：「prepare 快照 → 回填内存队列 →
 *   onReady 导航并写 token」作为一个**事务**进入单消费者串行队列。
 *   队列按主线程登记序执行：后点击永远晚于先点击导航（含缓存复用快路径——
 *   可跳 IO 不跳队），底层 IO 完成顺序不可能改变导航顺序。
 *   🟠3 生命周期：作用域取消/onCleared → 通道 cancel（关闭并丢弃缓冲积压，
 *   未执行闭包不滞留）；trySend 对已终止通道优雅 no-op；事务内普通异常逐
 *   事务捕获记录后继续服务队列，CE 先传播并以 finally 干净终止消费者。
 *   残余状态如实声明：事务并非不可分割——若作用域在 prepare 完成后、onReady
 *   前被取消/失败，快照可能已落盘而导航与 token 未发生。该快照无 token 引用，
 *   无害：恢复侧没有指向它的 token；旧身份冷恢复按精确匹配拒绝（唯一身份
 *   协议，无代次/水位概念），后续写入签发全新随机身份。
 * - 进程恢复处（Player/ImageViewer destination 组合期、目标 ViewModel 构造
 *   之前）：**磁盘解析与队列回填分离**（🔴1）——解析结果按 token 缓存（有界
 *   LRU 内存表），回填门控按消费方身份（backStackEntry 的 SavedStateHandle
 *   身份，弱引用表），每消费方恰一次成功回填；缺失 token 按各自 entry 独立
 *   处理（不合并）。登记语义（🔴1）：**解析成功即登记"已回填"**——包括
 *   解析成功但 route 不在列表的 route 降级（解析结果有效，无需重试）；解析
 *   失败（null）或 CE 不登记（允许重试，命中解析缓存则零磁盘）。
 *   LRU 缓存以（expectedType, token）复合为键：热缓存命中不可能绕过 store
 *   的媒体类型校验（跨类型 token 永不命中他类缓存）。
 * - 恢复绑定以 **route 参数为真源**：恢复列表必须包含 route 当前项，否则
 *   降级为仅该项；token 自身 currentId 与 route 不一致不影响全量恢复。
 * - 任何失败（快照缺失/损坏/错配）一律降级为仅 route 当前单项，绝不整库、
 *   绝不抛异常（CancellationException 除外，始终先传播）。
 */
@HiltViewModel
class QueueNavigationCoordinator @Inject constructor(
    private val snapshotStore: QueueSnapshotStore,
    private val videoQueue: VideoQueue,
    private val imageQueue: ImageQueue,
) : ViewModel() {

    /**
     * 🔴2 点击顺序协议：主线程调用入口 trySend 登记序 → 本协程（Main.immediate
     * 单消费者）按登记序逐个执行完整事务。UNLIMITED 缓冲保证登记永不溢出。
     */
    private val navActions = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    /**
     * 🔴1 回填门控：按消费方身份（SavedStateHandle 实例，未重写 equals → 身份
     * 语义）恰一次**成功**回填。弱引用表防 popped entry 泄漏。
     */
    private val backfilledConsumers: MutableSet<SavedStateHandle> =
        Collections.newSetFromMap(WeakHashMap())

    /**
     * 🔴1 磁盘解析缓存（与回填门控分离）；键为（expectedType, token）复合串
     * —— 热命中不可能跨媒体类型旁路 store 的类型校验。LRU 有界防长会话内存
     * 增长；仅缓存成功解析（失败不缓存：允许重试，语义见 restore* 方法）。
     */
    private val parsedSnapshots = object : LinkedHashMap<String, List<Long>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Long>>): Boolean =
            size > PARSE_CACHE_CAPACITY
    }

    init {
        viewModelScope.launch {
            try {
                for (action in navActions) {
                    try {
                        action()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // 🟠3 逐事务兜底：普通异常记录后继续服务队列，不终止唯一消费者。
                        Log.w(TAG, "navigation transaction failed; continue serving queue", e)
                    }
                }
            } finally {
                // 🔴2 作用域取消/CE 传播 → 干净终止：cancel（而非 close）同时
                // 拒绝新发送并丢弃缓冲积压，未执行闭包不滞留通道。
                navActions.cancel()
            }
        }
    }

    override fun onCleared() {
        // 🔴2 显式 cancel（重复调用安全）：拒绝新发送 + 丢弃缓冲积压；之后的
        // trySend 优雅 no-op。
        navActions.cancel()
        super.onCleared()
    }

    /**
     * 🔴2 可观察证据：通道已终止。[Channel.cancel] 的契约保证缓冲元素被丢弃、
     * 不可再被接收（未执行闭包不滞留通道）。注：本版本 kotlinx 对 cancelled
     * 通道的 [Channel.isEmpty] 返回值是实现细节（实测 false），不作为判据。
     */
    @get:VisibleForTesting
    internal val navQueueDrainedForTest: Boolean
        get() = navActions.isClosedForReceive

    /**
     * 播放器导航前置：入队完整事务（快照写入 → 回填 [videoQueue] → onReady），
     * 按登记序在主线程执行。永不抛异常；失败以 SINGLE 降级 token 保底。
     */
    fun navigateToPlayer(currentId: Long, ids: List<Long>, onReady: (String) -> Unit) {
        val safeIds = if (ids.isEmpty()) listOf(currentId) else ids
        // 🟠3 检查 trySend 结果：onCleared 竞态下通道已关闭 → 优雅 no-op（不崩、不导航）。
        if (navActions.trySend {
                val token = prepare(QueueMediaType.VIDEO, safeIds, currentId)
                videoQueue.setIds(safeIds)
                onReady(token)
            }.isFailure
        ) return
    }

    /** 同 [navigateToPlayer]，作用于图片查看器队列。 */
    fun navigateToImageViewer(currentId: Long, ids: List<Long>, onReady: (String) -> Unit) {
        val safeIds = if (ids.isEmpty()) listOf(currentId) else ids
        if (navActions.trySend {
                val token = prepare(QueueMediaType.IMAGE, safeIds, currentId)
                imageQueue.setIds(safeIds)
                onReady(token)
            }.isFailure
        ) return
    }

    private suspend fun prepare(type: QueueMediaType, ids: List<Long>, currentId: Long): String =
        try {
            snapshotStore.prepare(type, ids, currentId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Store 内部已兜底，这里是最后一道防线：保证导航永不因快照失败中断。
            // SINGLE 恢复不绑定文件身份，携带独立随机身份仅为格式统一。
            Log.w(TAG, "queue snapshot prepare failed; degrading", e)
            QueueToken(type, snapshotStore.newSnapshotId(), currentId, QueueToken.Mode.SINGLE).encode()
        }

    /**
     * Player destination 组合期第一行调用（先于 `hiltViewModel()`）。
     *
     * 🔴1 回填门控按消费方（[handle] 身份）：每消费方恰一次**成功**回填——
     * 重组跳过；解析失败不登记（允许重试，重试命中解析缓存则零磁盘）；CE
     * 传播前同样不登记。缺失 token 的各 entry 相互独立，不合并去重。
     * 🟠3 恢复绑定：[routeCurrentId]（route 参数）为真源——恢复出的列表必须
     * 包含它才接受全量；类型经 [QueueMediaType.VIDEO] 显式绑定，错型 token
     * 一律降级。
     */
    fun restoreVideoQueue(handle: SavedStateHandle, routeCurrentId: Long?) {
        if (routeCurrentId == null || handle in backfilledConsumers) return
        val token = handle.get<String>(QUEUE_VIDEO_TOKEN_KEY)
        val restored = resolveForRestore(token, QueueMediaType.VIDEO)
        videoQueue.setIds(
            if (restored != null && routeCurrentId in restored) restored else listOf(routeCurrentId)
        )
        // 失败（restored == null）不登记：后续重组可重试（重试走解析缓存/重读磁盘）。
        if (restored != null) backfilledConsumers.add(handle)
    }

    /** 同 [restoreVideoQueue]，作用于 [imageQueue]。 */
    fun restoreImageQueue(handle: SavedStateHandle, routeCurrentId: Long?) {
        if (routeCurrentId == null || handle in backfilledConsumers) return
        val token = handle.get<String>(QUEUE_IMAGE_TOKEN_KEY)
        val restored = resolveForRestore(token, QueueMediaType.IMAGE)
        imageQueue.setIds(
            if (restored != null && routeCurrentId in restored) restored else listOf(routeCurrentId)
        )
        if (restored != null) backfilledConsumers.add(handle)
    }

    /**
     * 🔴1 磁盘解析（按（expectedType, token）复合键缓存，与回填门控分离）；
     * 热命中与冷路径持有同一类型语义，不可能跨类型旁路；CE 先传播，普通
     * 异常 → null。
     */
    private fun resolveForRestore(token: String?, type: QueueMediaType): List<Long>? {
        if (token == null) return null
        val cacheKey = type.wireName + '|' + token
        synchronized(parsedSnapshots) { parsedSnapshots[cacheKey] }?.let { return it }
        val resolved = try {
            snapshotStore.readForRestoreBlocking(token, type)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "queue snapshot resolve failed; degrading", e)
            null
        }
        if (resolved != null) {
            synchronized(parsedSnapshots) { parsedSnapshots[cacheKey] = resolved }
        }
        return resolved
    }

    companion object {
        private const val TAG = "QueueNavCoordinator"

        /** 解析缓存容量：同进程活跃 token 量级为个位数（每次写入一个身份）。 */
        private const val PARSE_CACHE_CAPACITY = 5
    }
}
