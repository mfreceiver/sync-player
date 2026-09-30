package cn.vectory.syncplayer.player

import android.content.pm.ActivityInfo
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.vectory.syncplayer.data.local.entity.PlaybackRecordEntity
import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import cn.vectory.syncplayer.browser.UndoDeleteUseCase
import cn.vectory.syncplayer.browser.safeErrorDetail
import cn.vectory.syncplayer.browser.UndoResult
import cn.vectory.syncplayer.data.repository.PlaybackRepository
import cn.vectory.syncplayer.data.repository.UserPreferencesRepository
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.di.IoDispatcher
import cn.vectory.syncplayer.data.model.OrientationPreference
import cn.vectory.syncplayer.navigation.SAVED_VIDEO_IDS_KEY
import cn.vectory.syncplayer.navigation.VideoQueue
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
    val isUserSeeking: Boolean = false,
    val seekTarget: Long = -1L,
    val isLongPressing: Boolean = false,
    val lastAutoSwitchTime: Long = 0L,
    val hasPlayerError: Boolean = false,
    val error: ErrorEvent? = null,
    // BUG-019: structured queue-exhaustion terminal — normal EOF semantics, not an
    // error (no ErrorEvent, no Context-derived copy). UI consumes it and exits.
    val queueExhausted: Boolean = false,
)

data class GestureSeekUiState(
    val isSeeking: Boolean = false,
    val seekPositionFraction: Float = 0f,
    val targetMs: Long = 0L,
)

enum class SeekPhase { Idle, Dragging, Settling }
// Legacy values remain for the separately owned preview host; live seek uses Full only.
enum class SeekPrecision(val factor: Float) { Full(1f), Quarter(0.25f), Tenth(0.1f) }
enum class SeekFrame { CurrentBucket, Pending, Failed, Suppressed }
enum class SeekInput { Track, Gesture }

/** All positions are milliseconds; CurrentBucket means an approximate 500ms bucket, not a PTS. */
data class SeekPresentation(
    val mediaGeneration: Long = 0L,
    val session: Long = 0L,
    val phase: SeekPhase = SeekPhase.Idle,
    val input: SeekInput? = null,
    val targetMs: Long = 0L,
    val durationMs: Long = 0L,
    val anchorMs: Long = 0L,
    val precision: SeekPrecision = SeekPrecision.Full,
    val precisionExplicit: Boolean = false,
    val fineActivated: Boolean = false,
    val frame: SeekFrame = SeekFrame.Suppressed,
    val commitId: Long? = null,
)

/** Captured at seekTo dispatch, never reconstructed from mutable state in a callback. */
data class SeekCommitRecord(
    val mediaGeneration: Long,
    val session: Long,
    val commitId: Long,
    val targetMs: Long,
)

data class PlayerOverlayUiState(
    val overlay: PlayerOverlay? = null,
    val speedMultiplier: Float? = null,
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
    private val savedStateHandle: SavedStateHandle,
    private val videoRepository: VideoRepository,
    private val playbackRepository: PlaybackRepository,
    private val userPrefs: UserPreferencesRepository,
    private val videoQueue: VideoQueue,
    private val undoDeleteUseCase: UndoDeleteUseCase,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    /**
     * AND-001 / F-003: progress persistence must NOT run on [viewModelScope]. On the
     * recents-swipe teardown path viewModelScope can already be cancelled when
     * the UI asks for a final save — a `viewModelScope.launch` is then a silent
     * no-op and playback position is lost.
     *
     * To prevent leaking coroutines and ViewModel references after [onCleared],
     * [onCleared] closes [progressSaveChannel]. The consumer loop drains all already-queued
     * saves (including the final teardown save), executes them sequentially to completion,
     * and cancels [progressSaveScope] upon loop termination.
     *
     * The drain is bounded: each individual save is capped by [saveTimeoutMs]. A save
     * that exceeds the bound is abandoned (logged) and the consumer proceeds to the next
     * queued item — a permanently-hung repository call can never park the consumer, and
     * the ViewModel reference it captures, forever.
     */
    private val progressSaveScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    /**
     * Upper bound for a single progress save, read at each save's execution time.
     * Instance-level (not const) so tests can inject a small value before enqueueing.
     */
    @Volatile
    @VisibleForTesting
    internal var saveTimeoutMs: Long = 5_000L

    private data class PendingProgressSave(val videoId: Long, val position: Long, val duration: Long)

    // BUG-002: single consumer serializes all progress saves issued by THIS VM instance;
    // FIFO order == call order. Each save runs in its own child job of the consumer so a
    // CancellationException escaping one repository call cancels only that child —
    // the consumer loop keeps serving the queue (oracle #1: CE 不得杀死唯一消费者).
    private val progressSaveChannel = Channel<PendingProgressSave>(Channel.UNLIMITED)

    private val consumerJob: Job = progressSaveScope.launch {
        try {
            for (save in progressSaveChannel) {
                val job = launch {                       // 每次保存一个子 Job（父=消费者协程）
                    try {
                        // saveProgress returns Unit, so a null result here can only mean
                        // the timeout fired — the hung call is abandoned. An external
                        // (scope/job) cancellation is NOT swallowed by withTimeoutOrNull:
                        // it rethrows the foreign CancellationException, handled below.
                        val saved = withTimeoutOrNull(saveTimeoutMs) {
                            playbackRepository.saveProgress(save.videoId, save.position, save.duration)
                        }
                        if (saved == null) {
                            // No exception object on timeout — fixed text only, no
                            // paths or underlying messages (security red line #4).
                            Log.w(TAG, "Progress save timed out after ${saveTimeoutMs}ms (videoId=${save.videoId})")
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to save progress: ${safeErrorDetail(e)}")
                    }
                }
                job.join()                               // 上一保存结束（成功/失败/超时放弃）才取下一项：完成序 == 入队序，单次以 saveTimeoutMs 为上界
            }
        } finally {
            progressSaveScope.cancel()
        }
    }

    @get:VisibleForTesting
    internal val isProgressSaveScopeActive: Boolean
        get() = progressSaveScope.isActive

    @get:VisibleForTesting
    internal val isProgressSaveConsumerCompleted: Boolean
        get() = consumerJob.isCompleted

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _progressScrubState = MutableStateFlow(ProgressScrubUiState())
    private val _seekPresentation = MutableStateFlow(SeekPresentation())
    val seekPresentation: StateFlow<SeekPresentation> = _seekPresentation.asStateFlow()
    private var seekSequence = 0L
    private var commitSequence = 0L
    val pendingSeekCommit: SeekCommitRecord?
        get() = _seekPresentation.value.let { state ->
            state.commitId?.takeIf { state.phase == SeekPhase.Settling }?.let {
                SeekCommitRecord(state.mediaGeneration, state.session, it, state.targetMs)
            }
        }
    val progressScrubState: StateFlow<ProgressScrubUiState> = _progressScrubState.asStateFlow()

    private val _gestureSeekState = MutableStateFlow(GestureSeekUiState())
    val gestureSeekState: StateFlow<GestureSeekUiState> = _gestureSeekState.asStateFlow()

    private val _overlayState = MutableStateFlow(PlayerOverlayUiState())
    val overlayState: StateFlow<PlayerOverlayUiState> = _overlayState.asStateFlow()

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
    fun clearError(expectedSeq: Long? = null) {
        // AND-068: seq-guarded clear — see BrowserViewModel.clearError.
        _uiState.update { s ->
            if (expectedSeq == null || s.error?.seq == expectedSeq) s.copy(error = null) else s
        }
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

    // AND-089: loaded once in init; resolveOrientation() is synchronous and
    // called during composition, so it can't suspend to read the pref.
    @Volatile private var orientationPref = OrientationPreference.FOLLOW_VIDEO

    private var overlayHideJob: Job? = null

    init {
        loadFolderVideoIds()
        viewModelScope.launch {
            try {
                val vb = userPrefs.getVolumeBalance()
                val qr = userPrefs.getQuickRecycle()
                orientationPref = userPrefs.getOrientation() ?: OrientationPreference.FOLLOW_VIDEO
                _uiState.update { it.copy(volumeBalance = vb, quickRecycle = qr) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load preferences: ${safeErrorDetail(e)}")
            }
        }
    }

    private fun loadFolderVideoIds() {
        // AND-071: consume only a payload anchored to this entry's target id;
        // a mismatched payload belongs to a newer navigation and is left alone.
        val anchorId = savedStateHandle.get<Long>("videoItemId") ?: 0L
        val queueIds = videoQueue.consumeIds(anchorId)
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
                        Log.e(TAG, "Failed to load video IDs: ${safeErrorDetail(e)}")
                    }
                }
            }
        }
    }

    // BUG-015: load request generation — a stale request resuming after ANY
    // suspension point must never overwrite a newer request's committed state.
    private val loadGeneration = AtomicLong(0L)

    data class PendingRestore(
        val videoId: Long,
        val position: Long,
        val generation: Long,
    )

    private val _pendingRestore = MutableStateFlow<PendingRestore?>(null)
    val pendingRestore: StateFlow<PendingRestore?> = _pendingRestore.asStateFlow()

    @Volatile
    private var inFlightSwitchGeneration: Long? = null

    fun loadVideo(videoItemId: Long) {
        val generation = loadGeneration.incrementAndGet()   // 发起即占代
        val isSameVideo = (videoItemId == _uiState.value.videoItem?.id && _uiState.value.videoItem != null)

        if (!isSameVideo) {
            cancelSeek()
            _seekPresentation.update { it.copy(mediaGeneration = generation) }
            inFlightSwitchGeneration = generation
            _currentPosition.value = 0L
            _pendingRestore.value = null
            _uiState.update { it.copy(isPlaying = false) }
        }
        viewModelScope.launch {
            try {
                // A request whose body only starts after a newer request was issued is
                // already stale — its commit would be dropped at the guard below, and its
                // start-of-run flag clear could erase a terminal the newer request owns.
                if (generation != loadGeneration.get()) {
                    if (inFlightSwitchGeneration == generation) {
                        inFlightSwitchGeneration = null
                    }
                    return@launch
                }
                _uiState.update { it.copy(queueExhausted = false) }   // 每次加载先清终态
                var candidateId = videoItemId
                var video = videoRepository.getById(candidateId)
                // BUG-019: the queue snapshot may reference ids that background sync has since
                // deleted — skip forward to the next id that still exists; on exhaustion publish
                // a structured terminal so the UI exits exactly like normal EOF.
                val ids = _allVideoIds.value
                var idx = ids.indexOf(candidateId)
                while (video == null && idx in 0 until ids.size - 1) {
                    candidateId = ids[++idx]
                    video = videoRepository.getById(candidateId)
                }
                if (video != null) {
                    val record = playbackRepository.getByVideoId(video.id)
                    if (generation != loadGeneration.get()) {
                        if (inFlightSwitchGeneration == generation) {
                            inFlightSwitchGeneration = null
                        }
                        return@launch   // BUG-015: 旧代次迟到，丢弃
                    }

                    // R3: Clear any switch gate <= current committing generation
                    val gate = inFlightSwitchGeneration
                    if (gate != null && gate <= generation) {
                        inFlightSwitchGeneration = null
                    }

                    // N1 / AND-039 / R4: determine restore target strictly:
                    // - Recreation of same video: restored via live position (level 2 of consumeRestorePosition)
                    // - Switch to different video or cold start: restore DB record (never live position of previous video)
                    if (!isSameVideo) {
                        val targetPosition = record?.position ?: 0L
                        _pendingRestore.value = PendingRestore(video.id, targetPosition, generation)
                        _currentPosition.value = targetPosition
                    }

                    val gain = video.audioGainDb ?: 0f
                    val effectiveGain = if (_uiState.value.volumeBalance && video.audioGainDb != null) gain else 0f
                    _uiState.update { it.copy(
                        videoItem = video,
                        playbackRecord = record,
                        currentGainDb = effectiveGain,
                        duration = record?.duration ?: 0L,
                        seekTarget = -1L,
                        isUserSeeking = false,
                    ) }
                    _progressScrubState.value = ProgressScrubUiState()
                    _gestureSeekState.value = GestureSeekUiState()
                    _overlayState.value = PlayerOverlayUiState()
                } else {
                    if (generation != loadGeneration.get()) {
                        if (inFlightSwitchGeneration == generation) {
                            inFlightSwitchGeneration = null
                        }
                        return@launch   // BUG-015：耗尽发布同样受代次门控
                    }
                    val gate = inFlightSwitchGeneration
                    if (gate != null && gate <= generation) {
                        inFlightSwitchGeneration = null
                    }
                    // Queue exhausted (or id not in queue at all) — keep the current video and
                    // publish the structured terminal; the UI consumes it and exits. No
                    // ErrorEvent (this is normal EOF, not an error) and no Context-derived copy
                    // (i18n: structured state carries the semantics, UI layer owns wording).
                    _uiState.update { it.copy(queueExhausted = true) }
                    Log.w(TAG, "loadVideo: target gone and no later queue item exists")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val gate = inFlightSwitchGeneration
                if (gate != null && gate <= generation) {
                    inFlightSwitchGeneration = null
                }
                Log.e(TAG, "Failed to load video: ${safeErrorDetail(e)}")
            } finally {
                if (loadGeneration.get() == generation) {
                    val gate = inFlightSwitchGeneration
                    if (gate != null && gate <= generation) {
                        inFlightSwitchGeneration = null
                    }
                }
            }
        }
    }

    fun updatePlaybackProgress(position: Long, duration: Long, videoId: Long? = null) {
        val currentVideo = _uiState.value.videoItem
        if (videoId != null && currentVideo != null && videoId != currentVideo.id) {
            return
        }
        if (inFlightSwitchGeneration != null) {
            return
        }
        if (_seekPresentation.value.phase != SeekPhase.Settling) _currentPosition.value = position
        if (_uiState.value.duration != duration) {
            _uiState.update { it.copy(duration = duration) }
        }
    }

    fun setPlaying(playing: Boolean) {
        _uiState.update { it.copy(isPlaying = playing) }
    }

    fun saveProgress(position: Long, duration: Long, videoId: Long? = null) {
        val targetId = videoId ?: run {
            if (inFlightSwitchGeneration != null) return
            _uiState.value.videoItem?.id ?: return
        }
        progressSaveChannel.trySend(PendingProgressSave(targetId, position, duration))
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
                    Log.e(TAG, "Failed to refresh playback record after toggle: ${safeErrorDetail(e)}")
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

    fun consumeRestorePosition(videoId: Long): Long {
        // 1. Pending restore set on loadVideo commit (matches requested videoId)
        val pending = _pendingRestore.value
        if (pending != null && pending.videoId == videoId) {
            _pendingRestore.value = null
            return pending.position
        }
        // 2. Live position in memory (AND-039 recreation scenario: swap effect runs before load commit)
        // Must be scoped to current video matching videoId and live position > 0
        val currentVideo = _uiState.value.videoItem
        val live = _currentPosition.value
        if (currentVideo != null && currentVideo.id == videoId && live > 0L) {
            return live
        }
        // 3. Fallback to DB record snapshot — strictly scoped to requested videoId
        val record = _uiState.value.playbackRecord
        if (record != null && record.videoItemId == videoId) {
            return record.position
        }
        // 4. Default to 0 (no match / across-video switch in-flight)
        return 0L
    }

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
        // AND-089: FOLLOW_SYSTEM means "don't force an orientation change" —
        // the player stays under system rotation control even in fullscreen.
        if (orientationPref == OrientationPreference.FOLLOW_SYSTEM ||
            !_uiState.value.isFullscreen
        ) {
            return ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
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
        _overlayState.update { it.copy(overlay = overlay) }
        overlayHideJob?.cancel()
        overlayHideJob = viewModelScope.launch {
            delay(750L)
            _overlayState.update { it.copy(overlay = null) }
        }
    }

    fun hideOverlay() {
        overlayHideJob?.cancel()
        _overlayState.update { it.copy(overlay = null, speedMultiplier = null) }
    }

    fun setSpeedOverlay(multiplier: Float?) {
        _overlayState.update { it.copy(speedMultiplier = multiplier) }
    }

    fun setLongPressing(longPressing: Boolean) {
        _uiState.update { it.copy(isLongPressing = longPressing) }
    }

    private fun beginSeek(input: SeekInput, positionMs: Long): Long {
        val duration = _uiState.value.duration.coerceAtLeast(0L)
        val anchor = positionMs.coerceIn(0L, duration)
        _seekPresentation.value = SeekPresentation(
            mediaGeneration = _seekPresentation.value.mediaGeneration,
            session = ++seekSequence, phase = SeekPhase.Dragging, input = input,
            targetMs = anchor, anchorMs = anchor, durationMs = duration,
        )
        _progressScrubState.value = ProgressScrubUiState()
        _gestureSeekState.value = GestureSeekUiState()
        _uiState.update { it.copy(isUserSeeking = input == SeekInput.Track, seekTarget = -1L) }
        return seekSequence
    }

    private fun ownsSeek(input: SeekInput, session: Long): Boolean =
        _seekPresentation.value.let { it.phase == SeekPhase.Dragging && it.input == input && it.session == session }

    fun onProgressScrubStarted(anchorPositionMs: Long): Long {
        val session = beginSeek(SeekInput.Track, anchorPositionMs)
        val duration = _uiState.value.duration.coerceAtLeast(0L)
        val anchor = anchorPositionMs.coerceIn(0L, duration)
        _progressScrubState.value = ProgressScrubUiState(
            isActive = true,
            anchorPositionMs = anchor,
            targetPositionMs = anchor,
        )
        _uiState.update { it.copy(isUserSeeking = true) }
        return session
    }

    /** MOVE deliberately updates only the narrow progress state. */
    @Suppress("UNUSED_PARAMETER") // Legacy callers can no longer enable fine seek.
    fun onProgressScrubChanged(
        targetPositionMs: Long,
        factor: Float,
        isFineMode: Boolean,
        session: Long = _seekPresentation.value.session,
    ) {
        if (!ownsSeek(SeekInput.Track, session)) return
        val duration = _uiState.value.duration.coerceAtLeast(0L)
        _seekPresentation.update { it.copy(
            targetMs = targetPositionMs.coerceIn(0L, duration),
            precision = SeekPrecision.Full,
            fineActivated = false,
            frame = SeekFrame.Pending,
        ) }
        _progressScrubState.update {
            it.copy(
                targetPositionMs = targetPositionMs.coerceIn(0L, duration),
                factor = SeekPrecision.Full.factor,
                isFineMode = false,
            )
        }
    }

    /** FINISH is the sole progress-bar path that commits playback position. */
    fun onProgressScrubFinished(targetPositionMs: Long, session: Long = _seekPresentation.value.session): Long? {
        if (!ownsSeek(SeekInput.Track, session)) return null
        onProgressScrubChanged(targetPositionMs, _seekPresentation.value.precision.factor, _seekPresentation.value.fineActivated, session)
        return commitSeek()
    }

    /** CANCEL rolls back the UI session without seeking or changing position. */
    fun onProgressScrubCancelled(session: Long = _seekPresentation.value.session) {
        if (!ownsSeek(SeekInput.Track, session)) return
        cancelSeek()
    }

    fun cancelSeek() {
        _seekPresentation.update { it.copy(phase = SeekPhase.Idle, frame = SeekFrame.Suppressed, commitId = null) }
        _progressScrubState.value = ProgressScrubUiState()
        _gestureSeekState.value = GestureSeekUiState()
        _uiState.update { it.copy(isUserSeeking = false, seekTarget = -1L) }
    }

    fun onGestureSeekStarted(anchorMs: Long = _currentPosition.value): Long {
        val session = beginSeek(SeekInput.Gesture, anchorMs)
        _gestureSeekState.update { it.copy(isSeeking = true) }
        return session
    }

    fun onGestureSeekChanged(positionMs: Long, durationMs: Long, session: Long = _seekPresentation.value.session) {
        if (!ownsSeek(SeekInput.Gesture, session)) return
        if (durationMs > 0) {
            val target = positionMs.coerceIn(0L, _seekPresentation.value.durationMs)
            _seekPresentation.update { it.copy(targetMs = target, frame = SeekFrame.Pending) }
            val pos = (target.toDouble() / durationMs).toFloat().coerceIn(0f, 1f)
            _gestureSeekState.update { it.copy(isSeeking = true, seekPositionFraction = pos, targetMs = target) }
        }
    }

    /**
     * AND-014: [targetMs] is the FINAL seek target already computed from the
     * frozen gesture-start base (`base + offset`) by the UI layer. This method
     * must NOT re-add the live [currentPosition] — the playback head keeps
     * advancing during the gesture and would skew the committed target away
     * from what the overlay previewed.
     */
    fun onGestureSeekFinished(targetMs: Long, session: Long = _seekPresentation.value.session): Long? {
        if (!ownsSeek(SeekInput.Gesture, session)) return null
        onGestureSeekChanged(targetMs, _seekPresentation.value.durationMs, session)
        return commitSeek()
    }

    fun onGestureSeekCancelled(session: Long) {
        if (ownsSeek(SeekInput.Gesture, session)) cancelSeek()
    }

    private fun commitSeek(): Long {
        val target = _seekPresentation.value.targetMs
        _seekPresentation.update { it.copy(phase = SeekPhase.Settling, commitId = ++commitSequence, frame = SeekFrame.Suppressed) }
        _progressScrubState.value = ProgressScrubUiState()
        _gestureSeekState.value = GestureSeekUiState(targetMs = target)
        _currentPosition.value = target
        _uiState.update { it.copy(isUserSeeking = false, seekTarget = target) }
        return target
    }

    fun acknowledgeSeek(record: SeekCommitRecord, positionMs: Long) {
        val state = _seekPresentation.value
        if (pendingSeekCommit != record) return
        _currentPosition.value = positionMs.coerceIn(0L, state.durationMs)
        _seekPresentation.value = state.copy(phase = SeekPhase.Idle, targetMs = _currentPosition.value, commitId = null)
        clearSeekTarget()
    }

    fun terminateSeek(record: SeekCommitRecord, actualPositionMs: Long) {
        if (pendingSeekCommit != record) return
        cancelSeek()
        clearSeekTarget()
        _currentPosition.value = actualPositionMs.coerceAtLeast(0L)
    }

    /** A discontinuity without reliable commit identity still reports the actual head. */
    fun observeSeekPosition(positionMs: Long) {
        _currentPosition.value = positionMs.coerceAtLeast(0L)
    }

    @Suppress("UNUSED_PARAMETER")
    fun selectSeekPrecision(precision: SeekPrecision) {
        // Compatibility for the separately owned preview host. Fine seek is removed.
        _seekPresentation.update { it.copy(precision = SeekPrecision.Full, precisionExplicit = false, fineActivated = false) }
    }

    fun updateSeekFrame(session: Long, targetMs: Long, frame: SeekFrame) {
        _seekPresentation.update {
            if (it.session == session && it.targetMs == targetMs && it.phase == SeekPhase.Dragging) it.copy(frame = frame) else it
        }
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
        progressSaveChannel.close()
    }

    @VisibleForTesting
    internal fun clearForTest() {
        onCleared()
    }

    companion object {
        private const val TAG = "PlayerViewModel"
    }
}
