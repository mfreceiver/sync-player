package cn.vectory.syncplayer.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import cn.vectory.syncplayer.R
import cn.vectory.syncplayer.ui.components.FullScreenError

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GainAnalysisScreen(
    onBack: () -> Unit,
    viewModel: GainAnalysisViewModel = hiltViewModel()
) {
    val discoveredFiles by viewModel.discoveredFiles.collectAsState()
    val isDiscovering by viewModel.isDiscovering.collectAsState()
    val isAnalyzing by viewModel.isAnalyzing.collectAsState()
    val isComplete by viewModel.isComplete.collectAsState()
    val error by viewModel.error.collectAsState()
    val completedCount by viewModel.completedCount.collectAsState()
    val totalCount by viewModel.totalCount.collectAsState()
    val activeAnalysis by viewModel.activeAnalysis.collectAsState()
    val completedPaths by viewModel.completedPaths.collectAsState()
    var isNavigatingBack by remember { mutableStateOf(false) }

    val remainingFiles by remember {
        derivedStateOf { discoveredFiles.filter { it.localAbsolutePath !in completedPaths } }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.gain_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (!isNavigatingBack) {
                                isNavigatingBack = true
                                if (isAnalyzing) viewModel.cancelAnalysis()
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
                        if (isAnalyzing) {
                            viewModel.cancelAnalysis()
                        } else {
                            onBack()
                        }
                    }) {
                        Text(if (isAnalyzing) stringResource(R.string.common_stop) else stringResource(R.string.common_cancel))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { viewModel.discover() },
                        enabled = !isDiscovering && !isAnalyzing
                    ) {
                        if (isDiscovering) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        } else {
                            Text(stringResource(R.string.gain_discover))
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { viewModel.startAnalysis() },
                        enabled = discoveredFiles.isNotEmpty() && !isAnalyzing && !isDiscovering
                    ) {
                        if (isAnalyzing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        } else {
                            Text(stringResource(R.string.gain_process))
                        }
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                }
            }
        }
    ) { paddingValues ->
        when {
            error != null && discoveredFiles.isEmpty() -> {
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
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(stringResource(R.string.gain_complete), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            discoveredFiles.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.gain_discover_hint),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            else -> {
                Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                    if (isAnalyzing) {
                        GainAnalysisProgressCard(
                            completedFiles = completedCount,
                            totalFiles = totalCount,
                            activeAnalysis = activeAnalysis
                        )
                    }
                    if (!isAnalyzing && !isDiscovering) {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(16.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(stringResource(R.string.gain_discovered_count, discoveredFiles.size))
                            }
                        }
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        items(remainingFiles, key = { it.id }) { video ->
                            ListItem(
                                headlineContent = {
                                    Text(
                                        text = video.fileName,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                supportingContent = {
                                    Text(
                                        text = video.relativePath,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                },
                                leadingContent = {
                                    Icon(
                                        Icons.Default.Equalizer,
                                        contentDescription = stringResource(R.string.gain_analysis),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GainAnalysisProgressCard(
    completedFiles: Int,
    totalFiles: Int,
    activeAnalysis: List<String>
) {
    Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val progress = if (totalFiles > 0) completedFiles.toFloat() / totalFiles else 0f
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
                Text(stringResource(R.string.gain_completed_files, completedFiles, totalFiles), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.gain_remaining, totalFiles - completedFiles), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (activeAnalysis.isNotEmpty()) {
                HorizontalDivider()
                Text(stringResource(R.string.gain_analyzing, activeAnalysis.size), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                activeAnalysis.forEach { name ->
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
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
