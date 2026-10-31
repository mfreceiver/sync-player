package cn.vectory.syncplayer.storage

import cn.vectory.syncplayer.crypto.ChunkedEncryptSink
import cn.vectory.syncplayer.crypto.CryptoScheme
import cn.vectory.syncplayer.crypto.KekProvider
import cn.vectory.syncplayer.crypto.MediaCryptoMaster
import cn.vectory.syncplayer.crypto.RangeDecryptor
import cn.vectory.syncplayer.storage.volume.FileVolume
import cn.vectory.syncplayer.storage.volume.MediaVolume
import cn.vectory.syncplayer.storage.volume.VolumeResolver
import io.mockk.every
import io.mockk.mockk
import java.io.File
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * v2 双卷端到端（外部 CTR_V2）：attempt → CTR sink → finalize（长度核验+原子 rename）→
 * 随机读解密；thumb GCM；拔卷 fail-closed（不误判 NOT_FOUND）；CAS 目标卷路由。
 */
class MediaFileStoreRefTest {

    private lateinit var rootDir: File
    private lateinit var extDir: File
    private lateinit var store: MediaFileStore
    private lateinit var resolver: FakeResolver
    private val datasetId = "ds-test"

    /** 软件 KEK 替身。 */
    private class SoftKek : KekProvider {
        private val kek = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        override fun wrap(mk: javax.crypto.SecretKey): ByteArray {
            val iv = ByteArray(12)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
                .apply { init(Cipher.ENCRYPT_MODE, kek, GCMParameterSpec(128, iv)) }
            return iv + c.doFinal(mk.encoded)
        }

        override fun unwrap(blob: ByteArray): javax.crypto.SecretKey {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
                .apply { init(Cipher.DECRYPT_MODE, kek, GCMParameterSpec(128, blob.copyOfRange(0, 12))) }
            return SecretKeySpec(c.doFinal(blob.copyOfRange(12, blob.size)), "AES")
        }
    }

    private class FakeResolver(
        var internalRoot: File,
        var extRoot: File?,
        var defaultVolume: String = VolumeIds.INTERNAL
    ) : VolumeResolver {
        override val internalVolumeId: String = VolumeIds.INTERNAL
        override fun resolve(volumeId: String): MediaVolume? = try {
            when {
                volumeId == VolumeIds.INTERNAL -> FileVolume(VolumeIds.INTERNAL, internalRoot)
                volumeId == VolumeIds.external("card-1") ->
                    extRoot?.let { FileVolume(volumeId, it) }
                else -> null
            }
        } catch (_: Exception) {
            null
        }

        override fun defaultWriteTarget(): VolumeTarget = VolumeTarget(defaultVolume)
        override fun isAvailable(volumeId: String): Boolean = resolve(volumeId) != null
        override fun registeredVolumeIds(): List<String> = listOf(VolumeIds.INTERNAL)
    }

    @Before
    fun setup() {
        rootDir = File(System.getProperty("java.io.tmpdir"), "media-store-ref-test-${System.nanoTime()}")
        extDir = File(rootDir, "ext")
        extDir.mkdirs()
        val context = mockk<android.content.Context>()
        every { context.filesDir } returns File(rootDir, "files")
        resolver = FakeResolver(File(rootDir, "userdata"), extDir)
        store = MediaFileStore(
            storagePaths = StoragePaths(context),
            ioDispatcher = Dispatchers.Unconfined,
            volumeResolver = resolver,
            cryptoMaster = MediaCryptoMaster(SoftKek(), { null }, { }),
            datasetIdSource = DatasetIdSource { datasetId }
        )
    }

    @After
    fun cleanup() {
        rootDir.deleteRecursively()
    }

    private fun extTarget() = VolumeTarget(VolumeIds.external("card-1"))

    @Test
    fun externalAttemptFullFlow() = runTest {
        val plain = ByteArray(300_003) { (it % 251).toByte() }
        val attempt = store.newAttempt(77L, extTarget(), plain.size.toLong())
        assertEquals(StorageFormat.CTR_V2, attempt.ref.format)
        assertEquals(datasetId, attempt.ref.datasetId)
        assertEquals(8, attempt.ref.iv!!.size)

        val sink = store.openWriteSink(attempt)
        assertTrue(sink is ChunkedEncryptSink)
        sink.use { it.write(plain) } // close = 长度校验通过
        assertTrue(store.finalizeRef(attempt))

        // final 存在于预期外部路径，密文 ≠ 明文
        val rel = MediaPaths.externalMediaRelPath(datasetId, 77L, attempt.ref.generation!!)
        val cipherFile = File(extDir, rel)
        assertTrue(cipherFile.isFile)
        assertEquals(plain.size.toLong(), cipherFile.length())
        assertFalse(cipherFile.readBytes().contentEquals(plain))
        // tmp 已被 rename 消费
        assertFalse(File(extDir, rel + MediaPaths.TMP_SUFFIX).exists())

        // 随机读解密 == 明文
        val handle = store.openRead(attempt.ref)
        handle.use {
            val out = RangeDecryptor(
                store.contentKey(attempt.ref), attempt.ref.iv!!, attempt.ref.plainSize
            ).readRange(it, 123_457L, 1000)
            assertArrayEquals(plain.copyOfRange(123_457, 124_457), out)
        }

        assertEquals(MediaProbeState.VALID, store.probeFinalized(attempt.ref, plain.size.toLong()))
        store.verifyReadable(attempt.ref)
    }

    @Test
    fun closeLengthMismatchCannotFinalize() = runTest {
        val attempt = store.newAttempt(78L, extTarget(), 100L)
        val sink = store.openWriteSink(attempt)
        sink.write(ByteArray(40))
        try {
            sink.close()
            throw AssertionError("expected IOException on short close")
        } catch (_: java.io.IOException) {
        }
        assertFalse(store.finalizeRef(attempt)) // rename 未发生
    }

    @Test
    fun cancelDeletesTmpOnly() = runTest {
        val attempt = store.newAttempt(79L, extTarget(), 3L)
        store.openWriteSink(attempt).use { it.write(ByteArray(3)) }
        val tmpRel = MediaPaths.externalMediaRelPath(datasetId, 79L, attempt.ref.generation!!) + ".tmp"
        assertTrue(File(extDir, tmpRel).exists())
        store.cancelRef(attempt)
        assertFalse(File(extDir, tmpRel).exists())
    }

    @Test
    fun probePendingTmpStates() = runTest {
        val attempt = store.newAttempt(80L, extTarget(), 10L)
        assertEquals(TmpProbeState.ABSENT, store.probePendingTmp(attempt))
        store.openWriteSink(attempt).use { it.write(ByteArray(10)) }
        assertEquals(TmpProbeState.PRESENT, store.probePendingTmp(attempt))
        // 拔卡：卷不可用 ⇒ UNKNOWN（绝不误判 ABSENT→误导调用方删 final）
        resolver.extRoot = null
        assertEquals(TmpProbeState.UNKNOWN, store.probePendingTmp(attempt))
    }

    @Test
    fun pulledCardOperationsFailClosed() = runTest {
        val attempt = store.newAttempt(81L, extTarget(), 5L)
        store.openWriteSink(attempt).use { it.write(ByteArray(5)) }
        store.finalizeRef(attempt)
        resolver.extRoot = null
        // 拔卡：读/写/finalize 全部结构化失败，绝不降级为「文件不存在」
        try {
            store.openRead(attempt.ref)
            throw AssertionError("expected StorageAccessFailure")
        } catch (e: StorageAccessFailure) {
            assertEquals(StorageAccessFailure.Reason.VOLUME_UNAVAILABLE, e.reason)
        }
        try {
            store.openWriteSink(attempt)
            throw AssertionError("expected StorageAccessFailure")
        } catch (e: StorageAccessFailure) {
            assertEquals(StorageAccessFailure.Reason.VOLUME_UNAVAILABLE, e.reason)
        }
        assertEquals(SpaceResult.Unavailable(SpaceResult.Unavailable.Reason.VOLUME_MISSING), store.space(extTarget()))
    }

    @Test
    fun thumbGcmRoundtripOnExternal() = runTest {
        val attempt = store.newAttempt(82L, extTarget(), 5L)
        store.openWriteSink(attempt).use { it.write(ByteArray(5)) }
        store.finalizeRef(attempt)
        val bytes = ByteArray(2048) { (it % 7).toByte() }
        store.writeThumb(attempt.ref, bytes)
        assertArrayEquals(bytes, store.readThumbBytes(attempt.ref))
        // 篡改密文 ⇒ 认证失败 ⇒ null（不输出部分明文）
        val rel = MediaPaths.externalThumbRelPath(datasetId, 82L, attempt.ref.generation!!)
        val f = File(extDir, rel)
        val tampered = f.readBytes().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        f.writeBytes(tampered)
        assertNull(store.readThumbBytes(attempt.ref))
        assertTrue(store.deleteThumb(attempt.ref))
        assertNull(store.readThumbBytes(attempt.ref))
    }

    @Test
    fun selectWriteTargetPrefersExistingVolume() = runTest {
        val existing = StorageRef(83L, VolumeIds.external("card-1"), datasetId, "g", StorageFormat.CTR_V2, ByteArray(8), 1L)
        assertEquals(VolumeIds.external("card-1"), store.selectWriteTarget(existing).volumeId)
        resolver.defaultVolume = VolumeIds.external("card-1")
        assertEquals(VolumeIds.external("card-1"), store.selectWriteTarget(null).volumeId)
        assertEquals(VolumeIds.INTERNAL, FakeResolver(File(rootDir, "u"), null).defaultWriteTarget().volumeId)
    }

    @Test
    fun internalAttemptStaysPlainAndReadable() = runTest {
        val plain = byteArrayOf(5, 6, 7, 8)
        val attempt = store.newAttempt(84L, VolumeTarget(VolumeIds.INTERNAL), 4L)
        assertEquals(StorageFormat.PLAIN, attempt.ref.format)
        store.openWriteSink(attempt).use { it.write(plain) }
        assertTrue(store.finalizeRef(attempt))
        store.openRead(attempt.ref).use { h ->
            val buf = ByteArray(4)
            h.seek(0)
            assertEquals(4, h.read(buf, 0, 4))
            assertArrayEquals(plain, buf)
        }
    }

    @Test
    fun spaceReportsExternalVolume() = runTest {
        val r = store.space(extTarget())
        assertTrue(r is SpaceResult.Available)
        assertTrue((r as SpaceResult.Available).bytes > 0)
        val missing = store.space(VolumeTarget(VolumeIds.external("nope")))
        assertEquals(SpaceResult.Unavailable(SpaceResult.Unavailable.Reason.VOLUME_MISSING), missing)
    }

    @Test
    fun externalAttemptsGetFreshGenerationAndIv() = runTest {
        val a1 = store.newAttempt(85L, extTarget(), 1L)
        val a2 = store.newAttempt(85L, extTarget(), 1L)
        assertNotNull(a1.ref.generation)
        assertFalse(a1.ref.generation == a2.ref.generation)
        assertFalse(a1.ref.iv!!.contentEquals(a2.ref.iv))
    }
}
