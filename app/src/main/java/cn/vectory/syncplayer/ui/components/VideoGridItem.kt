package cn.vectory.syncplayer.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.vectory.syncplayer.R
import cn.vectory.syncplayer.provider.MediaUris
import cn.vectory.syncplayer.ui.theme.LocalExtendedColors
import cn.vectory.syncplayer.ui.util.formatDuration
import cn.vectory.syncplayer.ui.util.isVideoFile
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest

// Bound card height to 1/3..3 times its width: keep thumbnails readable and
// prevent extreme metadata from producing near-zero or abnormal measured sizes.
internal const val VIDEO_GRID_MIN_ASPECT_RATIO = 1f / 3f
internal const val VIDEO_GRID_MAX_ASPECT_RATIO = 3f

internal fun resolveVideoGridAspectRatio(staggeredMode: Boolean, isImage: Boolean, isVideo: Boolean, width: Int?, height: Int?): Float =
    if (staggeredMode && (isImage || isVideo) && width != null && height != null && width > 0 && height > 0) {
        (width.toFloat() / height.toFloat()).coerceIn(VIDEO_GRID_MIN_ASPECT_RATIO, VIDEO_GRID_MAX_ASPECT_RATIO)
    } else 4f / 3f

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun VideoGridItem(
    videoItemId: Long?,
    fileName: String,
    duration: Long?,
    width: Int?,
    height: Int?,
    playbackPosition: Long,
    playbackDuration: Long,
    isCompleted: Boolean,
    isTrashed: Boolean,
    isVideo: Boolean,
    modifier: Modifier = Modifier,
    isImage: Boolean = false,
    star: Int = 0,
    staggeredMode: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val extendedColors = LocalExtendedColors.current

    val thumbnailModifier = Modifier.fillMaxWidth().aspectRatio(
        resolveVideoGridAspectRatio(staggeredMode, isImage, isVideo, width, height)
    )

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .semantics { contentDescription = fileName }
    ) {
      Box(modifier = Modifier.fillMaxWidth()) {
        if (isImage) {
            Box(
                modifier = thumbnailModifier.background(Color(0xFF1A1A1A)),
                contentAlignment = Alignment.Center
            ) {
                GridThumbnail(
                    videoItemId = videoItemId,
                    isVideo = isVideo,
                    isImage = isImage,
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else {
            GridThumbnail(
                videoItemId = videoItemId,
                isVideo = isVideo,
                isImage = isImage,
                modifier = thumbnailModifier
            )
        }

        Canvas(
            modifier = thumbnailModifier.align(Alignment.TopStart)
        ) {
            val gradientHeight = size.height * 0.35f
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)),
                    startY = size.height - gradientHeight,
                    endY = size.height
                ),
                topLeft = Offset(0f, size.height - gradientHeight),
                size = Size(size.width, gradientHeight)
            )
        }

        if (isVideo) {
            val progress = when {
                isCompleted -> 1f
                playbackPosition > 0 && playbackDuration > 0 ->
                    (playbackPosition.toFloat() / playbackDuration.toFloat()).coerceIn(0f, 1f)
                else -> 0f
            }

            if (progress > 0f) {
                val progressColor = MaterialTheme.colorScheme.primary

                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                ) {
                    val barHeight = 3.dp.toPx()
                    drawRect(
                        color = Color.White.copy(alpha = 0.25f),
                        topLeft = Offset(0f, size.height - barHeight),
                        size = Size(size.width, barHeight)
                    )
                    drawRect(
                        color = progressColor.copy(alpha = 0.6f),
                        topLeft = Offset(0f, size.height - barHeight),
                        size = Size(size.width * progress, barHeight)
                    )
                }
            }
        }

        val showDuration = duration != null && duration > 0 && isVideoFile(fileName)

        if (showDuration) {
            Text(
                text = formatDuration(duration),
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 4.dp, bottom = 6.dp)
                    .background(
                        color = Color.Black.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(3.dp)
                    )
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }

        if (isTrashed) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(20.dp)
                    .background(
                        color = Color.Black.copy(alpha = 0.5f),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.trash_status),
                    tint = extendedColors.trashRed,
                    modifier = Modifier.size(12.dp)
                )
            }
        }

        // Optional star overlay on grid items
        if (star > 0) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 4.dp, bottom = 6.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(3.dp))
                    .padding(horizontal = 3.dp, vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(star) {
                    Icon(
                        imageVector = Icons.Filled.StarBorder,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(10.dp)
                    )
                }
            }
        }
      }
      Text(
          text = fileName,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp)
      )
    }
}

@Composable
private fun GridThumbnail(
    videoItemId: Long?,
    isVideo: Boolean,
    modifier: Modifier = Modifier,
    isImage: Boolean = false
) {
    val context = LocalContext.current

    val thumbUri = videoItemId?.let { MediaUris.thumbUri(it) }
    Box(modifier = modifier) {
        if (thumbUri != null) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(thumbUri)
                    .size(640)
                    .build(),
                contentDescription = stringResource(R.string.thumbnail),
                modifier = Modifier.fillMaxSize(),
                contentScale = if (isImage) ContentScale.Fit else ContentScale.Crop
            )
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = when {
                        isVideo -> Icons.Default.Movie
                        isImage -> Icons.Default.Image
                        else -> Icons.AutoMirrored.Filled.InsertDriveFile
                    },
                    contentDescription = when {
                        isVideo -> stringResource(R.string.media_type_video)
                        isImage -> stringResource(R.string.media_type_image)
                        else -> stringResource(R.string.media_type_file)
                    },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}
