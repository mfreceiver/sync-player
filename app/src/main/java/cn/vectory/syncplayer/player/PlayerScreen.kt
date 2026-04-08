package cn.vectory.syncplayer.player

import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import cn.vectory.syncplayer.ui.theme.LocalExtendedColors
import cn.vectory.syncplayer.ui.util.formatDuration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import java.io.File

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

    var showControls by remember { mutableStateOf(true) }
    var currentVideoId by remember { mutableStateOf(videoItemId) }

    // Reactive time display — polls exoPlayer position/duration
    var currentPosition by remember { mutableLongStateOf(0L) }
    var currentDuration by remember { mutableLongStateOf(0L) }

    val window = activity.window
    DisposableEffect(Unit) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            WindowCompat.setDecorFitsSystemWindows(window, true)
            val restoreController = WindowCompat.getInsetsController(window, window.decorView)
            restoreController.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    LaunchedEffect(showControls) {
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        if (showControls) {
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        } else {
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
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
    // Reactive time polling
    LaunchedEffect(exoPlayer) {
        while (true) {
            gestureView.videoDuration = exoPlayer.duration.coerceAtLeast(0)
            gestureView.currentPosition = exoPlayer.currentPosition.coerceAtLeast(0)
            currentPosition = exoPlayer.currentPosition.coerceAtLeast(0)
            currentDuration = exoPlayer.duration.coerceAtLeast(0)
            delay(200L)
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
    gestureView.setOnSingleTapCallback {
        showControls = !showControls
    }
    gestureView.gestureListener = object : GesturePlayerView.GestureListener {
        override fun onSpeedUpChanged(speedUp: Boolean) {
            if (speedUp) {
                exoPlayer.playbackParameters = PlaybackParameters(speedMultiplier)
            } else {
                exoPlayer.playbackParameters = PlaybackParameters(1.0f)
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
            gestureView.showVolumeOverlay(percent)
        }
        override fun onBrightnessChanged(delta: Float) {
            val window = activity.window
            val attrs = window.attributes
            val current = if (attrs.screenBrightness < 0) 0.5f else attrs.screenBrightness
            val newBrightness = (current + delta).coerceIn(0f, 1f)
            attrs.screenBrightness = newBrightness
            window.attributes = attrs
            val percent = (newBrightness * 100).toInt()
            gestureView.showBrightnessOverlay(percent)
        }
        override fun onDoubleTap() {
            if (exoPlayer.isPlaying) {
                exoPlayer.pause()
                gestureView.showGestureOverlay("⏸ 已暂停")
            } else {
                exoPlayer.play()
                gestureView.showGestureOverlay("▶ 播放中")
            }
        }
        override fun onTripleTap() {
            val wasFavorite = playbackRecord?.isFavorite ?: false
            gestureView.showGestureOverlay(if (wasFavorite) "取消预删除" else "已标记预删除")
            viewModel.toggleFavorite()
        }
    }
    fun doExit() {
        viewModel.saveProgress(exoPlayer.currentPosition, exoPlayer.duration.coerceAtLeast(0))
        gestureView.getPlayerView().visibility = android.view.View.INVISIBLE
        exoPlayer.stop()
        onExit()
    }
    fun switchToVideo(newId: Long) {
        viewModel.saveProgress(exoPlayer.currentPosition, exoPlayer.duration.coerceAtLeast(0))
        viewModel.resetForcedOrientation()
        currentVideoId = newId
        viewModel.loadVideo(newId)
        showControls = true
    }
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    val nextId = viewModel.getNextVideoId()
                    if (nextId != null) {
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
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { doExit() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = Color.White
                        )
                    }
                    Text(
                        text = videoItem?.fileName ?: "",
                        color = Color.White,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val isFavorite = playbackRecord?.isFavorite ?: false
                        val extendedColors = LocalExtendedColors.current
                        IconButton(onClick = { viewModel.toggleFavorite() }) {
                            Icon(
                                imageVector = if (isFavorite) Icons.Filled.Delete else Icons.Outlined.DeleteOutline,
                                contentDescription = if (isFavorite) "取消预删除" else "预删除",
                                tint = if (isFavorite) extendedColors.favoritePink else Color.White
                            )
                        }
                        IconButton(onClick = {
                            val current = viewModel.forcedOrientation.value
                                ?: activity.requestedOrientation
                            val newOrientation = if (current == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                || current == ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT) {
                                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            } else {
                                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                            }
                            viewModel.setForcedOrientation(newOrientation)
                            activity.requestedOrientation = newOrientation
                        }) {
                            Icon(
                                imageVector = Icons.Default.ScreenRotation,
                                contentDescription = "旋转方向",
                                tint = Color.White
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val currentIdx = allVideoIds.indexOf(currentVideoId)
                    IconButton(
                        onClick = {
                            val prevId = viewModel.getPrevVideoId()
                            if (prevId != null) switchToVideo(prevId)
                        },
                        enabled = currentIdx > 0
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipPrevious,
                            contentDescription = "上一个",
                            tint = Color.White
                        )
                    }
                    Text(
                        text = "${formatDuration(currentPosition)} / ${formatDuration(currentDuration)}",
                        color = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall
                    )
                    // Next button: exit player when at last video in folder
                    IconButton(
                        onClick = {
                            val nextId = viewModel.getNextVideoId()
                            if (nextId != null) {
                                switchToVideo(nextId)
                            } else {
                                doExit()
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "下一个",
                            tint = Color.White
                        )
                    }
                }
            }
        }
    }
}
