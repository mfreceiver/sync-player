package cn.vectory.syncplayer.sync

import android.content.Context
import android.util.Log
import cn.vectory.syncplayer.R
import cn.vectory.syncplayer.crypto.CryptoScheme
import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import cn.vectory.syncplayer.data.model.SyncAction
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
import cn.vectory.syncplayer.storage.volume.EncryptedMediaStore
import cn.vectory.syncplayer.ui.util.extractParentPath
import cn.vectory.syncplayer.ui.util.isImageFile
import dagger.Lazy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 下载主管线（DOWNLOAD/更新 + DELETE_LOCAL tail，从 [SyncEngine] 上帝类渐进拆分，
 * plan 12.2.0 Task 7）。
 *
 * 职责：原 `SyncEngine.executeSyncLocked` 的完整执行体——空间预检副作用分支、
 * 并发下载（AND-010 通道级客户端复用：W = [MAX_CONCURRENCY] 个长驻 lane 各持一个 client
 * 从 Channel 消费 action；E5 重连重试 / E9 逐文件空间复检 / AEAD v2 加密 sink /
 * 可读性探针回滚 / NonCancellable 取消清理）与 DELETING 阶段的 DELETE_LOCAL tail。
 *
 * **不持有任何锁**：双层互斥（`storageOperationMutex` + relation mutex）由 [SyncEngine]
 * 门面持有后调用——一次同步的生命周期编排（pending mark/finalize/NonCancellable 清理）
 * 留在 Engine。
 *
 * 迁移不变量（与原 [SyncEngine] 内联实现逐字符等价）：
 *  - 重试白名单 [RETRYABLE_CODES]（P1-2）与 E5 重试前重连；
 *  - 卷不匹配的 DELETE_LOCAL 计 failedDeleteCount（红线 2：不删不属于当前卷的密文）；
 *  - 无 DB 行的 DELETE_LOCAL 计成功（本地已不存在即已删除）；
 *  - CancellationException 先传播（worker 内 NonCancellable 清理 + workerScope.cancel）；
 *  - dry-run 语义不变（本类只执行，计划由 [SyncRemoteScanner] 产出）；
 *  - 空间检查（E10 AEAD 密文大小）走 [SyncSpaceChecker]；
 *  - 进度/计数上报走传入的 [progressPublisher]（Engine 构造一次并共享的同一实例）；
 *  - 存储删除原语复用 Task 5 的 [SyncDeleteExecutor]。
 */
internal class SyncDownloadPipeline(
    private val syncRelationRepository: SyncRelationRepository,
    private val syncSourceRepository: SyncSourceRepository,
    private val videoRepository: VideoRepository,
    private val playbackRepository: PlaybackRepository,
    private val syncLogRepository: SyncLogRepository,
    private val videoMetadataExtractor: VideoMetadataExtractor,
    private val imageMetadataExtractor: ImageMetadataExtractor,
    private val remoteFileClientFactory: RemoteFileClientFactory,
    private val storagePaths: StoragePaths,
    private val encryptedMediaStoreLazy: Lazy<EncryptedMediaStore>,
    private val context: Context,
    private val progressPublisher: SyncProgressPublisher,
    private val deleteExecutor: SyncDeleteExecutor
) {

    private val encryptedMediaStore: EncryptedMediaStore
        get() = encryptedMediaStoreLazy.get()

    private val spaceChecker = SyncSpaceChecker()

    private fun getClient(protocol: SyncProtocol): RemoteFileClient = remoteFileClientFactory.create(protocol)

    private suspend fun extractMetadata(entity: VideoItemEntity) {
        if (isImageFile(entity.fileName)) {
            imageMetadataExtractor.extractAndSave(entity)
        } else {
            videoMetadataExtractor.extractAndSave(entity)
        }
    }

    /**
     * P1-2: 单文件下载重试策略。
     * - [SyncException] 且 [SyncException.code] 在 [RETRYABLE_CODES] 白名单内 → 可重试。
     * - [SyncException] 但 code 为终态（AUTH/ACCESS/PATH_NOT_FOUND/SPACE/FINALIZE/SCAN/...）→ 不重试。
     * - 非 [SyncException]（裸 IOException 等）→ 保守可重试（未知传输失败）。
     */
    private fun isRetryableSyncException(e: Throwable): Boolean {
        val code = (e as? SyncException)?.code ?: return true
        return code in RETRYABLE_CODES
    }

    /** 迁自原 [SyncEngine.executeSyncLocked]（无 relation-mutex 壳，互斥由 Engine 持有）。 */
    suspend fun executeSyncLocked(
        relationId: Long,
        toDownload: List<SyncAction>,
        toDelete: List<SyncAction>,
        relationName: String = "",
        localBasePath: String = ""
    ): Boolean {
        DebugLogger.i(TAG, "同步开始: relationId=$relationId, 待下载=${toDownload.size}, 待删除=${toDelete.size}")
        val relation = syncRelationRepository.getById(relationId) ?: return false
        val source = syncSourceRepository.getById(relation.syncSourceId) ?: return false
        val maxConcurrency = MAX_CONCURRENCY
        val name = relationName.ifEmpty { relation.name }
        val basePath = localBasePath.ifEmpty { relation.localBasePath }
        // E2: encryptedMediaStore 是 Hilt Lazy 的纯属性读取，无阻塞，无需 withContext(IO)。
        val store = encryptedMediaStore
        DebugLogger.i(TAG, "当前卷: <configured>")

        val toActuallyDownload = toDownload
        // E10: 空间预检使用 AEAD 密文大小而非明文大小。预检逻辑（cipherLengthFor 调用、
        // 溢出降级、unknown-space 跳过语义、诊断日志）已迁入 SyncSpaceChecker；此处只处理
        // 「空间不足」这一需要 repository 副作用的判定分支，其余（Ok/Unknown/CipherFailed）
        // 直接放行到逐文件下载降级处理——与原内联实现完全一致。
        val spaceCheck = spaceChecker.checkSpace(toActuallyDownload, store)
        if (spaceCheck is SyncSpaceChecker.Result.Insufficient) {
            DebugLogger.e(TAG, "空间不足，中止同步")
            syncRelationRepository.updateLastSync(
                relationId,
                context.getString(
                    R.string.sync_disk_space_insufficient,
                    formatSize(spaceCheck.needed),
                    formatSize(spaceCheck.available)
                )
            )
            progressPublisher.value = SyncProgress(isRunning = false, phase = SyncPhase.FAILED, relationId = relationId, relationName = name, localBasePath = basePath)
            return false
        }

        val totalActions = toDownload.size + toDelete.size
        val completedCount = AtomicInteger(0)
        val failedDownloadCount = AtomicInteger(0)
        val failedDeleteCount = AtomicInteger(0)
        val totalBytesTransferred = AtomicLong(0L)
        val totalDownloadBytes = toDownload.sumOf { it.fileSize }
        // activeDownloadsMap / completedPathsSet / 保护它们的 mutex 已迁入 SyncProgressPublisher。
        // 它们原是本函数的局部变量（天然每轮新建），迁移为 publisher 实例状态后，必须每轮显式清空。
        progressPublisher.beginSyncRun()

        progressPublisher.value = SyncProgress(
            isRunning = true,
            totalFiles = totalActions,
            phase = SyncPhase.DOWNLOADING,
            relationId = relationId,
            relationName = name,
            localBasePath = basePath,
            totalBytes = totalDownloadBytes,
            startTimeMs = System.currentTimeMillis()
        )

        try {
            if (toDownload.isNotEmpty()) {

                if (toActuallyDownload.isNotEmpty()) {
                    // AND-010（通道级客户端复用）：W = maxConcurrency 个长驻 lane 各持一个 client，
                    // 从无界 Channel 消费 action。旧实现每个 action 新建 client（create+connect+close），
                    // SFTP 是完整 SSH 握手、SMB 是会话协商，大库同步时连接建立成本与传输本身同量级。
                    // lane 内 client 跨 action 复用；E5 重试内的 disconnect+connect 重连语义不变；
                    // 某 action 失败后该 lane 丢弃 client（可能已损坏），下一个 action 重新建连。
                    // 并发度由 lane 数量约束（原 Semaphore 不再需要）。
                    val actionChannel = Channel<SyncAction>(Channel.UNLIMITED)
                    coroutineScope {
                        val workerScope = this
                        launch {
                            for (action in toActuallyDownload) actionChannel.send(action)
                            actionChannel.close()
                        }
                        repeat(maxConcurrency) {
                            launch(Dispatchers.IO) {
                                var laneClient: RemoteFileClient? = null
                                try {
                                    for (action in actionChannel) {
                                        var client = laneClient
                                        try {
                                            val localRelPath = storagePaths.toLocalRelativePath(relation.localBasePath, action.relativePath)
                                            val fileName = action.relativePath.substringAfterLast("/")
                                            DebugLogger.d(TAG, "下载: $fileName (${action.fileSize} bytes)")

                                            if (client == null) {
                                                client = getClient(source.protocol)
                                                client.connect(source, relation.remotePath)
                                                laneClient = client
                                            }

                                            progressPublisher.beginDownload(
                                                relativePath = action.relativePath,
                                                fileName = fileName,
                                                totalBytes = action.fileSize,
                                                transferredSoFar = totalBytesTransferred.get(),
                                                totalDownloadBytes = totalDownloadBytes,
                                                completedFiles = completedCount.get(),
                                                totalFiles = totalActions
                                            )

                                            val existing = videoRepository.getByRelativePathAndRelationId(localRelPath, relationId)
                                            var downloadSuccess = false
                                            var attempts = 0
                                            var assignedId = existing?.id ?: 0L
                                            var lastIv = ByteArray(0)

                                            while (attempts < 3 && !downloadSuccess) {
                                                attempts++
                                                if (attempts > 1) {
                                                    // E5: 重试前重连——上次失败的下载可能已损坏底层传输（SMB session/SFTP channel），
                                                    // 复用旧 client 会再次失败。先断开再建新连接。
                                                    try { client.disconnect() } catch (e: CancellationException) {
                                                        throw e
                                                    } catch (_: Exception) {}
                                                    client.connect(source, relation.remotePath)
                                                }
                                                // E9: 每个文件下载前复检可用空间（3 并发下载途中磁盘可能被耗尽）。
                                                // 不足时直接抛出，由外层 catch 计 failedDownloadCount 并跳到下一个文件，
                                                // 不进入 3 次重试（空间不足不会在几秒内自愈，重试无意义）。
                                                // spaceNow < 0 表示未知（见上方预检注释），不可当作不足。
                                                // E10: 使用 AEAD 密文大小而非 action.fileSize。
                                                val requiredBytes = try {
                                                    CryptoScheme.cipherLengthFor(action.fileSize)
                                                } catch (e: IllegalArgumentException) {
                                                    // cipherLengthFor 抛异常 = fileSize 非法，无法计算密文大小，直接判失败
                                                    throw SyncException(
                                                        SyncErrorCode.INVALID_FILE_SIZE,
                                                        SyncOperation.DOWNLOAD,
                                                        path = action.relativePath,
                                                        cause = e
                                                    )
                                                }
                                                val spaceNow = store.usableSpace()
                                                if (spaceNow in 0 until requiredBytes) {
                                                    throw SyncException(
                                                        SyncErrorCode.INSUFFICIENT_SPACE,
                                                        SyncOperation.DOWNLOAD,
                                                        path = action.relativePath,
                                                        expectedBytes = requiredBytes,
                                                        availableBytes = spaceNow
                                                    )
                                                }
                                                val iv = CryptoScheme.newIv()
                                                lastIv = iv
                                                // 占位行仅在 existing==null 首次创建（最小元数据：fileSize=0、remoteModifiedTime=null），
                                                // 进程崩溃后 dryRun 必然重下（size/mtime 不匹配远端）。
                                                // existing 复用 id，下载前不改动 DB，失败时旧记录与旧密文保持一致（避免数据损坏）。
                                                if (assignedId == 0L) {
                                                    assignedId = videoRepository.upsert(VideoItemEntity(
                                                        syncRelationId = relationId,
                                                        relativePath = localRelPath,
                                                        parentPath = extractParentPath(localRelPath),
                                                        fileName = fileName,
                                                        fileSize = 0,
                                                        remoteModifiedTime = null,
                                                        dateSynced = System.currentTimeMillis(),
                                                        volumeId = store.volumeId,
                                                        iv = iv
                                                    ))
                                                }
                                                // AEAD v2 契约（跨 lane）：明文长度 = 远端源文件大小 = action.fileSize，
                                                // header 需要它来写 plaintextLength 字段。H2 在 EncryptedMediaStore 新增三参重载。
                                                var sink: java.io.OutputStream? = null
                                                try {
                                                    sink = store.openEncryptSink(assignedId, iv, action.fileSize)
                                                    downloadSuccess = client.downloadTo(action.relativePath, sink) { transferred, total ->
                                                        // onProgress 是普通（非 suspend）lambda，沿用 publisher 内的 tryLock/unlock 节流写入。
                                                        progressPublisher.reportDownloadProgress(
                                                            relativePath = action.relativePath,
                                                            fileName = fileName,
                                                            transferred = transferred,
                                                            total = total,
                                                            fileTotalBytes = action.fileSize,
                                                            transferredSoFar = totalBytesTransferred.get(),
                                                            totalDownloadBytes = totalDownloadBytes,
                                                            completedFiles = completedCount.get(),
                                                            totalFiles = totalActions
                                                        )
                                                    }
                                                    sink.close()
                                                    if (!downloadSuccess) throw SyncException(
                                                        SyncErrorCode.DOWNLOAD_FAILED,
                                                        SyncOperation.DOWNLOAD,
                                                        path = action.relativePath
                                                    )
                                                    if (!store.finalize(assignedId)) throw SyncException(
                                                        SyncErrorCode.FINALIZE_FAILED,
                                                        SyncOperation.DOWNLOAD,
                                                        path = action.relativePath
                                                    )
                                                    // 可读性探针（Bug 2 修复）：finalize 成功后立即验证文件能 seek + read + SPM2 magic。
                                                    // SAF 不支持 seek / 任何读取失败 → 回滚 finalize（删 .enc + 删 DB 行）+ 抛 STORAGE_ERROR。
                                                    // 不在重试白名单 → 立即抛到 outer catch 计 failedDownloadCount；下次 dryRun 因 DB 行已删重下。
                                                    try {
                                                        store.verifyReadable(assignedId)
                                                    } catch (e: CancellationException) {
                                                        throw e
                                                    } catch (e: Exception) {
                                                        try {
                                                            store.delete(assignedId)
                                                        } catch (e: CancellationException) {
                                                            throw e
                                                        } catch (_: Exception) {
                                                        }
                                                        videoRepository.deleteById(assignedId)
                                                        throw SyncException(SyncErrorCode.STORAGE_ERROR, SyncOperation.DOWNLOAD, path = action.relativePath, cause = e)
                                                    }
                                                } catch (e: CancellationException) {
                                                    try { sink?.close() } catch (_: Exception) {}
                                                    withContext(NonCancellable) {
                                                        try { store.cancel(assignedId) } catch (_: Exception) {}
                                                        if (existing == null) {
                                                            try { videoRepository.deleteById(assignedId) } catch (_: Exception) {}
                                                        }
                                                    }
                                                    throw e
                                                } catch (e: Exception) {
                                                    try { sink?.close() } catch (_: Exception) {}
                                                    store.cancel(assignedId)
                                                    if (existing == null) videoRepository.deleteById(assignedId)
                                                    // P1-2: 终态错误（AUTH/ACCESS/PATH_NOT_FOUND/SPACE/FINALIZE/...）
                                                    // 不会在几秒内自愈，重试纯属浪费延迟并放大认证/下载压力，立即抛出，
                                                    // 由外层 catch 计一次 failedDownloadCount。仅瞬态错误继续重试。
                                                    if (!isRetryableSyncException(e)) throw e
                                                    if (attempts >= 3) throw e
                                                    DebugLogger.w(TAG, "重试 ($attempts/3): ${action.relativePath.substringAfterLast("/")} - ${e.javaClass.simpleName}")
                                                    delay(1000L * attempts)
                                                }
                                            }

                                            progressPublisher.removeActiveDownload(action.relativePath)

                                            totalBytesTransferred.addAndGet(action.fileSize)
                                            // finalize 成功后才写入最终元数据（iv/fileSize/remoteModifiedTime），
                                            // 保证 DB 与磁盘密文一致；此前 placeholder/existing 不持有误导性 final 值。
                                            val finalized = videoRepository.getById(assignedId)
                                            if (finalized != null) {
                                                val withMeta = finalized.copy(
                                                    iv = lastIv,
                                                    volumeId = store.volumeId,
                                                    fileSize = action.fileSize,
                                                    remoteModifiedTime = action.remoteModifiedTime,
                                                    dateSynced = System.currentTimeMillis()
                                                )
                                                videoRepository.upsert(withMeta)
                                                try {
                                                    extractMetadata(withMeta)
                                                } catch (e: CancellationException) {
                                                    throw e
                                                } catch (e: Exception) {
                                                    Log.w(TAG, "Metadata extraction failed for ${withMeta.fileName}", e)
                                                }
                                            }
                                            syncLogRepository.log(
                                                action = "DOWNLOAD",
                                                relativePath = localRelPath,
                                                status = "SUCCESS",
                                                dryRun = false,
                                                fileSize = action.fileSize
                                            )
                                            DebugLogger.d(TAG, "完成: $fileName")
                                            completedCount.incrementAndGet()
                                            progressPublisher.completeDownload(
                                                relativePath = action.relativePath,
                                                transferredSoFar = totalBytesTransferred.get(),
                                                totalDownloadBytes = totalDownloadBytes,
                                                completedFiles = completedCount.get(),
                                                totalFiles = totalActions
                                            )
                                        } catch (e: CancellationException) {
                                            workerScope.cancel(e)
                                            throw e
                                        } catch (e: Exception) {
                                            val localRelPath = storagePaths.toLocalRelativePath(relation.localBasePath, action.relativePath)
                                            progressPublisher.removeActiveDownload(action.relativePath)
                                            failedDownloadCount.incrementAndGet()
                                            // E3: 失败不再 incrementAndGet completedCount——completedFiles 只表示成功数。
                                            // Download 与 Delete 的失败计数器已分离，避免 successDownloadCount 被删除失败侵蚀。
                                            syncLogRepository.log(
                                                action = "DOWNLOAD",
                                                relativePath = localRelPath,
                                                status = "FAILED",
                                                dryRun = false,
                                                fileSize = action.fileSize,
                                                errorMessage = syncErrorMessage(e, context)
                                            )
                                            Log.e(TAG, "Download failed code=${(e as? SyncException)?.code} path=${action.relativePath}", e)
                                            DebugLogger.w(TAG, "失败: ${action.relativePath.substringAfterLast("/")} - ${e.javaClass.simpleName}")
                                            // AND-010: 失败动作可能已损坏 lane 的底层传输，丢弃该 client，
                                            // 下一个 action 在本 lane 重新 create+connect（保留旧「每 action 新连接」
                                            // 的故障隔离性，同时全成功路径仍复用连接）。
                                            try { client?.disconnect() } catch (ce: CancellationException) {
                                                throw ce
                                            } catch (_: Exception) {
                                            }
                                            laneClient = null
                                        }
                                    }
                                } finally {
                                    // AND-010: lane 退出（Channel 耗尽或取消）时关闭长驻 client。
                                    val closing = laneClient
                                    laneClient = null
                                    if (closing != null) {
                                        try {
                                            closing.close()
                                        } catch (e: CancellationException) {
                                            workerScope.cancel(e)
                                            throw e
                                        } catch (_: Exception) {
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            progressPublisher.value = progressPublisher.value.copy(phase = SyncPhase.DELETING)

            for (action in toDelete) {
                progressPublisher.value = progressPublisher.value.copy(
                    currentFileName = action.relativePath,
                    completedFiles = completedCount.get()
                )

                val videoItem = videoRepository.getByRelativePathAndRelationId(action.relativePath, relationId)
                // 卷一致性最小防护（用户决策）：删除前校验 entity.volumeId 与当前 store.volumeId 一致。
                // 不一致说明该密文属于另一个存储卷（如旧的 SAF 卷未挂载），当前 store 既看不到也不应删它，
                // 否则会误删属于其他卷的本地密文。跳过并记 log，不删。
                if (videoItem != null && videoItem.volumeId != store.volumeId) {
                    // 与 executeDelete 的 DELETE_BOTH 路径保持一致：卷不匹配计入失败，
                    // 否则 completedFiles + failedFiles < totalFiles 且 executeSync 误报成功，
                    // 关系会卡在"看似完成实则未完成"的状态。status 记 SKIPPED（动作未执行），
                    // 但 failedDeleteCount++（关系未真正完成）。
                    failedDeleteCount.incrementAndGet()
                    Log.w(
                        TAG,
                        "DELETE_LOCAL skipped (volume mismatch): path=${action.relativePath} " +
                            "stored volumeId differs from active volume"
                    )
                    syncLogRepository.log(
                        action = "DELETE_LOCAL",
                        relativePath = action.relativePath,
                        status = "SKIPPED",
                        dryRun = false,
                        fileSize = action.fileSize,
                        errorMessage = context.getString(R.string.sync_error_volume_mismatch)
                    )
                    continue
                }
                if (videoItem != null) {
                    val mediaGone = deleteExecutor.deleteStoreMedia(store, videoItem.id)
                    val thumbGone = deleteExecutor.deleteStoreThumb(store, videoItem.id)
                    if (mediaGone && thumbGone) {
                        playbackRepository.deleteByVideoId(videoItem.id)
                        videoRepository.deleteByRelativePathAndRelationId(action.relativePath, relationId)
                        syncLogRepository.log(
                            action = "DELETE_LOCAL",
                            relativePath = action.relativePath,
                            status = "SUCCESS",
                            dryRun = false,
                            fileSize = action.fileSize
                        )
                        completedCount.incrementAndGet()
                        progressPublisher.markDeleted(action.relativePath)
                    } else {
                        Log.w(TAG, "DELETE_LOCAL failed id=${videoItem.id}: mediaGone=$mediaGone thumbGone=$thumbGone")
                        failedDeleteCount.incrementAndGet()
                        syncLogRepository.log(
                            action = "DELETE_LOCAL",
                            relativePath = action.relativePath,
                            status = "FAILED",
                            dryRun = false,
                            fileSize = action.fileSize,
                            errorMessage = context.getString(
                                R.string.sync_error_local_delete_incomplete, mediaGone, thumbGone
                            )
                        )
                    }
                } else {
                    // No DB row — nothing local to clean; treat as already gone.
                    syncLogRepository.log(
                        action = "DELETE_LOCAL",
                        relativePath = action.relativePath,
                        status = "SUCCESS",
                        dryRun = false,
                        fileSize = action.fileSize
                    )
                    completedCount.incrementAndGet()
                    progressPublisher.markDeleted(action.relativePath)
                }
            }

            val totalFailures = failedDownloadCount.get() + failedDeleteCount.get()
            val hadFailures = totalFailures > 0
            DebugLogger.i(TAG, "同步完成: 成功 ${completedCount.get()}, 失败 $totalFailures, 总计 $totalActions")
            progressPublisher.value = SyncProgress(
                isRunning = false,
                phase = if (hadFailures) SyncPhase.PARTIAL_FAILED else SyncPhase.COMPLETED,
                completedFiles = completedCount.get(),
                totalFiles = totalActions,
                failedFiles = totalFailures,
                transferredBytes = totalBytesTransferred.get(),
                relationId = relationId,
                relationName = name,
                localBasePath = basePath,
                startTimeMs = progressPublisher.value.startTimeMs,
                completedPaths = progressPublisher.completedPathsSnapshot()
            )

            val successDownloadCount = toActuallyDownload.size - failedDownloadCount.get()
            val reconcilePart = "" // RECONCILE 已移除
            val failedPart = if (totalFailures > 0) context.getString(R.string.sync_result_failed, totalFailures) else ""
            val msg = context.getString(R.string.sync_result_success, successDownloadCount, reconcilePart, failedPart, toDelete.size)
            syncRelationRepository.updateLastSync(relationId, msg)
            syncLogRepository.clearOldLogs()
            return !hadFailures
        } catch (e: CancellationException) {
            progressPublisher.value = SyncProgress(
                isRunning = false,
                phase = SyncPhase.CANCELLED,
                relationId = relationId,
                relationName = name,
                localBasePath = basePath
            )
            throw e
        } catch (e: Exception) {
            DebugLogger.e(TAG, "同步异常退出", e)
            progressPublisher.value = SyncProgress(
                isRunning = false,
                phase = SyncPhase.FAILED,
                relationId = relationId,
                relationName = name,
                localBasePath = basePath
            )
            syncRelationRepository.updateLastSync(relationId, syncErrorMessage(e, context))
            syncLogRepository.log(
                action = "SYNC",
                relativePath = "",
                status = "FAILED",
                dryRun = false,
                errorMessage = syncErrorMessage(e, context)
            )
            return false
        } finally {
        }
    }

    companion object {
        private const val TAG = "SyncEngine"
        // E11: 并发下载数。3 是个人 NAS 带宽与本地磁盘 IO 的折中——
        // 过高会抢占播放器预览/缩略图 IO 并可能压垮低端 NAS 的并发请求处理，
        // 过低则大库首次同步耗时过长。如需调整请同步评估 dryRun 的扫描上限与每文件可用空间复检。
        // AND-010: 该值同时是长驻下载 lane 数（每 lane 持一个跨 action 复用的 client），
        // 即一次同步轮次在全部成功路径下的 client 创建次数上限。
        private const val MAX_CONCURRENCY = 3

        // P1-2: 单文件下载重试白名单。只有真正「可能自愈」的瞬态错误才进入 3 次重试；
        // 终态错误（认证失败、权限拒绝、远端文件不存在、空间不足、finalize 失败、扫描不完整等）
        // 立即抛出，避免无意义延迟、重复认证压力与重复下载。
        private val RETRYABLE_CODES: Set<SyncErrorCode> = setOf(
            SyncErrorCode.TIMEOUT,
            SyncErrorCode.CONNECTION_REFUSED,
            SyncErrorCode.UNKNOWN_HOST,
            SyncErrorCode.NETWORK_UNAVAILABLE,
            SyncErrorCode.DOWNLOAD_FAILED,
            SyncErrorCode.UNKNOWN
        )
    }
}
