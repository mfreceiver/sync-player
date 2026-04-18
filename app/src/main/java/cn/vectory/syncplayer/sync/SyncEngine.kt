package cn.vectory.syncplayer.sync

import android.content.Context
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
import cn.vectory.syncplayer.sync.smb.SmbFileClient
import cn.vectory.syncplayer.sync.sftp.SftpFileClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
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
    private val pendingSyncActionDao: PendingSyncActionDao,
    private val smbFileClient: SmbFileClient,
    private val sftpFileClient: SftpFileClient,
    private val storagePaths: StoragePaths,
    @ApplicationContext private val context: Context
) {

    private val _progress = MutableStateFlow(SyncProgress())
    val progress: StateFlow<SyncProgress> = _progress.asStateFlow()

    private val syncMutexMap = ConcurrentHashMap<Long, Mutex>()

    private fun getMutexForRelation(relationId: Long): Mutex =
        syncMutexMap.computeIfAbsent(relationId) { Mutex() }

    private val lastProgressUpdateMs = AtomicLong(0L)
    private val PROGRESS_THROTTLE_MS = 500L

    suspend fun setPendingTask(task: PendingSyncTask) {
        pendingSyncActionDao.deleteByRelationId(task.relationId)
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
        pendingSyncActionDao.insertAll(entities)
    }

    suspend fun consumePendingTask(relationId: Long): PendingSyncTask? {
        val entities = pendingSyncActionDao.getByRelationId(relationId)
        if (entities.isEmpty()) return null
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
        pendingSyncActionDao.deleteByRelationId(relationId)
        return PendingSyncTask(
            relationId = relationId,
            relationName = first.relationName,
            localBasePath = first.localBasePath,
            toDownload = toDownload,
            toDelete = toDelete
        )
    }

    val isSyncRunning: Boolean get() = _progress.value.isRunning

    private fun getLocalSyncRoot(relation: SyncRelation): File =
        storagePaths.resolveUserData(relation.localBasePath.trimStart('/')).also { it.mkdirs() }

    private fun getClient(protocol: SyncProtocol): RemoteFileClient = when (protocol) {
        SyncProtocol.SMB -> smbFileClient
        SyncProtocol.SFTP -> sftpFileClient
    }

    private fun cleanupTempFiles(root: File) {
        root.walkTopDown().filter { it.name.endsWith(".sync_tmp") }.forEach {
            it.delete()
        }
    }

    private fun cleanupEmptyDirectories(root: File) {
        root.walkBottomUp()
            .filter { it.isDirectory && it != root && it.listFiles()?.isEmpty() == true }
            .forEach { it.delete() }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> "%.0f MB".format(bytes / (1024.0 * 1024))
        else -> "%.1f KB".format(bytes / 1024.0)
    }

    suspend fun dryRun(relationId: Long): Pair<List<SyncAction>, List<SyncAction>> = getMutexForRelation(relationId).withLock {
        try {
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

                    val actionType: SyncActionType? = when {
                        local != null -> {
                            if (remoteFile.size != local.fileSize || remoteFile.lastModified != (local.remoteModifiedTime ?: 0)) {
                                SyncActionType.DOWNLOAD
                            } else {
                                null
                            }
                        }
                        else -> {
                            val physicalFile = File(getLocalSyncRoot(relation), remoteRelPath)
                            if (physicalFile.exists() && physicalFile.length() == remoteFile.size) {
                                SyncActionType.RECONCILE
                            } else {
                                SyncActionType.DOWNLOAD
                            }
                        }
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
                        SyncActionType.RECONCILE -> {
                            val action = SyncAction(
                                type = SyncActionType.RECONCILE,
                                relativePath = remoteRelPath,
                                fileSize = remoteFile.size,
                                remoteModifiedTime = remoteFile.lastModified
                            )
                            toDownload.add(action)
                            syncLogRepository.log(
                                action = "RECONCILE",
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
        } finally {
            syncMutexMap.remove(relationId)
        }
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
        val client = getClient(source.protocol)
        val maxConcurrency = 3
        val name = relationName.ifEmpty { relation.name }
        val basePath = localBasePath.ifEmpty { relation.localBasePath }

        val toReconcile = toDownload.filter { it.type == SyncActionType.RECONCILE }
        val toActuallyDownload = toDownload.filter { it.type == SyncActionType.DOWNLOAD }
        val totalDownloadSize = toActuallyDownload.sumOf { it.fileSize }

        val availableSpace = storagePaths.userdataRoot.usableSpace
        if (totalDownloadSize > availableSpace) {
            syncRelationRepository.updateLastSync(relationId, "磁盘空间不足：需要 ${formatSize(totalDownloadSize)}，可用 ${formatSize(availableSpace)}")
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


        val localRoot = getLocalSyncRoot(relation)
        cleanupTempFiles(localRoot)

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
            client.connect(source, relation.remotePath)

            if (toDownload.isNotEmpty()) {

                for (action in toReconcile) {
                    val localRelPath = storagePaths.toLocalRelativePath(relation.localBasePath, action.relativePath)
                    val finalFile = File(localRoot, action.relativePath)

                    val rebuilt = videoRepository.getByRelativePathAndRelationId(localRelPath, 0)
                    if (rebuilt != null) {
                        videoRepository.upsert(rebuilt.copy(
                            syncRelationId = relationId,
                            remoteModifiedTime = action.remoteModifiedTime,
                            dateSynced = System.currentTimeMillis()
                        ))
                    } else {
                        val entity = VideoItemEntity(
                            relativePath = localRelPath,
                            fileName = action.relativePath.substringAfterLast("/"),
                            localAbsolutePath = finalFile.absolutePath,
                            fileSize = action.fileSize,
                            remoteModifiedTime = action.remoteModifiedTime,
                            dateSynced = System.currentTimeMillis(),
                            syncRelationId = relationId
                        )
                        videoRepository.upsert(entity)
                    }

                    val saved = videoRepository.getByRelativePathAndRelationId(localRelPath, relationId)
                    if (saved != null && (saved.duration == null || saved.thumbnailPath == null)) {
                        videoMetadataExtractor.extractAndSave(saved)
                    }

                    syncLogRepository.log(
                        action = "RECONCILE",
                        relativePath = localRelPath,
                        status = "SUCCESS",
                        dryRun = false,
                        fileSize = action.fileSize
                    )
                    completedCount.incrementAndGet()
                    completedPathsSet.add(action.relativePath)
                    _progress.value = _progress.value.copy(
                        completedFiles = completedCount.get(),
                        completedPaths = completedPathsSet.toSet()
                    )
                }

                if (toActuallyDownload.isNotEmpty()) {
                    val semaphore = Semaphore(maxConcurrency)
                    coroutineScope {
                        for (action in toActuallyDownload) {
                            semaphore.acquire()
                            launch(Dispatchers.IO) {
                                try {
                                    val tmpFile = File(localRoot, "${action.relativePath}.sync_tmp")
                                    val finalFile = File(localRoot, action.relativePath)
                                    val localRelPath = storagePaths.toLocalRelativePath(relation.localBasePath, action.relativePath)

                                    activeDownloadsMutex.withLock {
                                        activeDownloadsMap[action.relativePath] = ActiveDownload(
                                            fileName = action.relativePath.substringAfterLast("/"),
                                            totalBytes = action.fileSize
                                        )
                                        updateActiveDownloadsState(activeDownloadsMap, totalBytesTransferred.get(), totalDownloadBytes, completedCount.get(), totalActions)
                                    }

                                    var downloadSuccess = false
                                    var attempts = 0
                                    while (attempts < 3 && !downloadSuccess) {
                                        attempts++
                                        downloadSuccess = client.downloadFile(action.relativePath, tmpFile) { transferred, total ->
                                            val locked = activeDownloadsMutex.tryLock()
                                            if (locked) {
                                                try {
                                                    activeDownloadsMap[action.relativePath] = ActiveDownload(
                                                        fileName = action.relativePath.substringAfterLast("/"),
                                                        progress = if (total > 0) transferred.toFloat() / total else 0f,
                                                        transferredBytes = transferred,
                                                        totalBytes = action.fileSize
                                                    )
                                                    val now = System.currentTimeMillis()
                                                    if (now - lastProgressUpdateMs.get() >= PROGRESS_THROTTLE_MS) {
                                                        lastProgressUpdateMs.set(now)
                                                        _progress.value = _progress.value.copy(
                                                            activeDownloads = activeDownloadsMap.values.toList(),
                                                            transferredBytes = totalBytesTransferred.get() + activeDownloadsMap.values.sumOf { it.transferredBytes },
                                                            totalBytes = totalDownloadBytes,
                                                            completedFiles = completedCount.get(),
                                                            totalFiles = totalActions
                                                        )
                                                    }
                                                } finally {
                                                    activeDownloadsMutex.unlock()
                                                }
                                            }
                                        }
                                        if (!downloadSuccess && attempts < 3) {
                                            if (tmpFile.exists()) tmpFile.delete()
                                        }
                                    }

                                    activeDownloadsMutex.withLock {
                                        activeDownloadsMap.remove(action.relativePath)
                                    }

                                    if (downloadSuccess && tmpFile.exists()) {
                                        if (finalFile.exists()) finalFile.delete()
                                        if (!tmpFile.renameTo(finalFile)) {
                                            tmpFile.copyTo(finalFile)
                                            tmpFile.delete()
                                        }
                                        totalBytesTransferred.addAndGet(action.fileSize)
                                        val entity = VideoItemEntity(
                                            relativePath = localRelPath,
                                            fileName = action.relativePath.substringAfterLast("/"),
                                            localAbsolutePath = finalFile.absolutePath,
                                            fileSize = action.fileSize,
                                            remoteModifiedTime = action.remoteModifiedTime,
                                            dateSynced = System.currentTimeMillis(),
                                            syncRelationId = relationId
                                        )
                                        videoRepository.upsert(entity)
                                        val savedEntity = videoRepository.getByRelativePath(localRelPath)
                                        if (savedEntity != null) {
                                            videoMetadataExtractor.extractAndSave(savedEntity)
                                        }
                                        syncLogRepository.log(
                                            action = "DOWNLOAD",
                                            relativePath = localRelPath,
                                            status = "SUCCESS",
                                            dryRun = false,
                                            fileSize = action.fileSize
                                        )
                                    } else {
                                        if (tmpFile.exists()) tmpFile.delete()
                                        failedCount.incrementAndGet()
                                        syncLogRepository.log(
                                            action = "DOWNLOAD",
                                            relativePath = localRelPath,
                                            status = "FAILED",
                                            dryRun = false,
                                            fileSize = action.fileSize,
                                            errorMessage = "Download failed after $attempts attempts"
                                        )
                                    }
                                    completedCount.incrementAndGet()
                                    activeDownloadsMutex.withLock {
                                        completedPathsSet.add(action.relativePath)
                                        updateActiveDownloadsState(activeDownloadsMap, totalBytesTransferred.get(), totalDownloadBytes, completedCount.get(), totalActions)
                                    }
                                } finally {
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

                videoRepository.deleteByRelativePathAndRelationId(action.relativePath, relationId)
                val physicalRelPath = storagePaths.toPathUnderBase(relation.localBasePath, action.relativePath)
                val localFile = File(localRoot, physicalRelPath)
                if (localFile.exists()) localFile.delete()

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

            cleanupEmptyDirectories(localRoot)

            _progress.value = SyncProgress(
                isRunning = false,
                phase = SyncPhase.COMPLETED,
                completedFiles = totalActions,
                totalFiles = totalActions,
                transferredBytes = totalBytesTransferred.get(),
                relationId = relationId,
                relationName = name,
                localBasePath = basePath,
                startTimeMs = _progress.value.startTimeMs
            )

            val successDownloadCount = toActuallyDownload.size - failedCount.get()
            val msg = buildString {
                append("成功：下载 $successDownloadCount 个")
                if (toReconcile.isNotEmpty()) append("，匹配 ${toReconcile.size} 个")
                if (failedCount.get() > 0) append("，失败 ${failedCount.get()} 个")
                append("，删除 ${toDelete.size} 个")
            }
            syncRelationRepository.updateLastSync(relationId, msg)
            syncLogRepository.clearOldLogs()
            return true
        } catch (e: CancellationException) {
            cleanupTempFiles(localRoot)
            _progress.value = SyncProgress(
                isRunning = false,
                phase = SyncPhase.CANCELLED,
                relationId = relationId,
                relationName = name,
                localBasePath = basePath
            )
            throw e
        } catch (e: Exception) {
            cleanupTempFiles(localRoot)
            _progress.value = SyncProgress(
                isRunning = false,
                phase = SyncPhase.FAILED,
                relationId = relationId,
                relationName = name,
                localBasePath = basePath
            )
            syncRelationRepository.updateLastSync(relationId, "同步失败: ${e.message}")
            syncLogRepository.log(
                action = "SYNC",
                relativePath = "",
                status = "FAILED",
                dryRun = false,
                errorMessage = e.message
            )
            return false
        } finally {
            client.disconnect()
            syncMutexMap.remove(relationId)
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
        _progress.value = _progress.value.copy(
            activeDownloads = activeDownloadsMap.values.toList(),
            transferredBytes = transferredSoFar + activeTransferred,
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
        try {
            val relation = syncRelationRepository.getById(relationId) ?: return Pair(emptyList(), "未找到关联")
            val source = syncSourceRepository.getById(relation.syncSourceId) ?: return Pair(emptyList(), "未找到来源")
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
                    val localFile = File(videoItem.localAbsolutePath)
                    if (!localFile.exists()) continue

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
        } finally {
            syncMutexMap.remove(relationId)
        }
    }

    suspend fun executeDelete(relationId: Long, actions: List<SyncAction>): Boolean = getMutexForRelation(relationId).withLock {
        val relation = syncRelationRepository.getById(relationId) ?: return false
        val source = syncSourceRepository.getById(relation.syncSourceId) ?: return false
        val client = getClient(source.protocol)

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

            for (action in actions) {
                _progress.value = _progress.value.copy(
                    currentFileName = action.relativePath,
                    completedFiles = completedActions
                )

                val videoItem = videoRepository.getByRelativePath(action.relativePath)
                if (videoItem != null) {
                    val localFile = File(videoItem.localAbsolutePath)
                    if (localFile.exists()) localFile.delete()

                    if (videoItem.thumbnailPath != null) {
                        val thumbnailFile = File(videoItem.thumbnailPath)
                        if (thumbnailFile.exists()) thumbnailFile.delete()
                    }

                    playbackRepository.deleteByVideoId(videoItem.id)
                    videoRepository.deleteByRelativePathAndRelationId(action.relativePath, relationId)
                }

                val remoteRelPath = storagePaths.toPathUnderBase(relation.localBasePath, action.relativePath)
                client.deleteFile(remoteRelPath)

                syncLogRepository.log(
                    action = "DELETE_BOTH",
                    relativePath = action.relativePath,
                    status = "SUCCESS",
                    dryRun = false,
                    fileSize = action.fileSize
                )
                completedActions++
            }

            val localRoot = getLocalSyncRoot(relation)
            cleanupEmptyDirectories(localRoot)

            _progress.value = SyncProgress(
                isRunning = false,
                phase = SyncPhase.COMPLETED,
                completedFiles = totalActions,
                totalFiles = totalActions,
                relationId = relationId,
                relationName = relation.name,
                localBasePath = relation.localBasePath
            )

            syncRelationRepository.updateLastSync(relationId, "成功：删除 ${actions.size} 个文件")
            syncLogRepository.clearOldLogs()
            return true
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
            syncRelationRepository.updateLastSync(relationId, "删除失败: ${e.message}")
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
            syncMutexMap.remove(relationId)
        }
    }
}
