package cn.vectory.syncplayer.ui.components

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import cn.vectory.syncplayer.data.model.BrowseItem
import cn.vectory.syncplayer.data.model.FolderStats
import cn.vectory.syncplayer.R
import cn.vectory.syncplayer.ui.util.isVideoFile
import cn.vectory.syncplayer.ui.util.isImageFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Callback interface for file context menu actions.
 * Both BrowserViewModel and SearchViewModel implement this.
 */
interface FileContextMenuActions {
    fun updateStar(videoItemId: Long, star: Int)
    fun updateStarByPath(relativePath: String, star: Int)
    fun toggleTrashed(videoItemId: Long)
    fun deleteFile(relativePath: String)
    fun isFileInBaseDir(relativePath: String): Boolean
    suspend fun buildShareIntent(videoItemId: Long): Intent?
}

/**
 * Callback interface for folder context menu actions.
 */
interface FolderContextMenuActions {
    fun markFolderRead(folderPath: String, read: Boolean)
    fun deleteFolder(path: String)
    suspend fun getRecursiveFolderStats(folderPath: String): FolderStats
    fun isFileInBaseDir(relativePath: String): Boolean
}

/**
 * Shared context menu dialog for file items (videos and non-videos).
 * Extracted from BrowserScreen for reuse in SearchScreen.
 */
@Composable
fun FileContextMenuDialog(
    item: BrowseItem,
    actions: FileContextMenuActions,
    onDismiss: () -> Unit,
    scope: CoroutineScope,
    snackbarHostState: SnackbarHostState,
    context: Context = LocalContext.current,
) {
    var localStar by remember { mutableStateOf(item.star) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.width(320.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val pathOnly = item.relativePath.substringBeforeLast('/')
                Text(
                    text = if (pathOnly.isEmpty()) "/" else pathOnly,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )

                if (isVideoFile(item.name) || isImageFile(item.name)) {
                    val aspectRatio = if (item.width != null && item.height != null && item.width > 0 && item.height > 0) {
                        item.width.toFloat() / item.height.toFloat()
                    } else {
                        16f / 9f
                    }
                    Spacer(Modifier.height(8.dp))
                    Thumbnail(
                        thumbnailPath = item.thumbnailPath,
                        isVideo = isVideoFile(item.name),
                        isImage = isImageFile(item.name),
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(aspectRatio)
                            .heightIn(max = 200.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                }

                if (isVideoFile(item.name) || isImageFile(item.name)) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        StarRatingInput(
                            rating = localStar,
                            onRatingChange = { rating ->
                                localStar = rating
                                if (item.videoItemId != null) {
                                    actions.updateStar(item.videoItemId, rating)
                                } else {
                                    actions.updateStarByPath(item.relativePath, rating)
                                }
                            },
                            starSize = 20.dp
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = {
                        val itemId = item.videoItemId
                        if (itemId == null) {
                            onDismiss()
                            scope.launch {
                                snackbarHostState.showSnackbar(context.getString(R.string.context_no_record))
                            }
                            return@TextButton
                        }
                        onDismiss()
                        scope.launch {
                            val intent = actions.buildShareIntent(itemId)
                            if (intent != null) {
                                context.startActivity(intent)
                            } else {
                                snackbarHostState.showSnackbar(context.getString(R.string.context_share_failed))
                            }
                        }
                    }) {
                        Text(stringResource(R.string.context_share))
                    }
                    if (actions.isFileInBaseDir(item.relativePath)) {
                        TextButton(onClick = {
                            val vidId = item.videoItemId
                            if (vidId != null) {
                                actions.toggleTrashed(vidId)
                            }
                            onDismiss()
                        }) {
                            Text(if (item.isTrashed) stringResource(R.string.context_restore) else stringResource(R.string.context_trash))
                        }
                    }
                    if (!actions.isFileInBaseDir(item.relativePath)) {
                        TextButton(onClick = {
                            actions.deleteFile(item.relativePath)
                            onDismiss()
                        }) {
                            Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                        }
                    }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
            }
        }
    }
}

/**
 * Shared context menu dialog for folder items.
 * Extracted from BrowserScreen for reuse in SearchScreen.
 */
@Composable
fun FolderContextMenuDialog(
    item: BrowseItem,
    actions: FolderContextMenuActions,
    onDismiss: () -> Unit,
) {
    if (actions.isFileInBaseDir(item.relativePath)) {
        Dialog(onDismissRequest = onDismiss) {
            Card(
                modifier = Modifier.width(280.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = item.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    var stats by remember { mutableStateOf<FolderStats?>(null) }
                    LaunchedEffect(item.relativePath) {
                        stats = actions.getRecursiveFolderStats(item.relativePath)
                    }
                    if (stats != null) {
                        Text(stringResource(R.string.context_total_files, stats!!.totalFileCount), style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.context_video_files, stats!!.totalVideoCount), style = MaterialTheme.typography.bodyMedium)
                        if (stats!!.totalImageCount > 0) {
                            Text(stringResource(R.string.context_image_files, stats!!.totalImageCount), style = MaterialTheme.typography.bodyMedium)
                        }
                        Text(stringResource(R.string.context_total_size, formatFileSize(stats!!.totalSize)), style = MaterialTheme.typography.bodyMedium)
                    } else {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = {
                            actions.markFolderRead(item.relativePath, true)
                            onDismiss()
                        }) { Text(stringResource(R.string.context_mark_read)) }
                        TextButton(onClick = {
                            actions.markFolderRead(item.relativePath, false)
                            onDismiss()
                        }) { Text(stringResource(R.string.context_mark_unread)) }
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
                    }
                }
            }
        }
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(item.name) },
            confirmButton = {
                if (!item.isSyncBaseDir && !item.containsSyncBaseDir && !item.isInSyncBaseDir) {
                    TextButton(
                        onClick = {
                            actions.deleteFolder(item.relativePath)
                            onDismiss()
                        }
                    ) {
                        Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

private fun formatFileSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
    val value = size / Math.pow(1024.0, digitGroups.toDouble())
    return "${String.format("%.1f", value)} ${units[digitGroups]}"
}
