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
import cn.vectory.syncplayer.di.IoDispatcher
import cn.vectory.syncplayer.navigation.SAVED_VIDEO_IDS_KEY
import cn.vectory.syncplayer.navigation.VideoQueue
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlin.math.roundToInt

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

data class ErrorEvent(val message: String, val seq: Long)

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
    val isGestureSeeking: Boolean = false,
    val gestureSeekPosition: Float = 0f,
    val seekTarget: Long = -1L,
    val speedOverlayMultiplier: Float? = null,
    val isLongPressing: Boolean = false,
    val lastAutoSwitchTime: Long = 0L,
    val hasPlayerError: Boolean = false,
    val error: ErrorEvent? = null,
)

/**
 * High-frequency state used exclusively by the Aurora progress strip and its
 * preview host.  Keeping it outside [PlayerUiState] prevents a drag MOVE from
 * invalidating the complete player chrome.
 */
data class ProgressScrubUiState(
    val isActive: Boolean = false,
    val anchorPositionMs: Long = 0L,
    val targetPositionMs: Long = 0L,
    val isFineMode: Boolean = false,
    val factor: Float = 1f,
) {
    val deltaSeconds: Int
        get() = ((targetPositionMs - anchorPositionMs) / 1000.0).roundToInt()
}

@HiltViewModel
class PlayerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val videoRepository: VideoRepository,
    private val playbackRepository: PlaybackRepository,
    private val userPrefs: UserPreferencesRepository,
    private val videoQueue: VideoQueue,
    private val undoDeleteUseCase: UndoDeleteUseCase,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    /**
     * AND-001: progress persistence must NOT run on [viewModelScope]. On the
     * recents-swipe teardown path viewModelScope can already be cancelled when
     * the UI asks for a final save — a `viewModelScope.launch` is then a silent
     * no-op and playback position is lost. This scope is deliberately
     * independent of the ViewModel lifecycle (and intentionally not cancelled
     * in [onCleared]) so a just-launched save always completes.
     */
    private val progressSaveScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _progressScrubState = MutableStateFlow(ProgressScrubUiState())
    val progressScrubState: StateFlow<ProgressScrubUiState> = _progressScrubState.asStateFlow()

    private val errorSeq = AtomicLong(0L)

    /**
     * Adapter required by [UndoDeleteUseCase]'s existing String-based error callback API.
     * The use case assigns to [MutableStateFlow.value]; intercept that assignment so identical
     * consecutive messages still become distinct [ErrorEvent]s rather than being conflated by
     * the String state flow.
     */
    private val undoDeleteErrorState = MutableStateFlow<String?>(null)
    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    private val undoDeleteErrorFlow = object : MutableStateFlow<String?> by undoDeleteErrorState {
        override var value: String?
            get() = undoDeleteErrorState.value
            set(value) {
                undoDeleteErrorState.value = value
                value?.let(::showError)
            }
    }

    /** Clear the player error after the UI has shown it. */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /** Publish an already-mapped user-facing error as a distinct, one-shot UI event. */
    fun showError(message: String) {
        _uiState.update {
            it.copy(error = ErrorEvent(message, errorSeq.incrementAndGet()))
        }
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
                        gestureSeekPosition = 0f
                    ) }
                    _progressScrubState.value = ProgressScrubUiState()
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
        progressSaveScope.launch {
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
     * A failed toggle publishes an error event and never invokes [onSuccess].
     * CancellationException is propagated, never swallowed.
     */
    fun toggleTrashed(onSuccess: (Long) -> Unit = {}) {
        val videoId = _uiState.value.videoItem?.id ?: return
        undoDeleteUseCase.performTrashToggleNoGuard(videoId, viewModelScope, undoDeleteErrorFlow) {
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
        setFullscreen(!_uiState.value.isFullscreen)
    }

    fun setFullscreen(fullscreen: Boolean) {
        _uiState.update {
            if (fullscreen && !it.isFullscreen) {
                // Entering FULLSCREEN: hide the full controls inside the SAME atomic
                // state update, so no UI LaunchedEffect can observe isFullscreen=true
                // with a stale showControls=true (one-frame control-row flash).
                it.copy(isFullscreen = true, showControls = false)
            } else {
                // Exiting, or re-asserting while already fullscreen: keep the user's
                // current showControls value (they may have toggled it back on).
                it.copy(isFullscreen = fullscreen)
            }
        }
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

    fun onProgressScrubStarted(anchorPositionMs: Long) {
        val duration = _uiState.value.duration.coerceAtLeast(0L)
        val anchor = anchorPositionMs.coerceIn(0L, duration)
        _progressScrubState.value = ProgressScrubUiState(
            isActive = true,
            anchorPositionMs = anchor,
            targetPositionMs = anchor,
        )
        _uiState.update { it.copy(isUserSeeking = true) }
    }

    /** MOVE deliberately updates only the narrow progress state. */
    fun onProgressScrubChanged(
        targetPositionMs: Long,
        factor: Float,
        isFineMode: Boolean,
    ) {
        if (!_progressScrubState.value.isActive) return
        val duration = _uiState.value.duration.coerceAtLeast(0L)
        _progressScrubState.update {
            it.copy(
                targetPositionMs = targetPositionMs.coerceIn(0L, duration),
                factor = factor,
                isFineMode = isFineMode,
            )
        }
    }

    /** FINISH is the sole progress-bar path that commits playback position. */
    fun onProgressScrubFinished(targetPositionMs: Long) {
        val target = targetPositionMs.coerceIn(0L, _uiState.value.duration.coerceAtLeast(0L))
        _currentPosition.value = target
        _progressScrubState.value = ProgressScrubUiState()
        _uiState.update { it.copy(isUserSeeking = false, seekTarget = target) }
    }

    /** CANCEL rolls back the UI session without seeking or changing position. */
    fun onProgressScrubCancelled() {
        _progressScrubState.value = ProgressScrubUiState()
        _uiState.update { it.copy(isUserSeeking = false) }
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

    /**
     * AND-014: [targetMs] is the FINAL seek target already computed from the
     * frozen gesture-start base (`base + offset`) by the UI layer. This method
     * must NOT re-add the live [currentPosition] — the playback head keeps
     * advancing during the gesture and would skew the committed target away
     * from what the overlay previewed.
     */
    fun onGestureSeekFinished(targetMs: Long) {
        val duration = _uiState.value.duration
        val target = targetMs.coerceIn(0, duration.coerceAtLeast(0))
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
    }
}
