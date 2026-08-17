package cn.vectory.syncplayer.player

import android.content.pm.ActivityInfo
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.vectory.syncplayer.data.local.entity.PlaybackRecordEntity
import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import cn.vectory.syncplayer.browser.UndoDeleteUseCase
import cn.vectory.syncplayer.browser.UndoResult
import cn.vectory.syncplayer.data.repository.PlaybackRepository
import cn.vectory.syncplayer.data.repository.UserPreferencesRepository
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.navigation.VideoQueue
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class PlayerOverlay {
    data class Seek(val positionMs: Long, val durationMs: Long) : PlayerOverlay()
    data class Volume(val percent: Int) : PlayerOverlay()
    data class Brightness(val percent: Int) : PlayerOverlay()
    data class SpeedUp(val multiplier: Float) : PlayerOverlay()
    data object Previous : PlayerOverlay()
    data object Next : PlayerOverlay()
    data object NoPrevious : PlayerOverlay()
    data object NoNext : PlayerOverlay()
    data class TrashAction(val isIn: Boolean) : PlayerOverlay()
    data class Orientation(val mode: OrientationMode) : PlayerOverlay()
}

/** Orientation mode for the player overlay. UI resolves display text from resources. */
enum class OrientationMode {
    FULLSCREEN,
    ROTATION,
}

data class PlayerUiState(
    val videoItem: VideoItemEntity? = null,
    val playbackRecord: PlaybackRecordEntity? = null,
    val isFullscreen: Boolean = false,
    val currentGainDb: Float = 0f,
    val playbackSpeed: Float = 1.0f,
    val volumeBalance: Boolean = false,
    val quickRecycle: Boolean = true,
    val duration: Long = 0L,
    val isPlaying: Boolean = false,
    val showControls: Boolean = true,
    val currentOverlay: PlayerOverlay? = null,
    val isUserSeeking: Boolean = false,
    val userSeekPosition: Float = 0f,
    val isGestureSeeking: Boolean = false,
    val gestureSeekPosition: Float = 0f,
    val seekTarget: Long = -1L,
    val speedOverlayMultiplier: Float? = null,
    val isLongPressing: Boolean = false,
    val lastAutoSwitchTime: Long = 0L,
    val hasPlayerError: Boolean = false
)

@HiltViewModel
class PlayerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val videoRepository: VideoRepository,
    private val playbackRepository: PlaybackRepository,
    private val userPrefs: UserPreferencesRepository,
    private val videoQueue: VideoQueue,
    private val undoDeleteUseCase: UndoDeleteUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    /** Error flow for undo/delete operations — surfaced via Snackbar in the UI. */
    private val _playerErrorFlow = MutableStateFlow<String?>(null)

    /** Read-only view of [_playerErrorFlow] for the UI to collect. */
    val playerError: StateFlow<String?> = _playerErrorFlow.asStateFlow()

    /** Clear the player error after the UI has shown it. */
    fun clearError() {
        _playerErrorFlow.value = null
    }

    private val _allVideoIds = MutableStateFlow<List<Long>>(emptyList())
    val allVideoIds: StateFlow<List<Long>> = _allVideoIds.asStateFlow()

    // Reactive read of the id list passed via SavedStateHandle (e.g. when nav
    // pushes a new folder context). The previous one-shot `get<String>` only
    // observed the value present at construction time.
    private val savedVideoIdsFlow: StateFlow<String?> =
        savedStateHandle.getStateFlow(SAVED_VIDEO_IDS_KEY, null)

    private var overlayHideJob: Job? = null

    init {
        loadFolderVideoIds()
        viewModelScope.launch {
            try {
                val vb = userPrefs.getVolumeBalance()
                val qr = userPrefs.getQuickRecycle()
                _uiState.update { it.copy(volumeBalance = vb, quickRecycle = qr) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load preferences", e)
            }
        }
    }

    private fun loadFolderVideoIds() {
        val queueIds = videoQueue.consumeIds()
        if (queueIds.isNotEmpty()) {
            _allVideoIds.value = queueIds
            return
        }
        // Observe SavedStateHandle reactively so a later nav update to
        // "videoIds" (e.g. folder change) re-feeds the queue without needing a
        // fresh PlayerScreen instance.
        viewModelScope.launch {
            savedVideoIdsFlow.collect { idsStr ->
                val ids = idsStr
                    ?.takeIf { it.isNotBlank() }
                    ?.split(",")
                    ?.mapNotNull { it.trim().toLongOrNull() }
                    ?: emptyList()
                if (ids.isNotEmpty()) {
                    _allVideoIds.value = ids
                } else if (_allVideoIds.value.isEmpty()) {
                    // DB fallback only while we still have nothing to show;
                    // later non-empty SavedStateHandle emissions still win.
                    try {
                        _allVideoIds.value = videoRepository.getAllIdsSorted()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to load video IDs", e)
                    }
                }
            }
        }
    }

    fun loadVideo(videoItemId: Long) {
        viewModelScope.launch {
            try {
                val video = videoRepository.getById(videoItemId)
                if (video != null) {
                    val record = playbackRepository.getByVideoId(video.id)
                    val gain = video.audioGainDb ?: 0f
                    val effectiveGain = if (_uiState.value.volumeBalance && video.audioGainDb != null) gain else 0f
                    _uiState.update { it.copy(
                        videoItem = video,
                        playbackRecord = record,
                        currentGainDb = effectiveGain,
                        duration = record?.duration ?: 0L,
                        seekTarget = -1L,
                        isUserSeeking = false,
                        isGestureSeeking = false,
                        userSeekPosition = 0f,
                        gestureSeekPosition = 0f
                    ) }
                    _currentPosition.value = record?.position ?: 0L
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load video $videoItemId", e)
            }
        }
    }

    fun updatePlaybackProgress(position: Long, duration: Long) {
        _currentPosition.value = position
        if (_uiState.value.duration != duration) {
            _uiState.update { it.copy(duration = duration) }
        }
    }

    fun setPlaying(playing: Boolean) {
        _uiState.update { it.copy(isPlaying = playing) }
    }

    fun saveProgress(position: Long, duration: Long) {
        val videoId = _uiState.value.videoItem?.id ?: return
        viewModelScope.launch {
            try {
                playbackRepository.saveProgress(videoId, position, duration)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save progress", e)
            }
        }
    }

    /**
     * Toggle trashed flag with undo support. [onSuccess] receives the video id captured at
     * call time — the caller uses it as the undo Snackbar's `expectedId` so a stale Snackbar
     * (after switching to the next video) never undoes a newer toggle on the new media.
     *
     * The playback-record refresh is launched separately in `viewModelScope` and only updates
     * `state.playbackRecord` if the currently-loaded video still matches [videoId] — switching
     * to the next item before the refresh completes will NOT overwrite the new item's record.
     *
     * A failed toggle sets [_playerErrorFlow] and never invokes [onSuccess].
     * CancellationException is propagated, never swallowed.
     */
    fun toggleTrashed(onSuccess: (Long) -> Unit = {}) {
        val videoId = _uiState.value.videoItem?.id ?: return
        undoDeleteUseCase.performTrashToggleNoGuard(videoId, viewModelScope, _playerErrorFlow) {
            // Refresh the playback record only if the user is still on this video.
            viewModelScope.launch {
                try {
                    val record = playbackRepository.getByVideoId(videoId)
                    _uiState.update { state ->
                        if (state.videoItem?.id == videoId) {
                            state.copy(playbackRecord = record)
                        } else {
                            // Switched to another video — do NOT overwrite the new item's record.
                            state
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to refresh playback record after toggle id=$videoId", e)
                }
            }
            // Surface the captured id (NOT the current state's id) so a stale Snackbar can never
            // undo a newer toggle on a different video.
            onSuccess(videoId)
        }
    }

    /**
     * Undo the most recent delete operation (trash toggle).
     * @param expectedId If provided, only undo if the current pending operation matches this id.
     * Returns the result so the caller can show appropriate feedback.
     */
    suspend fun undoDelete(expectedId: Long? = null): UndoResult = undoDeleteUseCase.undo(expectedId)

    fun getSavedPosition(): Long = _uiState.value.playbackRecord?.position ?: 0L

    fun getVideoUri(): String? = _uiState.value.videoItem?.let { cn.vectory.syncplayer.provider.MediaUris.mediaUri(it.id).toString() }

    fun toggleFullscreen() {
        _uiState.update { it.copy(isFullscreen = !it.isFullscreen) }
    }

    fun setFullscreen(fullscreen: Boolean) {
        _uiState.update { it.copy(isFullscreen = fullscreen) }
    }

    fun resolveOrientation(videoWidth: Int?, videoHeight: Int?): Int {
        if (!_uiState.value.isFullscreen) return ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        if (videoWidth != null && videoHeight != null && videoWidth > 0 && videoHeight > 0) {
            return if (videoWidth >= videoHeight) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
        return ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    fun getPrevVideoId(): Long? {
        val ids = _allVideoIds.value
        val currentId = _uiState.value.videoItem?.id ?: return null
        val idx = ids.indexOf(currentId)
        return if (idx > 0) ids[idx - 1] else null
    }

    fun getNextVideoId(): Long? {
        val ids = _allVideoIds.value
        val currentId = _uiState.value.videoItem?.id ?: return null
        val idx = ids.indexOf(currentId)
        return if (idx >= 0 && idx < ids.size - 1) ids[idx + 1] else null
    }

    fun setPlaybackSpeed(speed: Float) {
        _uiState.update { it.copy(playbackSpeed = speed) }
    }

    fun toggleControls() {
        _uiState.update { it.copy(showControls = !it.showControls) }
    }

    fun showOverlay(overlay: PlayerOverlay) {
        _uiState.update { it.copy(currentOverlay = overlay) }
        overlayHideJob?.cancel()
        overlayHideJob = viewModelScope.launch {
            delay(750L)
            _uiState.update { it.copy(currentOverlay = null) }
        }
    }

    fun hideOverlay() {
        overlayHideJob?.cancel()
        _uiState.update { it.copy(currentOverlay = null, speedOverlayMultiplier = null) }
    }

    fun setSpeedOverlay(multiplier: Float?) {
        _uiState.update { it.copy(speedOverlayMultiplier = multiplier) }
    }

    fun setLongPressing(longPressing: Boolean) {
        _uiState.update { it.copy(isLongPressing = longPressing) }
    }

    fun onUserSeekStarted() {
        _uiState.update { it.copy(isUserSeeking = true) }
    }

    fun onUserSeekChanged(position: Float) {
        _uiState.update { it.copy(userSeekPosition = position) }
    }

    fun onUserSeekFinished(position: Float) {
        val target = (position * _uiState.value.duration).toLong()
        _currentPosition.value = target
        _uiState.update { it.copy(isUserSeeking = false, seekTarget = target) }
    }

    fun onGestureSeekStarted() {
        _uiState.update { it.copy(isGestureSeeking = true) }
    }

    fun onGestureSeekChanged(positionMs: Long, durationMs: Long) {
        if (durationMs > 0) {
            val pos = (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
            _uiState.update { it.copy(isGestureSeeking = true, gestureSeekPosition = pos) }
        }
    }

    fun onGestureSeekFinished(offsetMs: Long) {
        val current = _currentPosition.value
        val duration = _uiState.value.duration
        val target = (current + offsetMs).coerceIn(0, duration.coerceAtLeast(0))
        _currentPosition.value = target
        _uiState.update { it.copy(isGestureSeeking = false, seekTarget = target) }
    }

    fun updateAutoSwitchTime() {
        _uiState.update { it.copy(lastAutoSwitchTime = System.currentTimeMillis()) }
    }

    fun clearSeekTarget() {
        _uiState.update { it.copy(seekTarget = -1L) }
    }

    /**
     * Marks a playback error in the UI state WITHOUT carrying the underlying
     * [androidx.media3.common.PlaybackException.message]. That message may
     * contain URI/path/provider/internal details and must never reach the UI.
     * Callers should log the raw exception for developer diagnostics.
     */
    fun onPlayerError() {
        _uiState.update { it.copy(hasPlayerError = true, isPlaying = false) }
    }

    fun clearPlayerError() {
        _uiState.update { it.copy(hasPlayerError = false) }
    }

    override fun onCleared() {
        super.onCleared()
    }

    companion object {
        private const val TAG = "PlayerViewModel"
        private const val SAVED_VIDEO_IDS_KEY = "videoIds"
    }
}
