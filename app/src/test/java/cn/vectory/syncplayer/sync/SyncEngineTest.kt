package cn.vectory.syncplayer.sync

import cn.vectory.syncplayer.data.local.dao.PendingSyncActionDao
import cn.vectory.syncplayer.data.local.entity.PendingSyncActionEntity
import cn.vectory.syncplayer.data.model.RemoteFile
import cn.vectory.syncplayer.data.model.SyncAction
import cn.vectory.syncplayer.data.model.SyncActionType
import cn.vectory.syncplayer.data.model.SyncPhase
import cn.vectory.syncplayer.data.model.SyncProtocol
import cn.vectory.syncplayer.data.model.SyncRelation
import cn.vectory.syncplayer.data.model.SyncSource
import cn.vectory.syncplayer.data.repository.*
import cn.vectory.syncplayer.storage.StoragePaths
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class SyncEngineTest {

    private val context = mockk<android.content.Context>(relaxed = true)
    private val videoRepository = mockk<VideoRepository>()
    private val playbackRepository = mockk<PlaybackRepository>()
    private val syncSourceRepository = mockk<SyncSourceRepository>()
    private val syncRelationRepository = mockk<SyncRelationRepository>()
    private val syncLogRepository = mockk<SyncLogRepository>()
    private val pendingSyncActionDao = mockk<PendingSyncActionDao>()
    private val remoteFileClientFactory = mockk<RemoteFileClientFactory>()
    private val storagePaths = mockk<StoragePaths>()
    private val videoMetadataExtractor = mockk<VideoMetadataExtractor>()
    private val imageMetadataExtractor = mockk<ImageMetadataExtractor>()

    private lateinit var syncEngine: SyncEngine
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        syncEngine = SyncEngine(
            context = context,
            videoRepository = videoRepository,
            playbackRepository = playbackRepository,
            syncSourceRepository = syncSourceRepository,
            syncRelationRepository = syncRelationRepository,
            syncLogRepository = syncLogRepository,
            pendingSyncActionDao = pendingSyncActionDao,
            remoteFileClientFactory = remoteFileClientFactory,
            storagePaths = storagePaths,
            videoMetadataExtractor = videoMetadataExtractor,
            imageMetadataExtractor = imageMetadataExtractor
        )
    }

    @Test
    fun `dryRun should emit SCANNING phase`() {
        // This is just a boilerplate to show MockK usage
        // Actual test implementation would go here
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
        coVerify(exactly = 1) { pendingSyncActionDao.getPendingByRelationId(1L) }
        coVerify(exactly = 0) { pendingSyncActionDao.getAndDeleteByRelationId(any()) }
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
    fun `executeSync should return true when all downloads succeed`() = runTest {
        val relationId = 1L
        val source = SyncSource(
            id = 1L,
            name = "test-source",
            protocol = SyncProtocol.SFTP,
            host = "example.com",
            port = 22,
            username = "user",
            password = "pass"
        )
        val relation = SyncRelation(
            id = relationId,
            syncSourceId = 1L,
            name = "test-relation",
            localBasePath = "/sync/test",
            remotePath = "/remote/test"
        )
        val action = SyncAction(
            type = SyncActionType.DOWNLOAD,
            relativePath = "video.mp4",
            fileSize = 999L,
            remoteModifiedTime = 12345L
        )

        coEvery { syncRelationRepository.getById(relationId) } returns relation
        coEvery { syncSourceRepository.getById(1L) } returns source
        every { storagePaths.userdataRoot } returns mockk { every { usableSpace } returns 1024L * 1024 * 1024 * 10 }
        val realTempRoot = File(System.getProperty("java.io.tmpdir"), "sync-test-${System.nanoTime()}").apply { mkdirs() }
        every { storagePaths.safeResolveUserData(any()) } returns realTempRoot
        every { storagePaths.toLocalRelativePath(any(), any()) } answers { secondArg<String>() }
        every { storagePaths.thumbnailsDir } returns File("/tmp/thumbs")
        every { storagePaths.toPathUnderBase(any(), any()) } answers { secondArg<String>() }

        val mockClient = mockk<RemoteFileClient>(relaxed = true)
        coEvery { mockClient.downloadFile(any(), any(), any()) } answers {
            val localFile = secondArg<File>()
            localFile.parentFile?.mkdirs()
            localFile.writeBytes(ByteArray(999))
            true
        }
        every { remoteFileClientFactory.create(SyncProtocol.SFTP) } returns mockClient

        coEvery { videoRepository.upsert(any()) } returns 0L
        coEvery { videoRepository.getByRelativePathAndRelationId(any(), any()) } returns null
        coEvery { syncLogRepository.log(any(), any(), any(), any(), any(), any()) } returns Unit
        coEvery { syncLogRepository.clearOldLogs() } returns Unit
        coEvery { syncRelationRepository.updateLastSync(any(), any()) } returns Unit
        every { context.getString(any()) } returns ""

        val result = syncEngine.executeSync(relationId, listOf(action), emptyList(), "test", "/sync/test")

        assertTrue(result)
        val progress = syncEngine.progress.value
        assertTrue(progress.phase == SyncPhase.COMPLETED)
        assertEquals(0, progress.failedFiles)
    }

    @Test
    fun `executeSync should return false with PARTIAL_FAILED when downloads fail`() = runTest {
        val relationId = 1L
        val source = SyncSource(
            id = 1L,
            name = "test-source",
            protocol = SyncProtocol.SFTP,
            host = "example.com",
            port = 22,
            username = "user",
            password = "pass"
        )
        val relation = SyncRelation(
            id = relationId,
            syncSourceId = 1L,
            name = "test-relation",
            localBasePath = "/sync/test",
            remotePath = "/remote/test"
        )
        val action = SyncAction(
            type = SyncActionType.DOWNLOAD,
            relativePath = "video.mp4",
            fileSize = 999L,
            remoteModifiedTime = 12345L
        )

        coEvery { syncRelationRepository.getById(relationId) } returns relation
        coEvery { syncSourceRepository.getById(1L) } returns source
        every { storagePaths.userdataRoot } returns mockk { every { usableSpace } returns 1024L * 1024 * 1024 * 10 }
        val realTempRoot = File(System.getProperty("java.io.tmpdir"), "sync-test-${System.nanoTime()}").apply { mkdirs() }
        every { storagePaths.safeResolveUserData(any()) } returns realTempRoot
        every { storagePaths.toLocalRelativePath(any(), any()) } answers { secondArg<String>() }
        every { storagePaths.thumbnailsDir } returns File("/tmp/thumbs")
        every { storagePaths.toPathUnderBase(any(), any()) } answers { secondArg<String>() }

        val mockClient = mockk<RemoteFileClient>(relaxed = true)
        coEvery { mockClient.downloadFile(any(), any(), any()) } throws RuntimeException("Connection failed")
        every { remoteFileClientFactory.create(SyncProtocol.SFTP) } returns mockClient

        coEvery { syncLogRepository.log(any(), any(), any(), any(), any(), any()) } returns Unit
        coEvery { syncLogRepository.clearOldLogs() } returns Unit
        coEvery { syncRelationRepository.updateLastSync(any(), any()) } returns Unit
        every { context.getString(any()) } returns ""

        val result = syncEngine.executeSync(relationId, listOf(action), emptyList(), "test", "/sync/test")

        assertFalse(result)
        val progress = syncEngine.progress.value
        assertEquals(SyncPhase.PARTIAL_FAILED, progress.phase)
        assertEquals(1, progress.failedFiles)
    }
}
