package cn.vectory.syncplayer.sync

import android.util.Log
import cn.vectory.syncplayer.crypto.CryptoScheme
import cn.vectory.syncplayer.data.model.SyncAction
import cn.vectory.syncplayer.debug.DebugLogger
import cn.vectory.syncplayer.storage.volume.EncryptedMediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 字节数的人类可读格式化（空间预检日志与 `R.string.sync_disk_space_insufficient` 共用）。
 *
 * 从 [SyncEngine] 提取为包级函数：[SyncSpaceChecker] 与 [SyncEngine] 都需要它，
 * 提为顶层避免在两处重复实现。语义与原 [SyncEngine] 私有 `formatSize` 完全一致。
 */
internal fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.0f MB".format(bytes / (1024.0 * 1024))
    else -> "%.1f KB".format(bytes / 1024.0)
}

/**
 * 同步前空间预检（从 [SyncEngine] 上帝类渐进拆分第一轮提取）。
 *
 * 职责：计算待下载文件的 AEAD 密文总大小，与当前卷可用空间比较，返回判定结果。
 *
 * 语义与原 [SyncEngine] 内联实现**完全一致**：
 *  - [CryptoScheme.cipherLengthFor] 对非法 fileSize 抛 `IllegalArgumentException` → 降级跳过预检；
 *  - 密文总大小求和溢出 Long → 降级跳过预检；
 *  - 可用空间未知（[EncryptedMediaStore.usableSpace] `< 0`，如 SAF `treeUri` 无法解析到
 *    `StorageVolume`）→ 跳过预检，放行到逐文件下载降级处理（绝不把 -1 当作「空间不足」）。
 *
 * 本类只负责**计算与诊断日志**（`Log.w` / `DebugLogger.i` 均保留原 TAG 与文案）；
 * 判定后的副作用（`updateLastSync` / 进度置 FAILED / `return false`）仍由 [SyncEngine]
 * 处理，以保持「checker 无状态、无 IO 副作用、不依赖 repository」的单一职责。
 *
 * 每次调用都是无状态的纯计算（`usableSpace()` 的阻塞 IO 仍走 [Dispatchers.IO]，
 * 与原实现一致），无需 `beginSyncRun` 之类的状态重置。
 */
internal class SyncSpaceChecker {

    /** [checkSpace] 的判定结果。 */
    sealed interface Result {
        /** 可用空间未知（卷无法解析），跳过预检、放行下载。 */
        object UnknownAvailableSpace : Result
        /** 密文大小计算异常或求和溢出，跳过预检、放行下载。 */
        object CipherSizeComputationFailed : Result
        /** 空间不足，调用方必须中止同步。 */
        data class Insufficient(val needed: Long, val available: Long) : Result
        /** 预检通过，可继续下载。 */
        data class Ok(val needed: Long, val available: Long) : Result
    }

    /**
     * 对 [actions] 做空间预检。
     *
     * @param actions 待下载动作（明文 fileSize）
     * @param store 当前活跃卷（提供 `usableSpace()`）
     * @return 判定结果；诊断日志已在此处输出，调用方据 [Result.Insufficient] 执行副作用。
     */
    suspend fun checkSpace(actions: List<SyncAction>, store: EncryptedMediaStore): Result {
        // E10: 空间预检使用 AEAD 密文大小而非明文大小。
        val totalDownloadSize = computeTotalCipherSizePrecheck(actions)
        // E2: usableSpace() 内部 runBlocking 阻塞，必须放到 Dispatchers.IO。
        val availableSpace = withContext(Dispatchers.IO) { store.usableSpace() }
        if (availableSpace >= 0 && totalDownloadSize >= 0) {
            DebugLogger.i(TAG, "空间检查: 需要 ${formatSize(totalDownloadSize)}, 可用 ${formatSize(availableSpace)}")
        }
        return when {
            // SafVolume.usableSpace() 在无法把 treeUri 解析到某个 StorageVolume 时返回 -1（未知），
            // 例如 SAF 树指向 USB/云端 provider。此时不能拿 -1 当「空间不足」——那会让这些卷的
            // 同步被永久阻塞，且文案误导。跳过预检、放行到下载阶段：每个文件写入失败会被
            // 逐文件 catch 计为失败并清理临时文件，属于可恢复的优雅降级。
            availableSpace < 0 -> {
                Log.w(TAG, "可用空间未知（卷无法解析），跳过空间预检；下载失败将逐文件降级处理")
                Result.UnknownAvailableSpace
            }
            // cipherLengthFor 异常或求和溢出：降级跳过预检，等同可用空间未知。
            totalDownloadSize < 0 -> {
                Log.w(TAG, "AEAD 密文大小计算异常，跳过空间预检；下载失败将逐文件降级处理")
                Result.CipherSizeComputationFailed
            }
            totalDownloadSize > availableSpace -> Result.Insufficient(totalDownloadSize, availableSpace)
            else -> Result.Ok(totalDownloadSize, availableSpace)
        }
    }

    /**
     * 计算 [actions] 中所有文件 AEAD 密文总大小，供空间预检使用。
     * cipherLengthFor 对非法 fileSize 抛 IllegalArgumentException，此时返回 -1 降级；
     * 求和溢出时也返回 -1 降级（跳过预检，等价于可用空间未知）。
     */
    private fun computeTotalCipherSizePrecheck(actions: List<SyncAction>): Long {
        var total = 0L
        for (action in actions) {
            try {
                val cipherLen = CryptoScheme.cipherLengthFor(action.fileSize)
                val newTotal = total + cipherLen
                if (newTotal < total) { // Long 溢出
                    Log.w(TAG, "computeTotalCipherSize 溢出: total=$total + cipherLen=$cipherLen")
                    return -1L
                }
                total = newTotal
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "cipherLengthFor(${action.fileSize}) 异常, 跳过空间预检", e)
                return -1L
            }
        }
        return total
    }

    companion object {
        // 保留原 SyncEngine.TAG 以维持日志连续性（诊断日志属于开发面，非 i18n 目标）。
        private const val TAG = "SyncEngine"
    }
}
