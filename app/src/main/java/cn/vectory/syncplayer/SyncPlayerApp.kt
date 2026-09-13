package cn.vectory.syncplayer

import android.app.Application
import android.util.Log
import cn.vectory.syncplayer.security.AppLockManager
import cn.vectory.syncplayer.storage.MediaFileStore
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
     * [Provider] 而非直接注入：让 [MediaFileStore] 在后台协程首次取用时才构造，
     * 不占用 Application.onCreate 的启动路径。
     */
    @Inject lateinit var mediaFileStore: Provider<MediaFileStore>
    @Inject lateinit var syncEngine: SyncEngine

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        registerX25519Aliases()
        appLockManager.register()
        purgeUpgradeOrphans()
    }

    /**
     * v4 去加密断代收尾：清理升级前遗留的加密媒体孤儿。
     *
     * DB 已由破坏性迁移清空，这些文件不再被任何 entity 引用，仅占用空间。
     * [MediaFileStore.purgeOrphansIfNeeded] 通过 marker 保证只执行一次。
     * 失败不影响启动 —— 下次启动会再试一次。
     */
    private fun purgeUpgradeOrphans() {
        appScope.launch {
            try {
                val removed = syncEngine.storageOperationMutex.withLock {
                    mediaFileStore.get().purgeOrphansIfNeeded()
                }
                if (removed > 0) Log.i(TAG, "upgrade orphan purge: removed $removed file(s)")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "upgrade orphan purge skipped", e)
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
