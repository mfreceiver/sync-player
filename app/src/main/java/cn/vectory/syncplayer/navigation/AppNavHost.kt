package cn.vectory.syncplayer.navigation

import androidx.compose.runtime.Composable
import cn.vectory.syncplayer.debug.DebugLogger
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navOptions
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
import cn.vectory.syncplayer.debug.DebugLogScreen
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun AppNavHost(debugLogger: DebugLogger) {
    val navController = rememberNavController()

    LaunchedEffect(Unit) {
        navController.addOnDestinationChangedListener { _, destination, _ ->
            debugLogger.log("Nav", "Navigate to: ${destination.route}")
        }
    }

    NavHost(
        navController = navController,
        startDestination = Route.Browser.route
    ) {
        composable(
            route = Route.Browser.route,
            arguments = listOf(
                navArgument("folder") { type = NavType.StringType; defaultValue = "" }
            )
        ) { backStackEntry ->
            val folder = backStackEntry.arguments?.getString("folder") ?: ""
            val viewModel: BrowserViewModel = hiltViewModel()
            val drawerNavOptions = navOptions {
                popUpTo(Route.Browser.route) { inclusive = false }
                launchSingleTop = true
            }
            BrowserScreen(
                viewModel = viewModel,
                currentFolder = folder,
                onNavigateToPlayer = { videoItemId ->
                    val currentFolder = viewModel.currentFolder.value
                    navController.navigate(Route.Player.create(videoItemId, currentFolder))
                },
                onNavigateToSyncSourceList = {
                    navController.navigate(Route.SyncSourceList.route, drawerNavOptions)
                },
                onNavigateToSyncRelationList = {
                    navController.navigate(Route.SyncRelationList.route, drawerNavOptions)
                },
                onNavigateToPlaybackSettings = {
                    navController.navigate(Route.PlaybackSettings.route, drawerNavOptions)
                },
                onNavigateToAbout = {
                    navController.navigate(Route.About.route, drawerNavOptions)
                },
                onNavigateToSyncPreview = { relationId ->
                    navController.navigate(Route.SyncPreview.create(relationId))
                }
            , debugLogger = debugLogger
            )
        }

        composable(
            route = Route.Player.route,
            arguments = listOf(
                navArgument("videoItemId") { type = NavType.LongType },
                navArgument("returnFolder") { type = NavType.StringType; defaultValue = "" }
            )
        ) { backStackEntry ->
            val videoItemId = backStackEntry.arguments?.getLong("videoItemId") ?: return@composable
            val returnFolder = backStackEntry.arguments?.getString("returnFolder") ?: ""
            val viewModel: PlayerViewModel = hiltViewModel()
            PlayerScreen(
                viewModel = viewModel,
                videoItemId = videoItemId,
                onExit = {
                    navController.navigate(
                        Route.Browser.create(returnFolder),
                        navOptions {
                            popUpTo(Route.Browser.route) { inclusive = true }
                            launchSingleTop = true
                        }
                    )
                }
            )
        }

        composable(Route.SyncSourceList.route) {
            SyncSourceListScreen(
                onNavigateBack = { navController.popBackStack(Route.Browser.route, inclusive = false) },
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
                onNavigateBack = { navController.popBackStack(Route.Browser.route, inclusive = false) }
            )
        }

        composable(Route.SyncRelationList.route) {
            SyncRelationListScreen(
                onNavigateBack = { navController.popBackStack(Route.Browser.route, inclusive = false) },
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
                onNavigateBack = { navController.popBackStack(Route.Browser.route, inclusive = false) }
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
                onBack = { navController.popBackStack(Route.Browser.route, inclusive = false) },
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
                onBack = { navController.popBackStack(Route.Browser.route, inclusive = false) }
            )
        }

        composable(Route.PlaybackSettings.route) {
            val viewModel: SettingsViewModel = hiltViewModel()
            PlaybackSettingsScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack(Route.Browser.route, inclusive = false) },
                onNavigateToDebugLog = {
                    navController.navigate(Route.DebugLog.route)
                }
            )
        }

        composable(Route.DebugLog.route) {
            DebugLogScreen(
                debugLogger = debugLogger,
                onNavigateBack = { navController.popBackStack(Route.Browser.route, inclusive = false) }
            )
        }

        composable(Route.About.route) {
            val viewModel: SettingsViewModel = hiltViewModel()
            AboutScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack(Route.Browser.route, inclusive = false) }
            )
        }
    }
}
