package cn.vectory.syncplayer.sync

import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import cn.vectory.syncplayer.data.model.RemoteFile
import cn.vectory.syncplayer.data.model.SyncProtocol
import cn.vectory.syncplayer.data.model.SyncRelation
import cn.vectory.syncplayer.data.model.SyncSource
import cn.vectory.syncplayer.data.repository.PlaybackRepository
import cn.vectory.syncplayer.data.repository.SyncLogRepository
import cn.vectory.syncplayer.data.repository.SyncRelationRepository
import cn.vectory.syncplayer.data.repository.SyncSourceRepository
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.storage.StoragePaths
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Task 6 迁移测试：[SyncRemoteScanner] 从 [SyncEngine] 迁出后的扫描行为等价性。
 *
 * 覆盖 plan 12.2.0 remediation T6-C1：
 *  - 扫描异常 → DryRunResult 两列表空 + error != null（E8：部分远端快照绝不推导 DELETE_LOCAL）；
 *  - 远端 A,B / 本地 A,C → DOWNLOAD B + DELETE_LOCAL C；
 *  - 亚秒 mtime 差异不触发重下（E6：秒级截断比较）。
 */
class SyncRemoteScannerTest {

    private val videoRepository = mockk<VideoRepository>(relaxed = true)
    private val playbackRepository = mockk<PlaybackRepository>(relaxed = true)
    private val syncRelationRepository = mockk<SyncRelationRepository>(relaxed = true)
    private val syncSourceRepository = mockk<SyncSourceRepository>()
    private val syncLogRepository = mockk<SyncLogRepository>(relaxed = true)
    private val clientFactory = mockk<RemoteFileClientFactory>()
    private val client = mockk<RemoteFileClient>(relaxed = true)
    private val storagePaths = mockk<StoragePaths>()
    private val context = mockk<android.content.Context>(relaxed = true)
    private val sut = SyncRemoteScanner(
        syncRelationRepository, syncSourceRepository, videoRepository, playbackRepository,
        syncLogRepository, clientFactory, storagePaths, context, SyncProgressPublisher()
    )

    private val relation = SyncRelation(
        id = 1L, name = "r", syncSourceId = 10L, remotePath = "/remote", localBasePath = "videos"
    )
    private val source = SyncSource(
        id = 10L, name = "s", protocol = SyncProtocol.SFTP, host = "h", port = 22,
        username = "u", password = "p"
    )

    private fun stubBasics() {
        coEvery { syncRelationRepository.getById(1L) } returns relation
        coEvery { syncSourceRepository.getById(10L) } returns source
        every { clientFactory.create(SyncProtocol.SFTP) } returns client
        every { storagePaths.toLocalRelativePath("videos", any()) } answers { secondArg() }
    }

    private fun remote(p: String, size: Long, mtime: Long) = RemoteFile(
        path = p, name = p.substringAfterLast('/'), isDirectory = false, size = size, lastModified = mtime
    )

    private fun localItem(p: String, size: Long, mtime: Long) = mockk<VideoItemEntity> {
        every { relativePath } returns p
        every { fileSize } returns size
        every { remoteModifiedTime } returns mtime
    }

    @Test
    fun `scan failure yields empty actions and non-null error (E8)`() = runTest {
        stubBasics()
        coEvery { client.connect(any(), any()) } throws IOException("scan blew up")
        coEvery { videoRepository.getBySyncRelationId(1L) } returns emptyList()

        val r = sut.dryRunLocked(1L)

        assertTrue(r.toDownload.isEmpty())
        assertTrue(r.toDelete.isEmpty())
        assertNotNull(r.error)
    }

    @Test
    fun `listFiles throwing SCAN_INCOMPLETE yields empty download and delete sets with scanError code`() = runTest {
        stubBasics()
        coEvery { client.listFiles(any()) } throws RemoteScanIncompleteException()
        coEvery { videoRepository.getBySyncRelationId(1L) } returns listOf(
            localItem("a.mp4", 1L, 0L), localItem("b.mp4", 1L, 0L)
        )

        val r = sut.dryRunLocked(1L)

        assertEquals(SyncErrorCode.SCAN_INCOMPLETE, r.error?.code)
        assertTrue(r.toDownload.isEmpty())
        assertTrue(r.toDelete.isEmpty())
    }

    @Test
    fun `remote A,B and local A,C yields DOWNLOAD B and DELETE_LOCAL C`() = runTest {
        stubBasics()
        coEvery { client.listFiles("/remote") } returns listOf(remote("A.mp4", 1L, 0L), remote("B.mp4", 2L, 0L))
        coEvery { videoRepository.getBySyncRelationId(1L) } returns listOf(
            localItem("A.mp4", 1L, 0L), localItem("C.mp4", 3L, 0L)
        )

        val r = sut.dryRunLocked(1L)

        assertEquals(listOf("B.mp4"), r.toDownload.map { it.relativePath })
        assertEquals(listOf("C.mp4"), r.toDelete.map { it.relativePath })
    }

    @Test
    fun `sub-second mtime difference does not trigger re-download (E6)`() = runTest {
        stubBasics()
        coEvery { client.listFiles("/remote") } returns listOf(remote("A.mp4", 1L, 1_700_000_000_500L))
        coEvery { videoRepository.getBySyncRelationId(1L) } returns listOf(
            localItem("A.mp4", 1L, 1_700_000_000_000L)
        )

        assertTrue(sut.dryRunLocked(1L).toDownload.isEmpty())
    }
}
