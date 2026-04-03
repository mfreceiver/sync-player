package cn.vectory.syncplayer.navigation

sealed class Route(val route: String) {
    data object Browser : Route("browser")
    data object BrowserFolder : Route("browser/{folderPath}") {
        fun create(folderPath: String): String =
            "browser/${android.net.Uri.encode(folderPath, "/")}"
    }
    data object Player : Route("player/{videoItemId}") {
        fun create(videoItemId: Long): String = "player/$videoItemId"
    }
    data object Settings : Route("settings")
    data object SyncPreview : Route("sync_preview")
    data object DeletePreview : Route("delete_preview")
}
