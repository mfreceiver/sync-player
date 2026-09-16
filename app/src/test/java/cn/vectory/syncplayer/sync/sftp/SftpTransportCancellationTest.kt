package cn.vectory.syncplayer.sync.sftp

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import cn.vectory.syncplayer.data.model.SyncSource
import cn.vectory.syncplayer.data.model.SyncProtocol
import cn.vectory.syncplayer.sync.SyncErrorCode
import cn.vectory.syncplayer.sync.SyncException
import cn.vectory.syncplayer.sync.SyncOperation
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Cancellation-propagation regression tests for [SftpFileClient] (CQ-001 + rev-sgpt).
 *
 * Two cancellation shapes are covered for connect():
 *  (A) the underlying sshj call itself throws CancellationException, and
 *  (B) the coroutine is cancelled while the call blocks, the call then completes
 *      NORMALLY, and withContext throws prompt CancellationException at its return
 *      boundary — a shape no catch INSIDE the lambda can ever observe.
 * Both shapes must: best-effort disconnect the pending SSHClient (cleanup failures
 * only logged, never replacing the original CE), reset sshClient/sftpClient, and
 * leave the client NOT_CONNECTED (no further browsing).
 *
 * For data operations the mechanism under test is: cancel in flight → underlying
 * call completes with a NORMAL exception (or an in-loop ensureActive fires) → the
 * client must terminate with the original CancellationException (CE-first, no
 * wrapping into SyncException, no degradation to empty results).
 *
 * Asserts on exception TYPES, disconnect counts, and enum codes only — never on
 * message text.
 */
class SftpTransportCancellationTest {

    private val context = mockk<Context>(relaxed = true)
    private val sshClient = mockk<net.schmizz.sshj.SSHClient>(relaxed = true)
    private val sftpClient = mockk<net.schmizz.sshj.sftp.SFTPClient>(relaxed = true)
    private val client = SftpFileClient(context, sshClientFactory = { sshClient })

    private val source = SyncSource(
        name = "s",
        protocol = SyncProtocol.SFTP,
        host = "host",
        port = 22,
        username = "u",
        password = "p"
    )

    private var tempDir: File? = null

    @org.junit.Before
    fun setUp() {
        tempDir = java.nio.file.Files.createTempDirectory("sftp-cancel-test-").toFile()
        every { context.filesDir } returns tempDir
    }

    @org.junit.After
    fun tearDown() {
        tempDir?.deleteRecursively()
        tempDir = null
    }

    private fun stubNetworkOk() {
        val cm = mockk<ConnectivityManager>()
        val network = mockk<android.net.Network>()
        val caps = mockk<NetworkCapabilities>()
        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns cm
        every { cm.activeNetwork } returns network
        every { cm.getNetworkCapabilities(network) } returns caps
        every { caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } returns true
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

    private fun connectSuccessfully() {
        stubNetworkOk()
        every { sshClient.newSFTPClient() } returns sftpClient
        runCatching { runBlocking { client.connect(source, "/") } }
            .exceptionOrNull()?.let { throw AssertionError("connect setup failed: ${it.javaClass}: cause=${it.cause?.javaClass}", it) }
    }

    /** After a cancelled connect, browsing must terminate with NOT_CONNECTED. */
    private fun assertBrowseBlockedAfterCancellation() {
        val dirs = runCatching { runBlocking { client.listDirectories("/x") } }.exceptionOrNull()
        assertTrue("post-cancel listDirectories must fail NOT_CONNECTED, got ${dirs?.javaClass}", dirs is SyncException)
        assertEquals(SyncErrorCode.NOT_CONNECTED, (dirs as SyncException).code)
        val files = runCatching { runBlocking { client.listFiles("/x") } }.exceptionOrNull()
        assertTrue("post-cancel listFiles must fail NOT_CONNECTED, got ${files?.javaClass}", files is SyncException)
        assertEquals(SyncErrorCode.NOT_CONNECTED, (files as SyncException).code)
    }

    // ------------------------------------------------------------------ pre-fix RED

    @Test
    fun `connect cancelled mid-handshake terminates with CancellationException not SyncException`() {
        stubNetworkOk()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { sshClient.connect(any<String>(), any<Int>()) } answers {
            started.countDown()
            release.await(10, TimeUnit.SECONDS)
            throw SocketTimeoutException("simulated transport timeout")
        }

        val terminal = awaitTerminalAfterCancel(started, release) { client.connect(source, "/") }

        assertCancelled(terminal)
        // Partially-established connection must still be cleaned up.
        verify(exactly = 1) { sshClient.disconnect() }
    }

    @Test
    fun `downloadTo cancelled mid-read terminates with CancellationException not SyncException`() {
        connectSuccessfully()
        val attrs = mockk<net.schmizz.sshj.sftp.FileAttributes>()
        val remoteFile = mockk<net.schmizz.sshj.sftp.RemoteFile>(relaxed = true)
        every { attrs.size } returns 8L
        every { sftpClient.stat(any()) } returns attrs
        every { sftpClient.open(any()) } returns remoteFile
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { remoteFile.read(any<Long>(), any(), any<Int>(), any<Int>()) } answers {
            started.countDown()
            release.await(10, TimeUnit.SECONDS)
            8
        }
        val sink = mockk<OutputStream>(relaxed = true)

        val terminal = awaitTerminalAfterCancel(started, release) {
            client.downloadTo("v.mp4", sink, null)
        }

        assertCancelled(terminal)
    }

    @Test
    fun `deleteFile cancelled mid-rm terminates with CancellationException not SyncException`() {
        connectSuccessfully()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { sftpClient.rm(any()) } answers {
            started.countDown()
            release.await(10, TimeUnit.SECONDS)
            throw IOException("simulated transport teardown")
        }

        val terminal = awaitTerminalAfterCancel(started, release) { client.deleteFile("v.mp4") }

        assertCancelled(terminal)
    }

    // ------------------------------------- connect cancellation cleanup (rev-sgpt)

    /** Type A: the underlying handshake throws CancellationException DIRECTLY. */
    @Test
    fun `connect direct CancellationException disconnects pending client and blocks browsing`() {
        stubNetworkOk()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { sshClient.connect(any<String>(), any<Int>()) } answers {
            started.countDown()
            release.await(10, TimeUnit.SECONDS)
            throw CancellationException("cancelled by test")
        }

        val terminal = awaitTerminalAfterCancel(started, release) { client.connect(source, "/") }

        assertCancelled(terminal)
        verify(exactly = 1) { sshClient.disconnect() }
        assertBrowseBlockedAfterCancellation()
    }

    /**
     * Type B: the underlying handshake returns NORMALLY after cancellation;
     * withContext throws prompt CancellationException at its return boundary.
     */
    @Test
    fun `connect normal return after cancel disconnects pending client and blocks browsing`() {
        stubNetworkOk()
        every { sshClient.newSFTPClient() } returns sftpClient
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { sshClient.connect(any<String>(), any<Int>()) } answers {
            started.countDown()
            release.await(10, TimeUnit.SECONDS)
            Unit
        }

        val terminal = awaitTerminalAfterCancel(started, release) { client.connect(source, "/") }

        assertCancelled(terminal)
        verify(exactly = 1) { sshClient.disconnect() }
        assertBrowseBlockedAfterCancellation()
    }

    /** Cleanup failure must never mask the original CancellationException. */
    @Test
    fun `connect direct CE with failing disconnect still rethrows original CancellationException`() {
        stubNetworkOk()
        every { sshClient.disconnect() } throws IOException("simulated cleanup failure")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { sshClient.connect(any<String>(), any<Int>()) } answers {
            started.countDown()
            release.await(10, TimeUnit.SECONDS)
            throw CancellationException("cancelled by test")
        }

        val terminal = awaitTerminalAfterCancel(started, release) { client.connect(source, "/") }

        assertCancelled(terminal)
        verify(exactly = 1) { sshClient.disconnect() }
        assertBrowseBlockedAfterCancellation()
    }

    // ------------------------------------------------------------------ pre/post GUARD

    @Test
    fun `listDirectories cancelled mid-ls terminates with CancellationException`() {
        connectSuccessfully()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { sftpClient.ls(any()) } answers {
            started.countDown()
            release.await(10, TimeUnit.SECONDS)
            throw IOException("simulated transport teardown")
        }

        val terminal = awaitTerminalAfterCancel(started, release) { client.listDirectories("/base") }

        assertCancelled(terminal)
    }

    @Test
    fun `listDirectories uncanceled IOException throws SyncException not empty list`() {
        connectSuccessfully()
        every { sftpClient.ls(any()) } throws IOException("simulated transport failure")

        // listDirectories 契约：枚举失败 fail-loud —— SyncException 而非空列表。
        val t = runCatching { runBlocking { client.listDirectories("/base") } }.exceptionOrNull()

        assertTrue("expected SyncException but was ${t?.javaClass}", t is SyncException)
        assertEquals(SyncErrorCode.UNKNOWN, (t as SyncException).code)
        assertEquals(SyncOperation.BROWSE, (t as SyncException).operation)
    }

    @Test
    fun `deleteFile uncanceled IOException still classified as REMOTE_DELETE_FAILED`() {
        connectSuccessfully()
        every { sftpClient.rm(any()) } throws IOException("simulated transport failure")

        val t = runCatching { runBlocking { client.deleteFile("v.mp4") } }
            .exceptionOrNull()

        assertTrue(t is SyncException)
        assertEquals(SyncErrorCode.REMOTE_DELETE_FAILED, (t as SyncException).code)
    }

    // -------------------------------------- BUG-009: deleteFile NO_SUCH_FILE idempotency

    @Test
    fun `deleteFile maps NO_SUCH_FILE to idempotent false`() {
        connectSuccessfully()
        every { sftpClient.rm(any()) } throws SFTPException(Response.StatusCode.NO_SUCH_FILE, "no such file")

        // BUG-009 (red under old catch-all): already-gone must return false (idempotent success,
        // aligned with SMB !exists → false), never REMOTE_DELETE_FAILED.
        val result = runCatching { runBlocking { client.deleteFile("v.mp4") } }

        assertFalse("NO_SUCH_FILE must be idempotent false, not an exception", result.isFailure)
        assertFalse(result.getOrNull() ?: true)
    }

    @Test
    fun `deleteFile maps other SFTP errors to REMOTE_DELETE_FAILED`() {
        connectSuccessfully()
        every { sftpClient.rm(any()) } throws SFTPException(Response.StatusCode.PERMISSION_DENIED, "permission denied")

        val t = runCatching { runBlocking { client.deleteFile("v.mp4") } }.exceptionOrNull()

        assertTrue("expected SyncException but was ${t?.javaClass}", t is SyncException)
        assertEquals(SyncErrorCode.REMOTE_DELETE_FAILED, (t as SyncException).code)
        assertEquals(SyncOperation.DELETE, (t as SyncException).operation)
    }

    @Test
    fun `deleteFile wraps SyncException input per existing catch-all contract`() {
        connectSuccessfully()
        val input = SyncException(SyncErrorCode.REMOTE_DELETE_FAILED, SyncOperation.DELETE)
        every { sftpClient.rm(any()) } throws input

        val t = runCatching { runBlocking { client.deleteFile("v.mp4") } }.exceptionOrNull()

        assertTrue("expected SyncException but was ${t?.javaClass}", t is SyncException)
        val se = t as SyncException
        assertTrue("catch-all must rewrap into a NEW instance, not pass through", se !== input)
        assertEquals(SyncErrorCode.REMOTE_DELETE_FAILED, se.code)
        assertEquals(SyncOperation.DELETE, se.operation)
        assertTrue("cause must be the original input instance", se.cause === input)
    }

    @Test
    fun `deleteFile success returns true`() {
        connectSuccessfully()

        val result = runBlocking { client.deleteFile("v.mp4") }

        assertTrue(result)
    }

    @Test
    fun `deleteFile requires connection`() {
        val fresh = SftpFileClient(context, sshClientFactory = { sshClient })

        val t = runCatching { runBlocking { fresh.deleteFile("v.mp4") } }.exceptionOrNull()

        assertTrue("expected SyncException but was ${t?.javaClass}", t is SyncException)
        assertEquals(SyncErrorCode.NOT_CONNECTED, (t as SyncException).code)
    }

    @Test
    fun `deleteFile rethrows direct CancellationException without wrapping`() {
        connectSuccessfully()
        every { sftpClient.rm(any()) } throws CancellationException("cancelled by test")

        val t = runCatching { runBlocking { client.deleteFile("v.mp4") } }.exceptionOrNull()

        assertTrue(
            "expected CancellationException but was ${t?.javaClass}",
            t is CancellationException
        )
    }

    // ------------------------------------------- recursion active check (directed)

    @Test
    fun `listFiles cancelled mid-recursion does not scan deeper directories`() {
        connectSuccessfully()
        val dirA = mockk<net.schmizz.sshj.sftp.RemoteResourceInfo>()
        val dirB = mockk<net.schmizz.sshj.sftp.RemoteResourceInfo>()
        every { dirA.name } returns "dirA"
        every { dirA.isDirectory } returns true
        every { dirB.name } returns "dirB"
        every { dirB.isDirectory } returns true
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        var dirBLsCalls = 0
        every { sftpClient.ls("/remote") } returns listOf(dirA)
        every { sftpClient.ls("/remote/dirA") } answers {
            started.countDown()
            release.await(10, TimeUnit.SECONDS)
            listOf(dirB)
        }
        every { sftpClient.ls("/remote/dirB") } answers {
            dirBLsCalls++
            emptyList<net.schmizz.sshj.sftp.RemoteResourceInfo>()
        }

        val terminal = awaitTerminalAfterCancel(started, release) { client.listFiles("/remote") }

        assertCancelled(terminal)
        // The entry whose parent scan straddled the cancellation must never be touched.
        assertEquals(0, dirBLsCalls)
    }
}
