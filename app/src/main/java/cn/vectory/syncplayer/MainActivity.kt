package cn.vectory.syncplayer

import android.content.Intent
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
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import cn.vectory.syncplayer.navigation.AppNavHost
import cn.vectory.syncplayer.navigation.Route
import cn.vectory.syncplayer.ui.theme.SyncPlayerTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SyncPlayerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    var notificationIntent by remember { mutableStateOf(intent) }

                    LaunchedEffect(intent) {
                        notificationIntent = intent
                    }

                    LaunchedEffect(notificationIntent) {
                        handleNotificationNavigation(notificationIntent, navController)
                    }

                    AppNavHost(navController = navController)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
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
