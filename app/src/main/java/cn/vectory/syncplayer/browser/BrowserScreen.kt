package cn.vectory.syncplayer.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import cn.vectory.syncplayer.ui.components.showTrashUndoSnackbar
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.vectory.syncplayer.R
import cn.vectory.syncplayer.data.model.BrowseItem
import cn.vectory.syncplayer.ui.components.DirectoryNode
import cn.vectory.syncplayer.ui.components.DirectoryTreeDialog
import cn.vectory.syncplayer.browser.UndoResult
import cn.vectory.syncplayer.ui.components.FileContextMenuDialog
import cn.vectory.syncplayer.ui.components.FolderContextMenuDialog
import cn.vectory.syncplayer.ui.components.StarRatingInput
import cn.vectory.syncplayer.ui.util.isImageFile
import cn.vectory.syncplayer.ui.util.isVideoFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel = hiltViewModel(),
    currentFolder: String = "",
    onNavigateToPlayer: (videoItemId: Long, videoIds: List<Long>) -> Unit,
    onNavigateToImageViewer: (imageItemId: Long, imageIds: List<Long>) -> Unit = { _, _ -> },
    onNavigateToSearch: () -> Unit,
    onNavigateToPlaybackSettings: () -> Unit,
    onNavigateToSyncPreview: (relationId: Long) -> Unit,
    onNavigateToCreateSyncSource: () -> Unit,
    onNavigateToCreateSyncRelation: () -> Unit,
) {
    val browseItems by viewModel.browseItems.collectAsStateWithLifecycle()
    val browseItemsFolder by viewModel.browseItemsFolder.collectAsStateWithLifecycle()
    val hiddenByFilterCount by viewModel.hiddenByFilterCount.collectAsStateWithLifecycle()
    val libraryEmpty by viewModel.libraryEmpty.collectAsStateWithLifecycle()
    @Suppress("UNUSED_VARIABLE")
    val hasConfig by viewModel.hasConfig.collectAsStateWithLifecycle()
    val firstRunState by viewModel.firstRunState.collectAsStateWithLifecycle()
    val firstRelationId by viewModel.firstRelationId.collectAsStateWithLifecycle()
    val onboardingDismissed by viewModel.onboardingDismissed.collectAsStateWithLifecycle()
    val sortMode by viewModel.sortMode.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
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
    // Tracks the id of a file currently pending deferred hard delete, so the context
    // menu can disable its delete action and prevent duplicate deferred deletions.
    var pendingDeleteId by remember { mutableStateOf<Long?>(null) }
    // The most recent id for which a deferred hard delete was initiated. Captured so the
    // DisposableEffect below can commit it via the safety path if the composition disposes
    // before the undo Snackbar's Dismissed branch ran (snackbar cancelled / nav away).
    var latestPendingDeleteId by remember { mutableStateOf<Long?>(null) }
    // True while this composition is active. The deleteFileWithUndo onSuccess callback reads
    // this to decide between the Snackbar path (composition alive) and the safety-commit
    // fallback (composition disposed — the composition scope is cancelled, so the Snackbar
    // path is unreachable and would silently lose the deferred delete).
    var compositionActive by remember { mutableStateOf(true) }

    DisposableEffect(viewModel) {
        compositionActive = true
        onDispose {
            compositionActive = false
            // Fallback: commit any still-pending deferred hard delete via the safety path so
            // it is never silently lost when leaving the Browser destination. expectedId
            // protection keeps this a no-op if the Snackbar already resolved the operation
            // (state cleared) or a newer operation now holds the slot.
            latestPendingDeleteId?.let { viewModel.commitDeferredDeleteSafely(it) }
        }
    }

    val context = LocalContext.current
    val cannotPlayStr = stringResource(R.string.browser_cannot_play)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(currentFolder) {
        if (viewModel.currentFolder.value.isEmpty()) {
            viewModel.setFolder(currentFolder)
        }
    }

    // Stable navigation/callbacks — built once per identity change of their inputs.
    val onOpenVideo: (BrowseItem) -> Unit = remember(viewModel, onNavigateToPlayer, scope, snackbarHostState, cannotPlayStr) {
        { item ->
            val id = item.videoItemId
            if (id != null) {
                onNavigateToPlayer(id, viewModel.getFolderVideoIds())
            } else {
                scope.launch { snackbarHostState.showSnackbar(cannotPlayStr) }
            }
        }
    }
    val onOpenImage: (BrowseItem) -> Unit = remember(viewModel, onNavigateToImageViewer, scope, snackbarHostState, cannotPlayStr) {
        { item ->
            val id = item.videoItemId
            if (id != null) {
                onNavigateToImageViewer(id, viewModel.getFolderImageIds())
            } else {
                scope.launch { snackbarHostState.showSnackbar(cannotPlayStr) }
            }
        }
    }
    val onCannotPlay: () -> Unit = remember(scope, snackbarHostState, cannotPlayStr) {
        { scope.launch { snackbarHostState.showSnackbar(cannotPlayStr) } }
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
            BrowserTopBar(
                folder = folder,
                currentRelationId = currentRelationId,
                starFilterActive = starFilterMode != BrowserViewModel.StarFilterMode.OFF,
                onSettings = onNavigateToPlaybackSettings,
                onHome = { viewModel.setFolder("") },
                onTitleClick = { showDirectoryTree = true },
                onSync = { currentRelationId?.let(onNavigateToSyncPreview) },
                onSearch = onNavigateToSearch,
                onSortFilterClick = { showSortMenu = true },
                sortFilterDropdown = {
                    SortFilterDropdown(
                        expanded = showSortMenu,
                        onDismiss = { showSortMenu = false },
                        sortMode = sortMode,
                        onSortChange = viewModel::setSortMode,
                        filter = filter,
                        onFilterChange = viewModel::setFilter,
                        browseMode = browseMode,
                        onBrowseModeChange = viewModel::setBrowseMode,
                        viewMode = viewMode,
                        onViewModeChange = viewModel::setViewMode,
                        gridColumns = gridColumns,
                        onGridColumnsChange = viewModel::setGridColumns,
                        starFilterMode = starFilterMode,
                        onClearStarFilter = {
                            viewModel.setStarFilterMode(BrowserViewModel.StarFilterMode.OFF)
                            viewModel.setStarFilterValue(0)
                        },
                        onAdvancedFilter = { showAdvancedFilter = true },
                        folder = folder
                    )
                }
            )
        }
    ) { paddingValues ->
        // First-run guidance card: shown only at root, empty library, setup incomplete,
        // and not previously dismissed. Replaces the plain EmptyBrowseContent so a new
        // user can discover the source → relation → sync path without hunting in settings.
        val resolvedState = firstRunState
        val showOnboardingCard = folder.isEmpty()
                && browseItems.isEmpty()
                && libraryEmpty
                && !onboardingDismissed
                && resolvedState != null
                && resolvedState != FirstRunState.READY

        if (showOnboardingCard) {
            FirstRunOnboardingCard(
                state = resolvedState!!,
                targetRelationId = firstRelationId,
                onNavigateToCreateSource = onNavigateToCreateSyncSource,
                onNavigateToCreateRelation = onNavigateToCreateSyncRelation,
                onNavigateToSyncPreview = onNavigateToSyncPreview,
                onDismiss = viewModel::dismissOnboarding,
                modifier = Modifier.padding(paddingValues)
            )
        } else {
            BrowserContent(
                viewMode = viewMode,
                browseItems = browseItems,
                folder = folder,
                browseItemsFolder = browseItemsFolder,
                filter = filter,
                hiddenByFilterCount = hiddenByFilterCount,
                gridColumns = gridColumns,
                contentPadding = paddingValues,
                onFolderClick = viewModel::setFolder,
                onFolderLongClick = { showFolderContextMenu = it },
                onFileOpenVideo = onOpenVideo,
                onFileOpenImage = onOpenImage,
                onCannotPlay = onCannotPlay,
                onFileLongClick = { showContextMenu = it },
                onSaveScrollPosition = viewModel::saveScrollPosition,
                onGetScrollPosition = viewModel::getScrollPosition
            )
        }
    }

    // Resolve undo-related strings once (outside lambdas that are not @Composable).
    val undoTrashMsg = stringResource(R.string.undo_trash_message)
    val undoDeleteMsg = stringResource(R.string.undo_delete_message)
    val undoActionLbl = stringResource(R.string.undo_action)
    val undoFailedMsg = stringResource(R.string.undo_failed_message)

    showContextMenu?.let { item ->
        FileContextMenuDialog(
            item = item,
            actions = viewModel,
            onDismiss = { showContextMenu = null },
            scope = scope,
            snackbarHostState = snackbarHostState,
            context = context,
            onTrashed = { id ->
                // Defer the Snackbar until the async toggle actually succeeds: a failed toggle
                // must never produce a misleading "moved to trash" Snackbar. Errors land in
                // viewModel.error and are surfaced by the error Snackbar below.
                val markerAtRequest = pendingDeleteId
                viewModel.toggleTrashedWithUndo(
                    videoItemId = id,
                    onFailure = {
                        val (pendingId, latestId) = reconcilePendingDeleteMarker(
                            pendingDeleteId,
                            latestPendingDeleteId,
                            viewModel.getPendingHardDeleteId(),
                            markerAtRequest,
                        )
                        pendingDeleteId = pendingId
                        latestPendingDeleteId = latestId
                    }
                ) {
                    // A successful toggle means any older CommittingHardDelete was drained by
                    // the use-case before this operation was recorded. Reconcile against the
                    // authoritative state without clearing a newer marker.
                    val (pendingId, latestId) = reconcilePendingDeleteMarker(
                        pendingDeleteId,
                        latestPendingDeleteId,
                        viewModel.getPendingHardDeleteId(),
                        markerAtRequest,
                    )
                    pendingDeleteId = pendingId
                    latestPendingDeleteId = latestId
                    onTrashedInBrowser(
                        scope, snackbarHostState, viewModel, id,
                        undoTrashMsg, undoActionLbl, undoFailedMsg
                    )
                }
            },
            onDeleted = { id ->
                // A retained CommittingHardDelete is recoverable: allow the use-case to drain it
                // before recording this new delete, but keep the old marker until that succeeds.
                val (syncedPendingId, syncedLatestId) = reconcilePendingDeleteMarker(
                    localPendingId = pendingDeleteId,
                    latestPendingId = latestPendingDeleteId,
                    authoritativePendingId = viewModel.getPendingHardDeleteId(),
                    expectedLocalPendingId = pendingDeleteId,
                )
                pendingDeleteId = syncedPendingId
                latestPendingDeleteId = syncedLatestId
                val currentPendingId = syncedPendingId
                val hasPendingDebt = currentPendingId != null
                val hasCommittingDebt = currentPendingId?.let(viewModel::hasCommittingDelete) == true
                if (shouldStartDeferredDelete(
                        currentPendingId, id, hasPendingDebt, hasCommittingDebt
                    )
                ) {
                    if (!hasPendingDebt) {
                        pendingDeleteId = id
                        latestPendingDeleteId = id
                    }
                    viewModel.deleteFileWithUndo(
                        videoItemId = id,
                        onFailure = {
                            val (pendingId, latestId) = reconcilePendingDeleteMarker(
                                pendingDeleteId,
                                latestPendingDeleteId,
                                viewModel.getPendingHardDeleteId(),
                                currentPendingId,
                            )
                            pendingDeleteId = pendingId
                            latestPendingDeleteId = latestId
                        },
                        onSuccess = {
                            // onSuccess: the use-case recorded the pending delete and refreshed the
                            // list. Only NOW show the undo Snackbar. Reconcile against the
                            // authoritative state so an older callback cannot clear newer state.
                            val (pendingId, latestId) = reconcilePendingDeleteMarker(
                                pendingDeleteId,
                                latestPendingDeleteId,
                                viewModel.getPendingHardDeleteId(),
                                currentPendingId,
                            )
                            pendingDeleteId = pendingId
                            latestPendingDeleteId = latestId
                            if (compositionActive) {
                                onDeletedInBrowser(
                                    scope, snackbarHostState, viewModel, id,
                                    onResolved = {
                                        pendingDeleteId = null
                                        latestPendingDeleteId = null
                                    },
                                    undoDeleteMsg, undoActionLbl, undoFailedMsg
                                )
                            } else {
                                // Composition disposed before this callback fired (the composition
                                // scope is cancelled → the Snackbar path is unreachable). Commit the
                                // deferred delete via the safety path so it is not silently lost.
                                viewModel.commitDeferredDeleteSafely(id)
                                pendingDeleteId = null
                            }
                        },
                    )
                }
            }
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
        AdvancedFilterDialog(
            starFilterValue = starFilterValue,
            starFilterMode = starFilterMode,
            onStarFilterModeChange = viewModel::setStarFilterMode,
            onStarFilterValueChange = viewModel::setStarFilterValue,
            onDismiss = { showAdvancedFilter = false }
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

    error?.let { ev ->
        LaunchedEffect(ev.seq) {
            snackbarHostState.showSnackbar(ev.message)
            viewModel.clearError()
        }
    }
}

/**
 * Handle trash toggle in browser: the toggle is performed by the caller (via ViewModel →
 * UndoDeleteUseCase which records the previous state). We show a Snackbar with undo action.
 *
 * @param id The video item id — used as expectedId to protect against stale snackbar actions.
 * @param message Resolved "moved to trash" message (pre-resolved to avoid @Composable context).
 * @param actionLabel Resolved "undo" label (pre-resolved to avoid @Composable context).
 * @param failedMessage Resolved failure message (pre-resolved to avoid @Composable context).
 */
private fun onTrashedInBrowser(
    scope: CoroutineScope,
    snackbarHostState: SnackbarHostState,
    viewModel: BrowserViewModel,
    id: Long,
    message: String,
    actionLabel: String,
    failedMessage: String
) {
    scope.launch {
        snackbarHostState.currentSnackbarData?.dismiss()
        val result = snackbarHostState.showTrashUndoSnackbar(
            message = message,
            actionLabel = actionLabel,
        )
        when (result) {
            SnackbarResult.ActionPerformed -> {
                // expectedId protection: only undo if this operation is still the current one.
                val undoResult = viewModel.undoDelete(expectedId = id)
                if (undoResult is UndoResult.Failed) {
                    snackbarHostState.showSnackbar(failedMessage)
                }
            }
            SnackbarResult.Dismissed -> {
                // Trash toggle is already applied; nothing to commit.
            }
        }
    }
}

/**
 * Handle hard delete in browser: the delete is deferred by UndoDeleteUseCase (UI already
 * refreshed via refreshTrigger). We show a Snackbar with undo action.
 * If the Snackbar is dismissed without undo, we commit the deferred delete.
 *
 * @param id The video item id — used as expectedId to protect against stale snackbar actions.
 * @param onResolved Callback to clear the pending-delete guard in the UI.
 * @param message Resolved "deleted" message (pre-resolved to avoid @Composable context).
 * @param actionLabel Resolved "undo" label (pre-resolved to avoid @Composable context).
 * @param failedMessage Resolved failure message (pre-resolved to avoid @Composable context).
 */
private fun onDeletedInBrowser(
    scope: CoroutineScope,
    snackbarHostState: SnackbarHostState,
    viewModel: BrowserViewModel,
    id: Long,
    onResolved: () -> Unit,
    message: String,
    actionLabel: String,
    failedMessage: String
) {
    scope.launch {
        snackbarHostState.currentSnackbarData?.dismiss()
        val result = snackbarHostState.showSnackbar(
            message = message,
            actionLabel = actionLabel,
            duration = SnackbarDuration.Short
        )
        val resolved = when (result) {
            SnackbarResult.ActionPerformed -> {
                // expectedId protection: only cancel if this operation is still the current one.
                val undoResult = viewModel.undoDelete(expectedId = id)
                if (undoResult is UndoResult.Failed) {
                    snackbarHostState.showSnackbar(failedMessage)
                }
                shouldClearDeferredDelete(
                    undoResult,
                    hasAnyPendingDebt = viewModel.getPendingHardDeleteId() != null
                )
            }
            SnackbarResult.Dismissed -> {
                // expectedId protection: only commit if this operation is still the current one.
                val commitResult = viewModel.commitDeferredDelete(expectedId = id)
                shouldClearDeferredDelete(
                    commitResult,
                    hasAnyPendingDebt = viewModel.getPendingHardDeleteId() != null
                )
            }
        }
        // A failed commit remains an owed, non-undoable debt. Keep the marker so a later
        // operation can retry the drain instead of silently becoming a no-op.
        if (resolved) onResolved()
    }
}

/** Whether a snackbar operation has reached a state where its UI pending marker may be cleared. */
internal fun shouldClearDeferredDelete(
    result: UndoResult,
    hasAnyPendingDebt: Boolean = false,
): Boolean = result !is UndoResult.Failed && !hasAnyPendingDebt

/**
 * Sync the local BrowserScreen marker with the use-case's authoritative pending state, but only
 * when the callback still owns the marker it observed at request time.
 */
internal fun reconcilePendingDeleteMarker(
    localPendingId: Long?,
    latestPendingId: Long?,
    authoritativePendingId: Long?,
    expectedLocalPendingId: Long?,
): Pair<Long?, Long?> {
    if (localPendingId != expectedLocalPendingId) return localPendingId to latestPendingId
    val latest = if (latestPendingId == expectedLocalPendingId) {
        authoritativePendingId
    } else {
        latestPendingId
    }
    return authoritativePendingId to latest
}

internal fun shouldStartDeferredDelete(
    pendingDeleteId: Long?,
    requestedId: Long,
    hasPendingDebt: Boolean,
    hasCommittingDebt: Boolean,
): Boolean = pendingDeleteId == null || !hasPendingDebt ||
    hasCommittingDebt || pendingDeleteId == requestedId

@Composable
private fun AdvancedFilterDialog(
    starFilterValue: Int,
    starFilterMode: BrowserViewModel.StarFilterMode,
    onStarFilterModeChange: (BrowserViewModel.StarFilterMode) -> Unit,
    onStarFilterValueChange: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.browser_advanced_filter)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.browser_star_rating),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
                    val modeStateDescription = stringResource(
                        when (starFilterMode) {
                            BrowserViewModel.StarFilterMode.OFF -> R.string.browser_a11y_star_filter_mode_off
                            BrowserViewModel.StarFilterMode.EQ -> R.string.browser_a11y_star_filter_mode_equals
                            BrowserViewModel.StarFilterMode.GT -> R.string.browser_a11y_star_filter_mode_greater_than
                            BrowserViewModel.StarFilterMode.LT -> R.string.browser_a11y_star_filter_mode_less_than
                        }
                    )
                    val modeContentDescription = stringResource(R.string.browser_a11y_star_filter_mode)
                    val colors = MaterialTheme.colorScheme
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (starFilterMode != BrowserViewModel.StarFilterMode.OFF)
                            colors.secondaryContainer else Color.Transparent,
                        contentColor = if (starFilterMode != BrowserViewModel.StarFilterMode.OFF)
                            colors.onSecondaryContainer else colors.onSurfaceVariant,
                        onClick = { onStarFilterModeChange(nextMode) },
                        modifier = Modifier
                            .size(36.dp)
                            .semantics {
                                contentDescription = modeContentDescription
                                stateDescription = modeStateDescription
                            }
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            if (modeLabel.isNotEmpty()) {
                                Text(modeLabel, style = MaterialTheme.typography.labelMedium)
                            } else {
                                Icon(modeIcon, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                    if (starFilterMode != BrowserViewModel.StarFilterMode.OFF) {
                        StarRatingInput(
                            rating = starFilterValue,
                            onRatingChange = onStarFilterValueChange,
                            starSize = 28.dp,
                            enabled = true
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_confirm)) }
        }
    )
}
