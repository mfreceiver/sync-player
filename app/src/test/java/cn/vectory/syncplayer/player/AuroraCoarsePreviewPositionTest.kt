package cn.vectory.syncplayer.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import cn.vectory.syncplayer.NoOpTestApp
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Layout tests for the coarse seek preview's VERTICAL anchor: the preview's bottom
 * edge must sit exactly one AuroraPreviewGap (8dp) ABOVE the bottom chrome's top
 * edge — not flush against the container bottom.
 *
 * The test replicates PlayerScreen's wiring: a bottom chrome box whose height is
 * MEASURED via onSizeChanged and fed into [AuroraSeekPreviewHost] as
 * bottomChromeHeightPx. Chrome heights cover both real visibility cases: a short
 * chrome (progress zone only — gesture seek with the control row hidden) and a
 * tall chrome (progress + control row).
 *
 * The nav-inset test guards against DOUBLE-COUNTING the navigation bar: the
 * measured chrome height already contains AuroraBottomArea's
 * navigationBarsPadding(), so the preview's clearance must add the gap ONLY. A real
 * non-zero bottom system-bar inset is dispatched into the hierarchy so the old
 * double-counting implementation (clearance = nav inset + chrome height + gap)
 * fails this suite measurably.
 *
 * videoUri = null keeps the host on its placeholder path (no frame extraction,
 * extractor never invoked), and SeekSource.GESTURE exercises the same
 * AuroraCoarsePreview composable as the coarse PROGRESS_BAR path. Asserts pixel
 * geometry only — never user-facing text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    application = NoOpTestApp::class,
    qualifiers = "w800dp-h1280dp",
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AuroraCoarsePreviewPositionTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** Last chrome height reported by the replica's onSizeChanged. */
    private val measuredChromeHeight = arrayOf(0)

    private fun setContentWithChrome(
        chromeHeightDp: () -> Int,
        chromeHasNavPadding: Boolean = false,
        assertDisplayed: Boolean = true,
    ) {
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    // Fake bottom chrome, replicating PlayerScreen's bottom-area
                    // wrapper (measured height) around AuroraBottomArea's padded
                    // Column (navigationBarsPadding + content height).
                    var chromeHeightPx by remember { mutableIntStateOf(0) }
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .testTag("test_bottom_chrome")
                            .onSizeChanged {
                                chromeHeightPx = it.height
                                measuredChromeHeight[0] = it.height
                            },
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .then(if (chromeHasNavPadding) Modifier.navigationBarsPadding() else Modifier)
                                .height(chromeHeightDp().dp),
                        )
                    }
                    AuroraSeekPreviewHost(
                        seekSource = SeekSource.GESTURE,
                        progressScrubState = remember { MutableStateFlow(ProgressScrubUiState()) },
                        gesturePositionMs = 60_000L,
                        durationMs = 120_000L,
                        bottomChromeHeightPx = chromeHeightPx,
                        videoUri = null,
                        previewExtractor = SeekPreviewExtractor(ApplicationProvider.getApplicationContext()),
                    )
                }
            }
        }
        composeRule.waitForIdle()
        if (assertDisplayed) {
            composeRule.onNodeWithTag("player_aurora_coarse_preview").assertIsDisplayed()
        }
    }

    private fun dpToPx(dp: Int): Float = dp * composeRule.density.density

    private fun assertPreviewBottomSitsAboveChromeTop() {
        val preview = composeRule.onNodeWithTag("player_aurora_coarse_preview").fetchSemanticsNode()
        val chrome = composeRule.onNodeWithTag("test_bottom_chrome").fetchSemanticsNode()

        val previewBottom = preview.positionInRoot.y + preview.size.height
        val chromeTop = chrome.positionInRoot.y

        // The contract: preview bottom == chrome top - AuroraPreviewGap (8dp),
        // i.e. exactly one gap of clearance above the progress zone.
        assertEquals(chromeTop - dpToPx(8), previewBottom, 1.5f)
        assertTrue("preview bottom must be strictly above chrome top", previewBottom < chromeTop)
    }

    /**
     * Dispatches a REAL platform [android.view.WindowInsets] with a non-zero bottom
     * system-bar into the compose hierarchy (the same path production insets flow
     * through), so WindowInsets.navigationBarsPadding()/navigationBars resolve
     * non-zero under Robolectric too.
     */
    private fun dispatchBottomSystemBarInset(bottomPx: Int) {
        composeRule.runOnIdle {
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, bottomPx))
                .build()
                .toWindowInsets()
                ?: error("platform WindowInsets unavailable under this SDK")
            val content = (composeRule as AndroidComposeTestRule<*, *>)
                .activity
                .findViewById<android.view.View>(android.R.id.content)
            content.dispatchApplyWindowInsets(insets)
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `preview bottom sits one gap above a progress-only chrome`() {
        // Gesture seek with the control row hidden: chrome = progress touch zone.
        setContentWithChrome(chromeHeightDp = { 48 })
        assertPreviewBottomSitsAboveChromeTop()
    }

    @Test
    fun `preview bottom sits one gap above a full chrome with control row`() {
        // Progress-bar seek with full chrome: progress + stack gap + control row.
        setContentWithChrome(chromeHeightDp = { 140 })
        assertPreviewBottomSitsAboveChromeTop()
    }

    @Test
    fun `clearance does not double-count the nav bar inset`() {
        // Faithful replica: the chrome's measured height INCLUDES its
        // navigationBarsPadding(), exactly like AuroraBottomArea under PlayerScreen's
        // measuring wrapper. The preview must clear by the gap ONLY — adding the nav
        // inset on top (old implementation) lifted the preview one nav bar too high.
        setContentWithChrome(chromeHeightDp = { 48 }, chromeHasNavPadding = true)
        val chromeHeightBeforeInset = measuredChromeHeight[0]

        dispatchBottomSystemBarInset(bottomPx = 63)

        // Non-vacuity guard: the dispatched inset must be LIVE — the padded chrome
        // must have grown by exactly the injected 63px. If this fails, inset
        // dispatch is inert under the current Robolectric/Compose stack and this
        // test can no longer detect double-counting.
        assertEquals(
            chromeHeightBeforeInset + 63f,
            measuredChromeHeight[0].toFloat(),
            1.5f,
        )

        assertPreviewBottomSitsAboveChromeTop()
    }

    @Test
    fun `initial zero to measured chrome height keeps preview in viewport`() {
        // Initial-composition transient (0 -> measured): freeze the clock BEFORE
        // composing so the earliest frames render while bottomChromeHeightPx is
        // still 0 (chrome not yet measured). The preview must stay inside the
        // viewport during those frames, then land exactly one gap above the chrome.
        composeRule.mainClock.autoAdvance = false
        setContentWithChrome(chromeHeightDp = { 48 }, assertDisplayed = false)

        val rootHeightPx = dpToPx(1280)
        repeat(6) {
            composeRule.mainClock.advanceTimeByFrame()
            val preview = composeRule.onNodeWithTag("player_aurora_coarse_preview").fetchSemanticsNode()
            assertTrue("preview top must stay >= 0", preview.positionInRoot.y >= 0f)
            assertTrue(
                "preview bottom must stay within the viewport",
                preview.positionInRoot.y + preview.size.height <= rootHeightPx + 1f,
            )
        }

        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        assertPreviewBottomSitsAboveChromeTop()
    }

    @Test
    fun `chrome height transition keeps preview in viewport and lands gap-above`() {
        // Transition transient (140dp -> 48dp): sampled frame by frame with a frozen
        // clock, the preview must never leave the viewport; once idle it must land
        // exactly one gap above the new chrome top.
        val chromeHeightDp = mutableStateOf(140)
        composeRule.mainClock.autoAdvance = false
        setContentWithChrome(chromeHeightDp = { chromeHeightDp.value })

        composeRule.runOnUiThread { chromeHeightDp.value = 48 }
        val rootHeightPx = dpToPx(1280)
        repeat(6) {
            composeRule.mainClock.advanceTimeByFrame()
            val preview = composeRule.onNodeWithTag("player_aurora_coarse_preview").fetchSemanticsNode()
            assertTrue("preview top must stay >= 0", preview.positionInRoot.y >= 0f)
            assertTrue(
                "preview bottom must stay within the viewport",
                preview.positionInRoot.y + preview.size.height <= rootHeightPx + 1f,
            )
        }

        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        assertPreviewBottomSitsAboveChromeTop()
    }
}
