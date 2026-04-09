package cn.vectory.syncplayer.player

import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import cn.vectory.syncplayer.player.GesturePlayerView.DoubleTapZone
import cn.vectory.syncplayer.ui.util.formatDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.pow

private sealed class PlayerOverlay {
    data class Seek(val positionText: String) : PlayerOverlay()
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
    val videoItem by viewModel.videoItem.collectAsStateWithLifecycle()
    val playbackRecord by viewModel.playbackRecord.collectAsStateWithLifecycle()
    val speedMultiplier by viewModel.speedMultiplier.collectAsStateWithLifecycle()
    val allVideoIds by viewModel.allVideoIds.collectAsStateWithLifecycle()
    val currentGainDb by viewModel.currentGainDb.collectAsStateWithLifecycle()
    val playbackSpeed by viewModel.playbackSpeed.collectAsStateWithLifecycle()

    var showControls by remember { mutableStateOf(true) }
    var currentVideoId by remember { mutableStateOf(videoItemId) }
    var isPlaying by remember { mutableStateOf(true) }
    var isLongPressing by remember { mutableStateOf(false) }

    var currentPosition by remember { mutableLongStateOf(0L) }
    var currentDuration by remember { mutableLongStateOf(0L) }

    var currentOverlay by remember { mutableStateOf<PlayerOverlay?>(null) }
    val overlayHideJob = remember { mutableStateOf<Job?>(null) }

    fun showOverlay(overlay: PlayerOverlay) {
        currentOverlay = overlay
        overlayHideJob.value?.cancel()
        overlayHideJob.value = CoroutineScope(Dispatchers.Main).launch {
            delay(750L)
            currentOverlay = null
        }
    }

    val window = activity.window
    DisposableEffect(Unit) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            WindowCompat.setDecorFitsSystemWindows(window, true)
            val restoreController = WindowCompat.getInsetsController(window, window.decorView)
            restoreController.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            playWhenReady = true
        }
    }
    val gestureView = remember {
        GesturePlayerView(context).apply {
            this.speedMultiplier = speedMultiplier
            getPlayerView().player = exoPlayer
        }
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
        val orientation = viewModel.resolveOrientation(item.width, item.height)
        activity.requestedOrientation = orientation
    }
    val forcedOrientation by viewModel.forcedOrientation.collectAsStateWithLifecycle()
    LaunchedEffect(forcedOrientation) {
        forcedOrientation?.let {
            activity.requestedOrientation = it
        }
    }
    LaunchedEffect(currentGainDb) {
        val linear = 10f.pow(currentGainDb / 20f)
        exoPlayer.volume = linear.coerceIn(0f, 2f)
    }
    LaunchedEffect(exoPlayer) {
        while (true) {
            gestureView.videoDuration = exoPlayer.duration.coerceAtLeast(0)
            gestureView.currentPosition = exoPlayer.currentPosition.coerceAtLeast(0)
            currentPosition = exoPlayer.currentPosition.coerceAtLeast(0)
            currentDuration = exoPlayer.duration.coerceAtLeast(0)
            delay(200L)
        }
    }
    LaunchedEffect(playbackSpeed) {
        if (!isLongPressing) {
            exoPlayer.playbackParameters = PlaybackParameters(playbackSpeed)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            viewModel.saveProgress(exoPlayer.currentPosition, exoPlayer.duration.coerceAtLeast(0))
            exoPlayer.release()
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    var initialVolume by remember { mutableIntStateOf(0) }
    var lastAutoSwitchTime by remember { mutableLongStateOf(0L) }

    fun doExit() {
        viewModel.saveProgress(exoPlayer.currentPosition, exoPlayer.duration.coerceAtLeast(0))
        gestureView.getPlayerView().visibility = android.view.View.INVISIBLE
        exoPlayer.stop()
        onExit()
    }

    fun switchToVideo(newId: Long) {
        viewModel.saveProgress(exoPlayer.currentPosition, exoPlayer.duration.coerceAtLeast(0))
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
                    val wasTrashed = playbackRecord?.isTrashed ?: false
                    showOverlay(PlayerOverlay.TrashAction(!wasTrashed))
                    viewModel.toggleTrashed()
                }
            }
        }
        override fun onSeekOverlay(positionText: String) {
            showOverlay(PlayerOverlay.Seek(positionText))
        }
        override fun onLongPressSpeedOverlay(multiplier: Float) {
            showOverlay(PlayerOverlay.SpeedUp(multiplier))
        }
        override fun onHideOverlay() {
            overlayHideJob.value?.cancel()
            currentOverlay = null
        }
    }

    val progressAlwaysVisible by viewModel.progressAlwaysVisible.collectAsStateWithLifecycle()
    var isUserSeeking by remember { mutableStateOf(false) }
    var userSeekPosition by remember { mutableFloatStateOf(0f) }
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

        if (showControls || progressAlwaysVisible) {
            TopProgressBar(
                currentPosition = currentPosition,
                currentDuration = currentDuration,
                fileName = videoItem?.fileName ?: "",
                isTrashed = playbackRecord?.isTrashed ?: false,
                isUserSeeking = isUserSeeking,
                userSeekPosition = userSeekPosition,
                onSeekChange = { isUserSeeking = true; userSeekPosition = it },
                onSeekConfirm = {
                    exoPlayer.seekTo((it * currentDuration).toLong())
                    isUserSeeking = false
                },
                onExit = { doExit() },
                onToggleTrash = { viewModel.toggleTrashed() },
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
        ) {
            AnimatedVisibility(
                visible = showControls,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                BottomToolbar(
                    isPlaying = isPlaying,
                    playbackSpeed = playbackSpeed,
                    showSpeedMenu = showSpeedMenu,
                    onShowSpeedMenuChange = { showSpeedMenu = it },
                    onPlayPause = {
                        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                    },
                    onRewind = { exoPlayer.seekTo((exoPlayer.currentPosition - 10_000).coerceAtLeast(0)) },
                    onForward = { exoPlayer.seekTo((exoPlayer.currentPosition + 10_000).coerceAtMost(exoPlayer.duration.coerceAtLeast(0))) },
                    onSpeedChange = { speed ->
                        viewModel.setPlaybackSpeed(speed)
                        exoPlayer.playbackParameters = PlaybackParameters(speed)
                        showSpeedMenu = false
                    },
                    isForcedOrientation = forcedOrientation != null,
                    onRotation = {
                        if (forcedOrientation != null) {
                            viewModel.resetForcedOrientation()
                            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                            showOverlay(PlayerOverlay.Orientation("ROTATION"))
                        } else {
                            val item = videoItem ?: return@BottomToolbar
                            val orientation = if ((item.width ?: 0) >= (item.height ?: 0)) {
                                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            } else {
                                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                            }
                            viewModel.setForcedOrientation(orientation)
                            activity.requestedOrientation = orientation
                            showOverlay(PlayerOverlay.Orientation("FULLSCREEN"))
                        }
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
    onSeekChange: (Float) -> Unit,
    onSeekConfirm: (Float) -> Unit,
    onExit: () -> Unit,
    onToggleTrash: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sliderValue = if (isUserSeeking) {
        userSeekPosition
    } else if (currentDuration > 0) {
        currentPosition.toFloat() / currentDuration.toFloat()
    } else {
        0f
    }

    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp)
            .padding(top = 8.dp, bottom = 8.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxWidth()
        ) {
            IconButton(
                onClick = onExit,
                modifier = Modifier.size(36.dp).align(Alignment.CenterStart)
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Exit",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }

            Text(
                text = fileName,
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 48.dp)
            )

            IconButton(
                onClick = onToggleTrash,
                modifier = Modifier.size(36.dp).align(Alignment.CenterEnd)
            ) {
                Icon(
                    imageVector = if (isTrashed) Icons.Filled.Delete else Icons.Outlined.DeleteOutline,
                    contentDescription = if (isTrashed) "Restore" else "Trash",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        ThinProgressSlider(
            progress = sliderValue,
            elapsed = formatDuration(
                if (isUserSeeking) (userSeekPosition * currentDuration).toLong() else currentPosition
            ),
            totalDuration = formatDuration(currentDuration),
            isUserSeeking = isUserSeeking,
            onProgressChange = onSeekChange,
            onProgressChangeFinished = { onSeekConfirm(userSeekPosition) },
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .padding(horizontal = 4.dp)
        )
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
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(2.dp))
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onProgressChange((offset.x / size.width).coerceIn(0f, 1f))
                    onProgressChangeFinished()
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        onProgressChange((offset.x / size.width).coerceIn(0f, 1f))
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        onProgressChange((change.position.x / size.width).coerceIn(0f, 1f))
                    },
                    onDragEnd = { onProgressChangeFinished() },
                    onDragCancel = { onProgressChangeFinished() }
                )
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
    onRewind: () -> Unit,
    onForward: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    isForcedOrientation: Boolean,
    onRotation: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.5f))
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                IconButton(onClick = { onShowSpeedMenuChange(!showSpeedMenu) }) {
                    Icon(
                        imageVector = Icons.Filled.Speed,
                        contentDescription = "Speed",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
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

            IconButton(onClick = onRewind) {
                Icon(
                    imageVector = Icons.Filled.FastRewind,
                    contentDescription = "Rewind",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }

            IconButton(onClick = onPlayPause, modifier = Modifier.size(56.dp)) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }

            IconButton(onClick = onForward) {
                Icon(
                    imageVector = Icons.Filled.FastForward,
                    contentDescription = "Forward",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }

            IconButton(onClick = onRotation) {
                Icon(
                    imageVector = if (isForcedOrientation) Icons.Filled.Fullscreen else Icons.Filled.ScreenRotation,
                    contentDescription = if (isForcedOrientation) "Fullscreen" else "Rotation",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
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
                    is PlayerOverlay.Seek -> overlay.positionText
                    is PlayerOverlay.Volume -> "${overlay.percent}%"
                    is PlayerOverlay.Brightness -> "${overlay.percent}%"
                    is PlayerOverlay.SpeedUp -> "${overlay.multiplier}x"
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
