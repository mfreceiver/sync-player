package cn.vectory.syncplayer.sync

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import cn.vectory.syncplayer.data.model.SyncAction
import cn.vectory.syncplayer.data.model.SyncActionType
import cn.vectory.syncplayer.data.model.SyncOutcome
import cn.vectory.syncplayer.data.model.SyncPhase
import cn.vectory.syncplayer.data.model.SyncProgress
import cn.vectory.syncplayer.data.repository.SyncRelationRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * D1/D3 — 同步预览终态语义与运行归属（ora-2 状态机）。
 *
 * 互斥单状态 [SyncPreviewState]；成功/部分失败/失败/取消不再共享「已完成」标志；
 * 计划单次消费，Submitting 任何失败一律进 Finished 绝不回 Ready；恢复一律重新扫描；
 * 进度高频数据只走独立 progress 流；运行归属诚实化（其它 relation 占用引擎时拒绝
 * 提交；stop 仅在 progress 显示本 relation 运行时发送）；非终态的运行停止归 FAILED
 * 中断兜底；init 按 relationId 重连运行中任务。
 *
 * 兼容注：StaleTerminalTest（域外）锁定的旧投影 isSyncing/syncComplete/toDelete
 * 保留为派生只读属性，语义由状态机推导，不再是独立可写标志。
 *
 * D3 修复记录：
 *  - SyncService companion 无 @JvmStatic，须 mock [SyncService.Companion] 并对称 unmock；
 *  - createVm 的 initialTab 按用例显式设定（下载用例 dryRun / 删除用例 dryRunDelete）；
 *  - F02 withArg 内断言（原实现只计算丢弃 Boolean）；
 *  - 重复 cancel 真调两次断言 stop 恰一次；
 *  - CompletableDeferred + StandardTestDispatcher 覆盖真实交错（晚回扫描/挂起提交）。
 *
 * D4 修复记录：
 *  - 启动窗口停止意图：cancel 时 progress 仍 IDLE → 记录意图不发 stop，
 *    progress 首次发布本 relation 运行时观察器补发恰一次；
 *  - 确认门统一：startSync/startDelete 与 requestExecute 完全一致（含删除项
 *    一律过确认门，纯下载直通），不再存在测试专用豁免；
 *  - 空扫描第二挂起点：updateLastSync 返回后重校验，非合作晚回不得覆盖新状态；
 *  - 协作取消晚回测试如实改名（cooperative cancel），新增非合作晚回用例。
 *
 * 断言枚举/次数/状态形状 —— 绝不断言用户文案。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncPreviewViewModelTest {

    private val syncEngine = mockk<SyncEngine>(relaxed = true)
    private val syncRelationRepository = mockk<SyncRelationRepository>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)
    private lateinit var progress: MutableStateFlow<SyncProgress>

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        // SyncService companion 无 @JvmStatic：须按单例对象 mock（mockkStatic 拦截不到，
        // 会真实执行方法体并对 relaxed Context 调 startForegroundService 抛 AbstractMethodError）。
        mockkObject(SyncService.Companion)
        every { SyncService.start(any(), any()) } returns Unit
        every { SyncService.stop(any()) } returns Unit
        coEvery { syncRelationRepository.getById(1L) } returns cn.vectory.syncplayer.data.model.SyncRelation(
            id = 1L, name = "r", syncSourceId = 10L, remotePath = "/remote", localBasePath = "videos"
        )
    }

    @After
    fun tearDown() {
        // 与 setUp 对称 unmock，避免对象 mock 泄漏到其它测试类。
        unmockkObject(SyncService.Companion)
        Dispatchers.resetMain()
    }

    private fun createVm(
        initialTab: String = "download",
        initial: SyncProgress = SyncProgress(isRunning = false),
    ): SyncPreviewViewModel {
        progress = MutableStateFlow(initial)
        every { syncEngine.progress } returns progress
        return SyncPreviewViewModel(
            syncEngine = syncEngine,
            syncRelationRepository = syncRelationRepository,
            context = context,
            savedStateHandle = SavedStateHandle(
                mapOf("relationId" to 1L, "initialTab" to initialTab),
            ),
        )
    }

    private fun deleteAction(path: String = "a/video.enc") =
        SyncAction(type = SyncActionType.DELETE_BOTH, relativePath = path, fileSize = 10L)

    private fun downloadAction(path: String = "a/new.enc") =
        SyncAction(type = SyncActionType.DOWNLOAD, relativePath = path, fileSize = 100L)

    /** 删除模式（initialTab="delete"）下加载一个含删除项的预览（Ready）。 */
    private fun loadedDeleteVm(): SyncPreviewViewModel {
        coEvery { syncEngine.dryRunDelete(1L) } returns Pair(listOf(deleteAction()), null)
        val vm = createVm(initialTab = "delete")
        vm.loadPreview()
        return vm
    }

    /** 加载一个纯下载计划（无删除项，直通执行）的 Ready。 */
    private fun loadedDownloadVm(): SyncPreviewViewModel {
        coEvery { syncEngine.dryRun(1L) } returns DryRunResult(
            toDownload = listOf(downloadAction()), toDelete = emptyList()
        )
        val vm = createVm()
        vm.loadPreview()
        return vm
    }

    // =====================================================================
    // F-2-2 regression lock（保留）：取消执行中的删除不得变成用户可见错误。
    // =====================================================================

    @Test
    fun `cancelSync during executeDelete does not set error state`() = runTest {
        coEvery { syncEngine.dryRunDelete(1L) } returns Pair(listOf(deleteAction()), null)
        val cancellationReached = CompletableDeferred<Unit>()
        coEvery { syncEngine.executeDelete(any(), any()) } coAnswers {
            try {
                awaitCancellation()
            } finally {
                cancellationReached.complete(Unit)
            }
        }

        val vm = createVm(initialTab = "delete")
        vm.loadPreview()
        assertTrue("preview should hold one delete action", vm.toDelete.value.isNotEmpty())

        vm.requestExecute() // 含删除项：先设确认门
        vm.confirmExecute()
        assertTrue("isDeleting should be true while running", vm.isDeleting.value)
        vm.cancelSync()
        advanceUntilIdle()

        assertTrue("executeDelete should have observed cancellation", cancellationReached.isCompleted)
        assertFalse("isDeleting should be reset", vm.isDeleting.value)
        assertNull("cancellation must not surface as error state", vm.error.value)
        assertTrue(
            "cancelled delete run ends in CANCELLED outcome, not success",
            (vm.state.value as SyncPreviewState.Finished).result.outcome == SyncRunOutcome.CANCELLED
        )
    }

    // =====================================================================
    // F02 — 预览语义（保留）：无动作才写 SUCCESS；有动作不覆盖；失败绝不写成功。
    // D3：withArg 内必须显式断言（只计算丢弃 Boolean 等于没有校验）。
    // =====================================================================

    @Test
    fun `preview scan with no actions records SUCCESS outcome`() = runTest {
        coEvery { syncEngine.dryRunDelete(1L) } returns Pair(emptyList(), null)
        val vm = createVm(initialTab = "delete")

        vm.loadPreview()
        advanceUntilIdle()

        assertTrue("empty successful scan is NoChanges", vm.state.value is SyncPreviewState.NoChanges)
        coVerify(exactly = 1) {
            syncRelationRepository.updateLastSync(1L, withArg {
                assertEquals(SyncOutcome.Status.SUCCESS, it.status)
            })
        }
    }

    @Test
    fun `preview with pending delete actions does not overwrite last outcome`() = runTest {
        coEvery { syncEngine.dryRunDelete(1L) } returns Pair(listOf(deleteAction()), null)
        val vm = createVm(initialTab = "delete")

        vm.loadPreview()
        advanceUntilIdle()

        assertTrue(vm.state.value is SyncPreviewState.Ready)
        coVerify(exactly = 0) { syncRelationRepository.updateLastSync(any(), any()) }
    }

    @Test
    fun `preview scan failure never records success outcome`() = runTest {
        coEvery { syncEngine.dryRunDelete(1L) } returns Pair(emptyList(), "scan failed")
        val vm = createVm(initialTab = "delete")

        vm.loadPreview()
        advanceUntilIdle()

        assertTrue("failed scan is ScanFailed, never NoChanges", vm.state.value is SyncPreviewState.ScanFailed)
        coVerify(exactly = 0) { syncRelationRepository.updateLastSync(any(), any()) }
    }

    // =====================================================================
    // D1-1 终态映射：四个终态各自正确的 outcome；失败永不产生成功语义。
    // =====================================================================

    @Test
    fun `terminal phases map to distinct outcomes`() = runTest {
        val cases = mapOf(
            SyncPhase.COMPLETED to SyncRunOutcome.SUCCESS,
            SyncPhase.PARTIAL_FAILED to SyncRunOutcome.PARTIAL_FAILED,
            SyncPhase.FAILED to SyncRunOutcome.FAILED,
            SyncPhase.CANCELLED to SyncRunOutcome.CANCELLED,
        )
        cases.forEach { (phase, expected) ->
            val vm = loadedDownloadVm()
            assertTrue("precondition Ready for $phase", vm.state.value is SyncPreviewState.Ready)
            vm.requestExecute()
            assertTrue("precondition Running for $phase", vm.state.value is SyncPreviewState.Running)
            progress.value = SyncProgress(
                isRunning = false, phase = phase, relationId = 1L,
                completedFiles = 2, failedFiles = if (phase == SyncPhase.PARTIAL_FAILED) 4 else 0
            )
            val finished = vm.state.value as SyncPreviewState.Finished
            assertEquals("outcome for $phase", expected, finished.result.outcome)
            if (phase == SyncPhase.PARTIAL_FAILED) assertEquals(4, finished.result.failedFiles)
            if (phase == SyncPhase.COMPLETED) assertEquals(2, finished.result.completedFiles)
        }
    }

    @Test
    fun `failed terminal never yields success semantics`() = runTest {
        val vm = loadedDownloadVm()
        vm.requestExecute()
        progress.value = SyncProgress(isRunning = false, phase = SyncPhase.FAILED, relationId = 1L)

        val finished = vm.state.value as SyncPreviewState.Finished
        assertEquals(SyncRunOutcome.FAILED, finished.result.outcome)
        assertFalse(
            "legacy syncComplete projection is shape-only; outcome is the query source",
            finished.result.outcome == SyncRunOutcome.SUCCESS
        )
    }

    // =====================================================================
    // D1-2 计划单次消费：Submitting 后 Ready 不可回退；dismiss 不复活旧计划。
    // =====================================================================

    @Test
    fun `plan is consumed exactly once and cannot be re-executed while running`() = runTest {
        val vm = loadedDeleteVm()
        vm.requestExecute()
        coEvery { syncEngine.executeDelete(any(), any()) } coAnswers { awaitCancellation() }

        vm.confirmExecute()
        assertTrue(vm.state.value is SyncPreviewState.Running)
        vm.startDelete() // 重复触发
        vm.startDelete()
        advanceUntilIdle()
        coVerify(exactly = 1) { syncEngine.executeDelete(any(), any()) }
    }

    @Test
    fun `startSync after a failed terminal does not reuse the old plan`() = runTest {
        val vm = createVm()
        coEvery { syncEngine.dryRun(1L) } returns DryRunResult(
            toDownload = listOf(downloadAction()), toDelete = listOf(deleteAction("a/old.enc"))
        )
        vm.loadPreview()
        vm.requestExecute()
        vm.confirmExecute()
        progress.value = SyncProgress(isRunning = false, phase = SyncPhase.PARTIAL_FAILED, relationId = 1L, failedFiles = 1)
        assertTrue(vm.state.value is SyncPreviewState.Finished)

        vm.clearError() // dismiss 绝不重新启用旧计划
        vm.startSync()
        coVerify(exactly = 1) { syncEngine.setPendingTask(any()) }
    }

    // =====================================================================
    // D1-3 恢复一律重新扫描：新计划新 planId。
    // =====================================================================

    @Test
    fun `recovery triggers a fresh scan with a new plan id`() = runTest {
        val vm = createVm()
        coEvery { syncEngine.dryRun(1L) } returns DryRunResult(
            toDownload = listOf(downloadAction()), toDelete = listOf(deleteAction("a/x.enc"))
        )
        vm.loadPreview()
        val firstPlan = (vm.state.value as SyncPreviewState.Ready).planId
        vm.requestExecute()
        vm.confirmExecute()
        progress.value = SyncProgress(isRunning = false, phase = SyncPhase.FAILED, relationId = 1L)

        coEvery { syncEngine.dryRun(1L) } returns DryRunResult(
            toDownload = listOf(downloadAction("a/y.enc")), toDelete = listOf(deleteAction("a/z.enc"))
        )
        vm.loadPreview()
        val secondPlan = (vm.state.value as SyncPreviewState.Ready).planId
        assertTrue("fresh scan must mint a new plan id", secondPlan != firstPlan)

        // 新计划可执行（第二次消费），且是全新列表
        vm.requestExecute()
        vm.confirmExecute()
        coVerify(exactly = 2) { syncEngine.setPendingTask(any()) }
        coVerify {
            syncEngine.setPendingTask(match {
                it.toDownload.any { a -> a.relativePath == "a/y.enc" } &&
                    it.toDelete.any { a -> a.relativePath == "a/z.enc" }
            })
        }
    }

    // =====================================================================
    // D1-4 进度流独立：高频进度不进主状态。
    // =====================================================================

    @Test
    fun `progress emissions never mutate the page state`() = runTest {
        val vm = loadedDeleteVm()
        val before = vm.state.value
        repeat(5) { i ->
            progress.value = SyncProgress(
                isRunning = true, phase = SyncPhase.DOWNLOADING, relationId = 9L,
                transferredBytes = i * 100L, totalBytes = 1000L
            )
        }
        assertEquals(before, vm.state.value)
        // StateFlow 合流后只剩最新值：高频进度落在独立流上，主状态不受影响
        assertEquals(400L, progress.value.transferredBytes)
    }

    // =====================================================================
    // D1-5 停止只作用于当前运行；D3 运行归属诚实化：仅 progress 显示本
    // relation 运行中才发全局 stop；重复 cancel 只发一次。
    // =====================================================================

    @Test
    fun `stop only acts on the active run`() = runTest {
        val vm = loadedDeleteVm()
        // Ready：停止是 no-op
        vm.cancelSync()
        io.mockk.verify(exactly = 0) { SyncService.stop(any()) }

        // Finished：同样 no-op
        coEvery { syncEngine.executeDelete(any(), any()) } returns true
        vm.requestExecute()
        vm.confirmExecute()
        advanceUntilIdle()
        assertTrue(vm.state.value is SyncPreviewState.Finished)
        vm.cancelSync()
        io.mockk.verify(exactly = 0) { SyncService.stop(any()) }
    }

    @Test
    fun `cancel during download run calls stop exactly once and enters Cancelling`() = runTest {
        val vm = loadedDownloadVm()
        vm.requestExecute()
        assertTrue(vm.state.value is SyncPreviewState.Running)
        // D3 诚实化：progress 显示本 relation 运行中，stop 才会发送。
        progress.value = SyncProgress(isRunning = true, phase = SyncPhase.DOWNLOADING, relationId = 1L)
        vm.cancelSync()
        vm.cancelSync() // 重复 cancel：Cancelling 分支 no-op，绝不二次 stop
        io.mockk.verify(exactly = 1) { SyncService.stop(any()) }
        assertTrue(vm.state.value is SyncPreviewState.Cancelling)
    }

    @Test
    fun `cancel during startup window defers stop until progress confirms our run`() = runTest {
        // 启动窗口：start 已返回（Running）但服务尚未发布 progress（仍 IDLE）。
        val vm = loadedDownloadVm()
        vm.requestExecute()
        assertTrue(vm.state.value is SyncPreviewState.Running)

        vm.cancelSync() // 记录停止意图；progress 未运行 → 此刻不发 stop
        vm.cancelSync() // 幂等：重复 cancel 不重复记录/发送
        io.mockk.verify(exactly = 0) { SyncService.stop(any()) }
        assertTrue(vm.state.value is SyncPreviewState.Cancelling)

        // 服务后续发布本 relation 运行：观察器补发恰一次 stop。
        progress.value = SyncProgress(isRunning = true, phase = SyncPhase.DOWNLOADING, relationId = 1L)
        io.mockk.verify(exactly = 1) { SyncService.stop(any()) }

        // 终态到达：CANCELLED，且 stop 仍只发过一次（无二次补发）。
        progress.value = SyncProgress(isRunning = false, phase = SyncPhase.CANCELLED, relationId = 1L)
        val finished = vm.state.value as SyncPreviewState.Finished
        assertEquals(SyncRunOutcome.CANCELLED, finished.result.outcome)
        io.mockk.verify(exactly = 1) { SyncService.stop(any()) }
    }

    @Test
    fun `terminal from another relation does not finish our run`() = runTest {
        val vm = loadedDownloadVm()
        vm.requestExecute()
        progress.value = SyncProgress(isRunning = false, phase = SyncPhase.COMPLETED, relationId = 999L)
        assertTrue("foreign terminal must be ignored", vm.state.value is SyncPreviewState.Running)
        progress.value = SyncProgress(isRunning = false, phase = SyncPhase.COMPLETED, relationId = 1L)
        assertTrue(vm.state.value is SyncPreviewState.Finished)
    }

    @Test
    fun `preview and execute are refused with a banner while another relation runs`() = runTest {
        // 引擎正被其它 relation（99L）占用：init 不重连（relationId 不匹配）。
        // AND-083 合并策略：dryRun 会 park 在运行中同步持有的
        // storageOperationMutex 上——预览在入口即拒绝，而不是先扫描再在执行时拒绝。
        val vm = createVm(initial = SyncProgress(isRunning = true, phase = SyncPhase.DOWNLOADING, relationId = 99L))
        coEvery { syncEngine.dryRun(1L) } returns DryRunResult(
            toDownload = listOf(downloadAction()), toDelete = emptyList()
        )
        vm.loadPreview()
        assertTrue(
            "preview must be refused while the engine is owned by another relation",
            vm.state.value is SyncPreviewState.Idle
        )
        assertTrue("refusal must be reported via the typed error banner", vm.error.value != null)
        coVerify(exactly = 0) { syncEngine.dryRun(any()) }

        vm.requestExecute()
        assertTrue(vm.state.value is SyncPreviewState.Idle)
        assertNull("no confirm gate may be armed on refusal", vm.pendingExecuteConfirm.value)
        coVerify(exactly = 0) { syncEngine.setPendingTask(any()) }
        io.mockk.verify(exactly = 0) { SyncService.start(any(), any()) }
    }

    // =====================================================================
    // D1-6 删除确认门控：两模式均适用；无删除项直接执行。D3：门值为 planId。
    // =====================================================================

    @Test
    fun `delete plan execution is gated behind confirmation in delete mode`() = runTest {
        val vm = loadedDeleteVm()
        vm.requestExecute()
        coVerify(exactly = 0) { syncEngine.executeDelete(any(), any()) }
        assertTrue("confirm gate binds the ready plan id", vm.pendingExecuteConfirm.value != null)

        vm.confirmExecute()
        coVerify(exactly = 1) { syncEngine.executeDelete(1L, any()) }
        assertNull("confirm gate is cleared after consumption", vm.pendingExecuteConfirm.value)
    }

    @Test
    fun `dismissing delete confirmation does not execute`() = runTest {
        val vm = loadedDeleteVm()
        vm.requestExecute()
        assertTrue(vm.pendingExecuteConfirm.value != null)
        vm.dismissExecuteConfirm()
        coVerify(exactly = 0) { syncEngine.executeDelete(any(), any()) }
        assertNull(vm.pendingExecuteConfirm.value)
        assertTrue("plan stays Ready after dismiss", vm.state.value is SyncPreviewState.Ready)
    }

    @Test
    fun `download plan containing deletes is also gated`() = runTest {
        val vm = createVm()
        coEvery { syncEngine.dryRun(1L) } returns DryRunResult(
            toDownload = listOf(downloadAction()), toDelete = listOf(deleteAction())
        )
        vm.loadPreview()
        vm.requestExecute()
        coVerify(exactly = 0) { syncEngine.setPendingTask(any()) }
        assertTrue(vm.pendingExecuteConfirm.value != null)

        vm.confirmExecute()
        coVerify(exactly = 1) { syncEngine.setPendingTask(any()) }
    }

    @Test
    fun `download plan without deletes executes immediately`() = runTest {
        val vm = loadedDownloadVm()
        vm.requestExecute()
        coVerify(exactly = 1) { syncEngine.setPendingTask(any()) }
        assertNull("no gate for pure download plans", vm.pendingExecuteConfirm.value)
    }

    // ---------------------------------------------------------------------
    // D4 确认门统一：startSync/startDelete 与 requestExecute 完全一致，
    // 不存在任何测试专用豁免（主审裁定）。
    // ---------------------------------------------------------------------

    @Test
    fun `startSync on a plan containing deletes is gated like requestExecute`() = runTest {
        val vm = createVm()
        coEvery { syncEngine.dryRun(1L) } returns DryRunResult(
            toDownload = listOf(downloadAction()), toDelete = listOf(deleteAction())
        )
        vm.loadPreview()
        val planId = (vm.state.value as SyncPreviewState.Ready).planId

        vm.startSync()
        assertEquals("gate binds the ready plan id", planId, vm.pendingExecuteConfirm.value)
        assertTrue("plan stays Ready until confirmed", vm.state.value is SyncPreviewState.Ready)
        coVerify(exactly = 0) { syncEngine.setPendingTask(any()) }
        io.mockk.verify(exactly = 0) { SyncService.start(any(), any()) }
    }

    @Test
    fun `startDelete is gated behind confirmation like requestExecute`() = runTest {
        val vm = loadedDeleteVm()
        val planId = (vm.state.value as SyncPreviewState.Ready).planId

        vm.startDelete()
        assertEquals("gate binds the ready plan id", planId, vm.pendingExecuteConfirm.value)
        coVerify(exactly = 0) { syncEngine.executeDelete(any(), any()) }

        vm.confirmExecute()
        coVerify(exactly = 1) { syncEngine.executeDelete(1L, any()) }
        assertNull(vm.pendingExecuteConfirm.value)
    }

    @Test
    fun `startSync on pure download plan executes immediately without gate`() = runTest {
        // StaleTerminalTest 语义回归：纯下载计划 startSync 直通执行。
        val vm = loadedDownloadVm()
        vm.startSync()
        assertNull("pure download plan must not arm the gate", vm.pendingExecuteConfirm.value)
        assertTrue(vm.state.value is SyncPreviewState.Running)
        coVerify(exactly = 1) { syncEngine.setPendingTask(any()) }
    }

    // =====================================================================
    // D1-7 扫描失败：typed issue；ScanFailed 不产生可执行计划。
    // =====================================================================

    @Test
    fun `download scan failure is typed and gates execution`() = runTest {
        val vm = createVm()
        coEvery { syncEngine.dryRun(1L) } returns DryRunResult(
            toDownload = emptyList(), toDelete = emptyList(),
            error = SyncException(SyncErrorCode.PATH_NOT_FOUND, SyncOperation.SCAN)
        )
        vm.loadPreview()

        val failed = vm.state.value as SyncPreviewState.ScanFailed
        assertEquals(SyncPreviewIssue.PATH, failed.issue.kind)

        vm.startSync() // ScanFailed 下不可执行
        coVerify(exactly = 0) { syncEngine.setPendingTask(any()) }
    }

    @Test
    fun `delete mode scan failure maps to ScanFailed as well`() = runTest {
        coEvery { syncEngine.dryRunDelete(1L) } returns Pair(emptyList(), "boom")
        val vm = createVm(initialTab = "delete")
        vm.loadPreview()

        assertTrue(vm.state.value is SyncPreviewState.ScanFailed)
        vm.startDelete()
        coVerify(exactly = 0) { syncEngine.executeDelete(any(), any()) }
    }

    // =====================================================================
    // D3-1 计划消费：Submitting 任何失败（relation null / setPendingTask 抛 /
    // pending 写后 start 抛）→ Finished(FAILED)，绝不回退 Ready。
    // =====================================================================

    @Test
    fun `submit with missing relation ends in Finished FAILED and never returns to Ready`() = runTest {
        val vm = loadedDownloadVm()
        coEvery { syncRelationRepository.getById(1L) } returns null

        vm.requestExecute()
        advanceUntilIdle()

        val finished = vm.state.value as SyncPreviewState.Finished
        assertEquals(SyncRunOutcome.FAILED, finished.result.outcome)
        assertFalse("consumed plan must never fall back to Ready", vm.state.value is SyncPreviewState.Ready)
        coVerify(exactly = 0) { syncEngine.consumePendingTask(any()) } // pending 未写，无需回收
        assertTrue("failure must surface via the typed banner", vm.error.value != null)
    }

    @Test
    fun `setPendingTask failure ends in Finished FAILED without pending reclaim`() = runTest {
        val vm = loadedDownloadVm()
        coEvery { syncEngine.setPendingTask(any()) } throws RuntimeException("boom")

        vm.requestExecute()
        advanceUntilIdle()

        val finished = vm.state.value as SyncPreviewState.Finished
        assertEquals(SyncRunOutcome.FAILED, finished.result.outcome)
        assertFalse(vm.state.value is SyncPreviewState.Ready)
        coVerify(exactly = 0) { syncEngine.consumePendingTask(any()) } // pending 未落库
    }

    @Test
    fun `service start failure reclaims pending and ends in Finished FAILED`() = runTest {
        val vm = loadedDownloadVm()
        every { SyncService.start(any(), any()) } throws RuntimeException("boom")

        vm.requestExecute()
        advanceUntilIdle()

        val finished = vm.state.value as SyncPreviewState.Finished
        assertEquals(SyncRunOutcome.FAILED, finished.result.outcome)
        assertFalse(
            "partial submission (pending written) must not masquerade as replayable Ready",
            vm.state.value is SyncPreviewState.Ready
        )
        coVerify(exactly = 1) { syncEngine.consumePendingTask(1L) } // 尽力回收已写 pending
    }

    // =====================================================================
    // D3-2 模式与扫描统一失效：点当前 Tab no-op；忙态禁切换/重预览（VM 层）；
    // 晚回扫描按 generation+requestId+模式快照丢弃。
    // =====================================================================

    @Test
    fun `tapping the current tab is a no-op`() = runTest {
        val vm = loadedDeleteVm()
        val planId = (vm.state.value as SyncPreviewState.Ready).planId

        vm.setSyncMode(SyncMode.DELETE) // 点当前 Tab
        assertTrue("state must stay Ready", vm.state.value is SyncPreviewState.Ready)
        assertEquals("plan must stay untouched", planId, (vm.state.value as SyncPreviewState.Ready).planId)
        assertTrue(vm.toDelete.value.isNotEmpty())
    }

    @Test
    fun `mode switch and re-preview are rejected while running`() = runTest {
        val vm = loadedDownloadVm()
        vm.requestExecute()
        assertTrue(vm.state.value is SyncPreviewState.Running)

        vm.setSyncMode(SyncMode.DELETE)
        assertEquals("mode switch is rejected while running", SyncMode.DOWNLOAD, vm.syncMode.value)
        assertTrue(vm.state.value is SyncPreviewState.Running)

        vm.loadPreview()
        assertTrue("re-preview is rejected while running", vm.state.value is SyncPreviewState.Running)
    }

    @Test
    fun `late scan return after cooperative cancellation does not pollute the new mode`() = runTest {
        // 协作取消语义：setSyncMode 先递增 generation 再 cancel 扫描 job，
        // dryRun 挂起点抛 CancellationException 走取消收尾（非合作晚回见后续用例）。
        // 真实交错：StandardTestDispatcher 让扫描真正挂起在引擎调用里。
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val gate = CompletableDeferred<Unit>()
        coEvery { syncEngine.dryRun(1L) } coAnswers {
            gate.await()
            DryRunResult(toDownload = listOf(downloadAction()), toDelete = emptyList())
        }
        val vm = createVm()
        vm.loadPreview()
        runCurrent() // 扫描进入 dryRun，挂起在 gate
        assertTrue(vm.state.value is SyncPreviewState.Scanning)

        vm.setSyncMode(SyncMode.DELETE) // generation 先于 cancel 递增，作废在途扫描
        assertEquals(SyncMode.DELETE, vm.syncMode.value)

        gate.complete(Unit) // 旧扫描此刻才"返回"
        advanceUntilIdle() // 取消收尾：晚回结果必须被丢弃
        assertTrue(
            "invalidated late scan must not write state in the new mode",
            vm.state.value is SyncPreviewState.Idle
        )
        coVerify(exactly = 0) { syncRelationRepository.updateLastSync(any(), any()) }

        // 新模式下重新扫描产生全新计划
        coEvery { syncEngine.dryRunDelete(1L) } returns Pair(listOf(deleteAction()), null)
        vm.loadPreview()
        advanceUntilIdle()
        assertTrue(vm.state.value is SyncPreviewState.Ready)
        assertTrue(vm.toDelete.value.isNotEmpty())
    }

    // ---------------------------------------------------------------------
    // D4 非合作晚回：扫描空结果分支的 updateLastSync 是第二挂起点，返回后
    // 必须重校验代次/模式 —— 旧写入/旧异常以 NonCancellable 非合作方式晚回，
    // 不得覆盖新模式下已就绪的新计划。
    // ---------------------------------------------------------------------

    @Test
    fun `non cooperative late empty scan does not overwrite the new plan`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val dryRunGate = CompletableDeferred<Unit>()
        val updateGate = CompletableDeferred<Unit>()
        coEvery { syncEngine.dryRun(1L) } coAnswers {
            // 非合作挂起：不响应 job 取消（NonCancellable），返回后继续走
            // updateLastSync —— 精确复现第二挂起点前的时序。
            withContext(NonCancellable) { dryRunGate.await() }
            DryRunResult(toDownload = emptyList(), toDelete = emptyList())
        }
        coEvery { syncRelationRepository.updateLastSync(any(), any()) } coAnswers {
            withContext(NonCancellable) { updateGate.await() }
        }

        val vm = createVm()
        vm.loadPreview()
        runCurrent() // 挂起在 dryRun
        dryRunGate.complete(Unit) // 空结果返回，通过第一重校验，挂起在 updateLastSync
        runCurrent()
        assertTrue(vm.state.value is SyncPreviewState.Scanning)

        vm.setSyncMode(SyncMode.DELETE) // generation 先于 cancel 递增，作废在途扫描
        coEvery { syncEngine.dryRunDelete(1L) } returns Pair(listOf(deleteAction("a/del.enc")), null)
        vm.loadPreview() // 新模式新扫描
        advanceUntilIdle()
        val newPlan = (vm.state.value as SyncPreviewState.Ready).planId

        updateGate.complete(Unit) // 旧写入此刻才晚回
        advanceUntilIdle()
        assertTrue(
            "late NoChanges write must not clobber the new plan",
            vm.state.value is SyncPreviewState.Ready
        )
        assertEquals(
            "new plan id must be untouched",
            newPlan, (vm.state.value as SyncPreviewState.Ready).planId
        )
        // 写库确实发生过一次（晚回被状态校验拦截，而非未发生）。
        coVerify(exactly = 1) { syncRelationRepository.updateLastSync(any(), any()) }
    }

    @Test
    fun `non cooperative late scan exception does not pollute the new scan state`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val dryRunGate = CompletableDeferred<Unit>()
        val updateGate = CompletableDeferred<Unit>()
        coEvery { syncEngine.dryRun(1L) } coAnswers {
            withContext(NonCancellable) { dryRunGate.await() }
            DryRunResult(toDownload = emptyList(), toDelete = emptyList())
        }
        coEvery { syncRelationRepository.updateLastSync(any(), any()) } coAnswers {
            withContext(NonCancellable) { updateGate.await() }
            throw RuntimeException("late storage failure")
        }

        val vm = createVm()
        vm.loadPreview()
        runCurrent()
        dryRunGate.complete(Unit)
        runCurrent()
        assertTrue(vm.state.value is SyncPreviewState.Scanning)

        vm.setSyncMode(SyncMode.DELETE)
        coEvery { syncEngine.dryRunDelete(1L) } returns Pair(listOf(deleteAction("a/del.enc")), null)
        vm.loadPreview()
        advanceUntilIdle()
        val newPlan = (vm.state.value as SyncPreviewState.Ready).planId

        updateGate.complete(Unit) // 旧异常非合作晚回
        advanceUntilIdle()
        assertTrue(
            "late exception must not replace the new plan with ScanFailed",
            vm.state.value is SyncPreviewState.Ready
        )
        assertEquals(
            "new plan id must be untouched",
            newPlan, (vm.state.value as SyncPreviewState.Ready).planId
        )
    }

    @Test
    fun `submit suspension rejects mode switch and recovery never revives the old plan`() = runTest {
        // 真实交错：提交挂起在 relation 查询上，处于 Submitting 中段。
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val gate = CompletableDeferred<Unit>()
        coEvery { syncRelationRepository.getById(1L) } coAnswers {
            gate.await()
            cn.vectory.syncplayer.data.model.SyncRelation(
                id = 1L, name = "r", syncSourceId = 10L, remotePath = "/remote", localBasePath = "videos"
            )
        }
        val vm = loadedDownloadVm()
        advanceUntilIdle()
        assertTrue(vm.state.value is SyncPreviewState.Ready)

        vm.requestExecute() // 同步段消费：launch 前状态已迁 Submitting
        assertTrue(vm.state.value is SyncPreviewState.Submitting)
        vm.setSyncMode(SyncMode.DELETE) // Submitting 期间禁切模式（VM 层拒绝）
        assertEquals(SyncMode.DOWNLOAD, vm.syncMode.value)
        vm.loadPreview() // Submitting 期间禁重新预览
        assertTrue(vm.state.value is SyncPreviewState.Submitting)

        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue("suspended submit resumes into Running", vm.state.value is SyncPreviewState.Running)

        progress.value = SyncProgress(isRunning = false, phase = SyncPhase.COMPLETED, relationId = 1L)
        advanceUntilIdle()
        assertTrue(vm.state.value is SyncPreviewState.Finished)

        vm.loadPreview() // 恢复 = 重扫出新 planId
        advanceUntilIdle()
        assertTrue(vm.state.value is SyncPreviewState.Ready)
        coVerify(exactly = 1) { syncEngine.setPendingTask(any()) } // 旧计划绝不复活
    }

    // =====================================================================
    // D3-3 中断兜底与 init 重连；D3-4 投影：isFinished=任何终态，
    // syncComplete 仅 SUCCESS（@Deprecated 形状投影）。
    // =====================================================================

    @Test
    fun `non terminal stop of our run is recorded as FAILED interruption`() = runTest {
        val vm = loadedDownloadVm()
        vm.requestExecute()
        assertTrue(vm.state.value is SyncPreviewState.Running)

        progress.value = SyncProgress(isRunning = true, phase = SyncPhase.DOWNLOADING, relationId = 1L)
        progress.value = SyncProgress(isRunning = false, phase = SyncPhase.DOWNLOADING, relationId = 1L)

        val finished = vm.state.value as SyncPreviewState.Finished
        assertEquals(
            "non-terminal run stop must be FAILED (process death class), never CANCELLED/SUCCESS",
            SyncRunOutcome.FAILED, finished.result.outcome
        )
        assertTrue("any Finished sets isFinished", vm.isFinished.value)
        assertFalse("FAILED must not set the success-only syncComplete", vm.syncComplete.value)
    }

    @Test
    fun `init reconnects to a running engine for the same relation`() = runTest {
        val vm = createVm(initial = SyncProgress(isRunning = true, phase = SyncPhase.DOWNLOADING, relationId = 1L))
        assertTrue("page rebuild reconnects the running sync", vm.state.value is SyncPreviewState.Running)
        assertTrue(vm.isSyncing.value)

        progress.value = SyncProgress(isRunning = false, phase = SyncPhase.COMPLETED, relationId = 1L)
        val finished = vm.state.value as SyncPreviewState.Finished
        assertEquals(SyncRunOutcome.SUCCESS, finished.result.outcome)
        assertTrue(vm.isFinished.value)
        assertTrue("COMPLETED marks the legacy syncComplete projection", vm.syncComplete.value)
    }
}
