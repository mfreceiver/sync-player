package cn.vectory.syncplayer.sync

import cn.vectory.syncplayer.data.model.ActiveDownload
import cn.vectory.syncplayer.data.model.SyncProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/**
 * 同步进度的状态拥有者（从 [SyncEngine] 上帝类渐进拆分第一轮提取）。
 *
 * 职责：
 *  - 持有唯一的 [SyncProgress] StateFlow（通过 [progress] 对外只读暴露）；
 *  - 持有当前同步轮次的活跃下载集合（[activeDownloadsMap]）与已完成路径集合
 *    （[completedPathsSet]），以及保护它们并发访问的 [stateMutex]；
 *  - 实现下载进度回调的节流写入（[PROGRESS_THROTTLE_MS]），避免高频回调触发 UI 全量重组。
 *
 * 语义与原 [SyncEngine] 内联实现**完全一致**，仅做位置迁移：
 *  - [SyncEngine.progress] 委托给 [progress]；
 *  - 原 `_progress.value = ...` 直写改为通过 [value]；
 *  - 原下载回调里的 `activeDownloadsMutex.tryLock`/`unlock` + 节流逻辑迁入 [reportDownloadProgress]；
 *  - 原 `updateActiveDownloadsState` 私有助手迁入本类（行为不变）。
 *
 * 并发模型（与原实现一致）：[SyncEngine] 通过 `storageOperationMutex` + per-relation Mutex
 * 保证同一时刻只有一个同步轮次在写进度，因此 [activeDownloadsMap] / [completedPathsSet]
 * 作为实例状态是安全的；每轮同步开始时调用 [beginSyncRun] 清空（原先它们是局部变量、
 * 天然每轮新建，迁移后必须显式清空以保持等价）。
 *
 * 注意：[reportDownloadProgress] 是**非 suspend** 函数——[RemoteFileClient.downloadTo]
 * 的 `onProgress` 回调是普通 lambda，无法调用 suspend 的 `Mutex.withLock`，故沿用
 * 原实现的 `tryLock`/`unlock` 模式。其余方法在 suspend 上下文中调用，使用 `withLock`。
 */
internal class SyncProgressPublisher {

    private val _progress = MutableStateFlow(SyncProgress())

    /** 对外只读的进度流（替代原 [SyncEngine.progress]）。 */
    val progress: StateFlow<SyncProgress> = _progress.asStateFlow()

    /** 直接读写当前进度快照（替代原 [SyncEngine] 内的 `_progress.value`）。 */
    var value: SyncProgress
        get() = _progress.value
        set(value) { _progress.value = value }

    /** 进度节流时间戳，配合 [PROGRESS_THROTTLE_MS] 使用。 */
    private val lastProgressUpdateMs = AtomicLong(0L)

    /** 当前同步轮次的活跃下载（key = `action.relativePath`）。 */
    private val activeDownloadsMap = mutableMapOf<String, ActiveDownload>()

    /** 当前同步轮次已完成的路径集合（下载成功 / 删除成功）。 */
    private val completedPathsSet = mutableSetOf<String>()

    /** 保护 [activeDownloadsMap] 与 [completedPathsSet] 的并发访问。 */
    private val stateMutex = Mutex()

    companion object {
        const val PROGRESS_THROTTLE_MS = 500L
    }

    /**
     * 清空当前轮次的活跃下载与完成路径集合。每轮 `executeSync` 开始时调用
     * （替代原先 local 变量天然每轮新建的语义）。
     */
    suspend fun beginSyncRun() {
        stateMutex.withLock {
            activeDownloadsMap.clear()
            completedPathsSet.clear()
        }
    }

    /** 重置进度为初始空状态（原 [SyncEngine.resetProgress]）。 */
    fun reset() {
        _progress.value = SyncProgress()
    }

    /**
     * 标记某个下载开始：加入活跃集合并刷新进度（对应原 site A：
     * `activeDownloadsMutex.withLock { activeDownloadsMap[..] = ..; updateActiveDownloadsState(..) }`）。
     *
     * 调用方传入各计数器的当前快照（[transferredSoFar] / [completedFiles] 等）。
     */
    suspend fun beginDownload(
        relativePath: String,
        fileName: String,
        totalBytes: Long,
        transferredSoFar: Long,
        totalDownloadBytes: Long,
        completedFiles: Int,
        totalFiles: Int
    ) {
        stateMutex.withLock {
            activeDownloadsMap[relativePath] = ActiveDownload(
                fileName = fileName,
                totalBytes = totalBytes
            )
            updateActiveDownloadsState(
                transferredSoFar, totalDownloadBytes, completedFiles, totalFiles
            )
        }
    }

    /**
     * 下载进度回调（对应原 site B）。**非 suspend**——[RemoteFileClient.downloadTo] 的
     * `onProgress` 是普通 lambda，沿用原实现的 `tryLock`/`unlock` 模式。
     *
     * 节流：距上次写入 ≥ [PROGRESS_THROTTLE_MS] 才真正更新 StateFlow，与原实现一致。
     * 抢锁失败（其它 worker 正持锁）则跳过本次更新——与原 `if (locked) { ... }` 一致。
     */
    fun reportDownloadProgress(
        relativePath: String,
        fileName: String,
        transferred: Long,
        total: Long,
        fileTotalBytes: Long,
        transferredSoFar: Long,
        totalDownloadBytes: Long,
        completedFiles: Int,
        totalFiles: Int
    ) {
        val locked = stateMutex.tryLock()
        if (locked) {
            try {
                activeDownloadsMap[relativePath] = ActiveDownload(
                    fileName = fileName,
                    progress = if (total > 0) transferred.toFloat() / total else 0f,
                    transferredBytes = transferred,
                    totalBytes = fileTotalBytes
                )
                val now = System.currentTimeMillis()
                if (now - lastProgressUpdateMs.get() >= PROGRESS_THROTTLE_MS) {
                    lastProgressUpdateMs.set(now)
                    val currentTransferred =
                        transferredSoFar + activeDownloadsMap.values.sumOf { it.transferredBytes }
                    val elapsed = System.currentTimeMillis() - _progress.value.startTimeMs
                    val speed = if (elapsed > 1000) currentTransferred * 1000 / elapsed else 0L
                    _progress.value = _progress.value.copy(
                        activeDownloads = activeDownloadsMap.values.toList(),
                        transferredBytes = currentTransferred,
                        totalBytes = totalDownloadBytes,
                        completedFiles = completedFiles,
                        totalFiles = totalFiles,
                        speedBytesPerSec = speed
                    )
                }
            } finally {
                stateMutex.unlock()
            }
        }
    }

    /**
     * 下载成功完成：记录 completedPath、移出活跃集合并刷新进度（对应原 site D）。
     * [completedPaths] 默认参数取当前进度里的值，与原 `updateActiveDownloadsState`
     * 默认值 `_progress.value.completedPaths` 一致。
     */
    suspend fun completeDownload(
        relativePath: String,
        transferredSoFar: Long,
        totalDownloadBytes: Long,
        completedFiles: Int,
        totalFiles: Int
    ) {
        stateMutex.withLock {
            completedPathsSet.add(relativePath)
            activeDownloadsMap.remove(relativePath)
            updateActiveDownloadsState(
                transferredSoFar, totalDownloadBytes, completedFiles, totalFiles,
                completedPaths = completedPathsSet.toSet()
            )
        }
    }

    /**
     * 下载失败：仅移出活跃集合，不计入 completedPaths（对应原 site C / site E）。
     */
    suspend fun removeActiveDownload(relativePath: String) {
        stateMutex.withLock {
            activeDownloadsMap.remove(relativePath)
        }
    }

    /**
     * 删除动作成功（删除循环中）：记录 completedPath 并把集合刷进进度。
     * 删除循环在所有下载 worker join 之后单协程执行，本无需锁，但为保持与
     * [completeDownload] 一致的内存可见性与集合一致性，仍走 [stateMutex]（无争用，开销可忽略）。
     */
    suspend fun markDeleted(relativePath: String) {
        stateMutex.withLock {
            completedPathsSet.add(relativePath)
            _progress.value = _progress.value.copy(completedPaths = completedPathsSet.toSet())
        }
    }

    /** 当前已完成路径集合的快照（用于最终 `SyncProgress` 构造）。 */
    fun completedPathsSnapshot(): Set<String> = completedPathsSet.toSet()

    /**
     * 把活跃下载集合合并进当前进度（原 [SyncEngine] 私有 `updateActiveDownloadsState`）。
     * 必须在持有 [stateMutex] 时调用。[completedPaths] 默认取当前进度里的值，与原实现一致。
     */
    private fun updateActiveDownloadsState(
        transferredSoFar: Long,
        totalDownloadBytes: Long,
        completedFiles: Int,
        totalFiles: Int,
        completedPaths: Set<String> = _progress.value.completedPaths
    ) {
        val activeTransferred = activeDownloadsMap.values.sumOf { it.transferredBytes }
        val totalTransferred = transferredSoFar + activeTransferred
        _progress.value = _progress.value.copy(
            activeDownloads = activeDownloadsMap.values.toList(),
            transferredBytes = totalTransferred,
            totalBytes = totalDownloadBytes,
            completedFiles = completedFiles,
            totalFiles = totalFiles,
            currentFileName = activeDownloadsMap.values.firstOrNull()?.fileName ?: _progress.value.currentFileName,
            currentFileProgress = activeDownloadsMap.values.firstOrNull()?.progress ?: _progress.value.currentFileProgress,
            completedPaths = completedPaths
        )
    }
}
