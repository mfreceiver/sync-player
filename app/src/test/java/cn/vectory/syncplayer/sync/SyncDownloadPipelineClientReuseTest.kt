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
import cn.vectory.syncplayer.storage.StoragePaths
import cn.vectory.syncplayer.storage.volume.EncryptedMediaStore
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
    private val store = mockk<EncryptedMediaStore>(relaxed = true)
    private val storeLazy = mockk<Lazy<EncryptedMediaStore>> { every { get() } returns store }
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
        every { store.volumeId } returns "vol-active"
        every { storagePaths.toLocalRelativePath(any(), any()) } answers {
            val base = firstArg<String>()
            val rel = secondArg<String>()
            if (base.isNotEmpty()) "$base/$rel" else rel
        }
    }

    /** 所有 action 的下载成功路径（占位行 → finalize → 元数据回写）。 */
    private fun stubSuccessfulDownloadPath() {
        coEvery { videoRepository.getByRelativePathAndRelationId(any(), 1L) } returns null
        val idSeq = AtomicLong(100L)
        coEvery { videoRepository.upsert(any()) } answers { idSeq.incrementAndGet() }
        coEvery { videoRepository.getById(any()) } answers {
            VideoItemEntity(
                id = firstArg(), syncRelationId = 1L, relativePath = "rel",
                fileName = "f.mp4", fileSize = 1L, volumeId = "vol-active", iv = ByteArray(12)
            )
        }
        every { store.usableSpace() } returns Long.MAX_VALUE
        coEvery { store.openEncryptSink(any(), any(), any()) } returns mockk<OutputStream>(relaxed = true)
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
}
