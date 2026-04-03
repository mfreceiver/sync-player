package cn.vectory.syncplayer.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.vectory.syncplayer.data.model.SyncProtocol
import cn.vectory.syncplayer.data.local.entity.SyncLogEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit
) {
    val protocol by viewModel.protocol.collectAsStateWithLifecycle()
    val host by viewModel.host.collectAsStateWithLifecycle()
    val port by viewModel.port.collectAsStateWithLifecycle()
    val username by viewModel.username.collectAsStateWithLifecycle()
    val password by viewModel.password.collectAsStateWithLifecycle()
    val remotePath by viewModel.remotePath.collectAsStateWithLifecycle()
    val longPressSpeed by viewModel.longPressSpeed.collectAsStateWithLifecycle()
    val isTesting by viewModel.isTesting.collectAsStateWithLifecycle()
    val testResult by viewModel.testResult.collectAsStateWithLifecycle()
    val isSaving by viewModel.isSaving.collectAsStateWithLifecycle()
    val saveResult by viewModel.saveResult.collectAsStateWithLifecycle()
    val lastSyncTime by viewModel.lastSyncTime.collectAsStateWithLifecycle()
    val syncLogs by viewModel.syncLogs.collectAsStateWithLifecycle()

    var passwordVisible by remember { mutableStateOf(false) }
    var logViewerExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("服务器配置", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(12.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = protocol == SyncProtocol.SMB,
                                onClick = { viewModel.setProtocol(SyncProtocol.SMB) },
                                label = { Text("SMB") }
                            )
                            FilterChip(
                                selected = protocol == SyncProtocol.SFTP,
                                onClick = { viewModel.setProtocol(SyncProtocol.SFTP) },
                                label = { Text("SFTP") }
                            )
                        }

                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = host,
                            onValueChange = viewModel::setHost,
                            label = { Text("服务器地址") },
                            placeholder = { Text("192.168.1.100") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = port,
                            onValueChange = viewModel::setPort,
                            label = { Text("端口") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )

                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = username,
                            onValueChange = viewModel::setUsername,
                            label = { Text("用户名") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = password,
                            onValueChange = viewModel::setPassword,
                            label = { Text("密码") },
                            singleLine = true,
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(
                                        imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = if (passwordVisible) "隐藏密码" else "显示密码"
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = remotePath,
                            onValueChange = viewModel::setRemotePath,
                            label = { Text("远程文件夹路径") },
                            placeholder = { Text("/share/videos") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = viewModel::testConnection,
                                enabled = !isTesting && host.isNotBlank(),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(if (isTesting) "测试中…" else "测试连接")
                            }
                            Button(
                                onClick = viewModel::saveConfig,
                                enabled = !isSaving && host.isNotBlank(),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(if (isSaving) "保存中…" else "保存")
                            }
                        }

                        testResult?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                it,
                                color = if (it.contains("成功")) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        saveResult?.let {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                it,
                                color = if (it.contains("成功")) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("播放设置", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(12.dp))
                        Text("长按加速倍速", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(1.5f, 2.0f, 2.5f, 3.0f).forEach { speed ->
                                FilterChip(
                                    selected = longPressSpeed == speed,
                                    onClick = { viewModel.setLongPressSpeed(speed) },
                                    label = { Text("${speed}x") }
                                )
                            }
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("关于", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(12.dp))
                        ListItem(
                            headlineContent = { Text("应用版本") },
                            trailingContent = { Text("1.0.0") }
                        )
                        ListItem(
                            headlineContent = { Text("上次同步") },
                            trailingContent = { Text(lastSyncTime ?: "从未同步") }
                        )
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("调试日志", style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = { logViewerExpanded = !logViewerExpanded }) {
                                Text(if (logViewerExpanded) "收起" else "查看日志")
                            }
                        }

                        if (logViewerExpanded) {
                            Spacer(Modifier.height(12.dp))

                            if (syncLogs.isEmpty()) {
                                Text(
                                    "暂无日志记录",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 300.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    items(syncLogs, key = { it.id }) { log ->
                                        LogEntryItem(log = log)
                                    }
                                }
                            }

                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(
                                onClick = viewModel::clearLogs,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("清除日志")
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun LogEntryItem(log: SyncLogEntity) {
    val statusColor = when (log.status) {
        "SUCCESS" -> Color(0xFF4CAF50)
        "FAILED" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatLogTimestamp(log.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = log.status,
                style = MaterialTheme.typography.labelSmall,
                color = statusColor
            )
        }

        Text(
            text = "${log.action} · ${log.relativePath}",
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        log.errorMessage?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        HorizontalDivider(
            modifier = Modifier.padding(top = 8.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
        )
    }
}

private fun formatLogTimestamp(timestamp: Long): String {
    val sdf = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(timestamp))
}
