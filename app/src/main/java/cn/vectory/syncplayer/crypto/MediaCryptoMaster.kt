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

/** 抽象 KEK，便于单元测试用软件替身。 */
interface KekProvider {
    fun wrap(mk: SecretKey): ByteArray
    fun unwrap(blob: ByteArray): SecretKey
}

@Singleton
class MediaCryptoMaster constructor(
    private val kekProvider: KekProvider,
    private val loadBlob: () -> ByteArray?,
    private val storeBlob: (ByteArray) -> Unit,
) {
    @Volatile private var cached: SecretKey? = null

    fun masterKey(): SecretKey = cached ?: synchronized(this) {
        cached ?: (loadBlob()?.let { kekProvider.unwrap(it) } ?: generateAndStore()).also { cached = it }
    }

    private fun generateAndStore(): SecretKey {
        val mk = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        storeBlob(kekProvider.wrap(mk))
        return mk
    }

    companion object { const val PREF_KEY = "media_master_wrapped" }
}

/** 真实 Keystore KEK 实现（Hilt 提供时用）。 */
class KeystoreKekProvider(
    private val context: Context,
) : KekProvider {
    private val alias = "syncplayer_media_kek"
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(alias)) {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build())
                generateKey()
            }
        }
        return (ks.getEntry(alias, null) as KeyStore.SecretKeyEntry).secretKey
    }
    override fun wrap(mk: SecretKey): ByteArray {
        // 不要在 ENCRYPT_MODE 传 GCMParameterSpec：KeyGenParameterSpec 默认
        // setRandomizedEncryptionRequired(true)，禁止 caller-provided IV，否则抛
        // InvalidAlgorithmParameterException。让 Keystore 自己生成 IV，再用 cipher.iv 取回。
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val iv = c.iv
        return iv + c.doFinal(mk.encoded)
    }
    override fun unwrap(blob: ByteArray): SecretKey {
        val iv = blob.copyOfRange(0, 12); val ct = blob.copyOfRange(12, blob.size)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        return SecretKeySpec(c.doFinal(ct), "AES")
    }
}