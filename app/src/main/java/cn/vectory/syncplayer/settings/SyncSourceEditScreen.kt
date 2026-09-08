package cn.vectory.syncplayer.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import cn.vectory.syncplayer.ui.theme.LocalExtendedColors
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.vectory.syncplayer.data.model.SyncProtocol
import cn.vectory.syncplayer.R
import cn.vectory.syncplayer.sync.SyncErrorCode
import cn.vectory.syncplayer.sync.SyncException
import cn.vectory.syncplayer.sync.SyncOperation
import cn.vectory.syncplayer.sync.syncErrorMessage
import cn.vectory.syncplayer.ui.components.FormResultText



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncSourceEditScreen(
    onNavigateBack: () -> Unit,
    onNavigateToSourceList: () -> Unit,
    viewModel: SyncSourceEditViewModel = hiltViewModel()
) {
    val extendedColors = LocalExtendedColors.current
    val name by viewModel.name.collectAsStateWithLifecycle()
    val protocol by viewModel.protocol.collectAsStateWithLifecycle()
    val host by viewModel.host.collectAsStateWithLifecycle()
    val port by viewModel.port.collectAsStateWithLifecycle()
    val username by viewModel.username.collectAsStateWithLifecycle()
    val password by viewModel.password.collectAsStateWithLifecycle()
    val isTesting by viewModel.isTesting.collectAsStateWithLifecycle()
    val testResult by viewModel.testResult.collectAsStateWithLifecycle()
    val isSaving by viewModel.isSaving.collectAsStateWithLifecycle()
    val saveResult by viewModel.saveResult.collectAsStateWithLifecycle()

    val sourceId = viewModel.sourceId
    var passwordVisible by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var isNavigatingBack by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (sourceId > 0L) stringResource(R.string.source_edit) else stringResource(R.string.source_new)) },
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
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { viewModel.setName(it) },
                        label = { Text(stringResource(R.string.source_name)) },
                        placeholder = { Text(stringResource(R.string.source_name_placeholder), color = extendedColors.placeholderGrey) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(Modifier.height(8.dp))
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
                        onValueChange = { viewModel.setHost(it) },
                        label = { Text(stringResource(R.string.source_server)) },
                        placeholder = { Text(stringResource(R.string.source_server_placeholder), color = extendedColors.placeholderGrey) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = port,
                        onValueChange = { viewModel.setPort(it) },
                        label = { Text(stringResource(R.string.source_port)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )

                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = username,
                        onValueChange = { viewModel.setUsername(it) },
                        label = { Text(stringResource(R.string.source_username)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { viewModel.setPassword(it) },
                        label = { Text(stringResource(R.string.source_password)) },
                        singleLine = true,
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (passwordVisible) stringResource(R.string.source_hide_password) else stringResource(R.string.source_show_password)
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
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
                            Text(if (isTesting) stringResource(R.string.source_testing) else stringResource(R.string.source_test_connection))
                        }
                        Button(
                            onClick = viewModel::save,
                            enabled = !isSaving && name.isNotBlank() && host.isNotBlank(),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (isSaving) stringResource(R.string.common_saving) else stringResource(R.string.common_save))
                        }
                    }

                    if (sourceId > 0L) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { showDeleteDialog = true },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = stringResource(R.string.common_delete),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.source_delete_source))
                        }
                    }

                    testResult?.let { result ->
                        Spacer(Modifier.height(8.dp))
                        FormResultText(
                            message = sourceTestResultText(result, host),
                            isSuccess = result is SourceTestResult.Connected
                        )
                    }
                    saveResult?.let { result ->
                        Spacer(Modifier.height(4.dp))
                        FormResultText(
                            message = sourceSaveResultText(result, host),
                            isSuccess = when (result) {
                                is SourceSaveResult.Saved, is SourceSaveResult.Deleted -> true
                                is SourceSaveResult.BlockedByRelations,
                                is SourceSaveResult.SaveFailed,
                                is SourceSaveResult.DeleteFailed -> false
                            }
                        )
                    }
                }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.source_delete_confirm_title)) },
            text = { Text(stringResource(R.string.source_delete_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSource()
                    showDeleteDialog = false
                }) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    LaunchedEffect(saveResult) {
        // F09: 仅保存成功返回列表（类型驱动；与原字符串匹配行为等价）。
        if (saveResult is SourceSaveResult.Saved) {
            onNavigateToSourceList()
        }
    }
}

/**
 * F09: 类型化结果 → 展示文案。只复用既有 string 资源；错误细节按 F02 模式用
 * 结构化码经共享的 [syncErrorMessage] 码→资源映射重建（不重写 mapper）。
 * [host] 来自表单，覆盖异常内嵌 host 以保留主机信息（syncErrorMessage 的
 * host 参数即为「caller knows the source being tested」场景设计）。
 */
@Composable
private fun sourceTestResultText(result: SourceTestResult, host: String): String = when (result) {
    is SourceTestResult.Connected -> stringResource(R.string.source_connect_success)
    is SourceTestResult.TestFailed -> stringResource(
        R.string.source_connect_failed,
        resolvedSyncErrorText(result.code, SyncOperation.CONNECT, host)
    )
}

@Composable
private fun sourceSaveResultText(result: SourceSaveResult, host: String): String = when (result) {
    is SourceSaveResult.Saved -> stringResource(R.string.source_save_success)
    is SourceSaveResult.Deleted -> stringResource(R.string.source_delete_success)
    is SourceSaveResult.BlockedByRelations ->
        stringResource(R.string.source_has_relations, result.relationCount)
    is SourceSaveResult.SaveFailed -> stringResource(
        R.string.source_save_failed,
        resolvedSyncErrorText(result.code, SyncOperation.DOWNLOAD, host)
    )
    is SourceSaveResult.DeleteFailed -> stringResource(
        R.string.source_delete_failed,
        resolvedSyncErrorText(result.code, SyncOperation.DELETE, host)
    )
}

@Composable
private fun resolvedSyncErrorText(code: SyncErrorCode, operation: SyncOperation, host: String?): String =
    syncErrorMessage(SyncException(code, operation), LocalContext.current, host)
