package cn.vectory.syncplayer.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetadataRebuildScreen(
    onBack: () -> Unit,
    viewModel: MetadataRebuildViewModel = hiltViewModel()
) {
    val discoveryResult by viewModel.discoveryResult.collectAsState()
    val isDiscovering by viewModel.isDiscovering.collectAsState()
    val isRebuilding by viewModel.isRebuilding.collectAsState()
    val isComplete by viewModel.isComplete.collectAsState()
    val error by viewModel.error.collectAsState()
    val completedCount by viewModel.completedCount.collectAsState()
    val totalCount by viewModel.totalCount.collectAsState()
    val currentFile by viewModel.currentFile.collectAsState()
    var isNavigatingBack by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("重建元数据") },
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
                        Text(if (isRebuilding) "停止" else "取消")
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
                            Text("发现")
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { viewModel.startRebuild() },
                        enabled = discoveryResult != null && !isRebuilding && !isDiscovering
                    ) {
                        if (isRebuilding) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        } else {
                            Text("处理")
                        }
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                }
            }
        }
    ) { paddingValues ->
        when {
            error != null && discoveryResult == null && !isComplete -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(error!!, color = MaterialTheme.colorScheme.error)
                    }
                }
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
                        Text("重建完成", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                }
            }
            discoveryResult == null -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "点击下方\u201C发现\u201D按钮扫描需要处理的文件",
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
                                    Text("已完成 $completedCount / $totalCount 项", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                    Text("剩余 ${totalCount - completedCount} 项", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                val r = discoveryResult
                                Text("发现 ${r!!.newFiles.size} 个磁盘文件缺少记录")
                                Text("发现 ${r.orphanedRecords.size} 个孤立数据库记录")
                                Text("发现 ${r.incompleteRecords.size} 个文件缺少元数据")
                                Spacer(Modifier.height(4.dp))
                                HorizontalDivider()
                                Spacer(Modifier.height(4.dp))
                                Text("共需处理 ${r.newFiles.size + r.orphanedRecords.size + r.incompleteRecords.size} 项", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}
