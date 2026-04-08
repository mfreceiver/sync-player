package cn.vectory.syncplayer.browser

/**
 * Temporary store for passing a target folder from SearchScreen back to BrowserScreen.
 * Set by SearchScreen before navigation, read by BrowserScreen on recomposition.
 */
object BrowserNavigationStore {
    var targetFolder: String? = null
}
