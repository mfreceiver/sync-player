package cn.vectory.syncplayer.sync

import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import cn.vectory.syncplayer.data.model.SyncAction
import cn.vectory.syncplayer.data.model.SyncActionType
import cn.vectory.syncplayer.data.model.SyncProtocol
import cn.vectory.syncplayer.data.model.SyncRelation
import cn.vectory.syncplayer.data.model.SyncSource
import cn.vectory.syncplayer.data.repository.PlaybackRepository
import cn.vectory.syncplayer.data.repository.SyncLogRepository
import cn.vectory.syncplayer.data.repository.SyncRelationRepository
import cn.vectory.syncplayer.data.repository.SyncSourceRepository
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.data.model.SyncPhase
import cn.vectory.syncplayer.debug.DebugLogger
import cn.vectory.syncplayer.storage.StoragePaths
import cn.vectory.syncplayer.storage.MediaFileStore
import cn.vectory.syncplayer.storage.TmpProbeState
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Task 7 迁移测试：[SyncDownloadPipeline]（DOWNLOAD/更新 + DELETE_LOCAL tail）从 [SyncEngine]
 * 迁出后的行为等价性。
 *
 * 覆盖 plan 12.2.0 remediation T7-C1：
 *  - toDelete 无 DB 行 → 计为成功（本地已不存在即视为已删除）。
 */
class SyncDownloadPipelineTest {

    private val syncRelationRepository = mockk<SyncRelationRepository>(relaxed = true)
    private val syncSourceRepository = mockk<SyncSourceRepository>()
    private val videoRepository = mockk<VideoRepository>(relaxed = true)
    private val playbackRepository = mockk<PlaybackRepository>(relaxed = true)
    private val syncLogRepository = mockk<SyncLogRepository>(relaxed = true)
    private val clientFactory = mockk<RemoteFileClientFactory>()
    private val storagePaths = mockk<StoragePaths>()
    private val store = mockk<MediaFileStore>(relaxed = true)
    private val storeLazy = mockk<Lazy<MediaFileStore>> { every { get() } returns store }
    private val context = mockk<android.content.Context>(relaxed = true)
    private val publisher = SyncProgressPublisher()
    private val deleteExecutor = SyncDeleteExecutor(
        syncRelationRepository, syncSourceRepository, videoRepository, playbackRepository,
        syncLogRepository, clientFactory, storagePaths, storeLazy, context, publisher
    )
    private val videoMetadataExtractor = mockk<VideoMetadataExtractor>(relaxed = true)
    private val imageMetadataExtractor = mockk<ImageMetadataExtractor>(relaxed = true)
    private val sut = SyncDownloadPipeline(
        syncRelationRepository, syncSourceRepository, videoRepository, playbackRepository,
        syncLogRepository, videoMetadataExtractor, imageMetadataExtractor,
        clientFactory, storagePaths, storeLazy, context, publisher, deleteExecutor
    )

    private fun delAction(p: String) = SyncAction(SyncActionType.DELETE_LOCAL, p, 1L, 0L)

    private fun stubBasics() {
        coEvery { syncRelationRepository.getById(1L) } returns SyncRelation(
            id = 1L, name = "r", syncSourceId = 10L, remotePath = "/remote", localBasePath = "videos"
        )
        coEvery { syncSourceRepository.getById(10L) } returns SyncSource(
            id = 10L, name = "s", protocol = SyncProtocol.SMB, host = "h", port = 445,
            username = "u", password = "p"
        )
    }

    // ------------------------------------------------------------------ BUG-007 管线组（计划 §BUG-007.3 #1-#14+13b）
    // finalize 后取消的清理缺口：三阶段提交状态机（NOT_FINALIZED/FINALIZE_UNCERTAIN/COMMITTED）
    // + cleanUpAttempt 单点清理 + 三态 tmp 探测 + uncleanFinalizedId 双清零（恰一次回滚）。
    // 实现先于测试落盘（前会话），计划标红用例的红证据以注入点核实替代，逐条标注红/回归锁。

    private fun dlAction(name: String = "f.mp4") = SyncAction(SyncActionType.DOWNLOAD, name, 1000L, 0L)

    /** existing 行——只被读 id，metadata 窗口不触达。 */
    private fun existingRow(rowId: Long = 42L): VideoItemEntity = mockk {
        every { id } returns rowId
    }

    /** 元数据窗口 getById 返回的完整行（真实 data class，供 copy）。 */
    private fun finalizedEntity(id: Long) = VideoItemEntity(
        id = id, syncRelationId = 1L, relativePath = "videos/f.mp4", fileName = "f.mp4",
        fileSize = 1L
    )

    /**
     * BUG-007 公共下载 harness（抄 ClientReuseTest 的 stubBasics + stubSuccessfulDownloadPath）。
     * `store.usableSpace()` 必须 Long.MAX_VALUE——relaxed 默认 0 会命中 E9 复检 INSUFFICIENT_SPACE。
     * 返回 lane client 供用例覆写 downloadTo。
     */
    private fun stubDownloadRun(): RemoteFileClient {
        stubBasics()
        every { storagePaths.toLocalRelativePath(any(), any()) } answers {
            val base = firstArg<String>()
            val rel = secondArg<String>()
            if (base.isNotEmpty()) "$base/$rel" else rel
        }
        every { store.usableSpace() } returns Long.MAX_VALUE
        coEvery { videoRepository.getByRelativePathAndRelationId(any(), 1L) } returns null
        coEvery { store.openWriteSink(any(), any()) } returns mockk<OutputStream>(relaxed = true)
        coEvery { store.finalize(any()) } returns true
        val client = mockk<RemoteFileClient>(relaxed = true)
        every { clientFactory.create(SyncProtocol.SMB) } returns client
        coEvery { client.downloadTo(any(), any(), any()) } returns true
        return client
    }

    /** 单 action 下载执行，捕获抛出的异常（CE 场景公共入口）。 */
    private suspend fun catchExecute(action: SyncAction = dlAction()): Throwable? = try {
        sut.executeSyncLocked(1L, listOf(action), emptyList())
        null
    } catch (e: Throwable) {
        e
    }


    @Test
    fun `toDelete with no DB row counts as success`() = runTest {
        stubBasics()
        coEvery { videoRepository.getByRelativePathAndRelationId("gone.mp4", 1L) } returns null

        assertTrue(sut.executeSyncLocked(1L, emptyList(), listOf(delAction("gone.mp4"))))
    }

    /** 计划 #1（红）：finalize 确认成功后取消（verifyReadable 处）——COMMITTED 回滚 final 文件并删 existing 行，恰一次。 */
    @Test
    fun `cancellation after confirmed finalize rolls back final file and deletes existing row`() = runTest {
        stubDownloadRun()
        coEvery { videoRepository.getByRelativePathAndRelationId(any(), 1L) } returns existingRow(42L)
        coEvery { store.verifyReadable(42L, 1000L) } throws CancellationException("bug007-verify-cancel")

        val thrown = catchExecute()

        assertTrue("expected CancellationException but was ${thrown?.javaClass}", thrown is CancellationException)
        coVerify(exactly = 1) { store.delete(42L) }
        coVerify(exactly = 0) { store.cancel(42L) }
        // exactly=1 同时锁定恰一次回滚：attempt 级清理 + 外层补偿不得双写（v2 双重回滚形态会是 2）
        coVerify(exactly = 1) { videoRepository.deleteById(42L) }
    }

    /** 计划 #2（红）：new-file 变体——取消后无幽灵密文、无残留占位行。 */
    @Test
    fun `cancellation after confirmed finalize on new file removes ghost ciphertext and placeholder row`() = runTest {
        stubDownloadRun()
        coEvery { videoRepository.upsert(any()) } returns 4242L
        coEvery { store.verifyReadable(4242L, 1000L) } throws CancellationException("bug007-new-file-cancel")

        val thrown = catchExecute()

        assertTrue(thrown is CancellationException)
        coVerify(exactly = 1) { store.delete(4242L) }
        coVerify(exactly = 0) { store.cancel(any()) }
        coVerify(exactly = 1) { videoRepository.deleteById(4242L) }
    }

    /** 计划 #3（回归锁）：finalize 前取消——NOT_FINALIZED 语义：cancel tmp、删占位行、绝不删 final、绝不探测。 */
    @Test
    fun `cancellation before finalize keeps legacy cancel semantics`() = runTest {
        val client = stubDownloadRun()
        coEvery { videoRepository.upsert(any()) } returns 4242L
        coEvery { client.downloadTo(any(), any(), any()) } throws CancellationException("bug007-transfer-cancel")

        val thrown = catchExecute()

        assertTrue(thrown is CancellationException)
        coVerify(exactly = 1) { store.cancel(4242L) }
        coVerify(exactly = 0) { store.delete(any()) }
        coVerify(exactly = 1) { videoRepository.deleteById(4242L) }
        coVerify(exactly = 0) { store.finalize(any()) }
    }

    /** 计划 #4（回归锁）：verifyReadable IOException → STORAGE_ERROR 单点回滚（delete=1、cancel=0）与失败计数。 */
    @Test
    fun `verifyReadable IOException keeps single-point rollback and failure count`() = runTest {
        stubDownloadRun()
        coEvery { videoRepository.getByRelativePathAndRelationId(any(), 1L) } returns existingRow(42L)
        // F14/T10：探针签名演进为两参（id + 显式预期明文长 = action.fileSize）。
        // stub 用精确值（42, 1000)：若管线未把 action.fileSize 传入探针，此 stub 不命中
        // → 探针不抛 → 下方 assertFalse 失败，即参数流转被本用例锁定。
        coEvery { store.verifyReadable(42L, 1000L) } throws IOException("verify io boom")

        assertFalse(sut.executeSyncLocked(1L, listOf(dlAction()), emptyList()))

        coVerify(exactly = 1) { store.verifyReadable(42L, 1000L) }
        coVerify(exactly = 1) { store.delete(42L) }
        coVerify(exactly = 0) { store.cancel(any()) }
        coVerify(exactly = 1) { videoRepository.deleteById(42L) }
        val p = publisher.value
        assertEquals(SyncPhase.PARTIAL_FAILED, p.phase)
        assertEquals(1, p.failedFiles)
    }

    /** F14/T10（A-T10-3 集成锁）：成功路径探针恰执行一次且收到 action.fileSize 作为预期明文长。 */
    @Test
    fun `read probe receives action fileSize as expected plaintext length on success`() = runTest {
        stubDownloadRun()
        coEvery { videoRepository.getByRelativePathAndRelationId(any(), 1L) } returns existingRow(42L)

        assertTrue(sut.executeSyncLocked(1L, listOf(dlAction()), emptyList()))

        // dlAction() fileSize=1000L：探针必须收到 (42, 1000)——不依赖 DB 最终 metadata
        coVerify(exactly = 1) { store.verifyReadable(42L, 1000L) }
    }

    /** 计划 #5（红）：元数据读取窗口（getById）取消——外层 CE 补偿经 cleanUpAttempt(COMMITTED) 回滚已提交产物。 */
    @Test
    fun `cancellation during metadata read after finalize triggers outer rollback`() = runTest {
        stubDownloadRun()
        coEvery { videoRepository.getByRelativePathAndRelationId(any(), 1L) } returns existingRow(42L)
        coEvery { videoRepository.getById(42L) } throws CancellationException("bug007-meta-read-cancel")

        val thrown = catchExecute()

        assertTrue(thrown is CancellationException)
        coVerify(exactly = 1) { store.delete(42L) }
        coVerify(exactly = 1) { videoRepository.deleteById(42L) }
        coVerify(exactly = 0) { store.cancel(any()) }
    }

    /** 计划 #6（红）：最终元数据 upsert 本身取消（成功侧清零点之前）——同样走外层补偿回滚。 */
    @Test
    fun `cancellation during final metadata upsert itself triggers outer rollback`() = runTest {
        stubDownloadRun()
        coEvery { videoRepository.getByRelativePathAndRelationId(any(), 1L) } returns existingRow(42L)
        coEvery { videoRepository.getById(42L) } returns finalizedEntity(42L)
        coEvery { videoRepository.upsert(any()) } throws CancellationException("bug007-meta-upsert-cancel")

        val thrown = catchExecute()

        assertTrue(thrown is CancellationException)
        coVerify(exactly = 1) { store.delete(42L) }
        coVerify(exactly = 1) { videoRepository.deleteById(42L) }
    }

    /** 计划 #7（回归锁）：元数据 upsert 成功后取消——清零点已过，干净中断不回滚（与 #6 对照）。 */
    @Test
    fun `cancellation after metadata upsert success performs no rollback`() = runTest {
        stubDownloadRun()
        coEvery { videoRepository.getByRelativePathAndRelationId(any(), 1L) } returns existingRow(42L)
        coEvery { videoRepository.getById(42L) } returns finalizedEntity(42L)
        coEvery { videoMetadataExtractor.extractAndSave(any()) } throws CancellationException("bug007-extract-cancel")

        val thrown = catchExecute()

        assertTrue(thrown is CancellationException)
        coVerify(exactly = 0) { store.delete(any()) }
        coVerify(exactly = 0) { videoRepository.deleteById(any()) }
        assertEquals(SyncPhase.CANCELLED, publisher.value.phase)
    }

    /** 计划 #8（红）：finalize 边界 CE + 探测 ABSENT ⇒ 按「已提交」处理（existing 变体）。 */
    @Test
    fun `ambiguous finalize CE with tmp confirmed consumed treats pass as committed`() = runTest {
        stubDownloadRun()
        coEvery { videoRepository.getByRelativePathAndRelationId(any(), 1L) } returns existingRow(42L)
        coEvery { store.finalize(42L) } throws CancellationException("bug007-finalize-boundary")
        coEvery { store.probePendingTmp(42L) } returns TmpProbeState.ABSENT

        val thrown = catchExecute()

        assertTrue(thrown is CancellationException)
        coVerify(exactly = 1) { store.delete(42L) }
        coVerify(exactly = 0) { store.cancel(any()) }
        coVerify(exactly = 1) { videoRepository.deleteById(42L) }
    }

    /** 计划 #9（红）：finalize false + 探测 ABSENT（new 变体，双检误报 false 形状）——按已提交回滚；false 必须显式抛 FINALIZE_FAILED，不进 verify/元数据提交。 */
    @Test
    fun `ambiguous finalize false with tmp confirmed consumed rolls back committed state`() = runTest {
        stubDownloadRun()
        coEvery { videoRepository.upsert(any()) } returns 4242L
        coEvery { store.finalize(any()) } returns false
        coEvery { store.probePendingTmp(4242L) } returns TmpProbeState.ABSENT

        assertFalse(sut.executeSyncLocked(1L, listOf(dlAction()), emptyList()))
        assertEquals(1, publisher.value.failedFiles)

        coVerify(exactly = 1) { store.delete(4242L) }
        coVerify(exactly = 0) { store.cancel(any()) }
        coVerify(exactly = 1) { videoRepository.deleteById(4242L) }
        // 元数据 upsert 绝不发生（withMeta 的 fileSize=action.fileSize=1000；占位行是 0）
        coVerify(exactly = 0) { videoRepository.upsert(match { it.fileSize == 1000L }) }
        coVerify(exactly = 0) { store.verifyReadable(any(), any()) }
    }

    /** 计划 #10（回归锁）：finalize false + 探测 PRESENT——rename 未发生，cancel 路径、不删 final、existing 行保留。 */
    @Test
    fun `finalize false with tmp still present keeps legacy cancel semantics`() = runTest {
        stubDownloadRun()
        coEvery { videoRepository.getByRelativePathAndRelationId(any(), 1L) } returns existingRow(42L)
        coEvery { store.finalize(42L) } returns false
        coEvery { store.probePendingTmp(42L) } returns TmpProbeState.PRESENT

        assertFalse(sut.executeSyncLocked(1L, listOf(dlAction()), emptyList()))

        coVerify(exactly = 0) { store.delete(any()) }
        coVerify(exactly = 1) { store.cancel(42L) }
        coVerify(exactly = 0) { videoRepository.deleteById(any()) }
    }

    /** 计划 #11（回归锁）：NOT_FINALIZED 且 tmp 缺失（openWriteSink 失败即打开失败）——旧 final 完好、行保留、绝不探测。 */
    @Test
    fun `NOT_FINALIZED with tmp absent never deletes old final`() = runTest {
        stubDownloadRun()
        coEvery { videoRepository.getByRelativePathAndRelationId(any(), 1L) } returns existingRow(42L)
        coEvery { store.openWriteSink(42L, any()) } throws SyncException(
            SyncErrorCode.STORAGE_ERROR, SyncOperation.DOWNLOAD, path = "f.mp4"
        )

        assertFalse(sut.executeSyncLocked(1L, listOf(dlAction()), emptyList()))

        coVerify(exactly = 0) { store.delete(any()) }
        // no-op 但显式调用（NOT_FINALIZED 契约：只 cancel tmp）
        coVerify(exactly = 1) { store.cancel(42L) }
        // existing 行保留
        coVerify(exactly = 0) { videoRepository.deleteById(any()) }
        // NOT_FINALIZED 不进探测（v2 草案在 tmp 缺失变体会误删 final——评审 #3 数据丢失窗口）
        coVerify(exactly = 0) { store.probePendingTmp(any()) }
    }

    /** 计划 #12（红）：探测 UNKNOWN——保守只删行、不删 final（旧实现 existing 时保行 → 红）。 */
    @Test
    fun `probe UNKNOWN keeps row-only conservative cleanup`() = runTest {
        stubDownloadRun()
        coEvery { videoRepository.getByRelativePathAndRelationId(any(), 1L) } returns existingRow(42L)
        coEvery { store.finalize(42L) } throws CancellationException("bug007-finalize-ce")
        coEvery { store.probePendingTmp(42L) } returns TmpProbeState.UNKNOWN

        val thrown = catchExecute()

        assertTrue(thrown is CancellationException)
        coVerify(exactly = 0) { store.delete(any()) }
        coVerify(exactly = 1) { videoRepository.deleteById(42L) }
    }

    /** 计划 #13（红）：清理步骤失败不掩蔽原 CE（实例同一）且不跳过后续删行（规则 1 不短路）。 */
    @Test
    fun `cleanup failure neither masks original CE nor skips row deletion`() = runTest {
        stubDownloadRun()
        coEvery { videoRepository.getByRelativePathAndRelationId(any(), 1L) } returns existingRow(42L)
        val original = CancellationException("bug007-original-ce")
        coEvery { store.verifyReadable(42L, 1000L) } throws original
        coEvery { store.delete(42L) } throws RuntimeException("cleanup boom")

        val thrown = catchExecute()

        // 偏差记录：kotlinx 协程机制（coroutineScope + workerScope.cancel 路径）在 await/dispatch
        // 边界替换 CE 实例（消息保留、实例不保）——`===` 在 executeSyncLocked 边界不可观测
        // （scratch 实验 B/C 验证）。以「类型 + 消息」判别替代实例同一：cleanup boom（非 CE）
        // 或任何替换 CE 均仍使本断言失败，防护意图不变。
        assertTrue(
            "original CE (message) must propagate over cleanup failure, got ${thrown?.javaClass}: ${thrown?.message}",
            thrown is CancellationException && thrown.message == original.message
        )
        coVerify(exactly = 1) { store.delete(42L) }
        coVerify(exactly = 1) { videoRepository.deleteById(42L) }
    }

    /** 计划 #13b（红，v3.1）：探测自身抛 CE 不掩蔽原 CE、不跳过保守清理（探测异常收敛 UNKNOWN 后继续）。 */
    @Test
    fun `probe throwing CE neither masks original CE nor skips conservative cleanup`() = runTest {
        stubDownloadRun()
        coEvery { videoRepository.getByRelativePathAndRelationId(any(), 1L) } returns existingRow(42L)
        val original = CancellationException("bug007-original-ce")
        coEvery { store.finalize(42L) } throws original
        coEvery { store.probePendingTmp(42L) } throws CancellationException("bug007-probe-ce")

        val thrown = catchExecute()

        // 偏差记录同 #13：实例同一不可观测，以「类型 + 消息」判别——探测 CE（bug007-probe-ce）
        // 若替换原 CE（bug007-original-ce）本断言仍失败。
        assertTrue(
            "original CE (message) must survive probe CE, got ${thrown?.javaClass}: ${thrown?.message}",
            thrown is CancellationException && thrown.message == original.message
        )
        // 探测异常收敛 UNKNOWN → 保守删行照常；不删 final；不走 PRESENT 的 cancel 分支
        coVerify(exactly = 1) { videoRepository.deleteById(42L) }
        coVerify(exactly = 0) { store.delete(42L) }
        coVerify(exactly = 0) { store.cancel(42L) }
    }

    /**
     * 计划 #14（回归锁，验收拆分）：孤儿密文不被下次下载自动回收——有状态 fake repository +
     * fake store 调用记录，切片断言第二轮新增调用绝不含 42；第二轮完成后索引/可播放状态恢复。
     */
    @Test
    fun `orphan ciphertext is not reclaimed by next download`() = runTest {
        stubDownloadRun()
        // 有状态 fake repository：deleteById 真删行；下一轮占位 upsert 分配新 id 999（不复用 42）
        val rows = LinkedHashMap<Long, VideoItemEntity>()
        rows[42L] = finalizedEntity(42L)
        coEvery { videoRepository.getByRelativePathAndRelationId(any(), 1L) } coAnswers {
            rows.values.firstOrNull { it.relativePath == firstArg<String>() }
        }
        coEvery { videoRepository.getById(any()) } coAnswers { rows[firstArg<Long>()] }
        coEvery { videoRepository.upsert(any()) } coAnswers {
            val e = firstArg<VideoItemEntity>()
            if (e.id == 0L) {
                rows[999L] = e.copy(id = 999L)
                999L
            } else {
                rows[e.id] = e
                e.id
            }
        }
        coEvery { videoRepository.deleteById(any()) } coAnswers { rows.remove(firstArg<Long>()) }

        // fake store：有序调用记录
        val storeCalls = CopyOnWriteArrayList<String>()
        val round = AtomicInteger(1)
        coEvery { store.openWriteSink(any(), any()) } coAnswers {
            storeCalls.add("openSink:${firstArg<Long>()}")
            mockk<OutputStream>(relaxed = true)
        }
        coEvery { store.finalize(any()) } coAnswers {
            if (round.get() == 1) throw CancellationException("bug007-r1-finalize-ce")
            storeCalls.add("finalize:${firstArg<Long>()}")
            true
        }
        coEvery { store.probePendingTmp(any()) } coAnswers {
            storeCalls.add("probe:${firstArg<Long>()}")
            TmpProbeState.ABSENT
        }
        coEvery { store.delete(any()) } coAnswers {
            val id = firstArg<Long>()
            storeCalls.add("delete:$id")
            round.get() != 1 // 第一轮 delete(42) 模拟删除失败 → 42.enc 孤儿人为保留
        }
        coEvery { store.cancel(any()) } coAnswers {
            storeCalls.add("cancel:${firstArg<Long>()}")
            true
        }
        coEvery { store.verifyReadable(any(), any()) } coAnswers {
            storeCalls.add("verify:${firstArg<Long>()}")
        }

        // 第一轮：finalize CE + probe ABSENT → 行删 + 密文删路径走完，但 42.enc 保留（delete 返回 false）
        val round1 = catchExecute()
        assertTrue(round1 is CancellationException)
        coVerify(exactly = 1) { videoRepository.deleteById(42L) }
        assertTrue("row 42 must be gone after round 1", rows.isEmpty())

        val snapshot = storeCalls.toList()

        // 第二轮：同 relativePath 重下 → 占位行 id=999，openSink/finalize 全程只触 999
        round.set(2)
        assertTrue(sut.executeSyncLocked(1L, listOf(dlAction()), emptyList()))

        val slice = storeCalls.drop(snapshot.size)
        assertTrue("round-2 slice must be non-empty", slice.isNotEmpty())
        assertTrue("round-2 must only touch id 999: $slice", slice.all { it.endsWith(":999") })
        assertFalse("round-2 must never touch orphan id 42: $slice", slice.any { it.endsWith(":42") })
        // 索引/可播放状态恢复：行存在且最终元数据（fileSize）已回写
        val restored = rows[999L]
        assertNotNull(restored)
        assertEquals(1000L, restored?.fileSize)
    }

    // ------------------------------------------------------------------
    // 复审阻断（BUG-007 后续·普通异常/null）：finalize 成功后的元数据窗口——
    // getById 抛普通异常 / upsert 抛普通异常 / getById 返回 null。
    // 三者都须经同一 NonCancellable 清理入口（COMMITTED 回滚）收束并计失败，
    // 绝不留「密文已更新 + 旧行仍旧 IV」或「孤儿密文 + SUCCESS 计数」。
    // ------------------------------------------------------------------

    @Test
    fun `metadata read throwing normal exception after finalize rolls back and counts failure`() = runTest {
        stubDownloadRun()
        coEvery { videoRepository.getByRelativePathAndRelationId(any(), 1L) } returns existingRow(42L)
        coEvery { videoRepository.getById(42L) } throws IOException("meta-read-boom")

        assertFalse(sut.executeSyncLocked(1L, listOf(dlAction()), emptyList()))

        // 密文/索引一致性：COMMITTED 回滚——密文删除 + 行删除（下次 dryRun 重下）
        coVerify(exactly = 1) { store.delete(42L) }
        coVerify(exactly = 1) { videoRepository.deleteById(42L) }
        coVerify(exactly = 0) { store.cancel(any()) }
        assertEquals(1, publisher.value.failedFiles)
        assertEquals(0, publisher.value.completedFiles)
        coVerify(exactly = 1) { syncLogRepository.log("DOWNLOAD", any(), "FAILED", any(), any(), any()) }
        coVerify(exactly = 0) { syncLogRepository.log("DOWNLOAD", any(), "SUCCESS", any(), any(), any()) }
    }

    @Test
    fun `final metadata upsert throwing normal exception rolls back and counts failure`() = runTest {
        stubDownloadRun()
        coEvery { videoRepository.getByRelativePathAndRelationId(any(), 1L) } returns existingRow(42L)
        coEvery { videoRepository.getById(42L) } returns finalizedEntity(42L)
        coEvery { videoRepository.upsert(any()) } throws IOException("meta-upsert-boom")

        assertFalse(sut.executeSyncLocked(1L, listOf(dlAction()), emptyList()))

        // 旧行不再以旧 IV 存活（行已删——索引/密文一致），密文回滚
        coVerify(exactly = 1) { store.delete(42L) }
        coVerify(exactly = 1) { videoRepository.deleteById(42L) }
        coVerify(exactly = 0) { store.cancel(any()) }
        assertEquals(1, publisher.value.failedFiles)
        assertEquals(0, publisher.value.completedFiles)
        coVerify(exactly = 0) { syncLogRepository.log("DOWNLOAD", any(), "SUCCESS", any(), any(), any()) }
    }

    @Test
    fun `null metadata row after finalize is explicit failure with cleanup not success`() = runTest {
        stubDownloadRun()
        coEvery { videoRepository.getByRelativePathAndRelationId(any(), 1L) } returns existingRow(42L)
        coEvery { videoRepository.getById(42L) } returns null

        // 行在 finalize 提交后消失：孤儿密文 → 明确失败 + 清理，绝不 SUCCESS 计数
        assertFalse(sut.executeSyncLocked(1L, listOf(dlAction()), emptyList()))

        coVerify(exactly = 1) { store.delete(42L) }
        coVerify(exactly = 1) { videoRepository.deleteById(42L) }
        coVerify(exactly = 0) { store.cancel(any()) }
        assertEquals(1, publisher.value.failedFiles)
        assertEquals(0, publisher.value.completedFiles)
        coVerify(exactly = 0) { syncLogRepository.log("DOWNLOAD", any(), "SUCCESS", any(), any(), any()) }
        coVerify(exactly = 1) { syncLogRepository.log("DOWNLOAD", any(), "FAILED", any(), any(), any()) }
    }

    /**
     * 复审阻断（敏感日志红线）：失败下载的 DebugLogger 环形缓冲（DebugLogScreen UI 数据源）
     * 不得出现 relative path / 文件名 token。android.util.Log 在单测为 no-op 不可捕获——
     * Log.w/e 站点以源码级验证 + SyncSafeLog 纯函数测试收束。
     */
    @Test
    fun `failing download logs no path or filename tokens to debug ring`() = runTest {
        DebugLogger.clear()
        stubDownloadRun()
        coEvery { videoRepository.getByRelativePathAndRelationId(any(), 1L) } returns existingRow(42L)
        coEvery { store.verifyReadable(42L, 1000L) } throws IOException("verify-io-boom") // 稳定失败路径

        assertFalse(sut.executeSyncLocked(1L, listOf(dlAction("secret-dir/f.mp4")), emptyList()))

        val leaked = DebugLogger.snapshot().filter { entry ->
            entry.message.contains("f.mp4") || entry.message.contains("secret-dir")
        }
        assertTrue("debug ring leaked path/filename: $leaked", leaked.isEmpty())
    }
}
