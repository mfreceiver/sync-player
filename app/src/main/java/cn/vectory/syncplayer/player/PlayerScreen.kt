package cn.vectory.syncplayer.player

import android.content.Context
import android.media.AudioManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import java.io.File

@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    videoItemId: Long,
    onExit: () -> Unit
) {
    val context = LocalContext.current
    val videoItem by viewModel.videoItem.collectAsStateWithLifecycle()
    val playbackRecord by viewModel.playbackRecord.collectAsStateWithLifecycle()
    val speedMultiplier by viewModel.speedMultiplier.collectAsStateWithLifecycle()

    var showResumeDialog by remember { mutableStateOf(false) }
    var playerReady by remember { mutableStateOf(false) }

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

    LaunchedEffect(videoItemId) {
        viewModel.loadVideo(videoItemId)
    }

    LaunchedEffect(videoItem) {
        val filePath = viewModel.getVideoFilePath() ?: return@LaunchedEffect
        val file = File(filePath)
        if (!file.exists()) return@LaunchedEffect

        val mediaItem = MediaItem.fromUri(Uri.fromFile(file))
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()

        val savedPos = viewModel.getSavedPosition()
        if (savedPos > 0) {
            showResumeDialog = true
        }
        playerReady = true
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.saveProgress(exoPlayer.currentPosition, exoPlayer.duration.coerceAtLeast(0))
            exoPlayer.release()
        }
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

        override fun onVolumeChanged(delta: Float) {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val newVolume = (currentVolume + (delta * maxVolume).toInt()).coerceIn(0, maxVolume)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
            val percent = (newVolume * 100 / maxVolume).coerceIn(0, 100)
            gestureView.showVolumeOverlay(percent)
        }

        override fun onBrightnessChanged(delta: Float) {
            val activity = context as? android.app.Activity ?: return
            val window = activity.window
            val attrs = window.attributes
            val current = if (attrs.screenBrightness < 0) 0.5f else attrs.screenBrightness
            val newBrightness = (current + delta).coerceIn(0f, 1f)
            attrs.screenBrightness = newBrightness
            window.attributes = attrs
            val percent = (newBrightness * 100).toInt()
            gestureView.showBrightnessOverlay(percent)
        }
    }

    BackHandler {
        viewModel.saveProgress(exoPlayer.currentPosition, exoPlayer.duration.coerceAtLeast(0))
        exoPlayer.release()
        onExit()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { gestureView },
            modifier = Modifier.fillMaxSize()
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopStart)
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                viewModel.saveProgress(exoPlayer.currentPosition, exoPlayer.duration.coerceAtLeast(0))
                exoPlayer.release()
                onExit()
            }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = Color.White
                )
            }

            Text(
                text = videoItem?.fileName ?: "",
                color = Color.White,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
            )

            val isFavorite = playbackRecord?.isFavorite ?: false
            IconButton(onClick = { viewModel.toggleFavorite() }) {
                Icon(
                    imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = if (isFavorite) "取消喜欢" else "喜欢",
                    tint = if (isFavorite) Color(0xFFFF4081) else Color.White
                )
            }
        }
    }

    if (showResumeDialog) {
        val savedPos = viewModel.getSavedPosition()
        AlertDialog(
            onDismissRequest = {
                showResumeDialog = false
                exoPlayer.seekTo(0)
            },
            title = { Text("继续播放") },
            text = { Text("上次播放到 ${formatTime(savedPos)}，是否继续？") },
            confirmButton = {
                TextButton(onClick = {
                    showResumeDialog = false
                    exoPlayer.seekTo(savedPos)
                }) {
                    Text("继续播放")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showResumeDialog = false
                    exoPlayer.seekTo(0)
                }) {
                    Text("从头播放")
                }
            }
        )
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
