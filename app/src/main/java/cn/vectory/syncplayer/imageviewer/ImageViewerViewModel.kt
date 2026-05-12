package cn.vectory.syncplayer.imageviewer

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.vectory.syncplayer.data.local.entity.PlaybackRecordEntity
import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import cn.vectory.syncplayer.data.repository.PlaybackRepository
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.navigation.ImageQueue
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class ImageOverlay {
    data object Previous : ImageOverlay()
    data object Next : ImageOverlay()
    data object NoPrevious : ImageOverlay()
    data object NoNext : ImageOverlay()
    data class TrashAction(val isIn: Boolean) : ImageOverlay()
}

data class ImageViewerUiState(
    val currentImage: VideoItemEntity? = null,
    val playbackRecord: PlaybackRecordEntity? = null,
    val controlsVisible: Boolean = true,
    val currentOverlay: ImageOverlay? = null
)

@HiltViewModel
class ImageViewerViewModel @Inject constructor(
    private val videoRepository: VideoRepository,
    private val playbackRepository: PlaybackRepository,
    private val imageQueue: ImageQueue
) : ViewModel() {

    private val _uiState = MutableStateFlow(ImageViewerUiState())
    val uiState: StateFlow<ImageViewerUiState> = _uiState.asStateFlow()

    private val _allImageIds = MutableStateFlow<List<Long>>(emptyList())
    val allImageIds: StateFlow<List<Long>> = _allImageIds.asStateFlow()

    private var overlayHideJob: Job? = null

    init {
        val queueIds = imageQueue.consumeIds()
        if (queueIds.isNotEmpty()) {
            _allImageIds.value = queueIds
        }
    }

    fun loadImage(imageItemId: Long) {
        viewModelScope.launch {
            try {
                val image = videoRepository.getById(imageItemId)
                if (image != null) {
                    val record = playbackRepository.getByVideoId(image.id)
                    _uiState.update { it.copy(
                        currentImage = image,
                        playbackRecord = record,
                        currentOverlay = null
                    ) }
                    markCompleted(image.id)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load image $imageItemId", e)
            }
        }
    }

    fun getPrevImageId(): Long? {
        val ids = _allImageIds.value
        val currentId = _uiState.value.currentImage?.id ?: return null
        val idx = ids.indexOf(currentId)
        return if (idx > 0) ids[idx - 1] else null
    }

    fun getNextImageId(): Long? {
        val ids = _allImageIds.value
        val currentId = _uiState.value.currentImage?.id ?: return null
        val idx = ids.indexOf(currentId)
        return if (idx >= 0 && idx < ids.size - 1) ids[idx + 1] else null
    }

    fun toggleTrashed() {
        val imageId = _uiState.value.currentImage?.id ?: return
        viewModelScope.launch {
            try {
                playbackRepository.toggleTrashed(imageId)
                val record = playbackRepository.getByVideoId(imageId)
                _uiState.update { it.copy(playbackRecord = record) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to toggle trashed", e)
            }
        }
    }

    fun toggleControls() {
        _uiState.update { it.copy(controlsVisible = !it.controlsVisible) }
    }

    fun showOverlay(overlay: ImageOverlay) {
        _uiState.update { it.copy(currentOverlay = overlay) }
        overlayHideJob?.cancel()
        overlayHideJob = viewModelScope.launch {
            delay(750L)
            _uiState.update { it.copy(currentOverlay = null) }
        }
    }

    fun hideOverlay() {
        overlayHideJob?.cancel()
        _uiState.update { it.copy(currentOverlay = null) }
    }

    private fun markCompleted(imageId: Long) {
        viewModelScope.launch {
            try {
                playbackRepository.saveProgress(imageId, 1L, 1L)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to mark image completed", e)
            }
        }
    }

    companion object {
        private const val TAG = "ImageViewerViewModel"
    }
}
