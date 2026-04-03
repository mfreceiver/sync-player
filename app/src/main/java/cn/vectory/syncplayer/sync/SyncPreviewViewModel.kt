package cn.vectory.syncplayer.sync

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.vectory.syncplayer.data.model.SyncAction
import cn.vectory.syncplayer.data.model.SyncPhase
import cn.vectory.syncplayer.data.model.SyncProgress
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SyncPreviewViewModel @Inject constructor(
    private val syncEngine: SyncEngine,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _toDownload = MutableStateFlow<List<SyncAction>>(emptyList())
    val toDownload: StateFlow<List<SyncAction>> = _toDownload.asStateFlow()

    private val _toDelete = MutableStateFlow<List<SyncAction>>(emptyList())
    val toDelete: StateFlow<List<SyncAction>> = _toDelete.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _isDeleting = MutableStateFlow(false)
    val isDeleting: StateFlow<Boolean> = _isDeleting.asStateFlow()

    private val _previewLoaded = MutableStateFlow(false)
    val previewLoaded: StateFlow<Boolean> = _previewLoaded.asStateFlow()

    private val _syncMode = MutableStateFlow(SyncMode.DOWNLOAD)
    val syncMode: StateFlow<SyncMode> = _syncMode.asStateFlow()

    val progress: StateFlow<SyncProgress> = syncEngine.progress

    private var initialDownloadCount = 0
    private var initialDeleteCount = 0

    fun loadPreview() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                if (_syncMode.value == SyncMode.DOWNLOAD) {
                    val (downloads, deletions) = syncEngine.dryRun()
                    _toDownload.value = downloads
                    _toDelete.value = deletions
                    initialDownloadCount = downloads.size
                    initialDeleteCount = deletions.size
                } else {
                    val (actions, error) = syncEngine.dryRunDelete()
                    if (error != null) {
                        _error.value = error
                    } else {
                        _toDelete.value = actions
                        initialDeleteCount = actions.size
                    }
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
        val downloads = _toDownload.value
        val deletes = _toDelete.value
        _isSyncing.value = true
        viewModelScope.launch {
            // Observe progress and shrink lists as items complete
            val progressJob = viewModelScope.launch {
                syncEngine.progress.collect { p ->
                    if (p.phase == SyncPhase.DOWNLOADING || p.phase == SyncPhase.DELETING) {
                        val completed = p.completedFiles
                        if (p.phase == SyncPhase.DOWNLOADING && completed <= initialDownloadCount) {
                            _toDownload.value = downloads.drop(completed)
                        }
                        if (p.phase == SyncPhase.DELETING) {
                            val downloadDone = initialDownloadCount
                            val deleteCompleted = completed - downloadDone
                            if (deleteCompleted >= 0) {
                                _toDelete.value = deletes.drop(deleteCompleted)
                            }
                        }
                    }
                }
            }
            try {
                syncEngine.executeSync(downloads, deletes)
            } catch (e: Exception) {
                _error.value = "同步失败: ${e.message}"
            } finally {
                progressJob.cancel()
                _isSyncing.value = false
                _toDownload.value = emptyList()
                _toDelete.value = emptyList()
            }
        }
    }

    fun startDelete() {
        val deletes = _toDelete.value
        _isDeleting.value = true
        viewModelScope.launch {
            val progressJob = viewModelScope.launch {
                syncEngine.progress.collect { p ->
                    if (p.phase == SyncPhase.DELETING) {
                        val completed = p.completedFiles
                        _toDelete.value = deletes.drop(completed)
                    }
                }
            }
            try {
                syncEngine.executeDelete(deletes)
            } catch (e: Exception) {
                _error.value = "删除失败: ${e.message}"
            } finally {
                progressJob.cancel()
                _isDeleting.value = false
                _toDelete.value = emptyList()
            }
        }
    }

    fun setSyncMode(mode: SyncMode) {
        _syncMode.value = mode
        _previewLoaded.value = false
        _toDownload.value = emptyList()
        _toDelete.value = emptyList()
        _error.value = null
    }

    fun cancelSync() {
        syncEngine.resetProgress()
    }

    val totalDownloadSize: Long
        get() = _toDownload.value.sumOf { it.fileSize }

    val totalDeleteSize: Long
        get() = _toDelete.value.sumOf { it.fileSize }
}
