package cn.vectory.syncplayer

import android.app.Application
import cn.vectory.syncplayer.security.AppLockManager
import dagger.hilt.android.HiltAndroidApp
import java.security.Security
import javax.inject.Inject

@HiltAndroidApp
class SyncPlayerApp : Application() {
    @Inject lateinit var appLockManager: AppLockManager

    override fun onCreate() {
        super.onCreate()
        registerX25519Aliases()
        appLockManager.register()
    }

    /**
     * Android Conscrypt 注册的算法名是 "XDH"，而 SSHJ 硬编码查找 "X25519"。
     * 通过 JCA 标准别名机制将 "X25519" 映射到 "XDH"，让 SSHJ 的 Curve25519
     * 密钥交换能正常使用 Conscrypt 的实现。
     */
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
