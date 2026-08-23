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
 * Task 7 迁移测试：[SyncDownloadPipeline]（DOWNLOAD/更新 + DELETE_LOCAL tail）从 [SyncEngine]
 * 迁出后的行为等价性。
 *
 * 覆盖 plan 12.2.0 remediation T7-C1：
 *  - toDelete 卷不匹配 → false + 密文零删除（红线 2：volume ownership fail-closed）；
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

    private fun delAction(p: String) = SyncAction(SyncActionType.DELETE_LOCAL, p, 1L, 0L)

    private fun stubBasics() {
        coEvery { syncRelationRepository.getById(1L) } returns SyncRelation(
            id = 1L, name = "r", syncSourceId = 10L, remotePath = "/remote", localBasePath = "videos"
        )
        coEvery { syncSourceRepository.getById(10L) } returns SyncSource(
            id = 10L, name = "s", protocol = SyncProtocol.SMB, host = "h", port = 445,
            username = "u", password = "p"
        )
        every { store.volumeId } returns "vol-active"
    }

    @Test
    fun `toDelete with mismatched volumeId fails the run without deleting media`() = runTest {
        stubBasics()
        coEvery { videoRepository.getByRelativePathAndRelationId("a.mp4", 1L) } returns mockk<VideoItemEntity> {
            every { id } returns 7L
            every { volumeId } returns "vol-other"
        }

        assertFalse(sut.executeSyncLocked(1L, emptyList(), listOf(delAction("a.mp4"))))

        coVerify(exactly = 0) { store.delete(any()) }
    }

    @Test
    fun `toDelete with no DB row counts as success`() = runTest {
        stubBasics()
        coEvery { videoRepository.getByRelativePathAndRelationId("gone.mp4", 1L) } returns null

        assertTrue(sut.executeSyncLocked(1L, emptyList(), listOf(delAction("gone.mp4"))))
    }
}
