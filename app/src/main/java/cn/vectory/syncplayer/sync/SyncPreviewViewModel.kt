package cn.vectory.syncplayer.sync

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.vectory.syncplayer.R
import cn.vectory.syncplayer.data.model.SyncRelation
import cn.vectory.syncplayer.data.model.SyncAction
import cn.vectory.syncplayer.data.model.SyncOutcome
import cn.vectory.syncplayer.data.model.SyncPhase
import cn.vectory.syncplayer.data.model.SyncProgress
import cn.vectory.syncplayer.data.repository.SyncRelationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
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

    val relationId: Long = savedStateHandle.get<Long>("relationId") ?: 0L

    private val _allRelations = MutableStateFlow<List<SyncRelation>>(emptyList())
    val allRelations = _allRelations.asStateFlow()

    private val _currentRelationName = MutableStateFlow("")
    val currentRelationName = _currentRelationName.asStateFlow()

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
        viewModelScope.launch {
            try {
                _allRelations.value = syncRelationRepository.getAllWithSource().map { it.relation }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = syncErrorMessage(e, context)
            }
        }
        viewModelScope.launch {
            try {
                val relation = syncRelationRepository.getById(relationId)
                _currentRelationName.value = relation?.name ?: ""
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = syncErrorMessage(e, context)
            }
        }
    }

    fun setSyncMode(mode: SyncMode) {
        _syncMode.value = mode
        _syncComplete.value = false
        _previewLoaded.value = false
        _toDownload.value = emptyList()
        _toDelete.value = emptyList()
        _error.value = null
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
                    // F02 预览语义：最近一次完成的同步检查或执行结果——
                    // 成功扫描且确实无动作 → 更新 SUCCESS；有动作不覆盖上次终态；失败不写成功。
                    if (actions.isEmpty() && errMsg == null) {
                        syncRelationRepository.updateLastSync(relationId, SyncOutcome(SyncOutcome.Status.SUCCESS))
                        _syncComplete.value = true
                    }
                } else {
                    val result = syncEngine.dryRun(relationId)
                    _toDownload.value = result.toDownload
                    _toDelete.value = result.toDelete
                    // P1-1: 不再把 phase==FAILED 一律映射为固定「连接失败」。
                    // 用 dryRun 带回的真实根因走 syncErrorMessage 解析（AUTH/PATH_NOT_FOUND/SCAN_INCOMPLETE/UNKNOWN …）。
                    // 失败分支与「已同步」分支保持互斥（与原 if/else if 语义一致）。
                    if (result.error != null) {
                        _error.value = syncErrorMessage(result.error, context)
                    } else if (result.toDownload.isEmpty() && result.toDelete.isEmpty()) {
                        // F02 预览语义：同上——无动作才写 SUCCESS，扫描失败绝不写成功。
                        syncRelationRepository.updateLastSync(relationId, SyncOutcome(SyncOutcome.Status.SUCCESS))
                        _syncComplete.value = true
                    }
                }
                _previewLoaded.value = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = syncErrorMessage(e, context)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun startSync() {
        if (_isSyncing.value) return
        viewModelScope.launch {
            try {
                if (!syncEngine.progress.value.isRunning) {
                    syncEngine.resetProgress()
                }
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = syncErrorMessage(e, context)
            }
        }
    }

    private fun observeSyncCompletion() {
        viewModelScope.launch {
            try {
                val currentProgress = syncEngine.progress.value
                val isStaleTerminal = (currentProgress.relationId == relationId || currentProgress.relationId == 0L) &&
                    (currentProgress.phase == SyncPhase.COMPLETED ||
                        currentProgress.phase == SyncPhase.PARTIAL_FAILED ||
                        currentProgress.phase == SyncPhase.FAILED ||
                        currentProgress.phase == SyncPhase.CANCELLED)
                val flow = if (isStaleTerminal) syncEngine.progress.drop(1) else syncEngine.progress
                val result = flow.first { p ->
                    (p.relationId == relationId || p.relationId == 0L) &&
                        (p.phase == SyncPhase.COMPLETED || p.phase == SyncPhase.PARTIAL_FAILED || p.phase == SyncPhase.FAILED || p.phase == SyncPhase.CANCELLED)
                }
                _isSyncing.value = false
                if (result.phase == SyncPhase.COMPLETED) {
                    _toDownload.value = emptyList()
                    _toDelete.value = emptyList()
                } else if (result.phase == SyncPhase.PARTIAL_FAILED) {
                    _error.value = context.getString(R.string.sync_preview_partial_failed, result.failedFiles)
                } else if (result.phase == SyncPhase.FAILED) {
                    _error.value = context.getString(R.string.sync_preview_sync_failed)
                } else if (result.phase == SyncPhase.CANCELLED) {
                    _error.value = context.getString(R.string.sync_preview_cancelled)
                }
                _syncComplete.value = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = syncErrorMessage(e, context)
                _isSyncing.value = false
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

    fun clearError() {
        _error.value = null
    }

    fun startDelete() {
        deleteJob = viewModelScope.launch {
            val actions = _toDelete.value
            if (actions.isEmpty()) return@launch
            _isDeleting.value = true
            _error.value = null
            try {
                val success = syncEngine.executeDelete(relationId, actions)
                if (success) {
                    _toDelete.value = emptyList()
                    _toDownload.value = emptyList()
                    _syncComplete.value = true
                } else {
                    _error.value = context.getString(R.string.sync_preview_delete_failed)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = syncErrorMessage(e, context)
            } finally {
                _isDeleting.value = false
            }
        }
    }
}
