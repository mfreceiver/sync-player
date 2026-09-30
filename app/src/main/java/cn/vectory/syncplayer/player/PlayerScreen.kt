package cn.vectory.syncplayer.player

import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.res.stringResource
import cn.vectory.syncplayer.R
import cn.vectory.syncplayer.browser.UndoResult
import cn.vectory.syncplayer.browser.safeErrorDetail
import cn.vectory.syncplayer.ui.components.showTrashUndoSnackbar
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import android.net.Uri
import androidx.media3.exoplayer.ExoPlayer
import cn.vectory.syncplayer.player.GesturePlayerView.DoubleTapZone
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.WeakHashMap
import kotlin.math.pow

internal enum class MediaSwitchReason {
    EOF,
    MANUAL
}

internal fun determineAutoplayAfterSwitch(
    reason: MediaSwitchReason,
    currentPlayWhenReady: Boolean,
): Boolean = when (reason) {
    MediaSwitchReason.EOF -> true
    MediaSwitchReason.MANUAL -> currentPlayWhenReady
}

internal fun updateAutoplayIntent(toggleToPlaying: Boolean): Boolean = toggleToPlaying

/**
 * Decision helper for system-initiated playWhenReady changes (e.g. audio focus loss,
 * audio becoming noisy / headphone unplug).
 *
 * - When reason is USER_REQUEST: user code controls the autoplay intent via togglePlay()
 *   or media switch handlers; this function returns null (no-op).
 * - When reason is system-initiated (AUDIO_FOCUS_LOSS, AUDIO_BECOMING_NOISY, etc.):
 *   the system paused playback without explicit user action. To honor the baseline
 *   contract that switching while paused remains paused, we adopt the new playWhenReady
 *   value (false) into pendingAutoplay.
 */
internal fun autoplayIntentAfterSystemPauseChange(
    playWhenReady: Boolean,
    reason: Int,
): Boolean? = when (reason) {
    Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> null
    else -> playWhenReady
}

internal fun preparePlayerForMedia(
    player: Player,
    mediaUri: Uri,
    restorePosition: Long,
    shouldPlay: Boolean,
) {
    player.setMediaItem(MediaItem.fromUri(mediaUri))
    if (restorePosition > 0L) {
        player.seekTo(restorePosition)
    }
    player.prepare()
    if (shouldPlay) {
        player.play()
    }
}

private fun Modifier.verticalGradientBackground(colors: List<Color>, height: Dp): Modifier =
    this.then(
        Modifier.drawBehind {
            drawRect(
                brush = Brush.verticalGradient(colors, startY = 0f, endY = height.toPx())
            )
        }
    )

private val GradientHeight = 80.dp

@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    videoItemId: Long,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? android.app.Activity ?: return
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val gestureSeekState by viewModel.gestureSeekState.collectAsStateWithLifecycle()
    val overlayState by viewModel.overlayState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingUndoTrigger by remember { mutableIntStateOf(0) }
    // The video id captured at toggle time, used as expectedId for the undo Snackbar so a
    // stale Snackbar (from an earlier video) never undoes a newer toggle.
    var pendingUndoId by remember { mutableStateOf<Long?>(null) }
    // NOTE: currentPosition (200 ms high-frequency flow) is intentionally NOT
    // subscribed here. The only top-level consumer used to be `seekDisplayProgress`,
    // The narrow Aurora progress components subscribe on their own so
    // polling-driven recomposition stays scoped to them and does not invalidate
    // the whole PlayerScreen body. (AGENTS.md · State Splitting.)

    var originalBrightness by remember { mutableFloatStateOf(-1f) }
    var initialVolume by remember { mutableIntStateOf(0) }
    var isExiting by remember { mutableStateOf(false) }
    var currentMediaId by remember { mutableStateOf<Long?>(null) }
    var pendingAutoplay by remember { mutableStateOf(true) }

    val window = activity.window
    val exoPlayer = remember {
        val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
            .setUsage(androidx.media3.common.C.USAGE_MEDIA)
            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        ExoPlayer.Builder(context.applicationContext)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build().apply {
                playWhenReady = true
            }
    }
    val gestureView = remember {
        GesturePlayerView(context).apply {
            getPlayerView().player = exoPlayer
        }
    }
    val seekCommitListener = remember(viewModel, exoPlayer) { SeekCommitListener(viewModel) }

    val togglePlay = {
        val willPlay = !exoPlayer.isPlaying
        pendingAutoplay = updateAutoplayIntent(willPlay)
        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
    }

    DisposableEffect(Unit) {
        val currentBrightness = window.attributes.screenBrightness
        if (currentBrightness >= 0) {
            originalBrightness = currentBrightness
        }
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            // Re-read activity.window at dispose time instead of reusing the
            // window captured at composition — that reference can be stale if
            // the window has been re-attached between composition and dispose.
            val w = activity.window
            if (originalBrightness >= 0) {
                val attrs = w.attributes
                attrs.screenBrightness = originalBrightness
                w.attributes = attrs
            }
            val restoreController = WindowCompat.getInsetsController(w, w.decorView)
            restoreController.show(WindowInsetsCompat.Type.systemBars())
            restoreController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            viewModel.saveProgress(exoPlayer.currentPosition, exoPlayer.duration.coerceAtLeast(0), videoId = currentMediaId)
            gestureView.getPlayerView().player = null
            exoPlayer.release()
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    LaunchedEffect(videoItemId) {
        viewModel.loadVideo(videoItemId)
    }

    LaunchedEffect(uiState.videoItem) {
        val item = uiState.videoItem ?: return@LaunchedEffect
        seekCommitListener.resetForMedia()
        val mediaUri = cn.vectory.syncplayer.provider.MediaUris.mediaUri(item.id)
        currentMediaId = item.id
        val savedPos = viewModel.consumeRestorePosition(item.id)
        preparePlayerForMedia(
            player = exoPlayer,
            mediaUri = mediaUri,
            restorePosition = savedPos,
            shouldPlay = pendingAutoplay,
        )
    }

    LaunchedEffect(uiState.isFullscreen, uiState.videoItem) {
        val item = uiState.videoItem
        activity.requestedOrientation = if (item != null) {
            viewModel.resolveOrientation(item.width, item.height)
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    LaunchedEffect(uiState.currentGainDb) {
        val linear = 10f.pow(uiState.currentGainDb / 20f)
        exoPlayer.volume = linear.coerceIn(0f, 2f)
    }

    LaunchedEffect(uiState.playbackSpeed, uiState.isLongPressing) {
        if (!uiState.isLongPressing) {
            exoPlayer.playbackParameters = PlaybackParameters(uiState.playbackSpeed)
        }
    }

    val currentOnExit by rememberUpdatedState(onExit)

    // BUG-019: consume the structured exhaustion terminal — exit exactly like normal EOF.
    LaunchedEffect(uiState.queueExhausted) {
        // key 用组合值触发 relaunch；判断体再核对 VM 当前终态（StateFlow 现值）——
        // 防止读取"已被新请求成功加载清除 flag"的旧 composition 值而误退出。
        if (viewModel.uiState.value.queueExhausted && !isExiting) {
            isExiting = true
            currentOnExit()
        }
    }

    val playerListener = remember {
        object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                autoplayIntentAfterSystemPauseChange(playWhenReady, reason)?.let {
                    pendingAutoplay = it
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val playerPos = exoPlayer.currentPosition.coerceAtLeast(0)
                val playerDur = exoPlayer.duration.coerceAtLeast(0)
                viewModel.updatePlaybackProgress(playerPos, playerDur, videoId = currentMediaId)
                if (playbackState == Player.STATE_IDLE) seekCommitListener.terminate(playerPos)

                if (playbackState == Player.STATE_ENDED) {
                    val nextId = viewModel.getNextVideoId()
                    if (nextId != null) {
                        pendingAutoplay = determineAutoplayAfterSwitch(MediaSwitchReason.EOF, exoPlayer.playWhenReady)
                        viewModel.updateAutoSwitchTime()
                        viewModel.saveProgress(exoPlayer.currentPosition.coerceAtLeast(0), exoPlayer.duration.coerceAtLeast(0), videoId = currentMediaId)
                        exoPlayer.pause()
                        viewModel.loadVideo(nextId)
                    } else {
                        if (!isExiting) {
                            isExiting = true
                            currentOnExit()
                        }
                    }
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                viewModel.setPlaying(playing)
            }

            @androidx.annotation.OptIn(UnstableApi::class)
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                val eventUri = newPosition.mediaItem?.localConfiguration?.uri
                if (eventUri != null && eventUri != exoPlayer.currentMediaItem?.localConfiguration?.uri) return
                if (currentMediaId == viewModel.uiState.value.videoItem?.id) {
                    gestureView.currentPosition = newPosition.positionMs.coerceAtLeast(0L)
                }
                if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
                    seekCommitListener.onPositionDiscontinuity(oldPosition, newPosition, reason)
                }
                viewModel.updatePlaybackProgress(newPosition.positionMs, exoPlayer.duration.coerceAtLeast(0), videoId = currentMediaId)
                // AND-064: keep the gesture-seek base in sync on any real seek —
                // the poller only writes it while playing, so a paused scrub/±10s
                // left a stale anchor for the next gesture. Skip while a gesture
                // is active (the base is intentionally frozen mid-drag).
                if (!gestureView.isGestureSeeking) {
                    gestureView.currentPosition = newPosition.positionMs.coerceAtLeast(0)
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                // Log raw error for developer diagnostics only; never surface
                // error.message to the UI (it may carry URI/path/provider details).
                // AND-085: structured token only — PlaybackException.message can carry URI/provider details.
                Log.e("PlayerScreen", "Playback error: ${safeErrorDetail(error)}")
                viewModel.onPlayerError()
                seekCommitListener.terminate(exoPlayer.currentPosition)
            }
        }
    }

    DisposableEffect(exoPlayer) {
        SeekCommitRouter.register(exoPlayer, seekCommitListener)
        exoPlayer.addListener(playerListener)
        onDispose {
            SeekCommitRouter.unregister(exoPlayer)
            seekCommitListener.terminate(exoPlayer.currentPosition)
            seekCommitListener.close()
            exoPlayer.removeListener(playerListener)
        }
    }

    val isGestureSeeking by rememberUpdatedState(gestureSeekState.isSeeking)
    LaunchedEffect(uiState.isPlaying) {
        if (uiState.isPlaying) {
            var lastCheckpointMs = System.currentTimeMillis()
            while (true) {
                // AND-063: this effect's key is a Compose snapshot that freezes at
                // isPlaying=true once collectAsStateWithLifecycle stops on ON_STOP —
                // check the live player so the poll actually dies in background
                // instead of waking every 200ms and writing Room every 30s.
                if (!exoPlayer.isPlaying) break
                val playerPos = exoPlayer.currentPosition.coerceAtLeast(0)
                val playerDur = exoPlayer.duration.coerceAtLeast(0)
                gestureView.videoDuration = playerDur

                // Don't update gestureView.currentPosition during gesture seek —
                // it would cause the seek target to drift since GesturePlayerView
                // uses currentPosition as the base for offset calculation.
                if (!isGestureSeeking && viewModel.seekPresentation.value.phase != SeekPhase.Settling) {
                    gestureView.currentPosition = playerPos
                }
                viewModel.updatePlaybackProgress(playerPos, playerDur, videoId = currentMediaId)

                val now = System.currentTimeMillis()
                if (now - lastCheckpointMs >= 30_000L) {
                    lastCheckpointMs = now
                    viewModel.saveProgress(playerPos, playerDur, videoId = currentMediaId)
                }
                delay(200L)
            }
        }
    }

    val lifecycleOwner = activity as? ComponentActivity
    if (lifecycleOwner != null) {
        DisposableEffect(lifecycleOwner.lifecycle) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) {
                    if (exoPlayer.isPlaying) {
                        exoPlayer.pause()
                    }
                    // AND-001: persist progress immediately when the app stops
                    // (recents swipe, screen-off interruptions). The
                    // composition's onDispose save may never run on those
                    // paths, and viewModelScope may already be cancelled.
                    viewModel.saveProgress(
                        exoPlayer.currentPosition.coerceAtLeast(0),
                        exoPlayer.duration.coerceAtLeast(0),
                        videoId = currentMediaId
                    )
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
    }

    fun doExit() {
        if (isExiting) return
        isExiting = true
        // saveProgress is intentionally NOT called here: navigating away
        // disposes this composition, and the DisposableEffect(Unit).onDispose
        // above is the more reliable save path. Calling from both sites used
        // to double-write progress on back-press (doExit ran first, then
        // onDispose ran again as the composition left).
        gestureView.getPlayerView().player = null
        exoPlayer.pause()
        onExit()
    }

    val uiStateRef = rememberUpdatedState(uiState)
    var rippleEvent by remember { mutableStateOf<RippleEvent?>(null) }

    val gestureListener = remember {
        object : GesturePlayerView.GestureListener {
            override fun onSpeedUpChanged(speedUp: Boolean) {
                viewModel.setLongPressing(speedUp)
                if (speedUp) {
                    val multiplier = 2.0f // Or from prefs
                    exoPlayer.playbackParameters = PlaybackParameters(multiplier)
                } else {
                    exoPlayer.playbackParameters = PlaybackParameters(uiStateRef.value.playbackSpeed)
                }
            }
            override fun onSeekTo(offsetMs: Long, basePositionMs: Long) {
                // AND-014: commit from the FROZEN gesture-start base — never
                // exoPlayer.currentPosition, which keeps advancing while the
                // finger is down and would make the committed target overshoot
                // what the seek overlay previewed.
                val targetMs = (basePositionMs + offsetMs)
                    .coerceIn(0L, exoPlayer.duration.coerceAtLeast(0L))
                viewModel.onGestureSeekFinished(targetMs, gestureSession)?.let {
                    performPresentedSeek(viewModel, exoPlayer, it)
                }
            }
            private var gestureSession = -1L
            override fun onSeekStarted(anchorMs: Long) {
                gestureSession = viewModel.onGestureSeekStarted(anchorMs)
            }
            override fun onSeekCancelled() {
                viewModel.onGestureSeekCancelled(gestureSession)
            }
            override fun onVolumeGestureStart() {
                val audioManager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
                initialVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            }
            override fun onVolumeChanged(totalDelta: Float) {
                val audioManager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
                val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val newVolume = (initialVolume + (totalDelta * maxVolume).toInt()).coerceIn(0, maxVolume)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
                val percent = (newVolume * 100 / maxVolume).coerceIn(0, 100)
                viewModel.showOverlay(PlayerOverlay.Volume(percent))
            }
            override fun onBrightnessChanged(delta: Float) {
                val attrs = window.attributes
                val current = if (attrs.screenBrightness < 0) 0.5f else attrs.screenBrightness
                val newBrightness = (current + delta).coerceIn(0f, 1f)
                attrs.screenBrightness = newBrightness
                window.attributes = attrs
                val percent = (newBrightness * 100).toInt()
                viewModel.showOverlay(PlayerOverlay.Brightness(percent))
            }
            override fun onDoubleTap(zone: DoubleTapZone, xFraction: Float, yFraction: Float) {
                rippleEvent = RippleEvent(
                    sequence = (rippleEvent?.sequence ?: 0L) + 1L,
                    xFraction = xFraction,
                    yFraction = yFraction,
                )
                val s = uiStateRef.value
                when (zone) {
                    DoubleTapZone.PREVIOUS -> {
                        val prevId = viewModel.getPrevVideoId()
                        if (prevId != null) {
                            viewModel.showOverlay(PlayerOverlay.Previous)
                            viewModel.saveProgress(exoPlayer.currentPosition.coerceAtLeast(0), exoPlayer.duration.coerceAtLeast(0), videoId = currentMediaId)
                            pendingAutoplay = determineAutoplayAfterSwitch(MediaSwitchReason.MANUAL, exoPlayer.playWhenReady || pendingAutoplay)
                            exoPlayer.pause()
                            viewModel.loadVideo(prevId)
                        } else {
                            viewModel.showOverlay(PlayerOverlay.NoPrevious)
                        }
                    }
                    DoubleTapZone.NEXT -> {
                        val now = System.currentTimeMillis()
                        if (now - s.lastAutoSwitchTime < 500L) return
                        val nextId = viewModel.getNextVideoId()
                        if (nextId != null) {
                            viewModel.showOverlay(PlayerOverlay.Next)
                            viewModel.saveProgress(exoPlayer.currentPosition.coerceAtLeast(0), exoPlayer.duration.coerceAtLeast(0), videoId = currentMediaId)
                            pendingAutoplay = determineAutoplayAfterSwitch(MediaSwitchReason.MANUAL, exoPlayer.playWhenReady || pendingAutoplay)
                            exoPlayer.pause()
                            viewModel.loadVideo(nextId)
                        } else {
                            viewModel.showOverlay(PlayerOverlay.NoNext)
                        }
                    }
                    DoubleTapZone.CENTER_TOP -> {
                        togglePlay()
                    }
                    DoubleTapZone.CENTER_BOTTOM -> {
                        if (s.quickRecycle) {
                            val wasTrashed = s.playbackRecord?.isTrashed ?: false
                            viewModel.showOverlay(PlayerOverlay.TrashAction(!wasTrashed))
                            // Defer the Snackbar trigger until the async toggle succeeds.
                            // The callback receives the FIXED video id captured at call time —
                            // even if the user double-taps "next" before the toggle completes,
                            // pendingUndoId will hold the original video, so the Snackbar can
                            // never undo a toggle on the new (different) video.
                            viewModel.toggleTrashed { completedId ->
                                pendingUndoId = completedId
                                pendingUndoTrigger++
                            }
                        } else {
                            togglePlay()
                        }
                    }
                }
            }
            override fun onSeekOverlay(positionMs: Long, durationMs: Long) {
                viewModel.onGestureSeekChanged(positionMs, durationMs, gestureSession)
            }
            override fun onLongPressSpeedOverlay(multiplier: Float) {
                viewModel.setSpeedOverlay(multiplier)
            }
            override fun onHideOverlay() {
                viewModel.hideOverlay()
            }
            override fun onEdgeBack() {
                doExit()
            }
        }
    }

    DisposableEffect(gestureView, gestureListener) {
        gestureView.setOnSingleTapCallback {
            viewModel.toggleControls()
        }
        gestureView.gestureListener = gestureListener
        gestureView.positionProvider = { exoPlayer.currentPosition.coerceAtLeast(0L) }
        gestureView.durationProvider = { exoPlayer.duration.coerceAtLeast(0L) }
        gestureView.seekPrecisionProvider = { viewModel.seekPresentation.value.precision.factor }
        gestureView.edgeBackEnabled = true
        onDispose {
            gestureView.cancelSeekGesture()
            viewModel.cancelSeek()
            gestureView.positionProvider = null
            gestureView.durationProvider = null
            gestureView.seekPrecisionProvider = null
            gestureView.setOnSingleTapCallback(null)
            gestureView.gestureListener = null
        }
    }

    var showSettingsPopup by remember { mutableStateOf(false) }
    val previewExtractor = remember { SeekPreviewExtractor(context) }
    DisposableEffect(previewExtractor) {
        onDispose {
            previewExtractor.release()
        }
    }
    val seekSource = when {
        uiState.isUserSeeking -> SeekSource.PROGRESS_BAR
        gestureSeekState.isSeeking -> SeekSource.GESTURE
        else -> SeekSource.NONE
    }
    val chromeVisibility = resolveAuroraChromeVisibility(
        isFullscreen = uiState.isFullscreen,
        showControls = uiState.showControls,
        seekSource = seekSource,
    )
    val videoUri = uiState.videoItem
        ?.let { cn.vectory.syncplayer.provider.MediaUris.mediaUri(it.id).toString() }

    LaunchedEffect(uiState.showControls, uiState.videoItem?.id) {
        if (showSettingsPopup && (!uiState.showControls || uiState.videoItem == null)) {
            showSettingsPopup = false
        }
    }

    BackHandler { doExit() }

    // Resolve strings outside the suspend context of LaunchedEffect.
    val undoTrashMessage = stringResource(R.string.undo_trash_message)
    val undoActionLabel = stringResource(R.string.undo_action)
    val undoFailedMessage = stringResource(R.string.undo_failed_message)

    // Show undo snackbar ONLY after the async trash toggle succeeded (the trigger is
    // incremented inside the toggle's onSuccess callback, not at call time). This ensures a
    // failed toggle never produces a misleading "moved to trash" Snackbar.
    // pendingUndoId is the FIXED video id captured at toggle time (returned by the ViewModel
    // callback) and used as expectedId so a stale Snackbar (e.g. from an earlier video after a
    // double-tap next) never undoes a newer toggle. If the trigger fired but the id is null
    // (defensive), do nothing rather than call undo with a null expectedId (which would undo a
    // newer unrelated operation).
    LaunchedEffect(pendingUndoTrigger) {
        if (pendingUndoTrigger > 0) {
            val expectedId = pendingUndoId ?: return@LaunchedEffect
            val result = snackbarHostState.showTrashUndoSnackbar(
                message = undoTrashMessage,
                actionLabel = undoActionLabel,
            )
            when (result) {
                SnackbarResult.ActionPerformed -> {
                    // expectedId protection: only undo if this operation is still the current one.
                    val undoResult = viewModel.undoDelete(expectedId = expectedId)
                    if (undoResult is UndoResult.Failed) {
                        snackbarHostState.showSnackbar(undoFailedMessage)
                    }
                }
                SnackbarResult.Dismissed -> {
                    // Trash toggle is already applied; nothing to commit.
                }
            }
        }
    }

    // Surface toggle/delete failures (resolved message already localized by the ViewModel;
    // no underlying path/id/message leaks).
    uiState.error?.let { ev ->
        LaunchedEffect(ev.seq) {
            try {
                snackbarHostState.showSnackbar(ev.message)
            } finally {
                viewModel.clearError(ev.seq)
            }
        }
    }

    // Measured height of the bottom chrome wrapper (progress zone + optional
    // control row), hoisted here so the seek preview host can anchor the coarse
    // preview above it. Lives outside the conditional so the lambda capture stays
    // stable; the value is only read while a preview is visible, and the chrome is
    // always composed then (seekSource != NONE implies showProgressZone).
    var bottomChromeHeightPx by remember { mutableIntStateOf(0) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { gestureView },
            modifier = Modifier.fillMaxSize()
        )

        AuroraBands()
        AuroraTopRow(
            fileName = uiState.videoItem?.fileName?.substringBeforeLast(".") ?: "",
            visible = chromeVisibility.showTopRow,
            onExit = ::doExit,
        )

        AuroraBottomChrome(
            visible = chromeVisibility.showProgressZone || chromeVisibility.showControlRow,
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Box(
                modifier = Modifier
                    // Measured (never guessed) bottom-chrome height — fed to the seek
                    // preview host so the coarse preview anchors above the progress
                    // zone in both visibility cases (with/without control row) and
                    // both orientations. This wrapper measurement INCLUDES
                    // AuroraBottomArea's navigationBarsPadding(), so consumers must
                    // not add the nav-bar inset again.
                    .onSizeChanged { bottomChromeHeightPx = it.height },
            ) {
                AuroraBottomArea(
                    visibility = chromeVisibility,
                    viewModel = viewModel,
                    exoPlayer = exoPlayer,
                    seekSource = seekSource,
                    durationMs = uiState.duration,
                    gesturePositionMs = gestureSeekState.targetMs,
                    isPlaying = uiState.isPlaying,
                    onOpenSettings = { showSettingsPopup = true },
                    onBack10 = { seekBy(viewModel, exoPlayer, -10_000L) },
                    onForward10 = { seekBy(viewModel, exoPlayer, 10_000L) },
                    onRotate = viewModel::toggleFullscreen,
                    onPreviousVideo = {
                        val prevId = viewModel.getPrevVideoId()
                        if (prevId != null) {
                            viewModel.showOverlay(PlayerOverlay.Previous)
                            viewModel.saveProgress(exoPlayer.currentPosition.coerceAtLeast(0), exoPlayer.duration.coerceAtLeast(0), videoId = currentMediaId)
                            pendingAutoplay = determineAutoplayAfterSwitch(MediaSwitchReason.MANUAL, exoPlayer.playWhenReady || pendingAutoplay)
                            exoPlayer.pause()
                            viewModel.loadVideo(prevId)
                        } else {
                            viewModel.showOverlay(PlayerOverlay.NoPrevious)
                        }
                    },
                    onNextVideo = {
                        val nextId = viewModel.getNextVideoId()
                        if (nextId != null) {
                            viewModel.showOverlay(PlayerOverlay.Next)
                            viewModel.saveProgress(exoPlayer.currentPosition.coerceAtLeast(0), exoPlayer.duration.coerceAtLeast(0), videoId = currentMediaId)
                            pendingAutoplay = determineAutoplayAfterSwitch(MediaSwitchReason.MANUAL, exoPlayer.playWhenReady || pendingAutoplay)
                            exoPlayer.pause()
                            viewModel.loadVideo(nextId)
                        } else {
                            viewModel.showOverlay(PlayerOverlay.NoNext)
                        }
                    },
                    onTogglePlay = togglePlay,
                )
            }
        }

        if (chromeVisibility.showMinimizedProgress) {
            Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                AuroraMinimizedProgressBar(
                    viewModel = viewModel,
                    durationMs = uiState.duration,
                    onSeekBy = { seekBy(viewModel, exoPlayer, it) },
                )
            }
        }

        AuroraSettingsModal(
            visible = showSettingsPopup,
            isFullscreen = uiState.isFullscreen,
            showControls = uiState.showControls,
            videoId = uiState.videoItem?.id,
            playbackSpeed = uiState.playbackSpeed,
            isTrashed = uiState.playbackRecord?.isTrashed == true,
            errorSequence = uiState.error?.seq,
            onDismiss = { showSettingsPopup = false },
            onSpeedChange = viewModel::setPlaybackSpeed,
            onDirectionChange = { fullscreen ->
                // Close the settings panel explicitly before switching mode/overlay —
                // never rely on indirect state changes to dismiss it.
                showSettingsPopup = false
                viewModel.setFullscreen(fullscreen)
                viewModel.showOverlay(
                    PlayerOverlay.Orientation(
                        if (fullscreen) OrientationMode.FULLSCREEN else OrientationMode.ROTATION
                    )
                )
            },
            onToggleTrash = viewModel::toggleTrashed,
            onTrashSucceeded = { completedId ->
                pendingUndoId = completedId
                pendingUndoTrigger++
            },
        )

        AuroraSeekPreviewHost(
            seekSource = seekSource,
            progressScrubState = viewModel.progressScrubState,
            gesturePositionMs = gestureSeekState.targetMs,
            durationMs = uiState.duration,
            bottomChromeHeightPx = bottomChromeHeightPx,
            videoUri = videoUri,
            previewExtractor = previewExtractor,
            viewModel = viewModel,
            onStep = { seekBy(viewModel, exoPlayer, it) },
        )
        val speedOverlayMultiplier = overlayState.speedMultiplier
        if (speedOverlayMultiplier != null) {
            AuroraSpeedPill(speedOverlayMultiplier)
        } else {
            AuroraPill(overlayState.overlay)
        }
        DoubleTapRipple(rippleEvent)

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )


        if (uiState.hasPlayerError) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.7f))
                    .pointerInput(Unit) {
                        detectTapGestures { }
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Error,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier.size(48.dp)
                    )
                    Text(
                        text = stringResource(R.string.player_error_generic),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = { viewModel.clearPlayerError() }
                        ) {
                            Text(stringResource(R.string.common_close), color = Color.White)
                        }
                        Button(
                            onClick = {
                                viewModel.clearPlayerError()
                                // Seek back to the last reported position before
                                // re-preparing, otherwise prepare() restarts from
                                // the seek position the player holds in its error
                                // state (often 0 or the failure point) and the
                                // user loses their playback position.
                                val savedPos = viewModel.currentPosition.value.coerceAtLeast(0)
                                if (savedPos > 0) {
                                    exoPlayer.seekTo(savedPos)
                                }
                                exoPlayer.prepare()
                            }
                        ) {
                            Text(stringResource(R.string.common_retry))
                        }
                    }
                }
            }
        }
    }
}

private fun seekBy(viewModel: PlayerViewModel, exoPlayer: ExoPlayer, deltaMs: Long) {
    val durationMs = exoPlayer.duration.coerceAtLeast(0L)
    val state = viewModel.seekPresentation.value
    val anchor = if (state.phase == SeekPhase.Dragging) state.targetMs else exoPlayer.currentPosition
    val targetMs = (anchor + deltaMs).coerceIn(0L, durationMs)
    val session = viewModel.onProgressScrubStarted(targetMs)
    viewModel.onProgressScrubFinished(targetMs, session)?.let { performPresentedSeek(viewModel, exoPlayer, it) }
}

internal fun performPresentedSeek(viewModel: PlayerViewModel, player: ExoPlayer, targetMs: Long) {
    SeekCommitRouter.seek(viewModel, player, targetMs)
}

/** At most one issued seek and one replaceable latest intent, scoped to a media session.
 *
 * Media3无应用提交ID；应用超时不取消底层已发出事件；固定延时不能证明旧回调结束。
 * 历史来源不确定的通知只能更新实际位置，不能确认新提交。
 * The 100ms quiet window is ONLY scheduling/coalescing, NEVER proof of provenance.
 */
internal class SeekCommitListener(private val viewModel: PlayerViewModel) : Player.Listener {
    private data class Intent(val record: SeekCommitRecord, val player: ExoPlayer)
    private data class Flight(
        val intent: Intent,
        val mediaUri: Uri?,
        var sawSeek: Boolean = false,
        var confirmed: Boolean = false,
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var inFlight: Flight? = null
    private var pending: Intent? = null
    private var quietJob: Job? = null
    private var deadlineJob: Job? = null
    /** Sticky until resetForMedia or a fresh listener for a rebuilt player. */
    internal var provenanceUncertain = false
        private set
    /** A confirmed SEEK can still have an adjustment; never accept an adjustment
     * as the *first* identifying event of its successor. Not cleared by time. */
    private var predecessorAdjustmentPossible = false
    /** Structural bound independent of how many times the user scrubs. */
    internal val activeRecordCount: Int get() = (if (inFlight != null) 1 else 0) + (if (pending != null) 1 else 0)

    fun seek(player: ExoPlayer, targetMs: Long) {
        val record = viewModel.pendingSeekCommit ?: return
        if (record.targetMs != targetMs) return
        val intent = Intent(record, player)
        if (inFlight != null) {
            if (inFlight?.intent?.record == record) return
            // VM has replaced old Settling with its newest target. An unconfirmed
            // issued flight keeps its OWN deadline; replacing the pending slot
            // cannot postpone that deadline or create another issued seek.
            if (inFlight?.confirmed == false) provenanceUncertain = true
            pending = intent
            if (inFlight?.confirmed == true && !provenanceUncertain) scheduleQuietDrain()
            return
        }
        issue(intent)
    }

    private fun issue(intent: Intent) {
        val (record, player) = intent
        if (viewModel.pendingSeekCommit != record) return
        // Strict pre-submit no-op: no seekTo call, no in-flight event ownership.
        val before = player.currentPosition
        if (before == record.targetMs) {
            viewModel.acknowledgeSeek(record, before)
            return
        }
        val flight = Flight(intent, player.currentMediaItem?.localConfiguration?.uri)
        inFlight = flight
        // Independent issuance deadline, even when the VM commitId changes while
        // another gesture overwrites the pending slot.
        deadlineJob = scope.launch {
            delay(2_000L)
            expire(flight)
        }
        try {
            player.seekTo(record.targetMs)
        } catch (e: RuntimeException) {
            terminate(player.currentPosition)
            throw e
        }
    }

    private fun expire(flight: Flight) {
        if (inFlight !== flight) return
        // Missing a confirmable callback permanently poisons attribution for
        // THIS media session. A later SEEK may be a late event from this flight.
        if (!flight.confirmed) provenanceUncertain = true
        retire(flight, flight.intent.player.currentPosition)
    }

    private fun retire(flight: Flight, actualPositionMs: Long) {
        if (inFlight !== flight) return
        quietJob?.cancel()
        quietJob = null
        deadlineJob?.cancel()
        deadlineJob = null
        predecessorAdjustmentPossible = predecessorAdjustmentPossible || flight.confirmed
        if (!flight.confirmed) provenanceUncertain = true
        inFlight = null
        // If this remains the VM's current record, its actual deadline concludes
        // Settling with the real player head, without fabricating an ACK.
        viewModel.terminateSeek(flight.intent.record, actualPositionMs)
        val next = pending
        pending = null
        if (next != null) issue(next)
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        if (reason != Player.DISCONTINUITY_REASON_SEEK && reason != Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) return
        val flight = inFlight
        if (flight == null || (flight.mediaUri != null && flight.mediaUri != newPosition.mediaItem?.localConfiguration?.uri)) {
            viewModel.observeSeekPosition(newPosition.positionMs)
            return
        }
        if (provenanceUncertain) {
            viewModel.observeSeekPosition(newPosition.positionMs)
            // Do not retire on a seemingly relevant event. This flight ends at
            // ITS issuance deadline; callback timing is not attribution evidence.
            return
        }
        when (reason) {
            Player.DISCONTINUITY_REASON_SEEK -> {
                if (!flight.sawSeek) {
                    flight.sawSeek = true
                    flight.confirmed = true
                    if (viewModel.pendingSeekCommit == flight.intent.record) {
                        viewModel.acknowledgeSeek(flight.intent.record, newPosition.positionMs)
                    } else viewModel.observeSeekPosition(newPosition.positionMs)
                } else viewModel.observeSeekPosition(newPosition.positionMs)
                scheduleQuietDrain()
            }
            Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT -> {
                // The first adjustment of the first clean flight can confirm it.
                // Once a predecessor could still adjust, this event is ambiguous.
                val attributable = !flight.sawSeek && !predecessorAdjustmentPossible
                if (attributable) {
                    flight.confirmed = true
                    if (viewModel.pendingSeekCommit == flight.intent.record) {
                        viewModel.acknowledgeSeek(flight.intent.record, newPosition.positionMs)
                    } else viewModel.observeSeekPosition(newPosition.positionMs)
                    scheduleQuietDrain()
                } else {
                    viewModel.observeSeekPosition(newPosition.positionMs)
                    if (flight.sawSeek) scheduleQuietDrain()
                }
            }
        }
    }

    private fun scheduleQuietDrain() {
        quietJob?.cancel()
        quietJob = scope.launch {
            delay(100L)
            val old = inFlight ?: return@launch
            // Scheduling only. No elapsed duration proves that a late Media3
            // callback cannot still arrive after the new intent is issued.
            if (old.confirmed && !provenanceUncertain) retire(old, old.intent.player.currentPosition)
        }
    }

    fun terminate(actualPositionMs: Long) {
        quietJob?.cancel()
        quietJob = null
        deadlineJob?.cancel()
        deadlineJob = null
        val old = inFlight
        if (old != null && !old.confirmed) provenanceUncertain = true
        if (old?.confirmed == true) predecessorAdjustmentPossible = true
        inFlight = null
        pending = null
        viewModel.pendingSeekCommit?.let { viewModel.terminateSeek(it, actualPositionMs) }
    }

    fun resetForMedia() {
        terminate(0L)
        provenanceUncertain = false
        predecessorAdjustmentPossible = false
    }

    fun close() { terminate(0L); scope.cancel() }
}

private object SeekCommitRouter {
    private val listeners = WeakHashMap<ExoPlayer, SeekCommitListener>()
    fun register(player: ExoPlayer, listener: SeekCommitListener) { listeners[player] = listener }
    fun unregister(player: ExoPlayer) { listeners.remove(player) }
    fun seek(viewModel: PlayerViewModel, player: ExoPlayer, targetMs: Long) {
        val listener = listeners[player]
        if (listener != null) {
            listener.seek(player, targetMs)
        } else {
            // Isolated progress Composable tests have no PlayerScreen listener.
            // Do not allocate a detached coroutine scope for each invocation.
            val record = viewModel.pendingSeekCommit ?: return
            if (record.targetMs != targetMs) return
            if (player.currentPosition == targetMs) viewModel.acknowledgeSeek(record, player.currentPosition)
            else try { player.seekTo(targetMs) } catch (e: RuntimeException) {
                viewModel.terminateSeek(record, player.currentPosition)
                throw e
            }
        }
    }
}
