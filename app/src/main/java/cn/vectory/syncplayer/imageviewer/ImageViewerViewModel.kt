package cn.vectory.syncplayer.imageviewer

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.vectory.syncplayer.data.local.entity.PlaybackRecordEntity
import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import cn.vectory.syncplayer.browser.UndoDeleteUseCase
import cn.vectory.syncplayer.browser.UndoResult
import cn.vectory.syncplayer.data.repository.PlaybackRepository
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.navigation.ImageQueue
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
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
    private val imageQueue: ImageQueue,
    private val undoDeleteUseCase: UndoDeleteUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(ImageViewerUiState())
    val uiState: StateFlow<ImageViewerUiState> = _uiState.asStateFlow()

    private val _allImageIds = MutableStateFlow<List<Long>>(emptyList())
    val allImageIds: StateFlow<List<Long>> = _allImageIds.asStateFlow()

    /** Error flow for undo/delete operations — surfaced via Snackbar in the UI. */
    private val _imageErrorFlow = MutableStateFlow<String?>(null)

    /** Read-only view of [_imageErrorFlow] for the UI to collect. */
    val imageError: StateFlow<String?> = _imageErrorFlow.asStateFlow()

    /** Clear the image error after the UI has shown it. */
    fun clearError() {
        _imageErrorFlow.value = null
    }

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
            } catch (e: CancellationException) {
                throw e
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

    /**
     * Toggle trashed flag with undo support. [onSuccess] receives the image id captured at
     * call time — the caller uses it as the undo Snackbar's `expectedId` so a stale Snackbar
     * (after switching to the next image) never undoes a newer toggle on the new media.
     *
     * The playback-record refresh is launched separately in `viewModelScope` and only updates
     * `state.playbackRecord` if the currently-loaded image still matches [imageId] — switching
     * to the next item before the refresh completes will NOT overwrite the new item's record.
     *
     * A failed toggle sets [_imageErrorFlow] and never invokes [onSuccess].
     * CancellationException is propagated, never swallowed.
     */
    fun toggleTrashed(onSuccess: (Long) -> Unit = {}) {
        val imageId = _uiState.value.currentImage?.id ?: return
        undoDeleteUseCase.performTrashToggleNoGuard(imageId, viewModelScope, _imageErrorFlow) {
            // Refresh the playback record only if the user is still on this image.
            viewModelScope.launch {
                try {
                    val record = playbackRepository.getByVideoId(imageId)
                    _uiState.update { state ->
                        if (state.currentImage?.id == imageId) {
                            state.copy(playbackRecord = record)
                        } else {
                            // Switched to another image — do NOT overwrite the new item's record.
                            state
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to refresh playback record after toggle id=$imageId", e)
                }
            }
            // Surface the captured id (NOT the current state's id) so a stale Snackbar can never
            // undo a newer toggle on a different image.
            onSuccess(imageId)
        }
    }

    /**
     * Undo the most recent delete operation (trash toggle).
     * @param expectedId If provided, only undo if the current pending operation matches this id.
     * Returns the result so the caller can show appropriate feedback.
     */
    suspend fun undoDelete(expectedId: Long? = null): UndoResult = undoDeleteUseCase.undo(expectedId)

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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to mark image completed", e)
            }
        }
    }

    companion object {
        private const val TAG = "ImageViewerViewModel"
    }
}
