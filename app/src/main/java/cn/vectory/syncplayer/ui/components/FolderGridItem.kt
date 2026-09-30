package cn.vectory.syncplayer.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.vectory.syncplayer.provider.MediaUris
import coil.compose.AsyncImage
import coil.request.ImageRequest

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FolderGridItem(
    folderName: String,
    isSyncBaseDir: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    videoItemIds: List<Long> = emptyList(),
    gridDensity: Int = 2
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .semantics { contentDescription = folderName }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (videoItemIds.isNotEmpty()) {
                ThumbnailGrid(
                    videoItemIds = videoItemIds,
                    gridSize = gridDensity
                )
            } else {
                FolderIcon(
                    isSyncBaseDir = isSyncBaseDir,
                    modifier = Modifier.size(40.dp)
                )
            }
        }

        Text(
            text = folderName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun ThumbnailGrid(
    videoItemIds: List<Long>,
    gridSize: Int
) {
    val context = LocalContext.current
    val items = videoItemIds.take(gridSize * gridSize)
    if (items.isEmpty()) return

    val gap = 1.dp
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        items.chunked(gridSize).forEach { row ->
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                for (i in 0 until gridSize) {
                    val id = row.getOrNull(i)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .padding(gap)
                    ) {
                        if (id != null) {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(MediaUris.thumbUri(id))
                                    .size(160)
                                    .build(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                }
            }
        }
    }
}
