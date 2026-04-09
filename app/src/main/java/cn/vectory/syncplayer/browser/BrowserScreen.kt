package cn.vectory.syncplayer.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import cn.vectory.syncplayer.data.model.BrowseItem
import cn.vectory.syncplayer.ui.components.FileStatusIcon
import cn.vectory.syncplayer.ui.components.FolderGridItem
import cn.vectory.syncplayer.ui.components.FolderIcon
import cn.vectory.syncplayer.ui.components.Thumbnail
import cn.vectory.syncplayer.ui.components.VideoGridItem
import cn.vectory.syncplayer.ui.components.gridVerticalScrollbar
import cn.vectory.syncplayer.ui.components.verticalScrollbar
import cn.vectory.syncplayer.ui.util.buildMetadataLine
import cn.vectory.syncplayer.ui.util.isVideoFile
import kotlinx.coroutines.launch
import cn.vectory.syncplayer.debug.DebugLogger

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel = hiltViewModel(),
    currentFolder: String = "",
    onNavigateToPlayer: (videoItemId: Long, videoIds: List<Long>) -> Unit,
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
    val viewMode by viewModel.viewMode.collectAsState()
    val gridColumns by viewModel.gridColumns.collectAsState()

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
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
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
                        if (currentRelationId == null) {
                            IconButton(onClick = {
                                debugLogger.log("Click", "Create folder dialog opened")
                                showCreateFolderDialog = true
                            }) {
                                Icon(
                                    imageVector = Icons.Default.CreateNewFolder,
                                    contentDescription = "新建文件夹"
                                )
                            }
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
                                onDismissRequest = { showSortMenu = false },
                                modifier = Modifier.widthIn(max = 260.dp)
                            ) {
                                Text(
                                    "排序",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 4.dp)
                                )
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    SortOptionChip(
                                        selected = sortMode == BrowserViewModel.SortMode.FILENAME_ASC,
                                        onClick = { viewModel.setSortMode(BrowserViewModel.SortMode.FILENAME_ASC); debugLogger.log("Click","Sort: FILENAME_ASC"); showSortMenu = false },
                                        mainIcon = Icons.Default.SortByAlpha,
                                        ascending = true
                                    )
                                    SortOptionChip(
                                        selected = sortMode == BrowserViewModel.SortMode.FILENAME_DESC,
                                        onClick = { viewModel.setSortMode(BrowserViewModel.SortMode.FILENAME_DESC); debugLogger.log("Click","Sort: FILENAME_DESC"); showSortMenu = false },
                                        mainIcon = Icons.Default.SortByAlpha,
                                        ascending = false
                                    )
                                    SortOptionChip(
                                        selected = sortMode == BrowserViewModel.SortMode.DURATION_ASC,
                                        onClick = { viewModel.setSortMode(BrowserViewModel.SortMode.DURATION_ASC); debugLogger.log("Click","Sort: DURATION_ASC"); showSortMenu = false },
                                        mainIcon = Icons.Default.Schedule,
                                        ascending = true
                                    )
                                    SortOptionChip(
                                        selected = sortMode == BrowserViewModel.SortMode.DURATION_DESC,
                                        onClick = { viewModel.setSortMode(BrowserViewModel.SortMode.DURATION_DESC); debugLogger.log("Click","Sort: DURATION_DESC"); showSortMenu = false },
                                        mainIcon = Icons.Default.Schedule,
                                        ascending = false
                                    )
                                }
                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                Text(
                                    "筛选",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp)
                                )
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    IconOptionChip(
                                        selected = filter == BrowserViewModel.BrowseFilter.ALL,
                                        onClick = { viewModel.setFilter(BrowserViewModel.BrowseFilter.ALL); debugLogger.log("Click","Filter: ALL"); showSortMenu = false }
                                    ) { Icon(Icons.Default.FilterList, null, modifier = Modifier.size(20.dp)) }
                                    IconOptionChip(
                                        selected = filter == BrowserViewModel.BrowseFilter.VIDEO_ONLY,
                                        onClick = { viewModel.setFilter(BrowserViewModel.BrowseFilter.VIDEO_ONLY); debugLogger.log("Click","Filter: VIDEO_ONLY"); showSortMenu = false }
                                    ) { Icon(Icons.Default.VideoFile, null, modifier = Modifier.size(20.dp)) }
                                    IconOptionChip(
                                        selected = filter == BrowserViewModel.BrowseFilter.TRASHED_ONLY,
                                        onClick = { viewModel.setFilter(BrowserViewModel.BrowseFilter.TRASHED_ONLY); debugLogger.log("Click","Filter: TRASHED_ONLY"); showSortMenu = false }
                                    ) { Icon(Icons.Default.Delete, null, modifier = Modifier.size(20.dp)) }
                                }
                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                Text(
                                    "视图",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp)
                                )
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    IconOptionChip(
                                        selected = viewMode == BrowserViewModel.ViewMode.LIST,
                                        onClick = { viewModel.setViewMode(BrowserViewModel.ViewMode.LIST); showSortMenu = false }
                                    ) { Icon(Icons.AutoMirrored.Filled.ViewList, null, modifier = Modifier.size(20.dp)) }
                                    IconOptionChip(
                                        selected = viewMode == BrowserViewModel.ViewMode.GRID,
                                        onClick = { viewModel.setViewMode(BrowserViewModel.ViewMode.GRID); showSortMenu = false }
                                    ) { Icon(Icons.Default.Apps, null, modifier = Modifier.size(20.dp)) }
                                }
                                if (viewMode == BrowserViewModel.ViewMode.GRID) {
                                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        listOf(1, 2, 3, 4, 5).forEach { col ->
                                            FilterChip(
                                                selected = gridColumns == col,
                                                onClick = { viewModel.setGridColumns(col) },
                                                label = { Text("$col") },
                                                modifier = Modifier.height(28.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                    }
                )
            }
        ) { paddingValues ->
            if (viewMode == BrowserViewModel.ViewMode.GRID) {
                val gridState = rememberLazyGridState()
                LaunchedEffect(folder) {
                    val saved = viewModel.consumeScrollPosition(folder)
                    if (saved != null) {
                        gridState.scrollToItem(saved.first, saved.second)
                    }
                }
                LazyVerticalGrid(
                    columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(gridColumns),
                    state = gridState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .gridVerticalScrollbar(gridState),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = browseItems,
                        key = { it.relativePath }
                    ) { item ->
                        if (item.isDirectory) {
                            FolderGridItem(
                                folderName = item.name,
                                isSyncBaseDir = item.isSyncBaseDir,
                                onClick = {
                                    debugLogger.log("Click", "Open folder: ${item.relativePath}")
                                    viewModel.setFolder(item.relativePath)
                                },
                                onLongClick = {
                                    if (!item.isSyncBaseDir && !item.containsSyncBaseDir && !item.isInSyncBaseDir) {
                                        showFolderContextMenu = item
                                    }
                                }
                            )
                        } else {
                            VideoGridItem(
                                thumbnailPath = item.thumbnailPath,
                                fileName = item.name,
                                duration = item.duration,
                                width = item.width,
                                height = item.height,
                                playbackPosition = item.playbackPosition,
                                playbackDuration = item.playbackDuration,
                                isCompleted = item.isCompleted,
                                isTrashed = item.isTrashed,
                                isVideo = isVideoFile(item.name),
                                onClick = {
                                    debugLogger.log("Click", "Open file: ${item.name}")
                                    if (isVideoFile(item.name)) {
                                        viewModel.saveScrollPosition(folder, gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset)
                                        onNavigateToPlayer(item.videoItemId!!, viewModel.getFolderVideoIds())
                                    } else {
                                        scope.launch { snackbarHostState.showSnackbar("该文件无法播放") }
                                    }
                                },
                                onLongClick = { showContextMenu = item }
                            )
                        }
                    }

                    if (browseItems.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp),
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
            } else {
                val listState = rememberLazyListState()
                LaunchedEffect(folder) {
                    val saved = viewModel.consumeScrollPosition(folder)
                    if (saved != null) {
                        listState.scrollToItem(saved.first, saved.second)
                    }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .verticalScrollbar(listState)
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
                                onLongClick = {
                                    if (!item.isSyncBaseDir && !item.containsSyncBaseDir && !item.isInSyncBaseDir) {
                                        showFolderContextMenu = item
                                    }
                                }
                            )
                        } else {
                            FileItem(
                                item = item,
                                onClick = {
                                    debugLogger.log("Click", "Open file: ${item.name}")
                                    if (isVideoFile(item.name)) {
                                        viewModel.saveScrollPosition(folder, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
                                        onNavigateToPlayer(item.videoItemId!!, viewModel.getFolderVideoIds())
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
    }

    showContextMenu?.let { item ->
        Dialog(onDismissRequest = { showContextMenu = null }) {
            Card(
                modifier = Modifier.width(280.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (isVideoFile(item.name)) {
                        Spacer(Modifier.height(12.dp))
                        Thumbnail(
                            thumbnailPath = item.thumbnailPath,
                            isVideo = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                    }

                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
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
                        if (viewModel.isFileInBaseDir(item.relativePath)) {
                            TextButton(onClick = {
                                viewModel.toggleTrashed(item.videoItemId!!)
                                showContextMenu = null
                            }) {
                                Text(if (item.isTrashed) "从回收站恢复" else "放入回收站")
                            }
                        }
                        if (!viewModel.isFileInBaseDir(item.relativePath)) {
                            TextButton(onClick = {
                                viewModel.deleteFile(item.relativePath)
                                showContextMenu = null
                            }) {
                                Text("删除", color = MaterialTheme.colorScheme.error)
                            }
                        }
                        TextButton(onClick = { showContextMenu = null }) {
                            Text("取消")
                        }
                    }
                }
            }
        }
    }

    showFolderContextMenu?.let { item ->
        AlertDialog(
            onDismissRequest = { showFolderContextMenu = null },
            title = { Text(item.name) },
            confirmButton = {
                if (!item.isSyncBaseDir && !item.containsSyncBaseDir && !item.isInSyncBaseDir) {
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
                    isTrashed = item.isTrashed,
            playbackPosition = item.playbackPosition,
            playbackDuration = item.playbackDuration
        )
    }
}

@Composable
private fun IconOptionChip(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit
) {
    Surface(
        modifier = modifier.size(40.dp),
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        onClick = onClick
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            icon()
        }
    }
}

@Composable
private fun SortOptionChip(
    selected: Boolean,
    onClick: () -> Unit,
    mainIcon: ImageVector,
    ascending: Boolean,
    modifier: Modifier = Modifier
) {
    IconOptionChip(selected = selected, onClick = onClick, modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(mainIcon, null, modifier = Modifier.size(18.dp))
            Icon(
                if (ascending) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                null,
                modifier = Modifier.size(12.dp)
            )
        }
    }
}
