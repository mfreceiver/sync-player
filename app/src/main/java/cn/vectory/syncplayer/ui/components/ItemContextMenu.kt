package cn.vectory.syncplayer.ui.components

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import cn.vectory.syncplayer.R
import cn.vectory.syncplayer.data.model.BrowseItem
import cn.vectory.syncplayer.data.model.FolderStats
import cn.vectory.syncplayer.ui.util.isImageFile
import cn.vectory.syncplayer.ui.util.isVideoFile
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Callback interface for file context menu actions.
 * Both BrowserViewModel and SearchViewModel implement this.
 */
interface FileContextMenuActions {
    fun updateStar(videoItemId: Long, star: Int)
    fun toggleTrashed(videoItemId: Long)
    fun deleteFile(videoItemId: Long)
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
 * Dispatch a file-menu trash/restore tap.
 *
 * Fallback contract (so the button is never a silent no-op):
 * - When the host supplies an undo-aware [callback] (Browser), it drives the operation and the
 *   undo Snackbar — the direct [FileContextMenuActions.toggleTrashed] is NOT also called.
 * - When no callback is supplied (SearchScreen and other legacy hosts), fall back to the direct
 *   [actions] call.
 *
 * Exactly one of the two paths runs. Extracted as a pure function so the contract is unit-testable.
 */
internal fun dispatchFileTrashAction(
    id: Long,
    callback: ((Long) -> Unit)?,
    actions: FileContextMenuActions
) {
    if (callback != null) callback(id) else actions.toggleTrashed(id)
}

/**
 * Dispatch a file-menu delete tap. Same fallback contract as [dispatchFileTrashAction], but for
 * hard delete via [FileContextMenuActions.deleteFile].
 */
internal fun dispatchFileDeleteAction(
    id: Long,
    callback: ((Long) -> Unit)?,
    actions: FileContextMenuActions
) {
    if (callback != null) callback(id) else actions.deleteFile(id)
}

/**
 * Shared context menu dialog for file items (videos and non-videos).
 * Extracted from BrowserScreen for reuse in SearchScreen.
 *
 * @param onTrashed Called with the video item id when the user taps the trash/restore action.
 *   The host is responsible for performing the toggle (with undo support) and showing a snackbar.
 * @param onDeleted Called with the video item id when the user taps the delete action.
 *   The host is responsible for performing the deferred delete (with undo support) and showing a snackbar.
 */
@Composable
fun FileContextMenuDialog(
    item: BrowseItem,
    actions: FileContextMenuActions,
    onDismiss: () -> Unit,
    scope: CoroutineScope,
    snackbarHostState: SnackbarHostState,
    context: Context = LocalContext.current,
    onTrashed: ((Long) -> Unit)? = null,
    onDeleted: ((Long) -> Unit)? = null,
) {
    var localStar by remember { mutableIntStateOf(item.star) }
    val noRecordStr = stringResource(R.string.context_no_record)
    val shareFailedStr = stringResource(R.string.context_share_failed)

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.widthIn(max = 320.dp),
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
                        videoItemId = item.videoItemId,
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
                                }
                            },
                            starSize = 20.dp
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = {
                        val itemId = item.videoItemId
                        if (itemId == null) {
                            onDismiss()
                            scope.launch {
                                snackbarHostState.showSnackbar(noRecordStr)
                            }
                            return@TextButton
                        }
                        onDismiss()
                        scope.launch {
                            val intent = actions.buildShareIntent(itemId)
                            if (intent != null) {
                                context.startActivity(intent)
                            } else {
                                snackbarHostState.showSnackbar(shareFailedStr)
                            }
                        }
                    }) {
                        Text(stringResource(R.string.context_share))
                    }
                    if (actions.isFileInBaseDir(item.relativePath)) {
                        TextButton(onClick = {
                            val vidId = item.videoItemId
                            if (vidId != null) {
                                // Hosts that pass an undo-aware callback (Browser) drive the
                                // operation through it; hosts that don't (SearchScreen) fall back
                                // to the direct actions call. Exactly one path runs.
                                dispatchFileTrashAction(vidId, onTrashed, actions)
                            }
                            onDismiss()
                        }) {
                            Text(if (item.isTrashed) stringResource(R.string.context_restore) else stringResource(R.string.context_trash))
                        }
                    }
                    if (!actions.isFileInBaseDir(item.relativePath)) {
                        TextButton(onClick = {
                            val delId = item.videoItemId
                            if (delId == null) {
                                onDismiss()
                                scope.launch {
                                    snackbarHostState.showSnackbar(noRecordStr)
                                }
                                return@TextButton
                            }
                            // Same fallback contract as the trash button above.
                            dispatchFileDeleteAction(delId, onDeleted, actions)
                            onDismiss()
                        }) {
                            Text(
                                stringResource(
                                    if (onDeleted != null) R.string.context_delete_with_undo
                                    else R.string.common_delete
                                ),
                                color = MaterialTheme.colorScheme.error
                            )
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
                modifier = Modifier.widthIn(max = 280.dp),
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
                    FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
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
    return "${String.format(Locale.getDefault(), "%.1f", value)} ${units[digitGroups]}"
}
