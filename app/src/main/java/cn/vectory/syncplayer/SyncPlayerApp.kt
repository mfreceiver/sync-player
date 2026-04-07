package cn.vectory.syncplayer

import android.app.Application
import cn.vectory.syncplayer.storage.StoragePaths
import dagger.hilt.android.HiltAndroidApp
import java.security.Security
import javax.inject.Inject

@HiltAndroidApp
class SyncPlayerApp : Application() {
    @Inject lateinit var debugLogger: cn.vectory.syncplayer.debug.DebugLogger
    @Inject lateinit var storagePaths: StoragePaths

    override fun onCreate() {
        super.onCreate()
        Security.addProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
        storagePaths.migrateToUserdataLayout()
        debugLogger.init()
    }
}
