package cn.vectory.syncplayer.settings

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.vectory.syncplayer.R
import cn.vectory.syncplayer.data.local.dao.VideoItemDao
import cn.vectory.syncplayer.data.local.dao.PlaybackRecordDao
import cn.vectory.syncplayer.data.local.dao.PendingSyncActionDao
import cn.vectory.syncplayer.storage.MediaFileStore
import cn.vectory.syncplayer.di.IoDispatcher
import cn.vectory.syncplayer.sync.SyncEngine
import cn.vectory.syncplayer.sync.SyncStoragePort
import cn.vectory.syncplayer.sync.SyncVolumeInfo
import cn.vectory.syncplayer.ui.util.formatFileSize
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

data class StorageState(
    val usableSpaceLabel: String = "—",
    val mediaFileCount: Int = 0,
    val canReset: Boolean = false,
    val isResetting: Boolean = false,
    val resetWarning: String? = null,
    // 外部 SD v2：卷列表（默认存储位置区）与重置可用性（任一有数据的卷不可达 → 禁止全库重置）。
    val volumes: List<SyncVolumeInfo> = emptyList(),
    val setDefaultVolumeFailed: Boolean = false,
    val volumeOperationFailed: Boolean = false,
)

@HiltViewModel
class StorageManagementViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaFileStore: MediaFileStore,
    private val videoItemDao: VideoItemDao,
    private val playbackRecordDao: PlaybackRecordDao,
    private val pendingSyncActionDao: PendingSyncActionDao,
    private val syncEngine: SyncEngine,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    // 外部 SD v2：卷列表/默认卷/按卷 deleteAllOwned（legacy 端口委托内部卷 store）。
    private val storagePort: SyncStoragePort =
        cn.vectory.syncplayer.sync.LegacySyncStoragePort(
            dagger.Lazy { mediaFileStore }, ioDispatcher = ioDispatcher
        ),
) : ViewModel() {

    private val _state = MutableStateFlow(StorageState())
    val state: StateFlow<StorageState> = _state.asStateFlow()

    /**
     * refresh 代次失效（epoch）——发布顺序保证。每次 refresh 领取单调递增代次，
     * 任一时刻只有最新代次允许写 _state；旧代次在成功路径与降级路径的发布点都做
     * 代次比对，失配即丢弃，消灭"过期刷新覆盖"。
     */
    private val refreshEpoch = AtomicLong(0)

    init {
        refresh()
    }

    fun refresh() {
        val epoch = refreshEpoch.incrementAndGet()   // 领取代次；旧 refresh 发布权即时失效
        viewModelScope.launch {
            try {
                val count = withContext(ioDispatcher) {
                    try { videoItemDao.getTotalItemCount() } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to query file count", e)
                        Int.MAX_VALUE  // fail-closed: unknown → treat as "has files" → can reset only
                    }
                }
                val usableSpaceLabel = withContext(ioDispatcher) {
                    try {
                        val bytes = mediaFileStore.usableSpace()
                        if (bytes > 0L) formatFileSize(bytes) else "—"
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) { "—" }
                }
                val syncRunning = syncEngine.progress.value.isRunning
                // 外部 SD v2：卷列表 + 全部有数据的卷必须可达才允许重置（fail-closed：
                // 任一卷不可达 → canReset=false；绝不基于「可达卷」做部分重置）。
                val volumes = withContext(ioDispatcher) {
                    try {
                        storagePort.listVolumes()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) { emptyList() }
                }
                val allOwnedReachable = withContext(ioDispatcher) {
                    try {
                        storagePort.ownedVolumeIds().all { storagePort.isVolumeAvailable(it) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) { false }
                }
                if (refreshEpoch.get() != epoch) return@launch   // 乱序守卫：发布前自检代次
                _state.value = StorageState(
                    usableSpaceLabel = usableSpaceLabel,
                    mediaFileCount = count,
                    canReset = count > 0 && !syncRunning && allOwnedReachable,
                    isResetting = _state.value.isResetting,
                    resetWarning = _state.value?.resetWarning,
                    volumes = volumes,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to refresh storage state", e)
                if (refreshEpoch.get() != epoch) return@launch   // 旧失败不得覆盖新成功
                // 降级：状态不可知 → 禁用重置（fail-closed）；空间显示 "—"。
                _state.update { it.copy(usableSpaceLabel = "—", canReset = false) }
            }
        }
    }

    fun resetStorage() {
        viewModelScope.launch {
            _state.update { it.copy(isResetting = true, resetWarning = null) }
            try {
                var deleted = 0
                var failed = 0
                withContext(ioDispatcher) {
                    // 防并发同步（executeSync/dryRun/executeDelete 都持有同一 storageOperationMutex）。
                    syncEngine.storageOperationMutex.withLock {
                        // 外部 SD v2（安全红线）：重置前复核——任一有数据的卷不可达时
                        // 拒绝全库 reset（不删任何卷），提示用户接好所有存储卡后再试。
                        val owned = try {
                            storagePort.ownedVolumeIds()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) { null }
                        val unreachable = if (owned == null) {
                            setOf<Any>(Any())  // 读取失败 → 视为有不可达卷（fail-closed）
                        } else {
                            owned.filterNot { storagePort.isVolumeAvailable(it) }
                        }
                        if (unreachable.isNotEmpty()) {
                            throw VolumeUnreachableException()
                        }
                        // 外部 SD v2：deleteAllMedia → 按卷 deleteAllOwned(volumeId, datasetId)
                        //（legacy 端口为内部卷 store.deleteAllMedia()；双卷合入后逐卷执行）。
                        for (volumeId in (owned ?: emptySet())) {
                            val (d, f) = storagePort.deleteAllOwned(volumeId)
                            deleted += d
                            failed += f
                        }
                        if (failed > 0) Log.w(TAG, "Reset: $deleted deleted, $failed failed")
                        videoItemDao.deleteAll()
                        pendingSyncActionDao.deleteAll()
                        playbackRecordDao.deleteAll()
                    }
                }
                // Failure feedback: show warning if some files couldn't be deleted
                val warning = if (failed > 0) context.getString(R.string.storage_reset_partial, deleted, failed) else null
                _state.update { it.copy(isResetting = false, resetWarning = warning) }
                refresh()
            } catch (e: VolumeUnreachableException) {
                _state.update {
                    it.copy(
                        isResetting = false,
                        resetWarning = context.getString(R.string.storage_reset_volume_unavailable)
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reset storage", e)
                _state.update { it.copy(isResetting = false, resetWarning = context.getString(R.string.storage_reset_failed)) }
            }
        }
    }

    /** 内部信号：存在不可达卷，重置被拒绝（不用于 UI 文案判断）。 */
    private class VolumeUnreachableException : Exception()

    /**
     * 外部 SD v2：切换默认写入卷。仅影响后续同步的新文件；existing 行仍写回自身卷。
     * 端口拒绝（卷不存在/不可激活）时置 setDefaultVolumeFailed，由 UI 显示通用失败提示。
     */
    fun setDefaultVolume(volumeId: String) {
        viewModelScope.launch {
            val ok = withContext(ioDispatcher) {
                try {
                    storagePort.setDefaultWriteVolume(volumeId)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) { false }
            }
            _state.update { it.copy(setDefaultVolumeFailed = !ok) }
            refresh()
        }
    }

    fun dismissSetDefaultVolumeError() {
        _state.update { it.copy(setDefaultVolumeFailed = false) }
    }

    /**
     * 外部 SD v2：显式登记外部卷（能力检查通过才生效并持久化 uuid）。
     * 成功/失败都刷新卷列表；失败置 volumeOperationFailed。
     */
    fun registerExternalVolume() {
        viewModelScope.launch {
            val ok = withContext(ioDispatcher) {
                try {
                    storagePort.registerExternalVolume() != null
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) { false }
            }
            _state.update { it.copy(volumeOperationFailed = !ok) }
            refresh()
        }
    }

    /** 外部 SD v2：注销外部卷（默认卷被注销时端口回落内部卷）。 */
    fun unregisterExternalVolume(volumeId: String) {
        viewModelScope.launch {
            val ok = withContext(ioDispatcher) {
                try {
                    storagePort.unregisterExternalVolume(volumeId)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) { false }
            }
            _state.update { it.copy(volumeOperationFailed = !ok) }
            refresh()
        }
    }

    companion object {
        private const val TAG = "StorageManagementVM"
    }
}
