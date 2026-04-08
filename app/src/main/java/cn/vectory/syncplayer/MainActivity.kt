package cn.vectory.syncplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import cn.vectory.syncplayer.navigation.AppNavHost
import cn.vectory.syncplayer.navigation.Route
import cn.vectory.syncplayer.debug.DebugLogger
import javax.inject.Inject
import cn.vectory.syncplayer.ui.theme.SyncPlayerTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var debugLogger: DebugLogger

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SyncPlayerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainContent(debugLogger = debugLogger, activity = this@MainActivity)
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}

@Composable
private fun MainContent(debugLogger: DebugLogger, activity: MainActivity) {
    val navController = rememberNavController()
    var currentIntent by remember { mutableStateOf(activity.intent) }

    LaunchedEffect(activity) {
        activity.setOnNewIntentListener { intent ->
            currentIntent = intent
        }
    }

    LaunchedEffect(currentIntent) {
        handleNotificationNavigation(currentIntent, navController)
    }

    AppNavHost(debugLogger = debugLogger, navController = navController)
}

private fun handleNotificationNavigation(intent: android.content.Intent?, navController: NavController) {
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
