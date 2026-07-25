package cn.vectory.syncplayer.crypto

import cn.vectory.syncplayer.storage.volume.SeekableReadHandle
import javax.crypto.SecretKey
import kotlin.math.min

class RangeDecryptor(
    private val key: SecretKey,
    private val iv: ByteArray,
    private val size: Long,
) {
    private val cipherThreadLocal = ThreadLocal.withInitial {
        javax.crypto.Cipher.getInstance("AES/CTR/NoPadding")
    }

    fun readRange(handle: SeekableReadHandle, offset: Long, requestedLen: Int): ByteArray {
        require(offset >= 0 && requestedLen >= 0) { "offset and requestedLen must be non-negative" }
        if (offset >= size || requestedLen == 0) return ByteArray(0)
        val effectiveLen = min(requestedLen.toLong(), size - offset).toInt()
        val startBlock = offset / 16
        val blockAlignedStart = startBlock * 16
        val skip = (offset - blockAlignedStart).toInt()
        // cipherLen is the aligned ciphertext size needed, computed with Long to avoid Int overflow
        val cipherLen = ((skip.toLong() + effectiveLen + 15) / 16) * 16
        if (cipherLen > Int.MAX_VALUE) {
            throw IllegalArgumentException("Requested range too large, would overflow Int")
        }
        val cipherLenInt = cipherLen.toInt()
        val cipherBuf = ByteArray(cipherLenInt)
        handle.seek(blockAlignedStart)
        var totalRead = 0
        while (totalRead < cipherLenInt) {
            val n = handle.read(cipherBuf, totalRead, cipherLenInt - totalRead)
            if (n <= 0) {
                // If we got no bytes before reaching expected full read, it's a truncation error
                if (totalRead < skip + effectiveLen) {
                    throw java.io.EOFException("Truncated ciphertext: expected at least ${skip + effectiveLen} bytes, got $totalRead")
                }
                break
            }
            totalRead += n
        }
        // Decrypt using the ThreadLocal cipher
        val cipher = requireNotNull(cipherThreadLocal.get()) { "Cipher instance not available for decrypt" }
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key, counterBlock(startBlock))
        val plain = cipher.doFinal(cipherBuf, 0, totalRead)
        return plain.copyOfRange(skip, skip + effectiveLen)
    }

    private fun counterBlock(startBlock: Long): javax.crypto.spec.IvParameterSpec {
        val blk = java.nio.ByteBuffer.allocate(16).order(java.nio.ByteOrder.BIG_ENDIAN)
            .put(iv).putLong(startBlock).array()
        return javax.crypto.spec.IvParameterSpec(blk)
    }
}
