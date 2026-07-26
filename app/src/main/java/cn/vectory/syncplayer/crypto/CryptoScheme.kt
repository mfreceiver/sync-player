package cn.vectory.syncplayer.crypto

import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Crypto primitives for sync-player media at rest.
 *
 * Format v2: per-file chunked AEAD (`AES/GCM/NoPadding`). See
 * `/tmp/opencode/sync-player-aead-spec.md` for the authoritative layout:
 *
 * ```
 * [Header 16] [Chunk 0] ... [Chunk N-1]
 *   Header(plain): magic "SPM2" | version 2 | rsv 0 | chunkSizeKiB 64 | plaintextLength(i64 BE)
 *   Chunk i:        AES/GCM( key, nonce = iv(8) ‖ i(4 BE), aad = header(16) ‖ i(4 BE) ) → ct ‖ tag(16)
 * ```
 *
 * Public surface preserved for other lanes:
 *  - [newIv] (8 bytes), [deriveContentKey], [deriveThumbKey] — unchanged.
 *  - Thumbnail format ([encryptThumb] / [decryptThumb]) — unchanged (separate GCM blob).
 */
object CryptoScheme {
    // ---- v2 chunked AEAD format constants ----

    /** Per-file random nonce prefix length; stored in `VideoItemEntity.iv`. */
    const val IV_LEN = 8

    /** ASCII `"SPM2"` — magic identifying the v2 chunked-AEAD format. */
    val MAGIC = byteArrayOf(0x53, 0x50, 0x4D, 0x32)

    /** Header byte 4: format version. */
    const val FORMAT_VERSION = 2

    /** Header size in bytes (also the prefix bound into every chunk's AAD). */
    const val HEADER_SIZE = 16

    /** Plaintext chunk size: 64 KiB. */
    const val CHUNK_SIZE = 64 * 1024            // 65536

    /** Chunk size as encoded in the header (byte 6..7, BE u16, in KiB). */
    const val CHUNK_SIZE_KIB = 64

    /** GCM authentication tag length. */
    const val GCM_TAG_BITS = 128

    /** GCM tag length in bytes. */
    const val GCM_TAG_BYTES = GCM_TAG_BITS / 8  // 16

    /** GCM nonce length = iv(8) ‖ chunkIndex(4). */
    const val NONCE_LEN = IV_LEN + 4            // 12

    /** On-disk stride of every full chunk: plaintext(CHUNK) ‖ tag(16). */
    val CHUNK_STRIDE: Long = (CHUNK_SIZE + GCM_TAG_BYTES).toLong()   // 65552

    private const val TRANSFORM_AEAD = "AES/GCM/NoPadding"

    private val HKDF_INFO_CONTENT = "syncplayer/content".toByteArray()
    private val HKDF_INFO_THUMB = "syncplayer/thumb".toByteArray()

    fun deriveContentKey(master: SecretKey): SecretKey = hkdf(master, HKDF_INFO_CONTENT)
    fun deriveThumbKey(master: SecretKey): SecretKey = hkdf(master, HKDF_INFO_THUMB)

    private fun hkdf(master: SecretKey, info: ByteArray): SecretKey {
        // HKDF-Extract: IKM = raw AES key bytes, salt = zero(32)
        val ikm = master.encoded
        val salt = ByteArray(32)
        val prkMac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(salt, "HmacSHA256")) }
        val prkBytes = prkMac.doFinal(ikm)
        // Expand single block: T(1) = HMAC(prk, info || 0x01), L=32
        val expand = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(prkBytes, "HmacSHA256")) }
        val okm = expand.doFinal(info + byteArrayOf(0x01))
        return SecretKeySpec(okm, "AES")
    }

    /** 8-byte random per-file IV (nonce prefix). */
    fun newIv(): ByteArray = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }

    // ---- length math ----

    /**
     * Implementation cap on chunk count: [Int.MAX_VALUE] chunks × 64 KiB ≈ 128 TiB of plaintext.
     *
     * The wire format itself supports 2^32 chunks (256 TiB, spec §Nonce), but the read path
     * handles chunk indices as `Int`, so we cap `N` at [Int.MAX_VALUE]. Every entry point that
     * can produce or reason about a ciphertext ([cipherLengthFor], [encodeHeader], [encryptStream])
     * rejects inputs exceeding this cap up-front via [requireWithinCap].
     */
    private val MAX_CHUNKS: Long = Int.MAX_VALUE.toLong()

    /**
     * Number of plaintext chunks for [plainLen]; 0 iff `plainLen == 0`.
     *
     * Uses overflow-free ceiling division. The previous `(plainLen + CHUNK_SIZE - 1) / CHUNK_SIZE`
     * form overflowed `plainLen + CHUNK_SIZE - 1` to a negative value when `plainLen` was near
     * [Long.MAX_VALUE], which in turn made [cipherLengthFor] return a negative (or silently
     * wrong) length and bypass the cap check. Throws [IllegalArgumentException] for negative
     * input.
     */
    fun numChunks(plainLen: Long): Long {
        require(plainLen >= 0) { "plainLen must be non-negative: $plainLen" }
        // Overflow-free: plainLen/CHUNK_SIZE <= Long.MAX_VALUE/CHUNK_SIZE always fits, and the
        // conditional +1 cannot overflow because the quotient is well below Long.MAX_VALUE.
        return plainLen / CHUNK_SIZE + if (plainLen % CHUNK_SIZE != 0L) 1L else 0L
    }

    /**
     * Total on-disk ciphertext length for [plainLen] plaintext bytes:
     * `HEADER + N*TAG + plainLen` where `N = numChunks(plainLen)`.
     *
     * Contract:
     *  - **Never throws for legal input** `plainLen ∈ [0, MAX_CHUNKS * CHUNK_SIZE]` (≈ 128 TiB):
     *    returns the exact cipher length. Used by other lanes (e.g. SyncEngine space pre-check)
     *    which rely on this guarantee and guard the throw for inputs outside the legal range.
     *  - `plainLen < 0` or chunk count > [MAX_CHUNKS]: throws [IllegalArgumentException].
     *  - The `HEADER + N*TAG + plainLen` sum is computed with checked arithmetic
     *    ([Math.multiplyExact] / [Math.addExact]) as a defensive guard; for `N <= MAX_CHUNKS`
     *    the sum cannot overflow Long (max ≈ 128 TiB), so the [ArithmeticException] branch is
     *    unreachable on legal input.
     */
    fun cipherLengthFor(plainLen: Long): Long {
        require(plainLen >= 0) { "plainLen must be non-negative: $plainLen" }
        val n = numChunks(plainLen)
        require(n <= MAX_CHUNKS) {
            "plaintext too large: $plainLen bytes needs $n chunks (impl cap $MAX_CHUNKS, ~128 TiB)"
        }
        val tagBytes = Math.multiplyExact(n, GCM_TAG_BYTES.toLong())
        return Math.addExact(Math.addExact(HEADER_SIZE.toLong(), tagBytes), plainLen)
    }

    /**
     * Throws [IllegalArgumentException] if [plainLen] is negative or requires more than
     * [MAX_CHUNKS] chunks. Shared by [encodeHeader] and [encryptStream] so the cap is enforced
     * uniformly at every entry point — not only inside [cipherLengthFor].
     */
    private fun requireWithinCap(plainLen: Long) {
        require(plainLen >= 0) { "plain length must be non-negative: $plainLen" }
        require(numChunks(plainLen) <= MAX_CHUNKS) {
            "plaintext too large: $plainLen bytes exceeds impl cap (~128 TiB, $MAX_CHUNKS chunks)"
        }
    }

    // ---- header ----

    /** Encode the 16-byte v2 header for [plaintextLength]. */
    fun encodeHeader(plaintextLength: Long): ByteArray {
        requireWithinCap(plaintextLength)
        return ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
            .put(MAGIC)                            // 0..3  magic "SPM2"
            .put(FORMAT_VERSION.toByte())          // 4     version
            .put(0.toByte())                       // 5     reserved
            .putShort(CHUNK_SIZE_KIB.toShort())    // 6..7  chunkSizeKiB (BE u16)
            .putLong(plaintextLength)              // 8..15 plaintextLength (BE i64)
            .array()
    }

    /** Parsed v2 header. [raw] is the exact 16 bytes bound into each chunk's AAD. */
    class Header(val raw: ByteArray, val plaintextLength: Long)

    /**
     * Decode & strictly validate a 16-byte header. [buf] must contain at least [HEADER_SIZE] bytes.
     * Throws [LegacyCiphertextException] if the magic is absent; [IOException] on any other
     * structural inconsistency.
     */
    fun decodeHeader(buf: ByteArray): Header {
        require(buf.size >= HEADER_SIZE) { "header buffer too small: ${buf.size}" }
        val raw = buf.copyOfRange(0, HEADER_SIZE)
        if (raw[0] != MAGIC[0] || raw[1] != MAGIC[1] || raw[2] != MAGIC[2] || raw[3] != MAGIC[3]) {
            throw LegacyCiphertextException("not an SPM2 ciphertext (bad magic)")
        }
        val version = raw[4].toInt() and 0xFF
        if (version != FORMAT_VERSION) {
            throw IOException("unsupported format version: $version")
        }
        // raw[5] reserved = 0 (spec §Header). Strict check, NOT forward-compat tolerance:
        // for zero-byte files there are no chunks, so the header is never bound as GCM AAD and
        // byte 5 would otherwise be freely mutable without detection. Rejecting any non-zero
        // value closes that integrity gap and matches the spec's "保留 = 0".
        if (raw[5].toInt() != 0) {
            throw IOException("reserved byte must be 0 (got 0x${(raw[5].toInt() and 0xFF).toString(16)})")
        }
        val chunkSizeKiB = ((raw[6].toInt() and 0xFF) shl 8) or (raw[7].toInt() and 0xFF)
        if (chunkSizeKiB != CHUNK_SIZE_KIB) {
            throw IOException("unsupported chunk size: ${chunkSizeKiB} KiB")
        }
        val plain = ByteBuffer.wrap(raw, 8, 8).order(ByteOrder.BIG_ENDIAN).long
        if (plain < 0) throw IOException("negative plaintext length in header")
        return Header(raw, plain)
    }

    // ---- per-chunk crypto primitives ----

    /** Per-chunk GCM nonce: `iv(8) ‖ chunkIndex(4 BE)`. Unique across a file. */
    fun nonceForChunk(iv: ByteArray, chunkIndex: Int): ByteArray {
        require(iv.size == IV_LEN) { "iv must be $IV_LEN bytes" }
        require(chunkIndex >= 0) { "chunkIndex must be non-negative" }
        return ByteBuffer.allocate(NONCE_LEN).order(ByteOrder.BIG_ENDIAN)
            .put(iv)
            .putInt(chunkIndex)
            .array()
    }

    /** Per-chunk AAD: `header(16) ‖ chunkIndex(4 BE)`. Binds format/size/length/order. */
    fun aadForChunk(header: ByteArray, chunkIndex: Int): ByteArray {
        require(header.size >= HEADER_SIZE) { "header too small for AAD: ${header.size}" }
        require(chunkIndex >= 0) { "chunkIndex must be non-negative" }
        val aad = ByteArray(HEADER_SIZE + 4)
        System.arraycopy(header, 0, aad, 0, HEADER_SIZE)
        ByteBuffer.wrap(aad, HEADER_SIZE, 4).order(ByteOrder.BIG_ENDIAN).putInt(chunkIndex)
        return aad
    }

    /** Encrypt one plaintext chunk; output = `ciphertext(len) ‖ tag(16)`. */
    fun encryptChunk(
        key: SecretKey, iv: ByteArray, header: ByteArray, chunkIndex: Int,
        plain: ByteArray, off: Int, len: Int,
    ): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM_AEAD).apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonceForChunk(iv, chunkIndex)))
            updateAAD(aadForChunk(header, chunkIndex))
        }
        return cipher.doFinal(plain, off, len)
    }

    /** Decrypt one ciphertext+tag chunk; throws (`AEADBadTagException`) on tag/AAD mismatch. */
    fun decryptChunk(
        key: SecretKey, iv: ByteArray, header: ByteArray, chunkIndex: Int,
        cipherWithTag: ByteArray, off: Int = 0, len: Int = cipherWithTag.size,
    ): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM_AEAD).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonceForChunk(iv, chunkIndex)))
            updateAAD(aadForChunk(header, chunkIndex))
        }
        return cipher.doFinal(cipherWithTag, off, len)
    }

    /**
     * v2 encrypting sink: writes the header immediately, buffers up to [CHUNK_SIZE] plaintext bytes,
     * and seals each full chunk with AES/GCM as it fills. See [ChunkedEncryptSink] for the
     * close-time contract (final short chunk + length-mismatch check).
     */
    fun encryptStream(key: SecretKey, iv: ByteArray, plainLength: Long, sink: OutputStream): OutputStream {
        require(iv.size == IV_LEN) { "iv must be $IV_LEN bytes" }
        requireWithinCap(plainLength)
        return ChunkedEncryptSink(key, iv, plainLength, sink)
    }

    // ---- thumbnail (unchanged GCM format) ----

    fun encryptThumb(thumbKey: SecretKey, plain: ByteArray): ByteArray {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, thumbKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        return byteArrayOf(1) + iv + cipher.doFinal(plain)
    }

    fun decryptThumb(thumbKey: SecretKey, encoded: ByteArray): ByteArray {
        require(encoded.size >= 29 && encoded[0].toInt() == 1)
        val iv = encoded.copyOfRange(1, 13)
        val cipherBytes = encoded.copyOfRange(13, encoded.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, thumbKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        return cipher.doFinal(cipherBytes)
    }
}
