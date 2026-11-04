package cn.vectory.syncplayer.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * BUG-018（plan v3.1，已过 9.6 门禁）：服务尾部 pending race 的 drain 协调器。
 *
 * 修复的竞态：旧 `SyncService` 在 `syncJob` 收尾窗口以 `syncJob?.isActive == true`
 * 合并丢弃新 start 请求——PENDING 行滞留、UI 卡「同步中」。`SyncEngine.consumeAndExecute`
 * 的 drain 循环整个持有 `storageOperationMutex`，`setPendingTask` 的写入必然晚于循环退出
 * （锁内再查永远看不到并发入队），故唤醒原语放在服务层：本协调器。
 *
 * 设计原则（v3.1，四条）：
 *  - **① 单一串行域（confined dispatcher）**：全部可变状态（[drainJob]/[armedToken]/
 *    [armedForced]/[stopSeq]/请求缓冲）只在 [scope] 的单线程上读写。生产接线
 *    `Dispatchers.Main.immediate`——onStartCommand/onTimeout/onDestroy 回调与循环调度、
 *    停服动作天然同线程同序；[consumeAndExecute] 回调内部 `withContext(IO)` 是唯一切出点，
 *    完成后恢复回 confined。测试注入 TestDispatcher（单线程虚拟时间）→ 全交错矩阵确定性
 *    可驱动。check-then-act 段（武装判定/停服校验）无挂起点 ⇒ 构造性无 TOCTOU。
 *  - **② 到期事件不入请求队列**：[stopTimerFired] 是 confined 线程上的非挂起校验，通过即停、
 *    不通过原地丢弃。**令牌匹配是主门**——[onSyncRequested] 在入队时同步作废令牌（覆盖
 *    「请求已交付挂起中的 receiver、continuation 未运行」窗口：此刻 requests.isEmpty 为真
 *    但确有待执行请求）；`drainJob?.isActive`（循环存活）与 `requests.isEmpty`（队列空）为
 *    纵深防御。「有待执行请求」与「控制信号」分离 ⇒ pass 收尾的「队列空 ⇒ 武装」永不被
 *    stale 事件卡住 ⇒ 空闲必最终武装、服务必停。
 *  - **③ 先注册后启动**：Kotlin 先求值整个 RHS 再赋值——`launch(LAZY){...}.also { it.start() }`
 *    单表达式在 start() 执行时字段尚未写入，immediate/unconfined 下协程体内联执行会读到
 *    null/旧值漏武装。必须拆三条独立语句：`val job = launch(LAZY){...}; drainJob = job;
 *    job.start()`。
 *  - **④ 取消即弃置**：弃置契约 =「取消后不再完成该请求（不再开始执行其 pass）」。仍滞留
 *    缓冲者由 [cancelActiveDrain] 排空移除；已交付 receiver、continuation 未运行者由 job
 *    cancel 兜住（恢复即 CE、pass 不执行）。漏网计时器（已过 delay 正在排队）经令牌校验丢弃。
 *
 * 红线：CE 先传播——drainLoop 仅设普通 Exception 边界（复审阻断：普通异常终结 launch 会使
 * 已入队请求无 pass/停服状态地遗留），CE 一律先重抛、原样终结循环；[cancelActiveDrain] 只
 * cancel 不吞。失败 pass 的处置 =「上报（[onPassFailed]，不吞）+ 队列空时按无任务路径武装
 * 有界停服（原则 ②：空闲必最终武装、服务必停）；队列非空则继续 drain，由后续 pass 收尾接管」。
 * 请求队列只承载 relationId（Long）、令牌为 Long——无敏感信息。E8/卷归属/AEAD 不涉
 * （只动「谁来调用 consumeAndExecute」的调度层）。
 *
 * **回调前提（成文）**：[onEligibleStop]/[onForcedStop] 只调 stopService、不得重入本协调器
 * （接线 KDoc 明示；协调器测试 #13 锁定回调形态）。
 */
internal class SyncServiceDrainCoordinator(
    /** confined scope：生产 Main.immediate / 测试 TestDispatcher。 */
    private val scope: CoroutineScope,
    /** 执行一个关系的 drain pass；内部自行 withContext(IO)（唯一切出点）。 */
    private val consumeAndExecute: suspend (Long) -> Pair<PendingSyncTask?, Boolean>,
    /** 取消已武装的停服计时器。 */
    private val cancelStop: () -> Unit,
    /** 武装停服计时器；到期 body 必须调 [stopTimerFired]（token 原样传入）。 */
    private val armStop: (delayMs: Long, token: Long) -> Unit,
    /** ELIGIBLE 停服出口（空闲停：NO_TASK/SYNC 延迟后）——只调 stopService，不得重入协调器。 */
    private val onEligibleStop: () -> Unit,
    /** FORCED 停服出口（用户取消延迟停）——只调 stopService，不得重入协调器。 */
    private val onForcedStop: () -> Unit,
    /** !hadTask→NO_TASK；hadTask&&!isRunning→SYNC；否则 null（不武装，服务存续由引擎运行状态接管）。 */
    private val stopDelayForPass: (hadTask: Boolean) -> Long?,
    /**
     * pass 普通异常上报（复审阻断——「不吞异常」的出口）。CE 不经过此回调（先重抛）。
     * 契约：非挂起、不得重入协调器、不得抛异常（抛出由调用侧兜底吞并——上报不得反杀循环）。
     */
    private val onPassFailed: (relationId: Long, error: Exception) -> Unit = { _, _ -> },
) {
    /** 只承载工作（请求），不承载控制信号——原则 ②。 */
    private val requests = Channel<Long>(Channel.UNLIMITED)

    private var drainJob: Job? = null
    private var armedToken: Long? = null

    /** [armedToken] 的停服种类——false=ELIGIBLE（空闲停）/ true=FORCED（取消停）。 */
    private var armedForced = false

    /** ELIGIBLE/FORCED 共用单调令牌空间。 */
    private var stopSeq = 0L

    /** 注意：isActive == true ≠ idle（DRAINING 中也为 true）——调用方不得据此判定「空闲」。 */
    val isDraining: Boolean get() = drainJob?.isActive == true

    /**
     * onStartCommand 非取消分支：confined 非挂起。先作废令牌/取消计时器、再发送；
     * 循环不存活时以「先注册后启动」三语句拉起（原则 ③）。
     */
    fun onSyncRequested(relationId: Long) {
        armedToken = null // 入队前即撤销停服资格（覆盖已交付未恢复窗口——原则 ②）
        cancelStop()
        requests.trySend(relationId)
        if (drainJob?.isActive != true) {
            val job = scope.launch(start = CoroutineStart.LAZY) { drainLoop() }
            drainJob = job // 先注册：协程体首见 drainJob === myJob 即正确（原则 ③）
            job.start()
        }
    }

    /**
     * onTimeout / onDestroy / [cancelActiveDrainAndArmForcedStop] 内部：三步原子
     * （confined 域内无插入点）——cancel job（覆盖已交付未恢复者）→ best-effort 排空仍
     * 滞留缓冲者 → 作废令牌 + cancelStop。
     */
    fun cancelActiveDrain() {
        drainJob?.cancel(); drainJob = null
        while (requests.tryReceive().isSuccess) { /* best-effort 丢弃仍滞留缓冲的请求 */ }
        armedToken = null
        cancelStop()
    }

    /**
     * 用户取消 = [cancelActiveDrain] + 武装 FORCED 停服计时器（替代旧 cancelSync 的
     * Default 域 delay+stopService 块）。与 ELIGIBLE 共用令牌空间/计时器槽位/失效协议：
     * 新请求到达（[onSyncRequested] 入口）即作废 FORCED 令牌——CANCEL→start(D) 后任何
     * 漏网取消回调均失配。
     */
    fun cancelActiveDrainAndArmForcedStop(delayMs: Long) {
        cancelActiveDrain()
        val token = ++stopSeq
        armedToken = token
        armedForced = true
        armStop(delayMs, token)
    }

    /**
     * 计时器到期：confined 非挂起校验（原则 ①②）。stale → 原地丢弃，不入请求队列。
     * 条件分层：令牌匹配为主门；循环存活/队列空为纵深防御。
     */
    fun stopTimerFired(token: Long) {
        if (armedToken != token) return // 主门：令牌失配（含两类停服互相顶替后的旧令牌）⇒ 丢弃
        if (armedForced) {
            if (drainJob?.isActive == true) return // 纵深：取消后又有活跃 drain（正常序下不可达）
            armedToken = null
            onForcedStop()
        } else {
            if (drainJob?.isActive == true && requests.isEmpty) { // 纵深防御（角色见原则 ②）
                armedToken = null
                onEligibleStop() // 校验段无挂起点——检查与停服之间同线程无插入
            }
        }
    }

    private suspend fun drainLoop() {
        val myJob = coroutineContext[Job]!!
        while (true) {
            val relationId = requests.receive() // 唯一等待点
            armedToken = null // 正常协议下 onSyncRequested 已在入队时作废令牌；此处为防御保留（纵深）
            cancelStop()
            try {
                val (task, _) = consumeAndExecute(relationId) // 唯一切出点（内部 withContext(IO)）
                // 收尾段：非挂起、confined 原子。「A 检查空→B 入队→A 武装」在此不可能——
                // B 的 trySend 只能发生在本段之前（isEmpty 见到它）或之后（Req 入口即作废令牌）。
                finishPassTail(myJob, task != null)
            } catch (e: CancellationException) {
                throw e // 红线：CE 先传播——普通边界不吞取消
            } catch (e: Exception) {
                if (handlePassFailure(relationId, myJob, e)) return
            } catch (e: Throwable) {
                // H1（闪退最小修复集）：非 Exception 的 Throwable（OutOfMemoryError/
                // LinkageError/VirtualMachineError 子类等）原先直接终结 launch——已入队
                // 请求无 pass/停服状态地静默遗留；本循环跑在 Main.immediate +
                // SupervisorJob 上、无未捕获异常 handler，Error 直上还可能击穿进程。
                // 兜底：包成结构化 SyncException(UNKNOWN)（message 恒 null，cause 不落
                // 日志），与普通 Exception 同路——上报回调只取异常类名（敏感红线）→
                // 停服路径。SyncErrorCode 已有 UNKNOWN，无需新增语义码。
                // P2-5（opus 复审）：SyncOperation.SCAN 只是「最贴近的既有枚举值」——
                // 一个 drain pass 实际横跨 scan/download/delete 多操作（consumeAndExecute
                // 全链路），并非纯扫描；此处 operation 仅用于结构化归类，不承诺精确归因。
                if (handlePassFailure(
                        relationId, myJob,
                        SyncException(SyncErrorCode.UNKNOWN, SyncOperation.SCAN, cause = e)
                    )
                ) return
            }
        }
    }

    /**
     * 失败 pass 的统一处置（H1：普通 Exception 与 Throwable 兜底共用）。
     * 复审阻断（BUG-018 后续）：普通异常不得无条件终结 launch——已接收请求（如 B）
     * 会无 pass/停服状态地静默遗留。处置：上报（不吞，回调契约禁抛）→ 失败 pass
     * 的任务状态未知，但引擎执行体已随异常终结（无存活后台工作）——按无任务路径
     * 武装有界停服；队列非空则不武装，继续 drain 由后续 pass 收尾接管。若失败收尾
     * 连续武装仍失败，则撤销令牌并走停服出口，避免永久 receive。
     *
     * @return true ⇒ 循环应终止（已走停服出口）；false ⇒ 继续消费后续请求。
     */
    private suspend fun handlePassFailure(relationId: Long, myJob: Job, error: Exception): Boolean {
        try {
            onPassFailed(relationId, error)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) { /* 普通上报失败降级，不反杀循环 */ }
        try {
            finishPassTail(myJob, hadTask = false)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 二次武装失败后令牌已撤销；队列仍空时立即走既有停服出口，不能回到永久
            // receive；若同步有新请求，保留既有 drain 语义继续消费它。
            if (requests.isEmpty) {
                onEligibleStop()
                return true
            }
        }
        return false
    }

    /** pass 收尾：非挂起、confined 原子。队列空且循环身份未变时武装 ELIGIBLE 停服。 */
    private fun finishPassTail(myJob: Job, hadTask: Boolean) {
        if (drainJob === myJob && requests.isEmpty) {
            stopDelayForPass(hadTask)?.let { delayMs ->
                val token = ++stopSeq
                armedToken = token
                armedForced = false
                try {
                    armStop(delayMs, token)
                } catch (e: CancellationException) {
                    if (armedToken == token) {
                        armedToken = null
                        armedForced = false
                    }
                    throw e
                } catch (e: Exception) {
                    if (armedToken == token) {
                        armedToken = null
                        armedForced = false
                    }
                    throw e
                }
            }
        }
    }
}
