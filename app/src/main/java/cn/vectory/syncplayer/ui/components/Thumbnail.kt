package cn.vectory.syncplayer.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cn.vectory.syncplayer.R
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import java.io.File

@Composable
internal fun Thumbnail(
    thumbnailPath: String?,
    isVideo: Boolean = true,
    isImage: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    if (thumbnailPath != null) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(File(thumbnailPath))
                .size(320)
                .build(),
            contentDescription = stringResource(R.string.thumbnail),
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    } else {
        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center
        ) {
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
