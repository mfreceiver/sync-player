package com.mfreceiver.syncplayer.sync

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mfreceiver.syncplayer.data.model.SyncAction
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

    val progress: StateFlow<SyncProgress> = syncEngine.progress

    init {
        loadPreview()
    }

    fun loadPreview() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val (downloads, deletions) = syncEngine.dryRun()
                _toDownload.value = downloads
                _toDelete.value = deletions
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
            try {
                syncEngine.executeSync(_toDownload.value, _toDelete.value)
            } catch (e: Exception) {
                _error.value = "同步失败: ${e.message}"
            } finally {
                _isSyncing.value = false
            }
        }
    }

    fun cancelSync() {
        syncEngine.resetProgress()
    }

    val totalDownloadSize: Long
        get() = _toDownload.value.sumOf { it.fileSize }

    val totalDeleteSize: Long
        get() = _toDelete.value.sumOf { it.fileSize }
}
