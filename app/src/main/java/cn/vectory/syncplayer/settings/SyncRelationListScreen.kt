package cn.vectory.syncplayer.settings

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.vectory.syncplayer.data.model.SyncRelationWithSource
import cn.vectory.syncplayer.data.repository.SyncRelationRepository
import cn.vectory.syncplayer.sync.SyncEngine
import cn.vectory.syncplayer.sync.SyncService
import cn.vectory.syncplayer.ui.theme.LocalExtendedColors
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import java.text.SimpleDateFormat
import java.util.Locale

@HiltViewModel
class SyncRelationListViewModel @Inject constructor(
    private val syncRelationRepository: SyncRelationRepository,
    private val syncEngine: SyncEngine,
    @ApplicationContext private val context: Context
) : ViewModel() {
    val relationsWithSource = syncRelationRepository.getAllWithSourceFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isBatchSyncing = MutableStateFlow(false)
    val isBatchSyncing = _isBatchSyncing.asStateFlow()

    private val _batchProgress = MutableStateFlow("")
    val batchProgress = _batchProgress.asStateFlow()

    private val _batchError = MutableStateFlow<String?>(null)
    val batchError = _batchError.asStateFlow()

    fun batchSyncAll() {
        viewModelScope.launch {
            _isBatchSyncing.value = true
            _batchError.value = null
            try {
                val relations = syncRelationRepository.getAllWithSource()
                for ((index, item) in relations.withIndex()) {
                    _batchProgress.value = "正在扫描 ${item.relation.name} (${index + 1}/${relations.size})"
                    val (downloads, deletes) = syncEngine.dryRun(item.relation.id)
                    if (downloads.isNotEmpty() || deletes.isNotEmpty()) {
                        _batchProgress.value = "正在同步 ${item.relation.name} (${index + 1}/${relations.size})"
                        val relation = item.relation
                        syncEngine.setPendingTask(cn.vectory.syncplayer.sync.PendingSyncTask(
                            relationId = relation.id,
                            relationName = relation.name,
                            localBasePath = relation.localBasePath,
                            toDownload = downloads,
                            toDelete = deletes
                        ))
                        SyncService.start(context, relation.id)
                        withContext(Dispatchers.IO) {
                            syncEngine.progress.first { p ->
                                (p.relationId == relation.id || p.relationId == 0L) &&
                                    (p.phase == cn.vectory.syncplayer.data.model.SyncPhase.COMPLETED ||
                                     p.phase == cn.vectory.syncplayer.data.model.SyncPhase.FAILED ||
                                     p.phase == cn.vectory.syncplayer.data.model.SyncPhase.CANCELLED)
                            }
                        }
                    }
                }
                _batchProgress.value = "全部同步完成"
            } catch (e: Exception) {
                _batchError.value = "批量同步失败: ${e.message}"
            } finally {
                _isBatchSyncing.value = false
            }
        }
    }

    fun batchDeleteSyncAll() {
        viewModelScope.launch {
            _isBatchSyncing.value = true
            _batchError.value = null
            try {
                val relations = syncRelationRepository.getAllWithSource()
                for ((index, item) in relations.withIndex()) {
                    _batchProgress.value = "正在扫描 ${item.relation.name} (${index + 1}/${relations.size})"
                    val (actions, errMsg) = syncEngine.dryRunDelete(item.relation.id)
                    if (actions.isNotEmpty()) {
                        _batchProgress.value = "正在删除 ${item.relation.name} (${index + 1}/${relations.size})"
                        val success = syncEngine.executeDelete(item.relation.id, actions)
                        if (!success) {
                            _batchError.value = "${item.relation.name} 删除失败"
                            break
                        }
                    }
                }
                _batchProgress.value = "全部删除同步完成"
            } catch (e: Exception) {
                _batchError.value = "批量删除失败: ${e.message}"
            } finally {
                _isBatchSyncing.value = false
            }
        }
    }

    suspend fun getAllWithSource(): List<SyncRelationWithSource> = syncRelationRepository.getAllWithSource()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncRelationListScreen(
    onNavigateBack: () -> Unit,
    onNavigateToEdit: (relationId: Long) -> Unit,
    onNavigateToCreate: () -> Unit,
    onNavigateToSyncPreview: (relationId: Long) -> Unit,
    onNavigateToGainAnalysis: (relationId: Long) -> Unit = {},
    viewModel: SyncRelationListViewModel = hiltViewModel()
) {
    val relations by viewModel.relationsWithSource.collectAsStateWithLifecycle()
    val isBatchSyncing by viewModel.isBatchSyncing.collectAsState()
    val batchProgress by viewModel.batchProgress.collectAsState()
    val batchError by viewModel.batchError.collectAsState()
    var showBatchMenu by remember { mutableStateOf(false) }
    var isNavigatingBack by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("同步关系管理") },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (!isNavigatingBack) {
                                isNavigatingBack = true
                                onNavigateBack()
                            }
                        },
                        enabled = !isNavigatingBack
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    Box {
                        IconButton(
                            onClick = { showBatchMenu = true },
                            enabled = !isBatchSyncing
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "批量操作")
                        }
                        DropdownMenu(
                            expanded = showBatchMenu,
                            onDismissRequest = { showBatchMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("全部同步（下载）") },
                                onClick = {
                                    showBatchMenu = false
                                    viewModel.batchSyncAll()
                                },
                                enabled = !isBatchSyncing
                            )
                            DropdownMenuItem(
                                text = { Text("全部同步（删除）") },
                                onClick = {
                                    showBatchMenu = false
                                    viewModel.batchDeleteSyncAll()
                                },
                                enabled = !isBatchSyncing
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNavigateToCreate) {
                Icon(Icons.Filled.Add, contentDescription = "新增")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (isBatchSyncing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = batchProgress,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            batchError?.let { error ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
            if (relations.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("尚未配置同步关系，点击右下角 + 新增同步关系")
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(vertical = 16.dp)
                ) {
                    items(relations, key = { it.relation.id }) { item ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(item.relation.name, style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(4.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Filled.Cloud,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "${item.sourceName}  ${item.relation.remotePath}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text(
                                    item.relation.localBasePath,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(4.dp))

                                val extendedColors = LocalExtendedColors.current
                                val lastSync = item.relation.lastSyncTime
                                val lastResult = item.relation.lastSyncResult
                                val isSuccess = lastResult != null && !lastResult.contains("失败")
                                val resultColor = when {
                                    lastResult == null -> MaterialTheme.colorScheme.onSurfaceVariant
                                    isSuccess -> extendedColors.successGreen
                                    else -> MaterialTheme.colorScheme.error
                                }

                                Text(
                                    "上次同步: ${lastSync?.let {
                                        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(it)
                                    } ?: "从未同步"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = resultColor
                                )

                                if (lastResult != null && !isSuccess) {
                                    Text(
                                        lastResult,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                        maxLines = 2
                                    )
                                }
                                Spacer(Modifier.height(12.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedButton(onClick = { onNavigateToSyncPreview(item.relation.id) }) {
                                        Text("同步", style = MaterialTheme.typography.labelSmall)
                                    }
                                    OutlinedButton(onClick = { onNavigateToGainAnalysis(item.relation.id) }) {
                                        Text("增益分析", style = MaterialTheme.typography.labelSmall)
                                    }
                                    Spacer(Modifier.weight(1f))
                                    IconButton(onClick = { onNavigateToEdit(item.relation.id) }) {
                                        Icon(
                                            imageVector = Icons.Default.Edit,
                                            contentDescription = "编辑",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
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
