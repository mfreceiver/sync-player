package cn.vectory.syncplayer.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import cn.vectory.syncplayer.data.model.BrowseItem
import cn.vectory.syncplayer.ui.components.FileContextMenuDialog
import cn.vectory.syncplayer.ui.components.FileStatusIcon
import cn.vectory.syncplayer.ui.components.FolderContextMenuDialog
import cn.vectory.syncplayer.ui.components.FolderIcon
import cn.vectory.syncplayer.ui.components.Thumbnail
import cn.vectory.syncplayer.ui.components.showTrashUndoSnackbar
import cn.vectory.syncplayer.ui.components.verticalScrollbar
import cn.vectory.syncplayer.ui.util.buildMetadataLine
import cn.vectory.syncplayer.ui.util.buildImageMetadataLine
import cn.vectory.syncplayer.ui.util.isVideoFile
import cn.vectory.syncplayer.ui.util.isImageFile
import cn.vectory.syncplayer.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel = hiltViewModel(),
    initialFolder: String = "",
    onNavigateToFolder: (String) -> Unit,
    onNavigateToPlayer: (Long, List<Long>) -> Unit,
    onNavigateToImageViewer: (Long, List<Long>) -> Unit = { _, _ -> },
    onBack: () -> Unit,
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val searchScope by viewModel.searchScope.collectAsStateWithLifecycle()
    // Read items and their ownership from the same emission. The legacy results
    // StateFlow can lag behind searchState when query/scope changes.
    val searchState by viewModel.searchState.collectAsStateWithLifecycle()
    val searchResults = searchState.displayedResult?.items.orEmpty()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val resultKey = searchState.displayedResult?.resultKey
    val resultIsCurrent = searchState.phase == SearchViewModel.SearchPhase.READY &&
        !searchState.stale && resultKey?.query == query && resultKey?.scope == searchScope
    val hasOldResults = searchState.displayedResult != null && !resultIsCurrent
    val resultStillCurrent = {
        resultIsCurrent &&
            viewModel.query.value == resultKey?.query &&
            viewModel.searchScope.value == resultKey?.scope &&
            viewModel.searchState.value.let {
                it.phase == SearchViewModel.SearchPhase.READY && !it.stale &&
                    it.displayedResult?.resultKey == resultKey
            }
    }

    val focusRequester = remember { FocusRequester() }
    val context = LocalContext.current
    val noRecordStr = stringResource(R.string.context_no_record)
    val undoTrashMsg = stringResource(R.string.undo_trash_message)
    val undoDeleteMsg = stringResource(R.string.undo_delete_message)
    val undoActionLbl = stringResource(R.string.undo_action)
    val undoFailedMsg = stringResource(R.string.undo_failed_message)
    val deleteFailedMsg = stringResource(R.string.search_delete_failed)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current

    var showContextMenu by remember { mutableStateOf<BrowseItem?>(null) }
    var showFolderContextMenu by remember { mutableStateOf<BrowseItem?>(null) }
    var latestRequestedDeleteId by remember { mutableStateOf<Long?>(null) }
    var handledDeleteErrorSeq by remember { mutableStateOf<Long?>(null) }
    val deleteIdAtDispose = rememberUpdatedState(latestRequestedDeleteId)

    // The VM can outlive this destination on the back stack. The undo window must
    // not depend on ViewModel.onCleared() to settle a deferred hard delete. An
    // expected id prevents this page from committing another destination's token.
    DisposableEffect(viewModel) {
        onDispose {
            deleteIdAtDispose.value?.let(viewModel::commitDeferredDeleteSafely)
        }
    }
    LaunchedEffect(resultIsCurrent) {
        if (!resultIsCurrent) {
            showContextMenu = null
            showFolderContextMenu = null
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    // Best-effort undo notification: deleteEvents has no replay and a 16-slot DROP_OLDEST
    // buffer. An event lost while hidden or during recreation does NOT produce a guessed
    // success Snackbar. The VM owns the pending hard delete; our onDispose safety commit
    // and its onCleared safety path settle it even when no undo window was delivered.
    // Each handler is a child of STARTED and is cancelled on STOP (nothing shown offscreen).
    LaunchedEffect(viewModel, lifecycleOwner, snackbarHostState) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.deleteEvents.collect { event ->
                launch {
                    if (!event.success) {
                        // The VM also publishes the detailed error in its StateFlow. Consume
                        // that copy so one failed operation produces just one error Snackbar.
                        val failure = viewModel.error.value
                        handledDeleteErrorSeq = failure?.seq
                        if (failure != null) viewModel.clearError()
                        snackbarHostState.currentSnackbarData?.dismiss()
                        snackbarHostState.showSnackbar(failure?.message ?: deleteFailedMsg)
                        return@launch
                    }

                    snackbarHostState.currentSnackbarData?.dismiss()
                    if (event.operation == SearchViewModel.SearchDeleteOperation.TRASH_TOGGLE) {
                        val response = snackbarHostState.showTrashUndoSnackbar(
                            message = undoTrashMsg,
                            actionLabel = undoActionLbl,
                        )
                        if (response == SnackbarResult.ActionPerformed &&
                            viewModel.undoDelete(event.operationId) is UndoResult.Failed
                        ) {
                            snackbarHostState.showSnackbar(undoFailedMsg)
                        }
                    } else {
                        // BrowserScreen uses Short for hard delete and commits on dismissal.
                        // The event's operationId, never the latest global pending id, owns
                        // both the undo action and this Snackbar's eventual commit.
                        var finished = false
                        try {
                            val response = snackbarHostState.showSnackbar(
                                message = undoDeleteMsg,
                                actionLabel = undoActionLbl,
                                duration = SnackbarDuration.Short,
                            )
                            if (response == SnackbarResult.ActionPerformed) {
                                if (viewModel.undoDelete(event.operationId) is UndoResult.Failed) {
                                    snackbarHostState.showSnackbar(undoFailedMsg)
                                }
                            } else {
                                viewModel.commitDeferredDelete(event.operationId)
                            }
                            finished = true
                            if (latestRequestedDeleteId == event.operationId &&
                                viewModel.getPendingHardDeleteId() == null
                            ) latestRequestedDeleteId = null
                        } finally {
                            // STOP cancels the Snackbar, but must not strand its pending delete.
                            if (!finished) viewModel.commitDeferredDeleteSafely(event.operationId)
                        }
                    }
                }
            }
        }
    }

    BackHandler {
        onBack()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back)
                        )
                    }
                },
                title = { Text(stringResource(R.string.search_title)) }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { viewModel.setQuery(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 0.dp)
                    .focusRequester(focusRequester),
                placeholder = { Text(stringResource(R.string.search_hint)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = stringResource(R.string.browser_search)
                    )
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setQuery("") }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.search_clear)
                            )
                        }
                    }
                },
                singleLine = true
            )

            if (viewModel.searchFolder.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.search_scope_label, viewModel.searchFolder),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = searchScope == SearchViewModel.SearchScope.ALL,
                    onClick = { viewModel.setSearchScope(SearchViewModel.SearchScope.ALL) },
                    label = { Text(stringResource(R.string.search_scope_all)) }
                )
                FilterChip(
                    selected = searchScope == SearchViewModel.SearchScope.FILES_ONLY,
                    onClick = { viewModel.setSearchScope(SearchViewModel.SearchScope.FILES_ONLY) },
                    label = { Text(stringResource(R.string.search_scope_files)) }
                )
                FilterChip(
                    selected = searchScope == SearchViewModel.SearchScope.FOLDERS_ONLY,
                    onClick = { viewModel.setSearchScope(SearchViewModel.SearchScope.FOLDERS_ONLY) },
                    label = { Text(stringResource(R.string.search_scope_folders)) }
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            if (query.isNotEmpty() && (hasOldResults ||
                    searchState.phase == SearchViewModel.SearchPhase.WAITING ||
                    searchState.phase == SearchViewModel.SearchPhase.LOADING)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (searchState.phase != SearchViewModel.SearchPhase.FAILED) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    }
                    Text(
                        text = stringResource(
                            if (searchState.phase == SearchViewModel.SearchPhase.FAILED)
                                R.string.search_failed_change_query else R.string.search_results_updating
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (query.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = stringResource(R.string.search_empty_hint),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else if (searchResults.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (searchState.phase == SearchViewModel.SearchPhase.WAITING ||
                            searchState.phase == SearchViewModel.SearchPhase.LOADING
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(40.dp))
                        } else {
                            Icon(
                                imageVector = Icons.Default.SearchOff,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(48.dp)
                            )
                            Text(
                                text = stringResource(
                                    if (searchState.phase == SearchViewModel.SearchPhase.FAILED)
                                        R.string.search_failed_change_query else R.string.search_no_results
                                ),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (searchState.phase == SearchViewModel.SearchPhase.FAILED) {
                                TextButton(onClick = viewModel::retrySearch) {
                                    Text(stringResource(R.string.search_retry))
                                }
                            }
                        }
                    }
                }
            } else {
                val listState = rememberLazyListState()
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScrollbar(listState)
                ) {
                    items(
                        items = searchResults,
                        key = { it.relativePath }
                    ) { item ->
                        if (item.isDirectory) {
                            SearchFolderItem(
                                item = item,
                                enabled = resultIsCurrent,
                                onClick = {
                                    if (resultStillCurrent()) onNavigateToFolder(item.relativePath)
                                },
                                onLongClick = { if (resultStillCurrent()) showFolderContextMenu = item }
                            )
                        } else {
                            SearchFileItem(
                                item = item,
                                enabled = resultIsCurrent,
                                onClick = {
                                    if (!resultStillCurrent()) return@SearchFileItem
                                    when {
                                        isVideoFile(item.name) -> {
                                            val id = item.videoItemId
                                            if (id != null) {
                                                onNavigateToPlayer(id, viewModel.getSearchResultVideoIds())
                                            } else {
                                                scope.launch {
                                                    snackbarHostState.showSnackbar(noRecordStr)
                                                }
                                            }
                                        }
                                        isImageFile(item.name) -> {
                                            val id = item.videoItemId
                                            if (id != null) {
                                                onNavigateToImageViewer(id, viewModel.getSearchResultImageIds())
                                            } else {
                                                scope.launch {
                                                    snackbarHostState.showSnackbar(noRecordStr)
                                                }
                                            }
                                        }
                                    }
                                },
                                onLongClick = { if (resultStillCurrent()) showContextMenu = item }
                            )
                        }
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 72.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        }
    }

    if (resultStillCurrent()) showContextMenu?.let { item ->
        FileContextMenuDialog(
            item = item,
            actions = viewModel,
            onDismiss = { showContextMenu = null },
            scope = scope,
            snackbarHostState = snackbarHostState,
            context = context,
            onTrashed = viewModel::toggleTrashed,
            onDeleted = { id ->
                latestRequestedDeleteId = id
                viewModel.deleteFile(id)
            }
        )
    }

    if (resultStillCurrent()) showFolderContextMenu?.let { item ->
        FolderContextMenuDialog(
            item = item,
            actions = viewModel,
            onDismiss = { showFolderContextMenu = null }
        )
    }

    error?.let { ev ->
        LaunchedEffect(ev.seq, lifecycleOwner) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    // Allow a received delete-failure event to claim its matching error first.
                    delay(16)
                    if (handledDeleteErrorSeq != ev.seq) {
                        snackbarHostState.showSnackbar(ev.message)
                    }
                } finally {
                    // AND-080: cancellation mid-snackbar must still retire the
                    // event so it can't replay after recreation — guarded by seq
                    // so a newer error that arrived meanwhile is not cleared.
                    if (viewModel.error.value?.seq == ev.seq) viewModel.clearError()
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SearchFolderItem(
    item: BrowseItem,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val parentPath = extractParentPath(item.relativePath)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.48f)
            .combinedClickable(
                enabled = enabled,
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

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (parentPath.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = parentPath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SearchFileItem(
    item: BrowseItem,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val parentPath = extractParentPath(item.relativePath)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.48f)
            .combinedClickable(
                enabled = enabled,
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

            val metadata = if (isImageFile(item.name)) {
                buildImageMetadataLine(
                    width = item.width,
                    height = item.height,
                    dateTaken = item.dateTaken,
                    fileSize = item.fileSize
                ) + (if (item.name.substringAfterLast('.', "").isNotEmpty()) " · ${item.name.substringAfterLast('.', "").uppercase()}" else "")
            } else {
                buildMetadataLine(
                    duration = item.duration,
                    width = item.width,
                    height = item.height,
                    bitrate = item.bitrate,
                    extension = item.name.substringAfterLast('.', "").lowercase()
                )
            }
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

            if (parentPath.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = parentPath,
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

private fun extractParentPath(relativePath: String): String {
    val lastSlash = relativePath.lastIndexOf('/')
    return if (lastSlash > 0) relativePath.substring(0, lastSlash) else ""
}
