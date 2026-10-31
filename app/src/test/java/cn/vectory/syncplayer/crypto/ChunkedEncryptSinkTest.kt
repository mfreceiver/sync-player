package cn.vectory.syncplayer.crypto

import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChunkedEncryptSinkTest {

    private lateinit var key: SecretKey
    private val iv = CryptoScheme.newIv()

    @Before
    fun setup() {
        key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }

    @Test
    fun closeLengthMismatchThrowsAndCannotFinalize() {
        val out = ByteArrayOutputStream()
        val sink = ChunkedEncryptSink(out, key, iv, 100L)
        sink.write(ByteArray(50))
        assertThrows(IOException::class.java) { sink.close() }
        assertFalse(sink.finalized)
        assertEquals(50L, sink.plainBytesWritten)
    }

    @Test
    fun closeIsIdempotent() {
        val out = ByteArrayOutputStream()
        val sink = ChunkedEncryptSink(out, key, iv, 3L)
        sink.write(byteArrayOf(1, 2, 3))
        sink.close()
        sink.close() // 第二次不抛
        assertTrue(sink.finalized)
        assertEquals(3, out.size())
    }

    @Test
    fun failedCloseStaysFailed() {
        val out = ByteArrayOutputStream()
        val sink = ChunkedEncryptSink(out, key, iv, 10L)
        sink.write(ByteArray(4))
        assertThrows(IOException::class.java) { sink.close() }
        assertThrows(IOException::class.java) { sink.close() } // 幂等但保持失败可见
    }

    @Test
    fun writeAfterCloseRejected() {
        val out = ByteArrayOutputStream()
        val sink = ChunkedEncryptSink(out, key, iv, 0L)
        sink.close()
        assertThrows(IOException::class.java) { sink.write(1) }
        assertThrows(IOException::class.java) { sink.write(ByteArray(3)) }
    }

    @Test
    fun multiChunkBufferingRoundtrip() {
        // 跨多个 256KiB 缓冲块
        val plain = java.util.Random(1).let { r ->
            ByteArray(CryptoScheme.CTR_SINK_BUFFER * 2 + 123).also { r.nextBytes(it) }
        }
        val out = ByteArrayOutputStream()
        ChunkedEncryptSink(out, key, iv, plain.size.toLong()).use { s ->
            // 混合写法：不定长分片，跨多个缓冲块
            var off = 0
            while (off < plain.size) {
                val n = minOf(70001, plain.size - off)
                s.write(plain, off, n)
                off += n
            }
        }
        val plain2 = plain
        val out2 = out
        assertEquals(plain2.size.toLong(), out2.size().toLong())
        // P2-18：字节级断言（此前仅断 size——缓冲边界 off-by-one 不会被发现）
        val decryptor = RangeDecryptor(key, iv, plain2.size.toLong())
        val roundtrip = decryptor.readRange(
            object : cn.vectory.syncplayer.storage.volume.SeekableReadHandle {
                private var pos = 0L
                override fun seek(position: Long) { pos = position }
                override fun read(b: ByteArray, o: Int, l: Int): Int {
                    if (pos >= out2.size()) return -1
                    val n = minOf(l.toLong(), out2.size() - pos).toInt()
                    System.arraycopy(out2.toByteArray(), pos.toInt(), b, o, n)
                    pos += n; return n
                }
                override fun size(): Long = out2.size().toLong()
                override fun close() {}
            }, 0, plain2.size
        )
        assertArrayEquals(plain2, roundtrip)
    }

    @Test
    fun ctrCounterLegalBeyond2GiB() {
        // P2-18：CTR 计数器为 8B 大端 Long（counter = offset/16）。合法媒体文件可超
        // 2GiB/4GiB——此处用 cipher 级计算断言（不真写 2GiB）：
        // 1) startBlock 在 2GiB/2TiB 等合法偏移处加解密 roundtrip；
        // 2) 计数器推进生成的 keystream 与 block 0 不同（未回绕、未截断为 32 位）。
        val twoGiBBlocks = (1L shl 31) / 16L
        val twoTiBBlocks = (1L shl 41) / 16L
        val starts = longArrayOf(0L, twoGiBBlocks, twoGiBBlocks + 1L, twoTiBBlocks - 1L)
        val plain = ByteArray(64) { (it * 31 + 7).toByte() }

        val ciphertexts = starts.map { start ->
            CryptoScheme.newCtrCipher(key, iv, start, encrypt = true).doFinal(plain)
        }
        starts.forEachIndexed { i, start ->
            val decrypted = CryptoScheme.newCtrCipher(key, iv, start, encrypt = false)
                .doFinal(ciphertexts[i])
            assertArrayEquals("roundtrip failed at startBlock=$start", plain, decrypted)
        }
        // 非 0 起点的密文必须与起点 0 的不同（counter 进入了高位，没有回绕到 0）
        for (i in 1 until ciphertexts.size) {
            assertFalse("counter appears wrapped at start=${starts[i]}", ciphertexts[i].contentEquals(ciphertexts[0]))
        }
        // 计数器对 2GiB 边界两侧的相邻块产出不同 keystream（连续性、非重置）
        val a = CryptoScheme.newCtrCipher(key, iv, twoGiBBlocks - 1, encrypt = true).doFinal(plain)
        val b = CryptoScheme.newCtrCipher(key, iv, twoGiBBlocks, encrypt = true).doFinal(plain)
        assertNotEquals(a.contentHashCode(), b.contentHashCode())
        // 流式一致性：单 cipher 连续加密两块 == 两个 cipher 分别加密（计数器自增正确）
        val streaming = CryptoScheme.newCtrCipher(key, iv, twoGiBBlocks, encrypt = true)
        val first = ByteArray(16) { (it + 1).toByte() }
        val second = ByteArray(16) { (it + 100).toByte() }
        val streamOut = streaming.doFinal(first + second)
        val splitOut = CryptoScheme.newCtrCipher(key, iv, twoGiBBlocks, encrypt = true).doFinal(first) +
            CryptoScheme.newCtrCipher(key, iv, twoGiBBlocks + 1, encrypt = true).doFinal(second)
        assertArrayEquals(splitOut, streamOut)
    }
}
