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
import cn.vectory.syncplayer.settings.SyncSourceListScreen
import cn.vectory.syncplayer.settings.SyncSourceEditScreen
import cn.vectory.syncplayer.settings.SyncRelationListScreen
import cn.vectory.syncplayer.settings.SyncRelationEditScreen
import cn.vectory.syncplayer.settings.PlaybackSettingsScreen
import cn.vectory.syncplayer.settings.AboutScreen
import cn.vectory.syncplayer.settings.SettingsViewModel
import cn.vectory.syncplayer.sync.SyncPreviewScreen
import cn.vectory.syncplayer.sync.SyncPreviewViewModel
import cn.vectory.syncplayer.sync.DeletePreviewScreen
import cn.vectory.syncplayer.sync.DeletePreviewViewModel
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
                onNavigateToSyncSourceList = {
                    navController.navigate(Route.SyncSourceList.route)
                },
                onNavigateToSyncRelationList = {
                    navController.navigate(Route.SyncRelationList.route)
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

        composable(Route.SyncSourceList.route) {
            SyncSourceListScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToEdit = { sourceId ->
                    navController.navigate(Route.SyncSourceEdit.create(sourceId))
                },
                onNavigateToCreate = {
                    navController.navigate(Route.SyncSourceEdit.createNew())
                }
            )
        }

        composable(
            route = Route.SyncSourceEdit.route,
            arguments = listOf(
                navArgument("sourceId") { type = NavType.LongType; defaultValue = 0L }
            )
        ) {
            SyncSourceEditScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Route.SyncRelationList.route) {
            SyncRelationListScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToEdit = { relationId ->
                    navController.navigate(Route.SyncRelationEdit.create(relationId))
                },
                onNavigateToCreate = {
                    navController.navigate(Route.SyncRelationEdit.createNew())
                },
                onNavigateToSyncPreview = { relationId ->
                    navController.navigate(Route.SyncPreview.create(relationId))
                },
                onNavigateToDeletePreview = { relationId ->
                    navController.navigate(Route.DeletePreview.create(relationId))
                }
            )
        }

        composable(
            route = Route.SyncRelationEdit.route,
            arguments = listOf(
                navArgument("relationId") { type = NavType.LongType; defaultValue = 0L }
            )
        ) {
            SyncRelationEditScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Route.SyncPreview.route,
            arguments = listOf(
                navArgument("relationId") { type = NavType.LongType; defaultValue = 0L }
            )
        ) {
            val viewModel: SyncPreviewViewModel = hiltViewModel()
            SyncPreviewScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = Route.DeletePreview.route,
            arguments = listOf(
                navArgument("relationId") { type = NavType.LongType; defaultValue = 0L }
            )
        ) {
            val viewModel: DeletePreviewViewModel = hiltViewModel()
            DeletePreviewScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
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
