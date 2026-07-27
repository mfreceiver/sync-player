package cn.vectory.syncplayer.sync

import android.content.Context
import android.util.Log
import cn.vectory.syncplayer.R
import cn.vectory.syncplayer.debug.DebugLogger
import cn.vectory.syncplayer.crypto.CryptoScheme
import cn.vectory.syncplayer.data.model.SyncAction
import cn.vectory.syncplayer.data.model.SyncActionType
import cn.vectory.syncplayer.data.model.SyncPhase
import cn.vectory.syncplayer.data.model.SyncProgress
import cn.vectory.syncplayer.data.model.SyncProtocol
import cn.vectory.syncplayer.data.local.dao.PendingSyncActionDao
import cn.vectory.syncplayer.data.local.entity.PendingSyncActionEntity
import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import cn.vectory.syncplayer.data.model.SyncRelation
import cn.vectory.syncplayer.data.repository.PlaybackRepository
import cn.vectory.syncplayer.data.repository.SyncLogRepository
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.data.repository.SyncSourceRepository
import cn.vectory.syncplayer.data.repository.SyncRelationRepository
import cn.vectory.syncplayer.storage.StoragePaths
import cn.vectory.syncplayer.storage.volume.EncryptedMediaStore
import cn.vectory.syncplayer.ui.util.extractParentPath
import cn.vectory.syncplayer.ui.util.isImageFile
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

data class PendingSyncTask(
    val relationId: Long,
    val relationName: String,
    val localBasePath: String,
    val toDownload: List<SyncAction>,
    val toDelete: List<SyncAction>
)

/**
 * `dryRun` 的返回值。
 *
 * P1-1: 扫描失败时把根因 [SyncException] 带回调用方（ViewModel），避免一律显示「连接失败」。
 * 成功路径 [error] 为 null；扫描异常路径两个列表必为空（E8 guard），且 [error] 非空。
 */
data class DryRunResult(
    val toDownload: List<SyncAction>,
    val toDelete: List<SyncAction>,
    val error: SyncException? = null
)

@Singleton
class SyncEngine @Inject constructor(
    private val syncSourceRepository: SyncSourceRepository,
    private val syncRelationRepository: SyncRelationRepository,
    private val videoRepository: VideoRepository,
    private val playbackRepository: PlaybackRepository,
    private val syncLogRepository: SyncLogRepository,
    private val videoMetadataExtractor: VideoMetadataExtractor,
    private val imageMetadataExtractor: ImageMetadataExtractor,
    private val pendingSyncActionDao: PendingSyncActionDao,
    private val remoteFileClientFactory: RemoteFileClientFactory,
    private val storagePaths: StoragePaths,
    private val encryptedMediaStoreLazy: Lazy<EncryptedMediaStore>,
    @ApplicationContext private val context: Context
) {

    // 进度状态已迁入 SyncProgressPublisher（上帝类拆分第一轮）。
    // 对外仍暴露只读 StateFlow<SyncProgress>，签名不变；内部通过 progressPublisher 读写。
    private val progressPublisher = SyncProgressPublisher()
    val progress: StateFlow<SyncProgress> get() = progressPublisher.progress

    private val encryptedMediaStore: EncryptedMediaStore
        get() = encryptedMediaStoreLazy.get()

    private val syncMutexMap = ConcurrentHashMap<Long, Mutex>()

    /** Global mutex coordinating sync operations with storage reset/switch (prevents TOCTOU). */
    val storageOperationMutex = Mutex()

    private fun getMutexForRelation(relationId: Long): Mutex =
        syncMutexMap.computeIfAbsent(relationId) { Mutex() }

    // 空间预检已迁入 SyncSpaceChecker（无状态、无 IO 副作用的判定 + 诊断日志）。
    private val spaceChecker = SyncSpaceChecker()

    companion object {
        private const val TAG = "SyncEngine"
        // E11: 并发下载数。3 是个人 NAS 带宽与本地磁盘 IO 的折中——
        // 过高会抢占播放器预览/缩略图 IO 并可能压垮低端 NAS 的并发请求处理，
        // 过低则大库首次同步耗时过长。如需调整请同步评估 dryRun 的扫描上限与每文件可用空间复检。
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

    private suspend fun extractMetadata(entity: VideoItemEntity) {
        if (isImageFile(entity.fileName)) {
            imageMetadataExtractor.extractAndSave(entity)
        } else {
            videoMetadataExtractor.extractAndSave(entity)
        }
    }

    suspend fun setPendingTask(task: PendingSyncTask) {
        storageOperationMutex.withLock {
            val entities = mutableListOf<PendingSyncActionEntity>()
            for (action in task.toDownload) {
                entities.add(PendingSyncActionEntity(
                    relationId = task.relationId,
                    relationName = task.relationName,
                    localBasePath = task.localBasePath,
                    actionGroup = 0,
                    actionType = action.type.name,
                    relativePath = action.relativePath,
                    fileSize = action.fileSize,
                    remoteModifiedTime = action.remoteModifiedTime
                ))
            }
            for (action in task.toDelete) {
                entities.add(PendingSyncActionEntity(
                    relationId = task.relationId,
                    relationName = task.relationName,
                    localBasePath = task.localBasePath,
                    actionGroup = 1,
                    actionType = action.type.name,
                    relativePath = action.relativePath,
                    fileSize = action.fileSize,
                    remoteModifiedTime = action.remoteModifiedTime
                ))
            }
            pendingSyncActionDao.replaceForRelation(task.relationId, entities)
        }
    }

    internal suspend fun consumePendingTaskLocked(relationId: Long): PendingSyncTask? {
        pendingSyncActionDao.resetRunningToPending(relationId)
        val entities = pendingSyncActionDao.getPendingByRelationId(relationId)
        if (entities.isEmpty()) return null
        pendingSyncActionDao.markRunning(relationId)
        val first = entities.first()
        val toDownload = entities.filter { it.actionGroup == 0 }.map {
            SyncAction(
                type = try { SyncActionType.valueOf(it.actionType) } catch (_: Exception) { SyncActionType.DOWNLOAD },
                relativePath = it.relativePath,
                fileSize = it.fileSize,
                remoteModifiedTime = it.remoteModifiedTime
            )
        }
        val toDelete = entities.filter { it.actionGroup == 1 }.map {
            SyncAction(
                type = try { SyncActionType.valueOf(it.actionType) } catch (_: Exception) { SyncActionType.DELETE_LOCAL },
                relativePath = it.relativePath,
                fileSize = it.fileSize,
                remoteModifiedTime = it.remoteModifiedTime
            )
        }
        return PendingSyncTask(
            relationId = relationId,
            relationName = first.relationName,
            localBasePath = first.localBasePath,
            toDownload = toDownload,
            toDelete = toDelete
        )
    }

    suspend fun consumePendingTask(relationId: Long): PendingSyncTask? = storageOperationMutex.withLock {
        consumePendingTaskLocked(relationId)
    }

    /**
     * Atomically consume pending task + execute sync under [storageOperationMutex].
     * Eliminates TOCTOU race where reset/switch could intervene between consume and execute.
     * Called by [SyncService] instead of separate consumePendingTask + executeSync calls.
     */
    internal suspend fun consumeAndExecute(
        relationId: Long
    ): Pair<PendingSyncTask?, Boolean> = storageOperationMutex.withLock {
        val task = consumePendingTaskLocked(relationId) ?: return@withLock null to false
        val success = try {
            executeSyncLocked(
                relationId, task.toDownload, task.toDelete,
                task.relationName, task.localBasePath
            )
        } catch (e: CancellationException) {
            // NonCancellable: ensure DAO cleanup executes even in cancelled coroutine state.
            withContext(NonCancellable) {
                pendingSyncActionDao.markCancelled(relationId)
                pendingSyncActionDao.deleteByRelationId(relationId)
            }
            throw e
        }
        if (success) pendingSyncActionDao.markCompleted(relationId)
        else pendingSyncActionDao.markFailed(relationId)
        pendingSyncActionDao.deleteByRelationId(relationId)
        task to success
    }

    val isSyncRunning: Boolean get() = progressPublisher.value.isRunning

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

    suspend fun dryRun(relationId: Long): DryRunResult = storageOperationMutex.withLock { dryRunLocked(relationId) }

    private suspend fun dryRunLocked(relationId: Long): DryRunResult = getMutexForRelation(relationId).withLock {
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
                DebugLogger.e(TAG, "dryRun 失败", scanError)
            }
            return DryRunResult(toDownload, toDelete, scanError)
    }

    suspend fun executeSync(
        relationId: Long,
        toDownload: List<SyncAction>,
        toDelete: List<SyncAction>,
        relationName: String = "",
        localBasePath: String = ""
    ): Boolean = storageOperationMutex.withLock {
        executeSyncLocked(relationId, toDownload, toDelete, relationName, localBasePath)
    }

    internal suspend fun executeSyncLocked(
        relationId: Long,
        toDownload: List<SyncAction>,
        toDelete: List<SyncAction>,
        relationName: String = "",
        localBasePath: String = ""
    ): Boolean = getMutexForRelation(relationId).withLock {
            DebugLogger.i(TAG, "同步开始: relationId=$relationId, 待下载=${toDownload.size}, 待删除=${toDelete.size}")
            val relation = syncRelationRepository.getById(relationId) ?: return false
        val source = syncSourceRepository.getById(relation.syncSourceId) ?: return false
        val maxConcurrency = MAX_CONCURRENCY
        val name = relationName.ifEmpty { relation.name }
        val basePath = localBasePath.ifEmpty { relation.localBasePath }
        // E2: encryptedMediaStore 是 Hilt Lazy 的纯属性读取，无阻塞，无需 withContext(IO)。
        val store = encryptedMediaStore
        DebugLogger.i(TAG, "当前卷: ${store.volumeId}")

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
                    val semaphore = Semaphore(maxConcurrency)
                    coroutineScope {
                        for (action in toActuallyDownload) {
                            semaphore.acquire()
                            launch(Dispatchers.IO) {
                                val workerClient = getClient(source.protocol)
                                try {
                                    val localRelPath = storagePaths.toLocalRelativePath(relation.localBasePath, action.relativePath)
                                    val fileName = action.relativePath.substringAfterLast("/")
                                    DebugLogger.d(TAG, "下载: $fileName (${action.fileSize} bytes)")

                                    workerClient.connect(source, relation.remotePath)

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
                                            try { workerClient.disconnect() } catch (_: Exception) {}
                                            workerClient.connect(source, relation.remotePath)
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
                                        val sink = store.openEncryptSink(assignedId, iv, action.fileSize)
                                        try {
                                            downloadSuccess = workerClient.downloadTo(action.relativePath, sink) { transferred, total ->
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
                                        } catch (e: CancellationException) {
                                            try { sink.close() } catch (_: Exception) {}
                                            store.cancel(assignedId)
                                            if (existing == null) videoRepository.deleteById(assignedId)
                                            throw e
                                        } catch (e: Exception) {
                                            try { sink.close() } catch (_: Exception) {}
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
                                } finally {
                                    try { workerClient.close() } catch (_: Exception) {}
                                    semaphore.release()
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
                            "entity.volumeId=${videoItem.volumeId} store.volumeId=${store.volumeId}"
                    )
                    syncLogRepository.log(
                        action = "DELETE_LOCAL",
                        relativePath = action.relativePath,
                        status = "SKIPPED",
                        dryRun = false,
                        fileSize = action.fileSize,
                        errorMessage = context.getString(
                            R.string.sync_error_volume_mismatch,
                            videoItem.volumeId, store.volumeId
                        )
                    )
                    continue
                }
                if (videoItem != null) {
                    val mediaGone = deleteStoreMedia(store, videoItem.id)
                    val thumbGone = deleteStoreThumb(store, videoItem.id)
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

    fun resetProgress() {
        progressPublisher.reset()
    }

    suspend fun dryRunDelete(relationId: Long): Pair<List<SyncAction>, String?> = getMutexForRelation(relationId).withLock {
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

    suspend fun executeDelete(relationId: Long, actions: List<SyncAction>): Boolean = storageOperationMutex.withLock { executeDeleteLocked(relationId, actions) }

    private suspend fun executeDeleteLocked(relationId: Long, actions: List<SyncAction>): Boolean = getMutexForRelation(relationId).withLock {
        val relation = syncRelationRepository.getById(relationId) ?: return false
        val source = syncSourceRepository.getById(relation.syncSourceId) ?: return false
        val client = getClient(source.protocol)
        // E2: encryptedMediaStore 是 Hilt Lazy 纯属性读取，无阻塞，无需 withContext(IO)。
        val store = encryptedMediaStore

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

                // 1) Local first — never touch remote if local ciphertext/thumb still present.
                val videoItem = videoRepository.getByRelativePathAndRelationId(action.relativePath, relationId)
                // 卷一致性防护（同 executeSync 删除路径）：entity 属于另一卷时跳过整个 DELETE_BOTH，
                // 既不删本地（不属于当前 store 管辖）也不删远端，避免产生半完成状态。
                if (videoItem != null && videoItem.volumeId != store.volumeId) {
                    failedActions++
                    Log.w(
                        TAG,
                        "DELETE_BOTH skipped (volume mismatch): path=${action.relativePath} " +
                            "entity.volumeId=${videoItem.volumeId} store.volumeId=${store.volumeId}"
                    )
                    syncLogRepository.log(
                        action = "DELETE_BOTH",
                        relativePath = action.relativePath,
                        status = "SKIPPED",
                        dryRun = false,
                        fileSize = action.fileSize,
                        errorMessage = context.getString(
                            R.string.sync_error_volume_mismatch,
                            videoItem.volumeId, store.volumeId
                        )
                    )
                    continue
                }
                if (videoItem != null) {
                    val mediaGone = deleteStoreMedia(store, videoItem.id)
                    val thumbGone = deleteStoreThumb(store, videoItem.id)
                    if (!(mediaGone && thumbGone)) {
                        failedActions++
                        Log.w(TAG, "DELETE_BOTH local failed id=${videoItem.id}: mediaGone=$mediaGone thumbGone=$thumbGone")
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

                // 2) Remote — false from SMB means "already gone" (idempotent); exceptions are real failures.
                val remoteRelPath = storagePaths.toPathUnderBase(relation.localBasePath, action.relativePath)
                try {
                    client.deleteFile(remoteRelPath)
                    // true = deleted; false (SMB !exists) = already gone → both OK
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failedActions++
                    Log.w(TAG, "DELETE_BOTH remote failed code=${(e as? SyncException)?.code} path=$remoteRelPath", e)
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

            if (allOk) {
                syncRelationRepository.updateLastSync(
                    relationId,
                    context.getString(R.string.sync_delete_success, completedActions)
                )
            } else {
                syncRelationRepository.updateLastSync(
                    relationId,
                    context.getString(R.string.sync_delete_failed, "local/remote incomplete: ok=$completedActions fail=$failedActions")
                )
            }
            syncLogRepository.clearOldLogs()
            return allOk
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            progressPublisher.value = SyncProgress(
                isRunning = false,
                phase = SyncPhase.FAILED,
                relationId = relationId,
                relationName = relation.name,
                localBasePath = relation.localBasePath
            )
            syncRelationRepository.updateLastSync(relationId, syncErrorMessage(e, context))
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
     * Delete ciphertext; [Boolean] false is ambiguous (missing vs fail) — re-check [EncryptedMediaStore.exists].
     * @return true if media is gone (deleted or never present); false if still present after failed delete.
     */
    private suspend fun deleteStoreMedia(store: EncryptedMediaStore, id: Long): Boolean {
        return try {
            val deleted = store.delete(id)
            if (deleted) true
            else !store.exists(id)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete media id=$id", e)
            try {
                !store.exists(id)
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * Delete thumb; missing thumb is success. Still-present after false → failure.
     */
    private suspend fun deleteStoreThumb(store: EncryptedMediaStore, id: Long): Boolean {
        return try {
            val deleted = store.deleteThumb(id)
            if (deleted) true
            else !store.thumbExists(id)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete thumb id=$id", e)
            try {
                !store.thumbExists(id)
            } catch (_: Exception) {
                false
            }
        }
    }
}
