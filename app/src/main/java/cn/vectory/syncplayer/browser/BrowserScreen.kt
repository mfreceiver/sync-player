package cn.vectory.syncplayer.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import cn.vectory.syncplayer.data.model.BrowseItem
import cn.vectory.syncplayer.ui.components.FileStatusIcon
import cn.vectory.syncplayer.ui.components.FolderIcon
import cn.vectory.syncplayer.ui.components.Thumbnail
import cn.vectory.syncplayer.ui.util.buildMetadataLine
import cn.vectory.syncplayer.ui.util.isVideoFile
import kotlinx.coroutines.launch
import cn.vectory.syncplayer.debug.DebugLogger

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel = hiltViewModel(),
    currentFolder: String = "",
    onNavigateToPlayer: (videoItemId: Long) -> Unit,
    onNavigateToSearch: () -> Unit,
    onNavigateToSyncSourceList: () -> Unit,
    onNavigateToSyncRelationList: () -> Unit,
    onNavigateToPlaybackSettings: () -> Unit,
    onNavigateToAbout: () -> Unit,
    onNavigateToSyncPreview: (relationId: Long) -> Unit,
    debugLogger: DebugLogger,
) {
    val browseItems by viewModel.browseItems.collectAsState()
    val hasConfig by viewModel.hasConfig.collectAsState()
    val sortMode by viewModel.sortMode.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val folder by viewModel.currentFolder.collectAsState()
    val error by viewModel.error.collectAsState()
    val currentRelationId by viewModel.currentRelationId.collectAsState()

    var showContextMenu by remember { mutableStateOf<BrowseItem?>(null) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var showFolderContextMenu by remember { mutableStateOf<BrowseItem?>(null) }
    val context = LocalContext.current

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(currentFolder) {
        if (viewModel.currentFolder.value.isEmpty()) {
            viewModel.setFolder(currentFolder)
        }
    }

    LaunchedEffect(Unit) {
        debugLogger.log("Compose", "BrowserScreen composed")
    }

    BackHandler(enabled = folder.isNotEmpty()) {
        debugLogger.log("Nav", "Navigate up from folder: $folder")
        viewModel.navigateUp()
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.fillMaxWidth(0.6f)) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Sync Player",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp)
                )
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Dns, contentDescription = null) },
                    label = { Text("数据源") },
                    selected = false,
                    onClick = {
                        scope.launch {
                            drawerState.close()
                            onNavigateToSyncSourceList()
                        }
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Sync, contentDescription = null) },
                    label = { Text("同步关系") },
                    selected = false,
                    onClick = {
                        scope.launch {
                            drawerState.close()
                            onNavigateToSyncRelationList()
                        }
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.PlayCircleOutline, contentDescription = null) },
                    label = { Text("设置") },
                    selected = false,
                    onClick = {
                        scope.launch {
                            drawerState.close()
                            onNavigateToPlaybackSettings()
                        }
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Info, contentDescription = null) },
                    label = { Text("关于") },
                    selected = false,
                    onClick = {
                        scope.launch {
                            drawerState.close()
                            onNavigateToAbout()
                        }
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
            }
        }
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                    TopAppBar(
                        navigationIcon = {
                        IconButton(onClick = {
                            debugLogger.log("Drawer", "Opening drawer, folder=$folder")
                            scope.launch { drawerState.open() }
                        }) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = "菜单"
                            )
                        }
                    },
                    title = {
                        val displayPath = folder.substringAfterLast('/')
                        if (displayPath.isNotEmpty()) {
                            Text(
                                text = displayPath,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            debugLogger.log("Click", "Search button clicked")
                            onNavigateToSearch()
                        }) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "搜索"
                            )
                        }
                        if (currentRelationId != null) {
                            IconButton(onClick = {
                                debugLogger.log("Click", "Sync button clicked, relationId=$currentRelationId")
                                onNavigateToSyncPreview(currentRelationId!!)
                            }) {
                                Icon(
                                    imageVector = Icons.Default.Sync,
                                    contentDescription = "同步"
                                )
                            }
                        }
                        IconButton(onClick = {
                            debugLogger.log("Click", "Create folder dialog opened")
                            showCreateFolderDialog = true
                        }) {
                            Icon(
                                imageVector = Icons.Default.CreateNewFolder,
                                contentDescription = "新建文件夹"
                            )
                        }
                        Box {
                            IconButton(onClick = {
                                debugLogger.log("Click", "Sort menu opened")
                                showSortMenu = true
                            }) {
                                    Icon(
                                    imageVector = Icons.Default.SwapVert,
                                    contentDescription = "排序筛选"
                                )
                            }
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false }
                            ) {
                                Text(
                                    "排序",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                                )
                                DropdownMenuItem(
                                    text = { Text("按文件名 ↑") },
                                    onClick = { viewModel.setSortMode(BrowserViewModel.SortMode.FILENAME_ASC); debugLogger.log("Click","Sort: FILENAME_ASC"); showSortMenu = false },
                                    trailingIcon = { if (sortMode == BrowserViewModel.SortMode.FILENAME_ASC) Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp)) }
                                )
                                DropdownMenuItem(
                                    text = { Text("按文件名 ↓") },
                                    onClick = { viewModel.setSortMode(BrowserViewModel.SortMode.FILENAME_DESC); debugLogger.log("Click","Sort: FILENAME_DESC"); showSortMenu = false },
                                    trailingIcon = { if (sortMode == BrowserViewModel.SortMode.FILENAME_DESC) Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp)) }
                                )
                                DropdownMenuItem(
                                    text = { Text("按时长 ↑") },
                                    onClick = { viewModel.setSortMode(BrowserViewModel.SortMode.DURATION_ASC); debugLogger.log("Click","Sort: DURATION_ASC"); showSortMenu = false },
                                    trailingIcon = { if (sortMode == BrowserViewModel.SortMode.DURATION_ASC) Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp)) }
                                )
                                DropdownMenuItem(
                                    text = { Text("按时长 ↓") },
                                    onClick = { viewModel.setSortMode(BrowserViewModel.SortMode.DURATION_DESC); debugLogger.log("Click","Sort: DURATION_DESC"); showSortMenu = false },
                                    trailingIcon = { if (sortMode == BrowserViewModel.SortMode.DURATION_DESC) Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp)) }
                                )
                                HorizontalDivider()
                                Text(
                                    "筛选",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                                )
                                DropdownMenuItem(
                                    text = { Text("全部") },
                                    onClick = { viewModel.setFilter(BrowserViewModel.BrowseFilter.ALL); debugLogger.log("Click","Filter: ALL"); showSortMenu = false },
                                    trailingIcon = { if (filter == BrowserViewModel.BrowseFilter.ALL) Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp)) }
                                )
                                DropdownMenuItem(
                                    text = { Text("仅视频") },
                                    onClick = { viewModel.setFilter(BrowserViewModel.BrowseFilter.VIDEO_ONLY); debugLogger.log("Click","Filter: VIDEO_ONLY"); showSortMenu = false },
                                    trailingIcon = { if (filter == BrowserViewModel.BrowseFilter.VIDEO_ONLY) Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp)) }
                                )
                                DropdownMenuItem(
                                    text = { Text("仅预删除") },
                                    onClick = { viewModel.setFilter(BrowserViewModel.BrowseFilter.FAVORITES_ONLY); debugLogger.log("Click","Filter: FAVORITES_ONLY"); showSortMenu = false },
                                    trailingIcon = { if (filter == BrowserViewModel.BrowseFilter.FAVORITES_ONLY) Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp)) }
                                )
                            }
                        }
                    }
                )
            }
        ) { paddingValues ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                items(
                    items = browseItems,
                    key = { it.relativePath }
                ) { item ->
                    if (item.isDirectory) {
                        FolderItem(
                            item = item,
                            onClick = {
                                debugLogger.log("Click", "Open folder: ${item.relativePath}")
                                viewModel.setFolder(item.relativePath)
                            },
                            onLongClick = { showFolderContextMenu = item }
                        )
                    } else {
                        FileItem(
                            item = item,
                            onClick = {
                                debugLogger.log("Click", "Open file: ${item.name}")
                                if (isVideoFile(item.name)) {
                                    viewModel.setFolderVideoIdsForNavigation(viewModel.getFolderVideoIds())
                                    onNavigateToPlayer(item.videoItemId!!)
                                } else {
                                    scope.launch { snackbarHostState.showSnackbar("该文件无法播放") }
                                }
                            },
                            onLongClick = { showContextMenu = item }
                        )
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 72.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                }

                if (browseItems.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillParentMaxHeight()
                                .padding(vertical = 64.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FolderOpen,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.size(48.dp)
                                )
                                Text(
                                    text = "当前目录为空",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    showContextMenu?.let { item ->
        AlertDialog(
            onDismissRequest = { showContextMenu = null },
            title = { Text(item.name) },
            text = { Text("选择操作") },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        val itemId = item.videoItemId!!
                        showContextMenu = null
                        scope.launch {
                            val intent = viewModel.buildShareIntent(itemId)
                            if (intent != null) {
                                context.startActivity(intent)
                            } else {
                                scope.launch { snackbarHostState.showSnackbar("分享失败：文件不存在或无法访问") }
                            }
                        }
                    }) {
                        Text("分享")
                    }
                    TextButton(onClick = {
                        viewModel.toggleFavorite(item.videoItemId!!)
                        showContextMenu = null
                    }) {
                        Text(if (item.isFavorite) "取消预删除" else "预删除")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showContextMenu = null }) {
                    Text("取消")
                }
            }
        )
    }

    showFolderContextMenu?.let { item ->
        AlertDialog(
            onDismissRequest = { showFolderContextMenu = null },
            title = { Text(item.name) },
            confirmButton = {
                if (!item.isSyncBaseDir && !item.containsSyncBaseDir) {
                    TextButton(
                        onClick = {
                            viewModel.deleteFolder(item.relativePath)
                            showFolderContextMenu = null
                        }
                    ) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showFolderContextMenu = null }) {
                    Text("取消")
                }
            }
        )
    }

    if (showCreateFolderDialog) {
        var folderName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            title = { Text("新建文件夹") },
            text = {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text("文件夹名称") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (folderName.isNotBlank()) {
                            viewModel.createFolder(folder, folderName.trim())
                            showCreateFolderDialog = false
                        }
                    },
                    enabled = folderName.isNotBlank()
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

    error?.let {
        LaunchedEffect(it) {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderItem(
    item: BrowseItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FolderIcon(
            isSyncBaseDir = item.isSyncBaseDir,
            modifier = Modifier.size(40.dp)
        )

        Spacer(Modifier.width(12.dp))

        Text(
            text = item.name,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileItem(
    item: BrowseItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Thumbnail(
            thumbnailPath = item.thumbnailPath,
            isVideo = isVideoFile(item.name),
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(4.dp))
        )

        Spacer(Modifier.width(12.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = item.name.substringBeforeLast('.'),
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            val metadata = buildMetadataLine(
                duration = item.duration,
                width = item.width,
                height = item.height,
                bitrate = item.bitrate,
                extension = item.name.substringAfterLast('.', "").lowercase()
            )
            if (metadata.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = metadata,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        FileStatusIcon(
            isCompleted = item.isCompleted,
            isFavorite = item.isFavorite,
            playbackPosition = item.playbackPosition,
            playbackDuration = item.playbackDuration
        )
    }
}
