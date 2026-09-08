package cn.vectory.syncplayer.sync

import android.content.Context
import cn.vectory.syncplayer.R
import cn.vectory.syncplayer.data.model.SyncAction
import cn.vectory.syncplayer.data.model.SyncActionType
import cn.vectory.syncplayer.data.model.SyncProgress
import cn.vectory.syncplayer.data.model.SyncProtocol
import cn.vectory.syncplayer.data.model.SyncPhase
import cn.vectory.syncplayer.data.repository.PlaybackRepository
import cn.vectory.syncplayer.data.repository.SyncLogRepository
import cn.vectory.syncplayer.data.repository.SyncRelationRepository
import cn.vectory.syncplayer.data.repository.SyncSourceRepository
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.debug.DebugLogger
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
    private val progressPublisher: SyncProgressPublisher
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
                    val remoteMtimeSec = remoteFile.lastModified / 1000L
                    val localMtimeSec = (local.remoteModifiedTime ?: 0L) / 1000L
                    if (remoteFile.size != local.fileSize || remoteMtimeSec != localMtimeSec) {
                        SyncActionType.DOWNLOAD
                    } else {
                        null
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
                        syncLogRepository.log(
                            action = "DOWNLOAD",
                            relativePath = localRelPath,
                            status = "PENDING",
                            dryRun = true,
                            fileSize = remoteFile.size
                        )
                    }
                    null -> {
                        syncLogRepository.log(
                            action = "SKIP",
                            relativePath = localRelPath,
                            status = "SKIPPED",
                            dryRun = true,
                            fileSize = remoteFile.size
                        )
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
                    syncLogRepository.log(
                        action = "DELETE_LOCAL",
                        relativePath = localVideo.relativePath,
                        status = "PENDING",
                        dryRun = true,
                        fileSize = localVideo.fileSize
                    )
                }
            }

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
    }
}
