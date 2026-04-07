package cn.vectory.syncplayer.sync

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.vectory.syncplayer.data.model.SyncAction
import cn.vectory.syncplayer.data.model.SyncProgress
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SyncPreviewViewModel @Inject constructor(
    private val syncEngine: SyncEngine,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val relationId: Long = savedStateHandle.get<Long>("relationId") ?: 0L

    private val _toDownload = MutableStateFlow<List<SyncAction>>(emptyList())
    val toDownload = _toDownload.asStateFlow()

    private val _toDelete = MutableStateFlow<List<SyncAction>>(emptyList())
    val toDelete = _toDelete.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing = _isSyncing.asStateFlow()

    private val _isDeleting = MutableStateFlow(false)
    val isDeleting = _isDeleting.asStateFlow()

    private val _previewLoaded = MutableStateFlow(false)
    val previewLoaded = _previewLoaded.asStateFlow()

    private val _syncMode = MutableStateFlow(SyncMode.DOWNLOAD)
    val syncMode = _syncMode.asStateFlow()

    val progress = syncEngine.progress

    val totalDownloadSize: Long get() = _toDownload.value.sumOf { it.fileSize }
    val totalDeleteSize: Long get() = _toDelete.value.sumOf { it.fileSize }

    fun setSyncMode(mode: SyncMode) {
        _syncMode.value = mode
    }

    fun loadPreview() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            _previewLoaded.value = false
            try {
                syncEngine.resetProgress()
                if (_syncMode.value == SyncMode.DELETE) {
                    val (actions, errMsg) = syncEngine.dryRunDelete(relationId)
                    if (errMsg != null) {
                        _error.value = errMsg
                    }
                    _toDownload.value = emptyList()
                    _toDelete.value = actions
                } else {
                    val (downloads, deletes) = syncEngine.dryRun(relationId)
                    _toDownload.value = downloads
                    _toDelete.value = deletes
                }
                _previewLoaded.value = true
            } catch (e: Exception) {
                _error.value = "加载预览失败: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun startSync() {
        viewModelScope.launch {
            _isSyncing.value = true
            _error.value = null
            try {
                syncEngine.executeSync(relationId, _toDownload.value, _toDelete.value)
                // Auto-refresh preview to show actual sync results
                syncEngine.resetProgress()
                val (downloads, deletes) = syncEngine.dryRun(relationId)
                _toDownload.value = downloads
                _toDelete.value = deletes
            } catch (e: Exception) {
                _error.value = "同步失败: ${e.message}"
            } finally {
                _isSyncing.value = false
            }
        }
    }

    fun startDelete() {
        viewModelScope.launch {
            _isDeleting.value = true
            _error.value = null
            try {
                val (actions, errMsg) = syncEngine.dryRunDelete(relationId)
                if (errMsg != null) {
                    _error.value = errMsg
                    return@launch
                }
                _toDelete.value = actions
                syncEngine.executeDelete(relationId, actions)
            } catch (e: Exception) {
                _error.value = "删除失败: ${e.message}"
            } finally {
                _isDeleting.value = false
            }
        }
    }
}
