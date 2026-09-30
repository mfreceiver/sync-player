package cn.vectory.syncplayer.player

import org.junit.Assert.assertEquals
import org.junit.Test

class FineScrubMathTest {

    @Test
    fun `absolute rail ignores legacy fine requests from the first move`() {
        val resolver = TrackSeekResolver(20f, 5_000L, 100, 10_000L)
        assertEquals(3_000L, resolver.move(30f, SeekPrecision.Tenth, true))
        assertEquals(4_000L, resolver.move(40f, SeekPrecision.Quarter, true))
    }

    @Test
    fun `generic segment math preserves anchor and scales delta`() {
        // Utility arithmetic only, not an available precision-switching feature.
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
