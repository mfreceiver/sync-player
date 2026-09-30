package cn.vectory.syncplayer.sync

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
import cn.vectory.syncplayer.storage.StoragePaths
import cn.vectory.syncplayer.storage.MediaFileStore
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * AND-010 (S1-1)：通道级客户端复用。
 *
 * 下载并发模型从「每 action 新建 client（create+connect+close）」改为
 * W = MAX_CONCURRENCY 个长驻 lane，各持一个 client 从 Channel 消费 action：
 *  - 全成功批次：client 创建次数恰好等于 lane 数（W），与 action 数无关；
 *  - lane 内某 action 失败后，该 lane 的 client 被丢弃，下一个 action 重新建连
 *    （避免在可能已损坏的传输上继续下载）。
 *
 * 断言 factory 调用次数与终态计数器——不断言任何用户文案。
 */
class SyncDownloadPipelineClientReuseTest {

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
    private val sut = SyncDownloadPipeline(
        syncRelationRepository, syncSourceRepository, videoRepository, playbackRepository,
        syncLogRepository, mockk(relaxed = true), mockk(relaxed = true),
        clientFactory, storagePaths, storeLazy, context, publisher, deleteExecutor
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
    }

    private val source = SyncSource(
        id = 10L, name = "s", protocol = SyncProtocol.SMB, host = "h", port = 445,
        username = "u", password = "p"
    )

    /** 所有 action 的下载成功路径（占位行 → finalize → 列提交）。 */
    private fun stubSuccessfulDownloadPath() {
        coEvery { videoRepository.getByRelativePathAndRelationId(any(), 1L) } returns null
        val idSeq = AtomicLong(100L)
        coEvery { videoRepository.upsert(any()) } answers { idSeq.incrementAndGet() }
        // A1: 最终元数据走列提交（占位行存在 ⇒ 命中 1 行）
        coEvery { videoRepository.updateSyncMetadata(any(), any(), any(), any()) } returns 1
        every { store.usableSpace() } returns Long.MAX_VALUE
        coEvery { store.openWriteSink(any(), any()) } returns mockk<OutputStream>(relaxed = true)
        coEvery { store.finalize(any()) } returns true
    }

    private fun downloadAction(i: Int) =
        SyncAction(SyncActionType.DOWNLOAD, "video$i.mp4", 1000L, 0L)

    @Test
    fun `10 download actions reuse at most MAX_CONCURRENCY clients`() = runTest {
        stubBasics()
        stubSuccessfulDownloadPath()
        val client = mockk<RemoteFileClient>(relaxed = true)
        every { clientFactory.create(SyncProtocol.SMB) } returns client

        // 前 3 个 downloadTo 阻塞在 latch 上，直到 3 个 lane 都真实入场 —— 保证 3 个 lane
        // 各自完成了一次 client 创建，而不是一个 lane 串行消化全部 action。
        val entered = AtomicInteger(0)
        val firstWave = CountDownLatch(3)
        coEvery { client.downloadTo(any(), any(), any()) } coAnswers {
            if (entered.incrementAndGet() <= 3) {
                firstWave.countDown()
                assertTrue("3 lanes must engage within 10s", firstWave.await(10, TimeUnit.SECONDS))
            }
            true
        }

        val actions = (0 until 10).map { downloadAction(it) }
        val result = sut.executeSyncLocked(1L, actions, emptyList())

        assertTrue(result)
        // AND-010 核心：client 数 == lane 数 == MAX_CONCURRENCY(3)，与 action 数(10)无关。
        verify(exactly = 3) { clientFactory.create(SyncProtocol.SMB) }
        val p = publisher.value
        assertEquals(10, p.completedFiles)
        assertEquals(0, p.failedFiles)
        assertEquals(10, p.totalFiles)
    }

    @Test
    fun `failed action drops lane client so next action gets a fresh one`() = runTest {
        stubBasics()
        stubSuccessfulDownloadPath()
        val client = mockk<RemoteFileClient>(relaxed = true)
        every { clientFactory.create(SyncProtocol.SMB) } returns client
        val calls = AtomicInteger(0)
        coEvery { client.downloadTo(any(), any(), any()) } coAnswers {
            // 第 1 次（坏文件，终态 PATH_NOT_FOUND 不重试）失败；之后全部成功。
            // AtomicInteger：两个 action 可能并发下载，非原子计数会双双判为「第 1 次」。
            if (calls.incrementAndGet() == 1) {
                throw SyncException(
                    SyncErrorCode.PATH_NOT_FOUND, SyncOperation.DOWNLOAD, path = "video0.mp4"
                )
            }
            true
        }

        val result = sut.executeSyncLocked(1L, listOf(downloadAction(0), downloadAction(1)), emptyList())

        // 1 失败 + 1 成功：终态计数精确（红线）。
        assertFalse(result)
        val p = publisher.value
        assertEquals(1, p.completedFiles)
        assertEquals(1, p.failedFiles)
        assertEquals(2, p.totalFiles)
        // 2 个 action 且仅第 1 个失败 → 恰好 2 次 client 创建
        //（首个 lane 的 client 被丢弃后重建，或两个 lane 各建一次；无论调度如何均为 2）。
        verify(exactly = 2) { clientFactory.create(SyncProtocol.SMB) }
    }

    // ------------------------------------------------------------------ BUG-010: initial connect retry

    @Test
    fun `initial connect transient failure is retried within whitelist and action succeeds`() = runTest {
        stubBasics()
        stubSuccessfulDownloadPath()
        val client = mockk<RemoteFileClient>(relaxed = true)
        every { clientFactory.create(SyncProtocol.SMB) } returns client
        val connectCalls = AtomicInteger(0)
        coEvery { client.connect(any(), any()) } coAnswers {
            // 前 2 次瞬态失败（白名单码），第 3 次成功。
            if (connectCalls.incrementAndGet() <= 2) {
                throw SyncException(SyncErrorCode.NETWORK_UNAVAILABLE, SyncOperation.CONNECT)
            }
        }
        coEvery { client.downloadTo(any(), any(), any()) } returns true

        val result = sut.executeSyncLocked(1L, listOf(downloadAction(0)), emptyList())

        assertTrue("transient connect failure must recover within the whitelist", result)
        val p = publisher.value
        assertEquals(1, p.completedFiles)
        assertEquals(0, p.failedFiles)
        coVerify(exactly = 3) { client.connect(any(), any()) }
        // 每次重试前按 E5 语义先 disconnect 清理半建连状态（2 次失败 → 2 次）。
        coVerify(exactly = 2) { client.disconnect() }
    }

    @Test
    fun `initial connect terminal failure fails fast without retry`() = runTest {
        stubBasics()
        stubSuccessfulDownloadPath()
        val client = mockk<RemoteFileClient>(relaxed = true)
        every { clientFactory.create(SyncProtocol.SMB) } returns client
        coEvery { client.connect(any(), any()) } throws
            SyncException(SyncErrorCode.AUTHENTICATION_FAILED, SyncOperation.CONNECT)

        val result = sut.executeSyncLocked(1L, listOf(downloadAction(0)), emptyList())

        assertFalse(result)
        val p = publisher.value
        assertEquals(1, p.failedFiles)
        // 终态码（AUTH）不重试：恰好 1 次建连尝试。
        coVerify(exactly = 1) { client.connect(any(), any()) }
    }

    @Test
    fun `initial connect retries give up after 3 attempts and count one failure`() = runTest {
        stubBasics()
        stubSuccessfulDownloadPath()
        val client = mockk<RemoteFileClient>(relaxed = true)
        every { clientFactory.create(SyncProtocol.SMB) } returns client
        coEvery { client.connect(any(), any()) } throws
            SyncException(SyncErrorCode.NETWORK_UNAVAILABLE, SyncOperation.CONNECT)

        val result = sut.executeSyncLocked(1L, listOf(downloadAction(0)), emptyList())

        assertFalse(result)
        val p = publisher.value
        // connect 重试在 helper 内部消化，失败计数不放大：仍只计 1 次。
        assertEquals(1, p.failedFiles)
        coVerify(exactly = 3) { client.connect(any(), any()) }
    }

    @Test
    fun `cancellation during initial connect propagates without retry`() = runTest {
        stubBasics()
        stubSuccessfulDownloadPath()
        val client = mockk<RemoteFileClient>(relaxed = true)
        every { clientFactory.create(SyncProtocol.SMB) } returns client
        coEvery { client.connect(any(), any()) } throws CancellationException("cancelled by test")

        val thrown = try {
            sut.executeSyncLocked(1L, listOf(downloadAction(0)), emptyList())
            null
        } catch (e: Throwable) {
            e
        }

        assertTrue(
            "expected CancellationException but was ${thrown?.javaClass}",
            thrown is CancellationException
        )
        // CE 不重试：恰好 1 次建连尝试。
        coVerify(exactly = 1) { client.connect(any(), any()) }
    }
}
