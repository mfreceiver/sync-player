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
        Security.insertProviderAt(
            org.bouncycastle.jce.provider.BouncyCastleProvider(),
            Security.getProviders().size + 1
        )
        appLockManager.register()
    }
}
