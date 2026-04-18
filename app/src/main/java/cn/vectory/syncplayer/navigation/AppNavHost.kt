package cn.vectory.syncplayer.navigation

import androidx.compose.runtime.Composable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navOptions
import cn.vectory.syncplayer.browser.BrowserScreen
import cn.vectory.syncplayer.browser.BrowserViewModel
import cn.vectory.syncplayer.browser.SearchScreen
import cn.vectory.syncplayer.player.PlayerScreen
import cn.vectory.syncplayer.player.PlayerViewModel
import cn.vectory.syncplayer.settings.SyncSourceListScreen
import cn.vectory.syncplayer.settings.SyncSourceEditScreen
import cn.vectory.syncplayer.settings.SyncRelationListScreen
import cn.vectory.syncplayer.settings.SyncRelationEditScreen
import cn.vectory.syncplayer.settings.SettingsScreen
import cn.vectory.syncplayer.settings.SettingsViewModel
import cn.vectory.syncplayer.settings.GainAnalysisScreen
import cn.vectory.syncplayer.settings.MetadataRebuildScreen
import cn.vectory.syncplayer.sync.SyncPreviewScreen
import cn.vectory.syncplayer.sync.SyncPreviewViewModel

import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun AppNavHost(navController: NavHostController = rememberNavController()) {
    val navHelper: NavigationHelper = hiltViewModel()

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
                onNavigateToPlayer = { videoItemId, videoIds ->
                    val currentFolder = viewModel.currentFolder.value
                    navHelper.videoQueue.setIds(videoIds)
                    navController.navigate(Route.Player.create(videoItemId, currentFolder, "browser"))
                },
                onNavigateToSearch = {
                    navController.navigate(Route.Search.route)
                },
                onNavigateToPlaybackSettings = {
                    navController.navigate(Route.Settings.route, drawerNavOptions)
                },
                onNavigateToSyncPreview = { relationId ->
                    navController.navigate(Route.SyncPreview.create(relationId, "delete"))
                }
            )
        }

        composable(
            route = Route.Player.route,
            arguments = listOf(
                navArgument("videoItemId") { type = NavType.LongType },
                navArgument("returnFolder") { type = NavType.StringType; defaultValue = "" },
                navArgument("fromRoute") { type = NavType.StringType; defaultValue = "browser" }
            ),
            enterTransition = { fadeIn(tween(0)) },
            exitTransition = { fadeOut(tween(0)) },
            popEnterTransition = { fadeIn(tween(0)) },
            popExitTransition = { fadeOut(tween(0)) }
        ) { backStackEntry ->
            val videoItemId = backStackEntry.arguments?.getLong("videoItemId") ?: return@composable
            val fromRoute = backStackEntry.arguments?.getString("fromRoute") ?: "browser"
            val viewModel: PlayerViewModel = hiltViewModel()
            PlayerScreen(
                viewModel = viewModel,
                videoItemId = videoItemId,
                onExit = {
                    if (fromRoute == "search") {
                        navController.popBackStack(Route.Search.route, inclusive = false)
                    } else {
                        navController.popBackStack(Route.Browser.route, inclusive = false)
                    }
                }
            )
        }

        composable(
            route = Route.SyncSourceList.route
        ) {
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
                onNavigateBack = { navController.popBackStack() },
                onNavigateToSourceList = { navController.popBackStack(Route.SyncSourceList.route, inclusive = true) }
            )
        }

        composable(
            route = Route.SyncRelationList.route
        ) {
            SyncRelationListScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToEdit = { relationId ->
                    navController.navigate(Route.SyncRelationEdit.create(relationId))
                },
                onNavigateToCreate = {
                    navController.navigate(Route.SyncRelationEdit.createNew())
                },
                onNavigateToSyncPreview = { relationId ->
                    navController.navigate(Route.SyncPreview.create(relationId, "download"))
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
                onNavigateBack = { navController.popBackStack() },
                onNavigateToRelationList = { navController.popBackStack(Route.SyncRelationList.route, inclusive = true) }
            )
        }

        composable(
            route = Route.SyncPreview.route,
            arguments = listOf(
                navArgument("relationId") { type = NavType.LongType; defaultValue = 0L },
                navArgument("initialTab") { type = NavType.StringType; defaultValue = "download" }
            )
        ) {
            val viewModel: SyncPreviewViewModel = hiltViewModel()
            SyncPreviewScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onNavigateToRelation = { relationId, initialTab ->
                    navController.navigate(Route.SyncPreview.create(relationId, initialTab)) {
                        popUpTo(Route.SyncRelationList.route) { inclusive = false }
                        launchSingleTop = true
                    }
                }
            )
        }


        composable(Route.Search.route) {
            SearchScreen(
                onNavigateToFolder = { folderPath ->
                    navController.popBackStack(Route.Search.route, inclusive = true)
                    navController.navigate(Route.Browser.create(folderPath))
                },
                onNavigateToPlayer = { videoItemId, videoIds ->
                    navHelper.videoQueue.setIds(videoIds)
                    navController.navigate(Route.Player.create(videoItemId, "", "search"))
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Route.Settings.route
        ) {
            val viewModel: SettingsViewModel = hiltViewModel()
            SettingsScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToMetadataRebuild = { navController.navigate(Route.MetadataRebuild.route) },
                onNavigateToGainAnalysis = { navController.navigate(Route.GainAnalysis.create()) },
                onNavigateToSyncSourceList = { navController.navigate(Route.SyncSourceList.route) },
                onNavigateToSyncRelationList = { navController.navigate(Route.SyncRelationList.route) }
            )
        }

        composable(
            route = Route.GainAnalysis.route,
            arguments = listOf(
                navArgument("relationId") { type = NavType.LongType; defaultValue = 0L }
            )
        ) {
            GainAnalysisScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(Route.MetadataRebuild.route) {
            MetadataRebuildScreen(onBack = { navController.popBackStack() })
        }
    }
}
