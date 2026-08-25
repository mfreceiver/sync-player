package cn.vectory.syncplayer.sync

import androidx.activity.compose.BackHandler
import cn.vectory.syncplayer.ui.util.formatFileSize
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.vectory.syncplayer.R
import cn.vectory.syncplayer.data.model.ActiveDownload
import cn.vectory.syncplayer.data.model.SyncAction
import cn.vectory.syncplayer.ui.components.ErrorBanner
import cn.vectory.syncplayer.ui.components.FullScreenError

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncPreviewScreen(
    viewModel: SyncPreviewViewModel = hiltViewModel(),
    onBack: () -> Unit,
    onNavigateToRelation: (relationId: Long, initialTab: String) -> Unit = { _, _ -> }
) {
    val toDownload by viewModel.toDownload.collectAsStateWithLifecycle()
    val toDelete by viewModel.toDelete.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()
    val isDeleting by viewModel.isDeleting.collectAsStateWithLifecycle()
    val syncMode by viewModel.syncMode.collectAsStateWithLifecycle()
    val previewLoaded by viewModel.previewLoaded.collectAsStateWithLifecycle()
    val syncComplete by viewModel.syncComplete.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val allRelations by viewModel.allRelations.collectAsStateWithLifecycle()
    val currentRelationName by viewModel.currentRelationName.collectAsStateWithLifecycle()
    var isNavigatingBack by remember { mutableStateOf(false) }
    var showRelationDropdown by remember { mutableStateOf(false) }
    val navigateBack: () -> Unit = {
        if (!isNavigatingBack) {
            isNavigatingBack = true
            onBack()
        }
    }

    // Keep system and app-bar back behavior identical. A running sync is stopped only by
    // the explicit Stop control so leaving this preview is never a destructive side effect.
    BackHandler(enabled = !isNavigatingBack, onBack = navigateBack)

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Box {
                            Row(
                                modifier = Modifier.clickable { showRelationDropdown = !showRelationDropdown },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(currentRelationName.ifEmpty { stringResource(R.string.sync_preview_title) })
                                Icon(
                                    Icons.Default.ArrowDropDown,
                                    contentDescription = stringResource(R.string.sync_preview_switch_relation),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            DropdownMenu(
                                expanded = showRelationDropdown,
                                onDismissRequest = { showRelationDropdown = false }
                            ) {
                                allRelations.forEach { relation ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                relation.name,
                                                fontWeight = if (relation.id == viewModel.relationId) FontWeight.Bold else FontWeight.Normal
                                            )
                                        },
                                        onClick = {
                                            showRelationDropdown = false
                                            if (relation.id != viewModel.relationId) {
                                                onNavigateToRelation(relation.id, syncMode.name.lowercase())
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = navigateBack,
                            enabled = !isNavigatingBack
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.common_back)
                            )
                        }
                    }
                )
                PrimaryTabRow(selectedTabIndex = if (syncMode == SyncMode.DOWNLOAD) 0 else 1) {
                    Tab(
                        selected = syncMode == SyncMode.DOWNLOAD,
                        onClick = { viewModel.setSyncMode(SyncMode.DOWNLOAD) },
                        text = { Text(stringResource(R.string.sync_preview_download)) }
                    )
                    Tab(
                        selected = syncMode == SyncMode.DELETE,
                        onClick = { viewModel.setSyncMode(SyncMode.DELETE) },
                        text = { Text(stringResource(R.string.sync_preview_delete_label)) }
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
                        Text(if (isSyncing || isDeleting) stringResource(R.string.common_stop) else stringResource(R.string.common_cancel))
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
                            Text(stringResource(R.string.sync_preview_button))
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
                            (syncMode == SyncMode.DOWNLOAD && (toDownload.isNotEmpty() || toDelete.isNotEmpty()) || syncMode == SyncMode.DELETE && toDelete.isNotEmpty()),
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
                                Text(stringResource(R.string.sync_preview_confirm_download))
                            } else {
                                Text(stringResource(R.string.sync_preview_confirm_delete))
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
                FullScreenError(
                    message = error ?: stringResource(R.string.unknown_error),
                    onRetry = { viewModel.loadPreview() },
                    modifier = Modifier.padding(paddingValues)
                )
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
                            contentDescription = stringResource(R.string.sync_preview_hint),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(R.string.sync_preview_hint),
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
                        ErrorBanner(
                            message = error!!,
                            onDismiss = { viewModel.clearError() }
                        )
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
                                    text = stringResource(R.string.sync_preview_completed),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
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
                                speedBytesPerSec = progress.speedBytesPerSec
                            )
                        }
                        val remainingToDownload by remember {
                            derivedStateOf {
                                if (isSyncing) {
                                    toDownload.filter { it.relativePath !in progress.completedPaths }
                                } else {
                                    toDownload
                                }
                            }
                        }
                        val remainingToDelete by remember {
                            derivedStateOf {
                                if (isSyncing || isDeleting) {
                                    toDelete.filter { it.relativePath !in progress.completedPaths }
                                } else {
                                    toDelete
                                }
                            }
                        }
                        val currentItemsEmpty by remember {
                            derivedStateOf {
                                if (syncMode == SyncMode.DOWNLOAD) {
                                    remainingToDownload.isEmpty() && remainingToDelete.isEmpty()
                                } else {
                                    remainingToDelete.isEmpty()
                                }
                            }
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
                                        modifier = Modifier.size(48.dp)
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = stringResource(R.string.sync_preview_up_to_date),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(Modifier.height(16.dp))
                                    Button(onClick = { viewModel.loadPreview() }) {
                                        Text(stringResource(R.string.sync_preview_rescan))
                                    }
                                }
                            }
                        }
                        if (syncMode == SyncMode.DOWNLOAD) {
                            if (!isSyncing && (remainingToDownload.isNotEmpty() || remainingToDelete.isNotEmpty())) {
                                SyncDownloadSummaryCard(
                                    downloadCount = remainingToDownload.size,
                                    downloadSize = remainingToDownload.sumOf { it.fileSize },
                                    deleteCount = remainingToDelete.size,
                                    deleteSize = remainingToDelete.sumOf { it.fileSize }
                                )
                            }
                        } else {
                            if (!isDeleting && remainingToDelete.isNotEmpty()) {
                                SyncDeleteSummaryCard(
                                    count = remainingToDelete.size,
                                    totalSize = remainingToDelete.sumOf { it.fileSize }
                                )
                            }
                        }
                        if (syncMode == SyncMode.DOWNLOAD && remainingToDelete.isNotEmpty() && !isSyncing) {
                            SyncDeleteWarningCard()
                        }
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            if (syncMode == SyncMode.DOWNLOAD) {
                                items(remainingToDownload, key = { "dl_${it.relativePath}" }) { action ->
                                    SyncActionItem(action = action, isDownload = true)
                                }
                                items(remainingToDelete, key = { "rm_${it.relativePath}" }) { action ->
                                    SyncActionItem(action = action, isDownload = false, showStrikethrough = true)
                                }
                            } else {
                                items(remainingToDelete, key = { it.relativePath }) { action ->
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
                text = stringResource(R.string.sync_preview_download_summary, downloadCount, formatFileSize(downloadSize), deleteCount, formatFileSize(deleteSize)),
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
                text = stringResource(R.string.sync_preview_delete_summary, count, formatFileSize(totalSize)),
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
                contentDescription = stringResource(R.string.sync_preview_warning),
                tint = MaterialTheme.colorScheme.error
            )
            Text(
                text = stringResource(R.string.sync_preview_delete_warning),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
private fun SyncActionItem(
    action: SyncAction,
    isDownload: Boolean,
    showStrikethrough: Boolean = false
) {
    ListItem(
        headlineContent = {
            Text(
                text = action.relativePath.substringAfterLast('/'),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textDecoration = if (showStrikethrough) TextDecoration.LineThrough else null
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
                contentDescription = if (isDownload) stringResource(R.string.sync_preview_content_desc_download) else stringResource(R.string.sync_preview_content_desc_delete),
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
}



@Composable
private fun SyncProgressCard(
    completedFiles: Int,
    totalFiles: Int,
    transferredBytes: Long,
    totalBytes: Long,
    activeDownloads: List<ActiveDownload>,
    speedBytesPerSec: Long = 0
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
                modifier = Modifier.fillMaxWidth(),
                gapSize = 0.dp,
                drawStopIndicator = {}
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val remaining = totalFiles - completedFiles
                Text(
                    text = stringResource(R.string.sync_preview_completed_files, completedFiles, totalFiles),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.sync_preview_remaining, remaining),
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

            if (activeDownloads.isNotEmpty()) {
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.sync_preview_downloading, activeDownloads.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (speedBytesPerSec > 0) {
                        Text(
                            text = "${formatSpeed(speedBytesPerSec)}/s",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
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
            }
        }
    }
}

private fun formatSpeed(bytesPerSec: Long): String = when {
    bytesPerSec >= 1024L * 1024 * 1024 -> "%.1fGB".format(bytesPerSec / (1024.0 * 1024 * 1024))
    bytesPerSec >= 1024L * 1024 -> "%.1fMB".format(bytesPerSec / (1024.0 * 1024))
    bytesPerSec >= 1024L -> "%.0fKB".format(bytesPerSec / 1024.0)
    else -> "${bytesPerSec}B"
}
