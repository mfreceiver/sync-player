package cn.vectory.syncplayer.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.vectory.syncplayer.data.model.SyncRelationWithSource
import cn.vectory.syncplayer.data.repository.SyncRelationRepository
import cn.vectory.syncplayer.ui.theme.LocalExtendedColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class SyncRelationListViewModel @Inject constructor(
    private val syncRelationRepository: SyncRelationRepository
) : ViewModel() {
    val relationsWithSource = syncRelationRepository.getAllWithSourceFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncRelationListScreen(
    onNavigateBack: () -> Unit,
    onNavigateToEdit: (relationId: Long) -> Unit,
    onNavigateToCreate: () -> Unit,
    onNavigateToSyncPreview: (relationId: Long) -> Unit,
    viewModel: SyncRelationListViewModel = hiltViewModel()
) {
    val relations by viewModel.relationsWithSource.collectAsStateWithLifecycle()
    var isNavigatingBack by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("同步关系") },
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
            if (relations.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("尚未配置同步关系，点击右下角 + 新增同步关系")
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(relations, key = { it.relation.id }) { item ->
                        val extendedColors = LocalExtendedColors.current
                        val lastSync = item.relation.lastSyncTime
                        val lastResult = item.relation.lastSyncResult
                        val isSuccess = lastResult != null && !lastResult.contains("失败")
                        val resultColor = when {
                            lastResult == null -> MaterialTheme.colorScheme.onSurfaceVariant
                            isSuccess -> extendedColors.successGreen
                            else -> MaterialTheme.colorScheme.error
                        }

                        ListItem(
                            headlineContent = { Text(item.relation.name) },
                            supportingContent = {
                                Text(
                                    "上次同步: ${lastSync?.let {
                                        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(it)
                                    } ?: "从未同步"}",
                                    color = resultColor
                                )
                                if (lastResult != null && !isSuccess) {
                                    Text(lastResult, color = MaterialTheme.colorScheme.error, maxLines = 2)
                                }
                            },
                            leadingContent = {
                                Icon(
                                    Icons.Default.Sync,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            },
                            trailingContent = {
                                IconButton(onClick = { onNavigateToEdit(item.relation.id) }) {
                                    Icon(Icons.Default.Edit, contentDescription = "编辑", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            modifier = Modifier.clickable { onNavigateToSyncPreview(item.relation.id) }
                        )
                    }
                }
            }
        }
    }
}
