package cn.vectory.syncplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [resolvePreviewGeometry] — the pure math keeping the coarse seek
 * preview horizontally centered over the progress thumb, shrunk to fit, and clamped
 * inside the nav-inset-safe viewport. The safe viewport and the track span share the
 * same insets, so every case also asserts the bounds invariant
 * `offset >= safeLeft && offset + effectiveWidth <= safeRight`.
 * Asserts pixel values only; no user-facing text involved.
 */
class AuroraPreviewOffsetTest {

    private fun geometry(
        fraction: Float,
        rootWidthPx: Float = 1080f,
        navigationLeftPx: Float = 0f,
        navigationRightPx: Float = 0f,
        edgePaddingPx: Float = 0f,
        nominalPreviewWidthPx: Float = 200f,
    ): PreviewGeometry = resolvePreviewGeometry(
        rootWidthPx = rootWidthPx,
        navigationLeftPx = navigationLeftPx,
        navigationRightPx = navigationRightPx,
        edgePaddingPx = edgePaddingPx,
        fraction = fraction,
        nominalPreviewWidthPx = nominalPreviewWidthPx,
    )

    private fun assertWithinSafeViewport(g: PreviewGeometry, root: Float, navLeft: Float, navRight: Float) {
        val safeLeft = navLeft
        val safeRight = root - navRight
        assertTrue("offset $g must be >= safeLeft $safeLeft", g.offsetXPx >= safeLeft)
        assertTrue(
            "offset+width ${g.offsetXPx + g.effectiveWidthPx} must be <= safeRight $safeRight",
            g.offsetXPx + g.effectiveWidthPx <= safeRight,
        )
    }

    // --- Basic fractions, no insets (legacy semantics) ---

    @Test
    fun `fraction 0 clamps preview to safe left edge`() {
        val g = geometry(0f)
        assertEquals(200f, g.effectiveWidthPx, 0f)
        assertEquals(0f, g.offsetXPx, 0f)
        assertWithinSafeViewport(g, root = 1080f, navLeft = 0f, navRight = 0f)
    }

    @Test
    fun `fraction 0_5 centers preview on mid-track thumb`() {
        val g = geometry(0.5f)
        assertEquals(440f, g.offsetXPx, 0f)
        assertWithinSafeViewport(g, root = 1080f, navLeft = 0f, navRight = 0f)
    }

    @Test
    fun `fraction 1 clamps preview to safe right edge`() {
        val g = geometry(1f)
        assertEquals(880f, g.offsetXPx, 0f)
        assertWithinSafeViewport(g, root = 1080f, navLeft = 0f, navRight = 0f)
    }

    @Test
    fun `fraction above 1 behaves like 1`() {
        assertEquals(geometry(1f).offsetXPx, geometry(1.75f).offsetXPx, 0f)
    }

    @Test
    fun `fraction below 0 behaves like 0`() {
        assertEquals(geometry(0f).offsetXPx, geometry(-0.25f).offsetXPx, 0f)
    }

    @Test
    fun `non-finite fractions behave like 0`() {
        val zero = geometry(0f).offsetXPx
        assertEquals(zero, geometry(Float.NaN).offsetXPx, 0f)
        assertEquals(zero, geometry(Float.POSITIVE_INFINITY).offsetXPx, 0f)
        assertEquals(zero, geometry(Float.NEGATIVE_INFINITY).offsetXPx, 0f)
    }

    // --- Shrink to fit ---

    @Test
    fun `nominal preview wider than safe viewport shrinks to the safe width`() {
        // Safe width 1000 < nominal 1200 → effectiveWidth 1000, offset pinned to 0.
        listOf(0f, 0.5f, 1f).forEach { fraction ->
            val g = geometry(fraction, rootWidthPx = 1000f, nominalPreviewWidthPx = 1200f)
            assertEquals(1000f, g.effectiveWidthPx, 0f)
            assertEquals(0f, g.offsetXPx, 0f)
            assertWithinSafeViewport(g, root = 1000f, navLeft = 0f, navRight = 0f)
        }
    }

    @Test
    fun `nominal 184dp wider than inset-safe width shrinks and stays inside insets`() {
        // root 240, navLeft/navRight 40 → safe 40..200 (width 160) < nominal 184.
        val g = geometry(0.5f, rootWidthPx = 240f, navigationLeftPx = 40f, navigationRightPx = 40f, nominalPreviewWidthPx = 184f)
        assertEquals(160f, g.effectiveWidthPx, 0f)
        assertEquals(40f, g.offsetXPx, 0f)
        assertWithinSafeViewport(g, root = 240f, navLeft = 40f, navRight = 40f)
    }

    // --- Nav insets (non-zero / asymmetric) ---

    @Test
    fun `nonzero asymmetric nav insets keep preview clamped within the safe viewport`() {
        val root = 1080f
        val navLeft = 63f
        val navRight = 126f
        // track 127..890, nominal 368 fits safe width 891.
        listOf(0f, 0.25f, 0.5f, 0.75f, 1f, 1.5f, -0.3f, Float.NaN).forEach { fraction ->
            val g = geometry(fraction, rootWidthPx = root, navigationLeftPx = navLeft, navigationRightPx = navRight, edgePaddingPx = 64f, nominalPreviewWidthPx = 368f)
            assertEquals(368f, g.effectiveWidthPx, 0f)
            assertWithinSafeViewport(g, root, navLeft, navRight)
        }
        // Spot values: fraction 0 → thumb 127 → -57 → clamped to safeLeft 63;
        // fraction 1 → thumb 890 → 706 → clamped to maxLeft 586; 0.5 → 508.5-184.
        assertEquals(63f, geometry(0f, rootWidthPx = root, navigationLeftPx = navLeft, navigationRightPx = navRight, edgePaddingPx = 64f, nominalPreviewWidthPx = 368f).offsetXPx, 0f)
        assertEquals(586f, geometry(1f, rootWidthPx = root, navigationLeftPx = navLeft, navigationRightPx = navRight, edgePaddingPx = 64f, nominalPreviewWidthPx = 368f).offsetXPx, 0f)
        assertEquals(324.5f, geometry(0.5f, rootWidthPx = root, navigationLeftPx = navLeft, navigationRightPx = navRight, edgePaddingPx = 64f, nominalPreviewWidthPx = 368f).offsetXPx, 0f)
    }

    @Test
    fun `left nav inset shifts the start clamp off screen origin`() {
        // Asymmetric case with viewportLeft > 0 (legacy semantics): viewport 100..1100,
        // inset track 116..1084, preview 200.
        val g0 = geometry(0f, rootWidthPx = 1100f, navigationLeftPx = 100f, edgePaddingPx = 16f)
        assertEquals(100f, g0.offsetXPx, 0f)
        val gMid = geometry(0.5f, rootWidthPx = 1100f, navigationLeftPx = 100f, edgePaddingPx = 16f)
        assertEquals(500f, gMid.offsetXPx, 0f)
        val g1 = geometry(1f, rootWidthPx = 1100f, navigationLeftPx = 100f, edgePaddingPx = 16f)
        assertEquals(900f, g1.offsetXPx, 0f)
        assertWithinSafeViewport(g1, root = 1100f, navLeft = 100f, navRight = 0f)
    }

    // --- Degenerate inputs ---

    @Test
    fun `degenerate track collapses to zero width and pins thumb at track left`() {
        // root 300, nav 40/40, edge 160 → trackLeft 200 > trackRight 100 → width 0.
        listOf(0f, 0.5f, 1f).forEach { fraction ->
            val g = geometry(fraction, rootWidthPx = 300f, navigationLeftPx = 40f, navigationRightPx = 40f, edgePaddingPx = 160f)
            // thumb stays at 200; offset = 200-100 = 100 clamped to maxLeft 60.
            assertEquals(60f, g.offsetXPx, 0f)
            assertWithinSafeViewport(g, root = 300f, navLeft = 40f, navRight = 40f)
        }
    }

    @Test
    fun `duration 0 maps to fraction 0 and start-aligned clamped offset`() {
        // The host computes fraction=0 when durationMs <= 0. Real-world-ish geometry:
        // navLeft 48, track starts at 104, preview 368, root 1080.
        val g = geometry(0f, rootWidthPx = 1080f, navigationLeftPx = 48f, edgePaddingPx = 56f, nominalPreviewWidthPx = 368f)
        assertEquals(48f, g.offsetXPx, 0f)
        assertWithinSafeViewport(g, root = 1080f, navLeft = 48f, navRight = 0f)
    }

    @Test
    fun `degenerate viewport with negative safe width yields zero width at safe left`() {
        // navLeft > safeRight → effective width floored to 0, offset pinned at safeLeft.
        val g = geometry(0.5f, rootWidthPx = 200f, navigationLeftPx = 150f, navigationRightPx = 100f)
        assertEquals(0f, g.effectiveWidthPx, 0f)
        assertEquals(150f, g.offsetXPx, 0f)
    }
}
