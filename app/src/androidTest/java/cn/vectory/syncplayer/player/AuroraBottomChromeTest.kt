package cn.vectory.syncplayer.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Test

class AuroraBottomChromeTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun exitKeepsVisualContainerButRemovesInteractiveContentImmediately() {
        val visible = mutableStateOf(true)
        composeRule.setContent {
            AuroraBottomChrome(visible.value, Modifier.testTag("chrome_transition")) {
                Box(Modifier.size(48.dp).testTag("chrome_control").clickable {})
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("chrome_control").assertExists()
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnIdle { visible.value = false }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithTag("chrome_control").assertDoesNotExist()
        composeRule.onNodeWithTag("chrome_transition").assertExists()
        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.onNodeWithTag("chrome_control").assertDoesNotExist()
    }

    @Test fun topExitRemovesActionImmediatelyAndOldButtonCoordinateReachesUnderlyingContent() {
        val visible = mutableStateOf(true)
        var exitCalls = 0
        var underlyingCalls = 0
        composeRule.setContent {
            Box(Modifier.fillMaxSize().testTag("underlying_content").clickable { underlyingCalls++ }) {
                AuroraTopRow("", visible.value) { exitCalls++ }
            }
        }
        composeRule.waitForIdle()
        val oldButtonCenter = composeRule.onNodeWithTag("player_aurora_exit", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.center
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnIdle { visible.value = false }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithTag("player_aurora_exit", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag("underlying_content").performTouchInput { click(oldButtonCenter) }
        composeRule.runOnIdle {
            assertEquals(0, exitCalls)
            assertEquals(1, underlyingCalls)
        }
    }
}
