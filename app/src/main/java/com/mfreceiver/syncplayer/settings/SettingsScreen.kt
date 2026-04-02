package com.mfreceiver.syncplayer.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mfreceiver.syncplayer.data.model.SyncProtocol
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                ServerConfigCard(
                    uiState = uiState,
                    onProtocolChange = { viewModel.updateProtocol(it) },
                    onHostChange = { viewModel.updateHost(it) },
                    onPortChange = { viewModel.updatePort(it) },
                    onUsernameChange = { viewModel.updateUsername(it) },
                    onPasswordChange = { viewModel.updatePassword(it) },
                    onRemotePathChange = { viewModel.updateRemotePath(it) },
                    onTestConnection = { viewModel.testConnection() },
                    onSaveConfig = { viewModel.saveConfig() }
                )
            }
            
            item {
                PlaybackSettingsCard(
                    currentSpeed = uiState.longPressSpeed,
                    onSpeedChange = { viewModel.setLongPressSpeed(it) }
                )
            }
            
            item {
                AboutCard(
                    lastSyncTime = uiState.lastSyncTime
                )
            }
        }
    }
}

@Composable
private fun ServerConfigCard(
    uiState: SettingsUiState,
    onProtocolChange: (SyncProtocol) -> Unit,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onRemotePathChange: (String) -> Unit,
    onTestConnection: () -> Unit,
    onSaveConfig: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "服务器配置",
                style = MaterialTheme.typography.titleMedium
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = uiState.protocol == SyncProtocol.SMB,
                    onClick = { onProtocolChange(SyncProtocol.SMB) },
                    label = { Text("SMB") },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = uiState.protocol == SyncProtocol.SFTP,
                    onClick = { onProtocolChange(SyncProtocol.SFTP) },
                    label = { Text("SFTP") },
                    modifier = Modifier.weight(1f)
                )
            }
            
            OutlinedTextField(
                value = uiState.host,
                onValueChange = onHostChange,
                label = { Text("服务器地址") },
                placeholder = { Text("192.168.1.100") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                isError = uiState.hostError != null,
                supportingText = uiState.hostError?.let { { Text(it) } }
            )
            
            OutlinedTextField(
                value = uiState.port,
                onValueChange = onPortChange,
                label = { Text("端口") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                isError = uiState.portError != null,
                supportingText = uiState.portError?.let { { Text(it) } }
            )
            
            OutlinedTextField(
                value = uiState.username,
                onValueChange = onUsernameChange,
                label = { Text("用户名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            
            var passwordVisible by remember { mutableStateOf(false) }
            OutlinedTextField(
                value = uiState.password,
                onValueChange = onPasswordChange,
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
            
            OutlinedTextField(
                value = uiState.remotePath,
                onValueChange = onRemotePathChange,
                label = { Text("远程文件夹路径") },
                placeholder = { Text("/videos") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onTestConnection,
                    enabled = !uiState.isTesting,
                    modifier = Modifier.weight(1f)
                ) {
                    if (uiState.isTesting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text("测试连接")
                }
                
                Button(
                    onClick = onSaveConfig,
                    enabled = !uiState.isSaving,
                    modifier = Modifier.weight(1f)
                ) {
                    if (uiState.isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text("保存")
                }
            }
            
            uiState.testResult?.let { result ->
                Text(
                    text = result,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (result.contains("成功")) 
                        MaterialTheme.colorScheme.primary 
                    else 
                        MaterialTheme.colorScheme.error
                )
            }
            
            uiState.saveResult?.let { result ->
                Text(
                    text = result,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (result.contains("成功")) 
                        MaterialTheme.colorScheme.primary 
                    else 
                        MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun PlaybackSettingsCard(
    currentSpeed: Float,
    onSpeedChange: (Float) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "播放设置",
                style = MaterialTheme.typography.titleMedium
            )
            
            Text(
                text = "长按加速倍速",
                style = MaterialTheme.typography.bodyMedium
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val speeds = listOf(1.5f, 2.0f, 2.5f, 3.0f)
                speeds.forEach { speed ->
                    FilterChip(
                        selected = currentSpeed == speed,
                        onClick = { onSpeedChange(speed) },
                        label = { Text("${speed}x") },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun AboutCard(
    lastSyncTime: Long?
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "关于",
                style = MaterialTheme.typography.titleMedium
            )
            
            ListItem(
                headlineContent = { Text("应用版本") },
                supportingContent = { Text("1.0.0") }
            )
            
            ListItem(
                headlineContent = { Text("上次同步") },
                supportingContent = { 
                    Text(formatSyncTime(lastSyncTime)) 
                }
            )
        }
    }
}

private fun formatSyncTime(time: Long?): String {
    if (time == null || time == 0L) return "从未同步"
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return sdf.format(Date(time))
}
