package cn.vectory.syncplayer.navigation

sealed class Route(val route: String) {
    data object Browser : Route("browser")
    data object Player : Route("player/{videoItemId}") {
        fun create(videoItemId: Long): String = "player/$videoItemId"
    }
    data object ServerConfig : Route("server_config")
    data object SyncPreview : Route("sync_preview")
    data object PlaybackSettings : Route("playback_settings")
    data object About : Route("about")
}
