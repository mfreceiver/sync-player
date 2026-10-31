package cn.vectory.syncplayer.crypto

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * v2 加密方案（docs/ocmar/specs/2026-10-31-external-sd-encrypted-storage-v2.md「密钥与 crypto」）。
 *
 * - 正文：AES/CTR/NoPadding；8B 随机 IV ‖ 8B 大端 block counter（counter = offset/16，Long 计算）。
 *   每 attempt 新 IV，不做确定性派生。为降低跨全库 64 位 nonce 生日碰撞风险，CTR_V2 用
 *   128 位 generation 作为 HKDF 域隔离输入派生每 attempt 内容 key（与 thumb 域分离）——
 *   这是新格式，与历史 CTR（全局 contentKey）不做原地互通。
 * - 缩略图：AES/GCM/NoPadding，version 1B + 随机 nonce 12B + 密文 + tag 16B（共 n+29），
 *   上限 1 MiB；完整认证后才输出明文。
 *
 * 密钥真相源是 [MediaCryptoMaster] 的 256 位媒体主密钥（HKDF 用 master.encoded；
 * Keystore KEK 本身 encoded 为空，不参与 HKDF）。
 */
object CryptoScheme {
    const val IV_LEN = 8
    const val BLOCK = 16
    const val GCM_TAG_LEN = 16
    const val GCM_NONCE_LEN = 12
    const val THUMB_HEADER_LEN = 1 + GCM_NONCE_LEN
    const val THUMB_MAX_PLAIN = 1 shl 20 // 1 MiB
    private const val TRANSFORM = "AES/CTR/NoPadding"
    private val HKDF_INFO_CONTENT = "syncplayer/content/v2".toByteArray()
    private val HKDF_INFO_THUMB = "syncplayer/thumb/v2".toByteArray()

    /** media master → 缩略图域 key（无 generation 参与）。 */
    fun deriveThumbKey(master: SecretKey): SecretKey = hkdf(master.encoded, HKDF_INFO_THUMB)

    /** media master + 128 位 generation → 该 attempt 正文内容 key（域隔离）。 */
    fun deriveContentKey(master: SecretKey, generation: String): SecretKey =
        hkdf(master.encoded, HKDF_INFO_CONTENT + generation.toByteArray(Charsets.UTF_8))

    private fun hkdf(ikm: ByteArray, info: ByteArray): SecretKey {
        // HKDF-Extract: salt = zero(32)；Expand 单块：T(1) = HMAC(prk, info ‖ 0x01)，L=32
        val salt = ByteArray(32)
        val prkMac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(salt, "HmacSHA256")) }
        val prk = prkMac.doFinal(ikm)
        val expand = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(prk, "HmacSHA256")) }
        return SecretKeySpec(expand.doFinal(info + byteArrayOf(0x01)), "AES")
    }

    fun newIv(): ByteArray = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }

    fun newGeneration(): String {
        val token = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return token.joinToString("") { "%02x".format(it) }
    }

    fun newCtrCipher(key: SecretKey, iv: ByteArray, startBlock: Long, encrypt: Boolean): Cipher {
        require(iv.size == IV_LEN) { "iv must be $IV_LEN bytes" }
        return Cipher.getInstance(TRANSFORM).apply {
            init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, key, counterBlock(iv, startBlock))
        }
    }

    /**
     * 从 [startBlockIndex] 起顺序解密 [ciphertext] 的 [off]/[len] 区间。每次调用新建
     * Cipher → 无状态、线程安全。
     */
    fun decryptBlockAlignedRange(
        key: SecretKey, iv: ByteArray, startBlockIndex: Long,
        ciphertext: ByteArray, off: Int, len: Int
    ): ByteArray = newCtrCipher(key, iv, startBlockIndex, encrypt = false).doFinal(ciphertext, off, len)

    /** 构造 16 字节计数块：IV(8) ‖ counter(8 大端)。 */
    private fun counterBlock(iv: ByteArray, counter: Long): IvParameterSpec {
        val blk = ByteBuffer.allocate(BLOCK).order(ByteOrder.BIG_ENDIAN).put(iv).putLong(counter).array()
        return IvParameterSpec(blk)
    }

    fun encryptThumb(thumbKey: SecretKey, plain: ByteArray): ByteArray {
        require(plain.size <= THUMB_MAX_PLAIN) { "thumb too large" }
        val nonce = ByteArray(GCM_NONCE_LEN).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, thumbKey, GCMParameterSpec(GCM_TAG_LEN * 8, nonce))
        }
        return byteArrayOf(1) + nonce + cipher.doFinal(plain)
    }

    /** 完整 GCM 认证后才返回明文；篡改/截断抛 AEADBadTagException/异常，绝不输出部分明文。 */
    fun decryptThumb(thumbKey: SecretKey, encoded: ByteArray): ByteArray {
        require(encoded.size >= THUMB_HEADER_LEN + GCM_TAG_LEN && encoded[0].toInt() == 1) {
            "invalid thumb blob"
        }
        val nonce = encoded.copyOfRange(1, 1 + GCM_NONCE_LEN)
        val cipherBytes = encoded.copyOfRange(THUMB_HEADER_LEN, encoded.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, thumbKey, GCMParameterSpec(GCM_TAG_LEN * 8, nonce))
        }
        return cipher.doFinal(cipherBytes)
    }

    /** 仅供测试/诊断引用的常量（ChunkedEncryptSink 缓冲与加解密单元一致）。 */
    const val CTR_SINK_BUFFER = 256 * 1024
}

/** 无: Kotlin ByteArray 自带 `+` 运算符，直接用于 header 拼接。 */
