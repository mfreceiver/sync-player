package cn.vectory.syncplayer.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BUG-018（plan v3.1 §4 测试矩阵）：[SyncServiceDrainCoordinator] 的确定性交错测试。
 *
 * Harness 规则（v3.1，复审 #6——防假绿）：
 *  - **不用 advanceUntilIdle** 驱动关键时序——一律 `runCurrent()` + `advanceTimeBy(ms)` 后
 *    **必接 `runCurrent()`**；时序断言前被测对象必须处于显式确认状态（gate 完成）。
 *  - 协调器 scope 默认 `backgroundScope + StandardTestDispatcher(scheduler)`；**#16 专用
 *    UnconfinedTestDispatcher**（`job.start()` 内联执行协程体——被测注册序的暴露条件）。
 *  - 「逃过 cancelStop 的到期」一律以直接调 `coordinator.stopTimerFired(token)` 模拟
 *    （#14/#15/#20/#21/#22）；#17 的 detachOnCancel 模式走真实 delay 回调并断言回调确实触发。
 *  - 门控 fake consume：每请求配 entered/release/completed 三门；未确认 entered 不得推进时间。
 *    #22 刻意**不** runCurrent——制造「已交付 receiver、continuation 未运行」窗口即被测状态。
 *  - Mutation check 纪律：#16（注册序）/ #22（令牌作废位置）实现时须人工回退对应修复验证变红。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncServiceDrainCoordinatorTest {

    private companion object {
        // 与生产 SyncService 常量同步：NO_TASK=500 / SYNC=1500 / CANCEL(FORCED)=1000
        const val NO_TASK_DELAY_MS = 500L
        const val SYNC_DELAY_MS = 1500L
        const val FORCED_DELAY_MS = 1000L
    }

    /** 可执行 timer fake（v3.1：firedTokens 记录实际到期回报；detachOnCancel 模拟漏网计时器）。 */
    private class FakeStopScheduler(
        private val scope: CoroutineScope,
        private val onFired: (Long) -> Unit
    ) {
        var job: Job? = null
        var armCount = 0
        var cancelCount = 0
        var lastDelayMs = -1L
        val firedTokens = mutableListOf<Long>()

        /** #17 专用：true 时 cancel 与新 arm 均不再取消旧 job——旧计时器脱离句柄独立存活。 */
        var detachOnCancel = false

        fun arm(delayMs: Long, token: Long) {
            armCount++; lastDelayMs = delayMs
            if (!detachOnCancel) job?.cancel()
            job = scope.launch { delay(delayMs); firedTokens.add(token); onFired(token) }
        }

        fun cancel() {
            cancelCount++
            if (!detachOnCancel) job?.cancel()
            job = null
        }
    }

    /** 每个请求的三重门：entered（consume 入口）/ release（consume 内阻塞）/ completed（返回前）。 */
    private class Gates {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val completed = CompletableDeferred<Unit>()
    }

    private class Kit(
        val coordinator: SyncServiceDrainCoordinator,
        val timer: FakeStopScheduler,
        val consumeCalls: MutableList<Long>,
        val armedTokens: MutableList<Long>,
        val attemptedArmTokens: MutableList<Long>,
        val eligibleStopCalls: IntArray,
        val forcedStopCalls: IntArray,
        val passFailures: MutableList<Pair<Long, Throwable>>,
        private val gates: MutableMap<Long, Gates>
    ) {
        fun gate(rel: Long): Gates = gates.getOrPut(rel) { Gates() }
    }

    private fun taskFor(rel: Long) = PendingSyncTask(
        relationId = rel,
        relationName = "rel-$rel",
        localBasePath = "videos",
        toDownload = emptyList(),
        toDelete = emptyList()
    )

    /** 门控 consume：completed entered → 阻塞 release → completed completed → 返回非空 task。 */
    private suspend fun gatedConsume(rel: Long, g: Gates): Pair<PendingSyncTask?, Boolean> {
        g.entered.complete(Unit)
        g.release.await()
        g.completed.complete(Unit)
        return taskFor(rel) to true
    }

    /** 立即完成、无挂起的 consume（非门控用例；首 pass 快速完成路径）。 */
    private suspend fun immediateConsume(rel: Long, g: Gates): Pair<PendingSyncTask?, Boolean> {
        g.entered.complete(Unit)
        g.completed.complete(Unit)
        return taskFor(rel) to true
    }

    private fun kit(
        scope: CoroutineScope,
        detachOnCancel: Boolean = false,
        failFirstArmStop: Boolean = false,
        failArmStopAttempts: Int = if (failFirstArmStop) 1 else 0,
        throwCancellationFromArmStop: Boolean = false,
        onPassFailed: ((Long, Exception) -> Unit)? = null,
        consume: suspend (Long, Gates) -> Pair<PendingSyncTask?, Boolean>
    ): Kit {
        val consumeCalls = mutableListOf<Long>()
        val armedTokens = mutableListOf<Long>()
        val attemptedArmTokens = mutableListOf<Long>()
        val eligibleStopCalls = intArrayOf(0)
        val forcedStopCalls = intArrayOf(0)
        val passFailures = mutableListOf<Pair<Long, Throwable>>()
        val gates = mutableMapOf<Long, Gates>()
        var armAttempts = 0
        var coordinatorRef: SyncServiceDrainCoordinator? = null
        val timer = FakeStopScheduler(scope) { token -> coordinatorRef?.stopTimerFired(token) }
        timer.detachOnCancel = detachOnCancel
        val coordinator = SyncServiceDrainCoordinator(
            scope = scope,
            consumeAndExecute = { rel ->
                consumeCalls.add(rel)
                consume(rel, gates.getOrPut(rel) { Gates() })
            },
            cancelStop = { timer.cancel() },
            armStop = { delayMs, token ->
                armAttempts++
                attemptedArmTokens.add(token)
                if (throwCancellationFromArmStop && armAttempts == 1) {
                    coordinatorRef?.onSyncRequested(2L)
                    throw CancellationException("armStop-cancelled")
                }
                if (armAttempts <= failArmStopAttempts) throw RuntimeException("armStop-boom")
                armedTokens.add(token)
                timer.arm(delayMs, token)
            },
            // 与生产形态同构：回调只做停服动作（计数器模拟 stopService）、不重入协调器。
            onEligibleStop = { eligibleStopCalls[0]++ },
            onForcedStop = { forcedStopCalls[0]++ },
            stopDelayForPass = { hadTask -> if (!hadTask) NO_TASK_DELAY_MS else SYNC_DELAY_MS },
            onPassFailed = { rel, e ->
                passFailures.add(rel to e)
                onPassFailed?.invoke(rel, e)
            },
        )
        coordinatorRef = coordinator
        return Kit(
            coordinator, timer, consumeCalls, armedTokens, attemptedArmTokens,
            eligibleStopCalls, forcedStopCalls,
            passFailures, gates
        )
    }

    // ------------------------------------------------------------------
    // #1 核心竞态回归（红：旧 SyncService 以 syncJob?.isActive 合并丢弃 B）
    // ------------------------------------------------------------------
    @Test
    fun `1 request during active drain is consumed by same loop`() = runTest {
        val k = kit(backgroundScope + StandardTestDispatcher(testScheduler)) { rel, g -> gatedConsume(rel, g) }

        k.coordinator.onSyncRequested(1L)
        runCurrent()
        assertTrue("pass A must be entered", k.gate(1L).entered.isCompleted)

        k.coordinator.onSyncRequested(2L) // A 执行中（切出点）B 入队——旧代码在此丢 B
        k.gate(1L).release.complete(Unit)
        runCurrent()
        assertTrue(k.gate(1L).completed.isCompleted)
        runCurrent()
        assertTrue("B must be picked up by the same loop", k.gate(2L).entered.isCompleted)
        k.gate(2L).release.complete(Unit)
        runCurrent()
        assertTrue(k.gate(2L).completed.isCompleted)

        assertEquals(listOf(1L, 2L), k.consumeCalls)
        assertEquals("A tail must not arm (queue non-empty); only B tail arms", 1, k.timer.armCount)
        k.coordinator.cancelActiveDrain() // 双保险
    }

    // ------------------------------------------------------------------
    // #2 job 收尾窗口的请求不丢（consume 返回前同步重入——比 t3 窗口更强）
    // ------------------------------------------------------------------
    @Test
    fun `2 request in job tail window is not lost`() = runTest {
        val holder = arrayOfNulls<Kit>(1)
        val k = kit(backgroundScope + StandardTestDispatcher(testScheduler)) { rel, g ->
            if (rel == 1L) {
                holder[0]!!.coordinator.onSyncRequested(2L) // 同步重入：A 收尾前 B 已在队列
                g.entered.complete(Unit)
                g.completed.complete(Unit)
                taskFor(rel) to true
            } else {
                gatedConsume(rel, g)
            }
        }
        holder[0] = k

        k.coordinator.onSyncRequested(1L)
        runCurrent()
        assertTrue("B must be received after A's in-consume reentry", k.gate(2L).entered.isCompleted)
        assertEquals("no arming while B is buffered/running", 0, k.timer.armCount)

        k.gate(2L).release.complete(Unit)
        runCurrent()
        assertTrue(k.gate(2L).completed.isCompleted)
        assertEquals(listOf(1L, 2L), k.consumeCalls)
        assertEquals("only B's tail arms", 1, k.timer.armCount)
    }

    // ------------------------------------------------------------------
    // #3 冷启动：首个请求拉起循环
    // ------------------------------------------------------------------
    @Test
    fun `3 idle coordinator starts loop on first request`() = runTest {
        val k = kit(backgroundScope + StandardTestDispatcher(testScheduler)) { rel, g -> immediateConsume(rel, g) }

        k.coordinator.onSyncRequested(1L)
        runCurrent()

        assertEquals(listOf(1L), k.consumeCalls)
        assertEquals(1, k.timer.armCount) // NO_TASK 武装
        k.coordinator.cancelActiveDrain()
    }

    // ------------------------------------------------------------------
    // #4 同关系重复请求 → 幂等追加空 pass
    // ------------------------------------------------------------------
    @Test
    fun `4 duplicate requests run idempotent extra pass`() = runTest {
        var pass = 0
        val k = kit(backgroundScope + StandardTestDispatcher(testScheduler)) { rel, g ->
            pass++
            g.entered.complete(Unit)
            g.completed.complete(Unit)
            (if (pass == 1) taskFor(rel) else null) to true // 第二次为空 consume → null
        }

        k.coordinator.onSyncRequested(1L)
        k.coordinator.onSyncRequested(1L)
        runCurrent()
        runCurrent()

        assertEquals(listOf(1L, 1L), k.consumeCalls)
        assertEquals("only the second (empty) pass tail arms NO_TASK", 1, k.timer.armCount)
    }

    // ------------------------------------------------------------------
    // #5 FIFO 保序（多关系）
    // ------------------------------------------------------------------
    @Test
    fun `5 FIFO order preserved across relations`() = runTest {
        val k = kit(backgroundScope + StandardTestDispatcher(testScheduler)) { rel, g -> gatedConsume(rel, g) }

        k.coordinator.onSyncRequested(1L)
        k.coordinator.onSyncRequested(2L)
        k.coordinator.onSyncRequested(3L)
        runCurrent()
        assertTrue(k.gate(1L).entered.isCompleted)

        k.gate(1L).release.complete(Unit)
        runCurrent()
        assertTrue(k.gate(1L).completed.isCompleted)
        assertTrue(k.gate(2L).entered.isCompleted)

        k.gate(2L).release.complete(Unit)
        runCurrent()
        assertTrue(k.gate(3L).entered.isCompleted)

        k.gate(3L).release.complete(Unit)
        runCurrent()
        assertTrue(k.gate(3L).completed.isCompleted)

        assertEquals(listOf(1L, 2L, 3L), k.consumeCalls)
    }

    // ------------------------------------------------------------------
    // #6 DRAINING 中取消 → 循环终结、可重启
    // ------------------------------------------------------------------
    @Test
    fun `6 cancellation while draining ends loop and allows restart`() = runTest {
        val k = kit(backgroundScope + StandardTestDispatcher(testScheduler)) { rel, g ->
            if (rel == 1L) {
                g.entered.complete(Unit)
                awaitCancellation()
            } else {
                gatedConsume(rel, g)
            }
        }

        k.coordinator.onSyncRequested(1L)
        runCurrent()
        assertTrue(k.gate(1L).entered.isCompleted)
        assertTrue(k.coordinator.isDraining)

        k.coordinator.cancelActiveDrain()
        runCurrent()
        assertFalse(k.coordinator.isDraining)

        k.coordinator.onSyncRequested(4L)
        runCurrent()
        assertTrue(k.gate(4L).entered.isCompleted)
        assertTrue(k.coordinator.isDraining)

        k.gate(4L).release.complete(Unit)
        runCurrent()
        assertTrue(k.gate(4L).completed.isCompleted)

        assertEquals(listOf(1L, 4L), k.consumeCalls)
        assertTrue("timer must be cancelled alongside the drain", k.timer.cancelCount >= 1)
    }

    // ------------------------------------------------------------------
    // #7 WAITING 中取消：缓冲本已空，排空为 no-op
    // ------------------------------------------------------------------
    @Test
    fun `7 cancellation while waiting keeps nothing buffered`() = runTest {
        val k = kit(backgroundScope + StandardTestDispatcher(testScheduler)) { rel, g -> immediateConsume(rel, g) }

        k.coordinator.onSyncRequested(1L)
        runCurrent()
        assertEquals(listOf(1L), k.consumeCalls) // 循环挂 receive

        k.coordinator.cancelActiveDrain()
        runCurrent()

        k.coordinator.onSyncRequested(5L)
        runCurrent()
        assertEquals(listOf(1L, 5L), k.consumeCalls)
    }

    // ------------------------------------------------------------------
    // #8 取消排空缓冲；重启只跑新关系（弃置语义——B 不出现）
    // ------------------------------------------------------------------
    @Test
    fun `8 cancelled drain discards buffered requests and restart runs only new relation`() = runTest {
        val k = kit(backgroundScope + StandardTestDispatcher(testScheduler)) { rel, g -> gatedConsume(rel, g) }

        k.coordinator.onSyncRequested(1L)
        runCurrent()
        assertTrue(k.gate(1L).entered.isCompleted)

        k.coordinator.onSyncRequested(2L) // 缓冲
        k.coordinator.cancelActiveDrain() // 取消 A 的循环 + 排空 B

        k.coordinator.onSyncRequested(4L)
        runCurrent()
        assertTrue(k.gate(4L).entered.isCompleted)

        k.gate(1L).release.complete(Unit) // 旧循环已 CE 终结；模拟 NonCancellable 段放行
        runCurrent()

        assertFalse("discarded B must never enter consume", k.gate(2L).entered.isCompleted)
        assertEquals(listOf(1L, 4L), k.consumeCalls)

        k.gate(4L).release.complete(Unit)
        runCurrent()
        assertTrue(k.gate(4L).completed.isCompleted)
        advanceTimeBy(2000)
        runCurrent()

        assertEquals(1, k.timer.armCount) // 仅 D 收尾武装
        assertEquals(1, k.eligibleStopCalls[0]) // D 后恰一次正常停服
    }

    // ------------------------------------------------------------------
    // #9 缓冲请求运行超停服延迟期间不武装、不停服（红：v2/v1 在此交错武装并停服）
    // ------------------------------------------------------------------
    @Test
    fun `9 stop is not armed while buffered request runs beyond stop delay`() = runTest {
        val k = kit(backgroundScope + StandardTestDispatcher(testScheduler)) { rel, g -> gatedConsume(rel, g) }

        k.coordinator.onSyncRequested(1L)
        runCurrent()
        assertTrue(k.gate(1L).entered.isCompleted)

        k.coordinator.onSyncRequested(2L)
        k.gate(1L).release.complete(Unit)
        runCurrent()
        assertTrue("B must be running", k.gate(2L).entered.isCompleted)
        assertFalse("B must not have completed", k.gate(2L).completed.isCompleted)

        advanceTimeBy(3000)
        runCurrent()
        assertEquals("no arming while B runs", 0, k.timer.armCount)
        assertEquals("no eligible stop while B runs", 0, k.eligibleStopCalls[0])

        k.gate(2L).release.complete(Unit)
        runCurrent()
        assertTrue(k.gate(2L).completed.isCompleted)
        assertEquals(1, k.timer.armCount)

        advanceTimeBy(SYNC_DELAY_MS)
        runCurrent()
        assertEquals("exactly one eligible stop after B", 1, k.eligibleStopCalls[0])
    }

    // ------------------------------------------------------------------
    // #10 空 pass 武装后 B 到达：B 运行期间不停服（相对增量断言——baseline 吸收空 pass 武装）
    // ------------------------------------------------------------------
    @Test
    fun `10 empty pass followed by buffered request never stops mid-pass`() = runTest {
        val k = kit(backgroundScope + StandardTestDispatcher(testScheduler)) { rel, g ->
            if (rel == 1L) {
                g.entered.complete(Unit)
                g.completed.complete(Unit)
                null to false // 空 pass
            } else {
                gatedConsume(rel, g)
            }
        }

        k.coordinator.onSyncRequested(1L)
        runCurrent()
        assertTrue(k.gate(1L).completed.isCompleted)
        val baseline = k.timer.armCount // 空 pass 收尾已武装 NO_TASK（此断言点 baseline==1）
        assertEquals(1, baseline)

        k.coordinator.onSyncRequested(2L) // 入口即作废令牌 + cancelStop
        runCurrent()
        assertTrue(k.gate(2L).entered.isCompleted)

        advanceTimeBy(2000) // t1(500) 到期回报：令牌失配 → 原地丢弃
        runCurrent()
        assertEquals("no additional arming while B runs", baseline, k.timer.armCount)
        assertEquals(0, k.eligibleStopCalls[0])

        k.gate(2L).release.complete(Unit)
        runCurrent()
        assertTrue(k.gate(2L).completed.isCompleted)
        assertEquals("B tail arms once more", baseline + 1, k.timer.armCount)

        advanceTimeBy(SYNC_DELAY_MS)
        runCurrent()
        assertEquals("exactly one eligible stop", 1, k.eligibleStopCalls[0])
    }

    // ------------------------------------------------------------------
    // #11 请求与取消竞争：confined 下坍缩为两全序，均确定性成立
    // ------------------------------------------------------------------
    @Test
    fun `11 request race with cancellation is deterministic in both orders`() = runTest {
        // (a) 先入队后取消：首次入队被排空弃置，重试恰获一次完整 pass
        val ka = kit(backgroundScope + StandardTestDispatcher(testScheduler)) { rel, g -> immediateConsume(rel, g) }
        ka.coordinator.onSyncRequested(5L)
        ka.coordinator.cancelActiveDrain() // 循环已 LAZY 启动未运行 → 连同缓冲 E 一起终结
        ka.coordinator.onSyncRequested(5L)
        runCurrent()
        assertEquals(listOf(5L), ka.consumeCalls)
        assertTrue(ka.gate(5L).completed.isCompleted)

        // (b) 先取消后入队：E 由新循环消费恰一次
        val kb = kit(backgroundScope + StandardTestDispatcher(testScheduler)) { rel, g -> immediateConsume(rel, g) }
        kb.coordinator.cancelActiveDrain()
        kb.coordinator.onSyncRequested(5L)
        runCurrent()
        assertEquals(listOf(5L), kb.consumeCalls)
    }

    // ------------------------------------------------------------------
    // #12 僵尸旧循环：NonCancellable 段放行后执行收尾段，身份检查拒绝武装
    // ------------------------------------------------------------------
    @Test
    fun `12 zombie old drain cannot arm after restart`() = runTest {
        val k = kit(backgroundScope + StandardTestDispatcher(testScheduler)) { rel, g ->
            if (rel == 1L) {
                withContext(NonCancellable) { // 模拟旧循环卡在 NonCancellable 段——cancel 不打断
                    g.entered.complete(Unit)
                    g.release.await()
                }
                null to false // 放行后正常返回 → 旧循环继续执行非挂起收尾段
            } else {
                immediateConsume(rel, g)
            }
        }

        k.coordinator.onSyncRequested(1L)
        runCurrent()
        assertTrue(k.gate(1L).entered.isCompleted)

        k.coordinator.cancelActiveDrain()
        k.coordinator.onSyncRequested(4L)
        runCurrent()
        assertTrue(k.gate(4L).completed.isCompleted)
        advanceTimeBy(SYNC_DELAY_MS)
        runCurrent()
        assertEquals(1, k.timer.armCount)
        assertEquals(1, k.eligibleStopCalls[0])

        k.gate(1L).release.complete(Unit) // 旧循环 NonCancellable 段放行
        runCurrent() // 旧循环：consume 返回 → 收尾段 drainJob===myJob 拒武装 → receive 收 CE 终结

        assertEquals("zombie tail must not arm", 1, k.timer.armCount)
        assertEquals(1, k.eligibleStopCalls[0])
        assertEquals(listOf(1L, 4L), k.consumeCalls)
    }

    // ------------------------------------------------------------------
    // #13 停服恰一次；stale 重报全丢弃（回调形态与生产同构：不重入协调器）
    // ------------------------------------------------------------------
    @Test
    fun `13 eligible stop executes exactly once and stale refires are ignored`() = runTest {
        val k = kit(backgroundScope + StandardTestDispatcher(testScheduler)) { rel, g -> immediateConsume(rel, g) }

        k.coordinator.onSyncRequested(1L)
        runCurrent()
        advanceTimeBy(SYNC_DELAY_MS)
        runCurrent()
        assertEquals(1, k.eligibleStopCalls[0])

        val t = k.armedTokens.single()
        k.coordinator.stopTimerFired(t) // stale 重报
        k.coordinator.stopTimerFired(t)
        assertEquals("stale refires must all be dropped", 1, k.eligibleStopCalls[0])
    }

    // ------------------------------------------------------------------
    // #14 漏网 stale 到期：B 运行中不停服，B 后恰一次正常停服（双断言）
    // ------------------------------------------------------------------
    @Test
    fun `14 stale timer fired after new request never stops mid-pass`() = runTest {
        val k = kit(backgroundScope + StandardTestDispatcher(testScheduler)) { rel, g ->
            if (rel == 1L) immediateConsume(rel, g) else gatedConsume(rel, g)
        }

        k.coordinator.onSyncRequested(1L)
        runCurrent()
        assertTrue(k.gate(1L).completed.isCompleted) // arm t1

        k.coordinator.onSyncRequested(2L)
        runCurrent()
        assertTrue(k.gate(2L).entered.isCompleted)

        k.coordinator.stopTimerFired(k.armedTokens[0]) // 模拟逃过 cancelStop 的 t1 到期
        assertEquals("stale t1 must not stop mid-pass", 0, k.eligibleStopCalls[0])

        k.gate(2L).release.complete(Unit)
        runCurrent()
        assertTrue(k.gate(2L).completed.isCompleted)
        advanceTimeBy(SYNC_DELAY_MS)
        runCurrent()
        assertEquals("exactly one normal stop after B", 1, k.eligibleStopCalls[0])
    }

    // ------------------------------------------------------------------
    // #15 多个 stale 到期全丢弃；空闲停服仍发生（终局性）
    // ------------------------------------------------------------------
    @Test
    fun `15 multiple stale timer fires are all ignored and idle stop still occurs`() = runTest {
        val k = kit(backgroundScope + StandardTestDispatcher(testScheduler)) { rel, g ->
            if (rel == 1L) immediateConsume(rel, g) else gatedConsume(rel, g)
        }

        k.coordinator.onSyncRequested(1L)
        runCurrent()
        k.coordinator.onSyncRequested(2L)
        runCurrent()
        assertTrue(k.gate(2L).entered.isCompleted)

        repeat(3) { k.coordinator.stopTimerFired(k.armedTokens[0]) } // 旧令牌重复回报 ×3
        assertEquals(0, k.eligibleStopCalls[0])

        k.gate(2L).release.complete(Unit)
        runCurrent()
        advanceTimeBy(SYNC_DELAY_MS)
        runCurrent()
        assertEquals("idle stop still occurs exactly once", 1, k.eligibleStopCalls[0])
    }

    // ------------------------------------------------------------------
    // #16 快速完成的首 pass 仍武装（UnconfinedTestDispatcher——注册序暴露条件）
    //    Mutation check：回退为 `.also { it.start() }` 单表达式时本用例必须变红
    // ------------------------------------------------------------------
    @Test
    fun `16 fast-completing first pass still arms stop`() = runTest {
        val k = kit(backgroundScope + UnconfinedTestDispatcher(testScheduler)) { rel, g -> immediateConsume(rel, g) }

        k.coordinator.onSyncRequested(1L) // start() 内联执行协程体；drainJob 已注册 → 首 pass 收尾即武装
        assertEquals("first pass tail must arm", 1, k.timer.armCount)

        advanceTimeBy(SYNC_DELAY_MS)
        runCurrent()
        assertEquals(1, k.eligibleStopCalls[0])
    }

    // ------------------------------------------------------------------
    // #17 前代计时器漏网（detach 模式）：t1 确实触发过，但失配丢弃；新空闲停照常
    // ------------------------------------------------------------------
    @Test
    fun `17 timer from previous incarnation leaking past restart cannot stop new idle stop`() = runTest {
        val k = kit(
            backgroundScope + StandardTestDispatcher(testScheduler),
            detachOnCancel = true
        ) { rel, g -> immediateConsume(rel, g) }

        k.coordinator.onSyncRequested(1L)
        runCurrent()
        assertTrue(k.gate(1L).completed.isCompleted) // arm t1(500)

        k.coordinator.cancelActiveDrain() // detach：t1 存活、脱离句柄
        k.coordinator.onSyncRequested(4L)
        runCurrent()
        assertTrue(k.gate(4L).completed.isCompleted) // arm t2(1500)

        advanceTimeBy(2000)
        runCurrent() // t1、t2 先后到期回报

        assertEquals("both timers must have actually fired (non-vacuous)", 2, k.timer.firedTokens.size)
        assertEquals("t1 mismatched/dropped; t2 passes exactly once", 1, k.eligibleStopCalls[0])
    }

    // ------------------------------------------------------------------
    // #20 漏网 FORCED 回调不能停掉新 start 的 drain（复审 #3 现网缺陷场景）
    // ------------------------------------------------------------------
    @Test
    fun `20 leaked forced-stop callback after new start cannot stop new drain`() = runTest {
        val k = kit(backgroundScope + StandardTestDispatcher(testScheduler)) { rel, g ->
            if (rel == 1L) {
                g.entered.complete(Unit)
                awaitCancellation()
            } else {
                immediateConsume(rel, g)
            }
        }

        k.coordinator.onSyncRequested(1L)
        runCurrent()
        assertTrue(k.gate(1L).entered.isCompleted)

        k.coordinator.cancelActiveDrainAndArmForcedStop(FORCED_DELAY_MS) // 弃 A + 武装 FORCED t_f
        val tForced = k.armedTokens.single()

        k.coordinator.onSyncRequested(4L) // 入口即作废 t_f + cancelStop
        runCurrent()
        assertTrue("D must complete unaffected", k.gate(4L).completed.isCompleted) // arm t2 ELIGIBLE

        k.coordinator.stopTimerFired(tForced) // 模拟逃过 cancelStop 的漏网取消回调
        assertEquals("leaked forced callback must be dropped", 0, k.forcedStopCalls[0])

        advanceTimeBy(SYNC_DELAY_MS)
        runCurrent()
        assertEquals("new drain's own idle stop fires", 1, k.eligibleStopCalls[0])
        assertEquals(0, k.forcedStopCalls[0])
        assertEquals(listOf(1L, 4L), k.consumeCalls)
    }

    // ------------------------------------------------------------------
    // #21 FORCED 停服正常路径：无新请求时恰一次；两出口不串；stale 重报丢弃
    // ------------------------------------------------------------------
    @Test
    fun `21 forced stop fires exactly once when no new request arrives`() = runTest {
        val k = kit(backgroundScope + StandardTestDispatcher(testScheduler)) { rel, g -> immediateConsume(rel, g) }

        k.coordinator.onSyncRequested(1L)
        runCurrent()
        assertTrue(k.gate(1L).completed.isCompleted) // arm t1(1500 ELIGIBLE)

        k.coordinator.cancelActiveDrainAndArmForcedStop(FORCED_DELAY_MS) // cancelStop 撤 t1；arm t_f(1000)
        val tForced = k.armedTokens.last()

        advanceTimeBy(FORCED_DELAY_MS)
        runCurrent() // t_f 到期
        assertEquals(1, k.forcedStopCalls[0])
        assertEquals("eligible and forced outlets must not cross", 0, k.eligibleStopCalls[0])

        k.coordinator.stopTimerFired(tForced) // stale 重报 ×2
        k.coordinator.stopTimerFired(tForced)
        assertEquals(1, k.forcedStopCalls[0])
    }

    // ------------------------------------------------------------------
    // #22 交付-恢复窗口：B 已交付挂起中的 receiver、continuation 未运行时，
    //      旧 ELIGIBLE 到期不得停服（红 Mutation：令牌作废挪回 pass 开始处时本用例变红）
    // ------------------------------------------------------------------
    @Test
    fun `22 old eligible timer firing in delivered-not-resumed window cannot stop`() = runTest {
        val k = kit(backgroundScope + StandardTestDispatcher(testScheduler)) { rel, g ->
            if (rel == 1L) immediateConsume(rel, g) else gatedConsume(rel, g)
        }

        k.coordinator.onSyncRequested(1L)
        runCurrent()
        assertTrue(k.gate(1L).completed.isCompleted) // arm t1；循环挂 receive
        val t1 = k.armedTokens.single()

        k.coordinator.onSyncRequested(2L) // trySend 交付给挂起中的 receiver——continuation 已排队未运行
        // 刻意不 runCurrent：此刻 requests.isEmpty==true 但确有待执行请求（交付-恢复窗口）
        k.coordinator.stopTimerFired(t1) // 旧 t1 恰在此窗口执行
        assertEquals("stale timer must be dropped in delivered-not-resumed window", 0, k.eligibleStopCalls[0])

        runCurrent() // B 的 continuation 恢复
        assertTrue(k.gate(2L).entered.isCompleted)
        k.gate(2L).release.complete(Unit)
        runCurrent()
        assertTrue(k.gate(2L).completed.isCompleted)

        advanceTimeBy(SYNC_DELAY_MS)
        runCurrent()
        assertEquals("exactly one stop after B", 1, k.eligibleStopCalls[0])
    }

    // ------------------------------------------------------------------
    // #23 BUG-018 复审阻断：A 消费抛普通异常不得终结 launch——已入队 B 仍被同循环
    //      处置（不静默遗留）、失败 pass 上报（不吞）且不武装，停服协议不被破坏。
    // ------------------------------------------------------------------
    @Test
    fun `23 normal exception in consume does not strand buffered B and keeps stop protocol`() = runTest {
        val k = kit(backgroundScope + StandardTestDispatcher(testScheduler)) { rel, g ->
            if (rel == 1L) {
                g.entered.complete(Unit)
                g.release.await()
                throw RuntimeException("consume-boom")
            } else {
                gatedConsume(rel, g)
            }
        }

        k.coordinator.onSyncRequested(1L)
        runCurrent()
        assertTrue(k.gate(1L).entered.isCompleted)

        k.coordinator.onSyncRequested(2L) // A 执行中 B 入队
        k.gate(1L).release.complete(Unit) // A 的 consume 抛普通异常
        runCurrent()

        // 失败被上报（不吞异常）；失败 pass 不武装（B 已缓冲）
        assertEquals(listOf(1L), k.passFailures.map { it.first })
        assertTrue(k.passFailures.single().second is RuntimeException)
        assertEquals(0, k.timer.armCount)

        // B 不静默遗留——同循环继续 drain
        assertTrue("B must be consumed after A's failed pass", k.gate(2L).entered.isCompleted)
        k.gate(2L).release.complete(Unit)
        runCurrent()
        assertTrue(k.gate(2L).completed.isCompleted)
        assertEquals(listOf(1L, 2L), k.consumeCalls)

        // B 收尾恰一次武装 → 恰一次正常停服（服务必停不被失败 pass 破坏）
        assertEquals(1, k.timer.armCount)
        advanceTimeBy(SYNC_DELAY_MS)
        runCurrent()
        assertEquals(1, k.eligibleStopCalls[0])
    }

    // ------------------------------------------------------------------
    // #24 BUG-018 复审阻断：收尾状态写（armStop）抛普通异常不得终结 launch——
    //      失败 pass 走有界停服处置（重武装 NO_TASK），后续请求照常消费。
    // ------------------------------------------------------------------
    @Test
    fun `24 normal exception in tail state write does not kill loop`() = runTest {
        val k = kit(
            backgroundScope + StandardTestDispatcher(testScheduler),
            failFirstArmStop = true
        ) { rel, g -> immediateConsume(rel, g) }

        k.coordinator.onSyncRequested(1L)
        runCurrent() // pass 完成 → 收尾第一次 armStop 抛普通异常
        assertTrue(k.gate(1L).completed.isCompleted)

        // 失败被上报（不吞异常）
        assertEquals(1, k.passFailures.size)

        // 循环存活：新请求 D 仍被消费
        k.coordinator.onSyncRequested(4L)
        runCurrent()
        assertTrue(k.gate(4L).completed.isCompleted)
        assertEquals(listOf(1L, 4L), k.consumeCalls)

        // D 收尾武装到期 → 恰一次正常停服（服务必停不被破坏）
        advanceTimeBy(SYNC_DELAY_MS)
        runCurrent()
        assertEquals(1, k.eligibleStopCalls[0])
    }

    // ------------------------------------------------------------------
    // #25 CE 边界锁（红线 3）：consume 抛 CancellationException 仍先传播——
    //      不进普通异常边界、不上报为 pass 失败；旧循环终结、缓冲 B 由新循环处置。
    // ------------------------------------------------------------------
    @Test
    fun `25 cancellation from consume still propagates uncaught by normal boundary`() = runTest {
        val k = kit(backgroundScope + StandardTestDispatcher(testScheduler)) { rel, g ->
            if (rel == 1L) {
                g.entered.complete(Unit)
                g.release.await()
                throw CancellationException("ce-boom")
            } else {
                immediateConsume(rel, g)
            }
        }

        k.coordinator.onSyncRequested(1L)
        runCurrent()
        assertTrue(k.gate(1L).entered.isCompleted)
        k.coordinator.onSyncRequested(2L) // B 缓冲
        k.gate(1L).release.complete(Unit)
        runCurrent()

        // CE 不进普通边界、不上报失败；旧循环终结未消费 B
        assertEquals(0, k.passFailures.size)
        assertEquals(listOf(1L), k.consumeCalls)

        // 新请求重启新循环：缓冲的 B（FIFO）与 D 相继被处置
        k.coordinator.onSyncRequested(3L)
        runCurrent()
        assertTrue(k.gate(2L).completed.isCompleted)
        assertTrue(k.gate(3L).completed.isCompleted)
        assertEquals(listOf(1L, 2L, 3L), k.consumeCalls)
    }

    // ------------------------------------------------------------------
    // #26 CE 边界锁：失败上报抛 CE 时必须原样终结当前 drain，不消费 B。
    // ------------------------------------------------------------------
    @Test
    fun `26 cancellation from pass failure reporter terminates loop before buffered B`() = runTest {
        val k = kit(
            backgroundScope + StandardTestDispatcher(testScheduler),
            onPassFailed = { _, _ -> throw CancellationException("report-ce") }
        ) { rel, g ->
            if (rel == 1L) {
                g.entered.complete(Unit)
                g.release.await()
                throw RuntimeException("consume-boom")
            } else {
                immediateConsume(rel, g)
            }
        }

        k.coordinator.onSyncRequested(1L)
        runCurrent()
        k.coordinator.onSyncRequested(2L)
        k.gate(1L).release.complete(Unit)
        runCurrent()

        assertEquals(listOf(1L), k.consumeCalls)
        assertEquals(1, k.passFailures.size)
        assertFalse(k.coordinator.isDraining)
        assertEquals(0, k.timer.armCount)
    }

    // ------------------------------------------------------------------
    // #27 CE 边界锁：失败收尾 armStop 抛 CE 时撤销尝试令牌，不消费 armStop
    //      同步入队的 B，也不留下可触发的停服武装。
    // ------------------------------------------------------------------
    @Test
    fun `27 cancellation from failure tail arm stop terminates loop and revokes token`() = runTest {
        val k = kit(
            backgroundScope + StandardTestDispatcher(testScheduler),
            throwCancellationFromArmStop = true
        ) { rel, g ->
            if (rel == 1L) {
                g.entered.complete(Unit)
                g.completed.complete(Unit)
                throw RuntimeException("consume-boom")
            } else {
                immediateConsume(rel, g)
            }
        }

        k.coordinator.onSyncRequested(1L)
        runCurrent()

        assertEquals(listOf(1L), k.consumeCalls)
        assertEquals(1, k.passFailures.size)
        assertFalse(k.coordinator.isDraining)
        assertEquals(1, k.attemptedArmTokens.size)
        assertEquals(0, k.timer.armCount)
        k.coordinator.stopTimerFired(k.attemptedArmTokens.single())
        assertEquals(0, k.eligibleStopCalls[0])
        assertFalse(k.gate(2L).entered.isCompleted)
    }

    // ------------------------------------------------------------------
    // #28 armStop 连续普通失败：撤销两次失败令牌，立即走现有停服出口，
    //      不回到永久 receive，也不留下伪 armed 状态。
    // ------------------------------------------------------------------
    @Test
    fun `28 consecutive arm stop failures revoke tokens and stop without future request`() = runTest {
        val k = kit(
            backgroundScope + StandardTestDispatcher(testScheduler),
            failArmStopAttempts = 2
        ) { rel, g -> immediateConsume(rel, g) }

        k.coordinator.onSyncRequested(1L)
        runCurrent()

        assertEquals(listOf(1L), k.consumeCalls)
        assertFalse(k.coordinator.isDraining)
        assertEquals(2, k.attemptedArmTokens.size)
        assertEquals(0, k.armedTokens.size)
        assertEquals(0, k.timer.armCount)
        assertEquals(1, k.eligibleStopCalls[0])
    }
}
