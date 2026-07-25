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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.vectory.syncplayer.data.model.SyncRelationWithSource
import cn.vectory.syncplayer.R
import cn.vectory.syncplayer.ui.theme.LocalExtendedColors
import java.text.SimpleDateFormat
import java.util.Locale

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
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    var isNavigatingBack by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.relation_list_title)) },
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNavigateToCreate) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.relation_add))
            }
        }
    ) { padding ->
        if (relations.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(stringResource(R.string.relation_empty))
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(top = 0.dp, bottom = 8.dp)
                ) {
                    items(relations, key = { it.relation.id }) { item ->
                        val extendedColors = LocalExtendedColors.current
                        val lastSync = item.relation.lastSyncTime
                        val lastResult = item.relation.lastSyncResult
                        val failKeyword = stringResource(R.string.sync_failed).substringBefore(":").substringBefore("：")
                        val isSuccess = lastResult != null && !lastResult.contains(failKeyword)
                        val resultColor = when {
                            lastResult == null -> MaterialTheme.colorScheme.onSurfaceVariant
                            isSuccess -> extendedColors.successGreen
                            else -> MaterialTheme.colorScheme.error
                        }

                        ListItem(
                            headlineContent = { Text(item.relation.name) },
                            supportingContent = {
                                Text(
                                    stringResource(R.string.relation_last_sync, lastSync?.let {
                                        dateFormat.format(it)
                                    } ?: stringResource(R.string.relation_never_synced)),
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
                                    Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.relation_edit_label), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            modifier = Modifier.clickable { onNavigateToSyncPreview(item.relation.id) }
                        )
                    }
                }
            }
    }
}
