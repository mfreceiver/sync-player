package cn.vectory.syncplayer.player

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.media.AudioManager
import android.net.Uri
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.res.stringResource
import cn.vectory.syncplayer.R
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
import cn.vectory.syncplayer.ui.theme.LocalExtendedColors
import cn.vectory.syncplayer.ui.util.formatDuration
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File
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
    val currentPosition by viewModel.currentPosition.collectAsStateWithLifecycle()

    var originalBrightness by remember { mutableFloatStateOf(-1f) }
    var initialVolume by remember { mutableIntStateOf(0) }
    var isExiting by remember { mutableStateOf(false) }

    val window = activity.window
    val exoPlayer = remember {
        ExoPlayer.Builder(context.applicationContext).build().apply {
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
            if (originalBrightness >= 0) {
                val attrs = window.attributes
                attrs.screenBrightness = originalBrightness
                window.attributes = attrs
            }
            val restoreController = WindowCompat.getInsetsController(window, window.decorView)
            restoreController.show(WindowInsetsCompat.Type.systemBars())
            restoreController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            viewModel.saveProgress(exoPlayer.currentPosition, exoPlayer.duration.coerceAtLeast(0))
            gestureView.getPlayerView().player = null
            exoPlayer.release()
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    LaunchedEffect(Unit) {
        viewModel.loadVideo(videoItemId)
    }

    LaunchedEffect(uiState.videoItem) {
        val item = uiState.videoItem ?: return@LaunchedEffect
        val filePath = item.localAbsolutePath
        val file = File(filePath)
        if (!file.exists()) return@LaunchedEffect
        val mediaItem = MediaItem.fromUri(Uri.fromFile(file))
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
                val message = error.message ?: "Unknown playback error"
                Log.e("PlayerScreen", "Playback error: $message", error)
                viewModel.onPlayerError(message)
            }
        }
    }

    DisposableEffect(exoPlayer) {
        exoPlayer.addListener(playerListener)
        onDispose { exoPlayer.removeListener(playerListener) }
    }

    LaunchedEffect(uiState.isPlaying) {
        if (uiState.isPlaying) {
            while (true) {
                val playerPos = exoPlayer.currentPosition.coerceAtLeast(0)
                val playerDur = exoPlayer.duration.coerceAtLeast(0)

                if (uiState.seekTarget >= 0) {
                    if (abs(playerPos - uiState.seekTarget) < 500) {
                        viewModel.clearSeekTarget()
                    }
                }

                // Don't update gestureView.currentPosition during gesture seek —
                // it would cause the seek target to drift since GesturePlayerView
                // uses currentPosition as the base for offset calculation.
                if (!uiState.isGestureSeeking) {
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
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
    }

    fun doExit() {
        if (isExiting) return
        isExiting = true
        viewModel.saveProgress(exoPlayer.currentPosition, exoPlayer.duration.coerceAtLeast(0))
        gestureView.getPlayerView().player = null
        exoPlayer.pause()
        onExit()
    }

    gestureView.setOnSingleTapCallback {
        viewModel.toggleControls()
    }

    gestureView.gestureListener = object : GesturePlayerView.GestureListener {
        override fun onSpeedUpChanged(speedUp: Boolean) {
            viewModel.setLongPressing(speedUp)
            if (speedUp) {
                val multiplier = 2.0f // Or from prefs
                exoPlayer.playbackParameters = PlaybackParameters(multiplier)
            } else {
                exoPlayer.playbackParameters = PlaybackParameters(uiState.playbackSpeed)
            }
        }
        override fun onSeekTo(offsetMs: Long) {
            val newPos = (exoPlayer.currentPosition + offsetMs).coerceIn(0, exoPlayer.duration.coerceAtLeast(0))
            viewModel.onGestureSeekFinished(offsetMs)
            exoPlayer.seekTo(newPos)
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
        override fun onDoubleTap(zone: DoubleTapZone) {
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
                    if (now - uiState.lastAutoSwitchTime < 500L) return
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
                    if (uiState.quickRecycle) {
                        val wasTrashed = uiState.playbackRecord?.isTrashed ?: false
                        viewModel.showOverlay(PlayerOverlay.TrashAction(!wasTrashed))
                        viewModel.toggleTrashed()
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
    }

    var showSettingsPopup by remember { mutableStateOf(false) }
    var bottomAreaWidth by remember { mutableIntStateOf(0) }
    var progressBarHeightPx by remember { mutableIntStateOf(0) }

    val previewExtractor = remember { SeekPreviewExtractor() }
    DisposableEffect(previewExtractor) {
        onDispose {
            previewExtractor.endSession()
        }
    }
    var seekPreviewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var seekPreviewPosition by remember { mutableLongStateOf(0L) }
    var lastExtractedPosMs by remember { mutableLongStateOf(-1L) }

    val isSeekingActive = uiState.isUserSeeking || uiState.isGestureSeeking

    val seekDisplayProgress = when {
        uiState.isUserSeeking -> uiState.userSeekPosition
        uiState.isGestureSeeking -> uiState.gestureSeekPosition
        uiState.duration > 0 -> (currentPosition.toFloat() / uiState.duration.toFloat()).coerceIn(0f, 1f)
        else -> 0f
    }

    LaunchedEffect(isSeekingActive) {
        if (!isSeekingActive) {
            previewExtractor.endSession()
            delay(100L)
            seekPreviewBitmap = null
            return@LaunchedEffect
        }
        val filePath = uiState.videoItem?.localAbsolutePath ?: return@LaunchedEffect
        previewExtractor.beginSession(filePath)
        while (isActive) {
            val progress = when {
                uiState.isUserSeeking -> uiState.userSeekPosition
                uiState.isGestureSeeking -> uiState.gestureSeekPosition
                else -> break
            }
            val duration = uiState.duration
            if (duration <= 0) {
                delay(50)
                continue
            }
            val posMs = (progress * duration).toLong()
            seekPreviewPosition = posMs

            if (posMs == lastExtractedPosMs) {
                delay(50)
                continue
            }

            val cached = previewExtractor.getCachedFrame(filePath, posMs)
            if (cached != null) {
                seekPreviewBitmap = cached
                lastExtractedPosMs = posMs
            } else {
                val bitmap = previewExtractor.extractFrameAtPosition(filePath, posMs)
                lastExtractedPosMs = posMs
                if (bitmap != null && isActive) {
                    seekPreviewBitmap = bitmap
                }
            }
        }
    }

    BackHandler { doExit() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { gestureView },
            modifier = Modifier.fillMaxSize()
        )

        val controlsVisible = !uiState.isFullscreen && uiState.showControls

        if (controlsVisible) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .verticalGradientBackground(
                        listOf(Color.Black.copy(alpha = 0.5f), Color.Transparent),
                        GradientHeight
                    )
                    .statusBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { doExit() },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Exit",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Text(
                        text = uiState.videoItem?.fileName?.substringBeforeLast(".") ?: "",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp)
                    )
                    val isTrashed = uiState.playbackRecord?.isTrashed == true
                    IconButton(
                        onClick = { viewModel.toggleTrashed() },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = if (isTrashed) Icons.Filled.Delete else Icons.Outlined.DeleteOutline,
                            contentDescription = if (isTrashed) "Restore" else "Trash",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .onSizeChanged {
                    bottomAreaWidth = it.width
                }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
            ) {
                val showControlBar = controlsVisible || isSeekingActive
                if (showControlBar) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalGradientBackground(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f)),
                                GradientHeight
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .onSizeChanged { progressBarHeightPx = it.height }
                    ) {
                        PlaybackControlRow(
                            viewModel = viewModel,
                            exoPlayer = exoPlayer,
                            isPlaying = uiState.isPlaying,
                            isFullscreen = uiState.isFullscreen,
                            playbackSpeed = uiState.playbackSpeed,
                            showSettingsPopup = showSettingsPopup,
                            onToggleSettings = { showSettingsPopup = !showSettingsPopup },
                            onDismissSettings = { showSettingsPopup = false }
                        )
                    }
                }
            }
        }

        if (!uiState.showControls && !isSeekingActive) {
            MinimizedProgressBar(viewModel, uiState.duration)
        }

        if (isSeekingActive && seekPreviewBitmap != null) {
            val bitmap = seekPreviewBitmap!!
            val density = LocalDensity.current
            val previewWidthDp = 180.dp
            val previewWidthPx = with(density) { previewWidthDp.toPx() }
            val bitmapRatio = bitmap.height.toFloat() / bitmap.width.toFloat()
            val previewHeightDp = with(density) { (previewWidthPx * bitmapRatio).toDp() }.coerceAtMost(100.dp)

            val sideElementsWidthPx = with(density) { (32.dp + 8.dp + 8.dp + 32.dp).toPx() }
            val progressBarStartPx = with(density) { (32.dp + 8.dp).toPx() }
            val progressBarWidthPx = bottomAreaWidth.toFloat() - sideElementsWidthPx

            val maxOffsetPx = (bottomAreaWidth.toFloat() - previewWidthPx).coerceAtLeast(0f)
            val progressPointPx = progressBarStartPx + seekDisplayProgress * progressBarWidthPx
            val offsetPx = (progressPointPx - previewWidthPx / 2f)
                .coerceIn(0f, maxOffsetPx)
            val offsetDp = with(density) { offsetPx.toDp() }

            val bottomOffsetDp = with(density) { progressBarHeightPx.toDp() }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(bottom = bottomOffsetDp, start = offsetDp)
            ) {
                Box(
                    modifier = Modifier
                        .width(previewWidthDp)
                        .height(previewHeightDp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.7f))
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .background(Color.Black.copy(alpha = 0.6f))
                            .padding(vertical = 2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${formatDuration(seekPreviewPosition)} / ${formatDuration(uiState.duration)}",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }

        PlayerOverlayView(uiState.currentOverlay)

        if (uiState.speedOverlayMultiplier != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 32.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = Color.Black.copy(alpha = 0.6f),
                    tonalElevation = 0.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.FastForward,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = stringResource(R.string.player_speed_up),
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }

        if (uiState.playerError != null) {
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
                        text = uiState.playerError!!,
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

@Composable
private fun PlaybackControlRow(
    viewModel: PlayerViewModel,
    exoPlayer: ExoPlayer,
    isPlaying: Boolean,
    isFullscreen: Boolean,
    playbackSpeed: Float,
    showSettingsPopup: Boolean,
    onToggleSettings: () -> Unit,
    onDismissSettings: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = {
                if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
            },
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = Color.White,
                modifier = Modifier.size(32.dp)
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        SeekProgressBarWithTime(
            viewModel = viewModel,
            exoPlayer = exoPlayer
        )

        Spacer(modifier = Modifier.width(4.dp))

        Box {
            IconButton(
                onClick = onToggleSettings,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Settings",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
            if (showSettingsPopup) {
                SettingsPopup(
                    isFullscreen = isFullscreen,
                    playbackSpeed = playbackSpeed,
                    onDirectionChange = { fullscreen ->
                        viewModel.setFullscreen(fullscreen)
                        viewModel.showOverlay(
                            PlayerOverlay.Orientation(if (fullscreen) "FULLSCREEN" else "ROTATION")
                        )
                        onDismissSettings()
                    },
                    onSpeedChange = { speed ->
                        viewModel.setPlaybackSpeed(speed)
                        onDismissSettings()
                    },
                    onDismiss = onDismissSettings
                )
            }
        }
    }
}

/**
 * High-frequency progress bar + time text.
 * Subscribes to [PlayerViewModel.currentPosition] independently so that
 * 200 ms polling only recomposes this narrow scope — not the surrounding
 * play/pause and settings buttons.
 */
@Composable
private fun RowScope.SeekProgressBarWithTime(
    viewModel: PlayerViewModel,
    exoPlayer: ExoPlayer
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val currentPosition by viewModel.currentPosition.collectAsStateWithLifecycle()

    val seekDisplayProgress = when {
        uiState.isUserSeeking -> uiState.userSeekPosition
        uiState.isGestureSeeking -> uiState.gestureSeekPosition
        uiState.duration > 0 -> (currentPosition.toFloat() / uiState.duration.toFloat()).coerceIn(0f, 1f)
        else -> 0f
    }

    Box(
        modifier = Modifier
            .weight(1f)
            .height(20.dp)
            .pointerInput(uiState.duration) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        viewModel.onUserSeekStarted()
                        var lastP = (down.position.x / size.width).coerceIn(0f, 1f)
                        viewModel.onUserSeekChanged(lastP)
                        down.consume()

                        do {
                            val event = awaitPointerEvent()
                            val change = event.changes.first()
                            change.consume()
                            lastP = (change.position.x / size.width).coerceIn(0f, 1f)
                            viewModel.onUserSeekChanged(lastP)
                        } while (change.pressed)

                        viewModel.onUserSeekFinished(lastP)
                        exoPlayer.seekTo((lastP * uiState.duration).toLong())
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.3f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(seekDisplayProgress)
                    .background(LocalExtendedColors.current.playerProgressFill)
            )
        }
    }

    Text(
        text = "${formatDuration(currentPosition)} / ${formatDuration(uiState.duration)}",
        color = Color.White,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.widthIn(min = 80.dp),
        textAlign = TextAlign.Center,
        fontSize = 11.sp,
    )
}

@Composable
private fun MinimizedProgressBar(viewModel: PlayerViewModel, duration: Long) {
    val currentPosition by viewModel.currentPosition.collectAsStateWithLifecycle()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color.White.copy(alpha = 0.3f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(
                    if (duration > 0)
                        (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                    else 0f
                )
                .background(LocalExtendedColors.current.playerProgressFill)
        )
    }
}

@Composable
private fun SettingsPopup(
    isFullscreen: Boolean,
    playbackSpeed: Float,
    onDirectionChange: (Boolean) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    Popup(
        alignment = Alignment.BottomEnd,
        properties = PopupProperties(focusable = true),
        onDismissRequest = onDismiss
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 200.dp)
                .padding(end = 8.dp, bottom = 8.dp),
            shape = RoundedCornerShape(12.dp),
            color = Color.Black.copy(alpha = 0.85f),
            tonalElevation = 0.dp
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.player_direction),
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelMedium
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconOptionChip(
                        selected = !isFullscreen,
                        onClick = { onDirectionChange(false) },
                        icon = {
                            Icon(
                                imageVector = Icons.Filled.ScreenRotation,
                                contentDescription = null,
                                tint = if (!isFullscreen) Color.White else Color.White.copy(alpha = 0.6f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    )
                    IconOptionChip(
                        selected = isFullscreen,
                        onClick = { onDirectionChange(true) },
                        icon = {
                            Icon(
                                imageVector = Icons.Filled.Fullscreen,
                                contentDescription = null,
                                tint = if (isFullscreen) Color.White else Color.White.copy(alpha = 0.6f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    )
                }
                HorizontalDivider(color = Color.White.copy(alpha = 0.15f))
                Text(
                    text = stringResource(R.string.player_speed),
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelMedium
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(1.0f, 1.5f, 2.0f).forEach { speed ->
                        val label = if (speed == 1.0f) "1x" else "${speed}x"
                        TextOptionChip(
                            selected = playbackSpeed == speed,
                            onClick = { onSpeedChange(speed) },
                            label = label
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun IconOptionChip(
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.size(40.dp),
        shape = RoundedCornerShape(8.dp),
        color = if (selected) Color.White.copy(alpha = 0.3f) else Color.Transparent,
        contentColor = if (selected) Color.White else Color.White.copy(alpha = 0.6f),
        onClick = onClick
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            icon()
        }
    }
}

@Composable
private fun TextOptionChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
) {
    Surface(
        modifier = Modifier.height(32.dp),
        shape = RoundedCornerShape(8.dp),
        color = if (selected) Color.White.copy(alpha = 0.3f) else Color.Transparent,
        contentColor = if (selected) Color.White else Color.White.copy(alpha = 0.6f),
        onClick = onClick
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = 12.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

@Composable
private fun PlayerOverlayView(overlay: PlayerOverlay?) {
    if (overlay == null) return

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Color.Black.copy(alpha = 0.6f),
            tonalElevation = 0.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val icon: ImageVector = when (overlay) {
                    is PlayerOverlay.Seek -> Icons.Filled.Schedule
                    is PlayerOverlay.Volume -> Icons.AutoMirrored.Filled.VolumeUp
                    is PlayerOverlay.Brightness -> Icons.Filled.LightMode
                    is PlayerOverlay.SpeedUp -> Icons.Filled.FastForward
                    is PlayerOverlay.Previous -> Icons.Filled.SkipPrevious
                    is PlayerOverlay.Next -> Icons.Filled.SkipNext
                    is PlayerOverlay.NoPrevious -> Icons.Filled.Block
                    is PlayerOverlay.NoNext -> Icons.Filled.Block
                    is PlayerOverlay.TrashAction -> if (overlay.isIn) Icons.Filled.Delete else Icons.Outlined.DeleteOutline
                    is PlayerOverlay.Orientation -> if (overlay.label == "FULLSCREEN") Icons.Filled.Fullscreen else Icons.Filled.ScreenRotation
                }
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )

                val text: String = when (overlay) {
                    is PlayerOverlay.Seek -> "${formatDuration(overlay.positionMs)} / ${formatDuration(overlay.durationMs)}"
                    is PlayerOverlay.Volume -> "${overlay.percent}%"
                    is PlayerOverlay.Brightness -> "${overlay.percent}%"
                    is PlayerOverlay.SpeedUp -> "${overlay.multiplier}X"
                    is PlayerOverlay.Previous -> "PREVIOUS"
                    is PlayerOverlay.Next -> "NEXT"
                    is PlayerOverlay.NoPrevious -> "NO PREVIOUS"
                    is PlayerOverlay.NoNext -> "NO NEXT"
                    is PlayerOverlay.TrashAction -> if (overlay.isIn) "IN" else "OUT"
                    is PlayerOverlay.Orientation -> overlay.label
                }
                Text(
                    text = text,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
