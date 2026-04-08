package cn.vectory.syncplayer.navigation

import android.net.Uri

sealed class Route(val route: String) {
    data object Browser : Route("browser?folder={folder}") {
        fun create(folder: String = ""): String =
            if (folder.isEmpty()) "browser?folder=" else "browser?folder=${Uri.encode(folder)}"
    }
    data object Player : Route("player/{videoItemId}?returnFolder={returnFolder}&fromRoute={fromRoute}") {
        fun create(videoItemId: Long, returnFolder: String = "", fromRoute: String = "browser"): String {
            val encodedFolder = if (returnFolder.isEmpty()) "" else Uri.encode(returnFolder)
            return "player/$videoItemId?returnFolder=$encodedFolder&fromRoute=$fromRoute"
        }
    }
    data object SyncSourceList : Route("sync_source_list")
    data object SyncSourceEdit : Route("sync_source_edit/{sourceId}") {
        fun create(sourceId: Long): String = "sync_source_edit/$sourceId"
        fun createNew(): String = "sync_source_edit/0"
    }
    data object SyncRelationList : Route("sync_relation_list")
    data object SyncRelationEdit : Route("sync_relation_edit/{relationId}") {
        fun create(relationId: Long): String = "sync_relation_edit/$relationId"
        fun createNew(): String = "sync_relation_edit/0"
    }
    data object SyncPreview : Route("sync_preview/{relationId}") {
        fun create(relationId: Long): String = "sync_preview/$relationId"
    }
    data object Search : Route("search")
    data object PlaybackSettings : Route("playback_settings")
    data object About : Route("about")
}
