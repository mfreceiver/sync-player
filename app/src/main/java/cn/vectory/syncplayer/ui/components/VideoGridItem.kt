package cn.vectory.syncplayer.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.vectory.syncplayer.ui.theme.LocalExtendedColors
import cn.vectory.syncplayer.ui.util.formatDuration
import cn.vectory.syncplayer.ui.util.isVideoFile

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun VideoGridItem(
    thumbnailPath: String?,
    fileName: String,
    duration: Long?,
    width: Int?,
    height: Int?,
    playbackPosition: Long,
    playbackDuration: Long,
    isCompleted: Boolean,
    isTrashed: Boolean,
    isVideo: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val extendedColors = LocalExtendedColors.current

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        GridThumbnail(
            thumbnailPath = thumbnailPath,
            isVideo = isVideo,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
        )

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                .align(Alignment.TopStart)
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
                val progressColor = if (isCompleted) {
                    extendedColors.successGreen
                } else {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                }

                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                ) {
                    val barHeight = 3.dp.toPx()
                    drawRect(
                        color = Color.White.copy(alpha = 0.15f),
                        topLeft = Offset(0f, size.height - barHeight),
                        size = Size(size.width, barHeight)
                    )
                    drawRect(
                        color = progressColor,
                        topLeft = Offset(0f, size.height - barHeight),
                        size = Size(size.width * progress, barHeight)
                    )
                }
            }
        }

        val showDuration = duration != null && duration > 0 && isVideoFile(fileName)
        val showFileName = !isVideo || !showDuration

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

        if (showFileName) {
            Text(
                text = fileName.substringBeforeLast('.'),
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
                    contentDescription = "回收站",
                    tint = extendedColors.trashRed,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}

@Composable
private fun GridThumbnail(
    thumbnailPath: String?,
    isVideo: Boolean,
    modifier: Modifier = Modifier
) {
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var isExtremeRatio by remember { mutableStateOf(false) }

    LaunchedEffect(thumbnailPath) {
        thumbnailPath?.let { path ->
            try {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, options)

                if (options.outWidth > 0 && options.outHeight > 0) {
                    val ratio = options.outWidth.toFloat() / options.outHeight.toFloat()
                    isExtremeRatio = ratio > 2f || ratio < 0.5f
                }

                val targetWidth = 320
                val sampleSize = if (options.outWidth > targetWidth) {
                    Integer.highestOneBit(options.outWidth / targetWidth).coerceAtLeast(1)
                } else 1

                bitmap = BitmapFactory.decodeFile(
                    path,
                    BitmapFactory.Options().apply { inSampleSize = sampleSize }
                )
            } catch (_: Exception) {
                bitmap = null
            }
        }
    }

    Box(modifier = modifier) {
        if (bitmap != null) {
            Image(
                painter = BitmapPainter(bitmap!!.asImageBitmap()),
                contentDescription = "缩略图",
                modifier = Modifier.fillMaxSize(),
                contentScale = if (isExtremeRatio) ContentScale.Crop else ContentScale.Fit
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isVideo) Icons.Default.Movie else Icons.AutoMirrored.Filled.InsertDriveFile,
                    contentDescription = if (isVideo) "视频" else "文件",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}
