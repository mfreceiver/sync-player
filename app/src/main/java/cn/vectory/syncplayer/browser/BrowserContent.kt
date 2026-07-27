package cn.vectory.syncplayer.browser

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.vectory.syncplayer.R
import cn.vectory.syncplayer.data.model.BrowseItem
import cn.vectory.syncplayer.ui.components.FileStatusIcon
import cn.vectory.syncplayer.ui.components.FolderGridItem
import cn.vectory.syncplayer.ui.components.FolderIcon
import cn.vectory.syncplayer.ui.components.StarRatingDisplay
import cn.vectory.syncplayer.ui.components.Thumbnail
import cn.vectory.syncplayer.ui.components.VideoGridItem
import cn.vectory.syncplayer.ui.components.gridVerticalScrollbar
import cn.vectory.syncplayer.ui.components.staggeredGridVerticalScrollbar
import cn.vectory.syncplayer.ui.components.verticalScrollbar
import cn.vectory.syncplayer.ui.util.buildImageMetadataLine
import cn.vectory.syncplayer.ui.util.buildMetadataLineWithoutExtension
import cn.vectory.syncplayer.ui.util.isImageFile
import cn.vectory.syncplayer.ui.util.isVideoFile

/**
 * Content area of the browser screen. Dispatches to the list / grid / staggered
 * grid composable based on [viewMode]. Each branch owns its own scroll state and
 * wires a [BrowseScrollTracker] for cross-folder scroll-position memory.
 *
 * Callbacks are kept as stable primitives / lambdas so this composable can be
 * skipped when only the scroll state changes.
 *
 * Note (preserved behavior): the staggered branch intentionally does NOT save
 * scroll-on-click (matching the legacy implementation) and renders no filter
 * footer / empty state. Only the previously-missing scrollbar was added.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BrowserContent(
    viewMode: BrowserViewModel.ViewMode,
    browseItems: List<BrowseItem>,
    folder: String,
    browseItemsFolder: String,
    filter: BrowserViewModel.BrowseFilter,
    hiddenByFilterCount: Int,
    gridColumns: Int,
    contentPadding: PaddingValues,
    onFolderClick: (String) -> Unit,
    onFolderLongClick: (BrowseItem) -> Unit,
    onFileOpenVideo: (BrowseItem) -> Unit,
    onFileOpenImage: (BrowseItem) -> Unit,
    onCannotPlay: () -> Unit,
    onFileLongClick: (BrowseItem) -> Unit,
    onSaveScrollPosition: (String, Int, Int) -> Unit,
    onGetScrollPosition: (String) -> Pair<Int, Int>?,
    modifier: Modifier = Modifier
) {
    when (viewMode) {
        BrowserViewModel.ViewMode.GRID -> GridContent(
            browseItems = browseItems,
            folder = folder,
            browseItemsFolder = browseItemsFolder,
            filter = filter,
            hiddenByFilterCount = hiddenByFilterCount,
            gridColumns = gridColumns,
            contentPadding = contentPadding,
            onFolderClick = onFolderClick,
            onFolderLongClick = onFolderLongClick,
            onFileOpenVideo = onFileOpenVideo,
            onFileOpenImage = onFileOpenImage,
            onCannotPlay = onCannotPlay,
            onFileLongClick = onFileLongClick,
            onSaveScrollPosition = onSaveScrollPosition,
            onGetScrollPosition = onGetScrollPosition,
            modifier = modifier
        )
        BrowserViewModel.ViewMode.STAGGERED_GRID -> StaggeredContent(
            browseItems = browseItems,
            folder = folder,
            browseItemsFolder = browseItemsFolder,
            gridColumns = gridColumns,
            contentPadding = contentPadding,
            onFolderClick = onFolderClick,
            onFolderLongClick = onFolderLongClick,
            onFileOpenVideo = onFileOpenVideo,
            onFileOpenImage = onFileOpenImage,
            onCannotPlay = onCannotPlay,
            onFileLongClick = onFileLongClick,
            onSaveScrollPosition = onSaveScrollPosition,
            onGetScrollPosition = onGetScrollPosition,
            modifier = modifier
        )
        BrowserViewModel.ViewMode.LIST -> ListContent(
            browseItems = browseItems,
            folder = folder,
            browseItemsFolder = browseItemsFolder,
            filter = filter,
            hiddenByFilterCount = hiddenByFilterCount,
            contentPadding = contentPadding,
            onFolderClick = onFolderClick,
            onFolderLongClick = onFolderLongClick,
            onFileOpenVideo = onFileOpenVideo,
            onFileOpenImage = onFileOpenImage,
            onCannotPlay = onCannotPlay,
            onFileLongClick = onFileLongClick,
            onSaveScrollPosition = onSaveScrollPosition,
            onGetScrollPosition = onGetScrollPosition,
            modifier = modifier
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridContent(
    browseItems: List<BrowseItem>,
    folder: String,
    browseItemsFolder: String,
    filter: BrowserViewModel.BrowseFilter,
    hiddenByFilterCount: Int,
    gridColumns: Int,
    contentPadding: PaddingValues,
    onFolderClick: (String) -> Unit,
    onFolderLongClick: (BrowseItem) -> Unit,
    onFileOpenVideo: (BrowseItem) -> Unit,
    onFileOpenImage: (BrowseItem) -> Unit,
    onCannotPlay: () -> Unit,
    onFileLongClick: (BrowseItem) -> Unit,
    onSaveScrollPosition: (String, Int, Int) -> Unit,
    onGetScrollPosition: (String) -> Pair<Int, Int>?,
    modifier: Modifier
) {
    val gridState = rememberLazyGridState()
    BrowseScrollTracker(
        folder = folder,
        browseItemsFolder = browseItemsFolder,
        browseItemsNotEmpty = browseItems.isNotEmpty(),
        currentScrollPosition = { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset },
        saveScrollPosition = onSaveScrollPosition,
        getSavedScrollPosition = onGetScrollPosition,
        scrollToPosition = { i, o -> gridState.scrollToItem(i, o) }
    )
    LazyVerticalGrid(
        columns = GridCells.Fixed(gridColumns),
        state = gridState,
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding)
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
                    onClick = { onFolderClick(item.relativePath) },
                    onLongClick = { onFolderLongClick(item) },
                    videoItemIds = item.videoItemIds,
                    gridDensity = when {
                        gridColumns <= 2 -> 3
                        else -> 2
                    }
                )
            } else {
                VideoGridItem(
                    videoItemId = item.videoItemId,
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
                                onSaveScrollPosition(folder, gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset)
                                onFileOpenVideo(item)
                            }
                            isImageFile(item.name) -> {
                                onSaveScrollPosition(folder, gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset)
                                onFileOpenImage(item)
                            }
                            else -> onCannotPlay()
                        }
                    },
                    onLongClick = { onFileLongClick(item) }
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
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StaggeredContent(
    browseItems: List<BrowseItem>,
    folder: String,
    browseItemsFolder: String,
    gridColumns: Int,
    contentPadding: PaddingValues,
    onFolderClick: (String) -> Unit,
    onFolderLongClick: (BrowseItem) -> Unit,
    onFileOpenVideo: (BrowseItem) -> Unit,
    onFileOpenImage: (BrowseItem) -> Unit,
    onCannotPlay: () -> Unit,
    onFileLongClick: (BrowseItem) -> Unit,
    onSaveScrollPosition: (String, Int, Int) -> Unit,
    onGetScrollPosition: (String) -> Pair<Int, Int>?,
    modifier: Modifier
) {
    val staggeredState = rememberLazyStaggeredGridState()
    BrowseScrollTracker(
        folder = folder,
        browseItemsFolder = browseItemsFolder,
        browseItemsNotEmpty = browseItems.isNotEmpty(),
        currentScrollPosition = { staggeredState.firstVisibleItemIndex to staggeredState.firstVisibleItemScrollOffset },
        saveScrollPosition = onSaveScrollPosition,
        getSavedScrollPosition = onGetScrollPosition,
        scrollToPosition = { i, o -> staggeredState.scrollToItem(i, o) }
    )
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(gridColumns),
        state = staggeredState,
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding)
            .staggeredGridVerticalScrollbar(staggeredState),
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
                    onClick = { onFolderClick(item.relativePath) },
                    onLongClick = { onFolderLongClick(item) },
                    videoItemIds = item.videoItemIds,
                    gridDensity = when { gridColumns <= 2 -> 3 else -> 2 }
                )
            } else {
                VideoGridItem(
                    videoItemId = item.videoItemId,
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
                            isVideoFile(item.name) -> onFileOpenVideo(item)
                            isImageFile(item.name) -> onFileOpenImage(item)
                            else -> onCannotPlay()
                        }
                    },
                    onLongClick = { onFileLongClick(item) }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListContent(
    browseItems: List<BrowseItem>,
    folder: String,
    browseItemsFolder: String,
    filter: BrowserViewModel.BrowseFilter,
    hiddenByFilterCount: Int,
    contentPadding: PaddingValues,
    onFolderClick: (String) -> Unit,
    onFolderLongClick: (BrowseItem) -> Unit,
    onFileOpenVideo: (BrowseItem) -> Unit,
    onFileOpenImage: (BrowseItem) -> Unit,
    onCannotPlay: () -> Unit,
    onFileLongClick: (BrowseItem) -> Unit,
    onSaveScrollPosition: (String, Int, Int) -> Unit,
    onGetScrollPosition: (String) -> Pair<Int, Int>?,
    modifier: Modifier
) {
    val listState = rememberLazyListState()
    BrowseScrollTracker(
        folder = folder,
        browseItemsFolder = browseItemsFolder,
        browseItemsNotEmpty = browseItems.isNotEmpty(),
        currentScrollPosition = { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset },
        saveScrollPosition = onSaveScrollPosition,
        getSavedScrollPosition = onGetScrollPosition,
        scrollToPosition = { i, o -> listState.scrollToItem(i, o) }
    )
    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScrollbar(listState)
    ) {
        items(
            items = browseItems,
            key = { it.relativePath }
        ) { item ->
            if (item.isDirectory) {
                FolderItem(
                    item = item,
                    onClick = { onFolderClick(item.relativePath) },
                    onLongClick = { onFolderLongClick(item) }
                )
            } else {
                FileItem(
                    item = item,
                    onClick = {
                        when {
                            isVideoFile(item.name) -> {
                                onSaveScrollPosition(folder, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
                                onFileOpenVideo(item)
                            }
                            isImageFile(item.name) -> {
                                onSaveScrollPosition(folder, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
                                onFileOpenImage(item)
                            }
                            else -> onCannotPlay()
                        }
                    },
                    onLongClick = { onFileLongClick(item) }
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

/**
 * Tracks scroll position across folder navigations.
 *
 * Saves the previous folder's scroll offset when [folder] changes, and restores
 * the saved scroll offset once the matching [browseItemsFolder] is loaded.
 *
 * Uses [rememberSaveable] (instead of plain `remember`) for [previousFolder] so
 * the previous-folder pointer survives configuration changes / process death
 * and is never stale relative to a recomposed `folder` parameter. The restore
 * effect is keyed on both [browseItemsFolder] and [folder] so the captured
 * `folder` is always current.
 */
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
    var previousFolder by rememberSaveable { mutableStateOf(folder) }
    LaunchedEffect(folder) {
        if (previousFolder != folder) {
            val (index, offset) = currentScrollPosition()
            saveScrollPosition(previousFolder, index, offset)
        }
        previousFolder = folder
    }
    LaunchedEffect(browseItemsFolder, folder) {
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
            videoItemId = item.videoItemId,
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
