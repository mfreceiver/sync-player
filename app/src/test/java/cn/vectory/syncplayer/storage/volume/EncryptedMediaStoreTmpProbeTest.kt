package cn.vectory.syncplayer.storage.volume

import cn.vectory.syncplayer.crypto.CryptoScheme
import cn.vectory.syncplayer.sync.SyncErrorCode
import cn.vectory.syncplayer.sync.SyncException
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.spec.SecretKeySpec

/**
 * BUG-007 存储层组（计划 S1–S4）：[EncryptedMediaStore.probePendingTmp] 三态契约本体，
 * 以及 finalize 提交/取消边界前提链的存储层支点——
 *  - S2：openEncryptSink 包装失败自删 tmp（「FINALIZE_UNCERTAIN ⇒ tmp 必曾存在」的支点）；
 *  - S3：openWrite 失败从不创建 tmp（NOT_FINALIZED 语境 tmp 缺失的一类来源）；
 *  - S4：finalize 是薄封装，返回值直通（探测不得干扰 finalize 本体）。
 *
 * 断言枚举/调用记录/异常类型——不断言用户文案。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EncryptedMediaStoreTmpProbeTest {

    private val master = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES")

    /** 记录 delete/openWrite 调用与 existsStrict 结果的 fake volume（纯内存）。 */
    private class RecordingVolume : MediaVolume {
        override val volumeId: String = "fake-vol"
        val openWritePaths = mutableListOf<String>()
        val deletedPaths = mutableListOf<String>()
        var openWriteError: IOException? = null
        var existsStrictResult: Boolean = false
        var existsStrictError: IOException? = null
        var renameResult: Boolean = true

        override suspend fun openWrite(relPath: String): OutputStream {
            openWritePaths.add(relPath)
            openWriteError?.let { throw it }
            return java.io.ByteArrayOutputStream()
        }

        override suspend fun openRead(relPath: String): SeekableReadHandle =
            throw IOException("not needed in this fake")

        override suspend fun list(dirRelPath: String): List<String> = emptyList()

        override suspend fun delete(relPath: String): Boolean {
            deletedPaths.add(relPath)
            return true
        }

        override suspend fun exists(relPath: String): Boolean = existsStrictResult

        override suspend fun mkdirs(dirRelPath: String) {}

        override suspend fun rename(from: String, to: String): Boolean = renameResult

        override fun usableSpace(): Long = 0L

        override suspend fun existsStrict(relPath: String): Boolean {
            existsStrictError?.let { throw it }
            return existsStrictResult
        }
    }

    private fun newStore(volume: MediaVolume): EncryptedMediaStore {
        val provider = mockk<MediaVolumeProvider>()
        coEvery { provider.current() } returns volume
        return EncryptedMediaStore(provider) { master }
    }

    private fun tmpPath(id: Long) = MediaPaths.mediaRelPath(id) + MediaPaths.TMP_SUFFIX

    // S1: 三态契约本体
    @Test
    fun `probePendingTmp maps volume states to tri-state`() = runTest {
        val volume = RecordingVolume()
        val store = newStore(volume)

        volume.existsStrictResult = true
        assertEquals(TmpProbeState.PRESENT, store.probePendingTmp(42L))

        volume.existsStrictResult = false
        assertEquals(TmpProbeState.ABSENT, store.probePendingTmp(42L))

        volume.existsStrictError = IOException("simulated provider anomaly")
        assertEquals(
            "query failure must converge to UNKNOWN, never ABSENT",
            TmpProbeState.UNKNOWN,
            store.probePendingTmp(42L)
        )
    }

    // S2（前提链证据，v3.1 修正输入）：合法非负但超过实现上限的 plainLength 在
    // encodeHeader（ChunkedEncryptSink 属性初始化期）被拒 → openEncryptSink 的包装守卫自删 tmp。
    @Test
    fun `openEncryptSink wrapper failure self-deletes tmp`() = runTest {
        val volume = RecordingVolume()
        val store = newStore(volume)

        val overCapPlain = (Int.MAX_VALUE.toLong() + 1) * CryptoScheme.CHUNK_SIZE
        val thrown = runCatching {
            store.openEncryptSink(42L, CryptoScheme.newIv(), overCapPlain)
        }.exceptionOrNull()

        assertNotNull("over-cap plainLength must be rejected at sink construction", thrown)
        assertTrue(
            "wrapper failure must self-delete tmp, deleted=${volume.deletedPaths}",
            volume.deletedPaths.contains(tmpPath(42L))
        )
    }

    // S3（前提链证据）：openWrite 抛 IOException → tmp 从未创建、delete(tmp) 未被调。
    @Test
    fun `openWrite failure never creates tmp`() = runTest {
        val volume = RecordingVolume()
        volume.openWriteError = IOException("simulated openWrite failure")
        val store = newStore(volume)

        val thrown = runCatching {
            store.openEncryptSink(42L, CryptoScheme.newIv(), 1024L)
        }.exceptionOrNull()

        assertNotNull(thrown)
        assertTrue("openWrite failure must map to SyncException(STORAGE_ERROR)", thrown is SyncException)
        assertEquals(SyncErrorCode.STORAGE_ERROR, (thrown as SyncException).code)
        assertTrue(
            "tmp never existed — no cleanup delete may fire, deleted=${volume.deletedPaths}",
            volume.deletedPaths.isEmpty()
        )
    }

    // S4: finalize 是薄封装，返回值直通。
    @Test
    fun `finalize false and true are passed through unchanged`() = runTest {
        val volume = RecordingVolume()
        val store = newStore(volume)

        volume.renameResult = true
        assertTrue(store.finalize(42L))

        volume.renameResult = false
        assertTrue("finalize must pass the volume rename result through unchanged", !store.finalize(42L))
    }
}
