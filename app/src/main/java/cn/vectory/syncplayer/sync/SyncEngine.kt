package cn.vectory.syncplayer.sync

import android.content.Context
import cn.vectory.syncplayer.data.local.dao.PendingSyncActionDao
import cn.vectory.syncplayer.data.model.SyncAction
import cn.vectory.syncplayer.data.model.SyncProgress
import cn.vectory.syncplayer.data.repository.PlaybackRepository
import cn.vectory.syncplayer.data.repository.SyncLogRepository
import cn.vectory.syncplayer.data.repository.SyncRelationRepository
import cn.vectory.syncplayer.data.repository.SyncSourceRepository
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.storage.StoragePaths
import cn.vectory.syncplayer.storage.volume.EncryptedMediaStore
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
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

    private val syncMutexMap = ConcurrentHashMap<Long, Mutex>()

    /** Global mutex coordinating sync operations with storage reset/switch (prevents TOCTOU). */
    val storageOperationMutex = Mutex()

    private fun getMutexForRelation(relationId: Long): Mutex =
        syncMutexMap.computeIfAbsent(relationId) { Mutex() }

    // Pending task 簿记已迁入 SyncPendingTaskManager（Task 4）；锁编排保留在 Engine。
    private val pendingTaskManager = SyncPendingTaskManager(pendingSyncActionDao)

    // DELETE_BOTH 执行域已迁入 SyncDeleteExecutor（Task 5）；进度走共享 progressPublisher。
    private val deleteExecutor = SyncDeleteExecutor(
        syncRelationRepository, syncSourceRepository, videoRepository, playbackRepository,
        syncLogRepository, remoteFileClientFactory, storagePaths, encryptedMediaStoreLazy,
        context, progressPublisher
    )

    // 远端扫描域已迁入 SyncRemoteScanner（Task 6）；E8 守卫随迁，进度走共享 progressPublisher。
    private val remoteScanner = SyncRemoteScanner(
        syncRelationRepository, syncSourceRepository, videoRepository, playbackRepository,
        syncLogRepository, remoteFileClientFactory, storagePaths, context, progressPublisher
    )

    // 下载主管线已迁入 SyncDownloadPipeline（Task 7）；复用同一 deleteExecutor 与 progressPublisher。
    private val downloadPipeline = SyncDownloadPipeline(
        syncRelationRepository, syncSourceRepository, videoRepository, playbackRepository,
        syncLogRepository, videoMetadataExtractor, imageMetadataExtractor,
        remoteFileClientFactory, storagePaths, encryptedMediaStoreLazy, context,
        progressPublisher, deleteExecutor
    )

    suspend fun setPendingTask(task: PendingSyncTask) {
        storageOperationMutex.withLock {
            pendingTaskManager.setPendingTaskLocked(task)
        }
    }

    suspend fun consumePendingTask(relationId: Long): PendingSyncTask? = storageOperationMutex.withLock {
        pendingTaskManager.consumePendingTaskLocked(relationId)
    }

    /**
     * Atomically consume pending task + execute sync under [storageOperationMutex].
     * Eliminates TOCTOU race where reset/switch could intervene between consume and execute.
     * Called by [SyncService] instead of separate consumePendingTask + executeSync calls.
     */
    internal suspend fun consumeAndExecute(
        relationId: Long
    ): Pair<PendingSyncTask?, Boolean> = storageOperationMutex.withLock {
        val task = pendingTaskManager.consumePendingTaskLocked(relationId) ?: return@withLock null to false
        val success = try {
            executeSyncLocked(
                relationId, task.toDownload, task.toDelete,
                task.relationName, task.localBasePath
            )
        } catch (e: CancellationException) {
            // NonCancellable: ensure DAO cleanup executes even in cancelled coroutine state.
            pendingTaskManager.cancelCleanup(relationId)
            throw e
        }
        pendingTaskManager.finalize(relationId, success)
        task to success
    }

    val isSyncRunning: Boolean get() = progressPublisher.value.isRunning

    suspend fun dryRun(relationId: Long): DryRunResult = storageOperationMutex.withLock {
        getMutexForRelation(relationId).withLock { remoteScanner.dryRunLocked(relationId) }
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
        downloadPipeline.executeSyncLocked(relationId, toDownload, toDelete, relationName, localBasePath)
    }

    fun resetProgress() {
        progressPublisher.reset()
    }

    suspend fun dryRunDelete(relationId: Long): Pair<List<SyncAction>, String?> =
        getMutexForRelation(relationId).withLock { remoteScanner.dryRunDeleteLocked(relationId) }

    suspend fun executeDelete(relationId: Long, actions: List<SyncAction>): Boolean =
        storageOperationMutex.withLock { getMutexForRelation(relationId).withLock { deleteExecutor.executeDeleteLocked(relationId, actions) } }
}
