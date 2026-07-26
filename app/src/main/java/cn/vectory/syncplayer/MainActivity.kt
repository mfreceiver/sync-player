package cn.vectory.syncplayer

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import cn.vectory.syncplayer.navigation.AppNavHost
import cn.vectory.syncplayer.navigation.Route
import cn.vectory.syncplayer.security.AppLockManager
import cn.vectory.syncplayer.security.BiometricAuthManager
import cn.vectory.syncplayer.security.LockScreenOverlay
import cn.vectory.syncplayer.ui.theme.SyncPlayerTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var appLockManager: AppLockManager
    @Inject lateinit var biometricAuthManager: BiometricAuthManager

    private val pendingIntent = MutableStateFlow<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        enableEdgeToEdge()
        if (savedInstanceState == null) pendingIntent.value = intent
        setContent {
            SyncPlayerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    val notificationIntent by pendingIntent.collectAsStateWithLifecycle()
                    val isLocked by appLockManager.isLocked.collectAsStateWithLifecycle()

                    LaunchedEffect(notificationIntent, isLocked) {
                        if (!isLocked && notificationIntent != null) {
                            handleNotificationNavigation(notificationIntent!!, navController)
                            pendingIntent.value = null
                        }
                    }

                    AppNavHost(navController = navController)
                    if (isLocked) {
                        LockScreenOverlay(
                            appLockManager = appLockManager,
                            biometricAuthManager = biometricAuthManager
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingIntent.value = intent
    }
}

private fun handleNotificationNavigation(intent: Intent?, navController: NavHostController) {
    val navigateTo = intent?.getStringExtra("navigateTo") ?: return
    when (navigateTo) {
        "syncPreview" -> {
            val relationId = intent.getLongExtra("notification_relation_id", 0L)
            if (relationId > 0) {
                navController.navigate(Route.SyncPreview.create(relationId))
            }
        }
        "browser" -> {
            val basePath = intent.getStringExtra("notification_local_base_path") ?: ""
            navController.navigate(Route.Browser.create(basePath))
        }
    }
}
