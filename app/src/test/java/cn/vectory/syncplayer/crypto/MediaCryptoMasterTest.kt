package cn.vectory.syncplayer.crypto

import org.junit.Assert.*
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class MediaCryptoMasterTest {
    /** 纯软件 KEK 替身，验证 wrap/unwrap 逻辑（绕过 AndroidKeyStore）。 */
    private class FakeKek(val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()) : KekProvider {
        override fun wrap(mk: SecretKey): ByteArray {
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)) }
            return iv + c.doFinal(mk.encoded)
        }
        override fun unwrap(blob: ByteArray): SecretKey {
            val iv = blob.copyOfRange(0, 12); val ct = blob.copyOfRange(12, blob.size)
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }
            return SecretKeySpec(c.doFinal(ct), "AES")
        }
    }

    private class FakeStore(var blob: ByteArray? = null) {
        fun get(): ByteArray? = blob
        fun set(b: ByteArray) { blob = b }
    }

    @Test fun keyStableWithinProcess() {
        val kek = FakeKek(); val store = FakeStore()
        val master = MediaCryptoMaster(kek, { store.get() }, { store.set(it) }).masterKey()
        val again = MediaCryptoMaster(kek, { store.get() }, { store.set(it) }).masterKey()
        assertArrayEquals(master.encoded, again.encoded)
        assertNotNull(store.blob)          // 已持久化
    }

    @Test fun unwrapsExisting() {
        val kek = FakeKek()
        val preWrapped = kek.wrap(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())
        val store = FakeStore(preWrapped)
        val master = MediaCryptoMaster(kek, { store.get() }, { store.set(it) }).masterKey()
        assertEquals(32, master.encoded.size)
        assertSame(preWrapped, store.blob) // 未覆盖
    }
}