package com.mfreceiver.syncplayer.sync

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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

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
    val progress by viewModel.progress.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("下载 (${toDownload.size})", "删除 (${toDelete.size})")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("同步预览") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                }
            )
        },
        bottomBar = {
            BottomAppBar(
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                Spacer(modifier = Modifier.weight(1f))
                TextButton(
                    onClick = onBack,
                    enabled = !isSyncing
                ) {
                    Text("取消")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        viewModel.startSync()
                    },
                    enabled = !isSyncing && (toDownload.isNotEmpty() || toDelete.isNotEmpty())
                ) {
                    if (isSyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text("确认同步")
                }
                Spacer(modifier = Modifier.width(16.dp))
            }
        }
    ) { paddingValues ->
        when {
            isLoading -> {
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
                        CircularProgressIndicator()
                        Text(
                            text = "正在扫描文件...",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            error != null && toDownload.isEmpty() && toDelete.isEmpty() -> {
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
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error
                        )
                        Button(onClick = { viewModel.loadPreview() }) {
                            Text("重试")
                        }
                    }
                }
            }

            toDownload.isEmpty() && toDelete.isEmpty() -> {
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
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "完成",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = "本地文件已是最新",
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
                    if (isSyncing) {
                        LinearProgressIndicator(
                            progress = { 
                                if (progress.totalFiles > 0) {
                                    progress.completedFiles.toFloat() / progress.totalFiles.toFloat()
                                } else 0f
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (progress.currentFileName.isNotEmpty()) {
                            Text(
                                text = progress.currentFileName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }

                    SummaryCard(
                        downloadCount = toDownload.size,
                        downloadSize = viewModel.totalDownloadSize,
                        deleteCount = toDelete.size,
                        deleteSize = viewModel.totalDeleteSize
                    )

                    if (toDelete.isNotEmpty()) {
                        WarningCard(deleteCount = toDelete.size)
                    }

                    TabRow(selectedTabIndex = selectedTab) {
                        tabs.forEachIndexed { index, title ->
                            Tab(
                                selected = selectedTab == index,
                                onClick = { selectedTab = index },
                                text = { Text(title) }
                            )
                        }
                    }

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        when (selectedTab) {
                            0 -> items(toDownload, key = { it.relativePath }) { action ->
                                SyncActionItem(
                                    action = action,
                                    isDownload = true
                                )
                            }

                            1 -> items(toDelete, key = { it.relativePath }) { action ->
                                SyncActionItem(
                                    action = action,
                                    isDownload = false
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(
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
                text = "同步摘要",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "将下载 $downloadCount 个文件 (${formatFileSize(downloadSize)})，删除 $deleteCount 个本地文件 (${formatFileSize(deleteSize)})",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun WarningCard(deleteCount: Int) {
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
                text = "注意：$deleteCount 个本地文件将被删除！",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
private fun SyncActionItem(
    action: com.mfreceiver.syncplayer.data.model.SyncAction,
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
        bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}
