package cn.vectory.syncplayer.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import cn.vectory.syncplayer.ui.theme.LocalExtendedColors
import cn.vectory.syncplayer.sync.RemoteBrowseError
import cn.vectory.syncplayer.sync.SyncErrorCode
import cn.vectory.syncplayer.sync.SyncException
import cn.vectory.syncplayer.sync.SyncOperation
import cn.vectory.syncplayer.sync.syncErrorMessage
import cn.vectory.syncplayer.R
import cn.vectory.syncplayer.ui.components.FormResultText
import cn.vectory.syncplayer.ui.components.FullScreenError
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncRelationEditScreen(
    onNavigateBack: () -> Unit,
    onNavigateToRelationList: () -> Unit = {},
    viewModel: SyncRelationEditViewModel = hiltViewModel()
) {
    val extendedColors = LocalExtendedColors.current
    val name by viewModel.name.collectAsStateWithLifecycle()
    val sources by viewModel.availableSources.collectAsStateWithLifecycle()
    val selectedSourceId by viewModel.selectedSourceId.collectAsStateWithLifecycle()
    val remotePath by viewModel.remotePath.collectAsStateWithLifecycle()
    val localBasePath by viewModel.localBasePath.collectAsStateWithLifecycle()
    val remoteDirs by viewModel.remoteDirectories.collectAsStateWithLifecycle()
    val currentRemotePath by viewModel.currentRemoteBrowsePath.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val browseError by viewModel.browseError.collectAsStateWithLifecycle()
    val isBrowsing by viewModel.isBrowsing.collectAsStateWithLifecycle()
    val isTesting by viewModel.isTesting.collectAsStateWithLifecycle()
    val testResult by viewModel.testResult.collectAsStateWithLifecycle()
    val isSaving by viewModel.isSaving.collectAsStateWithLifecycle()
    val saveResult by viewModel.saveResult.collectAsStateWithLifecycle()
    val loadState by viewModel.loadState.collectAsStateWithLifecycle()
    val credentialsRequired by viewModel.credentialsRequired.collectAsStateWithLifecycle()
    val canSelectRemotePath by viewModel.canSelectRemotePath.collectAsStateWithLifecycle()

    var showRemoteBrowser by remember { mutableStateOf(false) }
    var sourceExpanded by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var isNavigatingBack by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val pathNotFoundStr = stringResource(R.string.relation_remote_path_not_found)
    val remoteTimeoutStr = stringResource(R.string.relation_remote_timeout)
    val remoteBrowseFailedStr = stringResource(R.string.relation_remote_browse_failed)
    val selectedSourceHost = sources.find { it.id == selectedSourceId }?.host

    LaunchedEffect(browseError) {
        val err = browseError ?: return@LaunchedEffect
        val msg = when (err) {
            RemoteBrowseError.PATH_NOT_FOUND -> pathNotFoundStr
            RemoteBrowseError.TIMEOUT -> remoteTimeoutStr
            RemoteBrowseError.OTHER -> remoteBrowseFailedStr
        }
        snackbarHostState.showSnackbar(msg)
        viewModel.clearBrowseError()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(if (viewModel.relationId > 0) stringResource(R.string.relation_edit) else stringResource(R.string.relation_new)) },
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
        if (loadState == RelationEditLoadState.FAILED) {
            FullScreenError(
                message = stringResource(R.string.relation_edit_load_failed),
                onRetry = { viewModel.retryLoad() },
                modifier = Modifier.padding(padding)
            )
            return@Scaffold
        }
        if (loadState == RelationEditLoadState.LOADING) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { viewModel.setName(it) },
                label = { Text(stringResource(R.string.relation_friendly_name)) },
                placeholder = { Text(stringResource(R.string.relation_name_placeholder), color = extendedColors.placeholderGrey) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            ExposedDropdownMenuBox(
                expanded = sourceExpanded,
                onExpandedChange = { sourceExpanded = it }
            ) {
                OutlinedTextField(
                    value = sources.find { it.id == selectedSourceId }?.name ?: stringResource(R.string.relation_select_source),
                    onValueChange = {},
                    label = { Text(stringResource(R.string.relation_source)) },
                    readOnly = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                    trailingIcon = {
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = stringResource(R.string.relation_expand))
                    }
                )
                DropdownMenu(expanded = sourceExpanded, onDismissRequest = { sourceExpanded = false }) {
                    sources.forEach { source ->
                        DropdownMenuItem(
                            text = { Text("${source.name} (${source.protocol.name})") },
                            onClick = {
                                viewModel.selectSource(source.id)
                                sourceExpanded = false
                            }
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = remotePath,
                    onValueChange = { viewModel.setRemotePath(it) },
                    label = { Text(stringResource(R.string.relation_remote_dir)) },
                    placeholder = { Text(stringResource(R.string.relation_remote_dir_placeholder), color = extendedColors.placeholderGrey) },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                IconButton(onClick = {
                    showRemoteBrowser = true
                    viewModel.browseRemote()
                }) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = stringResource(R.string.relation_browse))
                }
            }

            OutlinedTextField(
                value = localBasePath,
                onValueChange = { viewModel.setLocalBasePath(it) },
                label = { Text(stringResource(R.string.relation_local_base_dir)) },
                placeholder = { Text(stringResource(R.string.relation_local_dir_placeholder), color = extendedColors.placeholderGrey) },
                supportingText = { Text(stringResource(R.string.relation_local_path_supporting)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            val storageInfo by viewModel.storageInfo.collectAsStateWithLifecycle()
            storageInfo?.let { info ->
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.relation_current_storage, info.typeLabel),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(R.string.relation_storage_path, info.pathLabel),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = { viewModel.testRemotePath() },
                    enabled = !isTesting && selectedSourceId > 0L && remotePath.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (isTesting) stringResource(R.string.relation_testing) else stringResource(R.string.relation_test_remote))
                }
                Button(
                    onClick = { viewModel.save() },
                    // AND-057: same saved-gate as the source editor — repeat taps
                    // after a successful save must not insert duplicates.
                    enabled = !isSaving && saveResult !is RelationSaveResult.Saved
                            && name.isNotBlank() && selectedSourceId > 0L
                            && remotePath.isNotBlank() && localBasePath.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (isSaving) stringResource(R.string.common_saving) else stringResource(R.string.common_save))
                }
            }

            if (viewModel.relationId > 0L) {
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
                    Text(stringResource(R.string.common_delete))
                }
            }

            testResult?.let { result ->
                FormResultText(
                    message = relationTestResultText(result, selectedSourceHost),
                    isSuccess = result is RelationTestResult.DirAccessible
                )
            }
            error?.let {
                FormResultText(message = it, isSuccess = false)
            }
            saveResult?.let { result ->
                FormResultText(
                    message = relationSaveResultText(result),
                    isSuccess = result is RelationSaveResult.Saved || result is RelationSaveResult.Deleted
                )
            }
        }
    }

    LaunchedEffect(saveResult) {
        // F09: 仅保存成功返回列表（类型驱动；与原字符串匹配行为等价——删除成功不导航）。
        if (saveResult is RelationSaveResult.Saved) {
            onNavigateToRelationList()
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.relation_delete_confirm_title)) },
            text = { Text(stringResource(R.string.relation_delete_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteRelation()
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

    // Remote browser dialog
    if (showRemoteBrowser) {
        AlertDialog(
            onDismissRequest = { showRemoteBrowser = false },
            title = { Text(stringResource(R.string.relation_browse_remote)) },
            text = {
                Column {
                    Text(stringResource(R.string.relation_current_path, currentRemotePath), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    if (currentRemotePath != "/") {
                        TextButton(enabled = !isBrowsing && !isTesting, onClick = { viewModel.navigateRemoteUp() }) {
                            Text(stringResource(R.string.relation_parent_dir))
                        }
                    }
                    if (credentialsRequired) {
                        FormResultText(stringResource(R.string.relation_credentials_recovery), isSuccess = false)
                    } else if (error != null) {
                        FormResultText(error!!, isSuccess = false)
                    } else if (isBrowsing) {
                        Box(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                            items(remoteDirs, key = { it.name }) { dir ->
                                TextButton(
                                    enabled = !isBrowsing && !isTesting && canSelectRemotePath,
                                    onClick = { viewModel.navigateRemoteInto(dir.name) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Filled.Folder,
                                            contentDescription = null,
                                            tint = extendedColors.folderAmber,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(dir.name)
                                    }
                                }
                            }
                            if (remoteDirs.isEmpty() && canSelectRemotePath) {
                                item {
                                    Text(
                                        stringResource(R.string.relation_no_subdirs),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)
                                    )
                                }
                            }
                            if (remoteDirs.isEmpty() && !canSelectRemotePath) {
                                item {
                                    FormResultText(stringResource(R.string.relation_remote_browse_failed), isSuccess = false)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = canSelectRemotePath && !isBrowsing && !isTesting, onClick = {
                    if (viewModel.selectRemotePath()) showRemoteBrowser = false
                }) {
                    Text(stringResource(R.string.relation_select_dir))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoteBrowser = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }


 }

/**
 * F09: 类型化结果 → 展示文案。只复用既有 string 资源；错误细节按 F02 模式
 * （SyncRelationListScreen.syncOutcomeErrorText）用结构化码经共享的
 * [syncErrorMessage] 码→资源映射重建（不重写 mapper）。AccessFailed 的码分组
 * 镜像 classifyRemoteError 三分类；[sourceHost] 覆盖异常内嵌 host 以保留主机
 * 信息（syncErrorMessage 的 host 参数即为「caller knows the source」场景设计）。
 */
@Composable
private fun relationTestResultText(result: RelationTestResult, sourceHost: String?): String =
    when (result) {
        is RelationTestResult.CredentialsRequired -> stringResource(R.string.relation_credentials_recovery)
        is RelationTestResult.DirAccessible -> stringResource(R.string.relation_dir_accessible)
        is RelationTestResult.PreconditionFailed -> when (result.reason) {
            RelationTestResult.PreconditionFailed.Reason.NO_SOURCE ->
                stringResource(R.string.relation_select_source_first)
            RelationTestResult.PreconditionFailed.Reason.NO_PATH ->
                stringResource(R.string.relation_input_remote_first)
        }
        is RelationTestResult.AccessFailed -> when (result.code) {
            SyncErrorCode.PATH_NOT_FOUND -> stringResource(R.string.relation_dir_not_exist)
            SyncErrorCode.TIMEOUT,
            SyncErrorCode.UNKNOWN_HOST,
            SyncErrorCode.NETWORK_UNAVAILABLE -> stringResource(R.string.relation_timeout_check_network)
            else -> stringResource(
                R.string.relation_access_failed,
                syncErrorMessage(
                    SyncException(result.code, SyncOperation.CONNECT),
                    LocalContext.current,
                    sourceHost
                )
            )
        }
    }

@Composable
private fun relationSaveResultText(result: RelationSaveResult): String = when (result) {
    is RelationSaveResult.Saved -> stringResource(R.string.relation_save_success)
    is RelationSaveResult.Deleted -> stringResource(R.string.relation_delete_success)
    is RelationSaveResult.DeleteFailed -> stringResource(R.string.relation_delete_failed)
}
