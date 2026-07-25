package cn.vectory.syncplayer.crypto

import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.nio.ByteBuffer
import java.nio.ByteOrder

object CryptoScheme {
    private const val IV_LEN = 8             // 64-bit nonce
    private const val BLOCK = 16
    private const val GCM_TAG = 16
    private const val TRANSFORM = "AES/CTR/NoPadding"
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

    fun newIv(): ByteArray = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }

    /** CTR 加密写流：写入明文 → 内部产出密文写入 [sink]。顺序写，用于下载即加密。 */
    fun encryptStream(key: SecretKey, iv: ByteArray, sink: OutputStream): OutputStream {
        val cipher = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.ENCRYPT_MODE, key, counterBlock(iv, 0L))
        }
        return CipherOutputStream(sink, cipher)
    }

    /**
     * 从 [startBlockIndex] 起顺序解密 [ciphertext] 的 [off]/[len] 区间，返回从 startBlock*16 计的明文
     * （长度 = len；区间外的块仍被解密为无意义字节，调用方按需切片）。
     * 每次 onRead 调用新建 Cipher → 无状态、线程安全。
     */
    fun decryptBlockAlignedRange(
        key: SecretKey, iv: ByteArray, startBlockIndex: Long,
        ciphertext: ByteArray, off: Int, len: Int
    ): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, key, counterBlock(iv, startBlockIndex))
        }
        return cipher.doFinal(ciphertext, off, len)
    }

    /** 构造 16 字节计数块：IV(8) ‖ counter(8 大端)。 */
    private fun counterBlock(iv: ByteArray, counter: Long): IvParameterSpec {
        require(iv.size == IV_LEN)
        val blk = ByteBuffer.allocate(BLOCK).order(ByteOrder.BIG_ENDIAN)
            .put(iv)
            .putLong(counter)
            .array()
        return IvParameterSpec(blk)
    }

    fun encryptThumb(thumbKey: SecretKey, plain: ByteArray): ByteArray {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, thumbKey, GCMParameterSpec(GCM_TAG * 8, iv))
        }
        return byteArrayOf(1) + iv + cipher.doFinal(plain)
    }

    fun decryptThumb(thumbKey: SecretKey, encoded: ByteArray): ByteArray {
        require(encoded.size >= 29 && encoded[0].toInt() == 1)
        val iv = encoded.copyOfRange(1, 13)
        val cipherBytes = encoded.copyOfRange(13, encoded.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, thumbKey, GCMParameterSpec(GCM_TAG * 8, iv))
        }
        return cipher.doFinal(cipherBytes)
    }
}