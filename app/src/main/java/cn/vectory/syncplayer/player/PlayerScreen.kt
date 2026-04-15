package cn.vectory.syncplayer.player

import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs
import kotlin.math.pow

private sealed class PlayerOverlay {
    data class Seek(val positionMs: Long, val durationMs: Long) : PlayerOverlay()
    data class Volume(val percent: Int) : PlayerOverlay()
    data class Brightness(val percent: Int) : PlayerOverlay()
    data class SpeedUp(val multiplier: Float) : PlayerOverlay()
    data object Previous : PlayerOverlay()
    data object Next : PlayerOverlay()
    data class TrashAction(val isIn: Boolean) : PlayerOverlay()
    data class Orientation(val label: String) : PlayerOverlay()
}

@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    videoItemId: Long,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? android.app.Activity ?: return
    val coroutineScope = rememberCoroutineScope()
    val videoItem by viewModel.videoItem.collectAsStateWithLifecycle()
    val playbackRecord by viewModel.playbackRecord.collectAsStateWithLifecycle()
    val speedMultiplier by viewModel.speedMultiplier.collectAsStateWithLifecycle()
    val allVideoIds by viewModel.allVideoIds.collectAsStateWithLifecycle()
    val currentGainDb by viewModel.currentGainDb.collectAsStateWithLifecycle()
    val playbackSpeed by viewModel.playbackSpeed.collectAsStateWithLifecycle()
    val doubleTapAction by viewModel.doubleTapAction.collectAsStateWithLifecycle()

    var showControls by remember { mutableStateOf(true) }
    var currentVideoId by remember { mutableStateOf(videoItemId) }
    var isPlaying by remember { mutableStateOf(true) }
    var isLongPressing by remember { mutableStateOf(false) }

    var currentPosition by remember { mutableLongStateOf(0L) }
    var currentDuration by remember { mutableLongStateOf(0L) }

    var currentOverlay by remember { mutableStateOf<PlayerOverlay?>(null) }
    val overlayHideJob = remember { mutableStateOf<Job?>(null) }

    var isGestureSeeking by remember { mutableStateOf(false) }
    var gestureSeekPosition by remember { mutableFloatStateOf(0f) }
    var seekTarget by remember { mutableLongStateOf(-1L) }
    var isUserSeeking by remember { mutableStateOf(false) }
    var userSeekPosition by remember { mutableFloatStateOf(0f) }

    var isHighSpeedSeeking by remember { mutableStateOf(false) }
    var highSpeedSeekPosition by remember { mutableLongStateOf(0L) }

    var originalBrightness by remember { mutableFloatStateOf(-1f) }

    fun showOverlay(overlay: PlayerOverlay) {
        currentOverlay = overlay
        overlayHideJob.value?.cancel()
        overlayHideJob.value = coroutineScope.launch {
            delay(750L)
            currentOverlay = null
        }
    }

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

    LaunchedEffect(speedMultiplier) {
        gestureView.speedMultiplier = speedMultiplier
    }

    LaunchedEffect(Unit) {
        viewModel.loadVideo(videoItemId)
        currentVideoId = videoItemId
    }

    LaunchedEffect(videoItem) {
        val item = videoItem ?: return@LaunchedEffect
        val filePath = item.localAbsolutePath
        val file = File(filePath)
        if (!file.exists()) return@LaunchedEffect
        val mediaItem = MediaItem.fromUri(Uri.fromFile(file))
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        val record = viewModel.playbackRecord.value
        val isCompleted = record?.isCompleted == true
        if (!isCompleted) {
            val savedPos = viewModel.getSavedPosition()
            if (savedPos > 0) {
                exoPlayer.seekTo(savedPos)
            }
        }
    }
    val isFullscreen by viewModel.isFullscreen.collectAsStateWithLifecycle()
    LaunchedEffect(isFullscreen, videoItem) {
        val item = videoItem
        activity.requestedOrientation = if (item != null) {
            viewModel.resolveOrientation(item.width, item.height)
        } else {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    LaunchedEffect(currentGainDb) {
        val linear = 10f.pow(currentGainDb / 20f)
        exoPlayer.volume = linear.coerceIn(0f, 2f)
    }
// Step 1: Replace 200ms polling with event-driven updates
// Maintain current/duration only during playback, and keep gesture state in sync via a listener
val playerListener = remember {
    object : androidx.media3.common.Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            val playerDur = exoPlayer.duration.coerceAtLeast(0)
            if (currentDuration != playerDur) {
                currentDuration = playerDur
            }
            gestureView.videoDuration = playerDur
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // Ensure duration is up-to-date while playing/pausing
            gestureView.videoDuration = exoPlayer.duration.coerceAtLeast(0)
        }
    }
}
DisposableEffect(exoPlayer) {
    exoPlayer.addListener(playerListener)
    onDispose { exoPlayer.removeListener(playerListener) }
}
LaunchedEffect(exoPlayer) {
    while (true) {
        if (exoPlayer.isPlaying) {
            val playerPos = exoPlayer.currentPosition.coerceAtLeast(0)
            val playerDur = exoPlayer.duration.coerceAtLeast(0)
            gestureView.videoDuration = playerDur
            gestureView.currentPosition = playerPos
            if (currentDuration != playerDur) {
                currentDuration = playerDur
            }
            if (seekTarget >= 0) {
                if (abs(playerPos - seekTarget) < 500) {
                    seekTarget = -1L
                    currentPosition = playerPos
                }
            } else if (currentPosition != playerPos) {
                currentPosition = playerPos
            }
        }
        delay(200L)
    }
}
    LaunchedEffect(playbackSpeed) {
        if (!isLongPressing) {
            exoPlayer.playbackParameters = PlaybackParameters(playbackSpeed)
        }
    }

    val lifecycleOwner = activity as? ComponentActivity
    if (lifecycleOwner != null) {
        DisposableEffect(lifecycleOwner.lifecycle) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_STOP -> {
                        if (exoPlayer.isPlaying) {
                            exoPlayer.pause()
                        }
                    }
                    else -> {}
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
    }

    var initialVolume by remember { mutableIntStateOf(0) }
    var lastAutoSwitchTime by remember { mutableLongStateOf(0L) }
    var isExiting by remember { mutableStateOf(false) }

    fun doExit() {
        if (isExiting) return
        isExiting = true
        viewModel.saveProgress(exoPlayer.currentPosition, exoPlayer.duration.coerceAtLeast(0))
        gestureView.getPlayerView().player = null
        exoPlayer.pause()
        onExit()
    }

    fun switchToVideo(newId: Long) {
        viewModel.saveProgress(exoPlayer.currentPosition, exoPlayer.duration.coerceAtLeast(0))
        seekTarget = -1L
        isUserSeeking = false
        isGestureSeeking = false
        gestureSeekPosition = 0f
        userSeekPosition = 0f
        currentPosition = 0L
        currentDuration = 0L
        currentVideoId = newId
        viewModel.loadVideo(newId)
        showControls = true
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    seekTarget = -1L
                    val nextId = viewModel.getNextVideoId()
                    if (nextId != null) {
                        lastAutoSwitchTime = System.currentTimeMillis()
                        switchToVideo(nextId)
                    } else {
                        doExit()
                    }
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    gestureView.setOnSingleTapCallback {
        showControls = !showControls
    }
    gestureView.gestureListener = object : GesturePlayerView.GestureListener {
        override fun onSpeedUpChanged(speedUp: Boolean) {
            isLongPressing = speedUp
            if (speedUp) {
                exoPlayer.playbackParameters = PlaybackParameters(speedMultiplier)
            } else {
                exoPlayer.playbackParameters = PlaybackParameters(playbackSpeed)
            }
        }
        override fun onSeekTo(offsetMs: Long) {
            val newPos = (exoPlayer.currentPosition + offsetMs).coerceIn(0, exoPlayer.duration.coerceAtLeast(0))
            seekTarget = newPos
            currentPosition = newPos
            isGestureSeeking = false
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
            showOverlay(PlayerOverlay.Volume(percent))
        }
        override fun onBrightnessChanged(delta: Float) {
            val attrs = window.attributes
            val current = if (attrs.screenBrightness < 0) 0.5f else attrs.screenBrightness
            val newBrightness = (current + delta).coerceIn(0f, 1f)
            attrs.screenBrightness = newBrightness
            window.attributes = attrs
            val percent = (newBrightness * 100).toInt()
            showOverlay(PlayerOverlay.Brightness(percent))
        }
        override fun onDoubleTap(zone: DoubleTapZone) {
            when (zone) {
                DoubleTapZone.PREVIOUS -> {
                    showOverlay(PlayerOverlay.Previous)
                    val prevId = viewModel.getPrevVideoId()
                    if (prevId != null) switchToVideo(prevId)
                }
                DoubleTapZone.NEXT -> {
                    val now = System.currentTimeMillis()
                    if (now - lastAutoSwitchTime < 500L) return
                    showOverlay(PlayerOverlay.Next)
                    val nextId = viewModel.getNextVideoId()
                    if (nextId != null) {
                        switchToVideo(nextId)
                    }
                }
                DoubleTapZone.CENTER -> {
                    when (doubleTapAction) {
                        PlayerViewModel.DoubleTapAction.TRASH -> {
                            val wasTrashed = playbackRecord?.isTrashed ?: false
                            showOverlay(PlayerOverlay.TrashAction(!wasTrashed))
                            viewModel.toggleTrashed()
                        }
                        PlayerViewModel.DoubleTapAction.PLAY_PAUSE -> {
                            if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                        }
                    }
                }
            }
        }
        override fun onSeekOverlay(positionMs: Long, durationMs: Long) {
            if (durationMs > 0) {
                isGestureSeeking = true
                gestureSeekPosition = (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
            }
            showOverlay(PlayerOverlay.Seek(positionMs, durationMs))
        }
        override fun onLongPressSpeedOverlay(multiplier: Float) {
            showOverlay(PlayerOverlay.SpeedUp(multiplier))
        }
        override fun onHideOverlay() {
            overlayHideJob.value?.cancel()
            currentOverlay = null
        }
    }

    var showSpeedMenu by remember { mutableStateOf(false) }

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

        val topBarVisible = !isFullscreen && (showControls || isHighSpeedSeeking)

        if (topBarVisible) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(LocalExtendedColors.current.playerTopBar)
                    .statusBarsPadding()
            ) {
                TopProgressBar(
                    currentPosition = currentPosition,
                    currentDuration = currentDuration,
                    fileName = videoItem?.fileName ?: "",
                    isTrashed = playbackRecord?.isTrashed ?: false,
                    isUserSeeking = isUserSeeking,
                    userSeekPosition = userSeekPosition,
                    isGestureSeeking = isGestureSeeking,
                    gestureSeekPosition = gestureSeekPosition,
                    isHighSpeedSeeking = isHighSpeedSeeking,
                    highSpeedSeekPosition = highSpeedSeekPosition,
                    onSeekChange = { isUserSeeking = true; userSeekPosition = it },
                    onSeekConfirm = {
                        val target = (it * currentDuration).toLong()
                        seekTarget = target
                        currentPosition = target
                        exoPlayer.seekTo(target)
                        isUserSeeking = false
                    },
                    onExit = { doExit() },
                    onToggleTrash = { viewModel.toggleTrashed() },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (showControls) 48.dp else 96.dp)
                    .pointerInput(currentDuration) {
                        awaitPointerEventScope {
                            while (true) {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val startX = down.position.x
                                val basePosition = exoPlayer.currentPosition
                                val dur = currentDuration
                                if (dur <= 0) continue
                                down.consume()
                                isHighSpeedSeeking = true

                                do {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.first()
                                    change.consume()
                                    val dx = change.position.x - startX
                                    val ratio = dx / size.width
                                    val targetMs = (basePosition + ratio * dur).toLong()
                                        .coerceIn(0, dur)
                                    highSpeedSeekPosition = targetMs
                                    showOverlay(PlayerOverlay.Seek(targetMs, dur))
                                } while (change.pressed)

                                val finalTarget = highSpeedSeekPosition
                                seekTarget = finalTarget
                                currentPosition = finalTarget
                                exoPlayer.seekTo(finalTarget)
                                isHighSpeedSeeking = false
                                overlayHideJob.value?.cancel()
                                overlayHideJob.value = coroutineScope.launch {
                                    delay(750L)
                                    currentOverlay = null
                                }
                            }
                        }
                    }
            )
            if (!isFullscreen && showControls) {
                BottomToolbar(
                    isPlaying = isPlaying,
                    playbackSpeed = playbackSpeed,
                    showSpeedMenu = showSpeedMenu,
                    onShowSpeedMenuChange = { showSpeedMenu = it },
                    onPlayPause = {
                        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                    },
                    onPrev = {
                        val prevId = viewModel.getPrevVideoId()
                        if (prevId != null) {
                            showOverlay(PlayerOverlay.Previous)
                            switchToVideo(prevId)
                        }
                    },
                    onNext = {
                        val nextId = viewModel.getNextVideoId()
                        if (nextId != null) {
                            showOverlay(PlayerOverlay.Next)
                            switchToVideo(nextId)
                        }
                    },
                    onRewind = { exoPlayer.seekTo((exoPlayer.currentPosition - 10_000).coerceAtLeast(0)) },
                    onForward = { exoPlayer.seekTo((exoPlayer.currentPosition + 10_000).coerceAtMost(exoPlayer.duration.coerceAtLeast(0))) },
                    onSpeedChange = { speed ->
                        viewModel.setPlaybackSpeed(speed)
                        exoPlayer.playbackParameters = PlaybackParameters(speed)
                        showSpeedMenu = false
                    },
                    isFullscreen = isFullscreen,
                    onRotation = {
                        viewModel.toggleFullscreen()
                        showOverlay(PlayerOverlay.Orientation(if (isFullscreen) "ROTATION" else "FULLSCREEN"))
                    }
                )
            }
        }

        PlayerOverlayView(currentOverlay)
    }
}

@Composable
private fun TopProgressBar(
    currentPosition: Long,
    currentDuration: Long,
    fileName: String,
    isTrashed: Boolean,
    isUserSeeking: Boolean,
    userSeekPosition: Float,
    isGestureSeeking: Boolean,
    gestureSeekPosition: Float,
    isHighSpeedSeeking: Boolean,
    highSpeedSeekPosition: Long,
    onSeekChange: (Float) -> Unit,
    onSeekConfirm: (Float) -> Unit,
    onExit: () -> Unit,
    onToggleTrash: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sliderValue = when {
        isUserSeeking -> userSeekPosition
        isGestureSeeking -> gestureSeekPosition
        isHighSpeedSeeking && currentDuration > 0 -> (highSpeedSeekPosition.toFloat() / currentDuration.toFloat()).coerceIn(0f, 1f)
        currentDuration > 0 -> (currentPosition.toFloat() / currentDuration.toFloat()).coerceIn(0f, 1f)
        else -> 0f
    }

    val isSeeking = isUserSeeking || isGestureSeeking || isHighSpeedSeeking
    val displayElapsed = when {
        isUserSeeking -> (userSeekPosition * currentDuration).toLong()
        isGestureSeeking -> (gestureSeekPosition * currentDuration).toLong()
        isHighSpeedSeeking -> highSpeedSeekPosition
        else -> currentPosition
    }
    val elapsedText = formatDuration(displayElapsed)
    val totalDurationText = formatDuration(currentDuration)

    val shouldMarquee = remember(fileName) { fileName.length > 30 }
    val textMeasurer = rememberTextMeasurer()

    val currentOnChange by rememberUpdatedState(onSeekChange)
    var dragProgress by remember { mutableFloatStateOf(0f) }
    val currentOnFinished by rememberUpdatedState({ onSeekConfirm(dragProgress) })

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(LocalExtendedColors.current.playerTopBar),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onExit,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Exit",
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val p = (down.position.x / size.width).coerceIn(0f, 1f)
                            dragProgress = p
                            currentOnChange(p)
                            down.consume()

                            do {
                                val event = awaitPointerEvent()
                                val change = event.changes.first()
                                change.consume()
                                val cp = (change.position.x / size.width).coerceIn(0f, 1f)
                                dragProgress = cp
                                currentOnChange(cp)
                            } while (change.pressed)

                            currentOnFinished()
                        }
                    }
                }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LocalExtendedColors.current.playerProgressTrack.copy(alpha = 0.75f))
            )
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(sliderValue)
                    .background(LocalExtendedColors.current.playerProgressFill.copy(alpha = 0.75f))
            )
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = elapsedText,
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.widthIn(min = 40.dp)
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (shouldMarquee) {
                        val infiniteTransition = rememberInfiniteTransition(label = "marquee")
                        val scrollOffset by infiniteTransition.animateFloat(
                            initialValue = 0f,
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(durationMillis = 8000, easing = LinearEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "marqueeOffset"
                        )
                        val textStyle = MaterialTheme.typography.labelMedium
                        val textLayoutResult = remember(fileName) {
                            textMeasurer.measure(fileName, textStyle)
                        }
                        Canvas(modifier = Modifier.fillMaxWidth().height(20.dp)) {
                            val availableWidth = size.width
                            val maxOffset = (textLayoutResult.size.width.toFloat() - availableWidth).coerceAtLeast(0f)
                            val offsetX = -scrollOffset * maxOffset
                            drawText(
                                textLayoutResult = textLayoutResult,
                                color = Color.White,
                                topLeft = Offset(offsetX, (size.height - textLayoutResult.size.height) / 2f)
                            )
                        }
                    } else {
                        Text(
                            text = fileName,
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                Text(
                    text = totalDurationText,
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
        IconButton(
            onClick = onToggleTrash,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = if (isTrashed) Icons.Filled.Delete else Icons.Outlined.DeleteOutline,
                contentDescription = if (isTrashed) "Restore" else "Trash",
                tint = Color.White,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
private fun ThinProgressSlider(
    progress: Float,
    elapsed: String,
    totalDuration: String,
    isUserSeeking: Boolean,
    onProgressChange: (Float) -> Unit,
    onProgressChangeFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currentOnChange by rememberUpdatedState(onProgressChange)
    val currentOnFinished by rememberUpdatedState(onProgressChangeFinished)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(2.dp))
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        currentOnChange((down.position.x / size.width).coerceIn(0f, 1f))
                        down.consume()

                        do {
                            val event = awaitPointerEvent()
                            val change = event.changes.first()
                            change.consume()
                            currentOnChange((change.position.x / size.width).coerceIn(0f, 1f))
                        } while (change.pressed)

                        currentOnFinished()
                    }
                }
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White.copy(alpha = 0.3f))
        )
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress)
                .background(Color.White)
        )
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = if (isUserSeeking) "$elapsed / $totalDuration" else elapsed,
                color = Color.Black.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall
            )
            if (!isUserSeeking) {
                Text(
                    text = totalDuration,
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
private fun BottomToolbar(
    isPlaying: Boolean,
    playbackSpeed: Float,
    showSpeedMenu: Boolean,
    onShowSpeedMenuChange: (Boolean) -> Unit,
    onPlayPause: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    isFullscreen: Boolean,
    onRotation: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = 4.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                IconButton(
                    onClick = { onShowSpeedMenuChange(!showSpeedMenu) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Speed,
                        contentDescription = "Speed",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
                DropdownMenu(
                    expanded = showSpeedMenu,
                    onDismissRequest = { onShowSpeedMenuChange(false) },
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.85f))
                ) {
                    listOf(1.0f, 1.5f, 2.0f).forEach { speed ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (speed == 1.0f) "1x" else "${speed}x",
                                    color = if (playbackSpeed == speed) Color.White else Color.White.copy(alpha = 0.7f)
                                )
                            },
                            onClick = { onSpeedChange(speed) },
                            trailingIcon = if (playbackSpeed == speed) {
                                { Text("✓", color = Color.White, style = MaterialTheme.typography.bodySmall) }
                            } else null
                        )
                    }
                }
            }

            IconButton(onClick = onPrev, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Filled.SkipPrevious,
                    contentDescription = "Previous",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }

            IconButton(onClick = onRewind, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Filled.FastRewind,
                    contentDescription = "Rewind",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }

            IconButton(onClick = onPlayPause, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }

            IconButton(onClick = onForward, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Filled.FastForward,
                    contentDescription = "Forward",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }

            IconButton(onClick = onNext, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Filled.SkipNext,
                    contentDescription = "Next",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }

            IconButton(onClick = onRotation, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = if (isFullscreen) Icons.Filled.Fullscreen else Icons.Filled.ScreenRotation,
                    contentDescription = if (isFullscreen) "Fullscreen" else "Rotation",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
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
