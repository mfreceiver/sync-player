package cn.vectory.syncplayer.crypto

import cn.vectory.syncplayer.storage.volume.SeekableReadHandle
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.RandomAccessFile
import java.security.SecureRandom
import javax.crypto.spec.SecretKeySpec

class RangeDecryptorTest {
    private val master = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES")
    private val contentKey = CryptoScheme.deriveContentKey(master)
    private val iv = CryptoScheme.newIv()

    private fun fixture(plain: ByteArray): Pair<ByteArray, SeekableReadHandle> {
        // 生成密文
        val cipher = java.io.ByteArrayOutputStream().apply {
            CryptoScheme.encryptStream(contentKey, iv, this).use { it.write(plain) }
        }.toByteArray()
        // 内存 SeekableReadHandle
        val handle = object : SeekableReadHandle {
            private val buf = cipher; private var pos = 0L
            override fun seek(position: Long) { pos = position }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val n = minOf(length.toLong(), (buf.size - pos).coerceAtLeast(0)).toInt()
                if (n <= 0) return -1
                System.arraycopy(buf, pos.toInt(), buffer, offset, n); pos += n; return n
            }
            override fun size(): Long = buf.size.toLong()
            override fun close() {}
        }
        return plain to handle
    }

    @Test fun readRangeMatchesPlaintextSlice() {
        val (plain, handle) = fixture(ByteArray(2000) { (it and 0xFF).toByte() })
        val rd = RangeDecryptor(contentKey, iv, plain.size.toLong())
        for ((off, len) in listOf(0 to 1, 13 to 17, 32 to 64, 0 to 2000, 1500 to 500, 1999 to 1)) {
            val got = rd.readRange(handle, off.toLong(), len)
            assertArrayEquals("off=$off len=$len", plain.copyOfRange(off, off + len), got)
        }
    }

    @Test fun readRangeClampsAtEof() {
        val (plain, handle) = fixture(ByteArray(100) { it.toByte() })
        val rd = RangeDecryptor(contentKey, iv, 100L)
        val got = rd.readRange(handle, 90L, 50)
        assertEquals(10, got.size)
        assertArrayEquals(plain.copyOfRange(90, 100), got)
    }
}