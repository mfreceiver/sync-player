package cn.vectory.syncplayer.browser

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import cn.vectory.syncplayer.data.model.BrowseItem

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel = hiltViewModel(),
    currentFolder: String = "",
    onNavigateToPlayer: (videoItemId: Long) -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToSyncPreview: () -> Unit,
    onNavigateUp: (() -> Unit)? = null,
    onNavigateToFolder: ((folderPath: String) -> Unit)? = null
) {
    val browseItems by viewModel.browseItems.collectAsState()
    val hasConfig by viewModel.hasConfig.collectAsState()
    val sortMode by viewModel.sortMode.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val folder by viewModel.currentFolder.collectAsState()
    
    var showContextMenu by remember { mutableStateOf<BrowseItem?>(null) }
    var showSortMenu by remember { mutableStateOf(false) }

    LaunchedEffect(currentFolder) {
        viewModel.setFolder(currentFolder)
    }

    BackHandler(enabled = folder.isNotEmpty()) {
        viewModel.navigateUp()
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
                    Box {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.Sort,
                                contentDescription = "排序筛选"
                            )
                        }
                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false }
                        ) {
                            Text(
                                "排序",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                            )
                            DropdownMenuItem(
                                text = { Text("按文件名 ↑") },
                                onClick = { viewModel.setSortMode(BrowserViewModel.SortMode.FILENAME_ASC); showSortMenu = false },
                                trailingIcon = { if (sortMode == BrowserViewModel.SortMode.FILENAME_ASC) Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp)) }
                            )
                            DropdownMenuItem(
                                text = { Text("按文件名 ↓") },
                                onClick = { viewModel.setSortMode(BrowserViewModel.SortMode.FILENAME_DESC); showSortMenu = false },
                                trailingIcon = { if (sortMode == BrowserViewModel.SortMode.FILENAME_DESC) Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp)) }
                            )
                            DropdownMenuItem(
                                text = { Text("按时长 ↑") },
                                onClick = { viewModel.setSortMode(BrowserViewModel.SortMode.DURATION_ASC); showSortMenu = false },
                                trailingIcon = { if (sortMode == BrowserViewModel.SortMode.DURATION_ASC) Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp)) }
                            )
                            DropdownMenuItem(
                                text = { Text("按时长 ↓") },
                                onClick = { viewModel.setSortMode(BrowserViewModel.SortMode.DURATION_DESC); showSortMenu = false },
                                trailingIcon = { if (sortMode == BrowserViewModel.SortMode.DURATION_DESC) Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp)) }
                            )
                            HorizontalDivider()
                            Text(
                                "筛选",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                            )
                            DropdownMenuItem(
                                text = { Text("全部") },
                                onClick = { viewModel.setFilter(BrowserViewModel.BrowseFilter.ALL); showSortMenu = false },
                                trailingIcon = { if (filter == BrowserViewModel.BrowseFilter.ALL) Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp)) }
                            )
                            DropdownMenuItem(
                                text = { Text("仅喜欢") },
                                onClick = { viewModel.setFilter(BrowserViewModel.BrowseFilter.FAVORITES_ONLY); showSortMenu = false },
                                trailingIcon = { if (filter == BrowserViewModel.BrowseFilter.FAVORITES_ONLY) Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp)) }
                            )
                        }
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
                            onClick = {
                                if (onNavigateToFolder != null) {
                                    onNavigateToFolder(item.relativePath)
                                } else {
                                    viewModel.setFolder(item.relativePath)
                                }
                            }
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Thumbnail(
            thumbnailPath = item.thumbnailPath,
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(4.dp))
        )
        
        Spacer(Modifier.width(12.dp))
        
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            
            val metadata = buildMetadataLine(
                duration = item.duration,
                width = item.width,
                height = item.height,
                bitrate = item.bitrate
            )
            if (metadata.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = metadata,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        
        when {
            item.isCompleted -> {
                Spacer(Modifier.width(8.dp))
                Box(contentAlignment = Alignment.Center) {
                    ProgressRing(
                        progress = 1f,
                        modifier = Modifier.size(32.dp)
                    )
                    if (item.isFavorite) {
                        Icon(
                            imageVector = Icons.Default.Favorite,
                            contentDescription = "已收藏",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .size(14.dp)
                                .align(Alignment.Center)
                        )
                    }
                }
            }
            item.playbackPosition > 0 && item.playbackDuration > 0 -> {
                Spacer(Modifier.width(8.dp))
                val progress = item.playbackPosition.toFloat() / item.playbackDuration.toFloat()
                Box(contentAlignment = Alignment.Center) {
                    ProgressRing(
                        progress = progress,
                        modifier = Modifier.size(32.dp)
                    )
                    if (item.isFavorite) {
                        Icon(
                            imageVector = Icons.Default.Favorite,
                            contentDescription = "已收藏",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .size(14.dp)
                                .align(Alignment.Center)
                        )
                    }
                }
            }
            item.isFavorite -> {
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Default.Favorite,
                    contentDescription = "已收藏",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
private fun Thumbnail(
    thumbnailPath: String?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    
    LaunchedEffect(thumbnailPath) {
        thumbnailPath?.let { path ->
            try {
                bitmap = BitmapFactory.decodeFile(path)
            } catch (e: Exception) {
                bitmap = null
            }
        }
    }
    
    if (bitmap != null) {
        androidx.compose.foundation.Image(
            painter = BitmapPainter(bitmap!!.asImageBitmap()),
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
                imageVector = Icons.Default.Movie,
                contentDescription = "视频",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

@Composable
private fun ProgressRing(
    progress: Float,
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    
    Canvas(modifier = modifier) {
        val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        val radius = (size.minDimension - stroke.width) / 2
        
        drawArc(
            color = trackColor,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            style = stroke,
            topLeft = androidx.compose.ui.geometry.Offset(
                center.x - radius,
                center.y - radius
            ),
            size = Size(radius * 2, radius * 2)
        )
        
        if (progress > 0f) {
            drawArc(
                color = primaryColor,
                startAngle = -90f,
                sweepAngle = progress.coerceIn(0f, 1f) * 360f,
                useCenter = false,
                style = stroke,
                topLeft = androidx.compose.ui.geometry.Offset(
                    center.x - radius,
                    center.y - radius
                ),
                size = Size(radius * 2, radius * 2)
            )
        }
    }
}

private fun buildMetadataLine(
    duration: Long?,
    width: Int?,
    height: Int?,
    bitrate: Long?
): String {
    val parts = mutableListOf<String>()
    
    duration?.let { ms ->
        if (ms > 0) {
            parts.add(formatDuration(ms))
        }
    }
    
    if (width != null && height != null && width > 0 && height > 0) {
        parts.add("${width}×${height}")
    }
    
    bitrate?.let { bps ->
        if (bps > 0) {
            val mbps = bps / 1_000_000.0
            parts.add(if (mbps >= 1.0) "%.1fMbps".format(mbps) else "%.0fKbps".format(bps / 1000.0))
        }
    }
    
    return parts.joinToString(" · ")
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
