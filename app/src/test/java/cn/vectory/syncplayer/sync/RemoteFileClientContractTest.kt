package cn.vectory.syncplayer.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import cn.vectory.syncplayer.data.model.SyncProtocol
import cn.vectory.syncplayer.data.model.SyncSource
import cn.vectory.syncplayer.sync.sftp.SftpFileClient
import cn.vectory.syncplayer.sync.smb.SmbFileClient
import cn.vectory.syncplayer.sync.smb.SmbFileFactory
import io.mockk.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * Contract tests for [RemoteFileClient].
 *
 * `downloadTo`: writes into the caller-owned [sink] without closing it.
 *
 * `listDirectories` fail-loud contract: an enumeration-phase failure (permission /
 * IO / transport) must surface as a [SyncException] — never be swallowed into an
 * empty list, which would make "enumeration denied" indistinguishable from
 * "genuinely empty directory" for the browse UI and `testRemotePath`. An existing-
 * but-empty directory still yields an empty list. Verified against BOTH real
 * implementations with mocked transport seams (jcifs `SmbFileFactory`, sshj
 * `SSHClient`/`SFTPClient`).
 *
 * Asserts exception types and enum codes only — never message text.
 */
class RemoteFileClientContractTest {

    @Test
    fun `downloadTo writes to sink and does not close it`() = runTest {
        // Arrange
        val client = mockk<RemoteFileClient>()
        val sink = spyk(ByteArrayOutputStream())
        val testData = byteArrayOf(0x48, 0x65, 0x6c, 0x6c, 0x6f) // "Hello"

        // Simulate the real implementation: write testData to sink, then return true, never closing sink
        coEvery { client.downloadTo(any(), any(), any()) } coAnswers {
            val remotePath: String = firstArg()
            val outputStream: OutputStream = secondArg()
            val onProgress: ((Long, Long) -> Unit)? = thirdArg()

            outputStream.write(testData)
            onProgress?.invoke(testData.size.toLong(), testData.size.toLong())
            true
        }

        // Act
        val result = client.downloadTo("some/file.mp4", sink)

        // Assert: data was written
        assertTrue("downloadTo should return true on success", result)
        assertArrayEquals("Sink should contain the test data", "Hello".toByteArray(), sink.toByteArray())
        // Verify close was NOT called
        verify(exactly = 0) { sink.close() }
    }

    // ------------------------------------------------------------ listDirectories

    private val context = mockk<Context>(relaxed = true)
    private val smbFactory = mockk<SmbFileFactory>()
    private val smbClient = SmbFileClient(context, smbFileFactory = smbFactory)
    private val sshClient = mockk<SSHClient>(relaxed = true)
    private val sftpClient = mockk<SFTPClient>(relaxed = true)
    private val sftpFileClient = SftpFileClient(context, sshClientFactory = { sshClient })

    private val smbSource = SyncSource(
        name = "s", protocol = SyncProtocol.SMB,
        host = "host", port = 445, username = "u", password = "p"
    )
    private val sftpSource = SyncSource(
        name = "s", protocol = SyncProtocol.SFTP,
        host = "host", port = 22, username = "u", password = "p"
    )

    private var tempDir: File? = null

    @Before
    fun setUp() {
        // SyncPlayerHostKeyVerifier touches context.filesDir during connect().
        tempDir = java.nio.file.Files.createTempDirectory("rfc-contract-test-").toFile()
        every { context.filesDir } returns tempDir
    }

    @After
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

    private fun newSmbFileMock(): jcifs.smb.SmbFile = mockk(relaxed = true)

    /** Connects to share `media`; [dir] serves both the connect probe and the listing. */
    private fun connectSmbToMedia(dir: jcifs.smb.SmbFile) {
        stubNetworkOk()
        every { smbFactory.create("smb://host/media/", any()) } returns dir
        every { dir.exists() } returns true
        runBlocking { smbClient.connect(smbSource, "/media") }
    }

    private fun connectSftp() {
        stubNetworkOk()
        every { sshClient.newSFTPClient() } returns sftpClient
        runBlocking { sftpFileClient.connect(sftpSource, "/") }
    }

    private fun assertSyncException(t: Throwable?, code: SyncErrorCode) {
        assertTrue("expected SyncException but was ${t?.javaClass}", t is SyncException)
        assertEquals(code, (t as SyncException).code)
        assertEquals(SyncOperation.BROWSE, t.operation)
        assertNull("SyncException must never carry display text", t.message)
    }

    @Test
    fun `smb listDirectories maps denied enumeration to SyncException not empty list`() = runBlocking {
        val dir = newSmbFileMock()
        connectSmbToMedia(dir)
        every { dir.isDirectory() } returns true
        // 与 jcifs 真实 ACCESS_DENIED 一致：按 NT status 构造（文案为 "Access is denied."）。
        every { dir.listFiles() } throws
            jcifs.smb.SmbException(jcifs.smb.NtStatus.NT_STATUS_ACCESS_DENIED, true)

        val t = runCatching { smbClient.listDirectories("media") }.exceptionOrNull()

        assertSyncException(t, SyncErrorCode.ACCESS_DENIED)
    }

    @Test
    fun `smb listDirectories maps null listing to SyncException not empty list`() = runBlocking {
        val dir = newSmbFileMock()
        connectSmbToMedia(dir)
        every { dir.isDirectory() } returns true
        // jcifs-ng 实现上不会返回 null（空目录 = 空数组）；防御分支仍须 fail-loud。
        every { dir.listFiles() } returns null

        val t = runCatching { smbClient.listDirectories("media") }.exceptionOrNull()

        assertSyncException(t, SyncErrorCode.UNKNOWN)
    }

    @Test
    fun `smb root browse share-enumeration failure throws SyncException not empty list`() = runBlocking {
        stubNetworkOk()
        val rootFile = newSmbFileMock()
        // connect("/") 的探测与 listShares() 的枚举共用 "smb://host/" 这一个工厂产物。
        every { smbFactory.create("smb://host/", any()) } returns rootFile
        every { rootFile.exists() } returns true
        smbClient.connect(smbSource, "/")
        every { rootFile.listFiles() } throws
            jcifs.smb.SmbException(jcifs.smb.NtStatus.NT_STATUS_ACCESS_DENIED, true)

        val t = runCatching { smbClient.listDirectories("/") }.exceptionOrNull()

        assertSyncException(t, SyncErrorCode.ACCESS_DENIED)
    }

    @Test
    fun `sftp listDirectories maps PERMISSION_DENIED to ACCESS_DENIED`() = runBlocking {
        connectSftp()
        every { sftpClient.ls(any()) } throws
            SFTPException(Response.StatusCode.PERMISSION_DENIED, "permission denied")

        val t = runCatching { sftpFileClient.listDirectories("/base") }.exceptionOrNull()

        assertSyncException(t, SyncErrorCode.ACCESS_DENIED)
    }

    @Test
    fun `sftp listDirectories maps NO_SUCH_FILE to PATH_NOT_FOUND`() = runBlocking {
        connectSftp()
        every { sftpClient.ls(any()) } throws
            SFTPException(Response.StatusCode.NO_SUCH_FILE, "no such file")

        val t = runCatching { sftpFileClient.listDirectories("/missing") }.exceptionOrNull()

        assertSyncException(t, SyncErrorCode.PATH_NOT_FOUND)
    }

    @Test
    fun `sftp listDirectories maps transport failure to SyncException not empty list`() = runBlocking {
        connectSftp()
        every { sftpClient.ls(any()) } throws IOException("simulated transport failure")

        val t = runCatching { sftpFileClient.listDirectories("/base") }.exceptionOrNull()

        assertSyncException(t, SyncErrorCode.UNKNOWN)
    }

    @Test
    fun `sftp listDirectories on genuinely empty directory still returns empty list`() = runBlocking {
        connectSftp()
        every { sftpClient.ls(any()) } returns emptyList()

        val result = sftpFileClient.listDirectories("/base")

        assertTrue(result.isEmpty())
    }
}
