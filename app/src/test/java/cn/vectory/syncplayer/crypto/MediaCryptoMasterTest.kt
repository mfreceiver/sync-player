package cn.vectory.syncplayer.crypto

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class MediaCryptoMasterTest {
    /**
     * 纯软件 KEK 替身，验证 wrap/unwrap 逻辑（绕过 AndroidKeyStore）。
     *
     * wrap() 镜像修复后的 KeystoreKekProvider.wrap()：不向 ENCRYPT_MODE 传
     * GCMParameterSpec，由 Cipher 自行生成 IV，再用 cipher.iv 取回。这样测试覆盖的
     * blob 布局（[iv(12)][ct+tag]）与生产代码一致。
     */
    private class FakeKek(val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()) : KekProvider {
        override fun wrap(mk: SecretKey): ByteArray {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
            val iv = c.iv
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

    /**
     * 显式验证 wrap→unwrap round-trip：首次 masterKey() 触发 generateAndStore→wrap，
     * 清缓存后再次 masterKey() 走 unwrap，两次返回的密钥字节必须一致。blob 布局必须
     * 与 unwrap() 期望的 [iv(12)][ct+tag] 一致。
     */
    @Test fun wrapUnwrapRoundTrip() {
        val kek = FakeKek()
        val store = FakeStore()
        val master = MediaCryptoMaster(kek, { store.get() }, { store.set(it) })
        val first = master.masterKey()
        assertNotNull(store.blob)
        // 清缓存模拟新进程：loadBlob 非空 → 走 unwrap 路径
        val second = MediaCryptoMaster(kek, { store.get() }, { store.set(it) }).masterKey()
        assertArrayEquals(first.encoded, second.encoded)
    }
}
