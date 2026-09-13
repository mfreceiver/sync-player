package cn.vectory.syncplayer.browser

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.vectory.syncplayer.data.model.BrowseItem
import cn.vectory.syncplayer.data.model.FolderStats
import cn.vectory.syncplayer.data.repository.PlaybackRepository
import cn.vectory.syncplayer.data.repository.SyncRelationRepository
import cn.vectory.syncplayer.data.repository.SyncSourceRepository
import cn.vectory.syncplayer.data.repository.UserPreferencesRepository
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.R
import cn.vectory.syncplayer.sync.syncErrorMessage
import cn.vectory.syncplayer.ui.util.isVideoFile
import cn.vectory.syncplayer.ui.util.isImageFile
import cn.vectory.syncplayer.ui.components.DirectoryNode
import cn.vectory.syncplayer.ui.components.FileContextMenuActions
import cn.vectory.syncplayer.ui.components.FolderContextMenuActions
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ErrorEvent(val message: String, val seq: Long)

/**
 * Browser screen ViewModel.
 *
 * Since the remediation plan Task 8 extraction, the content pipeline (folder / filter /
 * sort / star / view state, the browseItems Flow chain, preference hydration and
 * persistence) lives in [BrowserContentCoordinator], constructed here with this
 * ViewModel's [viewModelScope] so sharing and cancellation semantics are identical to
 * the previous inline implementation. Every public StateFlow and method below keeps its
 * exact pre-extraction name and type; the delegation is a pure equivalence refactor.
 *
 * The ViewModel retains what is genuinely view-model concerns: the transient [_error]
 * channel written by file-operation callbacks, the scroll-position cache, onboarding
 * state, and the undo / context-menu operations.
 */
@HiltViewModel
class BrowserViewModel @Inject constructor(
    private val videoRepository: VideoRepository,
    playbackRepository: PlaybackRepository,
    syncRelationRepository: SyncRelationRepository,
    private val syncSourceRepository: SyncSourceRepository,
    private val userPrefs: UserPreferencesRepository,
    private val fileOps: FileOperationsUseCase,
    private val undoDeleteUseCase: UndoDeleteUseCase,
    @ApplicationContext private val context: Context
) : ViewModel(), FileContextMenuActions, FolderContextMenuActions {

    enum class SortMode { FILENAME_ASC, FILENAME_DESC, DURATION_ASC, DURATION_DESC, FILE_SIZE_ASC, FILE_SIZE_DESC }
    enum class BrowseFilter { ALL, MEDIA_ONLY, UNWATCHED }
    enum class ViewMode { LIST, GRID, STAGGERED_GRID }
    // Star-based filtering for videos
    enum class StarFilterMode { OFF, EQ, GT, LT }
    // Browse mode controls how items are presented
    enum class BrowseMode { DIRECTORY, FILE }

    private val coordinator = BrowserContentCoordinator(
        videoRepository = videoRepository,
        playbackRepository = playbackRepository,
        syncRelationRepository = syncRelationRepository,
        userPrefs = userPrefs,
        scope = viewModelScope
    )

    // === Content state, owned and exposed by the coordinator (read-only StateFlows) ===

    val currentFolder: StateFlow<String> = coordinator.currentFolder

    val sortMode: StateFlow<SortMode> = coordinator.sortMode

    val filter: StateFlow<BrowseFilter> = coordinator.filter

    // Star and browse mode related state
    val starFilterMode: StateFlow<StarFilterMode> = coordinator.starFilterMode

    val starFilterValue: StateFlow<Int> = coordinator.starFilterValue

    val browseMode: StateFlow<BrowseMode> = coordinator.browseMode

    val flatTotalCount: StateFlow<Int> = coordinator.flatTotalCount

    val viewMode: StateFlow<ViewMode> = coordinator.viewMode

    val gridColumns: StateFlow<Int> = coordinator.gridColumns

    val browseItemsFolder: StateFlow<String> = coordinator.browseItemsFolder

    val hiddenByFilterCount: StateFlow<Int> = coordinator.hiddenByFilterCount

    val browseItems: StateFlow<List<BrowseItem>> = coordinator.browseItems

    /** ID of the first sync relation, used as the sync target in the NEEDS_SYNC card. */
    val firstRelationId: StateFlow<Long?> = coordinator.firstRelationId

    val currentRelationId: StateFlow<Long?> = coordinator.currentRelationId

    // === View-model-owned transient state ===

    private val errorSeq = AtomicLong(0L)
    private val _error = MutableStateFlow<ErrorEvent?>(null)
    val error: StateFlow<ErrorEvent?> = _error.asStateFlow()

    private val _scrollPositions = mutableMapOf<String, Pair<Int, Int>>()

    fun saveScrollPosition(folder: String, firstVisibleItemIndex: Int, firstVisibleItemScrollOffset: Int) {
        _scrollPositions[folder] = Pair(firstVisibleItemIndex, firstVisibleItemScrollOffset)
    }

    fun getScrollPosition(folder: String): Pair<Int, Int>? = _scrollPositions[folder]

    fun clearScrollPosition(folder: String) {
        _scrollPositions.remove(folder)
    }

    val hasConfig: StateFlow<Boolean> = syncRelationRepository.getAllWithSourceFlow()
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Lazily, false)

    /**
     * Coarse onboarding state shown as a guidance card on the browser root when the
     * library is empty. Combines the raw repository Flows (not the [hasConfig]
     * StateFlow) so the initial value stays null until Room has emitted at least
     * once for all three signals — this prevents a one-frame NEEDS_SOURCE flash
     * for users who already have media.
     *
     * Null until first emission; the UI treats null as "do not show card".
     */
    val firstRunState: StateFlow<FirstRunState?> = combine(
        syncSourceRepository.getAllFlow(),
        syncRelationRepository.getAllWithSourceFlow(),
        videoRepository.hasAnyItemsFlow()
    ) { sources, relations, hasMedia ->
        deriveFirstRunState(
            hasSource = sources.isNotEmpty(),
            hasRelation = relations.isNotEmpty(),
            hasMedia = hasMedia
        )
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    /** Whether the user has dismissed the first-run card. Persisted across launches. */
    val onboardingDismissed: StateFlow<Boolean> = userPrefs.onboardingDismissed
        .stateIn(viewModelScope, SharingStarted.Lazily, false)

    /** Persist the dismissal so the card does not reappear on next launch. */
    fun dismissOnboarding() {
        viewModelScope.launch {
            try {
                userPrefs.setOnboardingDismissed(true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist onboarding dismissal", e)
            }
        }
    }

    /** Reset the dismissal flag so the card can be re-shown from a settings entry. */
    fun resetOnboardingDismissed() {
        viewModelScope.launch {
            try {
                userPrefs.setOnboardingDismissed(false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reset onboarding dismissal", e)
            }
        }
    }

    // === Content setters, delegated to the coordinator (state update + preference persist) ===

    fun setFolder(folder: String) = coordinator.setFolder(folder)

    fun navigateUp(): Boolean = coordinator.navigateUp()

    fun setSortMode(mode: SortMode) = coordinator.setSortMode(mode)

    fun setFilter(filter: BrowseFilter) = coordinator.setFilter(filter)

    fun setViewMode(mode: ViewMode) = coordinator.setViewMode(mode)

    fun setGridColumns(columns: Int) = coordinator.setGridColumns(columns)

    // Star filter controls
    fun setStarFilterMode(mode: StarFilterMode) = coordinator.setStarFilterMode(mode)

    fun setStarFilterValue(value: Int) = coordinator.setStarFilterValue(value)

    fun setBrowseMode(mode: BrowseMode) = coordinator.setBrowseMode(mode)

    override fun updateStar(videoItemId: Long, star: Int) {
        fileOps.updateStar(videoItemId, star, viewModelScope, ::showError) { coordinator.refresh() }
    }

    override suspend fun getRecursiveFolderStats(folderPath: String): FolderStats =
        fileOps.getRecursiveFolderStats(folderPath)

    suspend fun loadSubFolders(folderPath: String): List<DirectoryNode> = coordinator.loadSubFolders(folderPath)

    override fun markFolderRead(folderPath: String, read: Boolean) {
        fileOps.markFolderRead(folderPath, read, viewModelScope, ::showError) { coordinator.refresh() }
    }

    override fun deleteFolder(path: String) {
        fileOps.deleteFolder(path, coordinator.basePathsSnapshot(), viewModelScope, ::showError) { coordinator.refresh() }
    }

    fun showError(message: String) {
        _error.update { ErrorEvent(message, errorSeq.incrementAndGet()) }
    }

    fun clearError() {
        _error.value = null
    }

    fun getFolderVideoIds(includeItem: BrowseItem? = null, includeId: Long = -1L): List<Long> {
        return browseItems.value
            .filter { !it.isDirectory && isVideoFile(it.name) }
            .mapNotNull { item ->
                when {
                    item.videoItemId != null -> item.videoItemId
                    includeItem != null && item.relativePath == includeItem.relativePath && includeId > 0L -> includeId
                    else -> null
                }
            }
    }

    fun getFolderImageIds(includeItem: BrowseItem? = null, includeId: Long = -1L): List<Long> {
        return browseItems.value
            .filter { !it.isDirectory && isImageFile(it.name) }
            .mapNotNull { item ->
                when {
                    item.videoItemId != null -> item.videoItemId
                    includeItem != null && item.relativePath == includeItem.relativePath && includeId > 0L -> includeId
                    else -> null
                }
            }
    }

    override fun toggleTrashed(videoItemId: Long) {
        undoDeleteUseCase.performTrashToggle(videoItemId, coordinator.basePathsSnapshot(), viewModelScope, ::showError)
    }

    /**
     * Trash-toggle with an [onSuccess] callback invoked ONLY after the use-case has performed
     * the toggle and recorded undo state. The caller (BrowserScreen) uses this to defer showing
     * the undo Snackbar until the async toggle actually succeeds — so a failed toggle never
     * produces a misleading "moved to trash" Snackbar. On failure the error lands in [_error].
     */
    fun toggleTrashedWithUndo(
        videoItemId: Long,
        onFailure: () -> Unit = {},
        onSuccess: () -> Unit,
    ) {
        undoDeleteUseCase.performTrashToggle(
            videoItemId,
            coordinator.basePathsSnapshot(),
            viewModelScope,
            onError = {
                showError(it)
                onFailure()
            },
            onSuccess = onSuccess,
        )
    }

    override fun isFileInBaseDir(relativePath: String): Boolean {
        return fileOps.isFileInBaseDir(relativePath, coordinator.basePathsSnapshot())
    }

    override fun deleteFile(videoItemId: Long) {
        undoDeleteUseCase.performHardDelete(videoItemId, viewModelScope, ::showError) { coordinator.refresh() }
    }

    /**
     * Deferred hard-delete with an [onSuccess] callback invoked ONLY after the use-case has
     * recorded the pending delete and bumped the refresh trigger. The caller defers the undo
     * Snackbar until then, so a delete that fails before recording state never shows a
     * "deleted" Snackbar.
     */
    fun deleteFileWithUndo(
        videoItemId: Long,
        onSuccess: () -> Unit,
        onFailure: () -> Unit = {}
    ) {
        undoDeleteUseCase.performHardDelete(
            videoItemId,
            viewModelScope,
            onError = {
                showError(it)
                onFailure()
            }
        ) {
            coordinator.refresh()
            onSuccess()
        }
    }

    /**
     * Undo the most recent delete operation (trash toggle or deferred hard delete).
     * @param expectedId If provided, only undo if the current pending operation matches this id.
     * Returns the result so the caller can show appropriate feedback.
     */
    suspend fun undoDelete(expectedId: Long? = null): UndoResult = undoDeleteUseCase.undo(expectedId)

    /**
     * Commit the deferred hard delete when the undo snackbar expires without action.
     *
     * If the commit fails, the [UndoResult.Failed] is mapped to a localized message in [_error]
     * (surfaced via the error Snackbar) — a failed commit must never be silently swallowed.
     *
     * @param expectedId If provided, only commit if the current pending operation matches this id.
     * No-op if the last operation was a trash toggle (already applied).
     */
    suspend fun commitDeferredDelete(expectedId: Long? = null): UndoResult {
        val result = undoDeleteUseCase.commitDeferredDelete(expectedId)
        if (result is UndoResult.Failed) {
            showError(syncErrorMessage(result.error, context))
        }
        return result
    }

    /** Whether [id] is an uncertain hard-delete debt that can only be safely drained. */
    fun hasCommittingDelete(id: Long): Boolean = undoDeleteUseCase.hasCommittingDelete(id)

    /** Current pending operation id, including an uncertain hard-delete debt. */
    fun getPendingHardDeleteId(): Long? = undoDeleteUseCase.getPendingHardDeleteId()

    /**
     * Page-leave safety commit: fire-and-forget on the use-case's own safety scope, so a
     * deferred hard delete is never silently lost when the BrowserScreen composition disposes
     * (snackbar cancelled / navigation away) before the snackbar's Dismissed branch ran.
     *
     * [expectedId] scopes the commit to the specific pending delete observed at dispose time,
     * so a stale dispose cannot commit a newer operation.
     */
    fun commitDeferredDeleteSafely(expectedId: Long) {
        undoDeleteUseCase.commitPendingSafely(expectedId)
    }

    override suspend fun buildShareIntent(videoItemId: Long): Intent? =
        fileOps.buildShareIntent(videoItemId)

    /**
     * True when the entire library has no media (regardless of current filter/folder).
     * Used by onboarding to avoid showing the guidance card when the current view is
     * merely filtered empty while media exists elsewhere.
     */
    val libraryEmpty: StateFlow<Boolean> = videoRepository.hasAnyItemsFlow()
        .map { !it }
        .stateIn(viewModelScope, SharingStarted.Lazily, true)

    /**
     * On ViewModel clear (page leave / fragment destroy), commit any pending deferred
     * hard delete so it is never silently lost.
     *
     * NOTE: this MUST NOT launch into [viewModelScope] — by the time `onCleared()` runs,
     * that scope is already cancelled and a launch into it is a silent no-op (the pending
     * hard delete would leak forever). [UndoDeleteUseCase.commitPendingSafely] uses its
     * own owned safety scope + [NonCancellable] so the commit runs regardless.
     */
    override fun onCleared() {
        super.onCleared()
        undoDeleteUseCase.commitPendingSafely()
    }

    companion object {
        private const val TAG = "BrowserViewModel"
    }
}
