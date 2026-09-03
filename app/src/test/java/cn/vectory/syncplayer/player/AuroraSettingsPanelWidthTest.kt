package cn.vectory.syncplayer.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import cn.vectory.syncplayer.NoOpTestApp
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Layout tests for the settings panel's content-adaptive width
 * (width(IntrinsicSize.Max) + widthIn(max = AuroraPanelMaxWidth)):
 *
 * - With short labels the panel must be significantly narrower than
 *   min(hostWidth, 560dp) — a fillMaxWidth-driven regression would pin it at
 *   exactly the cap.
 * - All action rows (radios + trash row) must equal the panel's final content
 *   width (rows fill the panel, they do not inflate it).
 * - A narrow host must contain the panel within its bounds; a wide host keeps the
 *   panel capped at AuroraPanelMaxWidth.
 *
 * Host width control: the viewport is pinned via Robolectric qualifiers
 * (w800dp-h1280dp) so the wide scenario genuinely exists, and each host uses an
 * EXPLICIT width with height-only fill (`Modifier.width(N).fillMaxHeight()`).
 * NEVER `fillMaxSize()` before `width(...)` — fillMaxSize tightens the incoming
 * constraints and the later width() cannot override them, silently voiding the
 * scenario. Every width-dependent test first asserts the host's measured width so
 * an invalid scenario fails loudly instead of passing trivially.
 *
 * Runs on the unit side via Robolectric (NATIVE graphics so text width metrics are
 * real). Asserts pixel values only — never user-facing text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    application = NoOpTestApp::class,
    qualifiers = "w800dp-h1280dp",
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AuroraSettingsPanelWidthTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setContentWithHostWidth(hostWidthDp: Int) {
        composeRule.setContent {
            MaterialTheme {
                Box(
                    Modifier
                        .testTag("player_aurora_settings_panel_host")
                        .width(hostWidthDp.dp)
                        .fillMaxHeight(),
                ) {
                    AuroraSettingsModal(
                        visible = true,
                        isFullscreen = false,
                        showControls = true,
                        videoId = 1L,
                        playbackSpeed = 1f,
                        isTrashed = false,
                        errorSequence = null,
                        onDismiss = {},
                        onDirectionChange = {},
                        onSpeedChange = {},
                        onToggleTrash = {},
                        onTrashSucceeded = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("player_aurora_settings_panel_host").assertIsDisplayed()
        composeRule.onNodeWithTag("player_aurora_settings_panel").assertIsDisplayed()
    }

    private fun dpToPx(dp: Int): Float = dp * composeRule.density.density

    private fun nodeWidthPx(tag: String): Int =
        composeRule.onNodeWithTag(tag).fetchSemanticsNode().size.width

    private fun nodeLeftPx(tag: String): Float =
        composeRule.onNodeWithTag(tag).fetchSemanticsNode().positionInRoot.x

    /**
     * Guards the scenario itself: the host must actually measure [expectedDp] wide.
     * If host width control ever regresses (modifier order, qualifiers change),
     * every dependent test fails HERE first instead of passing trivially.
     */
    private fun assertHostWidth(expectedDp: Int) {
        val expectedPx = dpToPx(expectedDp)
        val actualPx = nodeWidthPx("player_aurora_settings_panel_host").toFloat()
        assertTrue(
            "host must measure ≈$expectedDp dp ($expectedPx px) but was $actualPx px — scenario invalid",
            abs(actualPx - expectedPx) <= 1.5f,
        )
    }

    private fun rowWidthsPx(): List<Int> =
        composeRule.onAllNodesWithTag("player_aurora_radio").fetchSemanticsNodes().map { it.size.width } +
            listOf(
                composeRule.onNodeWithTag("player_aurora_recycle_action").fetchSemanticsNode().size.width,
            )

    private fun assertRowsFillPanelContentWidth() {
        // The panel testTag sits inside the Surface's 16dp horizontal padding, so the
        // rows (which live under the Column's own 16dp padding) must each equal
        // tagWidth - 32dp. All rows must be identical: they FILL the panel instead of
        // sizing it.
        val tagWidth = nodeWidthPx("player_aurora_settings_panel")
        val expectedRowPx = tagWidth - dpToPx(32)
        val rows = rowWidthsPx()
        assertEquals(6, rows.size)
        rows.forEach { row ->
            assertEquals(expectedRowPx, row.toFloat(), 1.5f)
        }
    }

    @Test
    fun `short content keeps panel well below the width cap`() {
        setContentWithHostWidth(hostWidthDp = 800)
        assertHostWidth(800)

        val capPx = minOf(dpToPx(800), dpToPx(560))
        val panelPx = nodeWidthPx("player_aurora_settings_panel").toFloat()

        // Content-adaptive: with these short labels the panel must sit significantly
        // under min(host, 560dp). fillMaxWidth rows without intrinsic sizing
        // would force it exactly to the cap.
        assertTrue("panel width $panelPx must be > 0", panelPx > 0f)
        assertTrue(
            "panel width $panelPx must be < 0.9 * cap $capPx",
            panelPx < capPx * 0.9f,
        )
    }

    @Test
    fun `every action row equals the panel's final content width`() {
        setContentWithHostWidth(hostWidthDp = 800)
        assertHostWidth(800)

        assertRowsFillPanelContentWidth()
    }

    @Test
    fun `narrow host never overflows its bounds`() {
        setContentWithHostWidth(hostWidthDp = 240)
        assertHostWidth(240)

        val hostPx = dpToPx(240)
        val panelLeft = nodeLeftPx("player_aurora_settings_panel")
        val panelWidth = nodeWidthPx("player_aurora_settings_panel").toFloat()

        // Panel edges (the tag is inside the Surface's 16dp screen-safe padding, so
        // the tag's own bounds are stricter than the surface's) must stay within the
        // host: 0 <= left AND left + width <= hostWidth (+1px rounding).
        assertTrue("panel left $panelLeft must be >= 0", panelLeft >= 0f)
        assertTrue(
            "panel right ${panelLeft + panelWidth} must be <= host $hostPx",
            panelLeft + panelWidth <= hostPx + 1f,
        )
        // Rows still exactly fill the (now capped) content width.
        assertRowsFillPanelContentWidth()
    }

    @Test
    fun `wide host caps panel at AuroraPanelMaxWidth`() {
        setContentWithHostWidth(hostWidthDp = 800)
        assertHostWidth(800)

        val capPx = dpToPx(560)
        val panelPx = nodeWidthPx("player_aurora_settings_panel").toFloat()
        assertTrue(
            "panel $panelPx must be <= cap $capPx (+ padding allowance)",
            panelPx + dpToPx(32) <= capPx + 1f,
        )
    }
}
