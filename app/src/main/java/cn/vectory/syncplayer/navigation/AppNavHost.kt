package cn.vectory.syncplayer.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import cn.vectory.syncplayer.browser.BrowserScreen
import cn.vectory.syncplayer.browser.BrowserViewModel
import cn.vectory.syncplayer.player.PlayerScreen
import cn.vectory.syncplayer.player.PlayerViewModel
import cn.vectory.syncplayer.settings.ServerConfigScreen
import cn.vectory.syncplayer.settings.PlaybackSettingsScreen
import cn.vectory.syncplayer.settings.AboutScreen
import cn.vectory.syncplayer.settings.SettingsViewModel
import cn.vectory.syncplayer.sync.SyncPreviewScreen
import cn.vectory.syncplayer.sync.SyncPreviewViewModel
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun AppNavHost() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Route.Browser.route
    ) {
        composable(Route.Browser.route) {
            val viewModel: BrowserViewModel = hiltViewModel()
            BrowserScreen(
                viewModel = viewModel,
                onNavigateToPlayer = { videoItemId ->
                    navController.navigate(Route.Player.create(videoItemId))
                },
                onNavigateToServerConfig = {
                    navController.navigate(Route.ServerConfig.route)
                },
                onNavigateToSyncPreview = {
                    navController.navigate(Route.SyncPreview.route)
                },
                onNavigateToPlaybackSettings = {
                    navController.navigate(Route.PlaybackSettings.route)
                },
                onNavigateToAbout = {
                    navController.navigate(Route.About.route)
                }
            )
        }

        composable(
            route = Route.Player.route,
            arguments = listOf(
                navArgument("videoItemId") { type = NavType.LongType }
            )
        ) { backStackEntry ->
            val videoItemId = backStackEntry.arguments?.getLong("videoItemId") ?: return@composable
            val viewModel: PlayerViewModel = hiltViewModel()
            PlayerScreen(
                viewModel = viewModel,
                videoItemId = videoItemId,
                onExit = { navController.popBackStack() }
            )
        }

        composable(Route.ServerConfig.route) {
            val viewModel: SettingsViewModel = hiltViewModel()
            ServerConfigScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Route.SyncPreview.route) {
            val viewModel: SyncPreviewViewModel = hiltViewModel()
            SyncPreviewScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
            )
        }

        composable(Route.PlaybackSettings.route) {
            val viewModel: SettingsViewModel = hiltViewModel()
            PlaybackSettingsScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Route.About.route) {
            val viewModel: SettingsViewModel = hiltViewModel()
            AboutScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
