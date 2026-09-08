package cn.vectory.syncplayer.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.vectory.syncplayer.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageManagementScreen(
    viewModel: StorageManagementViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showResetDialog by remember { mutableStateOf(false) }

    val openDocumentTree = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val treeUri = uri ?: return@rememberLauncherForActivityResult
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            context.contentResolver.takePersistableUriPermission(treeUri, flags)
            viewModel.onTreePermissionGranted()
            viewModel.switchToExternal(treeUri.toString())
        } catch (_: SecurityException) {
            // F06：不再静默——上报结构化错误（不含 URI/message），由 UI 呈现 + 重试。
            viewModel.onTreePermissionDenied()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.storage_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Section 1: Storage method
            Text(
                stringResource(R.string.storage_method_section),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
            )

            // Internal storage option
            ListItem(
                headlineContent = { Text(stringResource(R.string.storage_method_internal)) },
                trailingContent = {
                    RadioButton(
                        selected = !state.isExternal,
                        enabled = state.canSwitch,
                        onClick = null,
                    )
                },
                modifier = Modifier.clickable {
                    if (state.canSwitch) viewModel.switchToInternal()
                }
            )

            // External storage option
            ListItem(
                headlineContent = { Text(stringResource(R.string.storage_method_external)) },
                supportingContent = {
                    val ep = state.externalPath
                    if (state.isExternal && ep != null) {
                        Text(stringResource(R.string.storage_external_path, ep))
                    } else if (!state.isExternal) {
                        Text(stringResource(R.string.storage_external_not_configured))
                    }
                },
                trailingContent = {
                    RadioButton(
                        selected = state.isExternal,
                        enabled = state.canSwitch,
                        onClick = null,
                    )
                },
                modifier = Modifier.clickable {
                    if (state.canSwitch) openDocumentTree.launch(null)
                }
            )

            // F06：将可恢复的授权失败紧贴外部存储选项呈现。它是持久状态，不用 Snackbar，
            // 以免用户从 SAF 返回或滚动后失去重试入口。
            if (state.permissionError != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            stringResource(R.string.storage_permission_denied),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(onClick = { openDocumentTree.launch(null) }) {
                            Text(stringResource(R.string.common_retry))
                        }
                    }
                }
            }

            // Blocked switch message
            if (!state.canSwitch) {
                Text(
                    stringResource(R.string.storage_switch_blocked),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // Section 2: Storage usage & reset
            Text(
                stringResource(R.string.storage_usage_section),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp)
            )

            ListItem(
                headlineContent = {
                    Text(stringResource(R.string.storage_usable_space, state.usableSpaceLabel))
                }
            )

            ListItem(
                headlineContent = {
                    Text(stringResource(R.string.storage_file_count, state.mediaFileCount))
                }
            )

            if (state.isResetting) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
                Text(
                    stringResource(R.string.storage_reset_in_progress),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            Button(
                onClick = { showResetDialog = true },
                enabled = state.canReset,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(stringResource(R.string.storage_reset))
            }

            state.resetWarning?.let { warning ->
                Text(
                    warning,
                    color = if (state.isResetting) MaterialTheme.colorScheme.onSurfaceVariant
                           else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }
    }

    // Reset confirmation dialog
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            icon = {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text(stringResource(R.string.storage_reset_warning_title)) },
            text = {
                Text(
                    if (state.isExternal) {
                        stringResource(R.string.storage_reset_warning_body_external)
                    } else {
                        stringResource(R.string.storage_reset_warning_body_internal)
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetDialog = false
                        viewModel.resetStorage()
                    }
                ) {
                    Text(
                        stringResource(R.string.storage_reset),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text(stringResource(R.string.volume_switch_cancel))
                }
            }
        )
    }
}
