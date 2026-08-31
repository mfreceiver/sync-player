package cn.vectory.syncplayer.player

import org.junit.Assert.assertEquals
import org.junit.Test

class FineScrubMathTest {

    @Test
    fun `fine factor enters and returns at hysteresis boundaries`() {
        assertEquals(1f, stabilizedFineScrubFactor(39f, 1f))
        assertEquals(0.25f, stabilizedFineScrubFactor(40f, 1f))
        assertEquals(0.25f, stabilizedFineScrubFactor(32.1f, 0.25f))
        assertEquals(1f, stabilizedFineScrubFactor(32f, 0.25f))
        assertEquals(0.1f, stabilizedFineScrubFactor(80f, 0.25f))
        assertEquals(0.1f, stabilizedFineScrubFactor(72.1f, 0.1f))
        assertEquals(0.25f, stabilizedFineScrubFactor(72f, 0.1f))
    }

    @Test
    fun `factor transition rebases target without a jump`() {
        val beforeTransition = resolveSegmentTargetMs(
            segmentStartX = 0f,
            currentX = 100f,
            widthPx = 1_000,
            durationMs = 100_000L,
            segmentStartTargetMs = 1_000L,
            factor = 1f,
        )

        val atTransition = resolveSegmentTargetMs(
            segmentStartX = 100f,
            currentX = 100f,
            widthPx = 1_000,
            durationMs = 100_000L,
            segmentStartTargetMs = beforeTransition,
            factor = 0.25f,
        )
        val afterTransition = resolveSegmentTargetMs(
            segmentStartX = 100f,
            currentX = 101f,
            widthPx = 1_000,
            durationMs = 100_000L,
            segmentStartTargetMs = atTransition,
            factor = 0.25f,
        )

        assertEquals(beforeTransition, atTransition)
        assertEquals(11_025L, afterTransition)
    }

    @Test
    fun `target math clamps degenerate negative and oversized drags`() {
        assertEquals(
            0L,
            resolveSegmentTargetMs(0f, 100f, 0, 0L, 0L, 1f),
        )
        assertEquals(
            0L,
            resolveSegmentTargetMs(100f, -100f, 100, 10_000L, 1_000L, 1f),
        )
        assertEquals(
            10_000L,
            resolveSegmentTargetMs(0f, 10_000f, 100, 10_000L, 1_000L, 1f),
        )
        assertEquals(
            Long.MAX_VALUE,
            resolveSegmentTargetMs(0f, 1f, 1, Long.MAX_VALUE, Long.MAX_VALUE, 1f),
        )
    }
}
