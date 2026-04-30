package cn.vectory.syncplayer

import android.app.Application
import cn.vectory.syncplayer.security.AppLockManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class SyncPlayerApp : Application() {
    @Inject lateinit var appLockManager: AppLockManager

    override fun onCreate() {
        super.onCreate()
        appLockManager.register()
    }
}
