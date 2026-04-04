package cn.vectory.syncplayer.navigation

sealed class Route(val route: String) {
    data object Browser : Route("browser")
    data object Player : Route("player/{videoItemId}") {
        fun create(videoItemId: Long): String = "player/$videoItemId"
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
    data object DeletePreview : Route("delete_preview/{relationId}") {
        fun create(relationId: Long): String = "delete_preview/$relationId"
    }
    data object PlaybackSettings : Route("playback_settings")
    data object About : Route("about")
}
