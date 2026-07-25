package cn.vectory.syncplayer.sync

import android.content.Context
import android.util.Log
import cn.vectory.syncplayer.R
import cn.vectory.syncplayer.crypto.CryptoScheme
import cn.vectory.syncplayer.data.model.ActiveDownload
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val _progress = MutableStateFlow(SyncProgress())
    val progress: StateFlow<SyncProgress> = _progress.asStateFlow()

    private val encryptedMediaStore: EncryptedMediaStore
        get() = encryptedMediaStoreLazy.get()

    private val syncMutexMap = ConcurrentHashMap<Long, Mutex>()

    private fun getMutexForRelation(relationId: Long): Mutex =
        syncMutexMap.computeIfAbsent(relationId) { Mutex() }

    private val lastProgressUpdateMs = AtomicLong(0L)
    private val PROGRESS_THROTTLE_MS = 500L

    companion object {
        private const val TAG = "SyncEngine"
    }

    private suspend fun extractMetadata(entity: VideoItemEntity) {
        if (isImageFile(entity.fileName)) {
            imageMetadataExtractor.extractAndSave(entity)
        } else {
            videoMetadataExtractor.extractAndSave(entity)
        }
    }

    suspend fun setPendingTask(task: PendingSyncTask) {
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

    suspend fun consumePendingTask(relationId: Long): PendingSyncTask? {
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

    val isSyncRunning: Boolean get() = _progress.value.isRunning

    private fun getClient(protocol: SyncProtocol): RemoteFileClient = remoteFileClientFactory.create(protocol)

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> "%.0f MB".format(bytes / (1024.0 * 1024))
        else -> "%.1f KB".format(bytes / 1024.0)
    }

    suspend fun dryRun(relationId: Long): Pair<List<SyncAction>, List<SyncAction>> = getMutexForRelation(relationId).withLock {
            val relation = syncRelationRepository.getById(relationId) ?: return Pair(emptyList(), emptyList())
            val source = syncSourceRepository.getById(relation.syncSourceId) ?: return Pair(emptyList(), emptyList())
            val client = getClient(source.protocol)

            _progress.value = SyncProgress(phase = SyncPhase.SCANNING, relationId = relationId, relationName = relation.name, localBasePath = relation.localBasePath)

            val toDownload = mutableListOf<SyncAction>()
            val toDelete = mutableListOf<SyncAction>()

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
                        if (remoteFile.size != local.fileSize || remoteFile.lastModified != (local.remoteModifiedTime ?: 0)) {
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

                _progress.value = SyncProgress(phase = SyncPhase.CONFIRMING, relationId = relationId, relationName = relation.name, localBasePath = relation.localBasePath)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _progress.value = SyncProgress(phase = SyncPhase.FAILED, relationId = relationId, relationName = relation.name, localBasePath = relation.localBasePath)
                syncLogRepository.log(
                    action = "SCAN",
                    relativePath = "",
                    status = "FAILED",
                    dryRun = true,
                    errorMessage = e.message
                )
            } finally {
                client.disconnect()
            }

            return Pair(toDownload, toDelete)
    }

    suspend fun executeSync(
        relationId: Long,
        toDownload: List<SyncAction>,
        toDelete: List<SyncAction>,
        relationName: String = "",
        localBasePath: String = ""
    ): Boolean = getMutexForRelation(relationId).withLock {
        val relation = syncRelationRepository.getById(relationId) ?: return false
        val source = syncSourceRepository.getById(relation.syncSourceId) ?: return false
        val maxConcurrency = 3
        val name = relationName.ifEmpty { relation.name }
        val basePath = localBasePath.ifEmpty { relation.localBasePath }
        val store = withContext(Dispatchers.IO) { encryptedMediaStore }

        val toActuallyDownload = toDownload
        val totalDownloadSize = toActuallyDownload.sumOf { it.fileSize }

        val availableSpace = store.usableSpace()
        if (totalDownloadSize > availableSpace) {
            syncRelationRepository.updateLastSync(relationId, context.getString(R.string.sync_disk_space_insufficient, formatSize(totalDownloadSize), formatSize(availableSpace)))
            _progress.value = SyncProgress(isRunning = false, phase = SyncPhase.FAILED, relationId = relationId, relationName = name, localBasePath = basePath)
            return false
        }

        val totalActions = toDownload.size + toDelete.size
        val completedCount = AtomicInteger(0)
        val failedCount = AtomicInteger(0)
        val totalBytesTransferred = AtomicLong(0L)
        val totalDownloadBytes = toDownload.sumOf { it.fileSize }
        val activeDownloadsMutex = Mutex()
        val activeDownloadsMap = mutableMapOf<String, ActiveDownload>()
        val completedPathsSet = mutableSetOf<String>()

        _progress.value = SyncProgress(
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

                                    workerClient.connect(source, relation.remotePath)

                                    activeDownloadsMutex.withLock {
                                        activeDownloadsMap[action.relativePath] = ActiveDownload(
                                            fileName = fileName,
                                            totalBytes = action.fileSize
                                        )
                                        updateActiveDownloadsState(activeDownloadsMap, totalBytesTransferred.get(), totalDownloadBytes, completedCount.get(), totalActions)
                                    }

                                    val existing = videoRepository.getByRelativePathAndRelationId(localRelPath, relationId)
                                    var downloadSuccess = false
                                    var attempts = 0
                                    var assignedId = existing?.id ?: 0L
                                    var lastIv = ByteArray(0)

                                    while (attempts < 3 && !downloadSuccess) {
                                        attempts++
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
                                        val sink = store.openEncryptSink(assignedId, iv)
                                        try {
                                            downloadSuccess = workerClient.downloadTo(action.relativePath, sink) { transferred, total ->
                                                val locked = activeDownloadsMutex.tryLock()
                                                if (locked) {
                                                    try {
                                                        activeDownloadsMap[action.relativePath] = ActiveDownload(
                                                            fileName = fileName,
                                                            progress = if (total > 0) transferred.toFloat() / total else 0f,
                                                            transferredBytes = transferred,
                                                            totalBytes = action.fileSize
                                                        )
                                                        val now = System.currentTimeMillis()
                                                        if (now - lastProgressUpdateMs.get() >= PROGRESS_THROTTLE_MS) {
                                                            lastProgressUpdateMs.set(now)
                                                            val currentTransferred = totalBytesTransferred.get() + activeDownloadsMap.values.sumOf { it.transferredBytes }
                                                            val elapsed = System.currentTimeMillis() - _progress.value.startTimeMs
                                                            val speed = if (elapsed > 1000) currentTransferred * 1000 / elapsed else 0L
                                                            _progress.value = _progress.value.copy(
                                                                activeDownloads = activeDownloadsMap.values.toList(),
                                                                transferredBytes = currentTransferred,
                                                                totalBytes = totalDownloadBytes,
                                                                completedFiles = completedCount.get(),
                                                                totalFiles = totalActions,
                                                                speedBytesPerSec = speed
                                                            )
                                                        }
                                                    } finally {
                                                        activeDownloadsMutex.unlock()
                                                    }
                                                }
                                            }
                                            sink.close()
                                            if (!downloadSuccess) throw java.io.IOException("download returned false for ${action.relativePath}")
                                            if (!store.finalize(assignedId)) throw java.io.IOException("finalize failed for id=$assignedId")
                                        } catch (e: CancellationException) {
                                            try { sink.close() } catch (_: Exception) {}
                                            store.cancel(assignedId)
                                            if (existing == null) videoRepository.deleteById(assignedId)
                                            throw e
                                        } catch (e: Exception) {
                                            try { sink.close() } catch (_: Exception) {}
                                            store.cancel(assignedId)
                                            if (existing == null) videoRepository.deleteById(assignedId)
                                            if (attempts >= 3) throw e
                                            delay(1000L * attempts)
                                        }
                                    }

                                    activeDownloadsMutex.withLock {
                                        activeDownloadsMap.remove(action.relativePath)
                                    }

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
                                    completedCount.incrementAndGet()
                                    activeDownloadsMutex.withLock {
                                        completedPathsSet.add(action.relativePath)
                                        updateActiveDownloadsState(
                                            activeDownloadsMap, totalBytesTransferred.get(),
                                            totalDownloadBytes, completedCount.get(), totalActions,
                                            completedPaths = completedPathsSet.toSet()
                                        )
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    val localRelPath = storagePaths.toLocalRelativePath(relation.localBasePath, action.relativePath)
                                    activeDownloadsMutex.withLock {
                                        activeDownloadsMap.remove(action.relativePath)
                                    }
                                    failedCount.incrementAndGet()
                                    completedCount.incrementAndGet()
                                    syncLogRepository.log(
                                        action = "DOWNLOAD",
                                        relativePath = localRelPath,
                                        status = "FAILED",
                                        dryRun = false,
                                        fileSize = action.fileSize,
                                        errorMessage = e.message
                                    )
                                    Log.e(TAG, "Download failed with exception: ${action.relativePath}", e)
                                } finally {
                                    try { workerClient.close() } catch (_: Exception) {}
                                    semaphore.release()
                                }
                            }
                        }
                    }
                }
            }

            _progress.value = _progress.value.copy(phase = SyncPhase.DELETING)

            for (action in toDelete) {
                _progress.value = _progress.value.copy(
                    currentFileName = action.relativePath,
                    completedFiles = completedCount.get()
                )

                val videoItem = videoRepository.getByRelativePathAndRelationId(action.relativePath, relationId)
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
                        completedPathsSet.add(action.relativePath)
                        _progress.value = _progress.value.copy(completedPaths = completedPathsSet.toSet())
                    } else {
                        Log.w(TAG, "DELETE_LOCAL failed id=${videoItem.id}: mediaGone=$mediaGone thumbGone=$thumbGone")
                        failedCount.incrementAndGet()
                        syncLogRepository.log(
                            action = "DELETE_LOCAL",
                            relativePath = action.relativePath,
                            status = "FAILED",
                            dryRun = false,
                            fileSize = action.fileSize,
                            errorMessage = "local delete incomplete mediaGone=$mediaGone thumbGone=$thumbGone"
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
                    completedPathsSet.add(action.relativePath)
                    _progress.value = _progress.value.copy(completedPaths = completedPathsSet.toSet())
                }
            }

            val hadFailures = failedCount.get() > 0
            _progress.value = SyncProgress(
                isRunning = false,
                phase = if (hadFailures) SyncPhase.PARTIAL_FAILED else SyncPhase.COMPLETED,
                completedFiles = completedCount.get(),
                totalFiles = totalActions,
                failedFiles = failedCount.get(),
                transferredBytes = totalBytesTransferred.get(),
                relationId = relationId,
                relationName = name,
                localBasePath = basePath,
                startTimeMs = _progress.value.startTimeMs,
                completedPaths = completedPathsSet.toSet()
            )

            val successDownloadCount = toActuallyDownload.size - failedCount.get()
            val reconcilePart = "" // RECONCILE 已移除
            val failedPart = if (failedCount.get() > 0) context.getString(R.string.sync_result_failed, failedCount.get()) else ""
            val msg = context.getString(R.string.sync_result_success, successDownloadCount, reconcilePart, failedPart, toDelete.size)
            syncRelationRepository.updateLastSync(relationId, msg)
            syncLogRepository.clearOldLogs()
            return !hadFailures
        } catch (e: CancellationException) {
            _progress.value = SyncProgress(
                isRunning = false,
                phase = SyncPhase.CANCELLED,
                relationId = relationId,
                relationName = name,
                localBasePath = basePath
            )
            throw e
        } catch (e: Exception) {
            _progress.value = SyncProgress(
                isRunning = false,
                phase = SyncPhase.FAILED,
                relationId = relationId,
                relationName = name,
                localBasePath = basePath
            )
            syncRelationRepository.updateLastSync(relationId, context.getString(R.string.sync_failed, e.message))
            syncLogRepository.log(
                action = "SYNC",
                relativePath = "",
                status = "FAILED",
                dryRun = false,
                errorMessage = e.message
            )
            return false
        } finally {
        }
    }

    private fun updateActiveDownloadsState(
        activeDownloadsMap: Map<String, ActiveDownload>,
        transferredSoFar: Long,
        totalDownloadBytes: Long,
        completedFiles: Int,
        totalFiles: Int,
        completedPaths: Set<String> = _progress.value.completedPaths
    ) {
        val activeTransferred = activeDownloadsMap.values.sumOf { it.transferredBytes }
        val totalTransferred = transferredSoFar + activeTransferred
        _progress.value = _progress.value.copy(
            activeDownloads = activeDownloadsMap.values.toList(),
            transferredBytes = totalTransferred,
            totalBytes = totalDownloadBytes,
            completedFiles = completedFiles,
            totalFiles = totalFiles,
            currentFileName = activeDownloadsMap.values.firstOrNull()?.fileName ?: _progress.value.currentFileName,
            currentFileProgress = activeDownloadsMap.values.firstOrNull()?.progress ?: _progress.value.currentFileProgress,
            completedPaths = completedPaths
        )
    }

    fun resetProgress() {
        _progress.value = SyncProgress()
    }

    suspend fun dryRunDelete(relationId: Long): Pair<List<SyncAction>, String?> = getMutexForRelation(relationId).withLock {
            val relation = syncRelationRepository.getById(relationId) ?: return Pair(emptyList(), context.getString(R.string.sync_relation_not_found))
            val source = syncSourceRepository.getById(relation.syncSourceId) ?: return Pair(emptyList(), context.getString(R.string.sync_source_not_found))
            val client = getClient(source.protocol)
            _progress.value = SyncProgress(phase = SyncPhase.SCANNING, relationId = relationId, relationName = relation.name, localBasePath = relation.localBasePath)

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

                _progress.value = SyncProgress(phase = SyncPhase.CONFIRMING, relationId = relationId, relationName = relation.name, localBasePath = relation.localBasePath)
                return Pair(toDelete, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _progress.value = SyncProgress(phase = SyncPhase.FAILED, relationId = relationId, relationName = relation.name, localBasePath = relation.localBasePath)
                return Pair(emptyList(), e.message)
            } finally {
                client.disconnect()
            }
    }

    suspend fun executeDelete(relationId: Long, actions: List<SyncAction>): Boolean = getMutexForRelation(relationId).withLock {
        val relation = syncRelationRepository.getById(relationId) ?: return false
        val source = syncSourceRepository.getById(relation.syncSourceId) ?: return false
        val client = getClient(source.protocol)
        val store = withContext(Dispatchers.IO) { encryptedMediaStore }

        val totalActions = actions.size
        var completedActions = 0

        _progress.value = SyncProgress(
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
                _progress.value = _progress.value.copy(
                    currentFileName = action.relativePath,
                    completedFiles = completedActions
                )

                // 1) Local first — never touch remote if local ciphertext/thumb still present.
                val videoItem = videoRepository.getByRelativePathAndRelationId(action.relativePath, relationId)
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
                            errorMessage = "local delete incomplete mediaGone=$mediaGone thumbGone=$thumbGone"
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
                    Log.w(TAG, "DELETE_BOTH remote failed path=$remoteRelPath", e)
                    syncLogRepository.log(
                        action = "DELETE_BOTH",
                        relativePath = action.relativePath,
                        status = "FAILED",
                        dryRun = false,
                        fileSize = action.fileSize,
                        errorMessage = e.message
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
            _progress.value = SyncProgress(
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
            _progress.value = SyncProgress(
                isRunning = false,
                phase = SyncPhase.FAILED,
                relationId = relationId,
                relationName = relation.name,
                localBasePath = relation.localBasePath
            )
            syncRelationRepository.updateLastSync(relationId, context.getString(R.string.sync_delete_failed, e.message))
            syncLogRepository.log(
                action = "DELETE_BOTH",
                relativePath = "",
                status = "FAILED",
                dryRun = false,
                errorMessage = e.message
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
