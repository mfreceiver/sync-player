package cn.vectory.syncplayer.browser

import android.content.Context
import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.vectory.syncplayer.data.model.BrowseItem
import cn.vectory.syncplayer.data.model.FolderStats
import cn.vectory.syncplayer.data.repository.SyncRelationRepository
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.sync.syncErrorMessage
import cn.vectory.syncplayer.ui.components.FileContextMenuActions
import cn.vectory.syncplayer.ui.components.FolderContextMenuActions
import cn.vectory.syncplayer.ui.util.fileNameComparator
import cn.vectory.syncplayer.ui.util.isImageFile
import cn.vectory.syncplayer.ui.util.isVideoFile
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Qualifier

/**
 * Default 计算调度器限定符：搜索的映射/路径展开/自然排序等 CPU 密集步骤
 * 从收集线程（主线程）迁移到 Default。测试注入 [kotlinx.coroutines.test.StandardTestDispatcher]
 * 以虚拟时钟控制映射跳转。
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

/** 仅服务本文件的 Hilt 绑定（写域隔离：不改动 di/DispatchersModule）。 */
@Module
@InstallIn(SingletonComponent::class)
object SearchDispatcherModule {
    @Provides
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default
}

@HiltViewModel
class SearchViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val videoRepository: VideoRepository,
    private val syncRelationRepository: SyncRelationRepository,
    private val fileOps: FileOperationsUseCase,
    private val undoDeleteUseCase: UndoDeleteUseCase,
    @ApplicationContext private val appContext: Context,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel(), FileContextMenuActions, FolderContextMenuActions {

    enum class SearchScope { ALL, FILES_ONLY, FOLDERS_ONLY }

    /** 输入身份键：query + scope + folder 三元组。任何一员变化即产生新 key。 */
    data class SearchInputKey(
        val query: String,
        val scope: SearchScope,
        val folder: String,
    )

    /**
     * 归属明确的已发布结果：只能被与其 [resultKey] 一致的输入展示/交互。
     * [generation] 单调递增，标记发布代次（过期/取消的结果不得覆盖更新的代次）。
     */
    data class DisplayedSearchResult(
        val resultKey: SearchInputKey,
        val generation: Long,
        val items: List<BrowseItem>,
    )

    /**
     * - EMPTY_QUERY：输入为空（立即进入，不查仓库）；
     * - WAITING：输入已变化但 query 防抖未触发（旧结果保留、归属旧 resultKey → stale）；
     * - LOADING：该 query 的管道已激活，当前 key 的结果尚未发布（含 scope 切换后的重映射）；
     * - READY：displayedResult.resultKey == inputKey；
     * - FAILED：该 query 的管道失败（Room 查询异常），改动 query 后恢复。
     */
    enum class SearchPhase { EMPTY_QUERY, WAITING, LOADING, READY, FAILED }

    /** 删除族操作类别（事件载荷用）。 */
    enum class SearchDeleteOperation { HARD_DELETE, TRASH_TOGGLE }

    /**
     * 删除/回收完成事件：UI 据此弹出撤销 Snackbar。[operationId] 即 undo token
     * （videoItemId），与 [UndoDeleteUseCase.undo] 的 expectedId 对齐。
     */
    data class SearchDeleteEvent(
        val operationId: Long,
        val operation: SearchDeleteOperation,
        val success: Boolean,
    )

    data class SearchState(
        val inputKey: SearchInputKey,
        val displayedResult: DisplayedSearchResult?,
        val phase: SearchPhase,
        val error: ErrorEvent?,
    ) {
        /** displayedResult 归属旧输入（inputKey != resultKey）→ UI 应禁用打开/删除/队列构造。 */
        val stale: Boolean
            get() = displayedResult != null && displayedResult.resultKey != inputKey
    }

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _searchScope = MutableStateFlow(SearchScope.ALL)
    val searchScope: StateFlow<SearchScope> = _searchScope.asStateFlow()

    val searchFolder: String = savedStateHandle.get<String>("folder") ?: ""

    private val errorSeq = AtomicLong(0L)
    private val _error = MutableStateFlow<ErrorEvent?>(null)
    val error: StateFlow<ErrorEvent?> = _error.asStateFlow()

    // --- P1b：删除/回收完成事件（UI 撤销 Snackbar 的事件源） ---
    private val _deleteEvents = MutableSharedFlow<SearchDeleteEvent>(
        extraBufferCapacity = DELETE_EVENT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * 删除/垃圾桶切换完成事件（P1c rev-1 文档化）：
     * **尽力通知，非可靠队列**——SharedFlow 采用 DROP_OLDEST，短时间高频删除时
     * 旧事件可能被覆盖丢弃，UI 不得依赖事件完整性做状态恢复；
     * 撤销机会（undo 窗口）的恢复以 use case 的权威 pending 状态
     * （[UndoDeleteUseCase.getPendingHardDeleteId]）为准，事件仅用于触发
     * Snackbar 展示。
     */
    val deleteEvents: SharedFlow<SearchDeleteEvent> = _deleteEvents.asSharedFlow()

    private fun emitDeleteEvent(
        videoItemId: Long,
        operation: SearchDeleteOperation,
        success: Boolean,
    ) {
        _deleteEvents.tryEmit(SearchDeleteEvent(videoItemId, operation, success))
    }

    // --- P1 状态归属内核 ---
    /** 已发布结果（唯一写入方：映射发布器，且受 mappingSeq 代次校验约束）。 */
    private val publishedResult = MutableStateFlow<DisplayedSearchResult?>(null)
    /** 当前活跃管道的防抖后 query（null = 空闲/空输入短路）。 */
    private val activePipelineQuery = MutableStateFlow<String?>(null)
    /** 失败管道的 query（FAILED 为终态，直到 query 变化）。 */
    private val failedForQuery = MutableStateFlow<String?>(null)
    /** 映射实例序号：每次映射任务启动时递增；发布前校验自身仍是最新实例。 */
    private val mappingSeq = AtomicLong(0L)
    /** 已发布结果代次（单调递增）。 */
    private val publishedGen = AtomicLong(0L)
    /**
     * 待提交硬删除的条目 id（VM 侧影子，源真相在 [UndoDeleteUseCase]）：
     * 映射时从结果流过滤，使逻辑已删除的条目在提交前即从列表消失。
     */
    private val pendingHardDeleteId = MutableStateFlow<Long?>(null)
    /** FAILED 重试触发器：自增迫使 combine→debounce 重新发射当前 query（同值 StateFlow 不重发）。 */
    private val retryToken = MutableStateFlow(0L)

    private val basePaths = syncRelationRepository.getAllWithSourceFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SHARING_STOP_TIMEOUT_MS), emptyList())

    /**
     * 路径流：节流在扩展之前（P1b rev-5——昂贵扩展每窗口至多一次执行）；
     * 冷流直接进入管道 combine（P1b rev-5——独立 stateIn 会让父路径 Room 流/扩展
     * 异常落入未处理协程异常，而非管道的 FAILED/ErrorEvent 处理）。
     */
    private val scopedFolderPaths: Flow<List<String>> =
        videoRepository.getParentPathsInFolder(searchFolder)
            .throttleLatest(ROOM_THROTTLE_MS)
            .map { parentPaths ->
                withContext(defaultDispatcher) { expandFolderPaths(parentPaths) }
            }

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    private val searchPipeline = combine(_query, retryToken) { query, _ -> query }
        .debounce(QUERY_DEBOUNCE_MS)
        .flatMapLatest { query ->
            if (query.isBlank()) {
                // 空 query 短路：不建管道、不触发 repo 查询，清空全部结果归属。
                activePipelineQuery.value = null
                failedForQuery.value = null
                publishedResult.value = null
                emptyFlow()
            } else {
                // F03 单管道化：同一防抖后的 query 捕获进整个下游——文件结果、
                // 文件夹过滤、scope 全部绑定同一个 query；flatMapLatest 天然取消
                // 旧查询管道（取消是普通控制流，不是 Error 事件）。
                // P1：scope 是 combine 的输入而非管道键——scope 切换立即重映射
                // （不等节流窗口、不重查仓库），旧结果在重映射发布前保持 stale。
                activePipelineQuery.value = query
                failedForQuery.value = null
                combine(
                    videoRepository
                        .searchByFileNameInFolder(query, searchFolder)
                        .throttleLatest(ROOM_THROTTLE_MS),
                    scopedFolderPaths,
                    _searchScope,
                    pendingHardDeleteId,
                ) { fileResults, folders, scope, pendingId ->
                    val mySeq = mappingSeq.incrementAndGet()
                    // 待提交硬删除的条目从结果流过滤（P1b rev-5——记录即从列表消失）。
                    val effectiveFiles =
                        if (pendingId == null) fileResults
                        else fileResults.filter { it.id != pendingId }
                    // 映射/自然排序在 Default：收集线程（主线程）只做发布与状态装配。
                    val mapped = withContext(defaultDispatcher) {
                        buildSearchResults(query, scope, effectiveFiles, folders)
                    }
                    // 发布前代次校验：仅最新映射实例可发布（Default 并行下旧代次
                    // 晚完成不覆盖新代次；取消路径由 flatMapLatest 的取消保证）。
                    if (mySeq == mappingSeq.get()) {
                        publishedResult.value = DisplayedSearchResult(
                            resultKey = SearchInputKey(query, scope, searchFolder),
                            generation = publishedGen.incrementAndGet(),
                            items = mapped,
                        )
                    }
                }.catch { e ->
                    // 取消旧查询不是 Error 事件；真正的管道失败进入 FAILED 终态。
                    // P1b rev-5：父路径 Room 流/路径扩展异常也汇入此处（冷流直接
                    // 进 combine，无独立 stateIn 协程），CE 照常优先传播。
                    if (e is CancellationException) throw e
                    failedForQuery.value = query
                    showError(syncErrorMessage(e, appContext))
                }
            }
        }

    /**
     * P1 搜索状态：inputKey 由（query, scope, folder）即时合成（输入变化立即反映），
     * displayedResult 由映射发布器写入（归属自身 resultKey + generation）。
     * phase 规则见 [SearchPhase]。
     */
    val searchState: StateFlow<SearchState> = combine(
        combine(_query, _searchScope) { q, s -> SearchInputKey(q, s, searchFolder) },
        publishedResult,
        activePipelineQuery,
        failedForQuery,
        _error,
    ) { inputKey, published, activeQuery, failedQuery, error ->
        val phase = when {
            inputKey.query.isBlank() -> SearchPhase.EMPTY_QUERY
            failedQuery == inputKey.query -> SearchPhase.FAILED
            published != null && published.resultKey == inputKey -> SearchPhase.READY
            activeQuery == inputKey.query -> SearchPhase.LOADING
            else -> SearchPhase.WAITING
        }
        SearchState(inputKey, published, phase, error)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(SHARING_STOP_TIMEOUT_MS),
        SearchState(
            inputKey = SearchInputKey("", SearchScope.ALL, searchFolder),
            displayedResult = null,
            phase = SearchPhase.EMPTY_QUERY,
            error = null,
        ),
    )

    /**
     * 兼容面（SearchScreen 现有消费点）：由 searchState 派生。
     * P1b rev-5：stale 结果不得经此面暴露——UI 未接 stale 感知前，
     * 旧归属条目一律不从这里流出（交互禁用由 UI 按 searchState.stale 接线）。
     */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val searchResults: StateFlow<List<BrowseItem>> = searchState
        .map { if (it.stale) emptyList() else it.displayedResult?.items.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SHARING_STOP_TIMEOUT_MS), emptyList())

    init {
        searchPipeline.launchIn(viewModelScope)
    }

    private fun buildSearchResults(
        query: String,
        scope: SearchScope,
        fileResults: List<cn.vectory.syncplayer.data.local.dao.VideoItemDao.VideoItemWithPlayback>,
        folders: List<String>
    ): List<BrowseItem> {
        val result = mutableListOf<BrowseItem>()

        if (scope != SearchScope.FILES_ONLY) {
            val matchingFolders = folders.filter { folderPath ->
                val leafName = folderPath.substringAfterLast('/')
                leafName.contains(query, ignoreCase = true)
            }.map { folderPath ->
                val leafName = folderPath.substringAfterLast('/')
                BrowseItem(
                    videoItemId = null,
                    name = leafName,
                    relativePath = folderPath,
                    isDirectory = true
                )
            }.sortedWith(compareBy(fileNameComparator) { it.name })
            result.addAll(matchingFolders)
        }

        if (scope != SearchScope.FOLDERS_ONLY) {
            val matchingFiles = fileResults.map { video ->
                BrowseItem(
                    videoItemId = video.id,
                    name = video.fileName,
                    relativePath = video.relativePath,
                    isDirectory = false,
                    fileSize = video.fileSize,
                    duration = video.duration,
                    width = video.width,
                    height = video.height,
                    bitrate = video.bitrate,
                    frameRate = video.frameRate,
                    playbackPosition = video.playbackPosition,
                    playbackDuration = video.playbackDuration,
                    isCompleted = video.isCompleted,
                    isTrashed = video.isTrashed,
                    hasGainAdjusted = video.audioGainDb != null,
                    star = video.star,
                    mediaType = video.mediaType,
                    dateTaken = video.dateTaken
                )
            }.sortedWith(compareBy(fileNameComparator) { it.name })
            result.addAll(matchingFiles)
        }

        return result
    }

    /** parentPath 前缀展开（原 scopedFolderPaths 内联逻辑，移至 Default 执行）。 */
    private fun expandFolderPaths(parentPaths: List<String>): List<String> {
        val folderSet = mutableSetOf<String>()
        for (parentPath in parentPaths) {
            if (parentPath.isEmpty()) continue
            val segments = parentPath.split("/")
            var current = ""
            for (segment in segments) {
                current = if (current.isEmpty()) segment else "$current/$segment"
                folderSet.add(current)
            }
        }
        return folderSet.toList()
    }

    fun setQuery(query: String) { _query.value = query }
    fun setSearchScope(scope: SearchScope) { _searchScope.value = scope }

    /**
     * P1c rev-1（🔴1）：同步交互有效性守卫。
     *
     * 主线程契约：所有要求有效结果的交互入口（删除族/星标/已读/队列构造）必须在
     * 主线程调用；本守卫在同一调用栈内同步读取输入源流 [_query]/[_searchScope] 与
     * 已发布结果 [publishedResult]，**不读** combine+stateIn 派生的 [searchState]——
     * 后者存在发布窗口（输入已变、派生态尚未重算），用它做守卫会让
     * 「旧 READY → setQuery → 派生态未更新」窗口内的交互被误放行。
     * 单线程主线程上三个 StateFlow 的连续读取之间无并发写，不存在多字段读窗口。
     *
     * @return 当前输入身份下仍有效的已发布结果单快照（身份与条目取自同一次
     *   [publishedResult].value 读取）；空查询/无结果/身份过期一律返回 null——
     *   要求有效结果的操作在无结果/空查询时同样必须拒绝。
     */
    private fun validPublishedSnapshot(): DisplayedSearchResult? {
        if (_query.value.isBlank()) return null
        val published = publishedResult.value ?: return null
        val identity = SearchInputKey(_query.value, _searchScope.value, searchFolder)
        return if (published.resultKey == identity) published else null
    }

    fun showError(message: String) {
        _error.update { ErrorEvent(message, errorSeq.incrementAndGet()) }
    }

    fun clearError(expectedSeq: Long? = null) {
        // AND-068: seq-guarded clear — see BrowserViewModel.clearError.
        _error.update { if (expectedSeq == null || it?.seq == expectedSeq) null else it }
    }

    fun getSearchResultVideoIds(): List<Long> {
        // P1c rev-1：同步身份守卫 + 单快照——身份与条目取自同一个 publishedResult
        // 读取（此前查派生 searchState 与读 searchResults 的双快照/派生窗口均已消除）。
        val published = validPublishedSnapshot() ?: return emptyList()
        return published.items
            .filter { !it.isDirectory && it.videoItemId != null && isVideoFile(it.name) }
            .mapNotNull { it.videoItemId }
    }

    fun getSearchResultImageIds(): List<Long> {
        val published = validPublishedSnapshot() ?: return emptyList()
        return published.items
            .filter { !it.isDirectory && it.videoItemId != null && isImageFile(it.name) }
            .mapNotNull { it.videoItemId }
    }

    override fun updateStar(videoItemId: Long, star: Int) {
        // P1c rev-1（🟠2）：无守卫入口补齐——同一同步身份守卫。
        if (validPublishedSnapshot() == null) return
        fileOps.updateStar(videoItemId, star, viewModelScope, ::showError)
    }

    override fun toggleTrashed(videoItemId: Long) {
        // P1：删除族操作统一走 UndoDeleteUseCase（token 化 undo），不直接 repository.delete。
        // P1b rev-5：stale 入口守卫——旧归属结果上的交互一律不产生副作用。
        // P1c rev-1（🔴1）：守卫改为同步身份比较（不读派生 searchState，无发布窗口）。
        if (validPublishedSnapshot() == null) return
        undoDeleteUseCase.performTrashToggle(
            videoItemId,
            basePaths.value,
            viewModelScope,
            { message ->
                emitDeleteEvent(videoItemId, SearchDeleteOperation.TRASH_TOGGLE, success = false)
                showError(message)
            },
        ) { emitDeleteEvent(videoItemId, SearchDeleteOperation.TRASH_TOGGLE, success = true) }
    }

    override fun deleteFile(videoItemId: Long) {
        // 延迟硬删除（token 化 undo）；结果列表刷新由 Room invalidation 驱动管道重映射。
        // P1b rev-5：stale 入口守卫 + 完成事件（UI 撤销 Snackbar 数据源）+
        // 记录成功后把 id 纳入 pending 过滤（逻辑删除条目即从结果流消失）。
        // P1c rev-1（🔴1）：守卫改为同步身份比较（无派生态发布窗口）。
        if (validPublishedSnapshot() == null) return
        undoDeleteUseCase.performHardDelete(
            videoItemId,
            viewModelScope,
            { message ->
                emitDeleteEvent(videoItemId, SearchDeleteOperation.HARD_DELETE, success = false)
                showError(message)
            },
        ) {
            pendingHardDeleteId.value = videoItemId
            emitDeleteEvent(videoItemId, SearchDeleteOperation.HARD_DELETE, success = true)
        }
    }

    /** P1b rev-5：FAILED 重试入口——重发射当前 inputKey 触发重查（经同一防抖管道）。 */
    fun retrySearch() {
        val state = searchState.value
        if (state.phase != SearchPhase.FAILED || state.inputKey.query.isBlank()) return
        retryToken.update { it + 1 }
    }

    /**
     * P1c rev-1（🟠4）：以 use case 的权威 pending 状态回同步 VM 影子。
     * 无条件清空会让「旧 undo/commit 迟到完成」误清新登记的 pending（旧完成
     * 把新操作的过滤一并解除，已删条目在提交前重新浮现）。改为读
     * [UndoDeleteUseCase.getPendingHardDeleteId]（唯一源真相）覆盖影子：
     * 自己的操作完成后影子随权威状态归位；迟到完成不误伤并发登记的新操作。
     */
    private suspend fun resyncPendingShadow() {
        pendingHardDeleteId.value = undoDeleteUseCase.getPendingHardDeleteId()
    }

    suspend fun undoDelete(expectedId: Long? = null): UndoResult {
        val result = undoDeleteUseCase.undo(expectedId)
        // 撤销成功 = 该操作不再待提交；影子按权威 pending 状态回同步。
        if (result == UndoResult.Success) resyncPendingShadow()
        return result
    }

    suspend fun commitDeferredDelete(expectedId: Long? = null): UndoResult {
        val result = undoDeleteUseCase.commitDeferredDelete(expectedId)
        if (result == UndoResult.Success) resyncPendingShadow()
        if (result is UndoResult.Failed) showError(syncErrorMessage(result.error, appContext))
        return result
    }

    fun commitDeferredDeleteSafely(expectedId: Long? = null) =
        undoDeleteUseCase.commitPendingSafely(expectedId)

    fun getPendingHardDeleteId(): Long? = undoDeleteUseCase.getPendingHardDeleteId()

    fun hasCommittingDelete(videoItemId: Long): Boolean =
        undoDeleteUseCase.hasCommittingDelete(videoItemId)

    override fun isFileInBaseDir(relativePath: String): Boolean {
        return fileOps.isFileInBaseDir(relativePath, basePaths.value)
    }

    /**
     * P1c rev-1（🟠2）允许策略：分享属**只读**操作，允许对当前展示条目执行，
     * 不做有效结果守卫——无数据副作用、不修改任何状态；stale 期间分享旧条目
     * 仅读取其已存在内容，不产生归属错位。写族入口（删除/星标/已读）不适用
     * 此豁免，仍走 [validPublishedSnapshot] 同步守卫。
     */
    override suspend fun buildShareIntent(videoItemId: Long): Intent? =
        fileOps.buildShareIntent(videoItemId)

    override fun markFolderRead(folderPath: String, read: Boolean) {
        // P1c rev-1（🟠2）：无守卫入口补齐——同一同步身份守卫。
        if (validPublishedSnapshot() == null) return
        fileOps.markFolderRead(folderPath, read, viewModelScope, ::showError)
    }

    override fun deleteFolder(path: String) {
        // P1b rev-5：stale 入口守卫（folder 删除族与文件删除族同规）。
        // P1c rev-1（🔴1）：守卫改为同步身份比较。
        if (validPublishedSnapshot() == null) return
        fileOps.deleteFolder(path, basePaths.value, viewModelScope, ::showError)
    }

    override suspend fun getRecursiveFolderStats(folderPath: String): FolderStats =
        fileOps.getRecursiveFolderStats(folderPath)

    override fun onCleared() {
        super.onCleared()
        // 页离开安全提交：与 BrowserViewModel 一致，防止待提交硬删除静默丢失。
        undoDeleteUseCase.commitPendingSafely()
    }

    companion object {
        private const val TAG = "SearchViewModel"

        /** query 输入防抖（保留既有 F03 行为）。 */
        private const val QUERY_DEBOUNCE_MS = 300L

        /**
         * Room 数据流节流：首值立即透传，此后进入 latest-only 窗口。
         * 与 trailing debounce 不同——持续写入每窗口必有一次发布，不会被饥饿。
         */
        private const val ROOM_THROTTLE_MS = 300L

        private const val SHARING_STOP_TIMEOUT_MS = 5000L

        /** 删除完成事件缓冲（UI 短暂离线时事件不丢，溢出丢最旧）。 */
        private const val DELETE_EVENT_BUFFER = 16
    }
}

/**
 * P1b rev-5：单订阅窗口状态机节流；P1c rev-1（🟠3）：待发缓冲有界化；
 * P1d rev-1：首值交付握手。
 *
 * - 上游仅订阅一次（本 flow{} 的 collect 是唯一订阅点；待发值经 **CONFLATED
 *   （容量 1、新值覆盖旧值）** 通道承接——下游忙（扩展/映射慢）或积压期间仅保留
 *   待发布最新快照，内存上界为常量，不随上游快照数量增长）；
 * - **首值交付握手（P1d）**：CONFLATED 的 send 永不挂起——若上游冷流在生产协程
 *   首次让出前同步发射多个值，通道只留最后一个，引导值丢失（原 UNLIMITED FIFO
 *   无此窗口但有界性代价）。修法：首值 send 后生产方挂起在 [firstDelivered]，
 *   消费方接收并发布引导值后才 complete，生产方恢复进入 conflated 覆盖缓冲。
 *   握手挂起期间上游 collect 被背压暂停属预期（Room 冷流可接受）；下游取消时
 *   CE 经 coroutineScope 取消生产方 await，两侧同为 CE 优先；上游零值完结/首值前
 *   异常时通道已关闭、生产协程已终止，ack 无人等待，不 complete 亦无泄漏；
 * - 首值（每窗口引导值）立即发射；
 * - 固定 [periodMs] 窗口内后续值只留最新，窗口关闭时补发 trailing；连续输入不重置
 *   窗口——持续 Room 写入每窗口必有一次发布，不会被 trailing debounce 饿死；
 * - 上游异常原样传播（生产者失败经 receiveCatching 的失败结果重抛，供管道
 *   FAILED/ErrorEvent 归属）；取消（CE）照常优先传播并恰好取消上游一次。
 * 可见性 internal：运算符级背压测试的直接接缝（同模块同包）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun <T> Flow<T>.throttleLatest(periodMs: Long): Flow<T> = flow {
    coroutineScope {
        val firstDelivered = CompletableDeferred<Unit>()
        val buffered = produce(capacity = Channel.CONFLATED) {
            var leading = true
            collect { value ->
                if (leading) {
                    // 首值握手：send 入槽后等消费方确认，防止同步冷流在首个
                    // 调度槽内把引导值覆盖掉。
                    leading = false
                    send(value)
                    firstDelivered.await()
                } else {
                    send(value)
                }
            }
        }
        while (true) {
            val result = buffered.receiveCatching()
            val value = result.getOrNull()
            if (value == null) {
                // 上游异常 → 原样重抛；正常完结 → 结束（ack 无人等待，无需 complete）。
                result.exceptionOrNull()?.let { throw it }
                break
            }
            emit(value)
            // 引导值已被消费方接收并发布（emit 返回）→ 放行生产方；后续值进
            // conflated 覆盖缓冲。emit 若因下游取消抛 CE，则直接向上传播。
            firstDelivered.complete(Unit)
            var latest: T? = null
            var hasLatest = false
            withTimeoutOrNull(periodMs) {
                while (true) {
                    val windowResult = buffered.receiveCatching()
                    val windowValue = windowResult.getOrNull()
                    if (windowValue == null) {
                        // 窗口内收到上游失败 → 原样重抛（供管道 FAILED 归属）；
                        // 窗口内收到上游正常完结 → 窗口仍是固定时长（固定窗口/
                        // 防饥饿语义的一部分），挂到窗口期满由 withTimeoutOrNull
                        // 统一收口。不能裸 receive()：正常完结会以
                        // ClosedReceiveChannelException（CE 子类）直接杀死管道。
                        windowResult.exceptionOrNull()?.let { throw it }
                        awaitCancellation()
                    }
                    latest = windowValue
                    hasLatest = true
                }
            }
            if (hasLatest) emit(latest!!)
        }
    }
}
