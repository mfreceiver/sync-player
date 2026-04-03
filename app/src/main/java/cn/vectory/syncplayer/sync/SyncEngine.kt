package cn.vectory.syncplayer.sync

import android.content.Context
import cn.vectory.syncplayer.data.model.SyncAction
import cn.vectory.syncplayer.data.model.SyncActionType
import cn.vectory.syncplayer.data.model.SyncPhase
import cn.vectory.syncplayer.data.model.SyncProgress
import cn.vectory.syncplayer.data.model.SyncProtocol
import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import cn.vectory.syncplayer.data.repository.PlaybackRepository
import cn.vectory.syncplayer.data.repository.ServerConfigRepository
import cn.vectory.syncplayer.data.repository.SyncLogRepository
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.sync.smb.SmbFileClient
import cn.vectory.syncplayer.sync.sftp.SftpFileClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncEngine @Inject constructor(
    private val serverConfigRepository: ServerConfigRepository,
    private val videoRepository: VideoRepository,
    private val playbackRepository: PlaybackRepository,
    private val syncLogRepository: SyncLogRepository,
    private val videoMetadataExtractor: VideoMetadataExtractor,
    private val smbFileClient: SmbFileClient,
    private val sftpFileClient: SftpFileClient,
    @ApplicationContext private val context: Context
) {

    private val _progress = MutableStateFlow(SyncProgress())
    val progress: StateFlow<SyncProgress> = _progress.asStateFlow()

    private val syncRootDir: File
        get() = File(context.filesDir, "synced").also { it.mkdirs() }

    private fun getClient(protocol: SyncProtocol): RemoteFileClient = when (protocol) {
        SyncProtocol.SMB -> smbFileClient
        SyncProtocol.SFTP -> sftpFileClient
    }

    suspend fun dryRun(): Pair<List<SyncAction>, List<SyncAction>> {
        val config = serverConfigRepository.getConfig()
            ?: return Pair(emptyList(), emptyList())

        val client = getClient(config.protocol)
        _progress.value = SyncProgress(phase = SyncPhase.SCANNING)

        val toDownload = mutableListOf<SyncAction>()
        val toDelete = mutableListOf<SyncAction>()

        try {
            client.connect(config)
            val remoteFiles = client.listFiles(config.remotePath)
            val localVideos = videoRepository.getAll()
            val localMap = localVideos.associateBy { it.relativePath }

            for (remoteFile in remoteFiles) {
                if (remoteFile.isDirectory) continue
                val relPath = remoteFile.path
                val local = localMap[relPath]
                val needsDownload = local == null ||
                    remoteFile.size != local.fileSize ||
                    remoteFile.lastModified != (local.remoteModifiedTime ?: 0)

                if (needsDownload) {
                    val action = SyncAction(
                        type = SyncActionType.DOWNLOAD,
                        relativePath = relPath,
                        fileSize = remoteFile.size,
                        remoteModifiedTime = remoteFile.lastModified
                    )
                    toDownload.add(action)
                    syncLogRepository.log(
                        action = "DOWNLOAD",
                        relativePath = relPath,
                        status = "PENDING",
                        dryRun = true,
                        fileSize = remoteFile.size
                    )
                } else {
                    syncLogRepository.log(
                        action = "SKIP",
                        relativePath = relPath,
                        status = "SKIPPED",
                        dryRun = true,
                        fileSize = remoteFile.size
                    )
                }
            }

            val remotePaths = remoteFiles.filter { !it.isDirectory }.map { it.path }.toSet()
            for (localVideo in localVideos) {
                if (localVideo.relativePath !in remotePaths) {
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

            _progress.value = SyncProgress(phase = SyncPhase.CONFIRMING)
        } catch (e: Exception) {
            _progress.value = SyncProgress(phase = SyncPhase.FAILED)
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
        toDownload: List<SyncAction>,
        toDelete: List<SyncAction>
    ): Boolean {
        val config = serverConfigRepository.getConfig() ?: return false
        val client = getClient(config.protocol)

        val totalActions = toDownload.size + toDelete.size
        var completedActions = 0
        var totalBytesTransferred = 0L

        _progress.value = SyncProgress(
            isRunning = true,
            totalFiles = totalActions,
            phase = SyncPhase.DOWNLOADING
        )

        try {
            client.connect(config)

            for (action in toDownload) {
                _progress.value = _progress.value.copy(
                    currentFileName = action.relativePath,
                    completedFiles = completedActions,
                    totalBytes = toDownload.sumOf { it.fileSize }
                )

                val localFile = File(syncRootDir, action.relativePath)
                val success = client.downloadFile(action.relativePath, localFile) { transferred, total ->
                    _progress.value = _progress.value.copy(
                        transferredBytes = totalBytesTransferred + transferred,
                        currentFileProgress = if (total > 0) transferred.toFloat() / total else 0f
                    )
                }

                if (success) {
                    totalBytesTransferred += action.fileSize
                    val entity = VideoItemEntity(
                        relativePath = action.relativePath,
                        fileName = action.relativePath.substringAfterLast("/"),
                        localAbsolutePath = localFile.absolutePath,
                        fileSize = action.fileSize,
                        remoteModifiedTime = action.remoteModifiedTime,
                        dateSynced = System.currentTimeMillis()
                    )
                    videoRepository.upsert(entity)
                    
                    val savedEntity = videoRepository.getByRelativePath(action.relativePath)
                    if (savedEntity != null) {
                        videoMetadataExtractor.extractAndSave(savedEntity)
                    }
                    
                    syncLogRepository.log(
                        action = "DOWNLOAD",
                        relativePath = action.relativePath,
                        status = "SUCCESS",
                        dryRun = false,
                        fileSize = action.fileSize
                    )
                } else {
                    syncLogRepository.log(
                        action = "DOWNLOAD",
                        relativePath = action.relativePath,
                        status = "FAILED",
                        dryRun = false,
                        fileSize = action.fileSize,
                        errorMessage = "Download failed"
                    )
                }
                completedActions++
            }

            _progress.value = _progress.value.copy(phase = SyncPhase.DELETING)

            for (action in toDelete) {
                _progress.value = _progress.value.copy(
                    currentFileName = action.relativePath,
                    completedFiles = completedActions
                )

                videoRepository.deleteByRelativePath(action.relativePath)
                val localFile = File(syncRootDir, action.relativePath)
                if (localFile.exists()) localFile.delete()
                
                syncLogRepository.log(
                    action = "DELETE_LOCAL",
                    relativePath = action.relativePath,
                    status = "SUCCESS",
                    dryRun = false,
                    fileSize = action.fileSize
                )
                completedActions++
            }

            _progress.value = SyncProgress(
                isRunning = false,
                phase = SyncPhase.COMPLETED,
                completedFiles = totalActions,
                totalFiles = totalActions,
                transferredBytes = totalBytesTransferred
            )

            serverConfigRepository.updateLastSync(
                "成功：下载 ${toDownload.size} 个，删除 ${toDelete.size} 个"
            )
            return true
        } catch (e: Exception) {
            _progress.value = SyncProgress(
                isRunning = false,
                phase = SyncPhase.FAILED
            )
            serverConfigRepository.updateLastSync("同步失败: ${e.message}")
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
        }
    }

    fun resetProgress() {
        _progress.value = SyncProgress()
    }

    suspend fun dryRunDelete(): Pair<List<SyncAction>, String?> {
        val config = serverConfigRepository.getConfig()
            ?: return Pair(emptyList(), "未配置服务器")

        val client = getClient(config.protocol)
        _progress.value = SyncProgress(phase = SyncPhase.SCANNING)

        val toDelete = mutableListOf<SyncAction>()

        try {
            client.connect(config)
            val remoteFiles = client.listFiles(config.remotePath)
            val remoteMap = remoteFiles.filter { !it.isDirectory }.associateBy { it.path }

            val favorites = playbackRepository.getFavorites()
            for (favorite in favorites) {
                val videoItem = videoRepository.getById(favorite.videoItemId) ?: continue
                val localFile = File(videoItem.localAbsolutePath)
                if (!localFile.exists()) continue

                val remoteFile = remoteMap[videoItem.relativePath]
                if (remoteFile != null && remoteFile.size == videoItem.fileSize) {
                    val action = SyncAction(
                        type = SyncActionType.DELETE_BOTH,
                        relativePath = videoItem.relativePath,
                        fileSize = videoItem.fileSize
                    )
                    toDelete.add(action)
                }
            }

            _progress.value = SyncProgress(phase = SyncPhase.CONFIRMING)
            return Pair(toDelete, null)
        } catch (e: Exception) {
            _progress.value = SyncProgress(phase = SyncPhase.FAILED)
            return Pair(emptyList(), e.message)
        } finally {
            client.disconnect()
        }
    }

    suspend fun executeDelete(actions: List<SyncAction>): Boolean {
        val config = serverConfigRepository.getConfig() ?: return false
        val client = getClient(config.protocol)

        val totalActions = actions.size
        var completedActions = 0

        _progress.value = SyncProgress(
            isRunning = true,
            totalFiles = totalActions,
            phase = SyncPhase.DELETING
        )

        try {
            client.connect(config)

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
                    videoRepository.deleteByRelativePath(action.relativePath)
                }

                client.deleteFile(action.relativePath)

                syncLogRepository.log(
                    action = "DELETE_BOTH",
                    relativePath = action.relativePath,
                    status = "SUCCESS",
                    dryRun = false,
                    fileSize = action.fileSize
                )
                completedActions++
            }

            _progress.value = SyncProgress(
                isRunning = false,
                phase = SyncPhase.COMPLETED,
                completedFiles = totalActions,
                totalFiles = totalActions
            )

            serverConfigRepository.updateLastSync(
                "成功：删除 ${actions.size} 个文件"
            )
            return true
        } catch (e: Exception) {
            _progress.value = SyncProgress(
                isRunning = false,
                phase = SyncPhase.FAILED
            )
            serverConfigRepository.updateLastSync("删除失败: ${e.message}")
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
}
