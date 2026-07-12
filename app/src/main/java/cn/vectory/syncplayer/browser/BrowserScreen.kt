package cn.vectory.syncplayer.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.FilterListOff
import androidx.compose.material.icons.filled.Block
import cn.vectory.syncplayer.ui.components.StarRatingInput
import cn.vectory.syncplayer.ui.components.StarRatingDisplay
import cn.vectory.syncplayer.ui.components.DirectoryTreeDialog
import cn.vectory.syncplayer.ui.components.DirectoryNode
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import cn.vectory.syncplayer.data.model.BrowseItem
import cn.vectory.syncplayer.ui.components.FileStatusIcon
import cn.vectory.syncplayer.ui.components.FolderGridItem
import cn.vectory.syncplayer.ui.components.FolderIcon
import cn.vectory.syncplayer.ui.components.FileContextMenuDialog
import cn.vectory.syncplayer.ui.components.FolderContextMenuDialog
import cn.vectory.syncplayer.ui.components.Thumbnail
import cn.vectory.syncplayer.ui.components.VideoGridItem
import cn.vectory.syncplayer.ui.components.gridVerticalScrollbar
import cn.vectory.syncplayer.ui.components.verticalScrollbar
import cn.vectory.syncplayer.ui.util.buildMetadataLineWithoutExtension
import cn.vectory.syncplayer.ui.util.buildImageMetadataLine
import cn.vectory.syncplayer.ui.util.isVideoFile
import cn.vectory.syncplayer.ui.util.isImageFile
import cn.vectory.syncplayer.R
import kotlinx.coroutines.launch
import java.lang.Math

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel = hiltViewModel(),
    currentFolder: String = "",
    onNavigateToPlayer: (videoItemId: Long, videoIds: List<Long>) -> Unit,
    onNavigateToImageViewer: (imageItemId: Long, imageIds: List<Long>) -> Unit = { _, _ -> },
    onNavigateToSearch: () -> Unit,
    onNavigateToPlaybackSettings: () -> Unit,
    onNavigateToSyncPreview: (relationId: Long) -> Unit,
) {
    val browseItems by viewModel.browseItems.collectAsStateWithLifecycle()
    val browseItemsFolder by viewModel.browseItemsFolder.collectAsStateWithLifecycle()
    val hiddenByFilterCount by viewModel.hiddenByFilterCount.collectAsStateWithLifecycle()
    val hasConfig by viewModel.hasConfig.collectAsStateWithLifecycle()
    val sortMode by viewModel.sortMode.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    // Additional UI state expected by the advanced sorting/filtering UI
    val browseMode by viewModel.browseMode.collectAsStateWithLifecycle()
    val starFilterValue by viewModel.starFilterValue.collectAsStateWithLifecycle()
    val starFilterMode by viewModel.starFilterMode.collectAsStateWithLifecycle()
    val folder by viewModel.currentFolder.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val currentRelationId by viewModel.currentRelationId.collectAsStateWithLifecycle()
    val viewMode by viewModel.viewMode.collectAsStateWithLifecycle()
    val gridColumns by viewModel.gridColumns.collectAsStateWithLifecycle()

    var showContextMenu by remember { mutableStateOf<BrowseItem?>(null) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showFolderContextMenu by remember { mutableStateOf<BrowseItem?>(null) }
    var showDirectoryTree by remember { mutableStateOf(false) }
    var showAdvancedFilter by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(currentFolder) {
        if (viewModel.currentFolder.value.isEmpty()) {
            viewModel.setFolder(currentFolder)
        }
    }

    // Support switching browse modes via back button as well
    val backEnabled = !showSortMenu && showContextMenu == null
            && showFolderContextMenu == null && !showDirectoryTree && !showAdvancedFilter
            && (folder.isNotEmpty() || browseMode == BrowserViewModel.BrowseMode.FILE)
    BackHandler(enabled = backEnabled) {
        if (browseMode == BrowserViewModel.BrowseMode.FILE) {
            viewModel.setBrowseMode(BrowserViewModel.BrowseMode.DIRECTORY)
        } else {
            viewModel.navigateUp()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
                TopAppBar(
                    navigationIcon = {
                    if (folder.isEmpty()) {
                        IconButton(onClick = {
                            onNavigateToPlaybackSettings()
                        }) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = stringResource(R.string.browser_settings)
                            )
                        }
                    } else {
                        IconButton(onClick = {
                            viewModel.setFolder("")
                        }) {
                            Icon(
                                imageVector = Icons.Default.Home,
                                contentDescription = stringResource(R.string.browser_root_dir)
                            )
                        }
                    }
                },
                title = {
                    val displayPath = folder.substringAfterLast('/')
                    if (displayPath.isNotEmpty()) {
                        Text(
                            text = displayPath,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable { showDirectoryTree = true }
                        )
                    } else {
                        Text(
                            text = "Sync Player",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable { showDirectoryTree = true }
                        )
                    }
                },
                    actions = {
                        if (currentRelationId != null) {
                            IconButton(onClick = {
                                onNavigateToSyncPreview(currentRelationId!!)
                            }) {
                                Icon(
                                    imageVector = Icons.Default.Sync,
                                    contentDescription = stringResource(R.string.browser_sync)
                                )
                            }
                        }
                        IconButton(onClick = {
                            onNavigateToSearch()
                        }) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = stringResource(R.string.browser_search)
                            )
                        }
                        Box {
                            val isAdvancedFilterActive = starFilterMode != BrowserViewModel.StarFilterMode.OFF
                            if (isAdvancedFilterActive) {
                                Box(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .background(
                                            MaterialTheme.colorScheme.secondaryContainer,
                                            RoundedCornerShape(8.dp)
                                        )
                                )
                            }
                            IconButton(onClick = {
                                showSortMenu = true
                            }) {
                                Icon(
                                    imageVector = Icons.Default.FilterList,
                                    contentDescription = stringResource(R.string.browser_sort_filter),
                                    tint = if (isAdvancedFilterActive) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                            }
                            SortFilterDropdown(
                                expanded = showSortMenu,
                                onDismiss = { showSortMenu = false },
                                sortMode = sortMode,
                                onSortChange = { viewModel.setSortMode(it) },
                                filter = filter,
                                onFilterChange = { viewModel.setFilter(it) },
                                browseMode = browseMode,
                                onBrowseModeChange = { viewModel.setBrowseMode(it) },
                                viewMode = viewMode,
                                onViewModeChange = { viewModel.setViewMode(it) },
                                gridColumns = gridColumns,
                                onGridColumnsChange = { viewModel.setGridColumns(it) },
                                starFilterMode = starFilterMode,
                                onClearStarFilter = {
                                    viewModel.setStarFilterMode(BrowserViewModel.StarFilterMode.OFF)
                                    viewModel.setStarFilterValue(0)
                                },
                                onAdvancedFilter = { showAdvancedFilter = true },
                                folder = folder
                            )
                        }

                    }
                )
            }
        ) { paddingValues ->
            if (viewMode == BrowserViewModel.ViewMode.GRID) {
                val gridState = rememberLazyGridState()
                BrowseScrollTracker(
                    folder = folder,
                    browseItemsFolder = browseItemsFolder,
                    browseItemsNotEmpty = browseItems.isNotEmpty(),
                    currentScrollPosition = { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset },
                    saveScrollPosition = { f, i, o -> viewModel.saveScrollPosition(f, i, o) },
                    getSavedScrollPosition = { f -> viewModel.getScrollPosition(f) },
                    scrollToPosition = { i, o -> gridState.scrollToItem(i, o) }
                )
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
                                    viewModel.setFolder(item.relativePath)
                                },
                                onLongClick = { showFolderContextMenu = item },
                                thumbnailPaths = item.thumbnailPaths,
                                gridDensity = when {
                                    gridColumns <= 2 -> 3
                                    else -> 2
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
                                isImage = isImageFile(item.name),
                                star = item.star,
                                onClick = {
                                    when {
                                        isVideoFile(item.name) -> {
                                            viewModel.saveScrollPosition(folder, gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset)
                                            val id = item.videoItemId
                                            if (id != null) {
                                                onNavigateToPlayer(id, viewModel.getFolderVideoIds())
                                            } else {
                                                scope.launch {
                                                    val newId = viewModel.ensureVideoRecord(item)
                                                    if (newId > 0) onNavigateToPlayer(newId, viewModel.getFolderVideoIds(item, newId))
                                                }
                                            }
                                        }
                                        isImageFile(item.name) -> {
                                            viewModel.saveScrollPosition(folder, gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset)
                                            val id = item.videoItemId
                                            if (id != null) {
                                                onNavigateToImageViewer(id, viewModel.getFolderImageIds())
                                            } else {
                                                scope.launch {
                                                    val newId = viewModel.ensureVideoRecord(item)
                                                    if (newId > 0) onNavigateToImageViewer(newId, viewModel.getFolderImageIds(item, newId))
                                                }
                                            }
                                        }
                                        else -> {
                                            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.browser_cannot_play)) }
                                        }
                                    }
                                },
                                onLongClick = { showContextMenu = item }
                            )
                        }
                    }

                    if (browseItems.isNotEmpty() && filter != BrowserViewModel.BrowseFilter.ALL && hiddenByFilterCount > 0) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            FilterCountFooter(hiddenByFilterCount)
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
                                EmptyBrowseContent(filter, hiddenByFilterCount)
                            }
                        }
                    }
                }
            } else if (viewMode == BrowserViewModel.ViewMode.STAGGERED_GRID) {
                val staggeredState = rememberLazyStaggeredGridState()
                BrowseScrollTracker(
                    folder = folder,
                    browseItemsFolder = browseItemsFolder,
                    browseItemsNotEmpty = browseItems.isNotEmpty(),
                    currentScrollPosition = { staggeredState.firstVisibleItemIndex to staggeredState.firstVisibleItemScrollOffset },
                    saveScrollPosition = { f, i, o -> viewModel.saveScrollPosition(f, i, o) },
                    getSavedScrollPosition = { f -> viewModel.getScrollPosition(f) },
                    scrollToPosition = { i, o -> staggeredState.scrollToItem(i, o) }
                )
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(gridColumns),
                    state = staggeredState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalItemSpacing = 8.dp
                ) {
                    items(
                        items = browseItems,
                        key = { it.relativePath }
                    ) { item ->
                        if (item.isDirectory) {
                            FolderGridItem(
                                folderName = item.name,
                                isSyncBaseDir = item.isSyncBaseDir,
                                onClick = { viewModel.setFolder(item.relativePath) },
                                onLongClick = { showFolderContextMenu = item },
                                thumbnailPaths = item.thumbnailPaths,
                                gridDensity = when { gridColumns <= 2 -> 3 else -> 2 }
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
                                isImage = isImageFile(item.name),
                                star = item.star,
                                staggeredMode = true,
                                onClick = {
                                    when {
                                        isVideoFile(item.name) -> {
                                            val id = item.videoItemId
                                            if (id != null) {
                                                onNavigateToPlayer(id, viewModel.getFolderVideoIds())
                                            } else {
                                                scope.launch {
                                                    val newId = viewModel.ensureVideoRecord(item)
                                                    if (newId > 0) onNavigateToPlayer(newId, viewModel.getFolderVideoIds(item, newId))
                                                }
                                            }
                                        }
                                        isImageFile(item.name) -> {
                                            val id = item.videoItemId
                                            if (id != null) {
                                                onNavigateToImageViewer(id, viewModel.getFolderImageIds())
                                            } else {
                                                scope.launch {
                                                    val newId = viewModel.ensureVideoRecord(item)
                                                    if (newId > 0) onNavigateToImageViewer(newId, viewModel.getFolderImageIds(item, newId))
                                                }
                                            }
                                        }
                                        else -> {
                                            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.browser_cannot_play)) }
                                        }
                                    }
                                },
                                onLongClick = { showContextMenu = item }
                            )
                        }
                    }
                }
            } else {
                val listState = rememberLazyListState()
                BrowseScrollTracker(
                    folder = folder,
                    browseItemsFolder = browseItemsFolder,
                    browseItemsNotEmpty = browseItems.isNotEmpty(),
                    currentScrollPosition = { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset },
                    saveScrollPosition = { f, i, o -> viewModel.saveScrollPosition(f, i, o) },
                    getSavedScrollPosition = { f -> viewModel.getScrollPosition(f) },
                    scrollToPosition = { i, o -> listState.scrollToItem(i, o) }
                )
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
                                    viewModel.setFolder(item.relativePath)
                                },
                            onLongClick = { showFolderContextMenu = item }
                            )
                        } else {
                            FileItem(
                                item = item,
                                onClick = {
                                    when {
                                        isVideoFile(item.name) -> {
                                            viewModel.saveScrollPosition(folder, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
                                            val id = item.videoItemId
                                            if (id != null) {
                                                onNavigateToPlayer(id, viewModel.getFolderVideoIds())
                                            } else {
                                                scope.launch {
                                                    val newId = viewModel.ensureVideoRecord(item)
                                                    if (newId > 0) onNavigateToPlayer(newId, viewModel.getFolderVideoIds(item, newId))
                                                }
                                            }
                                        }
                                        isImageFile(item.name) -> {
                                            viewModel.saveScrollPosition(folder, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
                                            val id = item.videoItemId
                                            if (id != null) {
                                                onNavigateToImageViewer(id, viewModel.getFolderImageIds())
                                            } else {
                                                scope.launch {
                                                    val newId = viewModel.ensureVideoRecord(item)
                                                    if (newId > 0) onNavigateToImageViewer(newId, viewModel.getFolderImageIds(item, newId))
                                                }
                                            }
                                        }
                                        else -> {
                                            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.browser_cannot_play)) }
                                        }
                                    }
                                },
                                onLongClick = { showContextMenu = item }
                            )
                        }
                    }

                    if (browseItems.isNotEmpty() && filter != BrowserViewModel.BrowseFilter.ALL && hiddenByFilterCount > 0) {
                        item {
                            FilterCountFooter(hiddenByFilterCount)
                        }
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
                                EmptyBrowseContent(filter, hiddenByFilterCount)
                            }
                        }
                    }
            }
        }
    }

    showContextMenu?.let { item ->
        FileContextMenuDialog(
            item = item,
            actions = viewModel,
            onDismiss = { showContextMenu = null },
            scope = scope,
            snackbarHostState = snackbarHostState,
            context = context
        )
    }

    showFolderContextMenu?.let { item ->
        FolderContextMenuDialog(
            item = item,
            actions = viewModel,
            onDismiss = { showFolderContextMenu = null }
        )
    }

    if (showAdvancedFilter) {
        AlertDialog(
            onDismissRequest = { showAdvancedFilter = false },
            title = { Text(stringResource(R.string.browser_advanced_filter)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.browser_star_rating), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val nextMode = when (starFilterMode) {
                            BrowserViewModel.StarFilterMode.OFF -> BrowserViewModel.StarFilterMode.EQ
                            BrowserViewModel.StarFilterMode.EQ -> BrowserViewModel.StarFilterMode.GT
                            BrowserViewModel.StarFilterMode.GT -> BrowserViewModel.StarFilterMode.LT
                            BrowserViewModel.StarFilterMode.LT -> BrowserViewModel.StarFilterMode.OFF
                        }
                        val modeIcon = when (starFilterMode) {
                            BrowserViewModel.StarFilterMode.OFF -> Icons.Default.Block
                            BrowserViewModel.StarFilterMode.EQ -> Icons.Default.Check
                            BrowserViewModel.StarFilterMode.GT -> Icons.Default.ArrowUpward
                            BrowserViewModel.StarFilterMode.LT -> Icons.Default.ArrowDownward
                        }
                        val modeLabel = when (starFilterMode) {
                            BrowserViewModel.StarFilterMode.OFF -> ""
                            BrowserViewModel.StarFilterMode.EQ -> "="
                            BrowserViewModel.StarFilterMode.GT -> ">"
                            BrowserViewModel.StarFilterMode.LT -> "<"
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (starFilterMode != BrowserViewModel.StarFilterMode.OFF)
                                MaterialTheme.colorScheme.secondaryContainer
                            else Color.Transparent,
                            contentColor = if (starFilterMode != BrowserViewModel.StarFilterMode.OFF)
                                MaterialTheme.colorScheme.onSecondaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            onClick = { viewModel.setStarFilterMode(nextMode) },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                if (modeLabel.isNotEmpty()) {
                                    Text(modeLabel, style = MaterialTheme.typography.labelMedium)
                                } else {
                                    Icon(modeIcon, null, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                        if (starFilterMode != BrowserViewModel.StarFilterMode.OFF) {
                            StarRatingInput(
                                rating = starFilterValue,
                                onRatingChange = { viewModel.setStarFilterValue(it) },
                                starSize = 28.dp,
                                enabled = true
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAdvancedFilter = false }) { Text(stringResource(R.string.common_confirm)) }
            }
        )
    }

    // Directory tree dialog
    if (showDirectoryTree) {
        val directoryTree = remember { mutableStateOf<List<DirectoryNode>?>(null) }
        LaunchedEffect(Unit) {
            directoryTree.value = viewModel.loadSubFolders("")
        }
        val tree = directoryTree.value
        if (tree != null) {
            DirectoryTreeDialog(
                directoryTree = tree,
                currentPath = folder,
                onNavigate = { path ->
                    viewModel.clearScrollPosition(path)
                    viewModel.setFolder(path)
                },
                onDismiss = { showDirectoryTree = false },
                onLoadChildren = { path -> viewModel.loadSubFolders(path) }
            )
        }
    }

    error?.let {
        LaunchedEffect(it) {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }
}

@Composable
private fun SortFilterDropdown(
    expanded: Boolean,
    onDismiss: () -> Unit,
    sortMode: BrowserViewModel.SortMode,
    onSortChange: (BrowserViewModel.SortMode) -> Unit,
    filter: BrowserViewModel.BrowseFilter,
    onFilterChange: (BrowserViewModel.BrowseFilter) -> Unit,
    browseMode: BrowserViewModel.BrowseMode,
    onBrowseModeChange: (BrowserViewModel.BrowseMode) -> Unit,
    viewMode: BrowserViewModel.ViewMode,
    onViewModeChange: (BrowserViewModel.ViewMode) -> Unit,
    gridColumns: Int,
    onGridColumnsChange: (Int) -> Unit,
    starFilterMode: BrowserViewModel.StarFilterMode,
    onClearStarFilter: () -> Unit,
    onAdvancedFilter: () -> Unit,
    folder: String
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(max = 260.dp)
    ) {
        Text(
            stringResource(R.string.browser_sort),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 4.dp)
        )
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SortFieldChip(
                selected = sortMode == BrowserViewModel.SortMode.FILENAME_ASC || sortMode == BrowserViewModel.SortMode.FILENAME_DESC,
                ascending = sortMode == BrowserViewModel.SortMode.FILENAME_ASC,
                mainIcon = Icons.Default.SortByAlpha,
                onClick = {
                    val next = when (sortMode) {
                        BrowserViewModel.SortMode.FILENAME_ASC -> BrowserViewModel.SortMode.FILENAME_DESC
                        else -> BrowserViewModel.SortMode.FILENAME_ASC
                    }
                    onSortChange(next)
                },
                contentDescription = stringResource(R.string.browser_sort_filename)
            )
            SortFieldChip(
                selected = sortMode == BrowserViewModel.SortMode.DURATION_ASC || sortMode == BrowserViewModel.SortMode.DURATION_DESC,
                ascending = sortMode == BrowserViewModel.SortMode.DURATION_ASC,
                mainIcon = Icons.Default.Schedule,
                onClick = {
                    val next = when (sortMode) {
                        BrowserViewModel.SortMode.DURATION_ASC -> BrowserViewModel.SortMode.DURATION_DESC
                        else -> BrowserViewModel.SortMode.DURATION_ASC
                    }
                    onSortChange(next)
                },
                contentDescription = stringResource(R.string.browser_sort_duration)
            )
            SortFieldChip(
                selected = sortMode == BrowserViewModel.SortMode.FILE_SIZE_ASC || sortMode == BrowserViewModel.SortMode.FILE_SIZE_DESC,
                ascending = sortMode == BrowserViewModel.SortMode.FILE_SIZE_ASC,
                mainIcon = Icons.Default.SdCard,
                onClick = {
                    val next = when (sortMode) {
                        BrowserViewModel.SortMode.FILE_SIZE_ASC -> BrowserViewModel.SortMode.FILE_SIZE_DESC
                        else -> BrowserViewModel.SortMode.FILE_SIZE_ASC
                    }
                    onSortChange(next)
                },
                contentDescription = stringResource(R.string.browser_sort_size)
            )
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        Text(
            stringResource(R.string.browser_filter),
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
                onClick = { onFilterChange(BrowserViewModel.BrowseFilter.ALL); onDismiss() },
                contentDescription = stringResource(R.string.browser_filter_all)
            ) { Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null, modifier = Modifier.size(20.dp)) }
            IconOptionChip(
                selected = filter == BrowserViewModel.BrowseFilter.MEDIA_ONLY,
                onClick = { onFilterChange(BrowserViewModel.BrowseFilter.MEDIA_ONLY); onDismiss() },
                contentDescription = stringResource(R.string.browser_filter_media_only)
            ) { Icon(Icons.Default.Collections, null, modifier = Modifier.size(20.dp)) }
            IconOptionChip(
                selected = filter == BrowserViewModel.BrowseFilter.UNWATCHED,
                onClick = { onFilterChange(BrowserViewModel.BrowseFilter.UNWATCHED); onDismiss() },
                contentDescription = stringResource(R.string.browser_filter_unwatched)
            ) { Icon(Icons.Default.PlayCircleOutline, null, modifier = Modifier.size(20.dp)) }
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        if (browseMode == BrowserViewModel.BrowseMode.FILE || folder.isEmpty()) {
                Text(
                    stringResource(R.string.browser_browse),
                    style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp)
            )
            Row(
                modifier = Modifier.padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconOptionChip(
                    selected = browseMode == BrowserViewModel.BrowseMode.DIRECTORY,
                    onClick = { onBrowseModeChange(BrowserViewModel.BrowseMode.DIRECTORY); onDismiss() },
                    contentDescription = stringResource(R.string.browser_browse_directory)
                ) { Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(20.dp)) }
                IconOptionChip(
                    selected = browseMode == BrowserViewModel.BrowseMode.FILE,
                    onClick = { onBrowseModeChange(BrowserViewModel.BrowseMode.FILE); onDismiss() },
                    contentDescription = stringResource(R.string.browser_browse_file)
                ) { Icon(Icons.Default.VideoFile, null, modifier = Modifier.size(20.dp)) }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        }
        Text(
            stringResource(R.string.browser_view),
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
                onClick = { onViewModeChange(BrowserViewModel.ViewMode.LIST); onDismiss() },
                contentDescription = stringResource(R.string.browser_view_list)
            ) { Icon(Icons.AutoMirrored.Filled.ViewList, null, modifier = Modifier.size(20.dp)) }
            IconOptionChip(
                selected = viewMode == BrowserViewModel.ViewMode.GRID,
                onClick = { onViewModeChange(BrowserViewModel.ViewMode.GRID); onDismiss() },
                contentDescription = stringResource(R.string.browser_view_grid)
            ) { Icon(Icons.Default.Apps, null, modifier = Modifier.size(20.dp)) }
            IconOptionChip(
                selected = viewMode == BrowserViewModel.ViewMode.STAGGERED_GRID,
                onClick = { onViewModeChange(BrowserViewModel.ViewMode.STAGGERED_GRID); onDismiss() },
                contentDescription = stringResource(R.string.browser_view_staggered)
            ) {
                Canvas(modifier = Modifier.size(20.dp)) {
                    val barWidth = size.width / 5f
                    val gap = barWidth
                    drawRect(Color(0xFFFFFFFF), topLeft = Offset(0f, size.height * 0.15f), size = Size(barWidth, size.height * 0.85f))
                    drawRect(Color(0xFFFFFFFF), topLeft = Offset(barWidth + gap, 0f), size = Size(barWidth, size.height * 0.6f))
                    drawRect(Color(0xFFFFFFFF), topLeft = Offset(2 * (barWidth + gap), size.height * 0.3f), size = Size(barWidth, size.height * 0.7f))
                }
            }
        }
        if (viewMode == BrowserViewModel.ViewMode.GRID || viewMode == BrowserViewModel.ViewMode.STAGGERED_GRID) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf(1, 2, 3, 4, 5).forEach { col ->
                    GridColumnChip(
                        selected = gridColumns == col,
                        onClick = { onGridColumnsChange(col) },
                        label = "$col"
                    )
                }
            }
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        Row(
            modifier = Modifier.padding(horizontal = 8.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = {
                    onDismiss()
                    onAdvancedFilter()
                }
            ) {
                Text(stringResource(R.string.browser_advanced_filter), style = MaterialTheme.typography.bodySmall)
            }
            if (starFilterMode != BrowserViewModel.StarFilterMode.OFF) {
                TextButton(
                    onClick = onClearStarFilter
                ) {
                    Text(stringResource(R.string.browser_clear_filter), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun BrowseScrollTracker(
    folder: String,
    browseItemsFolder: String,
    browseItemsNotEmpty: Boolean,
    currentScrollPosition: () -> Pair<Int, Int>,
    saveScrollPosition: (String, Int, Int) -> Unit,
    getSavedScrollPosition: (String) -> Pair<Int, Int>?,
    scrollToPosition: suspend (Int, Int) -> Unit
) {
    var previousFolder by remember { mutableStateOf(folder) }
    LaunchedEffect(folder) {
        if (previousFolder != folder) {
            val (index, offset) = currentScrollPosition()
            saveScrollPosition(previousFolder, index, offset)
        }
        previousFolder = folder
    }
    LaunchedEffect(browseItemsFolder) {
        if (browseItemsFolder == folder && browseItemsNotEmpty) {
            val saved = getSavedScrollPosition(folder)
            if (saved != null) {
                scrollToPosition(saved.first, saved.second)
            }
        }
    }
}

@Composable
private fun EmptyBrowseContent(
    filter: BrowserViewModel.BrowseFilter,
    hiddenByFilterCount: Int
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
            text = if (filter != BrowserViewModel.BrowseFilter.ALL && hiddenByFilterCount > 0)
                stringResource(R.string.browser_hidden_by_filter, hiddenByFilterCount)
            else stringResource(R.string.browser_empty_dir),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun FilterCountFooter(hiddenCount: Int) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.browser_hidden_by_filter, hiddenCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val infoText = buildString {
                if (item.videoCount > 0) {
                    append(stringResource(R.string.browser_video_count, item.videoCount))
                    if (item.playedCount > 0) append(" · ${stringResource(R.string.browser_played_count, item.playedCount)}")
                    if (item.trashedCount > 0) append(" · ${stringResource(R.string.browser_trashed_count, item.trashedCount)}")
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = infoText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
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
            isImage = isImageFile(item.name),
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

            val metadataPrefix = if (isImageFile(item.name)) {
                buildImageMetadataLine(
                    width = item.width,
                    height = item.height,
                    dateTaken = item.dateTaken,
                    fileSize = item.fileSize
                )
            } else {
                buildMetadataLineWithoutExtension(
                    duration = item.duration,
                    width = item.width,
                    height = item.height,
                    bitrate = item.bitrate,
                    hasGainAdjusted = item.hasGainAdjusted
                )
            }
            val extension = item.name.substringAfterLast('.', "").uppercase()
            val hasContent = metadataPrefix.isNotEmpty() || item.star > 0 || extension.isNotEmpty()
            if (hasContent) {
                val metaStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (metadataPrefix.isNotEmpty()) {
                        Text(text = metadataPrefix, style = metaStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (item.star > 0) {
                        if (metadataPrefix.isNotEmpty()) Text(" · ", style = metaStyle)
                        StarRatingDisplay(rating = item.star, starSize = 10.dp, filled = true)
                    }
                    if (extension.isNotEmpty()) {
                        if (metadataPrefix.isNotEmpty() || item.star > 0) Text(" · ", style = metaStyle)
                        Text(extension, style = metaStyle, maxLines = 1)
                    }
                }
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
    contentDescription: String,
    icon: @Composable () -> Unit
) {
    Surface(
        modifier = modifier
            .size(40.dp)
            .semantics { this.contentDescription = contentDescription },
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
private fun SortFieldChip(
    selected: Boolean,
    ascending: Boolean,
    mainIcon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String
) {
    IconOptionChip(selected = selected, onClick = onClick, modifier = modifier, contentDescription = contentDescription) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(mainIcon, null, modifier = Modifier.size(18.dp))
            if (selected) {
                Icon(
                    if (ascending) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                    null,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}




@Composable
private fun GridColumnChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        onClick = onClick
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(28.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center
            )
        }
    }
}
