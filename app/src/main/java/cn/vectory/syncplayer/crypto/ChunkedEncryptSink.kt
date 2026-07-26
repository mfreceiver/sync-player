package cn.vectory.syncplayer.crypto

import java.io.IOException
import java.io.OutputStream
import javax.crypto.SecretKey

/**
 * AEAD v2 encrypting output stream (see `/tmp/opencode/sync-player-aead-spec.md`).
 *
 * Plaintext written sequentially is buffered into [CryptoScheme.CHUNK_SIZE]-byte chunks; each
 * chunk is sealed with `AES/GCM/NoPadding` under a per-chunk nonce (`iv ‖ chunkIndex`) and AAD
 * (`header ‖ chunkIndex`). The 16-byte header is written to [sink] immediately on construction,
 * so even a partially-written file begins with the SPM2 magic and truncation is detectable on read.
 *
 * Close contract:
 *  - if the number of plaintext bytes actually written ≠ [plainLength], [close] throws
 *    [IOException] **before** sealing the buffered tail chunk (refuses to leave a
 *    decryptable-but-wrong-length file; only the header may have been written);
 *  - otherwise the final short chunk (if any) is flushed before [sink] is closed;
 *  - [sink] is always closed, even on failure (including construction-time header-write
 *    failure, where the caller never receives this instance).
 *
 * Not thread-safe (single-writer contract, matching [OutputStream]).
 */
internal class ChunkedEncryptSink(
    private val key: SecretKey,
    private val iv: ByteArray,
    private val plainLength: Long,
    private val sink: OutputStream,
) : OutputStream() {

    private val header: ByteArray = CryptoScheme.encodeHeader(plainLength)
    private val buf = ByteArray(CryptoScheme.CHUNK_SIZE)
    private var bufPos = 0
    private var chunkIndex = 0
    private var written = 0L
    private var closed = false

    init {
        // Write header first so partial files still self-identify as v2 (truncation detected on read).
        // If the write fails, the caller never receives `this` and so cannot call close(); close the
        // underlying sink here to avoid leaking it, then rethrow the original failure (any close
        // error is attached as a suppressed exception so the root cause stays primary).
        try {
            sink.write(header)
        } catch (e: Throwable) {
            try {
                sink.close()
            } catch (closeEx: Throwable) {
                e.addSuppressed(closeEx)
            }
            throw e
        }
    }

    override fun write(b: Int) {
        ensureOpen()
        buf[bufPos] = b.toByte()
        bufPos++
        written++
        if (bufPos == CryptoScheme.CHUNK_SIZE) flushFullChunk()
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        require(off >= 0 && len >= 0 && off + len <= b.size) { "invalid write args: off=$off len=$len cap=${b.size}" }
        ensureOpen()
        var cur = off
        var remaining = len
        while (remaining > 0) {
            val space = CryptoScheme.CHUNK_SIZE - bufPos
            val n = minOf(space, remaining)
            System.arraycopy(b, cur, buf, bufPos, n)
            bufPos += n
            cur += n
            remaining -= n
            written += n
            if (bufPos == CryptoScheme.CHUNK_SIZE) flushFullChunk()
        }
    }

    private fun flushFullChunk() {
        sink.write(CryptoScheme.encryptChunk(key, iv, header, chunkIndex, buf, 0, CryptoScheme.CHUNK_SIZE))
        bufPos = 0
        chunkIndex++
    }

    override fun flush() {
        ensureOpen()
        sink.flush()
    }

    override fun close() {
        if (closed) return
        closed = true
        var err: IOException? = null
        try {
            // Length check FIRST: if the actual byte count disagrees with the declared plaintext
            // length, refuse to seal the buffered tail chunk. Writing it would leave a
            // self-consistent v2 file whose plaintext length disagrees with what the caller
            // actually wrote — an undetectable corruption. Leaving a truncated (header-only)
            // file is preferable to a decryptable wrong-length one; the underlying sink is still
            // closed below on every path.
            if (written != plainLength) {
                throw IOException("plaintext length mismatch: declared=$plainLength actual=$written")
            }
            // Flush the final chunk: only present when there are buffered bytes not yet sealed.
            // (When plainLength is an exact multiple of CHUNK_SIZE, every chunk was flushed inline
            // and bufPos == 0 here — no extra chunk is written. plainLength == 0 also lands here
            // with bufPos == 0, producing a header-only file as required by the spec.)
            if (bufPos > 0) {
                sink.write(CryptoScheme.encryptChunk(key, iv, header, chunkIndex, buf, 0, bufPos))
                bufPos = 0
                chunkIndex++
            }
        } catch (e: IOException) {
            err = e
        } catch (e: Throwable) {
            // Wrap any non-IO failure (e.g. AEAD/BCE) so callers see a uniform IOException.
            err = IOException("encrypt failure on close", e)
        }
        try {
            sink.close()
        } catch (e: Throwable) {
            if (err == null) err = IOException("sink close failed", e)
        }
        err?.let { throw it }
    }

    private fun ensureOpen() {
        check(!closed) { "stream closed" }
    }
}
