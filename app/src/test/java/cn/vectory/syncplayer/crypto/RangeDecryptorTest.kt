package cn.vectory.syncplayer.crypto

import cn.vectory.syncplayer.storage.volume.SeekableReadHandle
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.RandomAccessFile
import java.security.SecureRandom
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/** Range/random-read tests for AEAD v2 (see /tmp/opencode/sync-player-aead-spec.md). */
class RangeDecryptorTest {
    private val master = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES")
    private val contentKey = CryptoScheme.deriveContentKey(master)
    private val iv = CryptoScheme.newIv()

    /** In-memory seekable handle backed by an exact-size byte array. */
    private class MemHandle(private val buf: ByteArray) : SeekableReadHandle {
        private var pos = 0L
        override fun seek(position: Long) { pos = position }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val n = minOf(length.toLong(), (buf.size - pos).coerceAtLeast(0)).toInt()
            if (n <= 0) return -1
            System.arraycopy(buf, pos.toInt(), buffer, offset, n)
            pos += n
            return n
        }
        override fun size(): Long = buf.size.toLong()
        override fun close() {}
    }

    /** File-backed handle (used for truncation/tamper tests where we edit bytes on disk). */
    private class FileHandle(private val raf: RandomAccessFile) : SeekableReadHandle {
        override fun seek(position: Long) { raf.seek(position) }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = raf.read(buffer, offset, length)
        override fun size(): Long = raf.length()
        override fun close() { raf.close() }
    }

    private fun encrypt(plain: ByteArray): ByteArray =
        ByteArrayOutputStream().apply {
            CryptoScheme.encryptStream(contentKey, iv, plain.size.toLong(), this).use { it.write(plain) }
        }.toByteArray()

    // ---- basic readRange matches plaintext slice ----

    @Test fun readRangeMatchesPlaintextSlice() {
        val plain = ByteArray(2000) { (it and 0xFF).toByte() }
        val rd = RangeDecryptor(contentKey, iv, plain.size.toLong())
        val handle = MemHandle(encrypt(plain))
        for ((off, len) in listOf(0 to 1, 13 to 17, 32 to 64, 0 to 2000, 1500 to 500, 1999 to 1)) {
            val got = rd.readRange(handle, off.toLong(), len)
            assertArrayEquals("off=$off len=$len", plain.copyOfRange(off, off + len), got)
        }
    }

    @Test fun readRangeClampsAtEof() {
        val plain = ByteArray(100) { it.toByte() }
        val rd = RangeDecryptor(contentKey, iv, 100L)
        val got = rd.readRange(MemHandle(encrypt(plain)), 90L, 50)
        assertEquals(10, got.size)
        assertArrayEquals(plain.copyOfRange(90, 100), got)
    }

    @Test fun readRangeAtOrBeyondSizeReturnsEmpty() {
        val plain = ByteArray(64) { 0x42 }
        val rd = RangeDecryptor(contentKey, iv, 64L)
        val h = MemHandle(encrypt(plain))
        assertArrayEquals(ByteArray(0), rd.readRange(h, 64L, 10))
        assertArrayEquals(ByteArray(0), rd.readRange(h, 0L, 0))
        assertArrayEquals(ByteArray(0), rd.readRange(h, 10_000L, 10))
    }

    // ---- random reads vs plaintext slice (multi-chunk, 200 cases) ----

    @Test fun randomReadsMatchPlaintextSlice() {
        val len = 3L * CryptoScheme.CHUNK_SIZE + 123
        val plain = ByteArray(len.toInt()).also { SecureRandom().nextBytes(it) }
        val cipher = encrypt(plain)
        val rd = RangeDecryptor(contentKey, iv, len)
        val handle = MemHandle(cipher)
        val rng = Random(seed = 0xC0FFEE)
        // 190 random (offset, length) pairs + 10 explicit boundary cases spanning chunk edges.
        val cases = mutableListOf<Pair<Long, Int>>()
        repeat(190) {
            val off = rng.nextLong(0L, len)
            val maxLen = (len - off + 1).toInt().coerceAtMost(CryptoScheme.CHUNK_SIZE * 2 + 5)
            val l = if (maxLen <= 1) 1 else rng.nextInt(1, maxLen)
            cases.add(off to l)
        }
        // boundary: chunk-start, chunk-end, last chunk (short), spanning two chunks, single byte at EOF-1
        cases.add(0L to 1)
        cases.add((CryptoScheme.CHUNK_SIZE - 1).toLong() to 2)                       // straddle chunk0/1
        cases.add((CryptoScheme.CHUNK_SIZE).toLong() to 1)                           // start of chunk1
        cases.add((2L * CryptoScheme.CHUNK_SIZE - 1) to 3)                           // straddle chunk1/2
        cases.add((3L * CryptoScheme.CHUNK_SIZE) to 123)                             // last (short) chunk
        cases.add((len - 1) to 1)                                                    // last byte
        cases.add(0L to CryptoScheme.CHUNK_SIZE)                                     // exact one chunk
        cases.add(0L to (len.toInt()))                                               // whole file
        cases.add((len - 5) to 100)                                                  // near/over EOF clamp
        cases.add(123L to (CryptoScheme.CHUNK_SIZE * 2))                             // multi-chunk from mid

        assertEquals(200, cases.size)
        cases.forEach { (off, l) ->
            val got = rd.readRange(handle, off, l)
            val expectedLen = minOf(l.toLong(), len - off).toInt().coerceAtLeast(0)
            assertEquals("off=$off len=$l", expectedLen, got.size)
            assertArrayEquals("off=$off len=$l", plain.copyOfRange(off.toInt(), off.toInt() + expectedLen), got)
        }
    }

    // ---- tamper detection ----

    @Test fun tamperHeaderIsDetected() {
        val plain = ByteArray(CryptoScheme.CHUNK_SIZE + 10).also { SecureRandom().nextBytes(it) }
        val cipher = encrypt(plain).copyOf()
        // flip a byte in the plaintext-length field (header byte 8 = high byte of i64)
        cipher[8] = (cipher[8].toInt() xor 0x01.toByte().toInt()).toByte()
        val rd = RangeDecryptor(contentKey, iv, plain.size.toLong())
        assertThrows(Exception::class.java) { rd.readRange(MemHandle(cipher), 0L, 16) }
    }

    @Test fun tamperChunkBodyIsDetected() {
        val plain = ByteArray(CryptoScheme.CHUNK_SIZE + 10).also { SecureRandom().nextBytes(it) }
        val cipher = encrypt(plain).copyOf()
        // flip one byte inside chunk 0's ciphertext body (just after header)
        val bodyOff = CryptoScheme.HEADER_SIZE + 10
        cipher[bodyOff] = (cipher[bodyOff].toInt() xor 0xFF.toByte().toInt()).toByte()
        val rd = RangeDecryptor(contentKey, iv, plain.size.toLong())
        assertThrows(Exception::class.java) { rd.readRange(MemHandle(cipher), 0L, 16) }
    }

    @Test fun tamperChunkTagIsDetected() {
        val plain = ByteArray(CryptoScheme.CHUNK_SIZE + 10).also { SecureRandom().nextBytes(it) }
        val cipher = encrypt(plain).copyOf()
        // flip one byte of chunk 0's GCM tag (last byte of the first chunk's region)
        val tagOff = CryptoScheme.HEADER_SIZE + CryptoScheme.CHUNK_SIZE + CryptoScheme.GCM_TAG_BYTES - 1
        cipher[tagOff] = (cipher[tagOff].toInt() xor 0x80.toByte().toInt()).toByte()
        val rd = RangeDecryptor(contentKey, iv, plain.size.toLong())
        assertThrows(Exception::class.java) { rd.readRange(MemHandle(cipher), 0L, 16) }
    }

    @Test fun tamperReservedByteIsRejected() {
        // The reserved byte (offset 5) is now strictly validated by decodeHeader, so a non-zero
        // value is rejected before any chunk is read. (For non-empty files this is also caught by
        // GCM since the header is AAD; the explicit check is the critical defense for empty files,
        // covered directly in CryptoSchemeTest.headerRejectsNonZeroReservedOnEmptyFile.)
        val plain = ByteArray(100) { 0x42 }
        val cipher = encrypt(plain).copyOf().apply { this[5] = 0x01 }
        val rd = RangeDecryptor(contentKey, iv, 100L)
        assertThrows(java.io.IOException::class.java) { rd.readRange(MemHandle(cipher), 0L, 16) }
    }

    @Test fun blockReorderIsDetected() {
        // 4 chunks: 0,1,2 full, 3 short. Swap the on-disk regions of chunk 0 and chunk 1 (both full).
        val plain = ByteArray(3 * CryptoScheme.CHUNK_SIZE + 123).also { SecureRandom().nextBytes(it) }
        val cipher = encrypt(plain)
        val stride = CryptoScheme.CHUNK_STRIDE.toInt()
        val reordered = cipher.copyOf()
        val tmp = cipher.copyOfRange(CryptoScheme.HEADER_SIZE, CryptoScheme.HEADER_SIZE + stride)
        System.arraycopy(
            cipher, CryptoScheme.HEADER_SIZE + stride,
            reordered, CryptoScheme.HEADER_SIZE, stride
        )
        System.arraycopy(tmp, 0, reordered, CryptoScheme.HEADER_SIZE + stride, stride)
        val rd = RangeDecryptor(contentKey, iv, plain.size.toLong())
        assertThrows(Exception::class.java) { rd.readRange(MemHandle(reordered), 0L, 16) }
    }

    @Test fun truncationIsDetected() {
        val plain = ByteArray(2 * CryptoScheme.CHUNK_SIZE + 5).also { SecureRandom().nextBytes(it) }
        val cipher = encrypt(plain)
        // remove the entire last chunk → reading it must throw (EOF)
        val lastChunkStart = CryptoScheme.HEADER_SIZE + 2 * CryptoScheme.CHUNK_STRIDE
        val truncated = cipher.copyOfRange(0, lastChunkStart.toInt())
        val rd = RangeDecryptor(contentKey, iv, plain.size.toLong())
        assertThrows(Exception::class.java) {
            rd.readRange(MemHandle(truncated), (2L * CryptoScheme.CHUNK_SIZE), 5)
        }
    }

    @Test fun legacyCiphertextThrowsLegacyException() {
        // Pre-v2 had no header; emulate with arbitrary non-magic bytes.
        val legacyCipher = ByteArray(2048).also { SecureRandom().nextBytes(it) }
        val rd = RangeDecryptor(contentKey, iv, legacyCipher.size.toLong())
        // header has SPM2 magic but legacy bytes won't; the declared-length check is bypassed here
        // because we deliberately construct the decryptor with the bogus size matching the blob.
        // Expect a LegacyCiphertextException (subclass of IOException) — never silent data return.
        val ex = assertThrows(LegacyCiphertextException::class.java) {
            rd.readRange(MemHandle(legacyCipher), 0L, 16)
        }
        // sanity: it's surfaced as IOException too
        assertTrue(ex is java.io.IOException)
    }

    @Test fun shortHeaderThrowsLegacyException() {
        // A 4-byte file can't be a v2 ciphertext; must be classified legacy (not generic EOF).
        val tiny = byteArrayOf(0x00, 0x01, 0x02, 0x03)
        val rd = RangeDecryptor(contentKey, iv, 4L)
        assertThrows(LegacyCiphertextException::class.java) { rd.readRange(MemHandle(tiny), 0L, 4) }
    }

    @Test fun fileBackedHandleRoundTrip() {
        // sanity: the FileHandle helper behaves identically to MemHandle for a clean file
        val tmpFile = java.io.File.createTempFile("range-dec-", ".bin").apply { deleteOnExit() }
        val plain = ByteArray(CryptoScheme.CHUNK_SIZE + 7).also { SecureRandom().nextBytes(it) }
        java.io.FileOutputStream(tmpFile).use { it.write(encrypt(plain)) }
        val rd = RangeDecryptor(contentKey, iv, plain.size.toLong())
        FileHandle(RandomAccessFile(tmpFile, "r")).use { h ->
            val got = rd.readRange(h, 0L, plain.size)
            assertArrayEquals(plain, got)
        }
    }
}
