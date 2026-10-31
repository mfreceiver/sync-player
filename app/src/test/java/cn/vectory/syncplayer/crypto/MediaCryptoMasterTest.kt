package cn.vectory.syncplayer.crypto

import java.io.ByteArrayOutputStream
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
