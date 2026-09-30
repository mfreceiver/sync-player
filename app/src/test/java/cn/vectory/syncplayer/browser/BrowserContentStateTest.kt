package cn.vectory.syncplayer.browser

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserContentStateTest {
    private fun state(
        folder: String = "",
        browseItemsFolder: String = folder,
        itemCount: Int = 0,
        firstRunState: FirstRunState? = FirstRunState.READY,
        filterActive: Boolean = false,
        hiddenByFilterCount: Int = 0,
        hasError: Boolean = false,
    ) = resolveBrowserContentState(
        folder, browseItemsFolder, itemCount, firstRunState,
        filterActive, hiddenByFilterCount, hasError
    )

    @Test fun `first root frame waits for a real onboarding signal and browse result`() {
        assertEquals(BrowserContentState.Loading, state(firstRunState = null))
        assertEquals(BrowserContentState.Loading, state())
        assertEquals(BrowserContentState.EmptyLibrary, state(firstRunState = FirstRunState.NEEDS_SYNC))
    }

    @Test fun `a folder switch never shows a stale folder's content or empty state`() {
        assertEquals(
            BrowserContentState.Loading,
            state(folder = "next", browseItemsFolder = "previous", itemCount = 3)
        )
        assertEquals(BrowserContentState.EmptyFolder, state(folder = "next"))
        assertEquals(BrowserContentState.Content, state(folder = "next", itemCount = 1))
    }

    @Test fun `filter including stars shows actionable empty only when it hid content`() {
        assertEquals(BrowserContentState.FilteredEmpty, state(filterActive = true, hiddenByFilterCount = 5))
        assertEquals(BrowserContentState.EmptyFolder, state(folder = "next", filterActive = true))
        assertEquals(BrowserContentState.Content, state(itemCount = 1, filterActive = true, hiddenByFilterCount = 1))
    }

    @Test fun `error state wins over an empty screen but not real content`() {
        assertEquals(BrowserContentState.Error, state(hasError = true))
        assertEquals(BrowserContentState.Content, state(itemCount = 2, hasError = true))
    }

    @Test fun `loading or filtered empty B preserves B history rather than saving A coordinates`() = runBlocking {
        for ((itemsFolder, hasItems) in listOf("A" to false, "B" to false)) {
            val saved = mutableMapOf("B" to (8 to 17))
            var visiblePosition = 50 to 23 // A's actual LazyState coordinates
            val tracker = BrowseScrollOwnership(previousFolder = "A", coordinateOwner = "A")
            tracker.enterFolder("B", { visiblePosition }) { folder, index, offset ->
                saved[folder] = index to offset
            }
            tracker.showContent("B", itemsFolder, hasItems, saved::get) { index, offset ->
                visiblePosition = index to offset
            }
            assertEquals(null, tracker.coordinateOwner)
            tracker.enterFolder("A", { visiblePosition }) { folder, index, offset ->
                saved[folder] = index to offset
            }
            assertEquals(8 to 17, saved["B"])

            tracker.enterFolder("B", { visiblePosition }) { folder, index, offset ->
                saved[folder] = index to offset
            }
            tracker.showContent("B", "B", true, saved::get) { index, offset ->
                visiblePosition = index to offset
            }
            assertEquals("B", tracker.coordinateOwner)
            assertEquals(8 to 17, visiblePosition)
        }
    }

    @Test fun `displayed B restores then saves its own newly scrolled position on departure`() = runBlocking {
        val saved = mutableMapOf("B" to (8 to 17))
        var visiblePosition = 50 to 23
        val tracker = BrowseScrollOwnership(previousFolder = "A", coordinateOwner = "A")
        tracker.enterFolder("B", { visiblePosition }) { folder, index, offset ->
            saved[folder] = index to offset
        }
        tracker.showContent("B", "B", true, saved::get) { index, offset ->
            visiblePosition = index to offset
        }
        assertEquals(8 to 17, visiblePosition)
        visiblePosition = 12 to 4
        tracker.enterFolder("A", { visiblePosition }) { folder, index, offset ->
            saved[folder] = index to offset
        }
        assertEquals(12 to 4, saved["B"])
    }

    @Test fun `fresh folder starts at top before owning a reused LazyState`() = runBlocking {
        var visiblePosition = 50 to 23
        val tracker = BrowseScrollOwnership(previousFolder = "A", coordinateOwner = "A")
        tracker.enterFolder("new", { visiblePosition }) { _, _, _ -> }
        tracker.showContent("new", "new", true, { null }) { index, offset ->
            visiblePosition = index to offset
        }
        assertEquals(0 to 0, visiblePosition)
        assertEquals("new", tracker.coordinateOwner)
    }
}
