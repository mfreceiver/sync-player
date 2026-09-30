package cn.vectory.syncplayer.sync

import android.content.Context
import cn.vectory.syncplayer.R
import cn.vectory.syncplayer.data.model.SyncAction
import cn.vectory.syncplayer.data.model.SyncActionType
import cn.vectory.syncplayer.data.model.SyncProgress
import cn.vectory.syncplayer.data.model.SyncProtocol
import cn.vectory.syncplayer.data.model.SyncPhase
import cn.vectory.syncplayer.data.local.entity.SyncLogEntity
import cn.vectory.syncplayer.data.repository.PlaybackRepository
import cn.vectory.syncplayer.data.repository.SyncLogRepository
import cn.vectory.syncplayer.data.repository.SyncRelationRepository
import cn.vectory.syncplayer.data.repository.SyncSourceRepository
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.debug.DebugLogger
import cn.vectory.syncplayer.storage.MediaFinalProbe
import cn.vectory.syncplayer.storage.MediaProbeState
import cn.vectory.syncplayer.storage.StoragePaths
import kotlinx.coroutines.CancellationException

/**
 * 远端扫描域（从 [SyncEngine] 上帝类渐进拆分，plan 12.2.0 Task 6）。
 *
 * 职责：
 *  - `dryRunLocked`：全量对比远端/本地索引，产出 DOWNLOAD/DELETE_LOCAL 计划（原 `SyncEngine.dryRunLocked`）；
 *  - `dryRunDeleteLocked`：回收站清理的 DELETE_BOTH 计划扫描（原 `SyncEngine.dryRunDelete`）。
 *
 * **不持有任何锁**：双层互斥（`storageOperationMutex` + relation mutex，dryRunDelete 仅
 * relation mutex——原本无 storageMutex，保持）由 [SyncEngine] 门面持有后调用。
 *
 * 迁移不变量（与原 [SyncEngine] 内联实现逐字符等价）：
 *  1. **E8（红线 1）**：扫描异常 → toDownload/toDelete 清空 + FAILED 相位 + `scanError`
 *     随 DryRunResult 上报——绝不让残缺远端快照派生 DELETE_LOCAL。
 *  2. `RemoteScanIncompleteException` 上限失败语义（listFiles 要么完整要么抛，静默截断禁止）
 *     经同一 catch 路径保持。
 *  3. finally `client.disconnect()`。
 *  4. progress 上报走传入的 [progressPublisher]（Engine 构造一次并共享的同一实例）。
 */
internal class SyncRemoteScanner(
    private val syncRelationRepository: SyncRelationRepository,
    private val syncSourceRepository: SyncSourceRepository,
    private val videoRepository: VideoRepository,
    private val playbackRepository: PlaybackRepository,
    private val syncLogRepository: SyncLogRepository,
    private val remoteFileClientFactory: RemoteFileClientFactory,
    private val storagePaths: StoragePaths,
    private val context: Context,
    private val progressPublisher: SyncProgressPublisher,
    /**
     * A8 起为**必填**依赖契约（不留 null 遗留形态）：任何已有本地行在进入
     * SKIP/下载覆盖决策前必须先过本地 final 完整性探测。生产接线为
     * `MediaFinalProbe { id, size -> mediaFileStoreLazy.get().probeFinalized(id, size) }`。
     * 契约：IO_ERROR ⇒ 整次扫描 fail-closed（清空两计划 + FAILED，绝不伪装
     * NoChanges，也绝不带着未知本地状态进入覆盖写）。
     */
    private val localFinalProbe: MediaFinalProbe
) {

    private fun getClient(protocol: SyncProtocol): RemoteFileClient = remoteFileClientFactory.create(protocol)

    /**
     * P1-1: 把任意 Throwable 归一化为 [SyncException]。
     * - 已经是 [SyncException]：原样返回（保留原始 code/operation/path 等分类）。
     * - 其它：包成 [SyncErrorCode.UNKNOWN] + [SyncOperation.SCAN]（dryRun 的 catch 上下文是扫描阶段）。
     * 调用方据此走 [syncErrorMessage] 解析展示文案，而非一律「连接失败」。
     */
    private fun wrapSyncException(e: Throwable): SyncException =
        if (e is SyncException) e
        else SyncException(SyncErrorCode.UNKNOWN, SyncOperation.SCAN, cause = e)

    /** 迁自原 [SyncEngine.dryRunLocked]（无 relation-mutex 壳，互斥由 Engine 持有）。 */
    suspend fun dryRunLocked(relationId: Long): DryRunResult {
        DebugLogger.i(TAG, "dryRun 开始: relationId=$relationId")
        val relation = syncRelationRepository.getById(relationId) ?: return DryRunResult(emptyList(), emptyList())
        val source = syncSourceRepository.getById(relation.syncSourceId) ?: return DryRunResult(emptyList(), emptyList())
        val client = getClient(source.protocol)

        progressPublisher.value = SyncProgress(phase = SyncPhase.SCANNING, relationId = relationId, relationName = relation.name, localBasePath = relation.localBasePath)

        val toDownload = mutableListOf<SyncAction>()
        val toDelete = mutableListOf<SyncAction>()
        // P1-1: 扫描异常时记录归一化后的根因，随 DryRunResult 带回调用方。
        var scanError: SyncException? = null

        // P2 扫描日志批量缓冲：行内容/顺序与原逐条 insert 完全一致（同一
        // SyncLogEntity 形状，timestamp 在入缓冲时打点），仅传输批量化。
        // 满 [LOG_BATCH_SIZE] 或阶段边界（CONFIRMING 前）经 insertAll 落库；
        // 取消/异常路径未 flush 的缓冲行直接丢弃（不做 NonCancellable 大
        // flush、不重试不确定提交——审计行靠 SCAN FAILED 汇总行兜底）。
        val logBuffer = ArrayDeque<SyncLogEntity>()

        suspend fun flushScanLogs() {
            if (logBuffer.isEmpty()) return
            val batch = logBuffer.toList()
            logBuffer.clear()
            syncLogRepository.logAll(batch)
        }

        fun bufferScanLog(action: String, relativePath: String, status: String, fileSize: Long?) {
            logBuffer.addLast(SyncLogEntity(
                dryRun = true,
                action = action,
                relativePath = relativePath,
                fileSize = fileSize,
                status = status
            ))
        }

        try {
            client.connect(source, relation.remotePath)
            val remoteFiles = client.listFiles(relation.remotePath)
            val localVideos = videoRepository.getBySyncRelationId(relationId)
            val localMap = localVideos.associateBy { it.relativePath }

            for (remoteFile in remoteFiles) {
                if (remoteFile.isDirectory) continue
                val remoteRelPath = remoteFile.path
                val localRelPath = storagePaths.toLocalRelativePath(relation.localBasePath, remoteRelPath)
                val local = localMap[localRelPath]

                val actionType = if (local != null) {
                    // E6: SMB/SFTP 协议报告的 mtime 精度为秒，本地以毫秒存储，
                    // 直接比较会出现亚秒差异导致的「假不等」→ 反复重下。统一截断到秒比较。
                    // A7: null DB mtime ≠ 有效 epoch0——不再 `?: 0L` 造假相等，陈旧行必须修复。
                    val remoteMtimeSec = remoteFile.lastModified / 1000L
                    val localMtimeSec = local.remoteModifiedTime?.let { it / 1000L }
                    val metadataDiffers =
                        remoteFile.size != local.fileSize || remoteMtimeSec != localMtimeSec

                    // A8 覆盖范围（安全契约）：已有本地行无论将被 SKIP 还是被下载覆盖，
                    // 都必须先探测本地 final——IO_ERROR 一律 fail-closed 抛入 E8 catch
                    // （清空两计划 + FAILED），绝不绕过未知本地状态。CE 由探针挂起调用
                    // 自然传播（CE-first catch）。探测为本地单次属性读取（预览只读，
                    // 不写 DB/文件，不新增远端交互）。
                    when (localFinalProbe.probe(local.id, local.fileSize)) {
                        MediaProbeState.IO_ERROR -> throw SyncException(
                            SyncErrorCode.STORAGE_ERROR,
                            SyncOperation.SCAN
                        )
                        // final 确实完好：仅当元数据也相等才 SKIP，否则覆盖重下。
                        MediaProbeState.VALID -> if (metadataDiffers) SyncActionType.DOWNLOAD else null
                        // final 缺失/截断：DOWNLOAD 本身就是同 id 修复候选。
                        MediaProbeState.MISSING, MediaProbeState.SIZE_MISMATCH -> SyncActionType.DOWNLOAD
                    }
                } else {
                    SyncActionType.DOWNLOAD
                }

                when (actionType) {
                    SyncActionType.DOWNLOAD -> {
                        val action = SyncAction(
                            type = SyncActionType.DOWNLOAD,
                            relativePath = remoteRelPath,
                            fileSize = remoteFile.size,
                            remoteModifiedTime = remoteFile.lastModified
                        )
                        toDownload.add(action)
                        bufferScanLog(
                            action = "DOWNLOAD",
                            relativePath = localRelPath,
                            status = "PENDING",
                            fileSize = remoteFile.size
                        )
                        if (logBuffer.size >= LOG_BATCH_SIZE) flushScanLogs()
                    }
                    null -> {
                        bufferScanLog(
                            action = "SKIP",
                            relativePath = localRelPath,
                            status = "SKIPPED",
                            fileSize = remoteFile.size
                        )
                        if (logBuffer.size >= LOG_BATCH_SIZE) flushScanLogs()
                    }
                    else -> {}
                }
            }

            val remoteLocalPaths = remoteFiles.filter { !it.isDirectory }.map {
                storagePaths.toLocalRelativePath(relation.localBasePath, it.path)
            }.toSet()
            for (localVideo in localVideos) {
                if (localVideo.relativePath !in remoteLocalPaths) {
                    val action = SyncAction(
                        type = SyncActionType.DELETE_LOCAL,
                        relativePath = localVideo.relativePath,
                        fileSize = localVideo.fileSize
                    )
                    toDelete.add(action)
                    bufferScanLog(
                        action = "DELETE_LOCAL",
                        relativePath = localVideo.relativePath,
                        status = "PENDING",
                        fileSize = localVideo.fileSize
                    )
                    if (logBuffer.size >= LOG_BATCH_SIZE) flushScanLogs()
                }
            }

            // P2: CONFIRMING 边界——先把缓冲整批落库成功，再进入确认相位。
            // flush 抛异常 ⇒ 走下方既有 E8 失败路径（清空两计划 + SCAN FAILED
            // 汇总行），审计语义不弱化：已 flush 批次保留，未 flush 行丢弃。
            flushScanLogs()

            progressPublisher.value = SyncProgress(phase = SyncPhase.CONFIRMING, relationId = relationId, relationName = relation.name, localBasePath = relation.localBasePath)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // E8: 扫描失败必须保守——清空 toDownload 与 toDelete 后返回，
            // 绝不让残缺远端快照派生出 DELETE_LOCAL（会真删本地密文 + DB 行，不可恢复）。
            // 这里覆盖三种「扫描不完整」来源：递归列目录异常、达到 MAX_REMOTE_FILES 上限、
            // 单条目属性异常（详见 RemoteScanIncompleteException 与两个 listFilesRecursive）。
            toDownload.clear()
            toDelete.clear()
            progressPublisher.value = SyncProgress(phase = SyncPhase.FAILED, relationId = relationId, relationName = relation.name, localBasePath = relation.localBasePath)
            syncLogRepository.log(
                action = "SCAN",
                relativePath = "",
                status = "FAILED",
                dryRun = true,
                errorMessage = syncErrorMessage(e, context)
            )
            // P1-1: catch 的清空 / phase=FAILED / 日志记录保持原样（E8 guard 不变），
            // 仅额外把根因带出，让 ViewModel 能显示真实错误而非固定「连接失败」。
            scanError = wrapSyncException(e)
        } finally {
            client.disconnect()
        }

        // E8: 到达这里的唯一「非空」路径是 try 块完整跑完（扫描成功）；
        // 任何扫描异常都已在 catch 中清空两个列表，故此处不可能泄漏残缺 toDelete。
        if (scanError == null) {
            DebugLogger.i(TAG, "dryRun 完成: ${toDownload.size} 个待下载, ${toDelete.size} 个待删除")
        } else {
            // F01 安全日志边界：不把原始 Throwable 交给 DebugLogger（它会把
            // stackTraceToString 写入 logcat 与应用内环形缓冲，DebugLogScreen 可见，
            // cause 可能含远端路径/主机名）。与 SyncDownloadPipeline 相同的安全摘要：
            // 保留操作类别与错误分类（错误码 + 异常类别），不含底层 message/堆栈。
            DebugLogger.e(TAG, SyncSafeLog.failure("SCAN", scanError))
        }
        return DryRunResult(toDownload, toDelete, scanError)
    }

    /** 迁自原 [SyncEngine.dryRunDelete]（无 relation-mutex 壳，互斥由 Engine 持有）。 */
    suspend fun dryRunDeleteLocked(relationId: Long): Pair<List<SyncAction>, String?> {
        val relation = syncRelationRepository.getById(relationId) ?: return Pair(emptyList(), context.getString(R.string.sync_relation_not_found))
        val source = syncSourceRepository.getById(relation.syncSourceId) ?: return Pair(emptyList(), context.getString(R.string.sync_source_not_found))
        val client = getClient(source.protocol)
        progressPublisher.value = SyncProgress(phase = SyncPhase.SCANNING, relationId = relationId, relationName = relation.name, localBasePath = relation.localBasePath)

        val toDelete = mutableListOf<SyncAction>()

        try {
            client.connect(source, relation.remotePath)
            val remoteFiles = client.listFiles(relation.remotePath)
            val remoteMap = remoteFiles.filter { !it.isDirectory }.associateBy { it.path }

            val trashed = playbackRepository.getTrashed()
            val localVideos = videoRepository.getBySyncRelationId(relationId)
            for (record in trashed) {
                val videoItem = localVideos.find { it.id == record.videoItemId } ?: continue
                // DB 是真相源，不再扫描物理文件
                val remoteRelPath = storagePaths.toPathUnderBase(relation.localBasePath, videoItem.relativePath)
                val remoteFile = remoteMap[remoteRelPath]
                if (remoteFile != null) {
                    val action = SyncAction(
                        type = SyncActionType.DELETE_BOTH,
                        relativePath = videoItem.relativePath,
                        fileSize = videoItem.fileSize
                    )
                    toDelete.add(action)
                }
            }

            progressPublisher.value = SyncProgress(phase = SyncPhase.CONFIRMING, relationId = relationId, relationName = relation.name, localBasePath = relation.localBasePath)
            return Pair(toDelete, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            progressPublisher.value = SyncProgress(phase = SyncPhase.FAILED, relationId = relationId, relationName = relation.name, localBasePath = relation.localBasePath)
            return Pair(emptyList(), syncErrorMessage(e, context))
        } finally {
            client.disconnect()
        }
    }

    companion object {
        private const val TAG = "SyncEngine"
        /** P2: 扫描日志批量落库阈值（满 128 行或 CONFIRMING 边界 flush）。 */
        private const val LOG_BATCH_SIZE = 128
    }
}
