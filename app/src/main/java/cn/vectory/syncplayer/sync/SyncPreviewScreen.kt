package cn.vectory.syncplayer.sync

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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import cn.vectory.syncplayer.data.model.ActiveDownload
import cn.vectory.syncplayer.data.model.SyncAction

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncPreviewScreen(
    viewModel: SyncPreviewViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val toDownload by viewModel.toDownload.collectAsState()
    val toDelete by viewModel.toDelete.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    val isDeleting by viewModel.isDeleting.collectAsState()
    val syncMode by viewModel.syncMode.collectAsState()
    val previewLoaded by viewModel.previewLoaded.collectAsState()
    val syncComplete by viewModel.syncComplete.collectAsState()
    val progress by viewModel.progress.collectAsState()
    var isNavigatingBack by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("同步预览") },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                if (!isNavigatingBack) {
                                    isNavigatingBack = true
                                    if (isSyncing) {
                                        viewModel.cancelSync()
                                    }
                                    onBack()
                                }
                            },
                            enabled = !isNavigatingBack
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回"
                            )
                        }
                    }
                )
                TabRow(selectedTabIndex = if (syncMode == SyncMode.DOWNLOAD) 0 else 1) {
                    Tab(
                        selected = syncMode == SyncMode.DOWNLOAD,
                        onClick = { viewModel.setSyncMode(SyncMode.DOWNLOAD) },
                        text = { Text("下载") }
                    )
                    Tab(
                        selected = syncMode == SyncMode.DELETE,
                        onClick = { viewModel.setSyncMode(SyncMode.DELETE) },
                        text = { Text("删除") }
                    )
                }
            }
        },
        bottomBar = {
            BottomAppBar(
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = {
                        if (isSyncing || isDeleting) {
                            viewModel.cancelSync()
                        } else {
                            onBack()
                        }
                    }) {
                        Text(if (isSyncing || isDeleting) "停止" else "取消")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { viewModel.loadPreview() },
                        enabled = !isLoading && !isSyncing && !isDeleting
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        } else {
                            Text("预览")
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (syncMode == SyncMode.DOWNLOAD) {
                                viewModel.startSync()
                            } else {
                                viewModel.startDelete()
                            }
                        },
                        enabled = previewLoaded && !isSyncing && !isDeleting &&
                            (syncMode == SyncMode.DOWNLOAD && toDownload.isNotEmpty() || syncMode == SyncMode.DELETE && toDelete.isNotEmpty()),
                        colors = if (syncMode == SyncMode.DELETE) {
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        } else {
                            ButtonDefaults.buttonColors()
                        }
                    ) {
                        if (isSyncing || isDeleting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = if (syncMode == SyncMode.DOWNLOAD) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onError
                                }
                            )
                        } else {
                            if (syncMode == SyncMode.DOWNLOAD) {
                                Text("确认下载")
                            } else {
                                Text("确认删除")
                            }
                        }
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                }
            }
        },
    ) { paddingValues ->
        when {
            isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            error != null && !previewLoaded -> {
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
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = "错误",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = error ?: "未知错误",
                            color = MaterialTheme.colorScheme.error
                        )
                        Button(onClick = { viewModel.loadPreview() }) {
                            Text("重试")
                        }
                    }
                }
            }
            !previewLoaded -> {
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
                        Icon(
                            imageVector = Icons.Default.PlayCircleOutline,
                            contentDescription = "提示",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "按下方预览按钮查看同步内容",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                ) {
                    if (error != null) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Error,
                                    contentDescription = "错误",
                                    tint = MaterialTheme.colorScheme.error
                                )
                                Text(
                                    text = error!!,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = { viewModel.clearError() }) {
                                    Text("关闭")
                                }
                            }
                        }
                    }
                    if (syncComplete) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = "同步已完成",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(16.dp))
                                Button(onClick = { viewModel.loadPreview() }) {
                                    Text("重新扫描")
                                }
                            }
                        }
                    } else {
                        if (isSyncing) {
                            SyncProgressCard(
                                completedFiles = progress.completedFiles,
                                totalFiles = progress.totalFiles,
                                transferredBytes = progress.transferredBytes,
                                totalBytes = progress.totalBytes,
                                activeDownloads = progress.activeDownloads,
                                currentFileName = progress.currentFileName,
                                currentFileProgress = progress.currentFileProgress
                            )
                        }
                        val currentItemsEmpty = if (syncMode == SyncMode.DOWNLOAD) {
                            toDownload.isEmpty() && toDelete.isEmpty()
                        } else {
                            toDelete.isEmpty()
                        }
                        if (currentItemsEmpty && !isSyncing && !isDeleting) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                )
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(36.dp)
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = "已同步，无需操作",
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }
                        if (syncMode == SyncMode.DOWNLOAD) {
                            if (toDownload.isNotEmpty() || toDelete.isNotEmpty()) {
                                SyncDownloadSummaryCard(
                                    downloadCount = toDownload.size,
                                    downloadSize = viewModel.totalDownloadSize,
                                    deleteCount = toDelete.size,
                                    deleteSize = viewModel.totalDeleteSize
                                )
                            }
                        } else {
                            if (toDelete.isNotEmpty()) {
                                SyncDeleteSummaryCard(
                                    count = toDelete.size,
                                    totalSize = viewModel.totalDeleteSize
                                )
                            }
                        }
                        if (syncMode == SyncMode.DOWNLOAD && toDelete.isNotEmpty()) {
                            SyncDeleteWarningCard()
                        }
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            if (syncMode == SyncMode.DOWNLOAD) {
                                items(toDownload, key = { it.relativePath }) { action ->
                                    SyncActionItem(action = action, isDownload = true)
                                }
                            } else {
                                items(toDelete, key = { it.relativePath }) { action ->
                                    SyncActionItem(action = action, isDownload = false)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

enum class SyncMode { DOWNLOAD, DELETE }

@Composable
private fun SyncDownloadSummaryCard(
    downloadCount: Int,
    downloadSize: Long,
    deleteCount: Int,
    deleteSize: Long
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "将下载 $downloadCount 个文件 (${formatFileSize(downloadSize)})，删除 $deleteCount 个本地文件 (${formatFileSize(deleteSize)})",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun SyncDeleteSummaryCard(
    count: Int,
    totalSize: Long
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "$count 个预删除文件将从本地和远端同时删除 (${formatFileSize(totalSize)})",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun SyncDeleteWarningCard() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "警告",
                tint = MaterialTheme.colorScheme.error
            )
            Text(
                text = "此操作不可恢复！删除后文件将从本地和远端永久移除",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
private fun SyncActionItem(
    action: SyncAction,
    isDownload: Boolean
) {
    ListItem(
        headlineContent = {
            Text(
                text = action.relativePath.substringAfterLast('/'),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Text(
                text = action.relativePath,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall
            )
        },
        leadingContent = {
            Icon(
                imageVector = if (isDownload) Icons.Default.Download else Icons.Default.Delete,
                contentDescription = if (isDownload) "下载" else "删除",
                tint = if (isDownload) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
        },
        trailingContent = {
            Text(
                text = formatFileSize(action.fileSize),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    )
    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> "%.0f MB".format(bytes / (1024.0 * 1024))
        bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}

@Composable
private fun SyncProgressCard(
    completedFiles: Int,
    totalFiles: Int,
    transferredBytes: Long,
    totalBytes: Long,
    activeDownloads: List<ActiveDownload>,
    currentFileName: String,
    currentFileProgress: Float
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val overallProgress = if (totalFiles > 0) completedFiles.toFloat() / totalFiles else 0f
            LinearProgressIndicator(
                progress = { overallProgress },
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val remaining = totalFiles - completedFiles
                Text(
                    text = "已完成 $completedFiles / $totalFiles 个文件",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "剩余 $remaining 个",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${formatFileSize(transferredBytes)} / ${formatFileSize(totalBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (totalBytes > 0) {
                    Text(
                        text = "%.0f%%".format(transferredBytes.toDouble() / totalBytes * 100),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (activeDownloads.size > 1) {
                HorizontalDivider()
                Text(
                    text = "正在下载 ${activeDownloads.size} 个文件",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                activeDownloads.forEach { download ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            progress = { download.progress },
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = download.fileName,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (download.progress > 0) {
                            Text(
                                text = "%.0f%%".format(download.progress * 100),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            } else if (currentFileName.isNotEmpty()) {
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(
                        progress = { currentFileProgress },
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                    Text(
                        text = currentFileName.substringAfterLast('/'),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (currentFileProgress > 0) {
                        Text(
                            text = "%.0f%%".format(currentFileProgress * 100),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}
