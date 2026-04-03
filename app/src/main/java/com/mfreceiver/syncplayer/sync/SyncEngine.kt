package com.mfreceiver.syncplayer.sync

import android.content.Context
import com.mfreceiver.syncplayer.data.model.SyncAction
import com.mfreceiver.syncplayer.data.model.SyncActionType
import com.mfreceiver.syncplayer.data.model.SyncPhase
import com.mfreceiver.syncplayer.data.model.SyncProgress
import com.mfreceiver.syncplayer.data.model.SyncProtocol
import com.mfreceiver.syncplayer.data.local.entity.VideoItemEntity
import com.mfreceiver.syncplayer.data.repository.ServerConfigRepository
import com.mfreceiver.syncplayer.data.repository.VideoRepository
import com.mfreceiver.syncplayer.sync.smb.SmbFileClient
import com.mfreceiver.syncplayer.sync.sftp.SftpFileClient
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
                    toDownload.add(
                        SyncAction(
                            type = SyncActionType.DOWNLOAD,
                            relativePath = relPath,
                            fileSize = remoteFile.size,
                            remoteModifiedTime = remoteFile.lastModified
                        )
                    )
                }
            }

            val remotePaths = remoteFiles.filter { !it.isDirectory }.map { it.path }.toSet()
            for (localVideo in localVideos) {
                if (localVideo.relativePath !in remotePaths) {
                    toDelete.add(
                        SyncAction(
                            type = SyncActionType.DELETE_LOCAL,
                            relativePath = localVideo.relativePath,
                            fileSize = localVideo.fileSize
                        )
                    )
                }
            }

            _progress.value = SyncProgress(phase = SyncPhase.CONFIRMING)
        } catch (e: Exception) {
            _progress.value = SyncProgress(phase = SyncPhase.FAILED)
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
                    videoRepository.upsert(
                        VideoItemEntity(
                            relativePath = action.relativePath,
                            fileName = action.relativePath.substringAfterLast("/"),
                            localAbsolutePath = localFile.absolutePath,
                            fileSize = action.fileSize,
                            remoteModifiedTime = action.remoteModifiedTime,
                            dateSynced = System.currentTimeMillis()
                        )
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
            return false
        } finally {
            client.disconnect()
        }
    }

    fun resetProgress() {
        _progress.value = SyncProgress()
    }
}
