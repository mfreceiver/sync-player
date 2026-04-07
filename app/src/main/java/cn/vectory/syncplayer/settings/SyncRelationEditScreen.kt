package cn.vectory.syncplayer.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val PlaceholderColor = Color(0xFF9E9E9E)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncRelationEditScreen(
    onNavigateBack: () -> Unit,
    viewModel: SyncRelationEditViewModel = hiltViewModel()
) {
    val name by viewModel.name.collectAsStateWithLifecycle()
    val sources by viewModel.availableSources.collectAsStateWithLifecycle()
    val selectedSourceId by viewModel.selectedSourceId.collectAsStateWithLifecycle()
    val remotePath by viewModel.remotePath.collectAsStateWithLifecycle()
    val localBasePath by viewModel.localBasePath.collectAsStateWithLifecycle()
    val remoteDirs by viewModel.remoteDirectories.collectAsStateWithLifecycle()
    val currentPath by viewModel.currentRemoteBrowsePath.collectAsStateWithLifecycle()
    val localDirs by viewModel.localDirectories.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val isSaving by viewModel.isSaving.collectAsStateWithLifecycle()
    val saveResult by viewModel.saveResult.collectAsStateWithLifecycle()

    var showRemoteBrowser by remember { mutableStateOf(false) }
    var showLocalBrowser by remember { mutableStateOf(false) }
    var sourceExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (viewModel.relationId > 0) "编辑同步关系" else "新增同步关系") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
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
                placeholder = { Text("电影同步", color = PlaceholderColor) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Box {
                OutlinedTextField(
                    value = sources.find { it.id == selectedSourceId }?.name ?: "请选择同步源",
                    onValueChange = {},
                    label = { Text("同步源") },
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = { sourceExpanded = true }) {
                            Icon(Icons.Filled.ArrowDropDown, contentDescription = "展开")
                        }
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
                    label = { Text("远端目录") },
                    placeholder = { Text("/share/电影", color = PlaceholderColor) },
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
                    onValueChange = { viewModel.setLocalBasePath(it) },
                    label = { Text("本地基目录") },
                    placeholder = { Text("动作片", color = PlaceholderColor) },
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

            Text(
                "基目录不能互相嵌套",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
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

    if (showRemoteBrowser) {
        AlertDialog(
            onDismissRequest = { showRemoteBrowser = false },
            title = { Text("浏览远端目录") },
            text = {
                Column {
                    Text("当前路径: $currentPath", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    if (currentPath != "/") {
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

    if (showLocalBrowser) {
        AlertDialog(
            onDismissRequest = { showLocalBrowser = false },
            title = { Text("选择本地基目录") },
            text = {
                Column {
                    if (localDirs.isEmpty()) {
                        Text(
                            "暂无本地目录，请在下方输入新目录名",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(modifier = Modifier.heightIn(max = 250.dp)) {
                        items(localDirs) { dirName ->
                            TextButton(
                                onClick = {
                                    viewModel.selectLocalDirectory(dirName)
                                    showLocalBrowser = false
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("📁 $dirName")
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    var customDirName by remember { mutableStateOf("") }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = customDirName,
                            onValueChange = { customDirName = it },
                            placeholder = { Text("输入新目录名", color = PlaceholderColor) },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        TextButton(
                            onClick = {
                                if (customDirName.isNotBlank()) {
                                    viewModel.selectLocalDirectory(customDirName.trim())
                                    showLocalBrowser = false
                                }
                            },
                            enabled = customDirName.isNotBlank()
                        ) {
                            Text("确定")
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showLocalBrowser = false }) {
                    Text("取消")
                }
            }
        )
    }
}
