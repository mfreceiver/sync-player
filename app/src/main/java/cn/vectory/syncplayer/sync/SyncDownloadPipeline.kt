package cn.vectory.syncplayer.sync

import android.content.Context
import android.util.Log
import cn.vectory.syncplayer.R
import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import cn.vectory.syncplayer.data.model.SyncAction
import cn.vectory.syncplayer.data.model.SyncOutcome
import cn.vectory.syncplayer.data.model.SyncProgress
import cn.vectory.syncplayer.data.model.SyncProtocol
import cn.vectory.syncplayer.data.model.SyncPhase
import cn.vectory.syncplayer.data.model.SyncSource
import cn.vectory.syncplayer.data.repository.PlaybackRepository
import cn.vectory.syncplayer.data.repository.SyncLogRepository
import cn.vectory.syncplayer.data.repository.SyncRelationRepository
import cn.vectory.syncplayer.data.repository.SyncSourceRepository
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.debug.DebugLogger
import cn.vectory.syncplayer.storage.StoragePaths
import cn.vectory.syncplayer.storage.MediaFileStore
import cn.vectory.syncplayer.storage.TmpProbeState
import cn.vectory.syncplayer.ui.util.extractParentPath
import cn.vectory.syncplayer.ui.util.isImageFile
import dagger.Lazy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
 * 从 Channel 消费 action；E5 重连重试 / E9 逐文件空间复检 / 两阶段提交 sink /
 * 可读性探针回滚 / NonCancellable 取消清理）与 DELETING 阶段的 DELETE_LOCAL tail。
 *
 * **不持有任何锁**：双层互斥（`storageOperationMutex` + relation mutex）由 [SyncEngine]
 * 门面持有后调用——一次同步的生命周期编排（pending mark/finalize/NonCancellable 清理）
 * 留在 Engine。
 *
 * 迁移不变量（与原 [SyncEngine] 内联实现逐字符等价）：
 *  - 重试白名单 [RETRYABLE_CODES]（P1-2）与 E5 重试前重连；
 *  - 无 DB 行的 DELETE_LOCAL 计成功（本地已不存在即已删除）；
 *  - CancellationException 先传播（worker 内 NonCancellable 清理 + workerScope.cancel）；
 *  - dry-run 语义不变（本类只执行，计划由 [SyncRemoteScanner] 产出）；
 *  - 空间检查走 [SyncSpaceChecker]；
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
    private val mediaFileStoreLazy: Lazy<MediaFileStore>,
    private val context: Context,
    private val progressPublisher: SyncProgressPublisher,
    private val deleteExecutor: SyncDeleteExecutor,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private val mediaFileStore: MediaFileStore
        get() = mediaFileStoreLazy.get()

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

    /**
     * BUG-007：单次下载尝试的三阶段提交状态。清理语义据此分派：
     *  - [NOT_FINALIZED]：本尝试未进入 finalize——磁盘提交面未被本尝试触碰，旧 final 必然完好，绝不删；
     *  - [FINALIZE_UNCERTAIN]：已进入 finalize、未取得 true 确认——由三态 tmp 探测收束；
     *  - [COMMITTED]：finalize 返回 true——final=新内容，可安全删。
     */
    private enum class AttemptPhase { NOT_FINALIZED, FINALIZE_UNCERTAIN, COMMITTED }

    /**
     * BUG-010：初始建连纳入 P1-2 白名单重试（与单文件下载重试同参：3 次、
     * `delay(1000L * attempt)`、[isRetryableSyncException] 白名单、终态码立即抛）。
     * 失败的 connect 可能残留半建连状态，重试前按 E5 语义先 disconnect。
     * CE 不重试；终态码（AUTH/ACCESS/...）立即上抛由外层计一次失败——
     * connect 重试预算与下载重试预算独立，失败计数不放大。
     */
    private suspend fun connectWithRetry(
        client: RemoteFileClient,
        source: SyncSource,
        remotePath: String
    ) {
        var attempt = 0
        while (true) {
            attempt++
            try {
                client.connect(source, remotePath)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isRetryableSyncException(e) || attempt >= 3) throw e
                try {
                    client.disconnect()
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Exception) {
                }
                delay(1000L * attempt)
            }
        }
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
        // E2: mediaFileStore 是 Hilt Lazy 的纯属性读取，无阻塞，无需 withContext(IO)。
        val store = mediaFileStore

        val toActuallyDownload = toDownload
        // 预检逻辑（溢出降级、诊断日志）已迁入 SyncSpaceChecker；此处只处理
        // 「空间不足」这一需要 repository 副作用的判定分支，Ok 直接放行到逐文件下载。
        val spaceCheck = spaceChecker.checkSpace(toActuallyDownload, store)
        if (spaceCheck is SyncSpaceChecker.Result.Insufficient) {
            DebugLogger.e(TAG, "空间不足，中止同步")
            // F02: 结构化终态入库（FAILED + 错误码），不再拼文案；文案由展示层映射。
            syncRelationRepository.updateLastSync(
                relationId,
                SyncOutcome(
                    SyncOutcome.Status.FAILED,
                    errorCode = SyncErrorCode.INSUFFICIENT_SPACE.name
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
                            launch(ioDispatcher) {
                                var laneClient: RemoteFileClient? = null
                                try {
                                    for (action in actionChannel) {
                                        var client = laneClient
                                        // BUG-007：声明提升至 per-action try 之外——attempt 级清理与
                                        // 外层 CE 补偿 catch（见下）均需读取 existing/assignedId；
                                        // uncleanFinalizedId 是「已提交未登记」登记簿，两处清零点：
                                        // ① 元数据列提交成功后（A1-01：与 UPDATE 同处 NonCancellable
                                        //    临界段内——suspend 提交的返回恢复点可抛 CE，清零必须在保护区内）
                                        // ② cleanUpAttempt 末尾（清理侧）。
                                        // A1-02: placeholderEntity 记录占位行实体（existing==null 时），
                                        // 在每次 upsert 分配后以真实主键刷新——供元数据提取阶段取 id/fileName，
                                        // 列提交后不再整行回读/回写。
                                        var existing: VideoItemEntity? = null
                                        var placeholderEntity: VideoItemEntity? = null
                                        var assignedId = 0L
                                        var uncleanFinalizedId = 0L

                                        /** BUG-007 唯一清理入口（attempt 级 catch 与外层 CE 补偿共用）。 */
                                        suspend fun cleanUpAttempt(phase: AttemptPhase, id: Long) {
                                            suspend fun guarded(op: suspend () -> Unit) {
                                                // NonCancellable 语境下框架不交付 CE；此兜底只防自定义实现/mock
                                                // 违约抛 CE。吞并理由「原异常优先」：清理中的任何异常都不得替换
                                                // 已在传播的原异常，也不得短路后续清理步骤。
                                                try { op() } catch (_: Exception) {}
                                            }
                                            when (phase) {
                                                AttemptPhase.NOT_FINALIZED -> {
                                                    // 本尝试从未进入 finalize ⇒ tmp 可能从未创建或已自删，
                                                    // 旧 final 必然完好——绝不探测、绝不删 final。
                                                    guarded { store.cancel(id) }
                                                    if (existing == null) {
                                                        guarded { videoRepository.deleteById(id) }
                                                        assignedId = 0L
                                                    }
                                                }
                                                AttemptPhase.FINALIZE_UNCERTAIN -> {
                                                    // 探测单独保护（v3.1）：probePendingTmp 对普通调用方 rethrow CE，
                                                    // 但 NonCancellable 不约束自定义实现/mock 的行为——探测抛出的任何
                                                    // 异常（含 CE）按「原异常优先」收敛 UNKNOWN 后继续清理。
                                                    val probe = try {
                                                        store.probePendingTmp(id)
                                                    } catch (_: Exception) {
                                                        TmpProbeState.UNKNOWN
                                                    }
                                                    when (probe) {
                                                        TmpProbeState.PRESENT -> {
                                                            // tmp 未被消费 ⇒ rename 未发生，旧 final 完好
                                                            guarded { store.cancel(id) }
                                                            if (existing == null) {
                                                                guarded { videoRepository.deleteById(id) }
                                                                assignedId = 0L
                                                            }
                                                        }
                                                        TmpProbeState.ABSENT -> {
                                                            // 进入过 finalize 且 tmp 严格确认缺失 ⇒ 被 rename 消费
                                                            // （前提链见计划 §2.1）——与 COMMITTED 分支同构
                                                            guarded { store.delete(id) }
                                                            guarded { videoRepository.deleteById(id) }
                                                            assignedId = 0L
                                                            existing = null
                                                        }
                                                        TmpProbeState.UNKNOWN -> {
                                                            // 探测不可信：删行（行不得指向可能已换的文件），不删 final
                                                            guarded { videoRepository.deleteById(id) }
                                                            assignedId = 0L
                                                            existing = null
                                                        }
                                                    }
                                                }
                                                AttemptPhase.COMMITTED -> {
                                                    guarded { store.delete(id) }
                                                    // 无条件：旧行指向已覆盖文件；占位行同理（幽灵文件防线）
                                                    guarded { videoRepository.deleteById(id) }
                                                    assignedId = 0L
                                                    existing = null
                                                }
                                            }
                                            // 清理侧清零点：与外层补偿 catch 互斥（恰一次回滚）
                                            uncleanFinalizedId = 0L
                                        }
                                        try {
                                            val localRelPath = storagePaths.toLocalRelativePath(relation.localBasePath, action.relativePath)
                                            val fileName = action.relativePath.substringAfterLast("/")
                                            // 复审红线：日志不拼 fileName/path——只留字节数。
                                            DebugLogger.d(TAG, "下载开始: ${action.fileSize} bytes")

                                            if (client == null) {
                                                client = getClient(source.protocol)
                                                // BUG-010: 初始建连走白名单重试（原裸 connect 一抛即弃，
                                                // 一次弱网抖动使整批文件逐个失败）。
                                                connectWithRetry(client, source, relation.remotePath)
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

                                            existing = videoRepository.getByRelativePathAndRelationId(localRelPath, relationId)
                                            var downloadSuccess = false
                                            var attempts = 0
                                            assignedId = existing?.id ?: 0L

                                            while (attempts < 3 && !downloadSuccess) {
                                                attempts++
                                                // BUG-007：每次尝试重置提交阶段——磁盘提交面的确定性随阶段推进
                                                var attemptPhase = AttemptPhase.NOT_FINALIZED
                                                if (attempts > 1) {
                                                    // E5: 重试前重连——上次失败的下载可能已损坏底层传输（SMB session/SFTP channel），
                                                    // 复用旧 client 会再次失败。先断开再建新连接。建连同样需走瞬态重试保护（F-002），
                                                    // 避免抖动直接逃出剩余重试。
                                                    try { client.disconnect() } catch (e: CancellationException) {
                                                        throw e
                                                    } catch (_: Exception) {}
                                                    connectWithRetry(client, source, relation.remotePath)
                                                }
                                                // E9: 每个文件下载前复检可用空间（3 并发下载途中磁盘可能被耗尽）。
                                                // 不足时直接抛出，由外层 catch 计 failedDownloadCount 并跳到下一个文件，
                                                // 不进入 3 次重试（空间不足不会在几秒内自愈，重试无意义）。
                                                val requiredBytes = action.fileSize
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
                                                // 占位行仅在 existing==null 首次创建（最小元数据：fileSize=0、remoteModifiedTime=null），
                                                // 进程崩溃后 dryRun 必然重下（size/mtime 不匹配远端）。
                                                // existing 复用 id，下载前不改动 DB，失败时旧记录与旧文件保持一致（避免数据损坏）。
                                                if (assignedId == 0L) {
                                                    val placeholder = VideoItemEntity(
                                                        syncRelationId = relationId,
                                                        relativePath = localRelPath,
                                                        parentPath = extractParentPath(localRelPath),
                                                        fileName = fileName,
                                                        fileSize = 0,
                                                        remoteModifiedTime = null,
                                                        dateSynced = System.currentTimeMillis()
                                                    )
                                                    assignedId = videoRepository.upsert(placeholder)
                                                    // A1-02：占位快照必须携带真实分配主键。cleanUpAttempt 在行删除后
                                                    // 会清零 assignedId ⇒ 重试重新 upsert 分配新 id ⇒ 每次分配后
                                                    // 都要刷新快照；否则提取阶段拿到 id=0 的快照，按列提交永远
                                                    // 0 行（元数据静默丢失）。
                                                    placeholderEntity = placeholder.copy(id = assignedId)
                                                }
                                                // 明文长度 = 远端源文件大小 = action.fileSize，sink close 时校验写入长度。
                                                var sink: java.io.OutputStream? = null
                                                try {
                                                    sink = store.openWriteSink(assignedId, action.fileSize)
                                                    // A1-1: downloadTo 返回 true 只是传输层收尾——不得据此退出
                                                    // 重试循环。sink.close() 的长度校验（截断→IOException）、
                                                    // finalize、verifyReadable 全部成功后才置 downloadSuccess；
                                                    // 否则 close IOException 会因提前置位而「带毒退出」循环
                                                    //（tmp 已 cancel、磁盘仍旧 final，却按成功路径提交元数据）。
                                                    val transferred = client.downloadTo(action.relativePath, sink) { transferred, total ->
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
                                                    if (!transferred) throw SyncException(
                                                        SyncErrorCode.DOWNLOAD_FAILED,
                                                        SyncOperation.DOWNLOAD,
                                                        path = action.relativePath
                                                    )
                                                    // BUG-007：进入 finalize——此后磁盘提交面不确定（rename 可能
                                                    // 已完成但未确认）。CE/异常保持 FINALIZE_UNCERTAIN（由探测收束）。
                                                    attemptPhase = AttemptPhase.FINALIZE_UNCERTAIN
                                                    val finalizedOk = try {
                                                        store.finalize(assignedId)
                                                    } catch (e: CancellationException) {
                                                        throw e
                                                    } catch (e: Exception) {
                                                        throw SyncException(
                                                            SyncErrorCode.FINALIZE_FAILED,
                                                            SyncOperation.DOWNLOAD,
                                                            path = action.relativePath,
                                                            cause = e
                                                        )
                                                    }
                                                    if (!finalizedOk) throw SyncException(
                                                        SyncErrorCode.FINALIZE_FAILED,
                                                        SyncOperation.DOWNLOAD,
                                                        path = action.relativePath
                                                    )
                                                // 仅 finalize 返回 true 才确认 COMMITTED；
                                                // 登记与判定之间无挂起点——登记簿不可能领先于磁盘确认。
                                                attemptPhase = AttemptPhase.COMMITTED
                                                uncleanFinalizedId = assignedId
                                                // 可读性探针：finalize 成功后立即做 O(1) 一致性检查——
                                                // final 文件存在且实际大小 == action.fileSize（远端源文件大小
                                                // 显式传入，不依赖 DB 最终 metadata）。截断/内容错配 → 抛
                                                // STORAGE_ERROR，由 attempt 级 generic catch 经
                                                // cleanUpAttempt(COMMITTED) 单点回滚（删 final + 删 DB 行，恰一次）。
                                                try {
                                                    store.verifyReadable(assignedId, action.fileSize)
                                                } catch (e: CancellationException) {
                                                    throw e
                                                } catch (e: Exception) {
                                                    throw SyncException(SyncErrorCode.STORAGE_ERROR, SyncOperation.DOWNLOAD, path = action.relativePath, cause = e)
                                                }
                                                    // A1-1: close/finalize/verify 全部成功的唯一退出点——
                                                    // 此前置位会使 close IOException 逃过重试（带毒成功）。
                                                    downloadSuccess = true
                                                } catch (e: CancellationException) {
                                                    try { sink?.close() } catch (_: Exception) {}
                                                    withContext(NonCancellable) { cleanUpAttempt(attemptPhase, assignedId) }
                                                    throw e
                                                } catch (e: Exception) {
                                                    try { sink?.close() } catch (_: Exception) {}
                                                    withContext(NonCancellable) { cleanUpAttempt(attemptPhase, assignedId) }
                                                    // P1-2: 终态错误（AUTH/ACCESS/PATH_NOT_FOUND/SPACE/FINALIZE/...）
                                                    // 不会在几秒内自愈，重试纯属浪费延迟并放大认证/下载压力，立即抛出，
                                                    // 由外层 catch 计一次 failedDownloadCount。仅瞬态错误继续重试。
                                                    if (!isRetryableSyncException(e)) throw e
                                                    if (attempts >= 3) throw e
                                                    DebugLogger.w(TAG, "重试 ($attempts/3): ${e.javaClass.simpleName}")
                                                    delay(1000L * attempts)
                                                }
                                            }

                                            progressPublisher.removeActiveDownload(action.relativePath)

                                            totalBytesTransferred.addAndGet(action.fileSize)
                                            // finalize 成功后才写入最终元数据（fileSize/remoteModifiedTime），
                                            // 保证 DB 与磁盘文件一致；此前 placeholder/existing 不持有误导性 final 值。
                                            // A1-2 列所有权：按列提交替代 getById+copy+整行 upsert——
                                            //  - 整行 upsert 会用下载前快照覆盖用户列（star/audioGainDb）；
                                            //  - @Upsert 带已知 id 会复活并发删除（回收站）的行；
                                            //  - 返回行数 0 = 行在 finalize 提交后消失（并发删除等）＝孤儿文件
                                            //    ——明确失败 + 同一 NonCancellable 清理入口收束，绝不 SUCCESS 计数。
                                            // A1-01 提交临界段（rev-gpt FAIL 修复）：suspend 列提交即使 DB 副作用
                                            // 已完成，返回恢复点仍可能抛 CE——若「登记清零」在保护区外，外层 CE
                                            // 补偿（下方 catch）会误删已提交的行+final。故 UPDATE+行数判定+成功
                                            // 清零必须在同一窄 NonCancellable 保护区；网络/提取/日志不得进入。
                                            // 0 行判定后的 STORAGE_ERROR 在保护区外抛出（普通失败路径交付）。
                                            var commitFailed = false
                                            withContext(NonCancellable) {
                                                val committedRows = videoRepository.updateSyncMetadata(
                                                    id = assignedId,
                                                    fileSize = action.fileSize,
                                                    remoteModifiedTime = action.remoteModifiedTime,
                                                    dateSynced = System.currentTimeMillis()
                                                )
                                                if (committedRows == 0) {
                                                    // 孤儿密文回滚走同一清理入口（cleanUpAttempt 末尾清零 ⇒ 恰一次）
                                                    cleanUpAttempt(AttemptPhase.COMMITTED, assignedId)
                                                    commitFailed = true
                                                } else {
                                                    // A1-3 成功侧清零点：与 DB 副作用同处一个保护区——
                                                    // 其后的取消是干净中断（CE 在保护区外下一挂起点交付），
                                                    // 不回滚已提交产物。
                                                    uncleanFinalizedId = 0L
                                                }
                                            }
                                            // A3（rev-gpt）：NonCancellable 只改保护区内的 Job——
                                            // 退出保护区后若外层 Job 已带取消标志，不会自动抛 CE。
                                            // 在 commitFailed 分类与元数据提取之前显式检查：
                                            //  - 提交成功后被取消 ⇒ 此处交付 CE，绝不进入提取器；
                                            //  - 0 行清理（已在保护区内完整收束、登记已清零）期间被取消
                                            //    ⇒ 此处交付 CE，清理恰一次，不重复补偿。
                                            currentCoroutineContext().ensureActive()
                                            if (commitFailed) throw SyncException(
                                                SyncErrorCode.STORAGE_ERROR,
                                                SyncOperation.DOWNLOAD
                                            )
                                            // A1-02: 提取阶段只需 id/fileName——existing/占位快照均携带真实
                                            // 分配主键（见上 placeholder.copy(id=assignedId)）；返回值仅作展示，
                                            // 绝不再作为整行写回依据（提取器内部同为列提交）。
                                            val entityForMeta = existing ?: placeholderEntity ?: VideoItemEntity(
                                                id = assignedId,
                                                syncRelationId = relationId,
                                                relativePath = localRelPath,
                                                parentPath = extractParentPath(localRelPath),
                                                fileName = fileName,
                                                fileSize = action.fileSize
                                            )
                                            try {
                                                extractMetadata(entityForMeta)
                                            } catch (e: CancellationException) {
                                                throw e
                                            } catch (e: Exception) {
                                                Log.w(TAG, SyncSafeLog.metadataExtractionFailed(e))
                                            }
                                            syncLogRepository.log(
                                                action = "DOWNLOAD",
                                                relativePath = localRelPath,
                                                status = "SUCCESS",
                                                dryRun = false,
                                                fileSize = action.fileSize
                                            )
                                            // 复审红线：日志不拼 fileName。
                                            DebugLogger.d(TAG, "下载完成")
                                            completedCount.incrementAndGet()
                                            progressPublisher.completeDownload(
                                                relativePath = action.relativePath,
                                                transferredSoFar = totalBytesTransferred.get(),
                                                totalDownloadBytes = totalDownloadBytes,
                                                completedFiles = completedCount.get(),
                                                totalFiles = totalActions
                                            )
                                        } catch (e: CancellationException) {
                                            // BUG-007 外层 CE 补偿：CE 落在元数据窗口（attempt 级清理未经过）时，
                                            // 登记簿非 0 → 走同一个 cleanUpAttempt 入口回滚已提交产物；
                                            // attempt 级已清理过则登记簿为 0 → 跳过（恰一次回滚）。
                                            val id = uncleanFinalizedId
                                            if (id != 0L) {
                                                withContext(NonCancellable) { cleanUpAttempt(AttemptPhase.COMMITTED, id) }
                                            }
                                            workerScope.cancel(e)
                                            throw e
                                        } catch (e: Exception) {
                                            // 复审阻断（BUG-007 后续）：普通异常也可能落在元数据窗口
                                            // （getById/upsert 抛普通异常）——登记簿非 0 ⇒ 文件已提交而行仍旧
                                            // 缺失＝索引-文件不一致。与上方 CE 补偿同构：经同一
                                            // NonCancellable 清理入口回滚（cleanUpAttempt 末尾清零 ⇒ 恰一次；
                                            // attempt 级已清理则登记簿为 0 ⇒ 跳过）。清理自身异常被 guarded
                                            // 吞并——原异常优先，继续走下方失败计费；CE 不经此分支（先传播）。
                                            val id = uncleanFinalizedId
                                            if (id != 0L) {
                                                withContext(NonCancellable) { cleanUpAttempt(AttemptPhase.COMMITTED, id) }
                                            }
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
                                            Log.e(TAG, SyncSafeLog.failure("DOWNLOAD", e))
                                            DebugLogger.w(TAG, "下载失败: ${e.javaClass.simpleName}")
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
                        Log.w(TAG, SyncSafeLog.localDeleteIncomplete("DELETE_LOCAL", videoItem.id, mediaGone, thumbGone))
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

            // F02: 结构化终态入库——部分失败必须与成功可区分（旧实现把 PARTIAL_FAILED
            // 写成 sync_result_success 文案）；失败数是展示所需的最小计数。
            val outcome = if (hadFailures) {
                SyncOutcome(SyncOutcome.Status.PARTIAL_FAILED, failedCount = totalFailures)
            } else {
                SyncOutcome(SyncOutcome.Status.SUCCESS)
            }
            syncRelationRepository.updateLastSync(relationId, outcome)
            syncLogRepository.clearOldLogs()
            return !hadFailures
        } catch (e: CancellationException) {
            // BUG-008: 终态必须 copy 当前进度（保留已 flush 的计数/字节/完成路径），
            // fresh SyncProgress 会把合并语义里的 0 当成「有意清零」→ "0 of 0"。
            progressPublisher.value = progressPublisher.value.copy(
                isRunning = false,
                phase = SyncPhase.CANCELLED,
                relationId = relationId,
                relationName = name,
                localBasePath = basePath
            )
            // F02: 取消收尾是**有限操作数**的 best-effort（单条 UPDATE；NonCancellable
            // 只保证在已取消协程内仍能执行这一条写入）——它不承诺取消等待时间有上界。
            // 无论记录成败，最终传播的都是原始 CancellationException e
            // （红线：绝不吞取消、绝不把取消当成功，收尾点也不得替换原始取消）。
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
            // 复审红线：不传原始 Throwable（DebugLogger 会把 stack+message 写入调试环形缓冲）。
            DebugLogger.e(TAG, SyncSafeLog.failure("SYNC", e))
            progressPublisher.value = progressPublisher.value.copy(
                isRunning = false,
                phase = SyncPhase.FAILED,
                relationId = relationId,
                relationName = name,
                localBasePath = basePath
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
