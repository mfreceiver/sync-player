package cn.vectory.syncplayer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
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
    private var isIntentConsumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        enableEdgeToEdge()
        isIntentConsumed = savedInstanceState?.getBoolean(KEY_INTENT_CONSUMED, false) ?: false
        if (!isIntentConsumed && isNotificationIntent(intent)) {
            pendingIntent.value = intent
        }
        setContent {
            SyncPlayerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    val notificationIntent by pendingIntent.collectAsStateWithLifecycle()
                    val isLocked by appLockManager.isLocked.collectAsStateWithLifecycle()

                    val permissionLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.RequestPermission()
                    ) { _ -> }
                    var hasRequestedNotificationPermission by rememberSaveable { mutableStateOf(false) }

                    LaunchedEffect(isLocked) {
                        if (!isLocked && !hasRequestedNotificationPermission) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                if (ContextCompat.checkSelfPermission(
                                        this@MainActivity,
                                        Manifest.permission.POST_NOTIFICATIONS
                                    ) != PackageManager.PERMISSION_GRANTED
                                ) {
                                    hasRequestedNotificationPermission = true
                                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }
                        }
                    }

                    LaunchedEffect(notificationIntent, isLocked) {
                        if (!isLocked && notificationIntent != null) {
                            handleNotificationNavigation(notificationIntent!!, navController)
                            notificationIntent?.removeExtra("navigateTo")
                            isIntentConsumed = true
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_INTENT_CONSUMED, isIntentConsumed)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        isIntentConsumed = false
        if (isNotificationIntent(intent)) {
            pendingIntent.value = intent
        }
    }

    companion object {
        internal const val KEY_INTENT_CONSUMED = "cn.vectory.syncplayer.INTENT_CONSUMED"
    }
}

internal fun isNotificationIntent(intent: Intent?): Boolean =
    intent?.getStringExtra("navigateTo") != null

internal fun handleNotificationNavigation(intent: Intent?, navController: NavHostController) {
    val navigateTo = intent?.getStringExtra("navigateTo") ?: return
    when (navigateTo) {
        "syncPreview" -> {
            val relationId = intent.getLongExtra("notification_relation_id", 0L)
            if (relationId > 0) {
                navController.navigate(Route.SyncPreview.create(relationId)) {
                    popUpTo(Route.Browser.route) { inclusive = false }
                    launchSingleTop = true
                }
            }
        }
        "browser" -> {
            val basePath = intent.getStringExtra("notification_local_base_path") ?: ""
            navController.navigate(Route.Browser.create(basePath)) {
                popUpTo(Route.Browser.route) { inclusive = false }
                launchSingleTop = true
            }
        }
    }
}
