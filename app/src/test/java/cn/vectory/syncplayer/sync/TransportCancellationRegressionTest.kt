package cn.vectory.syncplayer.sync

import cn.vectory.syncplayer.data.local.dao.PendingSyncActionDao
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
import cn.vectory.syncplayer.sync.sftp.SftpFileClient
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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Engine-level cancellation regression tests (CQ-001, Phase 1).
 *
 * These drive [SyncEngine] end-to-end with a REAL [SftpFileClient] (via the
 * sshClientFactory seam — only the SSHJ layer is mocked). When the coroutine is
 * cancelled while an SSHJ blocking call is in flight and that call then completes with
 * a NORMAL IOException, the transport must surface CancellationException — the engine
 * must NOT classify the run as FAILED: no PARTIAL_FAILED/FAILED terminal phase, no
 * failed-status sync log entry, no `updateLastSync` write.
 *
 * Asserts on exception types, phases, and call counts only — never on message text.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransportCancellationRegressionTest {

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

    private val sshClient = mockk<net.schmizz.sshj.SSHClient>(relaxed = true)
    private val sftpClient = mockk<net.schmizz.sshj.sftp.SFTPClient>(relaxed = true)
    private val sftpFileClient = SftpFileClient(context, sshClientFactory = { sshClient })

    private lateinit var syncEngine: SyncEngine
    private val testDispatcher = StandardTestDispatcher()
    private var tempDir: java.io.File? = null

    private val relation = mockk<SyncRelation>()
    private val source = SyncSource(
        id = 10L,
        name = "s",
        protocol = SyncProtocol.SFTP,
        host = "host",
        port = 22,
        username = "u",
        password = "p"
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        tempDir = java.nio.file.Files.createTempDirectory("transport-cancel-test-").toFile()
        every { context.filesDir } returns tempDir
        stubNetworkOk()
        every { encryptedMediaStoreLazy.get() } returns encryptedMediaStore
        every { relation.id } returns 1L
        every { relation.syncSourceId } returns 10L
        every { relation.remotePath } returns "/remote"
        every { relation.localBasePath } returns "/local"
        every { relation.name } returns "rel"
        coEvery { syncRelationRepository.getById(1L) } returns relation
        coEvery { syncSourceRepository.getById(10L) } returns source
        every { sshClient.newSFTPClient() } returns sftpClient
        every { remoteFileClientFactory.create(SyncProtocol.SFTP) } returns sftpFileClient
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

    @org.junit.After
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
        tempDir?.deleteRecursively()
        tempDir = null
    }

    private fun stubNetworkOk() {
        val cm = mockk<android.net.ConnectivityManager>()
        val network = mockk<android.net.Network>()
        val caps = mockk<android.net.NetworkCapabilities>()
        every { context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) } returns cm
        every { cm.activeNetwork } returns network
        every { cm.getNetworkCapabilities(network) } returns caps
        every { caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) } returns true
    }

    /**
     * Runs [block] in a child coroutine of a fresh [Job]; waits until the blocking seam
     * signals [started], cancels the parent job, releases the seam via [release], and
     * returns the terminal Throwable (null if [block] completed normally).
     *
     * try/finally guarantees the seam is always released and the job always cancelled
     * (no leaks, no hung threads) even if the start signal times out; the terminal
     * wait is bounded to 10s. Uses a structured scope on Dispatchers.Default —
     * no GlobalScope.
     */
    private fun awaitTerminalAfterCancel(
        started: CountDownLatch,
        release: CountDownLatch,
        block: suspend () -> Unit
    ): Throwable? {
        val parentJob = Job()
        val terminal = CompletableDeferred<Throwable?>()
        val scope = CoroutineScope(parentJob + Dispatchers.Default)
        try {
            scope.launch {
                try {
                    block()
                    terminal.complete(null)
                } catch (t: Throwable) {
                    terminal.completeExceptionally(t)
                }
            }
            assertTrue("blocking seam never signalled start", started.await(10, TimeUnit.SECONDS))
            parentJob.cancel()
        } finally {
            release.countDown()
            parentJob.cancel()
        }
        return try {
            runBlocking { withTimeout(10_000) { terminal.await() } }
            null
        } catch (t: Throwable) {
            if (t is TimeoutCancellationException) {
                throw AssertionError("terminal never arrived within 10s", t)
            }
            t
        }
    }

    private fun assertCancelled(t: Throwable?) {
        assertTrue(
            "expected CancellationException but was ${t?.javaClass}",
            t is CancellationException
        )
    }

    @Test
    fun `dryRun cancelled mid-scan terminates as CancellationException without deeper scan or FAILED`() = runTest {
        val dirA = mockk<net.schmizz.sshj.sftp.RemoteResourceInfo>()
        val dirB = mockk<net.schmizz.sshj.sftp.RemoteResourceInfo>()
        every { dirA.name } returns "dirA"
        every { dirA.isDirectory } returns true
        every { dirB.name } returns "dirB"
        every { dirB.isDirectory } returns true
        val dirBLsCalls = AtomicInteger(0)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { sftpClient.ls("/remote") } returns listOf(dirA)
        every { sftpClient.ls("/remote/dirA") } answers {
            started.countDown()
            release.await(10, TimeUnit.SECONDS)
            listOf(dirB)
        }
        every { sftpClient.ls("/remote/dirB") } answers {
            dirBLsCalls.incrementAndGet()
            emptyList<net.schmizz.sshj.sftp.RemoteResourceInfo>()
        }
        coEvery { videoRepository.getBySyncRelationId(1L) } returns emptyList()
        every { storagePaths.toLocalRelativePath(any(), any()) } returns "dirB"

        val terminal = awaitTerminalAfterCancel(started, release) { syncEngine.dryRun(1L) }

        assertCancelled(terminal)
        // The directory whose parent scan straddled the cancellation must never be scanned.
        assertEquals(0, dirBLsCalls.get())
        assertTrue(syncEngine.progress.value.phase != SyncPhase.FAILED)
        coVerify(exactly = 0) { syncRelationRepository.updateLastSync(any(), any()) }
        coVerify(exactly = 0) {
            syncLogRepository.log(action = any(), relativePath = any(), status = "FAILED", dryRun = any(), fileSize = any(), errorMessage = any())
        }
    }

    @Test
    fun `executeDelete cancelled mid-remote-delete never reports FAILED nor updates lastSync`() = runTest {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { storagePaths.toPathUnderBase(any(), any()) } returns "b.mp4"
        coEvery { videoRepository.getByRelativePathAndRelationId("b.mp4", 1L) } returns null
        every { sftpClient.rm(any()) } answers {
            started.countDown()
            release.await(10, TimeUnit.SECONDS)
            throw IOException("simulated transport teardown")
        }
        val action = SyncAction(
            type = SyncActionType.DELETE_BOTH,
            relativePath = "b.mp4",
            fileSize = 10L
        )

        val terminal = awaitTerminalAfterCancel(started, release) {
            syncEngine.executeDelete(1L, listOf(action))
        }

        assertCancelled(terminal)
        val phase = syncEngine.progress.value.phase
        assertTrue("unexpected terminal phase $phase", phase != SyncPhase.FAILED && phase != SyncPhase.PARTIAL_FAILED)
        coVerify(exactly = 0) { syncRelationRepository.updateLastSync(any(), any()) }
        coVerify(exactly = 0) {
            syncLogRepository.log(action = any(), relativePath = any(), status = "FAILED", dryRun = any(), fileSize = any(), errorMessage = any())
        }
    }
}
