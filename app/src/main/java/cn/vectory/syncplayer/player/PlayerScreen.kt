package cn.vectory.syncplayer.player

import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
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
import androidx.media3.exoplayer.ExoPlayer
import cn.vectory.syncplayer.player.GesturePlayerView.DoubleTapZone
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.pow

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
            viewModel.saveProgress(exoPlayer.currentPosition, exoPlayer.duration.coerceAtLeast(0))
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
        val mediaItem = MediaItem.fromUri(cn.vectory.syncplayer.provider.MediaUris.mediaUri(item.id))
        exoPlayer.setMediaItem(mediaItem)
        val savedPos = viewModel.getSavedPosition()
        if (savedPos > 0) {
            exoPlayer.seekTo(savedPos)
        }
        exoPlayer.prepare()
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
            override fun onPlaybackStateChanged(playbackState: Int) {
                val playerPos = exoPlayer.currentPosition.coerceAtLeast(0)
                val playerDur = exoPlayer.duration.coerceAtLeast(0)
                viewModel.updatePlaybackProgress(playerPos, playerDur)

                if (playbackState == Player.STATE_ENDED) {
                    val nextId = viewModel.getNextVideoId()
                    if (nextId != null) {
                        viewModel.updateAutoSwitchTime()
                        viewModel.saveProgress(exoPlayer.currentPosition.coerceAtLeast(0), exoPlayer.duration.coerceAtLeast(0))
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

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                viewModel.updatePlaybackProgress(newPosition.positionMs, exoPlayer.duration.coerceAtLeast(0))
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                // Log raw error for developer diagnostics only; never surface
                // error.message to the UI (it may carry URI/path/provider details).
                Log.e("PlayerScreen", "Playback error", error)
                viewModel.onPlayerError()
            }
        }
    }

    DisposableEffect(exoPlayer) {
        exoPlayer.addListener(playerListener)
        onDispose { exoPlayer.removeListener(playerListener) }
    }

    val seekTarget by rememberUpdatedState(uiState.seekTarget)
    val isGestureSeeking by rememberUpdatedState(uiState.isGestureSeeking)
    LaunchedEffect(uiState.isPlaying) {
        if (uiState.isPlaying) {
            while (true) {
                val playerPos = exoPlayer.currentPosition.coerceAtLeast(0)
                val playerDur = exoPlayer.duration.coerceAtLeast(0)

                if (seekTarget >= 0) {
                    if (abs(playerPos - seekTarget) < 500) {
                        viewModel.clearSeekTarget()
                    }
                }

                // Don't update gestureView.currentPosition during gesture seek —
                // it would cause the seek target to drift since GesturePlayerView
                // uses currentPosition as the base for offset calculation.
                if (!isGestureSeeking) {
                    gestureView.currentPosition = playerPos
                }
                gestureView.videoDuration = playerDur
                viewModel.updatePlaybackProgress(playerPos, playerDur)
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
                        exoPlayer.duration.coerceAtLeast(0)
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

    gestureView.setOnSingleTapCallback {
        viewModel.toggleControls()
    }

    // Hold the latest uiState in a remembered State holder so the long-lived
    // gestureListener below can read fresh values (playbackSpeed, quickRecycle,
    // lastAutoSwitchTime, playbackRecord.isTrashed) without being rebuilt on
    // every recomposition. Reading uiStateRef.value at callback time always
    // returns the current snapshot (see AGENTS.md · Recomposition Isolation).
    val uiStateRef = rememberUpdatedState(uiState)
    var rippleEvent by remember { mutableStateOf<RippleEvent?>(null) }

    // Build the listener ONCE. The previous code reallocated the whole object
    // and re-assigned it to the View on every recomposition. Captures that are
    // stable across the composition lifecycle (exoPlayer, viewModel, context,
    // window, initialVolume's delegated State) are safe to close over here;
    // uiState is read through uiStateRef above to avoid a stale snapshot.
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
                viewModel.onGestureSeekFinished(targetMs)
                exoPlayer.seekTo(targetMs)
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
                            viewModel.saveProgress(exoPlayer.currentPosition.coerceAtLeast(0), exoPlayer.duration.coerceAtLeast(0))
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
                            viewModel.saveProgress(exoPlayer.currentPosition.coerceAtLeast(0), exoPlayer.duration.coerceAtLeast(0))
                            viewModel.loadVideo(nextId)
                        } else {
                            viewModel.showOverlay(PlayerOverlay.NoNext)
                        }
                    }
                    DoubleTapZone.CENTER_TOP -> {
                        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
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
                            if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                        }
                    }
                }
            }
            override fun onSeekOverlay(positionMs: Long, durationMs: Long) {
                viewModel.onGestureSeekChanged(positionMs, durationMs)
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
    gestureView.gestureListener = gestureListener
    // The player UI is unconditionally immersive (system bars always hidden, video
    // fills the container), so edge-back must be enabled regardless of uiState.
    gestureView.edgeBackEnabled = true

    var showSettingsPopup by remember { mutableStateOf(false) }
    val previewExtractor = remember { SeekPreviewExtractor(context) }
    DisposableEffect(previewExtractor) {
        onDispose {
            previewExtractor.release()
        }
    }
    val seekSource = when {
        uiState.isUserSeeking -> SeekSource.PROGRESS_BAR
        uiState.isGestureSeeking -> SeekSource.GESTURE
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
                viewModel.clearError()
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

        if (chromeVisibility.showProgressZone || chromeVisibility.showControlRow) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
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
                    gesturePositionMs = (uiState.gestureSeekPosition * uiState.duration).toLong(),
                    isPlaying = uiState.isPlaying,
                    onOpenSettings = { showSettingsPopup = true },
                    onBack10 = { seekBy(viewModel, exoPlayer, -10_000L) },
                    onForward10 = { seekBy(viewModel, exoPlayer, 10_000L) },
                    onRotate = viewModel::toggleFullscreen,
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
            gesturePositionMs = (uiState.gestureSeekPosition * uiState.duration).toLong(),
            durationMs = uiState.duration,
            bottomChromeHeightPx = bottomChromeHeightPx,
            videoUri = videoUri,
            previewExtractor = previewExtractor,
        )
        val speedOverlayMultiplier = uiState.speedOverlayMultiplier
        if (speedOverlayMultiplier != null) {
            AuroraSpeedPill(speedOverlayMultiplier)
        } else {
            AuroraPill(uiState.currentOverlay)
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
                    .background(Color.Black.copy(alpha = 0.7f)),
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
    val targetMs = (viewModel.currentPosition.value + deltaMs).coerceIn(0L, durationMs)
    viewModel.onProgressScrubStarted(targetMs)
    viewModel.onProgressScrubFinished(targetMs)
    exoPlayer.seekTo(targetMs)
}
