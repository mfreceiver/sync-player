package cn.vectory.syncplayer.sync

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.vectory.syncplayer.R
import cn.vectory.syncplayer.data.model.SyncAction
import cn.vectory.syncplayer.data.model.SyncOutcome
import cn.vectory.syncplayer.data.model.SyncPhase
import cn.vectory.syncplayer.data.model.SyncProgress
import cn.vectory.syncplayer.data.model.SyncRelation
import cn.vectory.syncplayer.data.repository.SyncRelationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

// ===========================================================================
// D1（ora-2）：互斥单状态机 —— 「同步结束」不再等于「同步成功」。
//
//  - Finished 携带真实 outcome（SUCCESS/PARTIAL_FAILED/FAILED/CANCELLED），
//    进程中断等非用户取消路径一律归 FAILED 类，不伪装成取消或成功。
//  - 计划单次消费：进入执行（Submitting）时 Ready 计划同步消费，之后绝不
//    回退 Ready；Submitting 任何失败（relation 缺失 / setPendingTask 抛错 /
//    服务启动抛错）都进入 Finished(FAILED)。pending 已写但服务未启动时
//    尽力 consumePendingTask 回收；回收失败不掩盖为可重放，恢复一律重新扫描。
//  - 跨操作失效：generation 代次先于 cancel 递增；扫描返回/异常/finally 均
//    校验 requestId + 模式快照 + 代次；旧 activeRunId 在新操作前清零。
//  - 高频进度只走独立 progress 流（下方透传引擎 StateFlow），不进主状态。
//  - runId/planId/requestId 均为本页面实例内进程内标识，不持久化，也不随
//    SyncService.start 传递 —— 详见 observeSyncCompletion 的残留边界注释。
//  - 兼容投影：isSyncing/syncComplete/toDelete 等旧只读属性保留为状态机
//    的派生值（域外 StaleTerminalTest 锁定），不再是独立可写标志。
//
// 线程约束：所有 public 入口与状态写均发生在主线程（ViewModel 契约 +
// viewModelScope 默认 Main）；「校验 → 迁移」处于同一主线程同步段，天然原子，
// 无需额外线程锁（AtomicLong 仅用于跨线程安全的序号分配，不承担组合原子性）。
// ===========================================================================

/** 扫描失败的类型化问题（Screen 按 kind 映射文案，绝不透出底层 message）。 */
enum class SyncPreviewIssue { AUTH, PATH, NETWORK, SCAN_INCOMPLETE, STORAGE, UNKNOWN }

/** 同步运行终态。 */
enum class SyncRunOutcome { SUCCESS, PARTIAL_FAILED, FAILED, CANCELLED }

/** 终态携带的最小结果信息。 */
data class SyncRunResult(
    val runId: Long,
    val outcome: SyncRunOutcome,
    val completedFiles: Int = 0,
    val failedFiles: Int = 0
)

/** 页面互斥单状态。 */
sealed interface SyncPreviewState {
    data object Idle : SyncPreviewState
    data class Scanning(val requestId: Long) : SyncPreviewState
    data class ScanFailed(val issue: Issue) : SyncPreviewState
    data class Ready(val planId: Long) : SyncPreviewState
    data object NoChanges : SyncPreviewState
    data class Submitting(val planId: Long, val mode: SyncMode) : SyncPreviewState
    data class Running(val runId: Long, val mode: SyncMode) : SyncPreviewState
    data class Cancelling(val runId: Long, val mode: SyncMode) : SyncPreviewState
    data class Finished(val result: SyncRunResult) : SyncPreviewState

    /** 类型化扫描问题（含原始 code 映射来源，禁止直接展示 message）。 */
    data class Issue(val kind: SyncPreviewIssue, val code: SyncErrorCode?)
}

@HiltViewModel
class SyncPreviewViewModel @Inject constructor(
    private val syncEngine: SyncEngine,
    private val syncRelationRepository: SyncRelationRepository,
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val relationId: Long = savedStateHandle.get<Long>("relationId") ?: 0L

    private val _allRelations = MutableStateFlow<List<SyncRelation>>(emptyList())
    val allRelations = _allRelations.asStateFlow()

    private val _currentRelationName = MutableStateFlow("")
    val currentRelationName = _currentRelationName.asStateFlow()

    private val _state = MutableStateFlow<SyncPreviewState>(SyncPreviewState.Idle)
    val state: StateFlow<SyncPreviewState> = _state.asStateFlow()

    /** 瞬态错误横幅（可 dismiss）；dismiss 绝不改变状态机或复活旧计划。 */
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    /**
     * 含删除项计划的执行确认门（两模式均适用）。
     * 保存待确认的 planId —— 确认时要求仍与当前 Ready.planId 一致；
     * 扫描/切模式/计划消费/进入终态时清空，旧确认不可能落到新计划上。
     */
    private val _pendingExecuteConfirm = MutableStateFlow<Long?>(null)
    val pendingExecuteConfirm = _pendingExecuteConfirm.asStateFlow()

    private val initialTab: String = savedStateHandle.get<String>("initialTab") ?: "download"

    private val _syncMode = MutableStateFlow(
        if (initialTab == "delete") SyncMode.DELETE else SyncMode.DOWNLOAD
    )
    val syncMode = _syncMode.asStateFlow()

    /** 进程内标识（本地计数器，仅页面实例 token，不发送给 SyncService）。 */
    private val requestIdSeq = AtomicLong(0L)
    private val planIdSeq = AtomicLong(0L)
    private val runIdSeq = AtomicLong(0L)

    /**
     * 失效代次：任何会作废旧异步工作的操作（切模式/新扫描/取消扫描）先递增它
     * 再取消 job；晚回的扫描结果按「代次 + 模式快照 + requestId」判定丢弃。
     */
    private val generation = AtomicLong(0L)

    private var scanJob: Job? = null
    private var deleteJob: Job? = null
    private var activeRunId: Long = 0L

    /**
     * 「取消请求已记录、停止尚未发出」的 runId（0 = 无待发停止）。
     * 与「停止已发送」分离：服务启动窗口（start 已返回但 progress 尚未发布
     * 运行）内 cancel 时，progress 未运行不能立即 stop —— 意图记录在此，
     * 由观察器在该 run 后续 progress 首次显示本 relation 运行时补发恰一次；
     * 终态到达或 run 以 FAILED 收场则取消补发需求。绝不向其他 relation
     * 无条件全局 stop。
     */
    private var stopRequestedRunId: Long = 0L

    /** 若存在待发停止且 progress 已确认本 relation（或未归属）在运行，发送恰一次 stop。 */
    private fun trySendStopIfRunning() {
        if (stopRequestedRunId == 0L) return
        val p = syncEngine.progress.value
        if (p.isRunning && (p.relationId == relationId || p.relationId == 0L)) {
            SyncService.stop(context)
            stopRequestedRunId = 0L
        }
    }

    /** 高频进度：独立 StateFlow 透传，绝不并入 [_state]。 */
    val progress: StateFlow<SyncProgress> get() = syncEngine.progress

    // ------------------------------------------------------------------
    // 兼容只读投影（派生自状态机；域外 StaleTerminalTest / F-2-2 锁定）。
    // toDownload/toDelete 仅反映当前 Ready 计划；消费/终态后为空，
    // 旧计划不可能通过这两个投影被重新启用。
    // ------------------------------------------------------------------

    private var currentPlan: Plan? = null

    data class Plan(
        val id: Long,
        val mode: SyncMode,
        val downloads: List<SyncAction>,
        val deletions: List<SyncAction>
    )

    val toDownload: StateFlow<List<SyncAction>> = _state
        .map { s -> (s as? SyncPreviewState.Ready)
            ?.let { r -> currentPlan?.takeIf { it.id == r.planId }?.downloads } ?: emptyList() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val toDelete: StateFlow<List<SyncAction>> = _state
        .map { s -> (s as? SyncPreviewState.Ready)
            ?.let { r -> currentPlan?.takeIf { it.id == r.planId }?.deletions } ?: emptyList() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val isLoading: StateFlow<Boolean> = _state
        .map { it is SyncPreviewState.Scanning }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val isSyncing: StateFlow<Boolean> = _state
        .map { s ->
            (s is SyncPreviewState.Running && s.mode == SyncMode.DOWNLOAD) ||
                (s is SyncPreviewState.Cancelling && s.mode == SyncMode.DOWNLOAD)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val isDeleting: StateFlow<Boolean> = _state
        .map { s ->
            (s is SyncPreviewState.Running && s.mode == SyncMode.DELETE) ||
                (s is SyncPreviewState.Cancelling && s.mode == SyncMode.DELETE)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val previewLoaded: StateFlow<Boolean> = _state
        .map { s ->
            s is SyncPreviewState.Ready || s is SyncPreviewState.NoChanges ||
                s is SyncPreviewState.Submitting || s is SyncPreviewState.Running ||
                s is SyncPreviewState.Cancelling || s is SyncPreviewState.Finished
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 形状投影：任何终态都为 true（含失败/取消）。 */
    val isFinished: StateFlow<Boolean> = _state
        .map { it is SyncPreviewState.Finished }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * 兼容投影：仅 Finished(SUCCESS) 为 true。名「complete」实为「成功」，
     * 切勿当「运行已结束」使用。不要以 [isFinished] 机械替换：它对失败/取消
     * 终态同样为 true，会把失败当成功；成败语义请读 `state.value` 中
     * Finished 的 outcome。
     * 域外仅 StaleTerminalTest 消费，其锁定 COMPLETED → syncComplete = true。
     */
    @Deprecated(
        "Shape kept only for StaleTerminalTest: true means Finished(SUCCESS), not 'run ended'. " +
            "Do NOT substitute with isFinished — that includes failures and cancellations."
    )
    val syncComplete: StateFlow<Boolean> = _state
        .map { s -> (s as? SyncPreviewState.Finished)?.result?.outcome == SyncRunOutcome.SUCCESS }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val totalDownloadSize: Long get() = currentPlan?.downloads?.sumOf { it.fileSize } ?: 0L
    val totalDeleteSize: Long get() = currentPlan?.deletions?.sumOf { it.fileSize } ?: 0L

    init {
        val currentProgress = syncEngine.progress.value
        if (currentProgress.isRunning && currentProgress.relationId == relationId) {
            // 页面重建：按新 runId 重新连接当前运行（进程重启无法核实 → 用户重新扫描）。
            activeRunId = runIdSeq.incrementAndGet()
            _state.value = SyncPreviewState.Running(activeRunId, SyncMode.DOWNLOAD)
            observeSyncCompletion(activeRunId)
        }
        viewModelScope.launch {
            try {
                _allRelations.value = syncRelationRepository.getAllWithSource().map { it.relation }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = syncErrorMessage(e, context)
            }
        }
        viewModelScope.launch {
            try {
                val relation = syncRelationRepository.getById(relationId)
                _currentRelationName.value = relation?.name ?: ""
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = syncErrorMessage(e, context)
            }
        }
    }

    /**
     * 失效旧异步工作：先递增代次（晚回结果自证过期），再取消 job、清旧 run 归属。
     * 必须在更新 _syncMode 之前调用 —— 被切掉的扫描仍以旧模式快照判定有效性。
     */
    private fun invalidateInFlight() {
        generation.incrementAndGet()
        scanJob?.cancel()
        scanJob = null
        activeRunId = 0L
        currentPlan = null
        _pendingExecuteConfirm.value = null
    }

    /**
     * 切模式守卫：Submitting/Running/Cancelling 一律拒绝（UI 同时禁用 Tab，双守）；
     * 点当前 Tab 为 no-op；Scanning 允许切换并作废在途扫描。
     */
    fun setSyncMode(mode: SyncMode) {
        val s = _state.value
        if (mode == _syncMode.value) return
        when (s) {
            is SyncPreviewState.Submitting,
            is SyncPreviewState.Running,
            is SyncPreviewState.Cancelling -> return
            else -> Unit
        }
        invalidateInFlight()
        deleteJob?.cancel()
        deleteJob = null
        _syncMode.value = mode
        _error.value = null
        _state.value = SyncPreviewState.Idle
    }

    /**
     * 重新预览守卫：Submitting/Running/Cancelling 拒绝；Scanning 去重；
     * 其余状态（含 Finished/ScanFailed）允许，并先作废上一轮的扫描/run 观察。
     */
    fun loadPreview() {
        val s = _state.value
        if (s is SyncPreviewState.Scanning || s is SyncPreviewState.Submitting ||
            s is SyncPreviewState.Running || s is SyncPreviewState.Cancelling
        ) return
        // AND-083：dryRun/setPendingTask 在运行中的同步持锁期间会无限 park，
        // 且 resetProgress 会破坏运行中任务的进度——忙时直接拒绝预览。
        if (syncEngine.progress.value.isRunning) {
            _error.value = context.getString(R.string.sync_preview_busy)
            return
        }
        invalidateInFlight()
        val requestId = requestIdSeq.incrementAndGet()
        val modeSnapshot = _syncMode.value
        val gen = generation.get()
        _state.value = SyncPreviewState.Scanning(requestId)
        _error.value = null
        scanJob = viewModelScope.launch {
            try {
                syncEngine.resetProgress()
                if (modeSnapshot == SyncMode.DELETE) {
                    val (actions, errMsg) = syncEngine.dryRunDelete(relationId)
                    if (!isScanCurrent(requestId, modeSnapshot, gen)) return@launch
                    if (errMsg != null) {
                        _state.value = SyncPreviewState.ScanFailed(
                            SyncPreviewState.Issue(SyncPreviewIssue.UNKNOWN, null)
                        )
                    } else if (actions.isEmpty()) {
                        // F02 预览语义：成功扫描且确实无动作 → SUCCESS；失败绝不写成功。
                        syncRelationRepository.updateLastSync(relationId, SyncOutcome(SyncOutcome.Status.SUCCESS))
                        // 第二挂起点：updateLastSync 挂起期间代次/模式可能已失效，
                        // 非合作晚回必须重校验，不得覆盖新状态。
                        if (!isScanCurrent(requestId, modeSnapshot, gen)) return@launch
                        _state.value = SyncPreviewState.NoChanges
                    } else {
                        val plan = Plan(planIdSeq.incrementAndGet(), SyncMode.DELETE, emptyList(), actions)
                        currentPlan = plan
                        _state.value = SyncPreviewState.Ready(plan.id)
                    }
                } else {
                    val result = syncEngine.dryRun(relationId)
                    if (!isScanCurrent(requestId, modeSnapshot, gen)) return@launch
                    if (result.error != null) {
                        _state.value = SyncPreviewState.ScanFailed(
                            SyncPreviewState.Issue(result.error.code.toPreviewIssue(), result.error.code)
                        )
                    } else if (result.toDownload.isEmpty() && result.toDelete.isEmpty()) {
                        syncRelationRepository.updateLastSync(relationId, SyncOutcome(SyncOutcome.Status.SUCCESS))
                        // 同上：第二挂起点返回后重校验，失效不写 NoChanges。
                        if (!isScanCurrent(requestId, modeSnapshot, gen)) return@launch
                        _state.value = SyncPreviewState.NoChanges
                    } else {
                        val plan = Plan(
                            planIdSeq.incrementAndGet(), SyncMode.DOWNLOAD,
                            result.toDownload, result.toDelete
                        )
                        currentPlan = plan
                        _state.value = SyncPreviewState.Ready(plan.id)
                    }
                }
            } catch (e: CancellationException) {
                // 扫描被取消：仅当页面仍是本次 Scanning 时回到 Idle（已被代次/模式
                // 失效的晚回绝不写状态），不产生 ScanFailed/NoChanges/Ready。
                if (_state.value is SyncPreviewState.Scanning &&
                    (_state.value as SyncPreviewState.Scanning).requestId == requestId
                ) {
                    _state.value = SyncPreviewState.Idle
                }
                throw e
            } catch (e: Exception) {
                if (isScanCurrent(requestId, modeSnapshot, gen)) {
                    _state.value = SyncPreviewState.ScanFailed(
                        SyncPreviewState.Issue(e.toPreviewIssue(), (e as? SyncException)?.code)
                    )
                }
            } catch (e: Throwable) {
                // H1（闪退最小修复集）：非 Exception 的 Throwable（OOM/LinkageError 等）
                // 兜底。机制更正（P2-6）：无兜底时未捕获 Error 并不会被 SupervisorJob
                // 「吞掉后静默」——它经默认 CoroutineExceptionHandler 交线程
                // uncaughtExceptionHandler 直接杀进程。兜底转 ScanFailed(UNKNOWN)
                // （结构化，不读 Throwable.message）才能既保进程又让状态离开 Scanning。
                if (isScanCurrent(requestId, modeSnapshot, gen)) {
                    _state.value = SyncPreviewState.ScanFailed(
                        SyncPreviewState.Issue(SyncPreviewIssue.UNKNOWN, null)
                    )
                }
            } finally {
                // 防御兜底：非当前扫描的晚回若把状态遗留在本 requestId 的
                // Scanning（理论上不应发生），回 Idle 而不是永远转圈。
                val st = _state.value
                if (st is SyncPreviewState.Scanning && st.requestId == requestId &&
                    !isScanCurrent(requestId, modeSnapshot, gen)
                ) {
                    _state.value = SyncPreviewState.Idle
                }
            }
        }
    }

    /** 本扫描仍是当前有效工作：requestId 命中当前状态、代次与模式快照未变。 */
    private fun isScanCurrent(requestId: Long, mode: SyncMode, gen: Long): Boolean {
        val s = _state.value
        return s is SyncPreviewState.Scanning && s.requestId == requestId &&
            generation.get() == gen && _syncMode.value == mode
    }

    /**
     * 消费当前 Ready 计划（同步执行段，主线程约束保证原子）。
     * 返回已消费的计划；非 Ready / planId 不匹配 / 有其它任务在跑 → null。
     * 一旦成功返回，该计划不可再被任何入口消费 —— 提交失败进 Finished 而非回 Ready。
     */
    private fun consumeReadyPlan(): Plan? {
        val s = _state.value as? SyncPreviewState.Ready ?: return null
        val plan = currentPlan?.takeIf { it.id == s.planId } ?: return null
        // D1 运行归属守卫：引擎全局任务非本页面可寻址（本地 runId 仅为页面 token），
        // 有其它任务在跑时拒绝提交而不是带病进入 Running。
        val p = syncEngine.progress.value
        if (p.isRunning && p.relationId != 0L && p.relationId != relationId) {
            _error.value = context.getString(R.string.sync_preview_another_sync_running)
            _pendingExecuteConfirm.value = null
            return null
        }
        currentPlan = null
        _pendingExecuteConfirm.value = null
        _state.value = SyncPreviewState.Submitting(plan.id, plan.mode)
        return plan
    }

    /**
     * 执行入口（两模式统一）。含删除项的计划必须经确认门控；
     * 仅下载项直通执行。非 Ready 状态一律 no-op（计划单次消费 + 终态不可复用）。
     */
    fun requestExecute() {
        val s = _state.value as? SyncPreviewState.Ready ?: return
        val plan = currentPlan?.takeIf { it.id == s.planId } ?: return
        if (plan.deletions.isNotEmpty()) {
            _pendingExecuteConfirm.value = plan.id
        } else {
            performExecute()
        }
    }

    /** 确认门：仅确认当前仍 Ready 的同一份计划；过期确认静默丢弃。 */
    fun confirmExecute() {
        val confirmPlanId = _pendingExecuteConfirm.value ?: return
        _pendingExecuteConfirm.value = null
        val s = _state.value as? SyncPreviewState.Ready ?: return
        if (s.planId != confirmPlanId) return
        performExecute()
    }

    fun dismissExecuteConfirm() {
        _pendingExecuteConfirm.value = null
    }

    /**
     * 提交已确认的执行请求：先同步消费 Ready（消费点即 Submitting），
     * 之后任何失败都只能进 Finished —— 绝不回退 Ready 重放旧计划。
     */
    private fun performExecute() {
        val plan = consumeReadyPlan() ?: return
        if (plan.mode == SyncMode.DOWNLOAD) {
            submitDownloadPlan(plan)
        } else {
            startDeleteInternal(plan)
        }
    }

    /**
     * 兼容入口（域外 StaleTerminalTest 调用）。与生产入口完全一致：委托
     * [requestExecute] —— 含删除项计划一律过 [pendingExecuteConfirm] 确认门
     * （Screen 两模式统一渲染确认对话框），纯下载计划直通执行。
     * 不存在任何测试专用豁免例外；无论哪条路径，计划都会被同步消费，
     * 失败进终态而非回 Ready。
     */
    fun startSync() {
        requestExecute()
    }

    /**
     * 兼容入口（域外调用）。恢复门控（主审裁定）：与生产入口一致，委托
     * [requestExecute] —— 含删除项计划一律过 [pendingExecuteConfirm] 确认门。
     */
    fun startDelete() {
        requestExecute()
    }

    /**
     * 提交下载/服务执行计划。计划已消费（状态已迁 Submitting），
     * 任何失败 → Finished(FAILED)，绝不回 Ready：
     *  - relation 查询失败/null → FAILED；
     *  - setPendingTask 抛错 → FAILED（pending 未落库，无需清理）；
     *  - SyncService.start 抛错（pending 已写、部分提交）→ 尽力
     *    consumePendingTask 回收后 FAILED；回收失败意味着任务可能仍被
     *    后续 drain 执行 —— 如实记录为 FAILED 终态 + 错误横幅，绝不以
     *    「回 Ready」掩盖部分提交。
     */
    private fun submitDownloadPlan(plan: Plan) {
        viewModelScope.launch {
            val runId = runIdSeq.incrementAndGet()
            var pendingWritten = false
            try {
                if (!syncEngine.progress.value.isRunning) {
                    syncEngine.resetProgress()
                }
                val relation = syncRelationRepository.getById(relationId)
                    ?: throw SyncException(SyncErrorCode.DATABASE_ERROR, SyncOperation.DOWNLOAD)
                syncEngine.setPendingTask(PendingSyncTask(
                    relationId = relationId,
                    relationName = relation.name,
                    localBasePath = relation.localBasePath,
                    toDownload = plan.downloads,
                    toDelete = plan.deletions
                ))
                pendingWritten = true
                activeRunId = runId
                _error.value = null
                SyncService.start(context, relationId)
                if (activeRunId != runId) return@launch // 已被后续操作作废
                _state.value = SyncPreviewState.Running(runId, SyncMode.DOWNLOAD)
                observeSyncCompletion(runId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (pendingWritten) {
                    // 尽力回收未被执行的 pending 任务；回收失败 = 该任务可能
                    // 仍会被服务 drain 消费，只能如实记 FAILED + 错误横幅。
                    try {
                        syncEngine.consumePendingTask(relationId)
                    } catch (cleanup: CancellationException) {
                        throw cleanup
                    } catch (cleanup: Exception) {
                        // 清理失败不改变终态判定。
                    }
                }
                _error.value = syncErrorMessage(e, context)
                if (activeRunId == runId) activeRunId = 0L
                if (stopRequestedRunId == runId) {
                    // run 以 FAILED 收场：无运行可停，补发需求取消。
                    stopRequestedRunId = 0L
                }
                _state.value = SyncPreviewState.Finished(
                    SyncRunResult(runId = runId, outcome = SyncRunOutcome.FAILED)
                )
            }
        }
    }

    /**
     * 观察运行终态 —— 中断感知版：
     *  - 四个明确终态照旧映射为对应 outcome；
     *  - 本 relation 的运行中观察到「isRunning=false 且非终态」的新发射 →
     *    进程中断/服务消亡等路径，归 FAILED（绝不伪装取消或成功）；
     *  - 订阅瞬间快照的逐条重放豁免该规则（订阅即未运行且非终态 = 从未见到
     *    运行迹象，不裁决）；
     *  - isStaleTerminal + drop(1)：与旧实现一致地丢弃订阅前遗留的陈旧终态。
     *
     * 残留边界（本批不消除）：runId 不持久化也不发送给 SyncService，
     * cancelSync 的 stop 是全局 ACTION_CANCEL（仅在 progress 显示本 relation
     * 运行时发送；启动窗口内的取消经 stopRequestedRunId 由本观察器补发）。
     * 进程重启后无法核实旧运行 → 只能提示重新扫描；若服务在
     * 订阅后从未发射任何条目便死亡，本观察无法探测，仍悬挂等待后续发射。
     */
    private fun observeSyncCompletion(runId: Long) {
        viewModelScope.launch {
            try {
                val snapshot = syncEngine.progress.value
                val isStaleTerminal = (snapshot.relationId == relationId || snapshot.relationId == 0L) &&
                    snapshot.phase.let {
                        it == SyncPhase.COMPLETED || it == SyncPhase.PARTIAL_FAILED ||
                            it == SyncPhase.FAILED || it == SyncPhase.CANCELLED
                    }
                // CQ-002: 与旧实现一致的陈旧终态丢弃 —— 订阅前无挂起点，
                // 此处若见终态必为上一轮残留，drop(1) 忽略以等待本轮真实终态。
                val flow = if (isStaleTerminal) syncEngine.progress.drop(1) else syncEngine.progress
                var firstEmission = true
                val result = flow.first { p ->
                    val ours = p.relationId == relationId || p.relationId == 0L
                    // 启动窗口补发：cancel 时服务可能尚未发布运行进度，此处
                    // 在 progress 首次确认本 relation 运行时补发恰一次 stop
                    //（trySendStopIfRunning 内部幂等：已发过即 no-op）。
                    if (ours && p.isRunning) trySendStopIfRunning()
                    val isTerminal = p.phase == SyncPhase.COMPLETED ||
                        p.phase == SyncPhase.PARTIAL_FAILED ||
                        p.phase == SyncPhase.FAILED || p.phase == SyncPhase.CANCELLED
                    val interrupted = ours && !p.isRunning && !isTerminal &&
                        !(firstEmission && p == snapshot)
                    firstEmission = false
                    ours && (isTerminal || interrupted)
                }
                if (stopRequestedRunId == runId) {
                    // 终态已到达：取消补发需求。
                    stopRequestedRunId = 0L
                }
                if (activeRunId != runId) return@launch // 只接受当前 runId 的结果
                activeRunId = 0L
                _state.value = SyncPreviewState.Finished(
                    SyncRunResult(
                        runId = runId,
                        outcome = when (result.phase) {
                            SyncPhase.COMPLETED -> SyncRunOutcome.SUCCESS
                            SyncPhase.PARTIAL_FAILED -> SyncRunOutcome.PARTIAL_FAILED
                            SyncPhase.FAILED -> SyncRunOutcome.FAILED
                            SyncPhase.CANCELLED -> SyncRunOutcome.CANCELLED
                            else -> SyncRunOutcome.FAILED // 进程中断：非终态的运行停止
                        },
                        completedFiles = result.completedFiles,
                        failedFiles = result.failedFiles
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = syncErrorMessage(e, context)
                // 观察失败（服务死亡等）不能永久卡在 Running：归 FAILED 类终态。
                if (activeRunId == runId) {
                    activeRunId = 0L
                    if (stopRequestedRunId == runId) {
                        // run 以 FAILED 收场：补发需求取消。
                        stopRequestedRunId = 0L
                    }
                    _state.value = SyncPreviewState.Finished(
                        SyncRunResult(runId = runId, outcome = SyncRunOutcome.FAILED)
                    )
                }
            }
        }
    }

    /** 提交删除计划（已消费 → Submitting(DELETE)）；executeDelete 由本页直接驱动。 */
    private fun startDeleteInternal(plan: Plan) {
        deleteJob = viewModelScope.launch {
            val runId = runIdSeq.incrementAndGet()
            activeRunId = runId
            _state.value = SyncPreviewState.Running(runId, SyncMode.DELETE)
            _error.value = null
            try {
                val success = syncEngine.executeDelete(relationId, plan.deletions)
                if (activeRunId != runId) return@launch
                activeRunId = 0L
                _state.value = SyncPreviewState.Finished(
                    SyncRunResult(runId = runId, outcome = if (success) SyncRunOutcome.SUCCESS else SyncRunOutcome.FAILED)
                )
            } catch (e: CancellationException) {
                // 用户停止（Cancelling）→ CANCELLED 终态；绝不当错误横幅。
                if (activeRunId == runId) {
                    activeRunId = 0L
                    _state.value = SyncPreviewState.Finished(
                        SyncRunResult(runId = runId, outcome = SyncRunOutcome.CANCELLED)
                    )
                }
                throw e
            } catch (e: Exception) {
                if (activeRunId != runId) return@launch
                activeRunId = 0L
                _error.value = syncErrorMessage(e, context)
                _state.value = SyncPreviewState.Finished(
                    SyncRunResult(runId = runId, outcome = SyncRunOutcome.FAILED)
                )
            } catch (e: Throwable) {
                // H1（闪退最小修复集）：非 Exception 的 Throwable（OOM/LinkageError 等）
                // 兜底。机制更正（P2-6）：无兜底时未捕获 Error 并不会被 SupervisorJob
                // 「吞掉后静默」——它经默认 CoroutineExceptionHandler 交线程
                // uncaughtExceptionHandler 直接杀进程。兜底归 FAILED 类终态（结构化
                // UNKNOWN 文案，不读 Throwable.message——敏感红线）才能既保进程又收敛。
                if (activeRunId != runId) return@launch
                activeRunId = 0L
                _error.value = syncErrorMessage(SyncException(SyncErrorCode.UNKNOWN, SyncOperation.DELETE), context)
                _state.value = SyncPreviewState.Finished(
                    SyncRunResult(runId = runId, outcome = SyncRunOutcome.FAILED)
                )
            }
        }
    }

    /**
     * 停止只作用于当前运行/扫描；其余状态为 no-op。
     * 注意（诚实声明）：SyncService.stop 是全局 ACTION_CANCEL，不携带 runId —
     * 本页面仅在引擎 progress 显示本 relation（或未归属）正在运行时才发送 stop；
     * 运行中进度若实际属于其它 relation，stop 不发送但状态照常进入 Cancelling，
     * 由中断兜底（非终态的运行停止 → FAILED）或后续终态收敛。
     * 严格 run-scoped 停止属于后续 SyncService 运行身份扩展批次。
     *
     * 启动窗口（start 已返回但服务尚未发布 progress）内取消：意图记入
     * [stopRequestedRunId]（幂等，重复 cancel 不重复发），progress 已确认
     * 本 relation 运行则立即发送，否则由 [observeSyncCompletion] 在后续
     * progress 首次显示本 relation 运行时补发恰一次。
     */
    fun cancelSync() {
        val s = _state.value
        when {
            s is SyncPreviewState.Scanning -> {
                // 代次先于 cancel 递增，保证扫描协程晚回的取消收尾不再写状态。
                generation.incrementAndGet()
                scanJob?.cancel()
                scanJob = null
                _state.value = SyncPreviewState.Idle
            }
            s is SyncPreviewState.Running && s.mode == SyncMode.DOWNLOAD -> {
                _state.value = SyncPreviewState.Cancelling(s.runId, SyncMode.DOWNLOAD)
                // 分离「取消请求已记录」与「停止已发送」：先记录（幂等），
                // progress 已确认本 relation 运行则立即发，否则观察器补发。
                stopRequestedRunId = s.runId
                trySendStopIfRunning()
            }
            s is SyncPreviewState.Running && s.mode == SyncMode.DELETE -> {
                _state.value = SyncPreviewState.Cancelling(s.runId, SyncMode.DELETE)
                deleteJob?.cancel()
                deleteJob = null
            }
            else -> {
                // Submitting/Cancelling 重复点击无效；Idle/Ready/NoChanges/
                // Finished/ScanFailed：无运行可停。
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    private fun SyncErrorCode.toPreviewIssue(): SyncPreviewIssue = when (this) {
        SyncErrorCode.AUTHENTICATION_FAILED, SyncErrorCode.ACCESS_DENIED -> SyncPreviewIssue.AUTH
        SyncErrorCode.PATH_NOT_FOUND -> SyncPreviewIssue.PATH
        SyncErrorCode.NETWORK_UNAVAILABLE, SyncErrorCode.UNKNOWN_HOST,
        SyncErrorCode.CONNECTION_REFUSED, SyncErrorCode.TIMEOUT, SyncErrorCode.NOT_CONNECTED -> SyncPreviewIssue.NETWORK
        SyncErrorCode.SCAN_INCOMPLETE -> SyncPreviewIssue.SCAN_INCOMPLETE
        SyncErrorCode.STORAGE_ERROR, SyncErrorCode.FINALIZE_FAILED,
        SyncErrorCode.INSUFFICIENT_SPACE, SyncErrorCode.DATABASE_ERROR -> SyncPreviewIssue.STORAGE
        else -> SyncPreviewIssue.UNKNOWN
    }

    private fun Throwable.toPreviewIssue(): SyncPreviewIssue =
        (this as? SyncException)?.code?.toPreviewIssue() ?: SyncPreviewIssue.UNKNOWN
}
