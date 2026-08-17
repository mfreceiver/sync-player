package cn.vectory.syncplayer.sync.sftp

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import net.schmizz.sshj.common.SecurityUtils
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.util.Properties
import kotlin.io.path.createTempDirectory

/**
 * TOFU behavior tests for [SyncPlayerHostKeyVerifier].
 *
 * Verifies the three trust paths:
 * 1. First connection: fingerprint accepted and persisted to known_hosts.properties.
 * 2. Known host: matching fingerprint passes.
 * 3. Fingerprint mismatch: rejected (fail-closed).
 */
class SyncPlayerHostKeyVerifierTest {

    private val context = mockk<Context>(relaxed = true)
    private lateinit var filesDir: File

    @Before
    fun setUp() {
        filesDir = createTempDirectory(prefix = "hostkey-test-").toFile()
        every { context.filesDir } returns filesDir
    }

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
    }

    private fun rsaKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private fun knownHostsProps(): Properties =
        Properties().apply { File(filesDir, "known_hosts.properties").inputStream().use { load(it) } }

    /**
     * 用非空目录占位 known_hosts.properties，使 savePropsLocked 的 rename 必然失败。
     * POSIX 语义：文件 rename 到**非空**目录返回 EISDIR（Java 映射为 false）；
     * 空目录会被 rename 文件覆盖（实证：File.renameTo 文件->空目录 == true）。
     * tmp 文件会真实创建后由失败路径清理——可移植、无需宿主权限 hack。
     */
    private fun blockKnownHostsWithDirectory(): File =
        File(filesDir, "known_hosts.properties").apply {
            mkdirs()
            File(this, ".keep").createNewFile()
        }

    @Test
    fun `save failure leaves no temp file and no in-memory trust`() {
        blockKnownHostsWithDirectory()
        val verifier = SyncPlayerHostKeyVerifier(context)
        val key = rsaKeyPair().public

        assertThrows(IOException::class.java) { verifier.verify("example.com", 22, key) }

        // 失败路径必须清掉已创建的 tmp 文件，不留 .tmp 孤儿
        val tmpResidue = filesDir.listFiles()?.filter { it.name.endsWith(".tmp") }.orEmpty()
        assertTrue("no .tmp residue expected after failed save, got $tmpResidue", tmpResidue.isEmpty())

        // 同一实例再 verify 必须仍走首连分支（保存失败不建立信任）：
        // 再次尝试保存并再次失败——若内存残留指纹，此调用会直接返回 true。
        assertThrows(IOException::class.java) { verifier.verify("example.com", 22, key) }
    }

    @Test
    fun `failed first save rolls back in-memory fingerprint and retry re-persists`() {
        blockKnownHostsWithDirectory()
        val verifier = SyncPlayerHostKeyVerifier(context)
        val key = rsaKeyPair().public

        assertThrows(IOException::class.java) { verifier.verify("example.com", 22, key) }

        // 移除占位目录（非空），解除 rename 障碍
        val blocker = File(filesDir, "known_hosts.properties")
        assertTrue("blocker removal should succeed", blocker.deleteRecursively())

        // 同实例重试：必须重新尝试保存（内存无残留指纹可匹配），成功落盘
        assertTrue(verifier.verify("example.com", 22, key))

        val hostsFile = File(filesDir, "known_hosts.properties")
        assertTrue("retry should persist known_hosts.properties, got dir=${
            if (hostsFile.isDirectory) "still blocked" else if (!hostsFile.exists()) "missing" else "file"
        }", hostsFile.isFile)
        assertEquals(SecurityUtils.getFingerprint(key), knownHostsProps().getProperty("example.com:22"))
    }

    @Test
    fun `first connection accepts host and persists fingerprint`() {
        val verifier = SyncPlayerHostKeyVerifier(context)
        val key = rsaKeyPair().public

        assertTrue(verifier.verify("example.com", 22, key))

        val hostsFile = File(filesDir, "known_hosts.properties")
        assertTrue("known_hosts.properties should be written on first use", hostsFile.exists())
        assertEquals(SecurityUtils.getFingerprint(key), knownHostsProps().getProperty("example.com:22"))
    }

    @Test
    fun `known host with matching fingerprint passes`() {
        val key = rsaKeyPair().public
        SyncPlayerHostKeyVerifier(context).verify("example.com", 22, key)

        // Fresh instance reloads the persisted fingerprint from disk — must still pass.
        val fresh = SyncPlayerHostKeyVerifier(context)

        assertTrue(fresh.verify("example.com", 22, key))
    }

    @Test
    fun `fingerprint mismatch is rejected`() {
        SyncPlayerHostKeyVerifier(context).verify("example.com", 22, rsaKeyPair().public)

        val fresh = SyncPlayerHostKeyVerifier(context)

        assertFalse(fresh.verify("example.com", 22, rsaKeyPair().public))
    }
}
