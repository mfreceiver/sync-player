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
    private var consumptionState = NotifyIntentConsumptionState()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        enableEdgeToEdge()
        consumptionState = consumptionState.restoredFrom(
            savedConsumed = savedInstanceState?.getBoolean(KEY_INTENT_CONSUMED, false) ?: false,
            savedIdentity = savedInstanceState?.getString(KEY_CONSUMED_IDENTITY)
        )
        if (shouldDeliverNotificationIntentOnCreate(
                isNotification = isNotificationIntent(intent),
                savedConsumed = consumptionState.consumed,
                savedConsumedIdentity = consumptionState.consumedIdentity,
                currentIdentity = notificationIdentityOf(intent)
            )
        ) {
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
                            consumptionState = consumptionState.afterDelivery(
                                notificationIdentityOf(notificationIntent)
                            )
                            notificationIntent?.removeExtra("navigateTo")
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
        outState.putBoolean(KEY_INTENT_CONSUMED, consumptionState.consumed)
        outState.putString(KEY_CONSUMED_IDENTITY, consumptionState.consumedIdentity)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumptionState = NotifyIntentConsumptionState()
        if (isNotificationIntent(intent)) {
            pendingIntent.value = intent
        }
    }

    companion object {
        internal const val KEY_INTENT_CONSUMED = "cn.vectory.syncplayer.INTENT_CONSUMED"
        internal const val KEY_CONSUMED_IDENTITY =
            "cn.vectory.syncplayer.INTENT_CONSUMED_IDENTITY"
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

/**
 * Content identity of a notification intent, computed from the navigation-relevant extras
 * (`navigateTo`, `notification_relation_id`, `notification_local_base_path`).
 *
 * Returns null for null or non-notification intents. Identity is a normalized
 * length-prefixed string (collision-free, unlike a packed `Int` hash), captured at
 * consumption time before `navigateTo` is stripped, so a rebuilt activity can distinguish
 * "the same notification I already consumed" from "a genuinely new notification arriving
 * with stale saved state".
 */
internal fun notificationIdentityOf(intent: Intent?): String? {
    val navigateTo = intent?.getStringExtra("navigateTo") ?: return null
    val relationId = intent.getLongExtra("notification_relation_id", 0L)
    val basePath = intent.getStringExtra("notification_local_base_path") ?: ""
    return "${navigateTo.length}:$navigateTo|$relationId|${basePath.length}:$basePath"
}

/**
 * Save/restore state for notification intent consumption. The identity must survive
 * consecutive rebuild cycles: [restoredFrom] writes the saved identity back into the
 * field-backed state so that a later [onSaveInstanceState] persists it again instead of
 * degrading to the legacy no-identity form.
 */
internal data class NotifyIntentConsumptionState(
    val consumed: Boolean = false,
    val consumedIdentity: String? = null
) {
    fun restoredFrom(savedConsumed: Boolean, savedIdentity: String?): NotifyIntentConsumptionState =
        copy(consumed = savedConsumed, consumedIdentity = savedIdentity)

    fun afterDelivery(identity: String?): NotifyIntentConsumptionState =
        copy(consumed = true, consumedIdentity = identity)
}

/**
 * onCreate delivery decision for notification launch intents (pure state-machine core).
 *
 * Paths:
 * 1. Fresh launch (no consumed flag): notification intent is delivered.
 * 2. Rebuild replay: consumed flag and identity both restored and the current intent still
 *    carries the same extras → suppressed (anti-replay on activity rebuild). An intent
 *    whose `navigateTo` was already stripped is not a notification intent at all and is
 *    suppressed by the first rule.
 * 3. New notification with stale saved state: consumed flag says true but the current
 *    intent's identity differs from the saved one → delivered.
 * 4. Legacy saved state without identity: suppressed, matching the historical behavior.
 *
 * Delivery implies consumption even when [handleNotificationNavigation] no-ops (e.g.
 * `navigateTo=syncPreview` with relationId <= 0): the malformed intent is static and can
 * never navigate, and leaving it pending would re-fire the no-op navigation on every
 * unlock; consuming it once is the safe terminal state.
 */
internal fun shouldDeliverNotificationIntentOnCreate(
    isNotification: Boolean,
    savedConsumed: Boolean,
    savedConsumedIdentity: String?,
    currentIdentity: String?
): Boolean = when {
    !isNotification -> false
    !savedConsumed -> true
    savedConsumedIdentity != null && currentIdentity != null &&
        currentIdentity != savedConsumedIdentity -> true
    else -> false
}
