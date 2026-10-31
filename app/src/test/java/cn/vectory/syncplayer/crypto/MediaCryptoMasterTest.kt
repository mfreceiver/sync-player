package cn.vectory.syncplayer.crypto

import java.io.ByteArrayOutputStream
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCryptoMasterTest {

    /** 软件 KEK 替身（固定 AES key + GCM），布局与 KeystoreKekProvider 相同：iv(12)‖GCM。 */
    private class FakeKek(var broken: Boolean = false) : KekProvider {
        private val kek: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        override fun wrap(mk: SecretKey): ByteArray {
            val iv = ByteArray(12)
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, kek, GCMParameterSpec(128, iv))
            }
            return iv + c.doFinal(mk.encoded)
        }

        override fun unwrap(blob: ByteArray): SecretKey {
            if (broken) throw java.security.GeneralSecurityException("kek unavailable")
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, kek, GCMParameterSpec(128, blob.copyOfRange(0, 12)))
            }
            return SecretKeySpec(c.doFinal(blob.copyOfRange(12, blob.size)), "AES")
        }
    }

    @Test
    fun firstUseGeneratesAndPersistsSecondInstanceReuses() {
        var stored: ByteArray? = null
        val kek = FakeKek() // 同一 KEK 实例（真实环境为同一 Keystore 条目）
        val master = MediaCryptoMaster(kek, { stored }, { stored = it })
        val key1 = master.masterKey()
        assertEquals(32, key1.encoded.size)
        val master2 = MediaCryptoMaster(kek, { stored }, { stored = it })
        assertArrayEquals(key1.encoded, master2.masterKey().encoded)
    }

    @Test
    fun keystoreFailureThrowsNoSilentRekey() {
        var stored: ByteArray? = null
        val master = MediaCryptoMaster(FakeKek(), { stored }, { stored = it })
        val key1 = master.masterKey()

        // Keystore 失效：既有 blob 解不开 ⇒ 抛 MediaKeyUnavailableException，绝不换新钥。
        val brokenKek = FakeKek().apply { broken = true }
        val masterBroken = MediaCryptoMaster(brokenKek, { stored }, { stored = it })
        assertThrows(MediaKeyUnavailableException::class.java) { masterBroken.masterKey() }
        // blob 未被覆盖（仍是原 wrap），恢复后仍能解出同一把钥
        assertArrayEquals(key1.encoded, master.masterKey().encoded)
    }

    @Test
    fun corruptBlobThrows() {
        val stored = ByteArray(64) { it.toByte() }
        val master = MediaCryptoMaster(FakeKek(), { stored }, { })
        assertThrows(MediaKeyUnavailableException::class.java) { master.masterKey() }
    }

    @Test
    fun storeFailureThrowsKeyUnavailable() {
        val master = MediaCryptoMaster(FakeKek(), { null }, { throw java.io.IOException("disk") })
        assertThrows(MediaKeyUnavailableException::class.java) { master.masterKey() }
    }

    @Test
    fun storeFailureDoesNotCacheKey() {
        // P1-10：storeBlob 失败后，未持久化的 key 绝不能被缓存继续使用；存储恢复后
        // 重新生成并成功持久化，且持久化结果可被下一个实例解出同一把钥。
        var stored: ByteArray? = null
        var failing = true
        val kek = FakeKek() // 同一 KEK 实例（真实环境为同一 Keystore 条目）
        val master = MediaCryptoMaster(kek, { stored }, {
            if (failing) throw java.io.IOException("persist rejected") else stored = it
        })
        assertThrows(MediaKeyUnavailableException::class.java) { master.masterKey() }
        // 失败路径未落盘任何 blob
        assertEquals(null, stored)
        // 同一实例存储恢复后重试成功（未缓存失败 key——重试生成→持久化→缓存）
        failing = false
        val key = master.masterKey()
        assertEquals(32, key.encoded.size)
        assertNotNull(stored)
        // 新实例从持久化 blob 解出同一把钥（重启不换钥）
        assertArrayEquals(key.encoded, MediaCryptoMaster(kek, { stored }, { }).masterKey().encoded)
    }

    @Test
    fun cachingAvoidsRepeatedUnwrap() {
        var loads = 0
        var stored: ByteArray? = null
        val master = MediaCryptoMaster(FakeKek(), { loads++; stored }, { stored = it })
        master.masterKey()
        master.masterKey()
        assertEquals(1, loads)
        assertTrue(master.masterKey().encoded.size == 32)
    }
}
