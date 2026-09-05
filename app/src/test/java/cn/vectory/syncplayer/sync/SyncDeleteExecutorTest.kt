package cn.vectory.syncplayer.sync

import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import cn.vectory.syncplayer.data.model.SyncAction
import cn.vectory.syncplayer.data.model.SyncActionType
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
import cn.vectory.syncplayer.storage.volume.EncryptedMediaStore
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 5 迁移测试：[SyncDeleteExecutor]（DELETE_BOTH 路径）从 [SyncEngine] 迁出后的行为等价性。
 *
 * 覆盖 plan 12.2.0 remediation T5-C1（断言点为返回值 + 零删除副作用，不写死 log 参数表）：
 *  - 卷不匹配 → false + 密文零删除 + 远端零触碰（红线 2：volume ownership fail-closed）；
 *  - 远端删除异常 → false；
 *  - 本地 + 远端成功 → true。
 */
class SyncDeleteExecutorTest {

    private val videoRepository = mockk<VideoRepository>(relaxed = true)
    private val playbackRepository = mockk<PlaybackRepository>(relaxed = true)
    private val syncLogRepository = mockk<SyncLogRepository>(relaxed = true)
    private val syncRelationRepository = mockk<SyncRelationRepository>(relaxed = true)
    private val syncSourceRepository = mockk<SyncSourceRepository>()
    private val clientFactory = mockk<RemoteFileClientFactory>()
    private val client = mockk<RemoteFileClient>(relaxed = true)
    private val storagePaths = mockk<StoragePaths>()
    private val store = mockk<EncryptedMediaStore>(relaxed = true)
    private val storeLazy = mockk<Lazy<EncryptedMediaStore>> { every { get() } returns store }
    private val context = mockk<android.content.Context>(relaxed = true)
    private val publisher = SyncProgressPublisher()
    private val sut = SyncDeleteExecutor(
        syncRelationRepository, syncSourceRepository, videoRepository, playbackRepository,
        syncLogRepository, clientFactory, storagePaths, storeLazy, context, publisher
    )

    private val relation = SyncRelation(
        id = 1L, name = "r", syncSourceId = 10L, remotePath = "/remote", localBasePath = "videos"
    )
    private val source = SyncSource(
        id = 10L, name = "s", protocol = SyncProtocol.SMB, host = "h", port = 445,
        username = "u", password = "p"
    )
    private val action = SyncAction(SyncActionType.DELETE_BOTH, "a.mp4", 10L, 0L)

    private fun stubBasics() {
        coEvery { syncRelationRepository.getById(1L) } returns relation
        coEvery { syncSourceRepository.getById(10L) } returns source
        every { clientFactory.create(SyncProtocol.SMB) } returns client
        every { store.volumeId } returns "vol-active"
        every { storagePaths.toPathUnderBase(any(), any()) } returns "videos/a.mp4"
    }

    @Test
    fun `volume mismatch counts as FAILED and deletes nothing`() = runTest {
        stubBasics()
        coEvery { videoRepository.getByRelativePathAndRelationId("a.mp4", 1L) } returns mockk<VideoItemEntity> {
            every { id } returns 7L
            every { volumeId } returns "vol-other"
        }

        assertFalse(sut.executeDeleteLocked(1L, listOf(action)))

        coVerify(exactly = 0) { client.deleteFile(any()) } // 不触远端
        coVerify(exactly = 0) { store.delete(any()) }      // 不删本地密文（红线 2）
        coVerify(exactly = 0) { store.deleteThumb(any()) } // 不删缩略图
    }

    @Test
    fun `remote delete failure counts as FAILED`() = runTest {
        stubBasics()
        coEvery { videoRepository.getByRelativePathAndRelationId("a.mp4", 1L) } returns null
        coEvery { client.deleteFile(any()) } throws SyncException(SyncErrorCode.REMOTE_DELETE_FAILED, SyncOperation.DELETE)

        assertFalse(sut.executeDeleteLocked(1L, listOf(action)))
    }

    @Test
    fun `local and remote success returns true`() = runTest {
        stubBasics()
        coEvery { videoRepository.getByRelativePathAndRelationId("a.mp4", 1L) } returns null
        coEvery { client.deleteFile(any()) } returns true

        assertTrue(sut.executeDeleteLocked(1L, listOf(action)))
    }

    // -------------------------------------------------------------------------
    // BUG-008 — 终态计数保留（计划 §BUG-008.3 #4，注入点 :190 catch-all）
    // -------------------------------------------------------------------------

    @Test
    fun `delete flow catch-all FAILED preserves counters`() = runTest {
        // 注入点：循环前的 connect 即抛异常 → 直接落入 executeDeleteLocked 尾部
        // catch-all 的 FAILED 终态写入。该写入构造 fresh SyncProgress 会把
        // DELETING 相位已发布的 totalFiles 清零，必须改为基于当前值 copy。
        stubBasics()
        coEvery { client.connect(any(), any()) } throws SyncException(
            SyncErrorCode.REMOTE_DELETE_FAILED, SyncOperation.DELETE
        )

        val result = sut.executeDeleteLocked(1L, listOf(action))

        assertFalse(result)
        val p = publisher.value
        assertEquals(SyncPhase.FAILED, p.phase)
        assertEquals("terminal FAILED progress must keep totalFiles from DELETING phase", 1, p.totalFiles)
    }

    // -------------------------------------------------------------------------
    // BUG-001 — DELETE_BOTH remote-first（计划 §BUG-001.3）
    // 远端未 ack 前不动本地：防「行已删 + 远端仍在」被下次 dryRun 复活为 DOWNLOAD。
    // -------------------------------------------------------------------------

    /** volumeId 匹配当前卷的既有行（volume 检查放行，走到 local/remote 删除）。 */
    private fun ownedRow(): VideoItemEntity = mockk {
        every { id } returns 7L
        every { volumeId } returns "vol-active"
    }

    @Test
    fun `remote delete failure preserves local row, ciphertext and playback record`() = runTest {
        stubBasics()
        coEvery { videoRepository.getByRelativePathAndRelationId("a.mp4", 1L) } returns ownedRow()
        coEvery { client.deleteFile(any()) } throws SyncException(SyncErrorCode.REMOTE_DELETE_FAILED, SyncOperation.DELETE)

        assertFalse(sut.executeDeleteLocked(1L, listOf(action)))

        // 远端失败 → 本地状态必须原样保留（行/密文/缩略图/播放记录）
        coVerify(exactly = 0) { store.delete(any()) }
        coVerify(exactly = 0) { store.deleteThumb(any()) }
        coVerify(exactly = 0) { videoRepository.deleteByRelativePathAndRelationId(any(), any()) }
        coVerify(exactly = 0) { playbackRepository.deleteByVideoId(any()) }
    }

    @Test
    fun `remote ack precedes local deletion`() = runTest {
        stubBasics()
        coEvery { videoRepository.getByRelativePathAndRelationId("a.mp4", 1L) } returns ownedRow()
        coEvery { client.deleteFile(any()) } returns true
        coEvery { store.delete(7L) } returns true
        coEvery { store.deleteThumb(7L) } returns true

        assertTrue(sut.executeDeleteLocked(1L, listOf(action)))

        // remote-first 语义：远端 ack 必须先于任何本地删除副作用
        coVerifyOrder {
            client.deleteFile(any())
            store.delete(7L)
        }
    }

    @Test
    fun `remote success with local delete failure keeps row for mirror DELETE_LOCAL self-heal`() = runTest {
        stubBasics()
        coEvery { videoRepository.getByRelativePathAndRelationId("a.mp4", 1L) } returns ownedRow()
        coEvery { client.deleteFile(any()) } returns true
        // 本地密文删不掉（delete false + exists true → mediaGone=false）
        coEvery { store.delete(7L) } returns false
        coEvery { store.exists(7L) } returns true

        assertFalse(sut.executeDeleteLocked(1L, listOf(action)))

        // 行必须保留：下次镜像 dryRun 会推导 DELETE_LOCAL 自愈；远端已删过恰一次
        coVerify(exactly = 1) { client.deleteFile(any()) }
        coVerify(exactly = 0) { videoRepository.deleteByRelativePathAndRelationId(any(), any()) }
    }
}
