package cn.vectory.syncplayer

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import java.security.Security
import javax.inject.Inject

@HiltAndroidApp
class SyncPlayerApp : Application() {
    @Inject lateinit var debugLogger: cn.vectory.syncplayer.debug.DebugLogger

    override fun onCreate() {
        super.onCreate()
        Security.addProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
        debugLogger.init()
    }
}
