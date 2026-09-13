package cn.vectory.syncplayer.sync

import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import cn.vectory.syncplayer.data.model.RemoteFile
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
import cn.vectory.syncplayer.storage.MediaExistence
import cn.vectory.syncplayer.storage.MediaFileStore
import cn.vectory.syncplayer.storage.StoragePaths
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
 * BUG-001 回收站复活回归（计划 §BUG-001.3 #5/#6）。
 *
 * 端到端因果链：真实 [SyncDeleteExecutor] 执行 DELETE_BOTH → 真实 [SyncRemoteScanner]
 * .dryRunLocked 以「下一次同步」视角复扫同一关系。
 *
 * #5（红测试）依赖**有状态** fake videoRepository：旧 local-first 实现里行真的被删，
 * 下次扫描 local==null → 复活为 DOWNLOAD——静态 stub 无法复现该因果，必须可变。
 *
 * #6（回归锁）远端成功 + 本地删除失败 → 行保留 → 下次镜像 dryRun 推导 DELETE_LOCAL 自愈。
 */
class SyncTrashResurrectionRegressionTest {

    private val videoRepository = mockk<VideoRepository>()
    private val playbackRepository = mockk<PlaybackRepository>(relaxed = true)
    private val syncLogRepository = mockk<SyncLogRepository>(relaxed = true)
    private val syncRelationRepository = mockk<SyncRelationRepository>(relaxed = true)
    private val syncSourceRepository = mockk<SyncSourceRepository>()
    private val clientFactory = mockk<RemoteFileClientFactory>()
    private val client = mockk<RemoteFileClient>(relaxed = true)
    private val storagePaths = mockk<StoragePaths>()
    private val store = mockk<MediaFileStore>(relaxed = true)
    private val storeLazy = mockk<Lazy<MediaFileStore>> { every { get() } returns store }
    private val context = mockk<android.content.Context>(relaxed = true)
    private val publisher = SyncProgressPublisher()

    private val deleteExecutor = SyncDeleteExecutor(
        syncRelationRepository, syncSourceRepository, videoRepository, playbackRepository,
        syncLogRepository, clientFactory, storagePaths, storeLazy, context, publisher
    )
    private val remoteScanner = SyncRemoteScanner(
        syncRelationRepository, syncSourceRepository, videoRepository, playbackRepository,
        syncLogRepository, clientFactory, storagePaths, context, publisher
    )

    private val relation = SyncRelation(
        id = 1L, name = "r", syncSourceId = 10L, remotePath = "/remote", localBasePath = "videos"
    )
    private val source = SyncSource(
        id = 10L, name = "s", protocol = SyncProtocol.SMB, host = "h", port = 445,
        username = "u", password = "p"
    )

    /** 有状态 fake：key = relativePath。删除动作真实移除行，下次扫描可见。 */
    private val rows = mutableMapOf<String, VideoItemEntity>()

    private fun seedRow() {
        rows["a.mp4"] = VideoItemEntity(
            id = 7L,
            syncRelationId = 1L,
            relativePath = "a.mp4",
            fileName = "a.mp4",
            fileSize = 1000L,
            remoteModifiedTime = 5000L,
        )
    }

    private fun stubBasics() {
        coEvery { syncRelationRepository.getById(1L) } returns relation
        coEvery { syncSourceRepository.getById(10L) } returns source
        every { clientFactory.create(SyncProtocol.SMB) } returns client
        every { storagePaths.toPathUnderBase(any(), any()) } returns "videos/a.mp4"
        // 扫描侧本地映射：直接返回远端相对路径，使 localMap key == 行 key
        every { storagePaths.toLocalRelativePath(any(), any()) } answers { secondArg() }
        // 有状态 fake 接线
        coEvery { videoRepository.getByRelativePathAndRelationId(any(), 1L) } answers {
            rows[firstArg()]
        }
        coEvery { videoRepository.deleteByRelativePathAndRelationId(any(), 1L) } answers {
            rows.remove(firstArg())
            Unit
        }
        coEvery { videoRepository.getBySyncRelationId(1L) } answers { rows.values.toList() }
    }

    private val trashAction = SyncAction(SyncActionType.DELETE_BOTH, "a.mp4", 1000L, 5000L)

    @Test
    fun `DELETE_BOTH with remote failure is not resurrected by next dryRun`() = runTest {
        stubBasics()
        seedRow()
        // 远端删除失败（网络/权限等）
        coEvery { client.deleteFile(any()) } throws SyncException(SyncErrorCode.REMOTE_DELETE_FAILED, SyncOperation.DELETE)

        val deleteOk = deleteExecutor.executeDeleteLocked(1L, listOf(trashAction))
        assertFalse(deleteOk)

        // 「下一次同步」视角：远端快照仍含 a.mp4（删除确实没生效）
        coEvery { client.listFiles(any()) } returns listOf(
            RemoteFile(path = "a.mp4", name = "a.mp4", isDirectory = false, size = 1000L, lastModified = 5000L)
        )
        val scan = remoteScanner.dryRunLocked(1L)

        // 红线断言：本地行仍在 → 不得推导出 DOWNLOAD 复活
        assertTrue(
            "remote-failed DELETE_BOTH must not resurrect as DOWNLOAD (got ${scan.toDownload.map { it.relativePath }})",
            scan.toDownload.isEmpty()
        )
        coVerify(exactly = 0) { videoRepository.deleteByRelativePathAndRelationId(any(), any()) }
    }

    @Test
    fun `remote success with local delete failure self-heals as DELETE_LOCAL`() = runTest {
        stubBasics()
        seedRow()
        coEvery { client.deleteFile(any()) } returns true
        // 本地密文删不掉（delete false + checkExistence EXISTS → mediaGone=false）→ 行保留
        coEvery { store.delete(7L) } returns false
        coEvery { store.checkExistence(7L) } returns MediaExistence.EXISTS

        val deleteOk = deleteExecutor.executeDeleteLocked(1L, listOf(trashAction))
        assertFalse(deleteOk)

        // 「下一次同步」视角：远端快照已无 a.mp4 → 镜像同步推导 DELETE_LOCAL 自愈
        coEvery { client.listFiles(any()) } returns emptyList()
        val scan = remoteScanner.dryRunLocked(1L)

        val selfHeal = scan.toDelete.singleOrNull { it.relativePath == "a.mp4" }
        assertTrue(
            "kept row must self-heal as DELETE_LOCAL (got ${scan.toDelete.map { it.relativePath }})",
            selfHeal?.type == SyncActionType.DELETE_LOCAL
        )
    }
}
