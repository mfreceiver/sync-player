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
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
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
    private val sut = SyncDeleteExecutor(
        syncRelationRepository, syncSourceRepository, videoRepository, playbackRepository,
        syncLogRepository, clientFactory, storagePaths, storeLazy, context, SyncProgressPublisher()
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
}
