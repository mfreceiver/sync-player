package cn.vectory.syncplayer.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for [computeThumbSize].
 *
 * Regression guard for the tiny-viewport crash: a naive `coerceIn(40f, viewport)` throws
 * [IllegalArgumentException] when `viewport < 40` (minimumValue > maximumValue). The clamped
 * minimum must keep the function total (never throw) and keep the thumb within the track.
 */
class VerticalScrollbarTest {

    @Test
    fun `thumb size respects the 40px minimum on a normal viewport`() {
        // ratio 0.25 of a 1000px viewport → 250px thumb (above the 40px minimum).
        val size = computeThumbSize(ratio = 0.25f, viewport = 1000f)
        assertEquals(250f, size, 0.01f)
    }

    @Test
    fun `thumb size clamps up to the 40px minimum when ratio is tiny`() {
        // ratio 0.01 of a 1000px viewport → 10px, clamped up to 40px.
        val size = computeThumbSize(ratio = 0.01f, viewport = 1000f)
        assertEquals(40f, size, 0.01f)
    }

    @Test
    fun `thumb size never exceeds the viewport`() {
        // ratio 2.0 (more visible than total — degenerate) → clamped down to viewport.
        val size = computeThumbSize(ratio = 2.0f, viewport = 300f)
        assertEquals(300f, size, 0.01f)
    }

    @Test
    fun `tiny viewport below 40px does not crash and stays within the track`() {
        // The crash regression: viewport=20 (< 40 minimum) must NOT throw and must be <= viewport.
        val size = computeThumbSize(ratio = 0.5f, viewport = 20f)
        assertTrue("size must be <= viewport", size <= 20f)
        assertTrue("size must be >= 0", size >= 0f)
    }

    @Test
    fun `zero viewport does not crash`() {
        val size = computeThumbSize(ratio = 0.5f, viewport = 0f)
        assertEquals(0f, size, 0.01f)
    }
}
