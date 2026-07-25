package cn.vectory.syncplayer.sync

import cn.vectory.syncplayer.data.local.dao.PendingSyncActionDao
import cn.vectory.syncplayer.data.local.entity.PendingSyncActionEntity
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.io.OutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class SyncEngineTest {

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
    private val encryptedMediaStore = mockk<EncryptedMediaStore>(relaxed = true)
    private val encryptedMediaStoreLazy = mockk<Lazy<EncryptedMediaStore>>()

    private lateinit var syncEngine: SyncEngine
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { encryptedMediaStoreLazy.get() } returns encryptedMediaStore
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
            encryptedMediaStoreLazy = encryptedMediaStoreLazy,
            context = context
        )
    }

    @Test
    fun `consumePendingTask should mark rows RUNNING`() = runTest {
        val entities = listOf(
            PendingSyncActionEntity(
                relationId = 1L,
                relationName = "test-relation",
                localBasePath = "/test",
                actionGroup = 0,
                actionType = "DOWNLOAD",
                relativePath = "video.mp4",
                fileSize = 1000L,
                remoteModifiedTime = 12345L
            )
        )
        coEvery { pendingSyncActionDao.resetRunningToPending(1L) } returns Unit
        coEvery { pendingSyncActionDao.getPendingByRelationId(1L) } returns entities
        coEvery { pendingSyncActionDao.markRunning(1L) } returns Unit

        val task = syncEngine.consumePendingTask(1L)

        assertNotNull(task)
        assertEquals(1, task!!.toDownload.size)
        assertEquals(0, task.toDelete.size)
        assertEquals("video.mp4", task.toDownload[0].relativePath)
        coVerify(exactly = 1) { pendingSyncActionDao.markRunning(1L) }
    }

    @Test
    fun `consumePendingTask should return null when no pending actions`() = runTest {
        coEvery { pendingSyncActionDao.resetRunningToPending(1L) } returns Unit
        coEvery { pendingSyncActionDao.getPendingByRelationId(1L) } returns emptyList()

        val task = syncEngine.consumePendingTask(1L)

        assertEquals(null, task)
        coVerify(exactly = 0) { pendingSyncActionDao.markRunning(any()) }
    }

    @Test
    fun `executeSync streams download through encrypted sink and finalizes`() = runTest {
        val relationId = 1L
        val relation = mockk<SyncRelation>()
        every { relation.id } returns relationId
        every { relation.syncSourceId } returns 10L
        every { relation.remotePath } returns "/remote"
        every { relation.localBasePath } returns "/local"
        every { relation.name } returns "rel"
        val source = mockk<SyncSource>()
        every { source.protocol } returns SyncProtocol.SFTP
        every { source.id } returns 10L

        coEvery { syncRelationRepository.getById(relationId) } returns relation
        coEvery { syncSourceRepository.getById(10L) } returns source
        every { encryptedMediaStore.usableSpace() } returns Long.MAX_VALUE
        every { encryptedMediaStore.volumeId } returns "test-vol"

        val client = mockk<RemoteFileClient>(relaxed = true)
        every { remoteFileClientFactory.create(SyncProtocol.SFTP) } returns client
        coEvery { client.downloadTo(any(), any(), any()) } returns true
        every { storagePaths.toLocalRelativePath("/local", "video.mp4") } returns "video.mp4"

        // 新文件：existing == null → upsert 占位返回 id
        coEvery { videoRepository.getByRelativePathAndRelationId("video.mp4", relationId) } returns null
        coEvery { videoRepository.upsert(any()) } returns 100L
        val savedEntity = VideoItemEntity(
            id = 100L, syncRelationId = relationId, relativePath = "video.mp4",
            fileName = "video.mp4", fileSize = 1000L, volumeId = "test-vol", iv = ByteArray(8)
        )
        coEvery { videoRepository.getById(100L) } returns savedEntity
        coEvery { videoMetadataExtractor.extractAndSave(any()) } returns savedEntity

        val sink = mockk<OutputStream>(relaxed = true)
        coEvery { encryptedMediaStore.openEncryptSink(100L, any()) } returns sink
        coEvery { encryptedMediaStore.finalize(100L) } returns true

        val action = SyncAction(type = SyncActionType.DOWNLOAD, relativePath = "video.mp4", fileSize = 1000L)
        val result = syncEngine.executeSync(relationId, listOf(action), emptyList())

        assertEquals(true, result)
        coVerify(atLeast = 1) { encryptedMediaStore.openEncryptSink(100L, any()) }
        coVerify(atLeast = 1) { client.downloadTo(any(), any(), any()) }
        coVerify(atLeast = 1) { encryptedMediaStore.finalize(100L) }
        // 成功路径不应 cancel
        coVerify(exactly = 0) { encryptedMediaStore.cancel(100L) }
    }

    @Test
    fun `executeSync cancels and deletes placeholder when download fails`() = runTest {
        val relationId = 1L
        val relation = mockk<SyncRelation>()
        every { relation.id } returns relationId
        every { relation.syncSourceId } returns 10L
        every { relation.remotePath } returns "/remote"
        every { relation.localBasePath } returns "/local"
        every { relation.name } returns "rel"
        val source = mockk<SyncSource>()
        every { source.protocol } returns SyncProtocol.SFTP
        every { source.id } returns 10L

        coEvery { syncRelationRepository.getById(relationId) } returns relation
        coEvery { syncSourceRepository.getById(10L) } returns source
        every { encryptedMediaStore.usableSpace() } returns Long.MAX_VALUE
        every { encryptedMediaStore.volumeId } returns "test-vol"

        val client = mockk<RemoteFileClient>(relaxed = true)
        every { remoteFileClientFactory.create(SyncProtocol.SFTP) } returns client
        // downloadTo 始终失败
        coEvery { client.downloadTo(any(), any(), any()) } returns false
        every { storagePaths.toLocalRelativePath("/local", "video.mp4") } returns "video.mp4"

        coEvery { videoRepository.getByRelativePathAndRelationId("video.mp4", relationId) } returns null
        coEvery { videoRepository.upsert(any()) } returns 200L
        coEvery { videoRepository.deleteById(200L) } returns Unit

        val sink = mockk<OutputStream>(relaxed = true)
        coEvery { encryptedMediaStore.openEncryptSink(200L, any()) } returns sink
        coEvery { encryptedMediaStore.finalize(200L) } returns true
        coEvery { encryptedMediaStore.cancel(200L) } returns Unit

        val action = SyncAction(type = SyncActionType.DOWNLOAD, relativePath = "video.mp4", fileSize = 1000L)
        val result = syncEngine.executeSync(relationId, listOf(action), emptyList())

        // 全部失败 → return false
        assertEquals(false, result)
        // 失败应 cancel 并删除占位
        coVerify(atLeast = 1) { encryptedMediaStore.cancel(200L) }
        coVerify(atLeast = 1) { videoRepository.deleteById(200L) }
    }
}
