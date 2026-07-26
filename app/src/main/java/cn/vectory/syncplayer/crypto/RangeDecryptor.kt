package cn.vectory.syncplayer.crypto

import cn.vectory.syncplayer.storage.volume.SeekableReadHandle
import java.io.EOFException
import java.io.IOException
import javax.crypto.SecretKey
import kotlin.math.min

/**
 * Range decryptor for AEAD v2 ciphertext (see `/tmp/opencode/sync-player-aead-spec.md`).
 *
 * All offsets/lengths exposed to callers are **plaintext** coordinates; translation to per-chunk
 * ciphertext offsets is encapsulated here. The constructor signature
 * `RangeDecryptor(key, iv, size)` and [readRange]'s signature are unchanged from v1.
 *
 * Failure handling — this class never returns unauthenticated bytes:
 *  - missing/short/wrong magic header → [LegacyCiphertextException];
 *  - header fields inconsistent or declared length ≠ trusted [size] → [IOException];
 *  - any chunk GCM tag/AAD mismatch (bit-flip, reorder, cross-file swap) → AEADBadTagException;
 *  - missing bytes at end of file (truncation) → [EOFException].
 */
class RangeDecryptor(
    private val key: SecretKey,
    private val iv: ByteArray,
    private val size: Long,
) {
    init {
        require(iv.size == CryptoScheme.IV_LEN) { "iv must be ${CryptoScheme.IV_LEN} bytes" }
        require(size >= 0) { "size must be non-negative" }
    }

    /**
     * Decrypt the plaintext range `[offset, offset+requestedLen)` (clamped to [size]).
     * `offset >= size` or `requestedLen == 0` returns an empty array (v1-compatible).
     */
    fun readRange(handle: SeekableReadHandle, offset: Long, requestedLen: Int): ByteArray {
        require(offset >= 0 && requestedLen >= 0) { "offset and requestedLen must be non-negative" }
        if (offset >= size || requestedLen == 0) return ByteArray(0)
        val effectiveLen = min(requestedLen.toLong(), size - offset).toInt()
        val effectiveEnd = offset + effectiveLen   // exclusive, ≤ size

        // Read & validate the header on every call: fresh bytes from disk are bound as AAD below,
        // so any header tamper (magic/version/length) is detected either here or via GCM.
        val header = readAndValidateHeader(handle)
        if (header.plaintextLength != size) {
            throw IOException("header plaintext length (${header.plaintextLength}) != expected size ($size)")
        }

        val firstChunk = offset / CryptoScheme.CHUNK_SIZE
        val lastChunk = (effectiveEnd - 1) / CryptoScheme.CHUNK_SIZE
        require(lastChunk <= Int.MAX_VALUE.toLong()) { "chunk index out of range" }

        val out = java.io.ByteArrayOutputStream(effectiveLen)
        var readOff = offset
        var ci = firstChunk.toInt()
        while (ci <= lastChunk.toInt()) {
            val chunkPlainStart = ci.toLong() * CryptoScheme.CHUNK_SIZE
            val chunkPlainEnd = min(chunkPlainStart + CryptoScheme.CHUNK_SIZE, size)   // exclusive
            val chunkPlainLen = (chunkPlainEnd - chunkPlainStart).toInt()
            // On-disk start of chunk ci = HEADER + ci * (CHUNK + TAG). Holds for all i because every
            // chunk preceding i occupies exactly CHUNK+TAG bytes (only the *last* chunk is short).
            val cipherOff = CryptoScheme.HEADER_SIZE + ci.toLong() * CryptoScheme.CHUNK_STRIDE
            val cipherLen = chunkPlainLen + CryptoScheme.GCM_TAG_BYTES
            handle.seek(cipherOff)
            val cipherBuf = ByteArray(cipherLen)
            readFully(handle, cipherBuf, cipherLen)
            // GCM decrypt binds nonce(iv ‖ ci) + AAD(header ‖ ci): tag mismatch on any tamper/reorder.
            val plain = CryptoScheme.decryptChunk(key, iv, header.raw, ci, cipherBuf)
            val segStart = (readOff - chunkPlainStart).toInt().coerceAtLeast(0)
            val segEndExclusive = minOf(chunkPlainLen.toLong(), effectiveEnd - chunkPlainStart).toInt()
            out.write(plain, segStart, segEndExclusive - segStart)
            readOff = chunkPlainEnd
            ci++
        }
        return out.toByteArray()
    }

    /**
     * Read up to [CryptoScheme.HEADER_SIZE] bytes from offset 0. A short read or missing magic
     * → [LegacyCiphertextException] (covers pre-v2 CTR ciphertexts and truncated headers);
     * a full, magic-prefixed header is then strictly validated by [CryptoScheme.decodeHeader].
     */
    private fun readAndValidateHeader(handle: SeekableReadHandle): CryptoScheme.Header {
        handle.seek(0)
        val raw = ByteArray(CryptoScheme.HEADER_SIZE)
        var total = 0
        while (total < CryptoScheme.HEADER_SIZE) {
            val n = handle.read(raw, total, CryptoScheme.HEADER_SIZE - total)
            if (n <= 0) break
            total += n
        }
        if (total < CryptoScheme.HEADER_SIZE ||
            raw[0] != CryptoScheme.MAGIC[0] || raw[1] != CryptoScheme.MAGIC[1] ||
            raw[2] != CryptoScheme.MAGIC[2] || raw[3] != CryptoScheme.MAGIC[3]
        ) {
            throw LegacyCiphertextException("not an SPM2 ciphertext (short or missing magic header)")
        }
        return CryptoScheme.decodeHeader(raw)
    }

    /** Read exactly [count] bytes into [buf]; throws [EOFException] on truncation. */
    private fun readFully(handle: SeekableReadHandle, buf: ByteArray, count: Int) {
        var total = 0
        while (total < count) {
            val n = handle.read(buf, total, count - total)
            if (n <= 0) throw EOFException("truncated ciphertext: expected $count bytes, got $total")
            total += n
        }
    }
}
