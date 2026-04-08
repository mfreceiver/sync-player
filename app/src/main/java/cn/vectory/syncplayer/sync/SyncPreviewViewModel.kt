package cn.vectory.syncplayer.sync

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.vectory.syncplayer.data.model.SyncAction
import cn.vectory.syncplayer.data.model.SyncPhase
import cn.vectory.syncplayer.data.model.SyncProgress
import cn.vectory.syncplayer.data.repository.SyncRelationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SyncPreviewViewModel @Inject constructor(
    private val syncEngine: SyncEngine,
    private val syncRelationRepository: SyncRelationRepository,
    @ApplicationContext private val context: Context,
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

    private val _syncComplete = MutableStateFlow(false)
    val syncComplete = _syncComplete.asStateFlow()

    private val initialTab: String = savedStateHandle.get<String>("initialTab") ?: "download"

    private val _syncMode = MutableStateFlow(
        if (initialTab == "delete") SyncMode.DELETE else SyncMode.DOWNLOAD
    )
    val syncMode = _syncMode.asStateFlow()

    private var deleteJob: kotlinx.coroutines.Job? = null

    val progress = syncEngine.progress

    val totalDownloadSize: Long get() = _toDownload.value.sumOf { it.fileSize }
    val totalDeleteSize: Long get() = _toDelete.value.sumOf { it.fileSize }

    init {
        val currentProgress = syncEngine.progress.value
        if (currentProgress.isRunning && currentProgress.relationId == relationId) {
            _isSyncing.value = true
            _previewLoaded.value = true
            observeSyncCompletion()
        }
    }

    fun setSyncMode(mode: SyncMode) {
        _syncMode.value = mode
    }

    fun loadPreview() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            _previewLoaded.value = false
            _syncComplete.value = false
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
            val relation = syncRelationRepository.getById(relationId) ?: return@launch
            syncEngine.setPendingTask(PendingSyncTask(
                relationId = relationId,
                relationName = relation.name,
                localBasePath = relation.localBasePath,
                toDownload = _toDownload.value,
                toDelete = _toDelete.value
            ))
            _isSyncing.value = true
            _error.value = null
            SyncService.start(context, relationId)
            observeSyncCompletion()
        }
    }

    private fun observeSyncCompletion() {
        viewModelScope.launch {
            val result = syncEngine.progress.first { p ->
                (p.relationId == relationId || p.relationId == 0L) &&
                    (p.phase == SyncPhase.COMPLETED || p.phase == SyncPhase.FAILED || p.phase == SyncPhase.CANCELLED)
            }
            _isSyncing.value = false
            _syncComplete.value = true
            if (result.phase == SyncPhase.COMPLETED) {
                _toDownload.value = emptyList()
                _toDelete.value = emptyList()
            }
        }
    }

    fun cancelSync() {
        if (_isSyncing.value) {
            SyncService.stop(context)
        }
        deleteJob?.cancel()
        deleteJob = null
    }

    fun startDelete() {
        deleteJob = viewModelScope.launch {
            val actions = _toDelete.value
            if (actions.isEmpty()) return@launch
            _isDeleting.value = true
            _error.value = null
            try {
                syncEngine.executeDelete(relationId, actions)
                _toDelete.value = emptyList()
                _toDownload.value = emptyList()
                _syncComplete.value = true
            } catch (e: Exception) {
                _error.value = "删除失败: ${e.message}"
            } finally {
                _isDeleting.value = false
            }
        }
    }
}
