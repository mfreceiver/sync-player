package cn.vectory.syncplayer.browser

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import cn.vectory.syncplayer.NoOpTestApp
import cn.vectory.syncplayer.data.model.BrowseItem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * U2b-1: three-form wiring test for the "(N)" descendant-count suffix on folder names.
 *
 * The SAME [BrowseItem] (descendantCount = 3) must render the count suffix in LIST, GRID and
 * STAGGERED forms; count == 0 must hide it everywhere. Rendered through the internal
 * [BrowserContent] entry (per [BrowserViewModel.ViewMode]), i.e. the real production dispatch —
 * not the builder (its count write is covered by BrowseItemBuilderTest).
 *
 * Styling itself (labelSmall + onSurfaceVariant, shared by GRID and LIST) is owned by the
 * single `folderNameLabel` builder, so the assertion on the composed text is sufficient to
 * prove no form drifted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = NoOpTestApp::class)
class BrowserContentFolderCountLabelTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun folderItem(descendantCount: Int) = BrowseItem(
        videoItemId = null,
        name = "Demo Folder",
        relativePath = "Demo Folder",
        isDirectory = true,
        descendantCount = descendantCount
    )

    private fun setContent(viewMode: BrowserViewModel.ViewMode, descendantCount: Int) {
        composeTestRule.setContent {
            MaterialTheme {
                BrowserContent(
                    viewMode = viewMode,
                    browseItems = listOf(folderItem(descendantCount)),
                    folder = "",
                    browseItemsFolder = "",
                    filter = BrowserViewModel.BrowseFilter.ALL,
                    hiddenByFilterCount = 0,
                    gridColumns = 3,
                    contentPadding = PaddingValues(0.dp),
                    onFolderClick = {},
                    onFolderLongClick = {},
                    onFileOpenVideo = {},
                    onFileOpenImage = {},
                    onCannotPlay = {},
                    onFileLongClick = {},
                    onSaveScrollPosition = { _, _, _ -> },
                    onGetScrollPosition = { null }
                )
            }
        }
    }

    @Test
    fun `list form renders descendant count suffix on folder name`() {
        setContent(BrowserViewModel.ViewMode.LIST, descendantCount = 3)
        composeTestRule.onNodeWithText("Demo Folder (3)").assertExists()
    }

    @Test
    fun `grid form renders descendant count suffix on folder name`() {
        setContent(BrowserViewModel.ViewMode.GRID, descendantCount = 3)
        composeTestRule.onNodeWithText("Demo Folder (3)").assertExists()
    }

    @Test
    fun `staggered form renders descendant count suffix on folder name`() {
        setContent(BrowserViewModel.ViewMode.STAGGERED_GRID, descendantCount = 3)
        composeTestRule.onNodeWithText("Demo Folder (3)").assertExists()
    }

    @Test
    fun `zero descendant count hides the suffix in all three forms`() {
        // One setContent per test (activity content is single-shot); the view mode is flipped
        // via state so all three forms are exercised inside this test.
        val viewMode = mutableStateOf(BrowserViewModel.ViewMode.LIST)
        composeTestRule.setContent {
            MaterialTheme {
                BrowserContent(
                    viewMode = viewMode.value,
                    browseItems = listOf(folderItem(descendantCount = 0)),
                    folder = "",
                    browseItemsFolder = "",
                    filter = BrowserViewModel.BrowseFilter.ALL,
                    hiddenByFilterCount = 0,
                    gridColumns = 3,
                    contentPadding = PaddingValues(0.dp),
                    onFolderClick = {},
                    onFolderLongClick = {},
                    onFileOpenVideo = {},
                    onFileOpenImage = {},
                    onCannotPlay = {},
                    onFileLongClick = {},
                    onSaveScrollPosition = { _, _, _ -> },
                    onGetScrollPosition = { null }
                )
            }
        }
        BrowserViewModel.ViewMode.entries.forEach { mode ->
            composeTestRule.runOnIdle { viewMode.value = mode }
            composeTestRule.onNodeWithText("Demo Folder").assertExists()
            composeTestRule.onNodeWithText("Demo Folder (0)").assertDoesNotExist()
        }
    }
}
