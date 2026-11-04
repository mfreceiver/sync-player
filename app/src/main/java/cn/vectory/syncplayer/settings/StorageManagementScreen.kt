package cn.vectory.syncplayer.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.vectory.syncplayer.R
import cn.vectory.syncplayer.sync.formatSize

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageManagementScreen(
    viewModel: StorageManagementViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showResetDialog by remember { mutableStateOf(false) }

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
            // 外部 SD v2：默认存储位置——切换只影响新文件（existing 行写回自身卷）；
            // 不显示卷 id/UUID/路径，只显示内部/外部标签与可用性/剩余空间。
            Text(
                stringResource(R.string.storage_default_volume_section),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
            )

            state.volumes.forEach { volume ->
                ListItem(
                    headlineContent = {
                        Text(
                            stringResource(
                                if (volume.isExternal) R.string.storage_volume_external
                                else R.string.storage_volume_internal
                            )
                        )
                    },
                    supportingContent = {
                        val label = buildString {
                            if (!volume.isAvailable) {
                                append(stringResource(R.string.storage_volume_unavailable))
                            } else {
                                volume.usableBytes?.let {
                                    append(stringResource(R.string.storage_volume_free_space, formatSize(it)))
                                }
                            }
                        }
                        if (label.isNotBlank()) Text(label)
                    },
                    trailingContent = {
                        Row {
                            if (volume.isDefault) {
                                Text(
                                    stringResource(R.string.storage_volume_current_default),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            } else if (volume.isAvailable) {
                                TextButton(
                                    onClick = { viewModel.setDefaultVolume(volume.volumeId) },
                                    enabled = !state.isResetting
                                ) {
                                    Text(stringResource(R.string.storage_volume_set_default))
                                }
                            }
                            // 外部卷显式注销（登记是显式操作，注销也是；默认卷先切走再注销）。
                            if (volume.isExternal && !volume.isDefault && !state.isResetting) {
                                TextButton(
                                    onClick = { viewModel.unregisterExternalVolume(volume.volumeId) }
                                ) {
                                    Text(stringResource(R.string.storage_volume_unregister))
                                }
                            }
                        }
                    }
                )
            }

            // 登记外部存储卡（能力检查通过才生效；无卡/检查失败显示通用失败提示）。
            OutlinedButton(
                onClick = { viewModel.registerExternalVolume() },
                enabled = !state.isResetting,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Text(stringResource(R.string.storage_volume_register))
            }

            if (state.setDefaultVolumeFailed || state.volumeOperationFailed) {
                Text(
                    stringResource(R.string.storage_error_generic),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            // Storage usage & reset
            Text(
                stringResource(R.string.storage_usage_section),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
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
                Text(stringResource(R.string.storage_reset_warning_body))
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
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}
