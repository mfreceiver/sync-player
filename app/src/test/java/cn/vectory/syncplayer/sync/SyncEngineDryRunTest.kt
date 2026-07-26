package cn.vectory.syncplayer.sync

import cn.vectory.syncplayer.data.local.dao.PendingSyncActionDao
import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import cn.vectory.syncplayer.data.model.RemoteFile
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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [SyncEngine.dryRun] — specifically the E8 guard: a partial or failed
 * remote scan must NEVER produce DELETE_LOCAL actions.
 *
 * Key regression: if the remote scan fails while the local DB contains indexed videos,
 * the resulting `toDelete` collection MUST be empty (size == 0). A non-empty toDelete
 * would cause real, irreversible deletion of local ciphertext + DB rows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncEngineDryRunTest {

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

    private val relationId = 1L
    private val relation = SyncRelation(
        id = relationId,
        name = "test-relation",
        syncSourceId = 10L,
        remotePath = "/remote",
        localBasePath = "videos"
    )
    private val source = SyncSource(
        id = 10L,
        name = "test-source",
        protocol = SyncProtocol.SFTP,
        host = "host",
        port = 22,
        username = "user",
        password = "pass"
    )

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

        coEvery { syncRelationRepository.getById(relationId) } returns relation
        coEvery { syncSourceRepository.getById(10L) } returns source
        every { remoteFileClientFactory.create(SyncProtocol.SFTP) } returns
            mockk<RemoteFileClient>(relaxed = true)

        // Mirror the real StoragePaths.toLocalRelativePath so the engine's path
        // arithmetic is exercised faithfully without hand-wiring every path.
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

    // Helper: a fresh relaxed client per test (each test stubs listFiles differently).
    private fun stubClient(listFilesResult: List<RemoteFile>): RemoteFileClient {
        val client = mockk<RemoteFileClient>(relaxed = true)
        every { remoteFileClientFactory.create(SyncProtocol.SFTP) } returns client
        coEvery { client.listFiles(relation.remotePath) } returns listFilesResult
        return client
    }

    private fun localVideo(
        id: Long,
        relativePath: String,
        fileName: String,
        fileSize: Long,
        remoteModifiedTime: Long? = null
    ) = VideoItemEntity(
        id = id,
        syncRelationId = relationId,
        relativePath = relativePath,
        fileName = fileName,
        fileSize = fileSize,
        volumeId = "vol",
        iv = ByteArray(0),
        remoteModifiedTime = remoteModifiedTime
    )

    // ===========================================================================
    // E8 GUARD — scan failure must NEVER produce DELETE_LOCAL
    // ===========================================================================

    @Test
    fun `RemoteScanIncompleteException yields empty actions and FAILED phase`() = runTest {
        val client = mockk<RemoteFileClient>(relaxed = true)
        every { remoteFileClientFactory.create(SyncProtocol.SFTP) } returns client
        coEvery { client.listFiles(relation.remotePath) } throws
            RemoteScanIncompleteException(maxFiles = 10000)

        // Local DB has indexed videos — these MUST NOT appear as DELETE_LOCAL.
        coEvery { videoRepository.getBySyncRelationId(relationId) } returns listOf(
            localVideo(1, "videos/a.mp4", "a.mp4", 100),
            localVideo(2, "videos/b.mp4", "b.mp4", 200),
            localVideo(3, "videos/c.mp4", "c.mp4", 300)
        )

        val result = syncEngine.dryRun(relationId)

        assertEquals(0, result.toDownload.size)
        assertEquals(0, result.toDelete.size)
        // P1-1: 扫描失败必须把真实根因带回调用方（而非被 ViewModel 覆盖为固定「连接失败」）。
        assertTrue("scan failure must carry the error to the caller", result.error != null)
        assertEquals(SyncErrorCode.SCAN_INCOMPLETE, result.error?.code)
    }

    @Test
    fun `CRITICAL - three indexed local videos with failed scan produce zero DELETE_LOCAL`() = runTest {
        // The regression that matters most: 3 local videos, scan fails → toDelete MUST be empty.
        val client = mockk<RemoteFileClient>(relaxed = true)
        every { remoteFileClientFactory.create(SyncProtocol.SFTP) } returns client
        coEvery { client.listFiles(relation.remotePath) } throws
            RemoteScanIncompleteException()

        val threeLocalVideos = listOf(
            localVideo(101, "videos/a.mp4", "a.mp4", 1_000L, remoteModifiedTime = 5_000L),
            localVideo(102, "videos/b.mp4", "b.mp4", 2_000L, remoteModifiedTime = 6_000L),
            localVideo(103, "videos/c.mp4", "c.mp4", 3_000L, remoteModifiedTime = 7_000L)
        )
        coEvery { videoRepository.getBySyncRelationId(relationId) } returns threeLocalVideos

        val result = syncEngine.dryRun(relationId)

        assertEquals("scan failure must yield zero downloads", 0, result.toDownload.size)
        assertEquals("scan failure must NEVER produce DELETE_LOCAL — this is the E8 guard", 0, result.toDelete.size)
        // Progress must reflect failure.
        assertEquals(SyncPhase.FAILED, syncEngine.progress.value.phase)
        // No DELETE_LOCAL log entries should be written.
        coVerify(exactly = 0) {
            syncLogRepository.log(action = "DELETE_LOCAL", any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `generic exception during scan also yields empty toDelete`() = runTest {
        // Any exception (not just RemoteScanIncompleteException) must trigger the guard.
        val client = mockk<RemoteFileClient>(relaxed = true)
        every { remoteFileClientFactory.create(SyncProtocol.SFTP) } returns client
        coEvery { client.listFiles(relation.remotePath) } throws
            java.io.IOException("connection reset")

        coEvery { videoRepository.getBySyncRelationId(relationId) } returns listOf(
            localVideo(1, "videos/a.mp4", "a.mp4", 100)
        )

        val result = syncEngine.dryRun(relationId)

        assertEquals(0, result.toDownload.size)
        assertEquals(0, result.toDelete.size)
        assertEquals(SyncPhase.FAILED, syncEngine.progress.value.phase)
        // P1-1: 非 SyncException 的裸 IOException 经 wrapSyncException 归一化为 UNKNOWN，
        // 仍随 DryRunResult 带回（ViewModel 不再固定显示「连接失败」）。
        assertTrue("scan failure must carry the error to the caller", result.error != null)
        assertEquals(SyncErrorCode.UNKNOWN, result.error?.code)
    }

    // ===========================================================================
    // LEGITIMATE EMPTY REMOTE — assert actual coded behavior
    // ===========================================================================

    @Test
    fun `legit empty remote with empty local yields no actions`() = runTest {
        stubClient(emptyList())
        coEvery { videoRepository.getBySyncRelationId(relationId) } returns emptyList()

        val result = syncEngine.dryRun(relationId)

        assertEquals(0, result.toDownload.size)
        assertEquals(0, result.toDelete.size)
        assertEquals(SyncPhase.CONFIRMING, syncEngine.progress.value.phase)
    }

    @Test
    fun `legit empty remote with three local videos produces three DELETE_LOCAL`() = runTest {
        // When the remote is genuinely empty (scan succeeded, no exception) but local
        // has indexed videos, the engine DOES generate DELETE_LOCAL for each local file.
        // This is correct mirror-sync semantics: the files were removed on the remote.
        // (The double-empty guard in the task spec refers to the E8 guard above —
        //  a scan *failure* suppresses deletes. A scan *success* with an empty remote
        //  is legitimate and produces deletes as coded.)
        stubClient(emptyList())
        coEvery { videoRepository.getBySyncRelationId(relationId) } returns listOf(
            localVideo(1, "videos/a.mp4", "a.mp4", 100),
            localVideo(2, "videos/b.mp4", "b.mp4", 200),
            localVideo(3, "videos/c.mp4", "c.mp4", 300)
        )

        val result = syncEngine.dryRun(relationId)

        assertEquals(0, result.toDownload.size)
        assertEquals(3, result.toDelete.size)
        assertTrue("all deletes must be DELETE_LOCAL", result.toDelete.all { it.type == SyncActionType.DELETE_LOCAL })
        assertEquals(SyncPhase.CONFIRMING, syncEngine.progress.value.phase)
    }

    // ===========================================================================
    // HAPPY PATHS
    // ===========================================================================

    @Test
    fun `happy path - remote A,B and local A,C yields DOWNLOAD B and DELETE_LOCAL C`() = runTest {
        val remoteA = RemoteFile("a.mp4", "a.mp4", false, size = 100L, lastModified = 5_000L)
        val remoteB = RemoteFile("b.mp4", "b.mp4", false, size = 200L, lastModified = 6_000L)
        stubClient(listOf(remoteA, remoteB))

        // localA: matches remoteA exactly (size + mtime truncated to second) → SKIP.
        val localA = localVideo(1, "videos/a.mp4", "a.mp4", 100L, remoteModifiedTime = 5_000L)
        // localC: not on remote → DELETE_LOCAL.
        val localC = localVideo(3, "videos/c.mp4", "c.mp4", 300L)
        coEvery { videoRepository.getBySyncRelationId(relationId) } returns listOf(localA, localC)

        val result = syncEngine.dryRun(relationId)

        assertEquals(1, result.toDownload.size)
        assertEquals(SyncActionType.DOWNLOAD, result.toDownload[0].type)
        assertEquals("b.mp4", result.toDownload[0].relativePath)
        assertEquals(200L, result.toDownload[0].fileSize)

        assertEquals(1, result.toDelete.size)
        assertEquals(SyncActionType.DELETE_LOCAL, result.toDelete[0].type)
        assertEquals("videos/c.mp4", result.toDelete[0].relativePath)
    }

    @Test
    fun `remote identical to local yields no actions`() = runTest {
        val remoteA = RemoteFile("a.mp4", "a.mp4", false, size = 100L, lastModified = 5_000L)
        stubClient(listOf(remoteA))

        val localA = localVideo(1, "videos/a.mp4", "a.mp4", 100L, remoteModifiedTime = 5_000L)
        coEvery { videoRepository.getBySyncRelationId(relationId) } returns listOf(localA)

        val result = syncEngine.dryRun(relationId)

        assertEquals(0, result.toDownload.size)
        assertEquals(0, result.toDelete.size)
    }

    @Test
    fun `remote file with changed size triggers DOWNLOAD`() = runTest {
        // Same path but remote size differs from local → DOWNLOAD (re-download).
        val remoteA = RemoteFile("a.mp4", "a.mp4", false, size = 999L, lastModified = 5_000L)
        stubClient(listOf(remoteA))

        val localA = localVideo(1, "videos/a.mp4", "a.mp4", 100L, remoteModifiedTime = 5_000L)
        coEvery { videoRepository.getBySyncRelationId(relationId) } returns listOf(localA)

        val result = syncEngine.dryRun(relationId)

        assertEquals(1, result.toDownload.size)
        assertEquals(SyncActionType.DOWNLOAD, result.toDownload[0].type)
        assertEquals(0, result.toDelete.size)
    }

    @Test
    fun `directory entries on remote are skipped`() = runTest {
        // Remote directory entries must not produce actions (only files are synced).
        val dir = RemoteFile("subdir", "subdir", isDirectory = true, size = 0L, lastModified = 0L)
        stubClient(listOf(dir))
        coEvery { videoRepository.getBySyncRelationId(relationId) } returns emptyList()

        val result = syncEngine.dryRun(relationId)

        assertEquals(0, result.toDownload.size)
        assertEquals(0, result.toDelete.size)
    }

    // ===========================================================================
    // EDGE CASES
    // ===========================================================================

    @Test
    fun `dryRun returns empty when relation not found`() = runTest {
        coEvery { syncRelationRepository.getById(999L) } returns null

        val result = syncEngine.dryRun(999L)

        assertEquals(0, result.toDownload.size)
        assertEquals(0, result.toDelete.size)
    }

    @Test
    fun `dryRun returns empty when source not found`() = runTest {
        coEvery { syncSourceRepository.getById(10L) } returns null

        val result = syncEngine.dryRun(relationId)

        assertEquals(0, result.toDownload.size)
        assertEquals(0, result.toDelete.size)
    }
}
