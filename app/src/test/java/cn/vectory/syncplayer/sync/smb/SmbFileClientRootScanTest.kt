package cn.vectory.syncplayer.sync.smb

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import cn.vectory.syncplayer.data.model.SyncProtocol
import cn.vectory.syncplayer.data.model.SyncSource
import cn.vectory.syncplayer.sync.RemoteScanIncompleteException
import cn.vectory.syncplayer.sync.SyncErrorCode
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BUG-006: scanning the SMB root (`/`) must fail closed with SCAN_INCOMPLETE instead of
 * returning a "successful" snapshot.
 *
 * Two root-scan poison channels are closed here:
 *  - entry rejection: with an empty share the URL model cannot express any entry's
 *    relative path (`smb://host//` has no share segment), so any output — an empty list
 *    (E8 empty-snapshot hole: zero entries or only empty directories) or host-prefixed
 *    garbage paths — would make dryRun derive DELETE_LOCAL for every local video;
 *  - anchored extraction: leaf URLs must start with the constructed scan-root URL
 *    (`smb://host/share/base/`); any structural surprise (foreign host, missing marker)
 *    must throw instead of silently misattributing or falling back to a bare filename.
 *
 * Browsing root is unaffected: `listDirectories("/")` goes through `listShares()`,
 * never through `listFiles()`.
 *
 * Asserts exception types, enum codes, and relative paths only — never message text.
 */
class SmbFileClientRootScanTest {

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

    private fun newSmbFileMock(): jcifs.smb.SmbFile = mockk(relaxed = true)

    private fun stubRootConnect() {
        stubNetworkOk()
        val rootProbe = newSmbFileMock()
        every { factory.create("smb://host/", any()) } returns rootProbe
        every { rootProbe.exists() } returns true
    }

    private suspend fun connectRoot() {
        client.connect(source, "/")
    }

    private fun assertScanIncomplete(e: Throwable?) {
        assertTrue(
            "expected RemoteScanIncompleteException but was ${e?.javaClass}",
            e is RemoteScanIncompleteException
        )
        assertEquals(SyncErrorCode.SCAN_INCOMPLETE, (e as RemoteScanIncompleteException).code)
    }

    // ------------------------------------------------------------- root scan (entry rejection)

    @Test
    fun `root scan with files throws RemoteScanIncompleteException with SCAN_INCOMPLETE`() = runBlocking {
        stubRootConnect()
        val rootDir = newSmbFileMock()
        every { factory.create("smb://host//", any()) } returns rootDir
        every { rootDir.exists() } returns true
        every { rootDir.isDirectory } returns true
        val shareDir = newSmbFileMock()
        every { shareDir.url.toString() } returns "smb://host/media/"
        every { shareDir.isDirectory } returns true
        val leaf = newSmbFileMock()
        every { leaf.url.toString() } returns "smb://host/media/f1.mp4"
        every { leaf.isDirectory } returns false
        every { leaf.length() } returns 1L
        every { leaf.lastModified } returns 0L
        every { shareDir.listFiles() } returns arrayOf(leaf)
        every { rootDir.listFiles() } returns arrayOf(shareDir)

        connectRoot()
        val e = runCatching { client.listFiles("/") }.exceptionOrNull()

        assertScanIncomplete(e)
    }

    @Test
    fun `root scan with empty enumeration throws instead of returning empty snapshot`() = runBlocking {
        stubRootConnect()
        val rootDir = newSmbFileMock()
        every { factory.create("smb://host//", any()) } returns rootDir
        every { rootDir.exists() } returns true
        every { rootDir.isDirectory } returns true
        every { rootDir.listFiles() } returns arrayOf()

        connectRoot()
        val e = runCatching { client.listFiles("/") }.exceptionOrNull()

        assertScanIncomplete(e)
    }

    @Test
    fun `root scan with only empty directories throws instead of returning empty snapshot`() = runBlocking {
        stubRootConnect()
        val rootDir = newSmbFileMock()
        every { factory.create("smb://host//", any()) } returns rootDir
        every { rootDir.exists() } returns true
        every { rootDir.isDirectory } returns true
        val emptyShareDir = newSmbFileMock()
        every { emptyShareDir.url.toString() } returns "smb://host/media/"
        every { emptyShareDir.isDirectory } returns true
        every { emptyShareDir.listFiles() } returns arrayOf()
        every { rootDir.listFiles() } returns arrayOf(emptyShareDir)

        connectRoot()
        val e = runCatching { client.listFiles("/") }.exceptionOrNull()

        assertScanIncomplete(e)
    }

    // ------------------------------------------------------------- normal share (regression lock)

    @Test
    fun `normal share scan returns host-free relative paths`() = runBlocking {
        stubNetworkOk()
        val dir = newSmbFileMock()
        // One factory URL serves both the connect probe and the listFiles directory object.
        every { factory.create("smb://host/media/movies/", any()) } returns dir
        every { dir.exists() } returns true
        every { dir.isDirectory } returns true
        val leaf = newSmbFileMock()
        every { leaf.url.toString() } returns "smb://host/media/movies/f1.mp4"
        every { leaf.isDirectory } returns false
        every { leaf.length() } returns 1L
        every { leaf.lastModified } returns 0L
        every { dir.listFiles() } returns arrayOf(leaf)
        client.connect(source, "/media/movies")

        val result = client.listFiles("media/movies")

        assertEquals(listOf("f1.mp4"), result.map { it.path })
    }

    // ------------------------------------------------------------- anchored extraction

    @Test
    fun `url outside scan root containing marker substring throws instead of silent misattribution`() = runBlocking {
        stubNetworkOk()
        val dir = newSmbFileMock()
        every { factory.create("smb://host/media/movies/", any()) } returns dir
        every { dir.exists() } returns true
        every { dir.isDirectory } returns true
        val leaf = newSmbFileMock()
        every { leaf.url.toString() } returns "smb://other-host/media/movies/f1.mp4"
        every { leaf.isDirectory } returns false
        every { dir.listFiles() } returns arrayOf(leaf)
        client.connect(source, "/media/movies")

        val e = runCatching { client.listFiles("media/movies") }.exceptionOrNull()

        assertScanIncomplete(e)
    }

    @Test
    fun `url lacking marker entirely throws instead of filename fallback`() = runBlocking {
        stubNetworkOk()
        val dir = newSmbFileMock()
        every { factory.create("smb://host/media/movies/", any()) } returns dir
        every { dir.exists() } returns true
        every { dir.isDirectory } returns true
        val leaf = newSmbFileMock()
        every { leaf.url.toString() } returns "smb://host/media/series/f1.mp4"
        every { leaf.isDirectory } returns false
        every { dir.listFiles() } returns arrayOf(leaf)
        client.connect(source, "/media/movies")

        val e = runCatching { client.listFiles("media/movies") }.exceptionOrNull()

        assertScanIncomplete(e)
    }
}
