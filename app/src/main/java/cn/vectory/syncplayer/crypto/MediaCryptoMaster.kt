package cn.vectory.syncplayer.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/**
 * 媒体主密钥不可用（Keystore 失效/解包失败/磁盘损坏）。
 * TODO(B): 映射到 SyncErrorCode.KEY_UNAVAILABLE（枚举由实施者 B 冻结添加）。
 * 绝不静默换钥、删文件或清库。
 */
class MediaKeyUnavailableException(cause: Throwable? = null) : Exception(null, cause)

/** 抽象 KEK，便于单元测试用软件替身。 */
interface KekProvider {
    fun wrap(mk: SecretKey): ByteArray
    fun unwrap(blob: ByteArray): SecretKey
}

/**
 * 媒体主密钥单例：256 位随机 AES key，由独立 Android Keystore AES-GCM KEK 加密后落
 * 内部专用存储（blob 布局 iv(12) ‖ GCM(master.encoded)）。
 *
 * - 单一密钥真相源（本类），与远端凭证密钥逻辑隔离（独立别名/prefs）；
 * - 首次创建：blob 不存在时生成并存储；unmarshal 失败抛 [MediaKeyUnavailableException]，
 *   绝不静默换钥；
 * - 并发初始化：synchronized 单例锁；
 * - 媒体主密钥不是不可导出 KEK：HKDF 用 master.encoded（见 [CryptoScheme]）。
 */
@Singleton
class MediaCryptoMaster constructor(
    private val kekProvider: KekProvider,
    private val loadBlob: () -> ByteArray?,
    private val storeBlob: (ByteArray) -> Unit,
) {
    @Volatile
    private var cached: SecretKey? = null

    fun masterKey(): SecretKey = cached ?: synchronized(this) {
        cached ?: run {
            val blob = loadBlob()
            if (blob != null) {
                try {
                    kekProvider.unwrap(blob)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw MediaKeyUnavailableException(e)
                }
            } else {
                generateAndStore()
            }
        }.also { cached = it }
    }

    private fun generateAndStore(): SecretKey {
        val mk = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        try {
            storeBlob(kekProvider.wrap(mk))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // P1-10：持久化失败（含 commit()=false 抛出的 IOException）绝不缓存/返回该 key
            // ——否则重启后 loadBlob 读不到 blob 会静默换钥，全部外部密文永久不可解。
            throw MediaKeyUnavailableException(e)
        }
        return mk
    }

    companion object {
        const val PREFS_NAME = "media_master_blob"
        const val PREF_KEY = "media_master_wrapped"
    }
}

/** 真实 Keystore KEK 实现（Hilt 提供；独立别名，与凭证密钥隔离）。 */
class KeystoreKekProvider(
    @Suppress("unused") private val context: Context,
) : KekProvider {
    private val alias = "syncplayer_media_kek"
    private val lock = Object()

    private fun key(): SecretKey = synchronized(lock) {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(alias)) {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(
                    KeyGenParameterSpec.Builder(
                        alias,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
                generateKey()
            }
        }
        (ks.getEntry(alias, null) as KeyStore.SecretKeyEntry).secretKey
    }

    override fun wrap(mk: SecretKey): ByteArray {
        // P0-2：Keystore KEK 默认 randomizedEncryptionRequired=true，拒绝 caller-provided
        // GCM IV（InvalidAlgorithmParameterException/isForEncryption mismatch）。按设计文档
        // 密钥节：init(ENCRYPT_MODE, key) 不传 IV，由 Keystore 生成并回读 cipher.iv 存 blob。
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key())
        }
        val iv = c.iv ?: throw MediaKeyUnavailableException()
        return iv + c.doFinal(mk.encoded)
    }

    override fun unwrap(blob: ByteArray): SecretKey {
        if (blob.size < 12 + 16) throw MediaKeyUnavailableException()
        val iv = blob.copyOfRange(0, 12)
        val ct = blob.copyOfRange(12, blob.size)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        }
        return SecretKeySpec(c.doFinal(ct), "AES")
    }
}
