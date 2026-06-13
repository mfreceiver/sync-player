package cn.vectory.syncplayer.imageviewer

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlin.math.abs
import java.io.File

private val GradientHeight = 80.dp

private fun Modifier.verticalGradientBackground(colors: List<Color>, height: Dp): Modifier =
    this.then(
        Modifier.drawBehind {
            drawRect(
                brush = Brush.verticalGradient(colors, startY = 0f, endY = height.toPx())
            )
        }
    )

@Composable
fun ImageViewerScreen(
    viewModel: ImageViewerViewModel,
    imageItemId: Long,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity ?: return
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var isExiting by remember { mutableStateOf(false) }

    val window = activity.window

    DisposableEffect(Unit) {
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            val restoreController = WindowCompat.getInsetsController(window, window.decorView)
            restoreController.show(WindowInsetsCompat.Type.systemBars())
            restoreController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        }
    }

    LaunchedEffect(Unit) {
        viewModel.loadImage(imageItemId)
    }

    fun doExit() {
        if (isExiting) return
        isExiting = true
        onExit()
    }

    BackHandler { doExit() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        ZoomableImage(
            entity = uiState.currentImage,
            onSingleTap = { viewModel.toggleControls() },
            onDoubleTapLeft = {
                val prevId = viewModel.getPrevImageId()
                if (prevId != null) {
                    viewModel.showOverlay(ImageOverlay.Previous)
                    viewModel.loadImage(prevId)
                } else {
                    viewModel.showOverlay(ImageOverlay.NoPrevious)
                }
            },
            onDoubleTapRight = {
                val nextId = viewModel.getNextImageId()
                if (nextId != null) {
                    viewModel.showOverlay(ImageOverlay.Next)
                    viewModel.loadImage(nextId)
                } else {
                    viewModel.showOverlay(ImageOverlay.NoNext)
                }
            },
            onDoubleTapCenter = {
                val wasTrashed = uiState.playbackRecord?.isTrashed ?: false
                viewModel.showOverlay(ImageOverlay.TrashAction(!wasTrashed))
                viewModel.toggleTrashed()
            },
            onSwipeLeft = {
                val nextId = viewModel.getNextImageId()
                if (nextId != null) {
                    viewModel.showOverlay(ImageOverlay.Next)
                    viewModel.loadImage(nextId)
                } else {
                    viewModel.showOverlay(ImageOverlay.NoNext)
                }
            },
            onSwipeRight = {
                val prevId = viewModel.getPrevImageId()
                if (prevId != null) {
                    viewModel.showOverlay(ImageOverlay.Previous)
                    viewModel.loadImage(prevId)
                } else {
                    viewModel.showOverlay(ImageOverlay.NoPrevious)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        if (uiState.controlsVisible) {
            ImageTopBar(
                filename = uiState.currentImage?.fileName?.substringBeforeLast(".") ?: "",
                isTrashed = uiState.playbackRecord?.isTrashed == true,
                onClose = { doExit() },
                onTrashToggle = { viewModel.toggleTrashed() },
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }

        ImageOverlayView(uiState.currentOverlay)
    }
}

@Composable
private fun ZoomableImage(
    entity: cn.vectory.syncplayer.data.local.entity.VideoItemEntity?,
    onSingleTap: () -> Unit,
    onDoubleTapLeft: () -> Unit,
    onDoubleTapRight: () -> Unit,
    onDoubleTapCenter: () -> Unit,
    onSwipeLeft: () -> Unit,
    onSwipeRight: () -> Unit,
    modifier: Modifier = Modifier
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var canSwipeHorizontal by remember { mutableStateOf(true) }

    LaunchedEffect(scale) {
        if (scale <= 1f) {
            canSwipeHorizontal = true
        }
    }

    Box(
        modifier = modifier
            .pointerInput(Unit) {
                val swipeThreshold = 60f

                awaitEachGesture {
                    val firstDown = awaitFirstDown(requireUnconsumed = false)
                    var totalDragX = 0f
                    var previousCenter: Offset? = null
                    var previousDistance: Float? = null
                    var pointerCount = 1

                    firstDown.consume()

                    do {
                        val event = awaitPointerEvent()
                        val activeChanges = event.changes
                        pointerCount = event.changes.size

                        if (pointerCount >= 2) {
                            val ptrs = event.changes.filter { it.pressed }
                            if (ptrs.size >= 2) {
                                val currentCenter = (ptrs[0].position + ptrs[1].position) / 2f
                                val currentDist = (ptrs[0].position - ptrs[1].position).getDistance()

                                if (previousDistance != null && previousCenter != null && previousDistance > 0f) {
                                    val zoom = currentDist / previousDistance
                                    val pan = currentCenter - previousCenter
                                    val newScale = (scale * zoom).coerceIn(1f, 5f)

                                    if (newScale > 1f) {
                                        val maxOffX = (newScale - 1f) * size.width / 2f
                                        val maxOffY = (newScale - 1f) * size.height / 2f
                                        offsetX = (offsetX + pan.x).coerceIn(-maxOffX, maxOffX)
                                        offsetY = (offsetY + pan.y).coerceIn(-maxOffY, maxOffY)
                                    }
                                    scale = newScale
                                    if (scale <= 1f) {
                                        offsetX = 0f
                                        offsetY = 0f
                                    }
                                }
                                previousCenter = currentCenter
                                previousDistance = currentDist
                                totalDragX = 0f
                            }
                        } else if (pointerCount == 1) {
                            val change = event.changes.firstOrNull { it.id == firstDown.id } ?: continue
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                            val dragX = change.position.x - change.previousPosition.x
                            val dragY = change.position.y - change.previousPosition.y
                            totalDragX += dragX

                            if (scale > 1f) {
                                val maxOffX = (scale - 1f) * size.width / 2f
                                val maxOffY = (scale - 1f) * size.height / 2f
                                val atLeftEdge = offsetX <= -maxOffX + 1f
                                val atRightEdge = offsetX >= maxOffX - 1f
                                canSwipeHorizontal = (atRightEdge && dragX < 0f) || (atLeftEdge && dragX > 0f)
                                offsetX = (offsetX + dragX).coerceIn(-maxOffX, maxOffX)
                                offsetY = (offsetY + dragY).coerceIn(-maxOffY, maxOffY)
                            }
                            previousCenter = null
                            previousDistance = null
                            change.consume()
                        }
                    } while (true)

                    if (abs(totalDragX) > swipeThreshold) {
                        if (scale <= 1f || canSwipeHorizontal) {
                            if (totalDragX > 0f) onSwipeRight() else onSwipeLeft()
                        }
                    }
                    if (scale <= 1f) {
                        offsetX = 0f
                        offsetY = 0f
                    }
                }
            }
            .pointerInput(Unit) {
                val swipeThreshold = 60f
                var totalDragX = 0f

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    totalDragX = 0f
                    val startScale = scale
                    val startOffsetX = offsetX

                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break

                        val dragX = change.position.x - change.previousPosition.x
                        val dragY = change.position.y - change.previousPosition.y
                        totalDragX += dragX

                        if (scale > 1f) {
                            val maxOffX = (scale - 1f) * size.width / 2f
                            val maxOffY = (scale - 1f) * size.height / 2f
                            val newOffsetX = (offsetX + dragX).coerceIn(-maxOffX, maxOffX)
                            val newOffsetY = (offsetY + dragY).coerceIn(-maxOffY, maxOffY)

                            // Detect if we're hitting an edge
                            val atLeftEdge = offsetX <= -maxOffX + 1f
                            val atRightEdge = offsetX >= maxOffX - 1f
                            canSwipeHorizontal = (atRightEdge && dragX < 0f) || (atLeftEdge && dragX > 0f)

                            offsetX = newOffsetX
                            offsetY = newOffsetY
                        }

                        change.consume()
                    } while (event.changes.any { it.id == down.id && it.pressed })

                    if (abs(totalDragX) > swipeThreshold) {
                        if (scale <= 1f || canSwipeHorizontal) {
                            if (totalDragX > 0f) onSwipeRight() else onSwipeLeft()
                        }
                    }

                    if (scale <= 1f) {
                        offsetX = 0f
                        offsetY = 0f
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onSingleTap() },
                    onDoubleTap = { offset ->
                        if (scale > 1f) {
                            scale = 1f
                            offsetX = 0f
                            offsetY = 0f
                        } else {
                            val width = size.width
                            when {
                                offset.x < width / 3 -> onDoubleTapLeft()
                                offset.x > width * 2 / 3 -> onDoubleTapRight()
                                else -> onDoubleTapCenter()
                            }
                        }
                    }
                )
            }
    ) {
        if (entity != null) {
            AsyncImage(
                model = File(entity.localAbsolutePath),
                contentDescription = entity.fileName,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offsetX
                        translationY = offsetY
                    }
            )
        }
    }
}

@Composable
private fun ImageTopBar(
    filename: String,
    isTrashed: Boolean,
    onClose: () -> Unit,
    onTrashToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
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
                onClick = onClose,
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
                text = filename,
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp)
            )
            IconButton(
                onClick = onTrashToggle,
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

@Composable
private fun ImageOverlayView(overlay: ImageOverlay?) {
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
                    is ImageOverlay.Previous -> Icons.Filled.SkipPrevious
                    is ImageOverlay.Next -> Icons.Filled.SkipNext
                    is ImageOverlay.NoPrevious -> Icons.Filled.Block
                    is ImageOverlay.NoNext -> Icons.Filled.Block
                    is ImageOverlay.TrashAction -> if (overlay.isIn) Icons.Filled.Delete else Icons.Outlined.DeleteOutline
                }
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )

                val text: String = when (overlay) {
                    is ImageOverlay.Previous -> "PREVIOUS"
                    is ImageOverlay.Next -> "NEXT"
                    is ImageOverlay.NoPrevious -> "NO PREVIOUS"
                    is ImageOverlay.NoNext -> "NO NEXT"
                    is ImageOverlay.TrashAction -> if (overlay.isIn) "IN" else "OUT"
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
