package cn.vectory.syncplayer.sync.smb

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import cn.vectory.syncplayer.data.model.SyncProtocol
import cn.vectory.syncplayer.data.model.SyncSource
import cn.vectory.syncplayer.sync.SyncErrorCode
import cn.vectory.syncplayer.sync.SyncException
import cn.vectory.syncplayer.sync.SyncOperation
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Cancellation-propagation regression tests for [SmbFileClient] (CQ-001 + rev-sgpt).
 *
 * Two cancellation shapes are covered for connect():
 *  (A) the jcifs validation call itself throws CancellationException, and
 *  (B) the coroutine is cancelled while the call blocks, the call then completes
 *      NORMALLY, and withContext throws prompt CancellationException at its return
 *      boundary — a shape no catch INSIDE the lambda can ever observe.
 * Both shapes must clear cifsContext so listShares/listFiles terminate with
 * NOT_CONNECTED and never touch the SmbFile factory again.
 *
 * For data operations the mechanism under test is: cancel in flight → underlying
 * call completes with a NORMAL exception (or an in-loop ensureActive fires) → the
 * client must terminate with the original CancellationException (CE-first) —
 * including in per-entry catch blocks, where cancellation must also stop processing
 * of subsequent entries. Uncanceled per-entry degradation (skip bad entry) must
 * remain unchanged; enumeration-phase failures are now fail-loud SyncException
 * (listDirectories contract — never silently empty).
 *
 * Asserts on exception TYPES, entry/factory counts, and enum codes only — never on
 * message text.
 */
class SmbTransportCancellationTest {

    private val context = mockk<Context>(relaxed = true)
    private val factory = mockk<SmbFileFactory>()
    private val client = SmbFileClient(context, smbFileFactory = factory)

    private val source = SyncSource(
        name = "s",
        protocol = SyncProtocol.SMB,
        host = "host",
        port = 445,
        username = "u",
        password = "p"
    )

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

    private fun newSmbFileMock(): jcifs.smb.SmbFile = mockk(relaxed = true)

    /** Connects to share `media` so later calls have cifsContext/currentHost/shareName set. */
    private fun connectToMedia(connectFile: jcifs.smb.SmbFile) {
        stubNetworkOk()
        every { factory.create("smb://host/media/", any()) } returns connectFile
        // AND-010: connect() 探测已从 listFiles() 改为 exists() 单点查询。
        every { connectFile.exists() } returns true
        runBlocking { client.connect(source, "/media") }
    }

    /** After a cancelled connect: NOT_CONNECTED for browsing, zero further factory I/O. */
    private fun assertSessionClearedAndNoFurtherFactoryIo() {
        val shares = runCatching { runBlocking { client.listShares() } }.exceptionOrNull()
        assertTrue("post-cancel listShares must fail NOT_CONNECTED, got ${shares?.javaClass}", shares is SyncException)
        assertEquals(SyncErrorCode.NOT_CONNECTED, (shares as SyncException).code)
        val files = runCatching { runBlocking { client.listFiles("media") } }.exceptionOrNull()
        assertTrue("post-cancel listFiles must fail NOT_CONNECTED, got ${files?.javaClass}", files is SyncException)
        assertEquals(SyncErrorCode.NOT_CONNECTED, (files as SyncException).code)
        // The connect-time probe is the ONLY factory access ever made.
        verify(exactly = 1) { factory.create(any(), any()) }
    }

    // ------------------------------------------------------------------ pre-fix RED

    @Test
    fun `connect cancelled mid-probe terminates with CancellationException not SyncException`() {
        stubNetworkOk()
        val rootFile = newSmbFileMock()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { factory.create("smb://host/", any()) } returns rootFile
        every { rootFile.exists() } answers {
            started.countDown()
            release.await(10, TimeUnit.SECONDS)
            throw IOException("simulated transport teardown")
        }

        val terminal = awaitTerminalAfterCancel(started, release) { client.connect(source, "/") }

        assertCancelled(terminal)
    }

    @Test
    fun `downloadTo cancelled mid-read terminates with CancellationException not SyncException`() {
        val connectFile = newSmbFileMock()
        connectToMedia(connectFile)
        val dlFile = newSmbFileMock()
        val input = mockk<java.io.InputStream>()
        every { factory.create("smb://host/media/v.mp4", any()) } returns dlFile
        every { dlFile.exists() } returns true
        every { dlFile.length() } returns 8L
        every { dlFile.getInputStream() } returns input
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { input.read(any<ByteArray>()) } answers {
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
    fun `deleteFile cancelled mid-delete terminates with CancellationException not SyncException`() {
        val connectFile = newSmbFileMock()
        connectToMedia(connectFile)
        val delFile = newSmbFileMock()
        every { factory.create("smb://host/media/v.mp4", any()) } returns delFile
        every { delFile.exists() } returns true
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { delFile.delete() } answers {
            started.countDown()
            release.await(10, TimeUnit.SECONDS)
            throw IOException("simulated transport teardown")
        }

        val terminal = awaitTerminalAfterCancel(started, release) { client.deleteFile("v.mp4") }

        assertCancelled(terminal)
    }

    @Test
    fun `listShares cancelled during entry processing does not touch subsequent entries`() {
        val connectFile = newSmbFileMock()
        connectToMedia(connectFile)
        val sharesFile = newSmbFileMock()
        val first = newSmbFileMock()
        val second = newSmbFileMock()
        val secondTouched = AtomicInteger(0)
        every { factory.create("smb://host/", any()) } returns sharesFile
        every { sharesFile.listFiles() } returns arrayOf(first, second)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { first.isDirectory } answers {
            started.countDown()
            release.await(10, TimeUnit.SECONDS)
            throw IOException("simulated transport teardown")
        }
        every { second.isDirectory } answers { secondTouched.incrementAndGet(); true }
        every { second.name } returns "movies"
        every { second.lastModified } returns 0L

        val terminal = awaitTerminalAfterCancel(started, release) { client.listShares() }

        assertCancelled(terminal)
        assertEquals("entry after the cancelled one must not be accessed", 0, secondTouched.get())
    }

    @Test
    fun `listDirectories cancelled during entry processing does not touch subsequent entries`() {
        val connectFile = newSmbFileMock()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val child1 = newSmbFileMock()
        val child2 = newSmbFileMock()
        val child2Touched = AtomicInteger(0)
        stubNetworkOk()
        every { factory.create("smb://host/media/", any()) } returns connectFile
        every { connectFile.exists() } returns true
        every { connectFile.isDirectory() } returns true
        // AND-010: connect() 不再走 listFiles 探测，本 mock 的 listFiles 仅服务于
        // listDirectories() 的目录列举。
        every { connectFile.listFiles() } returns arrayOf(child1, child2)
        runBlocking { client.connect(source, "/media") }
        every { child1.isDirectory } answers {
            started.countDown()
            release.await(10, TimeUnit.SECONDS)
            throw IOException("simulated transport teardown")
        }
        every { child2.isDirectory } answers { child2Touched.incrementAndGet(); true }
        every { child2.name } returns "movies"
        every { child2.length() } returns 0L
        every { child2.lastModified } returns 0L

        val terminal = awaitTerminalAfterCancel(started, release) { client.listDirectories("media") }

        assertCancelled(terminal)
        assertEquals("entry after the cancelled one must not be accessed", 0, child2Touched.get())
    }

    /**
     * R1: listFilesRecursive must carry a cancellation checkpoint at the recursion /
     * entry boundary. jcifs blocking calls are NOT interruptible — the checkpoint only
     * makes cancellation effective at the NEXT entry boundary, skipping the remaining
     * traversal (less wasted work; the in-flight listing still runs to completion).
     *
     * Shape: root lists [child1(dir), child2(dir)]; cancel fires while child1's
     * recursive listFiles blocks, which then completes NORMALLY. The next entry
     * boundary must terminate with CancellationException — child2 must never be
     * touched and no further directory listing may be issued.
     */
    @Test
    fun `listFilesRecursive cancelled mid recursion does not issue the next directory listing`() {
        val connectFile = newSmbFileMock()
        connectToMedia(connectFile)
        val child1 = newSmbFileMock()
        val child2 = newSmbFileMock()
        val child2Touched = AtomicInteger(0)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { connectFile.isDirectory() } returns true
        every { connectFile.listFiles() } returns arrayOf(child1, child2)
        every { child1.isDirectory } returns true
        every { child1.listFiles() } answers {
            started.countDown()
            release.await(10, TimeUnit.SECONDS)
            arrayOf<jcifs.smb.SmbFile>()
        }
        every { child2.isDirectory } answers { child2Touched.incrementAndGet(); true }
        every { child2.listFiles() } returns arrayOf<jcifs.smb.SmbFile>()

        val terminal = awaitTerminalAfterCancel(started, release) { client.listFiles("media") }

        assertCancelled(terminal)
        assertEquals("directory after the cancelled one must not be accessed", 0, child2Touched.get())
        verify(exactly = 0) { child2.listFiles() }
    }

    // ------------------------------------- connect cancellation cleanup (rev-sgpt)

    /** Type A: the jcifs validation call throws CancellationException DIRECTLY. */
    @Test
    fun `connect direct CancellationException clears session and blocks factory IO`() {
        stubNetworkOk()
        val rootFile = newSmbFileMock()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { factory.create("smb://host/", any()) } returns rootFile
        every { rootFile.exists() } answers {
            started.countDown()
            release.await(10, TimeUnit.SECONDS)
            throw CancellationException("cancelled by test")
        }

        val terminal = awaitTerminalAfterCancel(started, release) { client.connect(source, "/") }

        assertCancelled(terminal)
        assertSessionClearedAndNoFurtherFactoryIo()
    }

    /**
     * Type B: the jcifs validation call returns NORMALLY after cancellation;
     * withContext throws prompt CancellationException at its return boundary.
     */
    @Test
    fun `connect normal return after cancel clears session and blocks factory IO`() {
        stubNetworkOk()
        val rootFile = newSmbFileMock()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { factory.create("smb://host/", any()) } returns rootFile
        every { rootFile.exists() } answers {
            started.countDown()
            release.await(10, TimeUnit.SECONDS)
            true
        }

        val terminal = awaitTerminalAfterCancel(started, release) { client.connect(source, "/") }

        assertCancelled(terminal)
        assertSessionClearedAndNoFurtherFactoryIo()
    }

    // ------------------------------------------------------------------ pre/post GUARD

    @Test
    fun `listShares cancelled mid-listFiles terminates with CancellationException`() {
        val connectFile = newSmbFileMock()
        connectToMedia(connectFile)
        val sharesFile = newSmbFileMock()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { factory.create("smb://host/", any()) } returns sharesFile
        every { sharesFile.listFiles() } answers {
            started.countDown()
            release.await(10, TimeUnit.SECONDS)
            throw IOException("simulated transport teardown")
        }

        val terminal = awaitTerminalAfterCancel(started, release) { client.listShares() }

        assertCancelled(terminal)
    }

    @Test
    fun `listDirectories cancelled mid-listFiles terminates with CancellationException`() {
        val connectFile = newSmbFileMock()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        stubNetworkOk()
        every { factory.create("smb://host/media/", any()) } returns connectFile
        every { connectFile.exists() } returns true
        every { connectFile.isDirectory() } returns true
        runBlocking { client.connect(source, "/media") }
        // AND-010: connect 探测不再调用 listFiles —— 唯一一次 listFiles 即被测的列举调用。
        every { connectFile.listFiles() } answers {
            started.countDown()
            release.await(10, TimeUnit.SECONDS)
            throw IOException("simulated transport teardown")
        }

        val terminal = awaitTerminalAfterCancel(started, release) { client.listDirectories("media") }

        assertCancelled(terminal)
    }

    @Test
    fun `listShares uncanceled bad entry is skipped and good entries kept`() {
        val connectFile = newSmbFileMock()
        connectToMedia(connectFile)
        val sharesFile = newSmbFileMock()
        val bad = newSmbFileMock()
        val good = newSmbFileMock()
        every { factory.create("smb://host/", any()) } returns sharesFile
        every { sharesFile.listFiles() } returns arrayOf(bad, good)
        every { bad.isDirectory } throws IOException("simulated bad entry")
        every { good.isDirectory } returns true
        every { good.name } returns "movies"
        every { good.lastModified } returns 0L

        val result = runBlocking { client.listShares() }

        assertEquals(1, result.size)
        assertEquals("movies", result[0].name)
    }

    @Test
    fun `listDirectories uncanceled IOException throws SyncException not empty list`() {
        val connectFile = newSmbFileMock()
        stubNetworkOk()
        every { factory.create("smb://host/media/", any()) } returns connectFile
        every { connectFile.exists() } returns true
        every { connectFile.isDirectory() } returns true
        runBlocking { client.connect(source, "/media") }
        every { connectFile.listFiles() } throws IOException("simulated transport failure")

        // listDirectories 契约：枚举失败 fail-loud —— SyncException 而非空列表。
        val t = runCatching { runBlocking { client.listDirectories("media") } }.exceptionOrNull()

        assertTrue("expected SyncException but was ${t?.javaClass}", t is SyncException)
        assertEquals(SyncErrorCode.UNKNOWN, (t as SyncException).code)
        assertEquals(SyncOperation.BROWSE, (t as SyncException).operation)
    }

    @Test
    fun `downloadTo uncanceled transport failure still classified via SyncException`() {
        val connectFile = newSmbFileMock()
        connectToMedia(connectFile)
        val dlFile = newSmbFileMock()
        every { factory.create("smb://host/media/v.mp4", any()) } returns dlFile
        every { dlFile.exists() } returns true
        every { dlFile.length() } returns 8L
        every { dlFile.getInputStream() } throws IOException("simulated transport failure")

        val t = runCatching {
            runBlocking { client.downloadTo("v.mp4", mockk<OutputStream>(relaxed = true), null) }
        }.exceptionOrNull()

        assertTrue(t is SyncException)
        assertEquals(SyncErrorCode.DOWNLOAD_FAILED, (t as SyncException).code)
    }
}
