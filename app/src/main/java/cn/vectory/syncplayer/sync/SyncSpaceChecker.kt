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
