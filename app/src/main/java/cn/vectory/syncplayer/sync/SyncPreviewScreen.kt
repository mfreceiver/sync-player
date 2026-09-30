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
import androidx.compose.ui.graphics.Color
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

/**
 * D1（ora-2）：终态语义修正 ——
 *  - 移除误导性「同步已完成」卡：Finished 按 outcome 分卡，失败/取消绝不显示成功。
 *  - 失败与部分失败提供「重新扫描」恢复入口；dismiss 错误不复活旧计划。
 *  - 含删除项的计划（两模式）先弹确认对话框，列明下载数/删除数。
 *  - 删除警告卡在两种模式均显示（旧版仅 DOWNLOAD 模式）。
 *  - 仅状态驱动适配 + 确认对话框；复用既有组件与样式，不做视觉重设计。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncPreviewScreen(
    viewModel: SyncPreviewViewModel = hiltViewModel(),
    onBack: () -> Unit,
    onNavigateToRelation: (relationId: Long, initialTab: String) -> Unit = { _, _ -> }
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val toDownload by viewModel.toDownload.collectAsStateWithLifecycle()
    val toDelete by viewModel.toDelete.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val pendingExecuteConfirm by viewModel.pendingExecuteConfirm.collectAsStateWithLifecycle()
    val syncMode by viewModel.syncMode.collectAsStateWithLifecycle()
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

    val isRunning = state is SyncPreviewState.Running || state is SyncPreviewState.Cancelling

    // D3: Submitting 亦为忙态 —— Tab 切换与重新预览按钮禁用（VM 同步拒绝，双层守卫）。
    val busy = isRunning || state is SyncPreviewState.Submitting

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
                        enabled = !busy,
                        onClick = { viewModel.setSyncMode(SyncMode.DOWNLOAD) },
                        text = { Text(stringResource(R.string.sync_preview_download)) }
                    )
                    Tab(
                        selected = syncMode == SyncMode.DELETE,
                        enabled = !busy,
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
                    TextButton(
                        onClick = {
                            if (state is SyncPreviewState.Running) {
                                viewModel.cancelSync()
                            } else {
                                onBack()
                            }
                        },
                        enabled = state !is SyncPreviewState.Cancelling
                    ) {
                        Text(
                            if (isRunning) stringResource(R.string.common_stop)
                            else stringResource(R.string.common_cancel)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { viewModel.loadPreview() },
                        enabled = state !is SyncPreviewState.Scanning && !busy
                    ) {
                        if (state is SyncPreviewState.Scanning) {
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
                        onClick = { viewModel.requestExecute() },
                        enabled = state is SyncPreviewState.Ready,
                        colors = if (syncMode == SyncMode.DELETE) {
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        } else {
                            ButtonDefaults.buttonColors()
                        }
                    ) {
                        if (isRunning) {
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
        when (val s = state) {
            is SyncPreviewState.Scanning -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            is SyncPreviewState.ScanFailed -> {
                FullScreenError(
                    message = scanIssueMessage(s.issue.kind),
                    onRetry = { viewModel.loadPreview() },
                    modifier = Modifier.padding(paddingValues)
                )
            }
            SyncPreviewState.Idle -> {
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
            SyncPreviewState.NoChanges -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    OutcomeCard(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        icon = Icons.Default.CheckCircle,
                        iconTint = MaterialTheme.colorScheme.primary,
                        title = stringResource(R.string.sync_preview_up_to_date),
                        showRescan = true,
                        onRescan = { viewModel.loadPreview() }
                    )
                }
            }
            is SyncPreviewState.Ready -> {
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
                    if (syncMode == SyncMode.DOWNLOAD) {
                        SyncDownloadSummaryCard(
                            downloadCount = toDownload.size,
                            downloadSize = toDownload.sumOf { it.fileSize },
                            deleteCount = toDelete.size,
                            deleteSize = toDelete.sumOf { it.fileSize }
                        )
                    } else {
                        SyncDeleteSummaryCard(
                            count = toDelete.size,
                            totalSize = toDelete.sumOf { it.fileSize }
                        )
                    }
                    // D1: 删除警告两模式均适用（旧版仅 DOWNLOAD 模式）。
                    if (toDelete.isNotEmpty()) {
                        SyncDeleteWarningCard()
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        if (syncMode == SyncMode.DOWNLOAD) {
                            items(toDownload, key = { "dl_${it.relativePath}" }) { action ->
                                SyncActionItem(action = action, isDownload = true)
                            }
                            items(toDelete, key = { "rm_${it.relativePath}" }) { action ->
                                SyncActionItem(action = action, isDownload = false, showStrikethrough = true)
                            }
                        } else {
                            items(toDelete, key = { it.relativePath }) { action ->
                                SyncActionItem(action = action, isDownload = false)
                            }
                        }
                    }
                }
            }
            is SyncPreviewState.Running -> RunningContent(
                state = s,
                progress = progress,
                paddingValues = paddingValues
            )
            is SyncPreviewState.Cancelling -> RunningContent(
                state = s,
                progress = progress,
                paddingValues = paddingValues,
                stopping = true
            )
            is SyncPreviewState.Finished -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    when (s.result.outcome) {
                        SyncRunOutcome.SUCCESS -> OutcomeCard(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            icon = Icons.Default.CheckCircle,
                            iconTint = MaterialTheme.colorScheme.primary,
                            title = stringResource(R.string.sync_preview_completed),
                            showRescan = true,
                            onRescan = { viewModel.loadPreview() }
                        )
                        SyncRunOutcome.PARTIAL_FAILED -> OutcomeCard(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            icon = Icons.Default.Warning,
                            iconTint = MaterialTheme.colorScheme.error,
                            title = stringResource(
                                R.string.sync_preview_result_partial_failed,
                                s.result.failedFiles,
                                s.result.completedFiles
                            ),
                            showRescan = true,
                            onRescan = { viewModel.loadPreview() }
                        )
                        SyncRunOutcome.FAILED -> OutcomeCard(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            icon = Icons.Default.ErrorOutline,
                            iconTint = MaterialTheme.colorScheme.error,
                            title = stringResource(R.string.sync_preview_result_failed),
                            showRescan = true,
                            onRescan = { viewModel.loadPreview() }
                        )
                        SyncRunOutcome.CANCELLED -> OutcomeCard(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            icon = Icons.Default.Cancel,
                            iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                            title = stringResource(R.string.sync_preview_result_cancelled),
                            showRescan = true,
                            onRescan = { viewModel.loadPreview() }
                        )
                    }
                }
            }
            is SyncPreviewState.Submitting -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }

    // D1: 含删除项计划的执行确认门（两模式均适用）。D3: 门值改为 planId，非空即弹。
    if (pendingExecuteConfirm != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissExecuteConfirm() },
            title = { Text(stringResource(R.string.sync_preview_confirm_dialog_title)) },
            text = {
                Text(
                    if (toDownload.isNotEmpty()) {
                        stringResource(
                            R.string.sync_preview_confirm_dialog_delete_message,
                            toDownload.size,
                            toDelete.size
                        )
                    } else {
                        stringResource(
                            R.string.sync_preview_confirm_dialog_delete_only_message,
                            toDelete.size
                        )
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmExecute() }) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissExecuteConfirm() }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

@Composable
private fun scanIssueMessage(kind: SyncPreviewIssue): String = when (kind) {
    SyncPreviewIssue.AUTH -> stringResource(R.string.sync_preview_issue_auth)
    SyncPreviewIssue.PATH -> stringResource(R.string.sync_preview_issue_path)
    SyncPreviewIssue.NETWORK -> stringResource(R.string.sync_preview_issue_network)
    SyncPreviewIssue.SCAN_INCOMPLETE -> stringResource(R.string.sync_preview_issue_scan_incomplete)
    SyncPreviewIssue.STORAGE -> stringResource(R.string.sync_preview_issue_storage)
    SyncPreviewIssue.UNKNOWN -> stringResource(R.string.sync_preview_issue_unknown)
}

@Composable
private fun RunningContent(
    state: SyncPreviewState,
    progress: cn.vectory.syncplayer.data.model.SyncProgress,
    paddingValues: PaddingValues,
    stopping: Boolean = false
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues),
        contentAlignment = Alignment.Center
    ) {
        if (state is SyncPreviewState.Running && state.mode == SyncMode.DOWNLOAD ||
            state is SyncPreviewState.Cancelling && state.mode == SyncMode.DOWNLOAD
        ) {
            SyncProgressCard(
                completedFiles = progress.completedFiles,
                totalFiles = progress.totalFiles,
                transferredBytes = progress.transferredBytes,
                totalBytes = progress.totalBytes,
                activeDownloads = progress.activeDownloads,
                speedBytesPerSec = progress.speedBytesPerSec
            )
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularProgressIndicator()
                Text(
                    text = stringResource(
                        if (stopping) R.string.sync_preview_stopping else R.string.sync_preview_deleting
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun OutcomeCard(
    containerColor: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    title: String,
    showRescan: Boolean,
    onRescan: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(48.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            if (showRescan) {
                Spacer(Modifier.height(16.dp))
                Button(onClick = onRescan) {
                    Text(stringResource(R.string.sync_preview_rescan))
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
