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
import cn.vectory.syncplayer.storage.MediaFileStore
import cn.vectory.syncplayer.storage.StoragePaths
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * A1 attempt 闸门（lane A，A3 重构）：**fake sink** 下的闸门/临界段/主键传递测试。
 *
 * ⚠️ 边界声明（A3，rev-gpt）：本文件 store/repository 均为 mock/fake——
 * 「真实长度校验 + 真实 final 字节」组合在 [SyncDownloadPipelineRealStorageTest]
 * （生产 MediaFileStore + 临时目录），本文件不再复制生产长度校验逻辑
 * （旧 LengthCheckSink 已删）。DB 侧为 fake repository（LinkedHashMap），
 * 非真实 SQLite——列所有权的真实 Room 验证在 VideoItemDaoColumnOwnershipTest。
 *
 * 逐用例红证据（旧缺陷）见各测试注释；本文件所有断言均为静态预期，未在本机执行
 * （Gradle 由 verifier 统一执行）。
 */
class SyncDownloadPipelineAttemptGateTest {

    private val syncRelationRepository = mockk<SyncRelationRepository>(relaxed = true)
    private val syncSourceRepository = mockk<SyncSourceRepository>()
    private val videoRepository = mockk<VideoRepository>(relaxed = true)
    private val playbackRepository = mockk<PlaybackRepository>(relaxed = true)
    private val syncLogRepository = mockk<SyncLogRepository>(relaxed = true)
    private val videoMetadataExtractor = mockk<VideoMetadataExtractor>(relaxed = true)
    private val imageMetadataExtractor = mockk<ImageMetadataExtractor>(relaxed = true)
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
    private val sut = SyncDownloadPipeline(
        syncRelationRepository, syncSourceRepository, videoRepository, playbackRepository,
        syncLogRepository, videoMetadataExtractor, imageMetadataExtractor,
        clientFactory, storagePaths, storeLazy, context, publisher, deleteExecutor
    )

    /**
     * A6：屏障/取消类测试专用 sut——lane worker（生产 launch(ioDispatcher)）默认跑
     * 真实 Dispatchers.IO。**A5 的「调度器不同步即根因」归因已撤回**：真正问题是
     * SyncSpaceChecker.checkSpace 在 lane 创建前 `withContext(Dispatchers.IO)` 执行，
     * testScheduler.runCurrent() 从根本上等不到任意外部 IO 推进。故到达/收束**不再**
     * 依赖 runCurrent，改用真实 reached 信号（CompletableDeferred）+ select/join
     * 挂起等待（见两个屏障测试）。此处注入 StandardTestDispatcher 只为屏障后取消
     * 链的确定性，不构成任何根因断言。非屏障测试沿用默认 sut（live 已通过）。
     */
    private fun schedulerSut(scheduler: TestCoroutineScheduler) = SyncDownloadPipeline(
        syncRelationRepository, syncSourceRepository, videoRepository, playbackRepository,
        syncLogRepository, videoMetadataExtractor, imageMetadataExtractor,
        clientFactory, storagePaths, storeLazy, context, publisher, deleteExecutor,
        StandardTestDispatcher(scheduler)
    )

    private fun dlAction() = SyncAction(SyncActionType.DOWNLOAD, "f.mp4", 1000L, 5000L)
    private fun imgAction() = SyncAction(SyncActionType.DOWNLOAD, "f.jpg", 1000L, 5000L)

    /** 有状态 fake repository：行真实可删可改（静态 stub 无法复现列所有权因果）。 */
    private val rows = LinkedHashMap<Long, VideoItemEntity>()
    private val storeCalls = CopyOnWriteArrayList<String>()
    /** 占位主键顺序分配：4242、4243……（A1-02 需要每次重试重分配的新 id）。 */
    private val idSeq = AtomicLong(4241L)

    /** 现有行（id=42）：用户列带非默认值，供所有权断言。 */
    private fun seedExistingRow(): VideoItemEntity = VideoItemEntity(
        id = 42L, syncRelationId = 1L, relativePath = "videos/f.mp4", parentPath = "videos",
        fileName = "f.mp4", fileSize = 1L, duration = 77L, star = 3, audioGainDb = 2.5f,
        mediaType = "VIDEO"
    )

    private fun stubBasics() {
        coEvery { syncRelationRepository.getById(1L) } returns SyncRelation(
            id = 1L, name = "r", syncSourceId = 10L, remotePath = "/remote", localBasePath = "videos"
        )
        coEvery { syncSourceRepository.getById(10L) } returns SyncSource(
            id = 10L, name = "s", protocol = SyncProtocol.SMB, host = "h", port = 445,
            username = "u", password = "p"
        )
        every { storagePaths.toLocalRelativePath(any(), any()) } answers {
            val base = firstArg<String>()
            val rel = secondArg<String>()
            if (base.isNotEmpty()) "$base/$rel" else rel
        }
        every { store.usableSpace() } returns Long.MAX_VALUE
        coEvery { store.openWriteSink(any(), any()) } coAnswers {
            storeCalls.add("openSink:${firstArg<Long>()}")
            mockk<OutputStream>(relaxed = true)
        }
        coEvery { store.finalize(any()) } coAnswers {
            storeCalls.add("finalize:${firstArg<Long>()}")
            true
        }
        coEvery { store.delete(any()) } coAnswers {
            storeCalls.add("delete:${firstArg<Long>()}")
            true
        }
        coEvery { store.cancel(any()) } coAnswers {
            storeCalls.add("cancel:${firstArg<Long>()}")
            true
        }
        coEvery { store.verifyReadable(any(), any()) } coAnswers {
            storeCalls.add("verify:${firstArg<Long>()}")
        }
        // fake repository：existing 行由 seedExistingRow 放入；占位 upsert 顺序分配新 id
        coEvery { videoRepository.getByRelativePathAndRelationId(any(), 1L) } coAnswers {
            rows.values.firstOrNull { it.relativePath == firstArg<String>() }
        }
        coEvery { videoRepository.upsert(any()) } coAnswers {
            val e = firstArg<VideoItemEntity>()
            if (e.id == 0L) {
                val newId = idSeq.incrementAndGet()
                rows[newId] = e.copy(id = newId)
                newId
            } else {
                rows[e.id] = e
                e.id
            }
        }
        // A1 列提交 fake：行存在 ⇒ 改 3 列返回 1；行消失 ⇒ 返回 0（不复活）
        coEvery {
            videoRepository.updateSyncMetadata(any(), any(), any(), any())
        } coAnswers {
            val id = firstArg<Long>()
            val row = rows[id] ?: return@coAnswers 0
            rows[id] = row.copy(
                fileSize = secondArg(),
                remoteModifiedTime = thirdArg(),
                dateSynced = arg(3),
            )
            1
        }
        coEvery { videoRepository.deleteById(any()) } coAnswers { rows.remove(firstArg<Long>()) }
    }

    /**
     * 受控 client（A3）：第 n 次调用向 sink 写入 byteCounts[n-1] 字节并返回 true。
     * 不注入 close 故障（长度语义见 RealStorage 测试）；close 故障由
     * [stubSinkCloseFailsFirstN] 单独注入，二者可独立组合。
     */
    private fun stubClient(byteCounts: List<Int>): RemoteFileClient {
        val client = mockk<RemoteFileClient>(relaxed = true)
        val calls = AtomicInteger(0)
        every { clientFactory.create(SyncProtocol.SMB) } returns client
        coEvery { client.downloadTo(any(), any(), any()) } coAnswers {
            val sink = secondArg<OutputStream>()
            val n = calls.incrementAndGet()
            sink.write(ByteArray(byteCounts.getOrElse(n - 1) { byteCounts.last() }))
            true
        }
        return client
    }

    /**
     * 前 [n] 次 openWriteSink 返回 close 必抛 IOException 的 sink（此后恢复 relaxed
     * mock sink）。用于闸门重试与重分配路径——这里刻意用「人为炸 close」：本文件
     * 不测长度校验语义，只测 close 失败后的重试/清理/主键行为。
     */
    private fun stubSinkCloseFailsFirstN(n: Int) {
        val opens = AtomicInteger(0)
        coEvery { store.openWriteSink(any(), any()) } coAnswers {
            storeCalls.add("openSink:${firstArg<Long>()}")
            if (opens.incrementAndGet() <= n) {
                object : OutputStream() {
                    override fun write(b: Int) {}
                    override fun write(b: ByteArray, off: Int, len: Int) {}
                    override fun close() {
                        storeCalls.add("closeFail")
                        throw IOException("sink close boom")
                    }
                }
            } else {
                mockk<OutputStream>(relaxed = true)
            }
        }
    }

    // ------------------------------------------------------------------ A1-1 close 闸门（fake sink）

    /**
     * A1-1（红，fake-sink 闸门锁）：close 抛 IOException 后必须进入第 2 次传输。
     * 红证据（旧代码）：downloadTo 返回即置 downloadSuccess ⇒ 循环带毒退出 ⇒
     * finalize 恰 0 次（exactly=1 断言失败）。真实长度校验形态在 RealStorage 测试。
     */
    @Test
    fun `close IOException on overwrite actually retries and second attempt succeeds`() = runTest {
        stubBasics()
        stubSinkCloseFailsFirstN(1)
        val client = stubClient(listOf(1000, 1000))
        rows[42L] = seedExistingRow()

        val result = sut.executeSyncLocked(1L, listOf(dlAction()), emptyList())

        assertTrue("attempt 2 must complete the commit after attempt 1 close failure", result)
        assertEquals(0, publisher.value.failedFiles)
        assertEquals(1, publisher.value.completedFiles)
        coVerify(exactly = 2) { client.downloadTo(any(), any(), any()) }
        coVerify(exactly = 1) { store.finalize(42L) }
        coVerify(exactly = 1) { store.verifyReadable(42L, 1000L) }
        // attempt 1 清理：NOT_FINALIZED ⇒ cancel tmp、绝不删 final、existing 行保留
        coVerify(exactly = 1) { store.cancel(42L) }
        coVerify(exactly = 0) { store.delete(any()) }
        coVerify(exactly = 1) { videoRepository.updateSyncMetadata(42L, 1000L, 5000L, any()) }
        assertEquals(1000L, rows[42L]?.fileSize)
    }

    /**
     * A1-1（红，fake-sink 闸门锁）：close 失败重试耗尽 ⇒ 保留旧 final、绝不提交元数据。
     * 红证据（旧代码）：result 为 true（带毒成功路径）⇒ assertFalse 失败。
     * 旧 final 字节级不变断言在 RealStorage 测试（本文件 store 为 mock）。
     */
    @Test
    fun `close IOException exhausted keeps old final and counts failure`() = runTest {
        stubBasics()
        stubSinkCloseFailsFirstN(3)
        val client = stubClient(listOf(1000))
        rows[42L] = seedExistingRow()

        val result = sut.executeSyncLocked(1L, listOf(dlAction()), emptyList())

        assertFalse("exhausted close failure must be a failed download, not success", result)
        assertEquals(1, publisher.value.failedFiles)
        assertEquals(0, publisher.value.completedFiles)
        coVerify(exactly = 3) { client.downloadTo(any(), any(), any()) }
        coVerify(exactly = 3) { store.cancel(42L) }
        coVerify(exactly = 0) { store.delete(any()) }
        coVerify(exactly = 0) { store.probePendingTmp(any()) }
        coVerify(exactly = 0) { store.finalize(any()) }
        coVerify(exactly = 0) { store.verifyReadable(any(), any()) }
        coVerify(exactly = 0) { videoRepository.updateSyncMetadata(any(), any(), any(), any()) }
        assertEquals(1L, rows[42L]?.fileSize)
        coVerify(exactly = 1) {
            syncLogRepository.log("DOWNLOAD", any(), "FAILED", any(), any(), any())
        }
    }

    /** A1-1（回归锁）：downloadTo 返回 false 仍是可重试失败，3 次耗尽计一次失败。 */
    @Test
    fun `downloadTo returning false stays a retryable failure`() = runTest {
        stubBasics()
        val client = mockk<RemoteFileClient>(relaxed = true)
        every { clientFactory.create(SyncProtocol.SMB) } returns client
        coEvery { client.downloadTo(any(), any(), any()) } returns false

        val result = sut.executeSyncLocked(1L, listOf(dlAction()), emptyList())

        assertFalse(result)
        assertEquals(1, publisher.value.failedFiles)
        coVerify(exactly = 0) { store.finalize(any()) }
        coVerify(exactly = 0) { videoRepository.updateSyncMetadata(any(), any(), any(), any()) }
    }

    // ------------------------------------------------------------------ A1-01 提交临界段

    /**
     * A1-01（红）+ A3 ensureActive：DB 副作用完成、suspend 返回恢复点取消——
     * CE 必须传播，且**绝不进入提取器**（A3：NonCancellable 退出后显式
     * ensureActive），已提交的行与 final 绝不被外层补偿误删。
     * 屏障：updateSyncMetadata 的 fake 先完成行更新再挂起（真实 suspend DAO
     * 返回恢复点的取消窗口）；取消后放行 ⇒ NC 保护区完成（清零登记）⇒
     * ensureActive 在提取器之前交付 CE。
     * 红证据（旧代码）：清零在裸 suspend 之后 ⇒ 外层补偿删行+final（exactly=0 失败）；
     * A2 代码（无 ensureActive）：CE 迟至下一真实挂起点才交付，提取器已被进入
     * （exactly=0 失败）——本断言组即 A3 修复证据。
     *
     * A6（rev-gpt 方案）：到达用真实 `reached`（CompletableDeferred）信号，
     * select 等待 reached 或 job 提前结束（后者带全量现场 fail）；收束用 job.join()。
     * **不使用 runCurrent 作为到达/结束保证**（空间预检在 lane 前经真实
     * Dispatchers.IO，虚拟时间调度器等不到它）；无 sleep/轮询/盲 advanceUntilIdle。
     * try/finally 始终 cancel job 并 trySend 放行 NC，防测试失败死锁。
     */
    @Test
    fun `cancellation after DB commit skips extractor and keeps committed row and final`() = runTest {
        stubBasics()
        stubClient(listOf(1000))
        rows[42L] = seedExistingRow()
        val barrierSut = schedulerSut(testScheduler)
        val release = Channel<Int>(Channel.UNLIMITED)
        val reached = CompletableDeferred<Unit>()
        val enteredBarrier = CopyOnWriteArrayList<Long>()
        coEvery { videoRepository.updateSyncMetadata(any(), any(), any(), any()) } coAnswers {
            val id = firstArg<Long>()
            val row = rows[id]
            if (row != null) {
                rows[id] = row.copy(
                    fileSize = secondArg(),
                    remoteModifiedTime = thirdArg(),
                    dateSynced = arg(3),
                )
            }
            enteredBarrier.add(id)
            reached.complete(Unit) // ← 真实到达信号：副作用完成、挂起前
            release.receive() // ← 屏障：副作用完成、返回前挂起
            if (row != null) 1 else 0
        }
        val caught = CopyOnWriteArrayList<Throwable>()
        val returned = CopyOnWriteArrayList<Boolean>()
        val job = launch {
            try {
                val r = barrierSut.executeSyncLocked(1L, listOf(dlAction()), emptyList())
                returned.add(r)
            } catch (t: Throwable) {
                caught.add(t)
            }
        }
        try {
            select<Unit> {
                reached.onAwait { }
                job.onJoin { } // 提前结束（正常返回/异常/取消）——不该先于屏障
            }
            if (!reached.isCompleted) {
                fail(
                    "job finished before barrier: returned=$returned " +
                        "caught=${caught.map { it.javaClass.name }} storeCalls=$storeCalls " +
                        "phase=${publisher.value.phase}"
                )
            }
            assertEquals("worker must be parked at the barrier", listOf(42L), enteredBarrier)
            assertEquals("DB side effect must be committed at the barrier", 1000L, rows[42L]?.fileSize)

            job.cancel(CancellationException("barrier-cancel"))
            release.send(1) // 放行屏障 ⇒ NC 保护区收束（清零登记）⇒ ensureActive 交付 CE
            job.join() // 挂起等待最终收束（非 runCurrent）

            assertTrue(
                "CE must propagate to caller (caught=${caught.map { it.javaClass.name }})",
                caught.single() is CancellationException
            )
            // A3：提取器绝不被进入（旧 A2 代码在下一真实挂起点前已进入提取器）
            coVerify(exactly = 0) { videoMetadataExtractor.extractAndSave(any()) }
            coVerify(exactly = 0) { imageMetadataExtractor.extractAndSave(any()) }
            // 已提交产物保留：行 + final 都不动；CE 路径不计失败
            assertEquals(1000L, rows[42L]?.fileSize)
            assertEquals(5000L, rows[42L]?.remoteModifiedTime)
            assertEquals(3, rows[42L]?.star)
            coVerify(exactly = 0) { store.delete(any()) }
            coVerify(exactly = 0) { videoRepository.deleteById(any()) }
            coVerify(exactly = 0) {
                syncLogRepository.log("DOWNLOAD", any(), "FAILED", any(), any(), any())
            }
        } finally {
            // 失败防死锁（幂等）：已结束的 job 再 cancel 无害；NC 内挂起的 worker 必须放行
            job.cancel()
            release.trySend(1)
        }
    }

    /**
     * A1-01（回归锁）：UPDATE 本身失败（异常）⇒ 走 COMMITTED 单点回滚
     * （删 final + 删行各恰一次）并计一次失败。
     */
    @Test
    fun `sync metadata UPDATE failure rolls back via committed cleanup`() = runTest {
        stubBasics()
        stubClient(listOf(1000))
        rows[42L] = seedExistingRow()
        coEvery { videoRepository.updateSyncMetadata(any(), any(), any(), any()) } throws
            RuntimeException("db down")

        val result = sut.executeSyncLocked(1L, listOf(dlAction()), emptyList())

        assertFalse(result)
        assertEquals(1, publisher.value.failedFiles)
        coVerify(exactly = 1) { store.delete(42L) }
        coVerify(exactly = 1) { videoRepository.deleteById(42L) }
        coVerify(exactly = 1) {
            syncLogRepository.log("DOWNLOAD", any(), "FAILED", any(), any(), any())
        }
    }

    /**
     * A3（红，ensureActive 0 行分支）：列提交 0 行的回滚清理（NC 保护区内部）
     * 期间被取消——清理完整收束恰一次，退出保护区后交付 CE（而非 STORAGE_ERROR
     * 失败路径），绝不重复补偿、绝不计失败。
     * 屏障在 store.delete 内：cleanup 执行到删 final 时挂起 → 取消 → 放行。
     * 红证据（A2 代码，无 ensureActive）：NC 退出后走 commitFailed ⇒ STORAGE_ERROR
     * ⇒ generic catch 计失败 + FAILED 日志（本测试 exactly=0 断言失败）。
     *
     * A6（rev-gpt 方案）：同上——reached 真实信号 + select + join；不依赖 runCurrent。
     */
    @Test
    fun `cancellation during zero-row cleanup delivers CE with exactly one cleanup`() = runTest {
        stubBasics()
        stubClient(listOf(1000))
        rows[42L] = seedExistingRow()
        val barrierSut = schedulerSut(testScheduler)
        coEvery { videoRepository.updateSyncMetadata(any(), any(), any(), any()) } coAnswers {
            rows.remove(firstArg<Long>()) // 0 行：行已在 finalize 后被并发删除
            0
        }
        val release = Channel<Int>(Channel.UNLIMITED)
        val reached = CompletableDeferred<Unit>()
        val enteredBarrier = CopyOnWriteArrayList<Long>()
        coEvery { store.delete(any()) } coAnswers {
            storeCalls.add("delete:${firstArg<Long>()}")
            enteredBarrier.add(firstArg<Long>())
            reached.complete(Unit) // ← 真实到达信号：删 final 挂起前
            release.receive() // ← 屏障：回滚删 final 时挂起（仍处 NC 保护区）
            true
        }
        val caught = CopyOnWriteArrayList<Throwable>()
        val returned = CopyOnWriteArrayList<Boolean>()
        val job = launch {
            try {
                val r = barrierSut.executeSyncLocked(1L, listOf(dlAction()), emptyList())
                returned.add(r)
            } catch (t: Throwable) {
                caught.add(t)
            }
        }
        try {
            select<Unit> {
                reached.onAwait { }
                job.onJoin { } // 提前结束——不该先于回滚屏障
            }
            if (!reached.isCompleted) {
                fail(
                    "job finished before rollback barrier: returned=$returned " +
                        "caught=${caught.map { it.javaClass.name }} storeCalls=$storeCalls " +
                        "phase=${publisher.value.phase}"
                )
            }
            assertEquals("rollback must be parked at store.delete barrier", listOf(42L), enteredBarrier)

            job.cancel(CancellationException("cleanup-cancel"))
            release.send(1) // 放行 ⇒ NC 内清理继续收束（删行 + 登记清零）⇒ ensureActive 交付 CE
            job.join()

            assertTrue(
                "CE must be the final outcome (caught=${caught.map { it.javaClass.name }})",
                caught.single() is CancellationException
            )
            // 清理恰一次：删 final + 删行各恰 1 次，绝无二次补偿
            coVerify(exactly = 1) { store.delete(42L) }
            coVerify(exactly = 1) { videoRepository.deleteById(42L) }
            // A6 补：回滚路径绝无任何提取器进入（CE 在提取之前交付）
            coVerify(exactly = 0) { videoMetadataExtractor.extractAndSave(any()) }
            coVerify(exactly = 0) { imageMetadataExtractor.extractAndSave(any()) }
            // CE 路径：不计成功、不计普通失败、无 FAILED 日志、无复活
            assertEquals(0, publisher.value.failedFiles)
            assertEquals(0, publisher.value.completedFiles)
            coVerify(exactly = 0) {
                syncLogRepository.log("DOWNLOAD", any(), "FAILED", any(), any(), any())
            }
            coVerify(exactly = 0) { videoRepository.upsert(match { it.id == 42L }) }
        } finally {
            // 失败防死锁（幂等）
            job.cancel()
            release.trySend(1)
        }
    }

    // ------------------------------------------------------------------ A1-2 列所有权 e2e

    /**
     * A1-2（红）：成功路径按列提交同步元数据——用户列（star/audioGainDb）与提取器列
     * （duration）绝不被覆盖。
     * 红证据（旧代码）：getById 陈旧快照（star=0）→ copy → 整行 upsert ⇒ star 被打回 0。
     */
    @Test
    fun `success commits sync columns only and never clobbers user columns`() = runTest {
        stubBasics()
        stubClient(listOf(1000))
        rows[42L] = seedExistingRow() // star=3, audioGainDb=2.5f, duration=77

        val result = sut.executeSyncLocked(1L, listOf(dlAction()), emptyList())

        assertTrue(result)
        val after = rows[42L]!!
        assertEquals(1000L, after.fileSize)
        assertEquals(5000L, after.remoteModifiedTime)
        assertEquals(3, after.star)
        assertEquals(2.5f, after.audioGainDb)
        assertEquals(77L, after.duration)
        coVerify(exactly = 0) { videoRepository.upsert(any()) }
        coVerify(exactly = 1) { videoRepository.updateSyncMetadata(42L, 1000L, 5000L, any()) }
    }

    /**
     * A1-2（红，占位行变体）：新文件占位 upsert 只发生在下载前（fileSize=0）；
     * 最终元数据仍走列提交，占位行上预置的用户列值不被覆盖。
     */
    @Test
    fun `new file placeholder keeps user columns and gets column commit`() = runTest {
        stubBasics()
        stubClient(listOf(1000))
        // 无 existing 行 ⇒ 走占位 upsert（fake 顺序分配 4242）
        coEvery { videoRepository.upsert(any()) } coAnswers {
            val e = firstArg<VideoItemEntity>()
            if (e.id == 0L) {
                // 模拟下载间隙用户已打星（下一行读取前）——占位本身不带 star
                val newId = idSeq.incrementAndGet()
                rows[newId] = e.copy(id = newId, star = 5)
                newId
            } else {
                rows[e.id] = e
                e.id
            }
        }

        val result = sut.executeSyncLocked(1L, listOf(dlAction()), emptyList())

        assertTrue(result)
        val after = rows[4242L]!!
        assertEquals(1000L, after.fileSize)
        assertEquals(5, after.star)
        coVerify(exactly = 1) { videoRepository.upsert(match { it.fileSize == 0L }) }
        coVerify(exactly = 0) { videoRepository.upsert(match { it.fileSize == 1000L }) }
        coVerify(exactly = 1) { videoRepository.updateSyncMetadata(4242L, 1000L, 5000L, any()) }
    }

    // ------------------------------------------------------------------ A1-02 主键传递（视频）

    /**
     * A1-02（红）：新文件首次分配的占位主键（4242）必须传给视频提取器。
     * 红证据（旧代码）：placeholderEntity 是 upsert 前快照（id=0）⇒ 提取器收到
     * id=0（match 断言失败）；且列提交 0 行——元数据静默丢失。
     */
    @Test
    fun `new file first allocation passes assigned id to video extractor`() = runTest {
        stubBasics()
        stubClient(listOf(1000))
        coEvery { videoMetadataExtractor.extractAndSave(any()) } answers {
            firstArg<VideoItemEntity>()
        }

        val result = sut.executeSyncLocked(1L, listOf(dlAction()), emptyList())

        assertTrue(result)
        coVerify(exactly = 1) {
            videoMetadataExtractor.extractAndSave(
                match { it.id == 4242L && it.fileName == "f.mp4" }
            )
        }
        coVerify(exactly = 0) { imageMetadataExtractor.extractAndSave(any()) }
        coVerify(exactly = 1) { videoRepository.updateSyncMetadata(4242L, 1000L, 5000L, any()) }
    }

    /**
     * A1-02（红）：close 失败重试后占位行被清理、重分配新主键（4243）——视频提取器
     * 必须拿到重分配后的 id，绝不再引用 id=0 快照。
     */
    @Test
    fun `retry re-allocation passes fresh assigned id to video extractor`() = runTest {
        stubBasics()
        stubSinkCloseFailsFirstN(1)
        val client = stubClient(listOf(1000, 1000))
        coEvery { videoMetadataExtractor.extractAndSave(any()) } answers {
            firstArg<VideoItemEntity>()
        }

        val result = sut.executeSyncLocked(1L, listOf(dlAction()), emptyList())

        assertTrue(result)
        coVerify(exactly = 2) { client.downloadTo(any(), any(), any()) }
        coVerify(exactly = 1) { store.finalize(4243L) }
        coVerify(exactly = 1) {
            videoMetadataExtractor.extractAndSave(match { it.id == 4243L })
        }
        coVerify(exactly = 0) { imageMetadataExtractor.extractAndSave(any()) }
        coVerify(exactly = 1) { videoRepository.updateSyncMetadata(4243L, 1000L, 5000L, any()) }
        assertEquals(1000L, rows[4243L]?.fileSize)
    }

    // ------------------------------------------------------------------ A1-02 主键传递（图片，A3 补）

    /**
     * A3（rev-gpt #4）：图片扩展名走 image 分支——首次分配主键（4242）必须传给
     * **图片**提取器，视频提取器零调用。ImageMetadataExtractor 单测不能替代
     * pipeline 的分支路由，故在此以管线级测试锁定。
     */
    @Test
    fun `image action first allocation routes to image extractor with assigned id`() = runTest {
        stubBasics()
        stubClient(listOf(1000))
        coEvery { imageMetadataExtractor.extractAndSave(any()) } answers {
            firstArg<VideoItemEntity>()
        }

        val result = sut.executeSyncLocked(1L, listOf(imgAction()), emptyList())

        assertTrue(result)
        coVerify(exactly = 1) {
            imageMetadataExtractor.extractAndSave(
                match { it.id == 4242L && it.fileName == "f.jpg" }
            )
        }
        coVerify(exactly = 0) { videoMetadataExtractor.extractAndSave(any()) }
        coVerify(exactly = 1) { videoRepository.updateSyncMetadata(4242L, 1000L, 5000L, any()) }
    }

    /** A3（rev-gpt #4）：图片分支重试重分配——重分配主键（4243）传给图片提取器。 */
    @Test
    fun `image action retry re-allocation routes to image extractor with fresh id`() = runTest {
        stubBasics()
        stubSinkCloseFailsFirstN(1)
        val client = stubClient(listOf(1000, 1000))
        coEvery { imageMetadataExtractor.extractAndSave(any()) } answers {
            firstArg<VideoItemEntity>()
        }

        val result = sut.executeSyncLocked(1L, listOf(imgAction()), emptyList())

        assertTrue(result)
        coVerify(exactly = 2) { client.downloadTo(any(), any(), any()) }
        coVerify(exactly = 1) { store.finalize(4243L) }
        coVerify(exactly = 1) {
            imageMetadataExtractor.extractAndSave(match { it.id == 4243L })
        }
        coVerify(exactly = 0) { videoMetadataExtractor.extractAndSave(any()) }
        coVerify(exactly = 1) { videoRepository.updateSyncMetadata(4243L, 1000L, 5000L, any()) }
        assertEquals(1000L, rows[4243L]?.fileSize)
    }

    // ------------------------------------------------------------------ A1-2 孤儿回滚

    /**
     * A1-2（红）：列提交 0 行（行在 finalize 后被并发删除）⇒ 孤儿文件明确失败 +
     * COMMITTED 单点回滚（删 final + 删行），绝不 SUCCESS、绝不复活。
     */
    @Test
    fun `zero-row sync metadata commit is explicit failure with committed cleanup`() = runTest {
        stubBasics()
        stubClient(listOf(1000))
        rows[42L] = seedExistingRow()
        coEvery { videoRepository.updateSyncMetadata(any(), any(), any(), any()) } coAnswers {
            rows.remove(firstArg<Long>()) // 模拟行已被并发删除（回收站）
            0
        }

        val result = sut.executeSyncLocked(1L, listOf(dlAction()), emptyList())

        assertFalse(result)
        assertEquals(1, publisher.value.failedFiles)
        assertEquals(0, publisher.value.completedFiles)
        coVerify(exactly = 1) { store.delete(42L) }
        coVerify(exactly = 1) { videoRepository.deleteById(42L) }
        coVerify(exactly = 0) { store.cancel(any()) }
        coVerify(exactly = 0) { videoRepository.upsert(match { it.id == 42L }) }
        coVerify(exactly = 1) {
            syncLogRepository.log("DOWNLOAD", any(), "FAILED", any(), any(), any())
        }
    }
}
