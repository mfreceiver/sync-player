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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.Context
import cn.vectory.syncplayer.data.model.SyncOutcome
import cn.vectory.syncplayer.data.model.SyncRelationWithSource
import cn.vectory.syncplayer.R
import cn.vectory.syncplayer.sync.SyncErrorCode
import cn.vectory.syncplayer.sync.SyncException
import cn.vectory.syncplayer.sync.SyncOperation
import cn.vectory.syncplayer.sync.syncErrorMessage
import cn.vectory.syncplayer.ui.theme.LocalExtendedColors
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * F02: 持久化的错误码稳定标识 → 展示文案。
 * 复用 [syncErrorMessage] 既有的码→资源映射（不重写 mapper）；
 * 未知/外来码一律落到 sync_error_unknown，绝不当成功展示。
 */
private fun syncOutcomeErrorText(errorCodeName: String, context: Context): String {
    val code = runCatching { SyncErrorCode.valueOf(errorCodeName) }
        .getOrDefault(SyncErrorCode.UNKNOWN)
    return syncErrorMessage(SyncException(code, SyncOperation.DOWNLOAD), context)
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
                        val context = LocalContext.current
                        val lastSync = item.relation.lastSyncTime
                        val outcome = item.relation.lastSyncOutcome
                        ListItem(
                            headlineContent = { Text(item.relation.name) },
                            supportingContent = {
                                Text(
                                    stringResource(R.string.relation_last_sync, lastSync?.let {
                                        dateFormat.format(it)
                                    } ?: stringResource(R.string.relation_never_synced)),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (outcome != null) {
                                    when (outcome.status) {
                                        SyncOutcome.Status.SUCCESS -> Text(
                                            stringResource(R.string.sync_status_success),
                                            color = extendedColors.successGreen,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                        )
                                        SyncOutcome.Status.PARTIAL_FAILED -> {
                                            Text(
                                                stringResource(R.string.sync_status_partial_failed),
                                                color = MaterialTheme.colorScheme.error,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium,
                                            )
                                            outcome.failedCount?.let {
                                                Text(
                                                    stringResource(R.string.sync_status_failed_count, it),
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    style = MaterialTheme.typography.bodySmall,
                                                )
                                            }
                                        }
                                        SyncOutcome.Status.FAILED -> {
                                            Text(
                                                stringResource(R.string.sync_status_failed),
                                                color = MaterialTheme.colorScheme.error,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium,
                                            )
                                            outcome.errorCode?.let { codeName ->
                                                Text(
                                                    syncOutcomeErrorText(codeName, context),
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        }
                                        SyncOutcome.Status.CANCELLED -> Text(
                                            stringResource(R.string.sync_status_cancelled),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                        )
                                        SyncOutcome.Status.UNKNOWN -> Text(
                                            stringResource(R.string.sync_status_unknown),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                        )
                                    }
                                } // outcome == null：无记录，不显示额外状态行
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
