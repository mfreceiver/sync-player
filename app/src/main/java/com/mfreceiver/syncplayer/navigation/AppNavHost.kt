package com.mfreceiver.syncplayer.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mfreceiver.syncplayer.browser.BrowserScreen
import com.mfreceiver.syncplayer.browser.BrowserViewModel
import com.mfreceiver.syncplayer.player.PlayerScreen
import com.mfreceiver.syncplayer.player.PlayerViewModel
import com.mfreceiver.syncplayer.settings.SettingsScreen
import com.mfreceiver.syncplayer.settings.SettingsViewModel
import com.mfreceiver.syncplayer.sync.SyncPreviewScreen
import com.mfreceiver.syncplayer.sync.SyncPreviewViewModel
import androidx.hilt.navigation.compose.hiltViewModel
import android.net.Uri

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
                onNavigateToSettings = {
                    navController.navigate(Route.Settings.route)
                },
                onNavigateToSyncPreview = {
                    navController.navigate(Route.SyncPreview.route)
                }
            )
        }

        composable(
            route = Route.BrowserFolder.route,
            arguments = listOf(
                navArgument("folderPath") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val folderPath = Uri.decode(backStackEntry.arguments?.getString("folderPath") ?: "")
            val viewModel: BrowserViewModel = hiltViewModel()
            BrowserScreen(
                viewModel = viewModel,
                currentFolder = folderPath,
                onNavigateToPlayer = { videoItemId ->
                    navController.navigate(Route.Player.create(videoItemId))
                },
                onNavigateToSettings = {
                    navController.navigate(Route.Settings.route)
                },
                onNavigateToSyncPreview = {
                    navController.navigate(Route.SyncPreview.route)
                },
                onNavigateUp = {
                    navController.popBackStack()
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

        composable(Route.Settings.route) {
            val viewModel: SettingsViewModel = hiltViewModel()
            SettingsScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Route.SyncPreview.route) {
            val viewModel: SyncPreviewViewModel = hiltViewModel()
            SyncPreviewScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
