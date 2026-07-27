package cn.vectory.syncplayer.sync

import cn.vectory.syncplayer.data.local.dao.PendingSyncActionDao
import cn.vectory.syncplayer.data.local.entity.PendingSyncActionEntity
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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.OutputStream

/**
 * Boundary regression tests for [SyncEngine] — covering the gaps NOT already locked by
 * [SyncEngineProgressTest] / [SyncEngineTest] / [SyncEngineDryRunTest]:
 *
 *  1. Cancellation propagation + NonCancellable pending-task cleanup in consumeAndExecute
 *     (the path SyncService actually uses; distinct from executeSync's CANCELLED phase).
 *  2. Additional terminal codes (ACCESS_DENIED, INSUFFICIENT_SPACE) that must NOT retry.
 *  3. Retry-whitelist recovery: a whitelisted code (TIMEOUT / CONNECTION_REFUSED) retries
 *     and can SUCCEED on a later attempt, or fail all 3 attempts.
 *  4. executeDelete DELETE_BOTH volume mismatch → counted as FAILED, no local/remote delete.
 *
 * No SyncEngine source is modified. Asserts on SyncPhase, MockK call counts, and enum codes —
 * never on user-facing message text.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncEngineBoundaryTest {

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
        id = relationId, name = "rel", syncSourceId = 10L,
        remotePath = "/remote", localBasePath = "/local"
    )
    private val source = SyncSource(
        id = 10L, name = "src", protocol = SyncProtocol.SFTP,
        host = "h", port = 22, username = "u", password = "p"
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
        every { encryptedMediaStore.usableSpace() } returns Long.MAX_VALUE
        every { encryptedMediaStore.volumeId } returns "test-vol"
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

    private fun downloadAction(relativePath: String, fileSize: Long = 1000L) =
        SyncAction(type = SyncActionType.DOWNLOAD, relativePath = relativePath, fileSize = fileSize)

    /** Stub a fresh hanging client whose downloadTo signals start then blocks until cancelled. */
    private fun hangingClient(started: CompletableDeferred<Unit>): RemoteFileClient {
        val client = mockk<RemoteFileClient>(relaxed = true)
        every { remoteFileClientFactory.create(SyncProtocol.SFTP) } returns client
        coEvery { client.downloadTo(any(), any(), any()) } coAnswers {
            started.complete(Unit)
            delay(60_000L)
            true
        }
        return client
    }

    private fun stubSuccessfulDownload(relativePath: String, assignedId: Long) {
        val localRelPath = storagePaths.toLocalRelativePath("/local", relativePath)
        coEvery { videoRepository.getByRelativePathAndRelationId(localRelPath, relationId) } returns null
        coEvery { videoRepository.upsert(match { it.relativePath == localRelPath }) } returns assignedId
        val saved = VideoItemEntity(
            id = assignedId, syncRelationId = relationId, relativePath = localRelPath,
            fileName = relativePath.substringAfterLast("/"), fileSize = 1000L,
            volumeId = "test-vol", iv = ByteArray(12)
        )
        coEvery { videoRepository.getById(assignedId) } returns saved
        coEvery { videoMetadataExtractor.extractAndSave(any()) } returns saved
        val sink = mockk<OutputStream>(relaxed = true)
        coEvery { encryptedMediaStore.openEncryptSink(assignedId, any(), any()) } returns sink
        coEvery { encryptedMediaStore.finalize(assignedId) } returns true
        // Retry cleanup (failed earlier attempt) cancels + deletes the placeholder row.
        coEvery { encryptedMediaStore.cancel(assignedId) } returns Unit
        coEvery { videoRepository.deleteById(assignedId) } returns Unit
    }

    // ===========================================================================
    // 1. CANCELLATION — consumeAndExecute NonCancellable cleanup
    // ===========================================================================

    @Test
    fun `consumeAndExecute cancellation runs NonCancellable markCancelled and deleteByRelationId`() = runTest {
        // consumeAndExecute is the path SyncService uses. On cancellation it must clean up
        // pending tasks inside NonCancellable (so cleanup runs even when the job is cancelled).
        val pendingEntities = listOf(
            PendingSyncActionEntity(
                relationId = relationId, relationName = "rel", localBasePath = "/local",
                actionGroup = 0, actionType = "DOWNLOAD", relativePath = "slow.mp4",
                fileSize = 1000L, status = "PENDING"
            )
        )
        coEvery { pendingSyncActionDao.resetRunningToPending(relationId) } returns Unit
        coEvery { pendingSyncActionDao.getPendingByRelationId(relationId) } returns pendingEntities
        coEvery { pendingSyncActionDao.markRunning(relationId) } returns Unit
        coEvery { pendingSyncActionDao.markCancelled(relationId) } returns Unit
        coEvery { pendingSyncActionDao.deleteByRelationId(relationId) } returns Unit

        val started = CompletableDeferred<Unit>()
        hangingClient(started)
        stubSuccessfulDownload("slow.mp4", assignedId = 300L)

        val parentJob = Job()
        val scope = CoroutineScope(testDispatcher + parentJob)
        var caught: CancellationException? = null
        scope.launch {
            try {
                syncEngine.consumeAndExecute(relationId)
            } catch (e: CancellationException) {
                caught = e
            }
        }

        started.await()
        parentJob.cancelAndJoin()

        // Cancellation must propagate out to the caller.
        assertTrue("CancellationException must propagate", caught != null)
        // NonCancellable cleanup must have run.
        coVerify(exactly = 1) { pendingSyncActionDao.markCancelled(relationId) }
        coVerify(exactly = 1) { pendingSyncActionDao.deleteByRelationId(relationId) }
        // On the cancellation path, neither markCompleted nor markFailed should run.
        coVerify(exactly = 0) { pendingSyncActionDao.markCompleted(relationId) }
        coVerify(exactly = 0) { pendingSyncActionDao.markFailed(relationId) }
    }

    // ===========================================================================
    // 2. TERMINAL ERRORS — must NOT retry (beyond the PATH_NOT_FOUND/AUTH already covered)
    // ===========================================================================

    @Test
    fun `download throwing SyncException ACCESS_DENIED is not retried`() = runTest {
        val client = mockk<RemoteFileClient>(relaxed = true)
        every { remoteFileClientFactory.create(SyncProtocol.SFTP) } returns client
        coEvery { client.downloadTo(any(), any(), any()) } throws SyncException(
            SyncErrorCode.ACCESS_DENIED, SyncOperation.DOWNLOAD, path = "video.mp4"
        )
        stubFailedDownload("video.mp4", assignedId = 400L)

        val result = syncEngine.executeSync(relationId, listOf(downloadAction("video.mp4")), emptyList())

        assertFalse(result)
        assertEquals(SyncPhase.PARTIAL_FAILED, syncEngine.progress.value.phase)
        assertEquals(0, syncEngine.progress.value.completedFiles)
        assertEquals(1, syncEngine.progress.value.failedFiles)
        // Terminal → exactly one attempt, no retry.
        coVerify(exactly = 1) { client.downloadTo(any(), any(), any()) }
    }

    @Test
    fun `download throwing SyncException INSUFFICIENT_SPACE is not retried`() = runTest {
        val client = mockk<RemoteFileClient>(relaxed = true)
        every { remoteFileClientFactory.create(SyncProtocol.SFTP) } returns client
        coEvery { client.downloadTo(any(), any(), any()) } throws SyncException(
            SyncErrorCode.INSUFFICIENT_SPACE, SyncOperation.DOWNLOAD, path = "video.mp4",
            expectedBytes = 10_000L, availableBytes = 100L
        )
        stubFailedDownload("video.mp4", assignedId = 401L)

        val result = syncEngine.executeSync(relationId, listOf(downloadAction("video.mp4")), emptyList())

        assertFalse(result)
        assertEquals(1, syncEngine.progress.value.failedFiles)
        // Terminal → exactly one attempt.
        coVerify(exactly = 1) { client.downloadTo(any(), any(), any()) }
    }

    // ===========================================================================
    // 3. RETRY-WHITELIST — transient codes retry and can recover or exhaust
    // ===========================================================================

    @Test
    fun `download throwing TIMEOUT retries and succeeds on second attempt`() = runTest {
        // TIMEOUT is in the retry whitelist → the engine should retry and recover.
        // Real backoff delay applies (1s on first retry) → ~1s wall-clock.
        val client = mockk<RemoteFileClient>(relaxed = true)
        every { remoteFileClientFactory.create(SyncProtocol.SFTP) } returns client
        coEvery { client.downloadTo(any(), any(), any()) } throws
            SyncException(SyncErrorCode.TIMEOUT, SyncOperation.DOWNLOAD, path = "video.mp4") andThen true
        stubSuccessfulDownload("video.mp4", assignedId = 500L)

        val result = syncEngine.executeSync(relationId, listOf(downloadAction("video.mp4")), emptyList())

        assertTrue("whitelisted code must be retried to recovery", result)
        assertEquals(SyncPhase.COMPLETED, syncEngine.progress.value.phase)
        assertEquals(1, syncEngine.progress.value.completedFiles)
        assertEquals(0, syncEngine.progress.value.failedFiles)
        // Exactly 2 attempts: one failure + one success.
        coVerify(exactly = 2) { client.downloadTo(any(), any(), any()) }
    }

    @Test
    fun `download throwing CONNECTION_REFUSED retries all 3 times then fails`() = runTest {
        // CONNECTION_REFUSED is whitelisted → retries all 3 attempts before giving up.
        // Real backoff (1s + 2s) → ~3s wall-clock.
        val client = mockk<RemoteFileClient>(relaxed = true)
        every { remoteFileClientFactory.create(SyncProtocol.SFTP) } returns client
        coEvery { client.downloadTo(any(), any(), any()) } throws
            SyncException(SyncErrorCode.CONNECTION_REFUSED, SyncOperation.DOWNLOAD, path = "video.mp4")
        stubFailedDownload("video.mp4", assignedId = 501L)

        val result = syncEngine.executeSync(relationId, listOf(downloadAction("video.mp4")), emptyList())

        assertFalse(result)
        assertEquals(SyncPhase.PARTIAL_FAILED, syncEngine.progress.value.phase)
        assertEquals(1, syncEngine.progress.value.failedFiles)
        // Whitelisted → all 3 attempts consumed.
        coVerify(exactly = 3) { client.downloadTo(any(), any(), any()) }
    }

    // ===========================================================================
    // 4. VOLUME MISMATCH — executeDelete DELETE_BOTH path
    // ===========================================================================

    @Test
    fun `executeDelete DELETE_BOTH with mismatched volumeId is counted as FAILED and deletes nothing`() = runTest {
        // Parallel regression to the executeSync DELETE_LOCAL volume-mismatch guard.
        // An entity belonging to another volume must be skipped (not deleted locally or
        // remotely) yet counted as a failed action so the relation shows PARTIAL_FAILED.
        every { encryptedMediaStore.volumeId } returns "current-vol"

        val action = SyncAction(type = SyncActionType.DELETE_BOTH, relativePath = "x.mp4", fileSize = 100L)
        val mismatchedEntity = VideoItemEntity(
            id = 600L, syncRelationId = relationId, relativePath = "x.mp4",
            fileName = "x.mp4", fileSize = 100L, volumeId = "other-vol", iv = ByteArray(0)
        )
        coEvery { videoRepository.getByRelativePathAndRelationId("x.mp4", relationId) } returns mismatchedEntity

        val client = mockk<RemoteFileClient>(relaxed = true)
        every { remoteFileClientFactory.create(SyncProtocol.SFTP) } returns client

        val result = syncEngine.executeDelete(relationId, listOf(action))

        assertFalse("volume-mismatch-only delete must not report success", result)
        val p = syncEngine.progress.value
        assertEquals(SyncPhase.PARTIAL_FAILED, p.phase)
        assertEquals(0, p.completedFiles)
        assertEquals(1, p.failedFiles)
        // No local media/thumb deletion.
        coVerify(exactly = 0) { encryptedMediaStore.delete(600L) }
        coVerify(exactly = 0) { encryptedMediaStore.deleteThumb(600L) }
        // No remote deletion attempt.
        coVerify(exactly = 0) { client.deleteFile(any()) }
        // No DB row deletion.
        coVerify(exactly = 0) { videoRepository.deleteByRelativePathAndRelationId("x.mp4", relationId) }
        // SKIPPED log written but counted as failure.
        coVerify(exactly = 1) {
            syncLogRepository.log(action = "DELETE_BOTH", relativePath = "x.mp4", status = "SKIPPED", any(), any(), any())
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun stubFailedDownload(relativePath: String, assignedId: Long) {
        val localRelPath = storagePaths.toLocalRelativePath("/local", relativePath)
        coEvery { videoRepository.getByRelativePathAndRelationId(localRelPath, relationId) } returns null
        coEvery { videoRepository.upsert(match { it.relativePath == localRelPath }) } returns assignedId
        // On each failed attempt the catch block cancels the sink + deletes the placeholder row.
        coEvery { encryptedMediaStore.cancel(assignedId) } returns Unit
        coEvery { videoRepository.deleteById(assignedId) } returns Unit
        val sink = mockk<OutputStream>(relaxed = true)
        coEvery { encryptedMediaStore.openEncryptSink(assignedId, any(), any()) } returns sink
    }
}
