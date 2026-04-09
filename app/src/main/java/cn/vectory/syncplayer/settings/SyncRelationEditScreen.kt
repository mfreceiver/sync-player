package cn.vectory.syncplayer.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import cn.vectory.syncplayer.ui.theme.LocalExtendedColors
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
    val localDirs by viewModel.localDirectories.collectAsStateWithLifecycle()
    val currentLocalPath by viewModel.currentLocalBrowsePath.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val isSaving by viewModel.isSaving.collectAsStateWithLifecycle()
    val saveResult by viewModel.saveResult.collectAsStateWithLifecycle()

    var showRemoteBrowser by remember { mutableStateOf(false) }
    var showLocalBrowser by remember { mutableStateOf(false) }
    var sourceExpanded by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var isNavigatingBack by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (viewModel.relationId > 0) "编辑同步关系" else "新增同步关系") },
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
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { viewModel.setName(it) },
                label = { Text("友好名称") },
                placeholder = { Text("电影同步", color = extendedColors.placeholderGrey) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Box {
                OutlinedTextField(
                    value = sources.find { it.id == selectedSourceId }?.name ?: "请选择数据源",
                    onValueChange = {},
                    label = { Text("数据源") },
                    readOnly = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { sourceExpanded = true },
                    trailingIcon = {
                        IconButton(onClick = { sourceExpanded = true }) {
                            Icon(Icons.Filled.ArrowDropDown, contentDescription = "展开")
                        }
                    },
                    interactionSource = remember { MutableInteractionSource() }
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
                    label = { Text("远端目录") },
                    placeholder = { Text("/share/电影", color = extendedColors.placeholderGrey) },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                IconButton(onClick = {
                    showRemoteBrowser = true
                    viewModel.browseRemote()
                }) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = "浏览")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = localBasePath,
                    onValueChange = {},
                    label = { Text("本地基目录") },
                    placeholder = { Text("动作片", color = extendedColors.placeholderGrey) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    readOnly = true
                )
                IconButton(onClick = {
                    showLocalBrowser = true
                    viewModel.browseLocalDirectories()
                }) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = "选择本地目录")
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (viewModel.relationId > 0L) {
                    OutlinedButton(
                        onClick = { showDeleteDialog = true },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("删除")
                    }
                }
                Button(
                    onClick = { viewModel.save() },
                    enabled = !isSaving && name.isNotBlank() && selectedSourceId > 0L
                            && remotePath.isNotBlank() && localBasePath.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (isSaving) "保存中…" else "保存")
                }
            }

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            saveResult?.let {
                Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    LaunchedEffect(saveResult) {
        if (saveResult?.contains("成功") == true) {
            onNavigateToRelationList()
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除同步关系") },
            text = { Text("确定要删除该同步关系吗？已同步的本地文件不会被删除。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteRelation()
                    showDeleteDialog = false
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // Remote browser dialog
    if (showRemoteBrowser) {
        AlertDialog(
            onDismissRequest = { showRemoteBrowser = false },
            title = { Text("浏览远端目录") },
            text = {
                Column {
                    Text("当前路径: $currentRemotePath", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    if (currentRemotePath != "/") {
                        TextButton(onClick = { viewModel.navigateRemoteUp() }) {
                            Text(".. (上级目录)")
                        }
                    }
                    LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                        items(remoteDirs) { dir ->
                            TextButton(
                                onClick = { viewModel.navigateRemoteInto(dir.name) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("📁 ${dir.name}")
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.selectRemotePath()
                    showRemoteBrowser = false
                }) {
                    Text("选择此目录")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoteBrowser = false }) {
                    Text("取消")
                }
            }
        )
    }

    // Local browser dialog (multi-level)
    if (showLocalBrowser) {
        var showCreateFolderDialog by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showLocalBrowser = false },
            title = { Text("选择本地基目录") },
            text = {
                Column {
                    Text(
                        "当前路径: /${if (currentLocalPath.isEmpty()) "" else currentLocalPath}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))

                    if (currentLocalPath.isNotEmpty()) {
                        TextButton(onClick = { viewModel.navigateLocalUp() }) {
                            Text(".. (上级目录)")
                        }
                    }

                    LazyColumn(modifier = Modifier.heightIn(max = 250.dp)) {
                        items(localDirs) { dirName ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.navigateLocalInto(dirName) }
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Filled.Folder,
                                    contentDescription = null,
                                    tint = extendedColors.folderAmber,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(dirName)
                            }
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start
                    ) {
                        IconButton(onClick = { showCreateFolderDialog = true }) {
                            Icon(
                                Icons.Filled.CreateNewFolder,
                                contentDescription = "新建文件夹",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.confirmLocalSelection()
                    showLocalBrowser = false
                }) {
                    Text("选择此目录")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLocalBrowser = false }) {
                    Text("取消")
                }
            }
        )

        if (showCreateFolderDialog) {
            var newFolderName by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showCreateFolderDialog = false },
                title = { Text("新建文件夹") },
                text = {
                    OutlinedTextField(
                        value = newFolderName,
                        onValueChange = { newFolderName = it },
                        label = { Text("文件夹名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (newFolderName.isNotBlank()) {
                                viewModel.createLocalFolder(newFolderName.trim())
                            }
                            showCreateFolderDialog = false
                        },
                        enabled = newFolderName.isNotBlank()
                    ) {
                        Text("创建")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showCreateFolderDialog = false }) {
                        Text("取消")
                    }
                }
            )
        }
    }
}
