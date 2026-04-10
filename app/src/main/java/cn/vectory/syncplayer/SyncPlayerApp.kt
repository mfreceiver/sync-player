package cn.vectory.syncplayer

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import java.security.Security

@HiltAndroidApp
class SyncPlayerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Security.addProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
    }
}
