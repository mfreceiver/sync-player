package cn.vectory.syncplayer.sync

import android.content.Context
import android.util.Log
import cn.vectory.syncplayer.R
import cn.vectory.syncplayer.data.model.SyncAction
import cn.vectory.syncplayer.data.model.SyncOutcome
import cn.vectory.syncplayer.data.model.SyncProgress
import cn.vectory.syncplayer.data.model.SyncProtocol
import cn.vectory.syncplayer.data.model.SyncPhase
import cn.vectory.syncplayer.data.repository.PlaybackRepository
import cn.vectory.syncplayer.data.repository.SyncLogRepository
import cn.vectory.syncplayer.data.repository.SyncRelationRepository
import cn.vectory.syncplayer.data.repository.SyncSourceRepository
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.storage.StoragePaths
import cn.vectory.syncplayer.storage.MediaFileStore
import dagger.Lazy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * DELETE_BOTH 执行域（从 [SyncEngine] 上帝类渐进拆分，plan 12.2.0 Task 5）。
 *
 * 职责：回收站清理流程中「本地媒体 + 缩略图 + DB 行 + 远端文件」四位一体的删除执行，
 * 以及可复用的存储删除原语（[deleteStoreMedia]/[deleteStoreThumb]，Task 7 的下载管线
 * DELETE_LOCAL tail 复用同一实现）。
 *
 * **不持有任何锁**：`executeDeleteLocked` 的双层互斥（`storageOperationMutex` +
 * relation mutex）由 [SyncEngine] 门面持有后调用——一次同步的生命周期编排留在 Engine。
 *
 * 迁移不变量（与原 [SyncEngine] 内联实现逐条等价）：
 *  1. remote-first（BUG-001）：远端 ack 前不动本地——远端失败保留全部本地状态
 *     （行留在回收站标记下，下次清理重推 DELETE_BOTH，防复活循环）；远端成功后本地
 *     失败由下次镜像同步推导 DELETE_LOCAL 自愈。
 *  2. 远端 `deleteFile` 幂等（返回 false 表示「本来就不存在」，按成功语义处理）。
 *  3. CancellationException rethrow 分支保留；finally `disconnect()` 保留。
 *  4. 进度上报走传入的 [progressPublisher]（Engine 构造一次并共享的同一实例）。
 */
internal class SyncDeleteExecutor(
    private val syncRelationRepository: SyncRelationRepository,
    private val syncSourceRepository: SyncSourceRepository,
    private val videoRepository: VideoRepository,
    private val playbackRepository: PlaybackRepository,
    private val syncLogRepository: SyncLogRepository,
    private val remoteFileClientFactory: RemoteFileClientFactory,
    private val storagePaths: StoragePaths,
    private val mediaFileStoreLazy: Lazy<MediaFileStore>,
    private val context: Context,
    private val progressPublisher: SyncProgressPublisher
) {

    private val mediaFileStore: MediaFileStore
        get() = mediaFileStoreLazy.get()

    private fun getClient(protocol: SyncProtocol): RemoteFileClient =
        remoteFileClientFactory.create(protocol)

    /** 迁自原 [SyncEngine.executeDeleteLocked]（无 relation-mutex 壳，互斥由 Engine 持有）。 */
    suspend fun executeDeleteLocked(relationId: Long, actions: List<SyncAction>): Boolean {
        val relation = syncRelationRepository.getById(relationId) ?: return false
        val source = syncSourceRepository.getById(relation.syncSourceId) ?: return false
        val client = getClient(source.protocol)
        // E2: mediaFileStore 是 Hilt Lazy 纯属性读取，无阻塞，无需 withContext(IO)。
        val store = mediaFileStore

        val totalActions = actions.size
        var completedActions = 0

        progressPublisher.value = SyncProgress(
            isRunning = true,
            totalFiles = totalActions,
            phase = SyncPhase.DELETING,
            relationId = relationId,
            relationName = relation.name,
            localBasePath = relation.localBasePath
        )

        try {
            client.connect(source, relation.remotePath)

            var failedActions = 0

            for (action in actions) {
                progressPublisher.value = progressPublisher.value.copy(
                    currentFileName = action.relativePath,
                    completedFiles = completedActions
                )

                val videoItem = videoRepository.getByRelativePathAndRelationId(action.relativePath, relationId)

                // 1) Remote first (BUG-001) — 远端未 ack 前不动任何本地状态。
                //    旧 local-first 在远端失败时已删 DB 行 → 下次 dryRun 见「远端仍在 +
                //    local==null」推导 DOWNLOAD → 回收站复活循环。remote-first 下远端失败
                //    保留行于回收站标记，下次清理重推 DELETE_BOTH，自终止。
                val remoteRelPath = storagePaths.toPathUnderBase(relation.localBasePath, action.relativePath)
                try {
                    client.deleteFile(remoteRelPath)
                    // true = deleted; false (SMB !exists) = already gone → both OK
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failedActions++
                    // 复审红线：不拼 remote path、不传原始 Throwable——操作+错误码+异常类别。
                    Log.w(TAG, SyncSafeLog.failure("DELETE_BOTH remote", e))
                    syncLogRepository.log(
                        action = "DELETE_BOTH",
                        relativePath = action.relativePath,
                        status = "FAILED",
                        dryRun = false,
                        fileSize = action.fileSize,
                        errorMessage = syncErrorMessage(e, context)
                    )
                    continue
                }

                // 2) Local — 远端 ack 后才执行；本地删除失败保留 DB 行，由下次镜像
                //    同步（dryRun 对比远端快照）推导 DELETE_LOCAL 自愈，不自作主张删行。
                if (videoItem != null) {
                    val mediaGone = deleteStoreMedia(store, videoItem.id)
                    val thumbGone = deleteStoreThumb(store, videoItem.id)
                    if (!(mediaGone && thumbGone)) {
                        failedActions++
                        Log.w(TAG, SyncSafeLog.localDeleteIncomplete("DELETE_BOTH", videoItem.id, mediaGone, thumbGone))
                        syncLogRepository.log(
                            action = "DELETE_BOTH",
                            relativePath = action.relativePath,
                            status = "FAILED",
                            dryRun = false,
                            fileSize = action.fileSize,
                            errorMessage = context.getString(
                                R.string.sync_error_local_delete_incomplete, mediaGone, thumbGone
                            )
                        )
                        continue
                    }
                    playbackRepository.deleteByVideoId(videoItem.id)
                    videoRepository.deleteByRelativePathAndRelationId(action.relativePath, relationId)
                }

                syncLogRepository.log(
                    action = "DELETE_BOTH",
                    relativePath = action.relativePath,
                    status = "SUCCESS",
                    dryRun = false,
                    fileSize = action.fileSize
                )
                completedActions++
            }

            val allOk = failedActions == 0
            progressPublisher.value = SyncProgress(
                isRunning = false,
                phase = if (allOk) SyncPhase.COMPLETED else SyncPhase.PARTIAL_FAILED,
                completedFiles = completedActions,
                totalFiles = totalActions,
                failedFiles = failedActions,
                relationId = relationId,
                relationName = relation.name,
                localBasePath = relation.localBasePath
            )

            // F02: 结构化终态入库——部分失败与成功可区分，失败数为展示所需最小计数。
            val outcome = if (allOk) {
                SyncOutcome(SyncOutcome.Status.SUCCESS)
            } else {
                SyncOutcome(SyncOutcome.Status.PARTIAL_FAILED, failedCount = failedActions)
            }
            syncRelationRepository.updateLastSync(relationId, outcome)
            syncLogRepository.clearOldLogs()
            return allOk
        } catch (e: CancellationException) {
            // BUG-008: 终态必须 copy 当前进度（保留 DELETING 相位已发布的计数），
            // 与 SyncDownloadPipeline 取消路径保持一致：置 CANCELLED 终态相位。
            progressPublisher.value = progressPublisher.value.copy(
                isRunning = false,
                phase = SyncPhase.CANCELLED,
                relationId = relationId,
                relationName = relation.name,
                localBasePath = relation.localBasePath
            )
            // F02: 取消收尾是**有限操作数**的 best-effort（单条 UPDATE；NonCancellable
            // 只保证在已取消协程内仍能执行这一条写入）——它不承诺取消等待时间有上界。
            // 无论记录成败，最终传播的都是原始 CancellationException e
            // （绝不吞取消、绝不把取消当成功，收尾点也不得替换原始取消）。
            try {
                withContext(NonCancellable) {
                    syncRelationRepository.updateLastSync(
                        relationId,
                        SyncOutcome(SyncOutcome.Status.CANCELLED)
                    )
                }
            } catch (ce: CancellationException) {
                // 原始异常优先：收尾写入自身再抛的 CE（ce）不得替换/遮蔽原始取消 e。
                throw e
            } catch (_: Exception) {
                // best-effort：终态记录失败不改变取消语义
            }
            throw e
        } catch (e: Exception) {
            // BUG-008: 终态必须 copy 当前进度，保留 DELETING 相位已发布的计数。
            progressPublisher.value = progressPublisher.value.copy(
                isRunning = false,
                phase = SyncPhase.FAILED,
                relationId = relationId,
                relationName = relation.name,
                localBasePath = relation.localBasePath
            )
            // F02: 结构化终态入库（FAILED + 错误码原样持久化），不再写 syncErrorMessage 文案。
            syncRelationRepository.updateLastSync(
                relationId,
                SyncOutcome(
                    SyncOutcome.Status.FAILED,
                    errorCode = (e as? SyncException)?.code?.name ?: SyncErrorCode.UNKNOWN.name
                )
            )
            syncLogRepository.log(
                action = "DELETE_BOTH",
                relativePath = "",
                status = "FAILED",
                dryRun = false,
                errorMessage = syncErrorMessage(e, context)
            )
            return false
        } finally {
            client.disconnect()
        }
    }

    /**
     * Delete media; [Boolean] false is ambiguous (missing vs fail) — re-check [MediaFileStore.exists].
     * @return true if media is gone (deleted or never present); false if still present after failed delete.
     */
    suspend fun deleteStoreMedia(store: MediaFileStore, id: Long): Boolean {
        return try {
            val deleted = store.delete(id)
            if (deleted) true
            else !store.exists(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 复审红线：只留行 id + 异常类别，不传原始 Throwable。
            Log.w(TAG, SyncSafeLog.cleanupFailed("deleteStoreMedia", id, e))
            try {
                !store.exists(id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * Delete thumb; missing thumb is success. Still-present after false → failure.
     */
    suspend fun deleteStoreThumb(store: MediaFileStore, id: Long): Boolean {
        return try {
            val deleted = store.deleteThumb(id)
            if (deleted) true
            else !store.thumbExists(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 复审红线：只留行 id + 异常类别，不传原始 Throwable。
            Log.w(TAG, SyncSafeLog.cleanupFailed("deleteStoreThumb", id, e))
            try {
                !store.thumbExists(id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
        }
    }

    companion object {
        private const val TAG = "SyncEngine"
    }
}
