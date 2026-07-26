package cn.vectory.syncplayer.sync

import cn.vectory.syncplayer.data.local.dao.PendingSyncActionDao
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
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
 * Unit tests for progress/counter accounting in [SyncEngine.executeSync].
 *
 * Asserts on [SyncPhase], counter values in the final [syncEngine.progress] snapshot,
 * and MockK call counts. Never asserts on user-facing message text.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncEngineProgressTest {

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
        name = "rel",
        syncSourceId = 10L,
        remotePath = "/remote",
        localBasePath = "/local"
    )
    private val source = SyncSource(
        id = 10L,
        name = "src",
        protocol = SyncProtocol.SFTP,
        host = "h",
        port = 22,
        username = "u",
        password = "p"
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

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun makeDownloadableClient(downloadSucceeds: Boolean): RemoteFileClient {
        val client = mockk<RemoteFileClient>(relaxed = true)
        every { remoteFileClientFactory.create(SyncProtocol.SFTP) } returns client
        coEvery { client.downloadTo(any(), any(), any()) } returns downloadSucceeds
        return client
    }

    /** Stub the download success path: new file → upsert → extractAndSave → finalize true. */
    private fun stubSuccessfulDownload(relativePath: String, assignedId: Long) {
        val localRelPath = storagePaths.toLocalRelativePath("/local", relativePath)
        coEvery {
            videoRepository.getByRelativePathAndRelationId(localRelPath, relationId)
        } returns null
        // Use match on relativePath so concurrent calls with different files don't collide.
        coEvery {
            videoRepository.upsert(match { it.relativePath == localRelPath })
        } returns assignedId
        val savedEntity = VideoItemEntity(
            id = assignedId, syncRelationId = relationId, relativePath = localRelPath,
            fileName = relativePath.substringAfterLast("/"),
            fileSize = 1000L, volumeId = "test-vol", iv = ByteArray(12)
        )
        coEvery { videoRepository.getById(assignedId) } returns savedEntity
        coEvery { videoMetadataExtractor.extractAndSave(any()) } returns savedEntity
        val sink = mockk<OutputStream>(relaxed = true)
        coEvery { encryptedMediaStore.openEncryptSink(assignedId, any(), any()) } returns sink
        coEvery { encryptedMediaStore.finalize(assignedId) } returns true
    }

    private fun stubFailedDownload(relativePath: String, assignedId: Long) {
        val localRelPath = storagePaths.toLocalRelativePath("/local", relativePath)
        coEvery {
            videoRepository.getByRelativePathAndRelationId(localRelPath, relationId)
        } returns null
        coEvery {
            videoRepository.upsert(match { it.relativePath == localRelPath })
        } returns assignedId
        coEvery { videoRepository.deleteById(assignedId) } returns Unit
        val sink = mockk<OutputStream>(relaxed = true)
        coEvery { encryptedMediaStore.openEncryptSink(assignedId, any(), any()) } returns sink
        coEvery { encryptedMediaStore.cancel(assignedId) } returns Unit
    }

    // =========================================================================
    // DOWNLOAD — SUCCESS
    // =========================================================================

    @Test
    fun `successful download increments completed exactly once`() = runTest {
        makeDownloadableClient(downloadSucceeds = true)
        stubSuccessfulDownload("video.mp4", assignedId = 100L)

        val action = SyncAction(type = SyncActionType.DOWNLOAD, relativePath = "video.mp4", fileSize = 1000L)
        val result = syncEngine.executeSync(relationId, listOf(action), emptyList())

        assertTrue(result)
        val p = syncEngine.progress.value
        assertEquals(SyncPhase.COMPLETED, p.phase)
        assertEquals("completedFiles must be exactly 1 for a single successful download", 1, p.completedFiles)
        assertEquals("failedFiles must be 0", 0, p.failedFiles)
        assertEquals(1, p.totalFiles)
        // Exactly one SUCCESS log entry for the download.
        coVerify(exactly = 1) {
            syncLogRepository.log(action = "DOWNLOAD", any(), status = "SUCCESS", any(), any(), any())
        }
    }

    // =========================================================================
    // DOWNLOAD — FAILURE
    // =========================================================================

    @Test
    fun `failed download increments failed count not completed`() = runTest {
        makeDownloadableClient(downloadSucceeds = false)
        stubFailedDownload("video.mp4", assignedId = 200L)

        val action = SyncAction(type = SyncActionType.DOWNLOAD, relativePath = "video.mp4", fileSize = 1000L)
        val result = syncEngine.executeSync(relationId, listOf(action), emptyList())

        assertFalse("all-failed executeSync must return false", result)
        val p = syncEngine.progress.value
        assertEquals(SyncPhase.PARTIAL_FAILED, p.phase)
        assertEquals("completedFiles must be 0 when the sole download fails", 0, p.completedFiles)
        assertEquals("failedFiles must be 1", 1, p.failedFiles)
        // Exactly one FAILED log entry.
        coVerify(exactly = 1) {
            syncLogRepository.log(action = "DOWNLOAD", any(), status = "FAILED", any(), any(), any())
        }
        // No SUCCESS log entries.
        coVerify(exactly = 0) {
            syncLogRepository.log(action = "DOWNLOAD", any(), status = "SUCCESS", any(), any(), any())
        }
    }

    // =========================================================================
    // DOWNLOAD — RETRY POLICY (P1-2)
    // Non-retryable SyncException codes MUST short-circuit after exactly 1 attempt.
    // Transient (non-SyncException) throwables still retry up to 3×.
    // =========================================================================

    @Test
    fun `download throwing SyncException PATH_NOT_FOUND is not retried`() = runTest {
        // PATH_NOT_FOUND is terminal — the remote file is gone; retrying won't bring it back.
        val client = mockk<RemoteFileClient>(relaxed = true)
        every { remoteFileClientFactory.create(SyncProtocol.SFTP) } returns client
        coEvery { client.downloadTo(any(), any(), any()) } throws SyncException(
            SyncErrorCode.PATH_NOT_FOUND, SyncOperation.DOWNLOAD, path = "video.mp4"
        )
        stubFailedDownload("video.mp4", assignedId = 200L)

        val action = SyncAction(type = SyncActionType.DOWNLOAD, relativePath = "video.mp4", fileSize = 1000L)
        val result = syncEngine.executeSync(relationId, listOf(action), emptyList())

        assertFalse("terminal-error sync must not report success", result)
        val p = syncEngine.progress.value
        assertEquals(SyncPhase.PARTIAL_FAILED, p.phase)
        assertEquals("completedFiles must be 0", 0, p.completedFiles)
        assertEquals("failedFiles must be 1", 1, p.failedFiles)
        // P1-2 regression: MUST be invoked exactly once, NOT 3×.
        coVerify(exactly = 1) { client.downloadTo(any(), any(), any()) }
    }

    @Test
    fun `download throwing SyncException AUTHENTICATION_FAILED is not retried`() = runTest {
        // AUTHENTICATION_FAILED is terminal — retrying just reapplies auth pressure.
        val client = mockk<RemoteFileClient>(relaxed = true)
        every { remoteFileClientFactory.create(SyncProtocol.SFTP) } returns client
        coEvery { client.downloadTo(any(), any(), any()) } throws SyncException(
            SyncErrorCode.AUTHENTICATION_FAILED, SyncOperation.DOWNLOAD, path = "video.mp4"
        )
        stubFailedDownload("video.mp4", assignedId = 201L)

        val action = SyncAction(type = SyncActionType.DOWNLOAD, relativePath = "video.mp4", fileSize = 1000L)
        val result = syncEngine.executeSync(relationId, listOf(action), emptyList())

        assertFalse(result)
        val p = syncEngine.progress.value
        assertEquals(SyncPhase.PARTIAL_FAILED, p.phase)
        assertEquals(0, p.completedFiles)
        assertEquals(1, p.failedFiles)
        // P1-2 regression: MUST be invoked exactly once, NOT 3×.
        coVerify(exactly = 1) { client.downloadTo(any(), any(), any()) }
    }

    @Test
    fun `download throwing transient IOException is retried up to 3 times`() = runTest {
        // A plain IOException is not a SyncException → conservatively retryable (UNKNOWN).
        // Verifies the retry mechanism still kicks in for genuinely transient transport errors
        // and that P1-2's early-exit only short-circuits *terminal* codes.
        // NOTE: real delays apply (download runs on Dispatchers.IO, not the test scheduler),
        // so this test takes ~3s wall-clock (1s + 2s backoff). Acceptable for a one-off regression.
        val client = mockk<RemoteFileClient>(relaxed = true)
        every { remoteFileClientFactory.create(SyncProtocol.SFTP) } returns client
        coEvery { client.downloadTo(any(), any(), any()) } throws
            java.io.IOException("transient transport error")
        stubFailedDownload("video.mp4", assignedId = 202L)

        val action = SyncAction(type = SyncActionType.DOWNLOAD, relativePath = "video.mp4", fileSize = 1000L)
        val result = syncEngine.executeSync(relationId, listOf(action), emptyList())

        assertFalse(result)
        val p = syncEngine.progress.value
        assertEquals(1, p.failedFiles)
        assertEquals(0, p.completedFiles)
        // Retryable → all 3 attempts consumed before giving up.
        coVerify(exactly = 3) { client.downloadTo(any(), any(), any()) }
    }

    // =========================================================================
    // COUNTERS — no double-counting
    // =========================================================================

    @Test
    fun `multiple successful downloads each increment completed exactly once`() = runTest {
        makeDownloadableClient(downloadSucceeds = true)
        stubSuccessfulDownload("a.mp4", assignedId = 101L)
        stubSuccessfulDownload("b.mp4", assignedId = 102L)

        val actions = listOf(
            SyncAction(type = SyncActionType.DOWNLOAD, relativePath = "a.mp4", fileSize = 500L),
            SyncAction(type = SyncActionType.DOWNLOAD, relativePath = "b.mp4", fileSize = 600L)
        )
        val result = syncEngine.executeSync(relationId, actions, emptyList())

        assertTrue(result)
        val p = syncEngine.progress.value
        assertEquals("completedFiles must equal exactly the number of successful downloads", 2, p.completedFiles)
        assertEquals(0, p.failedFiles)
        assertEquals(2, p.totalFiles)
    }

    @Test
    fun `mixed success and failure counts independently`() = runTest {
        val client = mockk<RemoteFileClient>(relaxed = true)
        every { remoteFileClientFactory.create(SyncProtocol.SFTP) } returns client
        coEvery { client.downloadTo(any(), any(), any()) } returns true

        stubSuccessfulDownload("a.mp4", assignedId = 101L)

        // b.mp4 fails: stub its upsert + make openEncryptSink throw (avoids 3x retry delay).
        val bLocalRelPath = storagePaths.toLocalRelativePath("/local", "b.mp4")
        coEvery {
            videoRepository.getByRelativePathAndRelationId(bLocalRelPath, relationId)
        } returns null
        coEvery {
            videoRepository.upsert(match { it.relativePath == bLocalRelPath })
        } returns 102L
        coEvery { encryptedMediaStore.openEncryptSink(102L, any(), any()) } throws
            java.io.IOException("simulated encrypt failure")

        val actions = listOf(
            SyncAction(type = SyncActionType.DOWNLOAD, relativePath = "a.mp4", fileSize = 500L),
            SyncAction(type = SyncActionType.DOWNLOAD, relativePath = "b.mp4", fileSize = 600L)
        )
        val result = syncEngine.executeSync(relationId, actions, emptyList())

        assertFalse("partial failure must return false", result)
        val p = syncEngine.progress.value
        assertEquals(SyncPhase.PARTIAL_FAILED, p.phase)
        assertEquals("completedFiles must be 1 (only a.mp4)", 1, p.completedFiles)
        assertEquals("failedFiles must be 1 (only b.mp4)", 1, p.failedFiles)
    }

    // =========================================================================
    // DELETE_LOCAL — volume mismatch (real bug regression)
    // =========================================================================

    @Test
    fun `DELETE_LOCAL with mismatched volumeId is counted as FAILED and does not delete media`() = runTest {
        // Real bug regression: a DELETE_LOCAL whose entity.volumeId != store.volumeId
        // must NOT increment completedCount (it must not count as success) AND must
        // increment failedDeleteCount (consistency with executeDelete's DELETE_BOTH path).
        // If it were neither completed nor failed, completedFiles+failedFiles < totalFiles
        // and executeSync would misreport SUCCESS, leaving the relation stuck.
        val store = encryptedMediaStore
        every { store.volumeId } returns "current-vol"

        val action = SyncAction(type = SyncActionType.DELETE_LOCAL, relativePath = "x.mp4", fileSize = 100L)
        // Entity belongs to a DIFFERENT volume than the active store.
        val mismatchedEntity = VideoItemEntity(
            id = 500L,
            syncRelationId = relationId,
            relativePath = "x.mp4",
            fileName = "x.mp4",
            fileSize = 100L,
            volumeId = "other-vol",
            iv = ByteArray(0)
        )
        coEvery {
            videoRepository.getByRelativePathAndRelationId("x.mp4", relationId)
        } returns mismatchedEntity

        val result = syncEngine.executeSync(relationId, emptyList(), listOf(action))

        assertFalse("volume-mismatch-only sync must not report success", result)
        val p = syncEngine.progress.value
        assertEquals(SyncPhase.PARTIAL_FAILED, p.phase)
        assertEquals("completedFiles must NOT include the volume-mismatched delete", 0, p.completedFiles)
        assertEquals("failedFiles must be 1 (mismatch counts as failure to complete the relation)", 1, p.failedFiles)
        // No media deletion must occur — the ciphertext belongs to another volume.
        coVerify(exactly = 0) { store.delete(500L) }
        coVerify(exactly = 0) { store.deleteThumb(500L) }
        // No DB row deletion.
        coVerify(exactly = 0) {
            videoRepository.deleteByRelativePathAndRelationId("x.mp4", relationId)
        }
        // A SKIPPED log must be written (action not performed) but counted as failure.
        coVerify(exactly = 1) {
            syncLogRepository.log(
                action = "DELETE_LOCAL", relativePath = "x.mp4",
                status = "SKIPPED", any(), any(), any()
            )
        }
    }

    // =========================================================================
    // DELETE_LOCAL — happy path (success counted)
    // =========================================================================

    @Test
    fun `successful DELETE_LOCAL increments completed`() = runTest {
        every { encryptedMediaStore.volumeId } returns "test-vol"

        val action = SyncAction(type = SyncActionType.DELETE_LOCAL, relativePath = "x.mp4", fileSize = 100L)
        val entity = VideoItemEntity(
            id = 600L,
            syncRelationId = relationId,
            relativePath = "x.mp4",
            fileName = "x.mp4",
            fileSize = 100L,
            volumeId = "test-vol",
            iv = ByteArray(0)
        )
        coEvery {
            videoRepository.getByRelativePathAndRelationId("x.mp4", relationId)
        } returns entity
        coEvery { encryptedMediaStore.delete(600L) } returns true
        coEvery { encryptedMediaStore.deleteThumb(600L) } returns true
        coEvery { videoRepository.deleteByRelativePathAndRelationId("x.mp4", relationId) } returns Unit

        val result = syncEngine.executeSync(relationId, emptyList(), listOf(action))

        assertTrue(result)
        val p = syncEngine.progress.value
        assertEquals(SyncPhase.COMPLETED, p.phase)
        assertEquals(1, p.completedFiles)
        assertEquals(0, p.failedFiles)
    }

    // =========================================================================
    // DELETE_LOCAL — missing entity treated as success (already gone)
    // =========================================================================

    @Test
    fun `DELETE_LOCAL with no DB row counts as success`() = runTest {
        val action = SyncAction(type = SyncActionType.DELETE_LOCAL, relativePath = "ghost.mp4", fileSize = 0L)
        coEvery {
            videoRepository.getByRelativePathAndRelationId("ghost.mp4", relationId)
        } returns null

        val result = syncEngine.executeSync(relationId, emptyList(), listOf(action))

        assertTrue(result)
        val p = syncEngine.progress.value
        assertEquals("already-gone delete counts as completed", 1, p.completedFiles)
        assertEquals(0, p.failedFiles)
        coVerify(exactly = 1) {
            syncLogRepository.log(
                action = "DELETE_LOCAL", relativePath = "ghost.mp4",
                status = "SUCCESS", any(), any(), any()
            )
        }
    }

    // =========================================================================
    // CANCELLATION
    // =========================================================================

    @Test
    fun `cancellation sets progress phase to CANCELLED`() = runTest {
        // Cancel executeSync mid-download and verify the CANCELLED phase is set.
        val client = mockk<RemoteFileClient>(relaxed = true)
        every { remoteFileClientFactory.create(SyncProtocol.SFTP) } returns client

        val downloadStarted = CompletableDeferred<Unit>()
        // downloadTo signals start, then hangs on a long delay so the test can cancel.
        coEvery { client.downloadTo(any(), any(), any()) } coAnswers {
            downloadStarted.complete(Unit)
            delay(60_000L)
            true
        }
        stubSuccessfulDownload("slow.mp4", assignedId = 300L)

        val action = SyncAction(type = SyncActionType.DOWNLOAD, relativePath = "slow.mp4", fileSize = 1000L)

        // Launch executeSync on a separate job so the test body can cancel it.
        val parentJob = Job()
        val scope = CoroutineScope(testDispatcher + parentJob)
        scope.launch {
            try {
                syncEngine.executeSync(relationId, listOf(action), emptyList())
            } catch (_: CancellationException) {
                // expected
            }
        }

        // Wait until the download coroutine has actually entered downloadTo.
        downloadStarted.await()
        // Cancel and let the engine's catch-block run.
        parentJob.cancelAndJoin()

        assertEquals(
            "cancellation must set the progress phase to CANCELLED",
            SyncPhase.CANCELLED,
            syncEngine.progress.value.phase
        )
    }
}
