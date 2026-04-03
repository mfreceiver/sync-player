package com.mfreceiver.syncplayer.browser

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mfreceiver.syncplayer.data.model.BrowseItem

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel = hiltViewModel(),
    currentFolder: String = "",
    onNavigateToPlayer: (videoItemId: Long) -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToSyncPreview: () -> Unit,
    onNavigateUp: (() -> Unit)? = null
) {
    val browseItems by viewModel.browseItems.collectAsState()
    val hasConfig by viewModel.hasConfig.collectAsState()
    val sortOrder by viewModel.sortOrder.collectAsState()
    val folder by viewModel.currentFolder.collectAsState()
    
    var showContextMenu by remember { mutableStateOf<BrowseItem?>(null) }

    LaunchedEffect(currentFolder) {
        viewModel.setFolder(currentFolder)
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = if (folder.isEmpty()) "Sync Player" else folder.substringAfterLast('/'),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    if (folder.isNotEmpty() || onNavigateUp != null) {
                        IconButton(onClick = {
                            if (onNavigateUp != null) {
                                onNavigateUp()
                            } else {
                                viewModel.navigateUp()
                            }
                        }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回"
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.toggleSortOrder() }) {
                        Icon(
                            imageVector = Icons.Default.Sort,
                            contentDescription = if (sortOrder == BrowserViewModel.SortOrder.ASCENDING) "升序" else "降序"
                        )
                    }
                    IconButton(onClick = onNavigateToSyncPreview) {
                        Icon(
                            imageVector = Icons.Default.Sync,
                            contentDescription = "同步"
                        )
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "设置"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        if (!hasConfig) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "请先配置服务器",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(onClick = onNavigateToSettings) {
                        Text("去设置")
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                items(
                    items = browseItems,
                    key = { it.relativePath }
                ) { item ->
                    if (item.isDirectory) {
                        FolderItem(
                            item = item,
                            onClick = { viewModel.setFolder(item.relativePath) }
                        )
                    } else {
                        FileItem(
                            item = item,
                            onClick = { onNavigateToPlayer(item.videoItemId!!) },
                            onLongClick = { showContextMenu = item }
                        )
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 72.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }

    showContextMenu?.let { item ->
        AlertDialog(
            onDismissRequest = { showContextMenu = null },
            title = { Text(item.name) },
            text = { Text("选择操作") },
            confirmButton = {
                TextButton(onClick = { showContextMenu = null }) {
                    Text("取消")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        viewModel.shareVideo(item.videoItemId!!)
                        showContextMenu = null
                    }) {
                        Text("分享")
                    }
                    TextButton(onClick = {
                        viewModel.toggleFavorite(item.videoItemId!!)
                        showContextMenu = null
                    }) {
                        Text(if (item.isFavorite) "取消喜欢" else "喜欢")
                    }
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderItem(
    item: BrowseItem,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = {
            Text(
                text = item.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        leadingContent = {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = "文件夹",
                tint = Color(0xFFFFB300),
                modifier = Modifier.size(40.dp)
            )
        },
        trailingContent = {
            Text(
                text = "N 项",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileItem(
    item: BrowseItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        ListItem(
            headlineContent = {
                Text(
                    text = item.name,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            },
            supportingContent = {
                Text(
                    text = buildString {
                        append(formatFileSize(item.fileSize))
                        item.duration?.let { 
                            append(" · ")
                            append(formatDuration(it))
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            leadingContent = {
                Icon(
                    imageVector = Icons.Default.Movie,
                    contentDescription = "视频",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp)
                )
            },
            trailingContent = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (item.isCompleted) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "已完成",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    if (item.isFavorite) {
                        Icon(
                            imageVector = Icons.Default.Favorite,
                            contentDescription = "已收藏",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        )
        
        if (item.playbackPosition > 0 && item.playbackDuration > 0 && !item.isCompleted) {
            LinearProgressIndicator(
                progress = { item.playbackPosition.toFloat() / item.playbackDuration.toFloat() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 72.dp, end = 16.dp, bottom = 8.dp)
                    .height(2.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> "%.0f MB".format(bytes / (1024.0 * 1024))
        bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}

private fun formatDuration(ms: Long?): String {
    if (ms == null || ms <= 0) return ""
    
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
