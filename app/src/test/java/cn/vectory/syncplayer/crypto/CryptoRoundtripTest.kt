package cn.vectory.syncplayer.crypto

import java.io.ByteArrayOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * v2 crypto 验收：CTR 随机非对齐 seek / 0 字节 / EOF / 大偏移正确；GCM 篡改拒绝；
 * HKDF 域隔离（generation 变 ⇒ key 变；content ≠ thumb 域）。
 */
class CryptoRoundtripTest {

    private lateinit var master: SecretKey
    private lateinit var key: SecretKey
    private lateinit var iv: ByteArray
    private lateinit var plain: ByteArray

    @Before
    fun setup() {
        master = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        key = CryptoScheme.deriveContentKey(master, "gen001")
        iv = CryptoScheme.newIv()
        plain = Random(42).nextBytes(1024 * 1024 + 7) // 1MiB+7：跨多块、非对齐长度
    }

    private fun encryptAll(): ByteArray {
        val out = ByteArrayOutputStream()
        ChunkedEncryptSink(out, key, iv, plain.size.toLong()).use { it.write(plain) }
        return out.toByteArray()
    }

    private fun readAt(cipher: ByteArray, offset: Long, len: Int): ByteArray {
        val handle = ByteHandle(cipher)
        return RangeDecryptor(key, iv, plain.size.toLong()).readRange(handle, offset, len)
    }

    @Test
    fun ctrRoundtripFull() {
        assertArrayEquals(plain, readAt(encryptAll(), 0, plain.size))
    }

    @Test
    fun ctrRandomUnalignedSeeks() {
        val cipher = encryptAll()
        val rnd = Random(7)
        repeat(50) {
            val off = rnd.nextInt(plain.size)
            val len = rnd.nextInt(plain.size - off + 1)
            val expected = plain.copyOfRange(off, off + len)
            assertArrayEquals("off=$off len=$len", expected, readAt(cipher, off.toLong(), len))
        }
    }

    @Test
    fun ctrZeroByteReadAndEofAndLargeOffset() {
        val cipher = encryptAll()
        assertEquals(0, readAt(cipher, 0, 0).size)
        assertEquals(0, readAt(cipher, plain.size.toLong(), 100).size) // EOF 起 0 字节
        assertEquals(0, readAt(cipher, Long.MAX_VALUE / 2, 16).size)  // 大偏移（>= size）
        // 末段跨界请求被 clamp 到 EOF
        val tail = readAt(cipher, plain.size - 3L, 100)
        assertArrayEquals(plain.copyOfRange(plain.size - 3, plain.size), tail)
    }

    @Test
    fun zeroByteFileRoundtrip() {
        val out = ByteArrayOutputStream()
        ChunkedEncryptSink(out, key, iv, 0L).use { }
        assertEquals(0, out.size())
    }

    @Test
    fun truncatedCiphertextThrowsEof() {
        val cipher = encryptAll().copyOf(plain.size / 2)
        assertThrows(java.io.EOFException::class.java) {
            RangeDecryptor(key, iv, plain.size.toLong())
                .readRange(ByteHandle(cipher), plain.size - 10L, 10)
        }
    }

    @Test
    fun cipherIsNotPlaintext() {
        val cipher = encryptAll()
        assertEquals(plain.size.toLong(), cipher.size.toLong())
        assertFalse(cipher.contentEquals(plain))
    }

    @Test
    fun thumbGcmRoundtripAndTamperRejected() {
        val thumbKey = CryptoScheme.deriveThumbKey(master)
        val blob = CryptoScheme.encryptThumb(thumbKey, byteArrayOf(1, 2, 3, 4))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), CryptoScheme.decryptThumb(thumbKey, blob))
        val tampered = blob.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertThrows(Exception::class.java) { CryptoScheme.decryptThumb(thumbKey, tampered) }
        // 截断也拒绝
        assertThrows(Exception::class.java) { CryptoScheme.decryptThumb(thumbKey, blob.copyOf(10)) }
    }

    @Test
    fun hkdfDomainSeparation() {
        val other = CryptoScheme.deriveContentKey(master, "gen002")
        assertFalse(other.encoded.contentEquals(key.encoded))
        assertFalse(CryptoScheme.deriveThumbKey(master).encoded.contentEquals(key.encoded))
        // 同输入确定性
        assertTrue(CryptoScheme.deriveContentKey(master, "gen001").encoded.contentEquals(key.encoded))
    }

    /** 密文句柄替身：内存字节 + seek/read。 */
    private class ByteHandle(private val data: ByteArray) :
        cn.vectory.syncplayer.storage.volume.SeekableReadHandle {
        private var pos = 0L
        override fun seek(position: Long) { pos = position }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (pos >= data.size) return -1
            val n = minOf(length.toLong(), data.size - pos).toInt()
            System.arraycopy(data, pos.toInt(), buffer, offset, n)
            pos += n
            return n
        }
        override fun size(): Long = data.size.toLong()
        override fun close() {}
    }
}
