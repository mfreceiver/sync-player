package cn.vectory.syncplayer.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import java.io.File

@Composable
internal fun Thumbnail(
    thumbnailPath: String?,
    isVideo: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    if (thumbnailPath != null) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(File(thumbnailPath))
                .size(320)
                .build(),
            contentDescription = "缩略图",
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    } else {
        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isVideo) Icons.Default.Movie else Icons.Default.InsertDriveFile,
                contentDescription = if (isVideo) "视频" else "文件",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(32.dp)
            )
        }
    }
}
