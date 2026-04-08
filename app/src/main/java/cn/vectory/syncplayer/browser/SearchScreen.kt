package cn.vectory.syncplayer.browser

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import cn.vectory.syncplayer.data.model.BrowseItem

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel = hiltViewModel(),
    onNavigateToFolder: (String) -> Unit,
    onNavigateToPlayer: (Long) -> Unit,
    onBack: () -> Unit,
) {
    val query by viewModel.query.collectAsState()
    val searchScope by viewModel.searchScope.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()

    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    BackHandler {
        onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                title = { Text("搜索") }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { viewModel.setQuery(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 0.dp)
                    .focusRequester(focusRequester),
                placeholder = { Text("搜索文件名或文件夹名") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "搜索"
                    )
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setQuery("") }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "清除"
                            )
                        }
                    }
                },
                singleLine = true
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = searchScope == SearchViewModel.SearchScope.ALL,
                    onClick = { viewModel.setSearchScope(SearchViewModel.SearchScope.ALL) },
                    label = { Text("全部") }
                )
                FilterChip(
                    selected = searchScope == SearchViewModel.SearchScope.FILES_ONLY,
                    onClick = { viewModel.setSearchScope(SearchViewModel.SearchScope.FILES_ONLY) },
                    label = { Text("仅文件") }
                )
                FilterChip(
                    selected = searchScope == SearchViewModel.SearchScope.FOLDERS_ONLY,
                    onClick = { viewModel.setSearchScope(SearchViewModel.SearchScope.FOLDERS_ONLY) },
                    label = { Text("仅文件夹") }
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            if (query.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = "输入关键词开始搜索",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else if (searchResults.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SearchOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = "未找到匹配的结果",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    items(
                        items = searchResults,
                        key = { it.relativePath }
                    ) { item ->
                        if (item.isDirectory) {
                            SearchFolderItem(
                                item = item,
                                onClick = {
                                    BrowserNavigationStore.targetFolder = item.relativePath
                                    onNavigateToFolder(item.relativePath)
                                }
                            )
                        } else {
                            SearchFileItem(
                                item = item,
                                onClick = {
                                    if (isVideoFile(item.name)) {
                                        viewModel.setFolderVideoIdsForNavigation(viewModel.getSearchResultVideoIds())
                                        onNavigateToPlayer(item.videoItemId!!)
                                    }
                                }
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
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SearchFolderItem(
    item: BrowseItem,
    onClick: () -> Unit
) {
    val parentPath = extractParentPath(item.relativePath)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = {}
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(40.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = "文件夹",
                tint = Color(0xFFFFB300),
                modifier = Modifier.size(40.dp)
            )
            if (item.isSyncBaseDir) {
                Icon(
                    imageVector = Icons.Default.Cloud,
                    contentDescription = "同步基目录",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(14.dp)
                        .align(Alignment.BottomEnd)
                )
            }
        }

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
            if (parentPath.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = parentPath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SearchFileItem(
    item: BrowseItem,
    onClick: () -> Unit
) {
    val parentPath = extractParentPath(item.relativePath)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = {}
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Thumbnail(
            thumbnailPath = item.thumbnailPath,
            isVideo = isVideoFile(item.name),
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
                text = item.name.substringBeforeLast('.'),
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            val metadata = buildMetadataLine(
                duration = item.duration,
                width = item.width,
                height = item.height,
                bitrate = item.bitrate,
                extension = item.name.substringAfterLast('.', "").lowercase()
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

            if (parentPath.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = parentPath,
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
                            imageVector = Icons.Default.Delete,
                            contentDescription = "已预删除",
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
                            imageVector = Icons.Default.Delete,
                            contentDescription = "已预删除",
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
                    imageVector = Icons.Default.Delete,
                    contentDescription = "已预删除",
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
    isVideo: Boolean = true,
    modifier: Modifier = Modifier
) {
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
                imageVector = if (isVideo) Icons.Default.Movie else Icons.AutoMirrored.Filled.InsertDriveFile,
                contentDescription = if (isVideo) "视频" else "文件",
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
            topLeft = Offset(
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
                topLeft = Offset(
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
    bitrate: Long?,
    extension: String
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

    if (extension.isNotEmpty()) {
        parts.add(extension.uppercase())
    }

    return parts.joinToString(" · ")
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

private fun extractParentPath(relativePath: String): String {
    val lastSlash = relativePath.lastIndexOf('/')
    return if (lastSlash > 0) relativePath.substring(0, lastSlash) else ""
}

private val VIDEO_EXTENSIONS = setOf(
    "mp4", "mkv", "avi", "mov", "flv", "wmv", "webm", "ts", "m4v", "3gp", "mpeg", "mpg"
)

private fun isVideoFile(fileName: String): Boolean {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return ext in VIDEO_EXTENSIONS
}
