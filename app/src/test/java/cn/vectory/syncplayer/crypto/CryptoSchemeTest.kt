package cn.vectory.syncplayer.crypto

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Scheme-level tests for AEAD v2: header codec, length math, nonce uniqueness, full round-trip
 * via the encrypting sink, and the thumbnail format. Range/random-read/tamper coverage lives in
 * [RangeDecryptorTest].
 */
class CryptoSchemeTest {
    private val master: SecretKey = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES")
    private val contentKey = CryptoScheme.deriveContentKey(master)
    private val thumbKey = CryptoScheme.deriveThumbKey(master)

    // ---- header codec ----

    @Test fun headerRoundTrip() {
        for (plain in listOf(0L, 1L, (CryptoScheme.CHUNK_SIZE - 1).toLong(),
            CryptoScheme.CHUNK_SIZE.toLong(), (CryptoScheme.CHUNK_SIZE + 1).toLong(),
            3L * CryptoScheme.CHUNK_SIZE + 123)) {
            val raw = CryptoScheme.encodeHeader(plain)
            assertEquals(CryptoScheme.HEADER_SIZE, raw.size)
            // magic at bytes 0..3
            assertArrayEquals(CryptoScheme.MAGIC, raw.copyOfRange(0, 4))
            // version byte
            assertEquals(CryptoScheme.FORMAT_VERSION, raw[4].toInt() and 0xFF)
            // reserved byte zero
            assertEquals(0, raw[5].toInt())
            // chunk size KiB (BE u16)
            val cs = ((raw[6].toInt() and 0xFF) shl 8) or (raw[7].toInt() and 0xFF)
            assertEquals(CryptoScheme.CHUNK_SIZE_KIB, cs)
            // plaintext length (BE i64)
            val back = ByteBuffer.wrap(raw, 8, 8).order(ByteOrder.BIG_ENDIAN).long
            assertEquals(plain, back)

            val parsed = CryptoScheme.decodeHeader(raw)
            assertEquals(plain, parsed.plaintextLength)
            assertArrayEquals(raw, parsed.raw)
        }
    }

    @Test fun headerRejectsBadMagic() {
        val raw = CryptoScheme.encodeHeader(10L).copyOf().apply { this[0] = 0x00 }
        assertThrows(LegacyCiphertextException::class.java) { CryptoScheme.decodeHeader(raw) }
    }

    @Test fun headerRejectsUnsupportedVersion() {
        val raw = CryptoScheme.encodeHeader(10L).copyOf().apply { this[4] = 0x09 }
        assertThrows(IOException::class.java) { CryptoScheme.decodeHeader(raw) }
    }

    @Test fun headerRejectsUnsupportedChunkSize() {
        val raw = CryptoScheme.encodeHeader(10L).copyOf().apply { this[6] = 0x01 } // 256 KiB
        assertThrows(IOException::class.java) { CryptoScheme.decodeHeader(raw) }
    }

    @Test fun headerRejectsNonZeroReserved() {
        val raw = CryptoScheme.encodeHeader(100L).copyOf().apply { this[5] = 0x01 }
        assertThrows(IOException::class.java) { CryptoScheme.decodeHeader(raw) }
    }

    @Test fun headerRejectsNonZeroReservedOnEmptyFile() {
        // Zero-byte files have no chunks, so the header is never bound as GCM AAD. The strict
        // reserved-byte check is therefore the only defense against undetected tampering of
        // byte 5 on empty files (the specific vulnerability this guards).
        val raw = CryptoScheme.encodeHeader(0L).copyOf().apply { this[5] = 0x42 }
        assertEquals(CryptoScheme.HEADER_SIZE, raw.size)  // empty file → header-only on disk
        assertThrows(IOException::class.java) { CryptoScheme.decodeHeader(raw) }
    }

    // ---- length math ----

    @Test fun cipherLengthForMatchesActualForSixLengths() {
        for (plainLen in listOf(0L, 1L, (CryptoScheme.CHUNK_SIZE - 1).toLong(),
                CryptoScheme.CHUNK_SIZE.toLong(), (CryptoScheme.CHUNK_SIZE + 1).toLong(),
                3L * CryptoScheme.CHUNK_SIZE + 123)) {
            val expected = CryptoScheme.cipherLengthFor(plainLen)
            val plain = ByteArray(plainLen.toInt())
            SecureRandom().nextBytes(plain)
            val iv = CryptoScheme.newIv()
            val cipher = ByteArrayOutputStream().apply {
                CryptoScheme.encryptStream(contentKey, iv, plainLen, this).use { it.write(plain) }
            }.toByteArray()
            assertEquals("plainLen=$plainLen", expected, cipher.size.toLong())
        }
    }

    @Test fun cipherLengthForFormula() {
        // 16 + N*16 + plainLen, where N = ceil(plainLen / CHUNK)
        assertEquals(16L, CryptoScheme.cipherLengthFor(0L))                       // N=0
        assertEquals((16 + 16 + 1).toLong(), CryptoScheme.cipherLengthFor(1L))     // N=1
        assertEquals(
            (16 + 16 + CryptoScheme.CHUNK_SIZE).toLong(),
            CryptoScheme.cipherLengthFor(CryptoScheme.CHUNK_SIZE.toLong())
        ) // N=1
        val p = 3L * CryptoScheme.CHUNK_SIZE + 123                                 // N=4
        assertEquals(16 + 4 * 16 + p, CryptoScheme.cipherLengthFor(p))
    }

    // ---- length math: overflow safety & impl cap ----

    @Test fun numChunksRejectsNegative() {
        assertThrows(IllegalArgumentException::class.java) { CryptoScheme.numChunks(-1L) }
        assertThrows(IllegalArgumentException::class.java) { CryptoScheme.numChunks(Long.MIN_VALUE) }
    }

    @Test fun cipherLengthForRejectsNegative() {
        assertThrows(IllegalArgumentException::class.java) { CryptoScheme.cipherLengthFor(-1L) }
        assertThrows(IllegalArgumentException::class.java) { CryptoScheme.cipherLengthFor(Long.MIN_VALUE) }
    }

    @Test fun numChunksLegalLengthsStillCorrect() {
        // Regression guard: the new overflow-free formula matches expected ceil() on legal inputs.
        val chunk = CryptoScheme.CHUNK_SIZE.toLong()
        assertEquals(0L, CryptoScheme.numChunks(0L))
        assertEquals(1L, CryptoScheme.numChunks(1L))
        assertEquals(1L, CryptoScheme.numChunks(chunk - 1))
        assertEquals(1L, CryptoScheme.numChunks(chunk))
        assertEquals(2L, CryptoScheme.numChunks(chunk + 1))
        assertEquals(4L, CryptoScheme.numChunks(3L * chunk + 123))
        // Top of the impl cap: exactly Int.MAX_VALUE full chunks.
        assertEquals(Int.MAX_VALUE.toLong(), CryptoScheme.numChunks(Int.MAX_VALUE.toLong() * chunk))
    }

    @Test fun cipherLengthForRejectsNearLongMax() {
        // plainLen near Long.MAX_VALUE used to overflow `plainLen + CHUNK_SIZE - 1` to a negative
        // value, yielding a negative chunk count that bypassed the cap check and made
        // cipherLengthFor return a negative (or silently wrong) length. Must throw now — never
        // return a wrapped/negative value.
        for (plainLen in listOf(
            Long.MAX_VALUE,
            Long.MAX_VALUE - 1,
            Long.MAX_VALUE - CryptoScheme.CHUNK_SIZE,
            Long.MAX_VALUE - CryptoScheme.CHUNK_SIZE + 1,
        )) {
            assertThrows("plainLen=$plainLen", IllegalArgumentException::class.java) {
                CryptoScheme.cipherLengthFor(plainLen)
            }
        }
    }

    @Test fun cipherLengthForBoundaryAtImplCap() {
        // Impl cap = numChunks(plainLen) <= Int.MAX_VALUE (≈ 128 TiB plaintext).
        val maxN = Int.MAX_VALUE.toLong()           // 2_147_483_647
        val chunk = CryptoScheme.CHUNK_SIZE.toLong()

        // Largest plainLen still within cap (exactly maxN full chunks).
        val justInside = maxN * chunk
        assertEquals(maxN, CryptoScheme.numChunks(justInside))
        // Does not throw; returns the exact formula sum.
        val expected = CryptoScheme.HEADER_SIZE.toLong() +
            maxN * CryptoScheme.GCM_TAG_BYTES +
            justInside
        assertEquals(expected, CryptoScheme.cipherLengthFor(justInside))

        // One chunk more → must throw (chunk count maxN+1 > Int.MAX_VALUE).
        val justOutside = (maxN + 1L) * chunk
        assertEquals(maxN + 1L, CryptoScheme.numChunks(justOutside))   // chunk count computed correctly…
        assertThrows(IllegalArgumentException::class.java) { CryptoScheme.cipherLengthFor(justOutside) }
    }

    @Test fun encodeHeaderAndEncryptStreamRejectOverCap() {
        val overCap = (Int.MAX_VALUE.toLong() + 1L) * CryptoScheme.CHUNK_SIZE
        assertThrows(IllegalArgumentException::class.java) { CryptoScheme.encodeHeader(overCap) }
        val iv = CryptoScheme.newIv()
        assertThrows(IllegalArgumentException::class.java) {
            CryptoScheme.encryptStream(contentKey, iv, overCap, ByteArrayOutputStream())
        }
    }

    // ---- nonce uniqueness ----

    @Test fun nonceDiffersAcrossChunkIndices() {
        val iv = CryptoScheme.newIv()
        val n0 = CryptoScheme.nonceForChunk(iv, 0)
        val n1 = CryptoScheme.nonceForChunk(iv, 1)
        val n2 = CryptoScheme.nonceForChunk(iv, 2)
        assertEquals(CryptoScheme.NONCE_LEN, n0.size)
        assertFalse(n0.contentEquals(n1))
        assertFalse(n1.contentEquals(n2))
        assertFalse(n0.contentEquals(n2))
        // iv prefix preserved
        assertArrayEquals(iv, n0.copyOfRange(0, CryptoScheme.IV_LEN))
        // chunk index encoded BE in last 4 bytes
        assertEquals(1, ByteBuffer.wrap(n1, 8, 4).order(ByteOrder.BIG_ENDIAN).int)
        assertEquals(2, ByteBuffer.wrap(n2, 8, 4).order(ByteOrder.BIG_ENDIAN).int)
    }

    @Test fun newIvIsUniqueAcrossManyCalls() {
        val ivs = (1..2000).map { CryptoScheme.newIv().toList() }.toSet()
        assertEquals(2000, ivs.size)
    }

    // ---- full round-trip via the encrypting sink (six lengths) ----

    @Test fun fullRoundTripForSixLengths() {
        for (plainLen in listOf(0L, 1L, (CryptoScheme.CHUNK_SIZE - 1).toLong(),
                CryptoScheme.CHUNK_SIZE.toLong(), (CryptoScheme.CHUNK_SIZE + 1).toLong(),
                3L * CryptoScheme.CHUNK_SIZE + 123)) {
            val plain = ByteArray(plainLen.toInt()).also { SecureRandom().nextBytes(it) }
            val iv = CryptoScheme.newIv()
            val cipher = ByteArrayOutputStream().apply {
                CryptoScheme.encryptStream(contentKey, iv, plainLen, this).use { it.write(plain) }
            }.toByteArray()
            // Decrypt sequentially chunk-by-chunk and reassemble
            val header = CryptoScheme.decodeHeader(cipher)
            assertEquals(plainLen, header.plaintextLength)
            val out = ByteArrayOutputStream()
            val n = CryptoScheme.numChunks(plainLen).toInt()
            var pos = CryptoScheme.HEADER_SIZE
            var remaining = plainLen
            for (i in 0 until n) {
                val chunkPlain = minOf(CryptoScheme.CHUNK_SIZE.toLong(), remaining).toInt()
                val chunkCipherLen = chunkPlain + CryptoScheme.GCM_TAG_BYTES
                val dec = CryptoScheme.decryptChunk(contentKey, iv, header.raw, i, cipher, pos, chunkCipherLen)
                out.write(dec)
                pos += chunkCipherLen
                remaining -= chunkPlain
            }
            assertEquals(0L, remaining)
            assertArrayEquals("plainLen=$plainLen", plain, out.toByteArray())
        }
    }

    @Test fun sinkCloseRejectsLengthMismatch() {
        val iv = CryptoScheme.newIv()
        // declare 100 bytes but write only 10
        val sink = CryptoScheme.encryptStream(contentKey, iv, 100L, ByteArrayOutputStream())
        sink.write(ByteArray(10))
        assertThrows(IOException::class.java) { sink.close() }
    }

    @Test fun sinkCloseRejectsOverflow() {
        val iv = CryptoScheme.newIv()
        // declare 10 bytes but write 100
        val sink = CryptoScheme.encryptStream(contentKey, iv, 10L, ByteArrayOutputStream())
        sink.write(ByteArray(100))
        assertThrows(IOException::class.java) { sink.close() }
    }

    @Test fun sinkCloseLengthMismatchWritesNoDecryptableTail() {
        // The fix: on length mismatch, close() must throw BEFORE sealing the buffered tail chunk.
        // Otherwise the on-disk result would be a self-consistent v2 file decryptable to the
        // wrong length. Assert the output is header-only (16 bytes) → truncated → not decryptable
        // to any plaintext matching the declared length.
        val iv = CryptoScheme.newIv()
        val captured = ByteArrayOutputStream()
        val sink = CryptoScheme.encryptStream(contentKey, iv, 100L, captured)
        sink.write(ByteArray(10))   // declare 100, write only 10
        assertThrows(IOException::class.java) { sink.close() }
        assertEquals(CryptoScheme.HEADER_SIZE.toLong(), captured.size().toLong())
    }

    @Test fun sinkCloseOverflowWritesNoDecryptableTail() {
        // Symmetric to the above: declare 10, write 100 (still < CHUNK_SIZE → no inline flush).
        // Output must be header-only, not a 100-byte chunk under a 10-byte declared length.
        val iv = CryptoScheme.newIv()
        val captured = ByteArrayOutputStream()
        val sink = CryptoScheme.encryptStream(contentKey, iv, 10L, captured)
        sink.write(ByteArray(100))
        assertThrows(IOException::class.java) { sink.close() }
        assertEquals(CryptoScheme.HEADER_SIZE.toLong(), captured.size().toLong())
    }

    @Test fun sinkConstructorClosesUnderlyingSinkOnHeaderWriteFailure() {
        // The fix: if sink.write(header) throws during construction, the caller never receives
        // `this` and so cannot call close(). The constructor must close the underlying sink itself
        // to avoid leaking it, then rethrow.
        val iv = CryptoScheme.newIv()
        var closed = false
        val failingSink = object : java.io.OutputStream() {
            override fun write(b: Int) { throw IOException("header write failure") }
            override fun close() { closed = true }
        }
        assertThrows(IOException::class.java) {
            CryptoScheme.encryptStream(contentKey, iv, 10L, failingSink)
        }
        assertTrue("underlying sink must be closed on construction failure", closed)
    }

    // ---- thumbnail format (unchanged) ----

    @Test fun gcmThumbRoundTrip() {
        val plain = ByteArray(500).also { SecureRandom().nextBytes(it) }
        val encoded = CryptoScheme.encryptThumb(thumbKey, plain)
        assertArrayEquals(plain, CryptoScheme.decryptThumb(thumbKey, encoded))
    }

    @Test fun gcmThumbTamper() {
        val plain = ByteArray(64).also { SecureRandom().nextBytes(it) }
        val encoded = CryptoScheme.encryptThumb(thumbKey, plain).copyOf().apply { this[20] = (this[20].toInt() xor 0xFF).toByte() }
        assertThrows(Exception::class.java) { CryptoScheme.decryptThumb(thumbKey, encoded) }
    }
}
