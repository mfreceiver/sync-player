package cn.vectory.syncplayer.sync

import cn.vectory.syncplayer.data.local.dao.PendingSyncActionDao
import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import cn.vectory.syncplayer.data.model.SyncAction
import cn.vectory.syncplayer.data.model.SyncActionType
import cn.vectory.syncplayer.data.model.SyncOutcome
import cn.vectory.syncplayer.data.model.SyncPhase
import cn.vectory.syncplayer.data.model.SyncProtocol
import cn.vectory.syncplayer.data.model.SyncRelation
import cn.vectory.syncplayer.data.model.SyncSource
import cn.vectory.syncplayer.data.repository.PlaybackRepository
import cn.vectory.syncplayer.data.repository.SyncLogRepository
import cn.vectory.syncplayer.data.repository.SyncRelationRepository
import cn.vectory.syncplayer.data.repository.SyncSourceRepository
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.storage.StoragePaths
import cn.vectory.syncplayer.storage.MediaFileStore
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.OutputStream

/**
 * F02/T6: [SyncEngine.executeSync] 各终态写入点的结构化结果协议。
 *
 * 覆盖：
 *  - 成功 → SUCCESS 快照（单条写入，无错误码、无计数）；
 *  - 逐文件失败 → PARTIAL_FAILED + failedCount（不再写成功文案）；
 *  - 未捕获异常 → FAILED + 错误码（UNKNOWN 兜底）；
 *  - 空间不足中止 → FAILED + INSUFFICIENT_SPACE；
 *  - 取消 → CANCELLED 经 NonCancellable 取消收尾（有限操作数 best-effort，
 *    不承诺时间上界）记录，且最终传播的是原始 CancellationException
 *    （绝不吞取消、绝不把取消当成功；收尾写入自身的结果不得替换原始取消）。
 *
 * 断言枚举/调用次数，绝不断言用户文案。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncOutcomePersistenceTest {

    private val context = mockk<android.content.Context>(relaxed = true)
    private val videoRepository = mockk<VideoRepository>()
    private val playbackRepository = mockk<PlaybackRepository>(relaxed = true)
    private val syncSourceRepository = mockk<SyncSourceRepository>()
    private val syncRelationRepository = mockk<SyncRelationRepository>(relaxed = true)
    private val syncLogRepository = mockk<SyncLogRepository>(relaxed = true)
    private val pendingSyncActionDao = mockk<PendingSyncActionDao>()
    private val remoteFileClientFactory = mockk<RemoteFileClientFactory>()
    private val storagePaths = mockk<StoragePaths>()
    private val videoMetadataExtractor = mockk<VideoMetadataExtractor>()
    private val imageMetadataExtractor = mockk<ImageMetadataExtractor>()
    private val mediaFileStore = mockk<MediaFileStore>(relaxed = true)
    private val mediaFileStoreLazy = mockk<Lazy<MediaFileStore>>()

    private lateinit var syncEngine: SyncEngine
    private val testDispatcher = StandardTestDispatcher()

    private val relationId = 1L
    private val relation = SyncRelation(
        id = relationId,
        name = "rel",
        syncSourceId = 10L,
        remotePath = "/remote",
        localBasePath = "/local"
    )
    private val source = SyncSource(
        id = 10L,
        name = "src",
        protocol = SyncProtocol.SFTP,
        host = "h",
        port = 22,
        username = "u",
        password = "p"
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { mediaFileStoreLazy.get() } returns mediaFileStore
        syncEngine = SyncEngine(
            syncSourceRepository = syncSourceRepository,
            syncRelationRepository = syncRelationRepository,
            videoRepository = videoRepository,
            playbackRepository = playbackRepository,
            syncLogRepository = syncLogRepository,
            videoMetadataExtractor = videoMetadataExtractor,
            imageMetadataExtractor = imageMetadataExtractor,
            pendingSyncActionDao = pendingSyncActionDao,
            remoteFileClientFactory = remoteFileClientFactory,
            storagePaths = storagePaths,
            mediaFileStoreLazy = mediaFileStoreLazy,
            context = context
        )
        coEvery { syncRelationRepository.getById(relationId) } returns relation
        coEvery { syncSourceRepository.getById(10L) } returns source
        every { mediaFileStore.usableSpace() } returns Long.MAX_VALUE
        every { storagePaths.toLocalRelativePath(any(), any()) } answers {
            val base = firstArg<String>()
            val rel = secondArg<String>()
            if (base.isNotEmpty()) "$base/$rel" else rel
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // -------------------------------------------------------------------------
    // Helpers（与 SyncEngineProgressTest 同款 stub）
    // -------------------------------------------------------------------------

    private fun makeDownloadableClient(downloadSucceeds: Boolean): RemoteFileClient {
        val client = mockk<RemoteFileClient>(relaxed = true)
        every { remoteFileClientFactory.create(SyncProtocol.SFTP) } returns client
        coEvery { client.downloadTo(any(), any(), any()) } returns downloadSucceeds
        return client
    }

    private fun stubSuccessfulDownload(relativePath: String, assignedId: Long) {
        val localRelPath = storagePaths.toLocalRelativePath("/local", relativePath)
        coEvery {
            videoRepository.getByRelativePathAndRelationId(localRelPath, relationId)
        } returns null
        coEvery {
            videoRepository.upsert(match { it.relativePath == localRelPath })
        } returns assignedId
        val savedEntity = VideoItemEntity(
            id = assignedId, syncRelationId = relationId, relativePath = localRelPath,
            fileName = relativePath.substringAfterLast("/"),
            fileSize = 1000L
        )
        coEvery { videoRepository.getById(assignedId) } returns savedEntity
        coEvery { videoMetadataExtractor.extractAndSave(any()) } returns savedEntity
        val sink = mockk<OutputStream>(relaxed = true)
        coEvery { mediaFileStore.openWriteSink(assignedId, any()) } returns sink
        coEvery { mediaFileStore.finalize(assignedId) } returns true
        // A2 (rev-gpt migration): success path commits sync columns only — this scenario
        // hits a live row, so the column UPDATE reports 1 row. Scoped to this assignedId.
        coEvery { videoRepository.updateSyncMetadata(assignedId, any(), any(), any()) } returns 1
    }

    private fun stubFailedDownload(relativePath: String, assignedId: Long) {
        val localRelPath = storagePaths.toLocalRelativePath("/local", relativePath)
        coEvery {
            videoRepository.getByRelativePathAndRelationId(localRelPath, relationId)
        } returns null
        coEvery {
            videoRepository.upsert(match { it.relativePath == localRelPath })
        } returns assignedId
        coEvery { videoRepository.deleteById(assignedId) } returns Unit
        val sink = mockk<OutputStream>(relaxed = true)
        coEvery { mediaFileStore.openWriteSink(assignedId, any()) } returns sink
        coEvery { mediaFileStore.cancel(assignedId) } returns Unit
    }

    /** 捕获全部 updateLastSync 终态写入。 */
    private fun captureOutcomes(): MutableList<SyncOutcome> {
        val outcomes = mutableListOf<SyncOutcome>()
        coEvery { syncRelationRepository.updateLastSync(any(), any()) } coAnswers {
            outcomes.add(arg(1))
            Unit
        }
        return outcomes
    }

    // -------------------------------------------------------------------------
    // SUCCESS
    // -------------------------------------------------------------------------

    @Test
    fun `successful sync persists exactly one SUCCESS snapshot`() = runTest {
        makeDownloadableClient(downloadSucceeds = true)
        stubSuccessfulDownload("video.mp4", assignedId = 100L)
        val outcomes = captureOutcomes()

        val result = syncEngine.executeSync(relationId, listOf(
            SyncAction(type = SyncActionType.DOWNLOAD, relativePath = "video.mp4", fileSize = 1000L)
        ), emptyList())

        assertTrue(result)
        coVerify(exactly = 1) { syncRelationRepository.updateLastSync(any(), any()) }
        val outcome = outcomes.single()
        assertEquals(SyncOutcome.Status.SUCCESS, outcome.status)
        assertEquals("successful sync must not persist an error code", null, outcome.errorCode)
        assertEquals("successful sync must not persist a failure count", null, outcome.failedCount)
    }

    // -------------------------------------------------------------------------
    // PARTIAL_FAILED（旧实现此处写 sync_result_success 文案——回归锁）
    // -------------------------------------------------------------------------

    @Test
    fun `all downloads failed persists PARTIAL_FAILED with failedCount`() = runTest {
        makeDownloadableClient(downloadSucceeds = false)
        stubFailedDownload("video.mp4", assignedId = 200L)
        val outcomes = captureOutcomes()

        val result = syncEngine.executeSync(relationId, listOf(
            SyncAction(type = SyncActionType.DOWNLOAD, relativePath = "video.mp4", fileSize = 1000L)
        ), emptyList())

        assertFalse(result)
        val p = syncEngine.progress.value
        assertEquals(SyncPhase.PARTIAL_FAILED, p.phase)
        coVerify(exactly = 1) { syncRelationRepository.updateLastSync(any(), any()) }
        val outcome = outcomes.single()
        assertEquals(SyncOutcome.Status.PARTIAL_FAILED, outcome.status)
        assertEquals("failedCount must reflect the failed action count", 1, outcome.failedCount)
    }

    // -------------------------------------------------------------------------
    // FAILED（catch-all）+ 错误码
    // -------------------------------------------------------------------------

    @Test
    fun `unexpected exception after completion writes FAILED with UNKNOWN code`() = runTest {
        // 注入点：SUCCESS 终态写入时抛非 CE 异常 → 落入 catch-all 的 FAILED 终态写入。
        makeDownloadableClient(downloadSucceeds = true)
        stubSuccessfulDownload("video.mp4", assignedId = 100L)
        val outcomes = mutableListOf<SyncOutcome>()
        coEvery { syncRelationRepository.updateLastSync(any(), any()) } coAnswers {
            outcomes.add(arg(1))
            if (outcomes.size == 1) throw RuntimeException("boom")
            Unit
        }

        val result = syncEngine.executeSync(relationId, listOf(
            SyncAction(type = SyncActionType.DOWNLOAD, relativePath = "video.mp4", fileSize = 1000L)
        ), emptyList())

        assertFalse(result)
        assertEquals("one SUCCESS write + one FAILED catch-all write", 2, outcomes.size)
        assertEquals(SyncOutcome.Status.SUCCESS, outcomes[0].status)
        assertEquals(SyncOutcome.Status.FAILED, outcomes[1].status)
        assertEquals("non-sync exception maps to UNKNOWN code", "UNKNOWN", outcomes[1].errorCode)
    }

    @Test
    fun `sync exception in catch-all persists its error code`() = runTest {
        // 注入点同上，但首次写入抛 SyncException（携带业务码）→ catch-all 原样持久化该码。
        makeDownloadableClient(downloadSucceeds = true)
        stubSuccessfulDownload("video.mp4", assignedId = 100L)
        val outcomes = mutableListOf<SyncOutcome>()
        coEvery { syncRelationRepository.updateLastSync(any(), any()) } coAnswers {
            outcomes.add(arg(1))
            if (outcomes.size == 1) {
                throw SyncException(SyncErrorCode.DATABASE_ERROR, SyncOperation.DOWNLOAD)
            }
            Unit
        }

        syncEngine.executeSync(relationId, listOf(
            SyncAction(type = SyncActionType.DOWNLOAD, relativePath = "video.mp4", fileSize = 1000L)
        ), emptyList())

        assertEquals(SyncOutcome.Status.FAILED, outcomes[1].status)
        assertEquals("SYNC exception code must be persisted as-is", "DATABASE_ERROR", outcomes[1].errorCode)
    }

    // -------------------------------------------------------------------------
    // 空间不足中止
    // -------------------------------------------------------------------------

    @Test
    fun `insufficient space abort persists FAILED with INSUFFICIENT_SPACE`() = runTest {
        every { mediaFileStore.usableSpace() } returns 0L
        // 外部 SD v2：目标卷预判会按 action 查 existing 行（新文件 → null → 默认卷）。
        coEvery { videoRepository.getByRelativePathAndRelationId(any(), relationId) } returns null
        val outcomes = captureOutcomes()

        val result = syncEngine.executeSync(relationId, listOf(
            SyncAction(type = SyncActionType.DOWNLOAD, relativePath = "video.mp4", fileSize = 1000L)
        ), emptyList())

        assertFalse(result)
        val p = syncEngine.progress.value
        assertEquals(SyncPhase.FAILED, p.phase)
        coVerify(exactly = 1) { syncRelationRepository.updateLastSync(any(), any()) }
        val outcome = outcomes.single()
        assertEquals(SyncOutcome.Status.FAILED, outcome.status)
        assertEquals(SyncErrorCode.INSUFFICIENT_SPACE.name, outcome.errorCode)
        assertEquals("space abort happens before any action; count unknown must stay null", null, outcome.failedCount)
    }

    // -------------------------------------------------------------------------
    // 取消 → CANCELLED 有界收尾 + CE 传播
    // -------------------------------------------------------------------------

    @Test
    fun `cancellation mid-run records CANCELLED and propagates CancellationException`() = runTest {
        val client = mockk<RemoteFileClient>(relaxed = true)
        every { remoteFileClientFactory.create(SyncProtocol.SFTP) } returns client
        val downloadStarted = CompletableDeferred<Unit>()
        coEvery { client.downloadTo(any(), any(), any()) } coAnswers {
            downloadStarted.complete(Unit)
            delay(60_000L)
            true
        }
        stubSuccessfulDownload("slow.mp4", assignedId = 300L)
        val outcomes = captureOutcomes()

        var caught: Throwable? = null
        val parentJob = Job()
        val scope = CoroutineScope(testDispatcher + parentJob)
        scope.launch {
            try {
                syncEngine.executeSync(relationId, listOf(
                    SyncAction(type = SyncActionType.DOWNLOAD, relativePath = "slow.mp4", fileSize = 1000L)
                ), emptyList())
            } catch (t: Throwable) {
                caught = t
            }
        }
        downloadStarted.await()
        parentJob.cancelAndJoin()

        assertTrue(
            "cancellation must propagate as CancellationException",
            caught is CancellationException
        )
        coVerify(exactly = 1) { syncRelationRepository.updateLastSync(any(), any()) }
        assertEquals(SyncOutcome.Status.CANCELLED, outcomes.single().status)
        assertEquals("cancelled outcome must not carry success-like count", null, outcomes.single().failedCount)
        assertEquals(SyncPhase.CANCELLED, syncEngine.progress.value.phase)
    }

    // -------------------------------------------------------------------------
    // 取消收尾三态：CANCELLED 收尾写入（单条 UPDATE）自身的任何结果——成功 /
    // 普通异常 / 再抛新 CE——都不得替换/遮蔽原始 CancellationException（红线 3）。
    // -------------------------------------------------------------------------

    /** 取消收尾三态测试的运行结果（含原始 CE 实例，用于同一性断言）。 */
    private class CancelledRun(
        val originalCe: CancellationException,
        val caught: Throwable?,
        val outcomes: List<SyncOutcome>,
    )

    /** 下载中途抛原始 CE；CANCELLED 收尾写入（updateLastSync）行为由 [finalizationWrite] 注入。 */
    private suspend fun runCancelledSync(
        finalizationWrite: suspend () -> Unit
    ): CancelledRun {
        val client = mockk<RemoteFileClient>(relaxed = true)
        every { remoteFileClientFactory.create(SyncProtocol.SFTP) } returns client
        val originalCe = CancellationException("original-cancel")
        coEvery { client.downloadTo(any(), any(), any()) } throws originalCe
        stubSuccessfulDownload("slow.mp4", assignedId = 300L)
        val outcomes = mutableListOf<SyncOutcome>()
        coEvery { syncRelationRepository.updateLastSync(any(), any()) } coAnswers {
            outcomes.add(arg(1))
            finalizationWrite()
        }

        var caught: Throwable? = null
        try {
            syncEngine.executeSync(relationId, listOf(
                SyncAction(type = SyncActionType.DOWNLOAD, relativePath = "slow.mp4", fileSize = 1000L)
            ), emptyList())
        } catch (t: Throwable) {
            caught = t
        }
        return CancelledRun(originalCe, caught, outcomes)
    }

    @Test
    fun `cancelled finalization write success propagates the original CancellationException`() = runTest {
        val run = runCancelledSync { }

        assertPropagatesOriginalCancellation(run, "must propagate the ORIGINAL cancellation (directly or as cause)")
        assertEquals(SyncOutcome.Status.CANCELLED, run.outcomes.single().status)
        assertEquals(SyncPhase.CANCELLED, syncEngine.progress.value.phase)
    }

    @Test
    fun `cancelled finalization write ordinary failure still propagates the original CancellationException`() = runTest {
        val run = runCancelledSync { throw RuntimeException("finalization write boom") }

        assertPropagatesOriginalCancellation(run, "finalization write failure must not replace the original cancellation")
        assertEquals(SyncOutcome.Status.CANCELLED, run.outcomes.single().status)
    }

    @Test
    fun `cancelled finalization write new CancellationException cannot replace the original`() = runTest {
        val finCe = CancellationException("finalization write itself cancelled")
        val run = runCancelledSync { throw finCe }

        assertPropagatesOriginalCancellation(
            run,
            "new CE from the finalization write must NOT replace the original cancellation"
        )
        // 区分性断言（旧实现 throw ce 会命中失败）：传播的既不是收尾 CE 本身，
        // 也不以收尾 CE 为 cause——原始取消优先。
        assertTrue("finalization CE must not surface as the propagated exception", run.caught !== finCe)
        assertTrue("finalization CE must not surface as the propagated cause", run.caught?.cause !== finCe)
        assertEquals(SyncOutcome.Status.CANCELLED, run.outcomes.single().status)
    }

    /**
     * kotlinx.coroutines 在已被取消的 coroutineScope 边界（pipeline 内
     * workerScope.cancel + scope 重建）会把传播的 CE 重建为新实例、原 CE 挂在
     * [Throwable.cause] 上——因此 pipeline 侧断言「身份或 cause 身份」；
     * 收尾 catch 块抛出的异常本身即原始实例（executor 路径无此边界，可 assertSame）。
     */
    private fun assertPropagatesOriginalCancellation(run: CancelledRun, message: String) {
        assertTrue("cancellation must propagate as CancellationException", run.caught is CancellationException)
        val propagatedOriginal = run.caught === run.originalCe || run.caught?.cause === run.originalCe
        assertTrue(message, propagatedOriginal)
    }
}
