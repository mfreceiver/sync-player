package cn.vectory.syncplayer

import android.app.Application
import android.util.Log
import cn.vectory.syncplayer.security.AppLockManager
import cn.vectory.syncplayer.storage.volume.EncryptedMediaStore
import cn.vectory.syncplayer.sync.SyncEngine
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.security.Security
import javax.inject.Inject
import javax.inject.Provider

@HiltAndroidApp
class SyncPlayerApp : Application() {
    @Inject lateinit var appLockManager: AppLockManager

    /**
     * [Provider] 而非直接注入：让 [EncryptedMediaStore] 及其依赖（MediaCryptoMaster/Keystore）
     * 在后台协程首次取用时才构造，不占用 Application.onCreate 的启动路径。
     */
    @Inject lateinit var encryptedMediaStore: Provider<EncryptedMediaStore>
    @Inject lateinit var syncEngine: SyncEngine

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        registerX25519Aliases()
        appLockManager.register()
        purgeLegacyCiphertext()
    }

    /**
     * AEAD v2 断代收尾：清理升级前遗留的 v1（无认证 CTR）密文孤儿。
     *
     * DB 已由 destructive migration 清表，这些文件不再被任何 entity 引用，仅占用空间。
     * [EncryptedMediaStore.purgeLegacyIfNeeded] 通过卷根 marker 保证每个卷只执行一次，
     * 且会读 magic 保留 v2 文件，因此重复调用与已迁移库都是安全的。
     * 失败不影响启动 —— 下次启动会再试一次。
     */
    private fun purgeLegacyCiphertext() {
        appScope.launch {
            try {
                val removed = syncEngine.storageOperationMutex.withLock {
                    encryptedMediaStore.get().purgeLegacyIfNeeded()
                }
                if (removed > 0) Log.i(TAG, "legacy v1 ciphertext purge: removed $removed file(s)")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "legacy v1 ciphertext purge skipped", e)
            }
        }
    }

    /**
     * Android Conscrypt 注册的算法名是 "XDH"，而 SSHJ 硬编码查找 "X25519"。
     * 通过 JCA 标准别名机制将 "X25519" 映射到 "XDH"，让 SSHJ 的 Curve25519
     * 密钥交换能正常使用 Conscrypt 的实现。
     */
    private companion object {
        private const val TAG = "SyncPlayerApp"
    }

    private fun registerX25519Aliases() {
        for (provider in Security.getProviders()) {
            if (provider.getService("KeyPairGenerator", "XDH") != null) {
                provider.put("Alg.Alias.KeyPairGenerator.X25519", "XDH")
                provider.put("Alg.Alias.KeyAgreement.X25519", "XDH")
                provider.put("Alg.Alias.KeyFactory.X25519", "XDH")
                break
            }
        }
    }
}
