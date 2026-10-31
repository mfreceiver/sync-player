package cn.vectory.syncplayer.sync

import android.util.Log
import cn.vectory.syncplayer.data.model.SyncAction
import cn.vectory.syncplayer.debug.DebugLogger
import cn.vectory.syncplayer.storage.MediaFileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 字节数的人类可读格式化（空间预检日志与 `R.string.sync_disk_space_insufficient` 共用）。
 *
 * 从 [SyncEngine] 提取为包级函数：[SyncSpaceChecker] 与 [SyncEngine] 都需要它，
 * 提为顶层避免在两处重复实现。
 */
internal fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.0f MB".format(bytes / (1024.0 * 1024))
    else -> "%.1f KB".format(bytes / 1024.0)
}

/**
 * 同步前空间预检（从 [SyncEngine] 上帝类渐进拆分第一轮提取）。
 *
 * 职责：计算待下载文件总大小，与当前卷可用空间比较，返回判定结果。
 *
 *  - 求和溢出 → 饱和为 [Long.MAX_VALUE]（必然大于任何可用空间 → 判定 Insufficient，
 *    与「磁盘不够」同一处理路径，无需单独的降级分支）；
 *  - 可用空间读取失败（`usableSpace() <= 0`）→ 视为空间不足，与磁盘写满同路径。
 *
 * 本类只负责**计算与诊断日志**；判定后的副作用（`updateLastSync` / 进度置 FAILED /
 * `return false`）仍由调用方处理，以保持「checker 无状态、无 IO 副作用、不依赖
 * repository」的单一职责。每次调用都是无状态的纯计算（`usableSpace()` 的阻塞 IO
 * 走 [Dispatchers.IO]）。
 */
internal class SyncSpaceChecker {

    /** [checkSpace] 的判定结果。 */
    sealed interface Result {
        /** 预检通过，可继续下载。 */
        data class Ok(val needed: Long, val available: Long) : Result
        /** 空间不足，调用方必须中止同步。 */
        data class Insufficient(val needed: Long, val available: Long) : Result
    }

    /**
     * 对 [actions] 做空间预检。
     *
     * @param actions 待下载动作（fileSize 为远端源文件大小）
     * @param store 当前存储门面（提供 `usableSpace()`）
     * @return 判定结果；诊断日志已在此处输出，调用方据 [Result.Insufficient] 执行副作用。
     */
    suspend fun checkSpace(actions: List<SyncAction>, store: MediaFileStore): Result {
        val totalDownloadSize = computeTotalSize(actions)
        // usableSpace() 内部阻塞，必须放到 Dispatchers.IO。
        val availableSpace = withContext(Dispatchers.IO) { store.usableSpace() }
        DebugLogger.i(TAG, "空间检查: 需要 ${formatSize(totalDownloadSize)}, 可用 ${formatSize(availableSpace)}")
        return if (totalDownloadSize > availableSpace) {
            Result.Insufficient(totalDownloadSize, availableSpace)
        } else {
            Result.Ok(totalDownloadSize, availableSpace)
        }
    }

    /**
     * 外部 SD v2：按卷分组的预检结果。
     *
     * [volumeIndex] 是触发判定的 action 下标（诊断日志只记录下标，不记录卷 id，
     * 避免把稳定标识写进日志面）。任何卷 Insufficient/Unavailable 都中止整轮同步。
     */
    sealed interface VolumeResult {
        /** 全部卷预检通过。perVolume: volumeId → 该卷待写入总量（诊断用）。 */
        data class Ok(val perVolume: Map<String, Long>) : VolumeResult
        /** 某卷空间不足（needed 为饱和求和后的需求，available 为该卷可用字节）。 */
        data class Insufficient(val volumeIndex: Int, val needed: Long, val available: Long) : VolumeResult
        /** 某卷不可用（缺卡/IO 失败），[code] 为端口判定的结构化错误码。 */
        data class Unavailable(val volumeIndex: Int, val code: SyncErrorCode) : VolumeResult
    }

    /**
     * 外部 SD v2：按目标卷分组的同步前空间预检。
     *
     * [targets] 与 [actions] 按下标一一对应（由调用方在 storageOperationMutex
     * 快照期内解析）。每卷独立求和（饱和 [Long.MAX_VALUE]）与独立可用空间比较；
     * 卷可用空间读取失败返回 [VolumeResult.Unavailable]。
     *
     * 与 [checkSpace] 同一职责边界：只计算与记诊断日志，不执行副作用。
     */
    suspend fun checkSpaceByVolume(
        actions: List<SyncAction>,
        targets: List<SyncVolumeTarget>,
        port: SyncStoragePort
    ): VolumeResult {
        val neededByVolume = HashMap<String, Long>()
        val indexByVolume = HashMap<String, Int>()
        for (i in actions.indices) {
            val volumeId = targets[i].volumeId
            val prev = neededByVolume[volumeId] ?: 0L
            neededByVolume[volumeId] = try {
                Math.addExact(prev, actions[i].fileSize)
            } catch (_: ArithmeticException) {
                Long.MAX_VALUE
            }
            indexByVolume.putIfAbsent(volumeId, i)
        }
        for ((volumeId, needed) in neededByVolume) {
            when (val space = port.usableSpace(SyncVolumeTarget(volumeId))) {
                is SyncSpaceOutcome.Unavailable -> {
                    DebugLogger.e(TAG, "卷预检不可用: volumeIndex=${indexByVolume[volumeId]}")
                    return VolumeResult.Unavailable(indexByVolume[volumeId] ?: 0, space.code)
                }
                is SyncSpaceOutcome.Available -> {
                    val available = space.usableBytes
                    DebugLogger.i(TAG, "卷空间检查: 需要 ${formatSize(needed)}, 可用 ${formatSize(available)}")
                    if (needed > available) {
                        return VolumeResult.Insufficient(
                            indexByVolume[volumeId] ?: 0, needed, available
                        )
                    }
                }
            }
        }
        return VolumeResult.Ok(neededByVolume)
    }

    /**
     * 计算 [actions] 中所有文件的总大小。求和溢出时饱和为 [Long.MAX_VALUE]
     * （判定为空间不足，属正确语义：待下载总量已超出任何可表达的空间）。
     */
    private fun computeTotalSize(actions: List<SyncAction>): Long {
        var total = 0L
        for (action in actions) {
            total = try {
                Math.addExact(total, action.fileSize)
            } catch (_: ArithmeticException) {
                Log.w(TAG, "computeTotalSize 溢出: total=$total + fileSize=${action.fileSize}")
                return Long.MAX_VALUE
            }
        }
        return total
    }

    companion object {
        // 保留原 SyncEngine.TAG 以维持日志连续性（诊断日志属于开发面，非 i18n 目标）。
        private const val TAG = "SyncEngine"
    }
}

/**
 * 外部 SD v2：按卷空间预约账本——弥补 E9 逐文件复检的「检查-生效」竞态：
 * 3 lane 并发时各 lane 的复检都可能通过但合计超出卷可用空间（慢卡上写入
 * 落后于检查尤其明显）。预约在 mutex 内做「卷可用 - 已预约」判定，保证
 * 在途写入总量不超卖；action 结束（成败/取消）后 [release]。
 *
 * 无状态副作用、不持有 store——所有可用空间读取经 [SyncStoragePort]。
 */
internal class VolumeSpaceReservation {

    /** [tryReserve] 的判定结果。 */
    sealed interface Result {
        /** 预约成功。 */
        data class Reserved(val usableBytes: Long) : Result
        /** 预约失败：空间不足（含可用空间读取失败的降级语义）或卷不可用。 */
        data class Denied(val code: SyncErrorCode, val needed: Long, val available: Long?) : Result {
            /** 转结构化异常（调用方在 attempt 循环外抛出，走失败计费路径）。 */
            fun toSyncException(operation: SyncOperation, path: String? = null): SyncException =
                SyncException(code, operation, path = path, expectedBytes = needed, availableBytes = available)
        }
    }

    private val mutex = kotlinx.coroutines.sync.Mutex()
    private val reservedByVolume = HashMap<String, Long>()

    /**
     * 尝试为 [volumeId] 预约 [bytes]。判据：`卷可用空间 - 该卷已预约 > bytes`
     * （bytes <= 0 视为探针，只复检不占用）。可用空间 <= 0 沿用旧语义视为不足。
     */
    suspend fun tryReserve(volumeId: String, bytes: Long, port: SyncStoragePort): Result = mutex.lockWith {
        val space = port.usableSpace(SyncVolumeTarget(volumeId))
        when (space) {
            is SyncSpaceOutcome.Unavailable -> Result.Denied(space.code, bytes, null)
            is SyncSpaceOutcome.Available -> {
                val alreadyReserved = reservedByVolume[volumeId] ?: 0L
                val available = space.usableBytes
                // P1-13（sol）：`remaining in 0 until bytes` 对 remaining<0（已超卖）
                // 不成立反而放行，available<0 也放行——超卖窗口。改为一致快照额度：
                // bytes>0 时，available<0（读取异常值）或剩余额度 <bytes 一律拒绝。
                if (bytes > 0 && (available < 0 || available - alreadyReserved < bytes)) {
                    return@lockWith Result.Denied(
                        SyncErrorCode.INSUFFICIENT_SPACE, bytes, available
                    )
                }
                reservedByVolume[volumeId] = saturatingAdd(alreadyReserved, bytes)
                Result.Reserved(available)
            }
        }
    }

    /** Mutex 模板（避免 try/finally 样板重复）。 */
    private suspend fun <T> kotlinx.coroutines.sync.Mutex.lockWith(block: suspend () -> T): T {
        lock()
        try {
            return block()
        } finally {
            unlock()
        }
    }

    /** 释放 [bytes] 预约（action 收尾时调用；bytes 取当年实际预约量）。 */
    suspend fun release(volumeId: String, bytes: Long) {
        mutex.lock()
        try {
            val current = reservedByVolume[volumeId] ?: 0L
            reservedByVolume[volumeId] = (current - bytes).coerceAtLeast(0L)
        } finally {
            mutex.unlock()
        }
    }

    private fun saturatingAdd(a: Long, b: Long): Long = try {
        Math.addExact(a, b)
    } catch (_: ArithmeticException) {
        Long.MAX_VALUE
    }
}
