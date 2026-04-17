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
        Security.addProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
        appLockManager.register()
    }
}
