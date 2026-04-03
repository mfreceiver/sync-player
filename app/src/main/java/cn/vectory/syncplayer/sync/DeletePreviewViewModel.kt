package cn.vectory.syncplayer.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.vectory.syncplayer.data.model.SyncAction
import cn.vectory.syncplayer.data.model.SyncProgress
import cn.vectory.syncplayer.data.repository.PlaybackRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DeletePreviewViewModel @Inject constructor(
    private val syncEngine: SyncEngine,
    private val playbackRepository: PlaybackRepository
) : ViewModel() {

    private val _toDelete = MutableStateFlow<List<SyncAction>>(emptyList())
    val toDelete: StateFlow<List<SyncAction>> = _toDelete.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _isDeleting = MutableStateFlow(false)
    val isDeleting: StateFlow<Boolean> = _isDeleting.asStateFlow()

    val progress: StateFlow<SyncProgress> = syncEngine.progress

    init {
        loadPreview()
    }

    fun loadPreview() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val (actions, errorMsg) = syncEngine.dryRunDelete()
                if (errorMsg != null) {
                    _error.value = errorMsg
                }
                _toDelete.value = actions
            } catch (e: Exception) {
                _error.value = "加载预览失败: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun startDelete() {
        viewModelScope.launch {
            _isDeleting.value = true
            try {
                syncEngine.executeDelete(_toDelete.value)
            } catch (e: Exception) {
                _error.value = "删除失败: ${e.message}"
            } finally {
                _isDeleting.value = false
            }
        }
    }

    val totalDeleteSize: Long
        get() = _toDelete.value.sumOf { it.fileSize }
}
