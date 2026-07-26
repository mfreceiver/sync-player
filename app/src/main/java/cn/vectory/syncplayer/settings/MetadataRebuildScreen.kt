package cn.vectory.syncplayer.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.vectory.syncplayer.R
import cn.vectory.syncplayer.ui.components.FullScreenError

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetadataRebuildScreen(
    onBack: () -> Unit,
    viewModel: MetadataRebuildViewModel = hiltViewModel()
) {
    val discoveryResult by viewModel.discoveryResult.collectAsStateWithLifecycle()
    val isDiscovering by viewModel.isDiscovering.collectAsStateWithLifecycle()
    val isRebuilding by viewModel.isRebuilding.collectAsStateWithLifecycle()
    val isComplete by viewModel.isComplete.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val completedCount by viewModel.completedCount.collectAsStateWithLifecycle()
    val totalCount by viewModel.totalCount.collectAsStateWithLifecycle()
    val currentFile by viewModel.currentFile.collectAsStateWithLifecycle()
    val cleanupSummary by viewModel.cleanupSummary.collectAsStateWithLifecycle()
    var isNavigatingBack by remember { mutableStateOf(false) }
    var showOtherVolumeConfirm by remember { mutableStateOf(false) }

    if (showOtherVolumeConfirm) {
        val otherCount = discoveryResult?.otherVolumeRecords?.size ?: 0
        AlertDialog(
            onDismissRequest = { showOtherVolumeConfirm = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.metadata_confirm_clean_title)) },
            text = {
                Text(stringResource(R.string.metadata_confirm_clean_body, otherCount))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showOtherVolumeConfirm = false
                        viewModel.cleanOtherVolumeIndexes()
                    }
                ) {
                    Text(stringResource(R.string.metadata_confirm_clean_button), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showOtherVolumeConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.metadata_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (!isNavigatingBack) {
                                isNavigatingBack = true
                                if (isRebuilding) viewModel.cancelRebuild()
                                onBack()
                            }
                        },
                        enabled = !isNavigatingBack
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                }
            )
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
                        if (isRebuilding) {
                            viewModel.cancelRebuild()
                        } else {
                            onBack()
                        }
                    }) {
                        Text(if (isRebuilding) stringResource(R.string.common_stop) else stringResource(R.string.common_cancel))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { viewModel.discover() },
                        enabled = !isDiscovering && !isRebuilding
                    ) {
                        if (isDiscovering) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        } else {
                            Text(stringResource(R.string.metadata_discover))
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    val canRebuild = discoveryResult != null
                        && !isRebuilding && !isDiscovering
                        && ((discoveryResult?.orphanedRecords?.size ?: 0)
                            + (discoveryResult?.incompleteRecords?.size ?: 0) > 0)
                    Button(
                        onClick = { viewModel.startRebuild() },
                        enabled = canRebuild
                    ) {
                        if (isRebuilding) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        } else {
                            Text(stringResource(R.string.metadata_process))
                        }
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                }
            }
        }
    ) { paddingValues ->
        when {
            error != null && discoveryResult == null && !isComplete -> {
                FullScreenError(
                    message = error!!,
                    onRetry = { viewModel.discover() },
                    modifier = Modifier.padding(paddingValues)
                )
            }
            isComplete -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.metadata_complete), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        cleanupSummary?.let { s ->
                            if (s.failed > 0) {
                                Spacer(Modifier.height(8.dp))
                                Text(stringResource(R.string.metadata_cleanup_summary, s.success, s.failed), color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
            discoveryResult == null -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.metadata_discover_hint),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            else -> {
                Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                    if (isRebuilding) {
                        Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                val progress = if (totalCount > 0) completedCount.toFloat() / totalCount else 0f
                                LinearProgressIndicator(
                                    progress = { progress },
                                    modifier = Modifier.fillMaxWidth(),
                                    gapSize = 0.dp,
                                    drawStopIndicator = {}
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(stringResource(R.string.metadata_completed_items, completedCount, totalCount), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                    Text(stringResource(R.string.metadata_remaining_items, totalCount - completedCount), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }

                                currentFile?.let { name ->
                                    HorizontalDivider()
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                        Text(
                                            text = name,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            modifier = Modifier.weight(1f),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (!isRebuilding && !isDiscovering) {
                        val r = discoveryResult!!
                        Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(stringResource(R.string.metadata_orphaned_records_label, r.orphanedRecords.size))
                                Text(stringResource(R.string.metadata_incomplete_records, r.incompleteRecords.size))
                                Spacer(Modifier.height(4.dp))
                                HorizontalDivider()
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    stringResource(R.string.metadata_pending_count, r.orphanedRecords.size + r.incompleteRecords.size),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        if (r.otherVolumeRecords.isNotEmpty()) {
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer
                                )
                            ) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        stringResource(R.string.metadata_other_volume_count, r.otherVolumeRecords.size),
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    Text(
                                        stringResource(R.string.metadata_other_volume_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    Button(
                                        onClick = { showOtherVolumeConfirm = true },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.error
                                        )
                                    ) {
                                        Text(stringResource(R.string.metadata_clean_other_volume))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
