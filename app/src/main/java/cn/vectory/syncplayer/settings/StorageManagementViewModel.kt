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
                if (refreshEpoch.get() != epoch) return@launch   // 乱序守卫：发布前自检代次
                _state.value = StorageState(
                    usableSpaceLabel = usableSpaceLabel,
                    mediaFileCount = count,
                    canReset = count > 0 && !syncRunning,
                    isResetting = _state.value.isResetting,
                    resetWarning = _state.value?.resetWarning,
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
                        val (d, f) = mediaFileStore.deleteAllMedia()
                        deleted = d
                        failed = f
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reset storage", e)
                _state.update { it.copy(isResetting = false, resetWarning = context.getString(R.string.storage_reset_failed)) }
            }
        }
    }

    companion object {
        private const val TAG = "StorageManagementVM"
    }
}
