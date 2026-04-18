package cn.vectory.syncplayer.player

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.media.AudioManager
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
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
    data object NoPrevious : PlayerOverlay()
    data object NoNext : PlayerOverlay()
    data class TrashAction(val isIn: Boolean) : PlayerOverlay()
    data class Orientation(val label: String) : PlayerOverlay()
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
    val coroutineScope = rememberCoroutineScope()
    val videoItem by viewModel.videoItem.collectAsStateWithLifecycle()
    val playbackRecord by viewModel.playbackRecord.collectAsStateWithLifecycle()
    val speedMultiplier by viewModel.speedMultiplier.collectAsStateWithLifecycle()
    val allVideoIds by viewModel.allVideoIds.collectAsStateWithLifecycle()
    val currentGainDb by viewModel.currentGainDb.collectAsStateWithLifecycle()
    val playbackSpeed by viewModel.playbackSpeed.collectAsStateWithLifecycle()
    val quickRecycle by viewModel.quickRecycle.collectAsStateWithLifecycle()

    var showControls by remember { mutableStateOf(false) }
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

    var speedOverlayMultiplier by remember { mutableStateOf<Float?>(null) }

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

    SideEffect {
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
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
        val record = viewModel.playbackRecord.value
        val isCompleted = record?.isCompleted == true
        if (!isCompleted) {
            val savedPos = viewModel.getSavedPosition()
            if (savedPos > 0) {
                currentPosition = savedPos
                exoPlayer.seekTo(savedPos)
            }
        }
        exoPlayer.prepare()
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
        exoPlayer.playWhenReady = true
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
                    val prevId = viewModel.getPrevVideoId()
                    if (prevId != null) {
                        showOverlay(PlayerOverlay.Previous)
                        switchToVideo(prevId)
                    } else {
                        showOverlay(PlayerOverlay.NoPrevious)
                    }
                }
                DoubleTapZone.NEXT -> {
                    val now = System.currentTimeMillis()
                    if (now - lastAutoSwitchTime < 500L) return
                    val nextId = viewModel.getNextVideoId()
                    if (nextId != null) {
                        showOverlay(PlayerOverlay.Next)
                        switchToVideo(nextId)
                    } else {
                        showOverlay(PlayerOverlay.NoNext)
                    }
                }
                DoubleTapZone.CENTER_TOP -> {
                    if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                }
                DoubleTapZone.CENTER_BOTTOM -> {
                    if (quickRecycle) {
                        val wasTrashed = playbackRecord?.isTrashed ?: false
                        showOverlay(PlayerOverlay.TrashAction(!wasTrashed))
                        viewModel.toggleTrashed()
                    } else {
                        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                    }
                }
            }
        }
        override fun onSeekOverlay(positionMs: Long, durationMs: Long) {
            if (durationMs > 0) {
                isGestureSeeking = true
                gestureSeekPosition = (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
            }
        }
        override fun onLongPressSpeedOverlay(multiplier: Float) {
            speedOverlayMultiplier = multiplier
        }
        override fun onHideOverlay() {
            overlayHideJob.value?.cancel()
            currentOverlay = null
            speedOverlayMultiplier = null
        }
    }

    var showSettingsPopup by remember { mutableStateOf(false) }
    var bottomAreaWidth by remember { mutableIntStateOf(0) }
    var bottomControlHeight by remember { mutableIntStateOf(0) }

    val previewExtractor = remember { SeekPreviewExtractor() }
    var seekPreviewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var seekPreviewPosition by remember { mutableLongStateOf(0L) }

    val isSeekingActive = isUserSeeking || isGestureSeeking || isHighSpeedSeeking

    val seekDisplayProgress = when {
        isUserSeeking -> userSeekPosition
        isGestureSeeking -> gestureSeekPosition
        isHighSpeedSeeking && currentDuration > 0 -> (highSpeedSeekPosition.toFloat() / currentDuration.toFloat()).coerceIn(0f, 1f)
        currentDuration > 0 -> (currentPosition.toFloat() / currentDuration.toFloat()).coerceIn(0f, 1f)
        else -> 0f
    }

    LaunchedEffect(isSeekingActive, seekDisplayProgress, currentDuration) {
        if (isSeekingActive && currentDuration > 0) {
            val posMs = (seekDisplayProgress * currentDuration).toLong()
            seekPreviewPosition = posMs
            val filePath = videoItem?.localAbsolutePath ?: return@LaunchedEffect
            seekPreviewBitmap = previewExtractor.extractFrameAtPosition(filePath, posMs)
        }
    }

    LaunchedEffect(!isSeekingActive) {
        if (!isSeekingActive) {
            delay(100L)
            seekPreviewBitmap = null
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

        val controlsVisible = !isFullscreen && showControls

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
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Exit",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Text(
                        text = videoItem?.fileName?.substringBeforeLast(".") ?: "",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp)
                    )
                    val isTrashed = playbackRecord?.isTrashed == true
                    IconButton(
                        onClick = { viewModel.toggleTrashed() },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = if (isTrashed) Icons.Filled.Delete else Icons.Outlined.DeleteOutline,
                            contentDescription = if (isTrashed) "Restore" else "Trash",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
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
                    bottomControlHeight = it.height
                }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
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

                val showControlBar = controlsVisible || isSeekingActive
                if (showControlBar) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (controlsVisible) Modifier.verticalGradientBackground(
                                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f)),
                                    GradientHeight
                                ) else Modifier
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (controlsVisible) {
                                IconButton(
                                    onClick = {
                                        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                        contentDescription = if (isPlaying) "Pause" else "Play",
                                        tint = Color.White,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }

                            if (controlsVisible) { Spacer(modifier = Modifier.width(8.dp)) }

                            val currentOnChange by rememberUpdatedState { pos: Float ->
                                isUserSeeking = true
                                userSeekPosition = pos
                            }
                            val dragState = remember { mutableFloatStateOf(0f) }
                            val currentOnFinished by rememberUpdatedState {
                                val target = (dragState.floatValue * currentDuration).toLong()
                                seekTarget = target
                                currentPosition = target
                                exoPlayer.seekTo(target)
                                isUserSeeking = false
                            }

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(20.dp)
                                    .pointerInput(Unit) {
                                        awaitPointerEventScope {
                                            while (true) {
                                                val down = awaitFirstDown(requireUnconsumed = false)
                                                val p = (down.position.x / size.width).coerceIn(0f, 1f)
                                                dragState.floatValue = p
                                                currentOnChange(p)
                                                down.consume()

                                                do {
                                                    val event = awaitPointerEvent()
                                                    val change = event.changes.first()
                                                    change.consume()
                                                    val cp = (change.position.x / size.width).coerceIn(0f, 1f)
                                                    dragState.floatValue = cp
                                                    currentOnChange(cp)
                                                } while (change.pressed)

                                                currentOnFinished()
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
                                        .background(Color.White)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .fillMaxWidth(seekDisplayProgress)
                                            .background(LocalExtendedColors.current.playerProgressFill)
                                    )
                                }
                            }

                            if (controlsVisible) { Spacer(modifier = Modifier.width(8.dp)) }

                            if (controlsVisible) {
                                Box {
                                    IconButton(
                                        onClick = { showSettingsPopup = !showSettingsPopup },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Settings,
                                            contentDescription = "Settings",
                                            tint = Color.White,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    if (showSettingsPopup) {
                                        SettingsPopup(
                                            isFullscreen = isFullscreen,
                                            playbackSpeed = playbackSpeed,
                                            onDirectionChange = { fullscreen ->
                                                if (fullscreen) {
                                                    if (!isFullscreen) viewModel.toggleFullscreen()
                                                } else {
                                                    if (isFullscreen) viewModel.toggleFullscreen()
                                                }
                                                showOverlay(
                                                    PlayerOverlay.Orientation(if (fullscreen) "FULLSCREEN" else "ROTATION")
                                                )
                                                showSettingsPopup = false
                                            },
                                            onSpeedChange = { speed ->
                                                viewModel.setPlaybackSpeed(speed)
                                                exoPlayer.playbackParameters = PlaybackParameters(speed)
                                                showSettingsPopup = false
                                            },
                                            onDismiss = { showSettingsPopup = false }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

        }

        if (!showControls) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .align(Alignment.BottomCenter)
                    .background(Color.White.copy(alpha = 0.3f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(
                            if (currentDuration > 0)
                                (currentPosition.toFloat() / currentDuration.toFloat()).coerceIn(0f, 1f)
                            else 0f
                        )
                        .background(LocalExtendedColors.current.playerProgressFill)
                )
            }
        }

        if (isSeekingActive && seekPreviewBitmap != null) {
            val bitmap = seekPreviewBitmap!!
            val density = LocalDensity.current
            val previewWidthDp = 180.dp
            val previewWidthPx = with(density) { previewWidthDp.toPx() }
            val bitmapRatio = bitmap.height.toFloat() / bitmap.width.toFloat()
            val previewHeightDp = with(density) { (previewWidthPx * bitmapRatio).toDp() }.coerceAtMost(100.dp)

            val sideElementsWidthPx = if (controlsVisible) {
                with(density) { (32.dp + 8.dp + 8.dp + 32.dp).toPx() }
            } else 0f
            val progressBarStartPx = if (controlsVisible) {
                with(density) { (32.dp + 8.dp).toPx() }
            } else 0f
            val progressBarWidthPx = bottomAreaWidth.toFloat() - sideElementsWidthPx

            val maxOffsetPx = (bottomAreaWidth.toFloat() - previewWidthPx).coerceAtLeast(0f)
            val progressPointPx = progressBarStartPx + seekDisplayProgress * progressBarWidthPx
            val offsetPx = (progressPointPx - previewWidthPx / 2f)
                .coerceIn(0f, maxOffsetPx)
            val offsetDp = with(density) { offsetPx.toDp() }

            val bottomOffsetDp = with(density) { bottomControlHeight.toDp() } + 8.dp

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
                            text = "${formatDuration(seekPreviewPosition)} / ${formatDuration(currentDuration)}",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }

        PlayerOverlayView(currentOverlay)

        val currentSpeedOverlay = speedOverlayMultiplier
        if (currentSpeedOverlay != null) {
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
                            text = "加速",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
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
                    text = "方向",
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
                    text = "速度",
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
