package cn.vectory.syncplayer.sync

import cn.vectory.syncplayer.data.model.ActiveDownload
import cn.vectory.syncplayer.data.model.SyncPhase
import cn.vectory.syncplayer.data.model.SyncProgress
import java.util.concurrent.locks.ReentrantLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.withLock

/**
 * 同步进度的状态拥有者（从 [SyncEngine] 上帝类渐进拆分第一轮提取）。
 *
 * 职责：
 *  - 持有唯一的 [SyncProgress] StateFlow（通过 [progress] 对外只读暴露）；
 *  - 持有当前同步轮次的活跃下载集合（[activeDownloadsMap]）与已完成路径集合
 *    （[completedPathsSet]），以及保护它们并发访问的 [stateLock]；
 *  - 实现进度写入的节流（[PROGRESS_THROTTLE_MS]），避免高频回调触发 UI 全量重组。
 *
 * AND-013 节流模型（完成事件与字节进度共用一个 500ms 节流阀）：
 *  - [completeDownload] / [markDeleted] / [beginDownload] / [reportDownloadProgress]
 *    仅在节流窗口到期时真正写 StateFlow，否则记录脏标记（[hasPendingDownloadState] /
 *    [hasPendingCompletedPaths]）与待发布快照字段，等待下一次发布机会；
 *  - **强制立即发布点**：phase 变化（如 DOWNLOADING→DELETING）与终态
 *    （COMPLETED/PARTIAL_FAILED/FAILED/CANCELLED）经 [value] setter 写入时，
 *    先无条件 flush 脏状态再应用写入值——保证最终 completedFiles 计数精确，
 *    终值永远不被节流吞掉；
 *  - [completedPathsSet] 的 `toSet()` 拷贝仅在真实发布时执行（完成事件本身不再
 *    每次触发 O(n) 拷贝）。
 *
 * 并发模型：[SyncEngine] 通过 `storageOperationMutex` + per-relation Mutex
 * 保证同一时刻只有一个同步轮次在写进度；所有临界区内**无挂起点**（纯内存操作），
 * 因此用 [ReentrantLock] 而非协程 Mutex——这让非 suspend 的 [value] setter 也能
 * 同步执行强制 flush（AND-013 的 phase/终态强制发布点）。[reportDownloadProgress]
 * 沿用 `tryLock` 跳过模式（抢锁失败即跳过本次更新，与原实现一致）。每轮同步开始
 * 时调用 [beginSyncRun] 清空调污状态。
 */
internal class SyncProgressPublisher(
    /** 可注入时钟（节流窗口判定）；prod 用系统时钟。 */
    private val currentTimeMs: () -> Long = System::currentTimeMillis,
) {

    private val _progress = MutableStateFlow(SyncProgress())

    /** 对外只读的进度流（替代原 [SyncEngine.progress]）。 */
    val progress: StateFlow<SyncProgress> = _progress.asStateFlow()

    /**
     * 直接读写当前进度快照（替代原 [SyncEngine] 内的 `_progress.value`）。
     *
     * AND-013：写入若伴随 phase 变化或进入终态（COMPLETED/PARTIAL_FAILED/FAILED/
     * CANCELLED），先强制 flush 待发布脏状态，再应用写入值。调用方基于旧快照的
     * `copy` 未修改的字段保留 flush 结果，显式改动的字段优先。
     */
    var value: SyncProgress
        get() = _progress.value
        set(value) {
            stateLock.withLock {
                val snapshotBeforeFlush = _progress.value
                val forceFlush = value.phase != snapshotBeforeFlush.phase || value.phase in TERMINAL_PHASES
                if (forceFlush) publishPendingLocked(ignoreWindow = true)
                _progress.value = if (forceFlush) {
                    _progress.value.applyRequestedChanges(snapshotBeforeFlush, value)
                } else {
                    value
                }
            }
        }

    /** 进度节流时间戳，配合 [PROGRESS_THROTTLE_MS] 使用（[stateLock] 保护）。 */
    private var lastProgressUpdateMs = 0L

    /** 当前同步轮次的活跃下载（key = `action.relativePath`）。 */
    private val activeDownloadsMap = mutableMapOf<String, ActiveDownload>()

    /** 当前同步轮次已完成的路径集合（下载成功 / 删除成功）。 */
    private val completedPathsSet = mutableSetOf<String>()

    /** 保护 [activeDownloadsMap]、[completedPathsSet] 与待发布状态的并发访问。 */
    private val stateLock = ReentrantLock()

    /** 待发布快照：完成/开始/字节进度事件的最新计数（[stateLock] 保护）。 */
    private var pendingTransferredSoFar = 0L
    private var pendingTotalDownloadBytes = 0L
    private var pendingCompletedFiles = 0
    private var pendingTotalFiles = 0
    private var pendingSpeedBytesPerSec: Long? = null

    /** 脏标记：存在被节流抑制的下载相位状态 / 完成路径集合未发布。 */
    private var hasPendingDownloadState = false
    private var hasPendingCompletedPaths = false

    companion object {
        const val PROGRESS_THROTTLE_MS = 500L

        /** 终态：写入这些 phase 时无条件 flush（计数精确性优先于节流）。 */
        private val TERMINAL_PHASES = setOf(
            SyncPhase.COMPLETED,
            SyncPhase.PARTIAL_FAILED,
            SyncPhase.FAILED,
            SyncPhase.CANCELLED
        )
    }

    /**
     * 清空当前轮次的活跃下载、完成路径集合与待发布脏状态。每轮 `executeSync`
     * 开始时调用（替代原先 local 变量天然每轮新建的语义）。
     */
    fun beginSyncRun() {
        stateLock.withLock {
            activeDownloadsMap.clear()
            completedPathsSet.clear()
            pendingTransferredSoFar = 0L
            pendingTotalDownloadBytes = 0L
            pendingCompletedFiles = 0
            pendingTotalFiles = 0
            pendingSpeedBytesPerSec = null
            hasPendingDownloadState = false
            hasPendingCompletedPaths = false
        }
    }

    /** 重置进度为初始空状态（原 [SyncEngine.resetProgress]）。 */
    fun reset() {
        _progress.value = SyncProgress()
    }

    /**
     * 标记某个下载开始：加入活跃集合并（在节流窗口允许时）刷新进度。
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
        stateLock.withLock {
            activeDownloadsMap[relativePath] = ActiveDownload(
                fileName = fileName,
                totalBytes = totalBytes
            )
            stashDownloadStateLocked(transferredSoFar, totalDownloadBytes, completedFiles, totalFiles)
            maybeFlushLocked()
        }
    }

    /**
     * 下载进度回调（原 site B）。**非 suspend**——[RemoteFileClient.downloadTo] 的
     * `onProgress` 是普通 lambda，沿用 `tryLock`/`unlock` 模式；抢锁失败则跳过本次
     * 更新（与原实现一致）。
     *
     * 节流：距上次发布 ≥ [PROGRESS_THROTTLE_MS] 才真正写 StateFlow；窗口内的事件
     * 记入待发布快照，由下一次 flush 一并带出。
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
        val locked = stateLock.tryLock()
        if (locked) {
            try {
                activeDownloadsMap[relativePath] = ActiveDownload(
                    fileName = fileName,
                    progress = if (total > 0) transferred.toFloat() / total else 0f,
                    transferredBytes = transferred,
                    totalBytes = fileTotalBytes
                )
                val currentTransferred =
                    transferredSoFar + activeDownloadsMap.values.sumOf { it.transferredBytes }
                val elapsed = currentTimeMs() - _progress.value.startTimeMs
                pendingSpeedBytesPerSec = if (elapsed > 1_000L) {
                    currentTransferred * 1_000L / elapsed
                } else {
                    0L
                }
                stashDownloadStateLocked(transferredSoFar, totalDownloadBytes, completedFiles, totalFiles)
                maybeFlushLocked()
            } finally {
                stateLock.unlock()
            }
        }
    }

    /**
     * 下载成功完成：记录 completedPath、移出活跃集合；发布走 500ms 节流
     * （被抑制的终计数由 phase 变化/终态写入强制 flush 补齐，语义不丢）。
     */
    suspend fun completeDownload(
        relativePath: String,
        transferredSoFar: Long,
        totalDownloadBytes: Long,
        completedFiles: Int,
        totalFiles: Int
    ) {
        stateLock.withLock {
            completedPathsSet.add(relativePath)
            activeDownloadsMap.remove(relativePath)
            stashDownloadStateLocked(transferredSoFar, totalDownloadBytes, completedFiles, totalFiles)
            hasPendingCompletedPaths = true
            maybeFlushLocked()
        }
    }

    /**
     * 下载失败：仅移出活跃集合，不计入 completedPaths（对应原 site C / site E）。
     */
    suspend fun removeActiveDownload(relativePath: String) {
        stateLock.withLock {
            activeDownloadsMap.remove(relativePath)
        }
    }

    /**
     * 删除动作成功（删除循环中）：记录 completedPath；路径集发布走节流，
     * `toSet()` 拷贝仅发生在真实发布时。
     */
    suspend fun markDeleted(relativePath: String) {
        stateLock.withLock {
            completedPathsSet.add(relativePath)
            hasPendingCompletedPaths = true
            maybeFlushLocked()
        }
    }

    /** 当前已完成路径集合的快照（用于最终 `SyncProgress` 构造）。 */
    fun completedPathsSnapshot(): Set<String> = stateLock.withLock { completedPathsSet.toSet() }

    /**
     * 记录事件的最新计数快照并置下载相位脏标记（必须持有 [stateLock]）。
     */
    private fun stashDownloadStateLocked(
        transferredSoFar: Long,
        totalDownloadBytes: Long,
        completedFiles: Int,
        totalFiles: Int
    ) {
        pendingTransferredSoFar = transferredSoFar
        pendingTotalDownloadBytes = totalDownloadBytes
        pendingCompletedFiles = completedFiles
        pendingTotalFiles = totalFiles
        hasPendingDownloadState = true
    }

    /**
     * 节流窗口允许时发布脏状态（必须持有 [stateLock]）。
     */
    private fun maybeFlushLocked() {
        if (!hasPendingDownloadState && !hasPendingCompletedPaths) return
        if (currentTimeMs() - lastProgressUpdateMs < PROGRESS_THROTTLE_MS) return
        publishPendingLocked(ignoreWindow = false)
    }

    /**
     * 发布待发布状态（必须持有 [stateLock]）。[ignoreWindow]=true 用于强制发布点
     * （phase 变化/终态）。发布完成后两类脏标记清零、节流时钟前移。
     */
    private fun publishPendingLocked(ignoreWindow: Boolean) {
        if (!ignoreWindow) {
            if (!hasPendingDownloadState && !hasPendingCompletedPaths) return
            if (currentTimeMs() - lastProgressUpdateMs < PROGRESS_THROTTLE_MS) return
        }
        if (hasPendingDownloadState) {
            // 全量快照：字节/计数/活跃集合 + 完成路径（拷贝仅在此发生）
            val activeTransferred = activeDownloadsMap.values.sumOf { it.transferredBytes }
            val totalTransferred = pendingTransferredSoFar + activeTransferred
            _progress.value = _progress.value.copy(
                activeDownloads = activeDownloadsMap.values.toList(),
                transferredBytes = totalTransferred,
                totalBytes = pendingTotalDownloadBytes,
                completedFiles = pendingCompletedFiles,
                totalFiles = pendingTotalFiles,
                currentFileName = activeDownloadsMap.values.firstOrNull()?.fileName
                    ?: _progress.value.currentFileName,
                currentFileProgress = activeDownloadsMap.values.firstOrNull()?.progress
                    ?: _progress.value.currentFileProgress,
                speedBytesPerSec = pendingSpeedBytesPerSec ?: _progress.value.speedBytesPerSec,
                completedPaths = completedPathsSet.toSet()
            )
            hasPendingDownloadState = false
            hasPendingCompletedPaths = false
            pendingSpeedBytesPerSec = null
        } else if (hasPendingCompletedPaths) {
            // 仅路径集合脏（删除循环等场景）：只发布 completedPaths，不触碰其它字段
            _progress.value = _progress.value.copy(completedPaths = completedPathsSet.toSet())
            hasPendingCompletedPaths = false
        }
        lastProgressUpdateMs = currentTimeMs()
    }

    /**
     * 将基于 [baseline] 生成的请求合并进当前（已 flush）状态。phase 变化通常来自
     * `value = value.copy(phase = ...)`：其余字段仍是旧快照，不能覆盖刚发布的终计数。
     */
    private fun SyncProgress.applyRequestedChanges(
        baseline: SyncProgress,
        requested: SyncProgress
    ): SyncProgress = copy(
        isRunning = if (requested.isRunning != baseline.isRunning) requested.isRunning else isRunning,
        totalFiles = if (requested.totalFiles != baseline.totalFiles) requested.totalFiles else totalFiles,
        completedFiles = if (requested.completedFiles != baseline.completedFiles) requested.completedFiles else completedFiles,
        failedFiles = if (requested.failedFiles != baseline.failedFiles) requested.failedFiles else failedFiles,
        totalBytes = if (requested.totalBytes != baseline.totalBytes) requested.totalBytes else totalBytes,
        transferredBytes = if (requested.transferredBytes != baseline.transferredBytes) requested.transferredBytes else transferredBytes,
        currentFileName = if (requested.currentFileName != baseline.currentFileName) requested.currentFileName else currentFileName,
        currentFileProgress = if (requested.currentFileProgress != baseline.currentFileProgress) requested.currentFileProgress else currentFileProgress,
        activeDownloads = if (requested.activeDownloads != baseline.activeDownloads) requested.activeDownloads else activeDownloads,
        speedBytesPerSec = if (requested.speedBytesPerSec != baseline.speedBytesPerSec) requested.speedBytesPerSec else speedBytesPerSec,
        phase = if (requested.phase != baseline.phase) requested.phase else phase,
        relationId = if (requested.relationId != baseline.relationId) requested.relationId else relationId,
        relationName = if (requested.relationName != baseline.relationName) requested.relationName else relationName,
        localBasePath = if (requested.localBasePath != baseline.localBasePath) requested.localBasePath else localBasePath,
        startTimeMs = if (requested.startTimeMs != baseline.startTimeMs) requested.startTimeMs else startTimeMs,
        completedPaths = if (requested.completedPaths != baseline.completedPaths) requested.completedPaths else completedPaths
    )
}
