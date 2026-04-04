package cn.vectory.syncplayer.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.vectory.syncplayer.data.model.SyncSource
import cn.vectory.syncplayer.data.repository.SyncSourceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SyncSourceListViewModel @Inject constructor(
    private val syncSourceRepository: SyncSourceRepository
) : ViewModel() {
    val sources: StateFlow<List<SyncSource>> = syncSourceRepository.getAllFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _deleteError = MutableStateFlow<String?>(null)
    val deleteError: StateFlow<String?> = _deleteError.asStateFlow()

    fun deleteSource(id: Long) {
        viewModelScope.launch {
            val count = syncSourceRepository.getRelationCount(id)
            if (count > 0) {
                _deleteError.value = "该同步源仍有 $count 个同步关系，请先删除所有同步关系"
            } else {
                syncSourceRepository.delete(id)
            }
        }
    }

    fun clearDeleteError() {
        _deleteError.value = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncSourceListScreen(
    onNavigateBack: () -> Unit,
    onNavigateToEdit: (sourceId: Long) -> Unit,
    onNavigateToCreate: () -> Unit,
    viewModel: SyncSourceListViewModel = hiltViewModel()
) {
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val deleteError by viewModel.deleteError.collectAsStateWithLifecycle()
    var showDeleteDialog by remember { mutableStateOf<Long?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("同步源管理") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
        if (sources.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("尚未配置同步源，点击右下角 + 新增同步源")
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                items(sources, key = { it.id }) { source ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(source.name, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "${source.protocol.name} · ${source.host}:${source.port}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = { onNavigateToEdit(source.id) }) {
                                    Text("编辑")
                                }
                                Spacer(Modifier.width(8.dp))
                                TextButton(onClick = { showDeleteDialog = source.id }) {
                                    Text("删除")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (deleteError != null) {
        AlertDialog(
            onDismissRequest = { viewModel.clearDeleteError() },
            title = { Text("无法删除") },
            text = { Text(deleteError ?: "") },
            confirmButton = {
                TextButton(onClick = { viewModel.clearDeleteError() }) {
                    Text("确定")
                }
            }
        )
    }

    if (showDeleteDialog != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("删除同步源") },
            text = { Text("确定要删除该同步源吗？") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog?.let { viewModel.deleteSource(it) }
                    showDeleteDialog = null
                }) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text("取消")
                }
            }
        )
    }
}
