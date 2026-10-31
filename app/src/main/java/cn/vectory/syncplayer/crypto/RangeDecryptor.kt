package cn.vectory.syncplayer.crypto

import cn.vectory.syncplayer.storage.volume.SeekableReadHandle
import java.io.EOFException
import javax.crypto.SecretKey
import kotlin.math.min

/**
 * CTR 随机读解密器：对齐 offset/16（不整文件解密），块内 skip，Long 计算 counter。
 *
 * 每次 [readRange] 新建 Cipher（无状态、线程安全）；handle 的 seek/read 串行性由
 * 调用方（[DecryptingFdCallback]）持锁保证——避免共享 seek 指针竞争。
 * 长偏移、EOF、负值、零读、截断（EOFException）均覆盖。
 */
class RangeDecryptor(
    private val key: SecretKey,
    private val iv: ByteArray,
    private val size: Long,
) {
    init {
        require(iv.size == CryptoScheme.IV_LEN) { "iv must be ${CryptoScheme.IV_LEN} bytes" }
    }

    fun readRange(handle: SeekableReadHandle, offset: Long, requestedLen: Int): ByteArray {
        require(offset >= 0 && requestedLen >= 0) { "offset and requestedLen must be non-negative" }
        if (offset >= size || requestedLen == 0) return ByteArray(0)
        val effectiveLen = min(requestedLen.toLong(), size - offset).toInt()
        val startBlock = offset / CryptoScheme.BLOCK
        val blockAlignedStart = startBlock * CryptoScheme.BLOCK
        val skip = (offset - blockAlignedStart).toInt()
        val cipherLen = ((skip.toLong() + effectiveLen + CryptoScheme.BLOCK - 1) /
            CryptoScheme.BLOCK) * CryptoScheme.BLOCK
        check(cipherLen <= Int.MAX_VALUE) { "requested range too large" }
        val cipherLenInt = cipherLen.toInt()
        val cipherBuf = ByteArray(cipherLenInt)
        handle.seek(blockAlignedStart)
        var totalRead = 0
        while (totalRead < cipherLenInt) {
            val n = handle.read(cipherBuf, totalRead, cipherLenInt - totalRead)
            if (n <= 0) {
                if (totalRead < skip + effectiveLen) {
                    throw EOFException(
                        "truncated ciphertext: expected at least ${skip + effectiveLen}, got $totalRead"
                    )
                }
                break
            }
            totalRead += n
        }
        val plain = CryptoScheme.decryptBlockAlignedRange(key, iv, startBlock, cipherBuf, 0, totalRead)
        return plain.copyOfRange(skip, skip + effectiveLen)
    }
}
