package cn.vectory.syncplayer.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class SeekPresentationTest {
    @Test fun `compact and hidden previews have no decode demand`() {
        assertFalse(seekPreviewDemand(active = true, compact = true, hasMedia = true))
        assertFalse(seekPreviewDemand(active = false, compact = false, hasMedia = true))
        assertFalse(seekPreviewDemand(active = true, compact = false, hasMedia = false))
        assertTrue(seekPreviewDemand(active = true, compact = false, hasMedia = true))
    }

    @Test fun `frame identity includes generation and session as well as bucket`() {
        val frame = PresentedPreviewFrame(4L, 2L, 1_200L, null)
        assertTrue(frame.matches(4L, 2L, 1_499L))
        assertFalse(frame.matches(4L, 2L, 1_500L))
        assertFalse(frame.matches(5L, 2L, 1_200L))
        assertFalse(frame.matches(4L, 3L, 1_200L))
    }

    @Test fun `generic segment math applies the supplied scale`() {
        // Utility arithmetic only; live player input always supplies Full (1f).
        val first = resolveSegmentTargetMs(10f, 30f, 100, 10_000L, 5_000L, 1f)
        val second = resolveSegmentTargetMs(30f, 50f, 100, 10_000L, first, 0.25f)
        assertEquals(7_000L, first)
        assertEquals(7_500L, second)
    }

    @Test fun `absolute rail stays clamped through excursions beyond both edges`() {
        val resolver = TrackSeekResolver(20f, 200L, 100, 1_000L)
        assertEquals(1_000L, resolver.move(110f, SeekPrecision.Full, relativeRequested = false))
        assertEquals(1_000L, resolver.move(105f, SeekPrecision.Full, relativeRequested = false))
        assertEquals(950L, resolver.move(95f, SeekPrecision.Full, relativeRequested = false))
        assertEquals(0L, resolver.move(-10f, SeekPrecision.Full, relativeRequested = false))
        assertEquals(0L, resolver.move(-5f, SeekPrecision.Full, relativeRequested = false))
    }

    @Test fun `legacy fine requests cannot change absolute track semantics`() {
        val resolver = TrackSeekResolver(20f, 200L, 100, 1_000L)
        assertEquals(400L, resolver.move(40f, SeekPrecision.Full, relativeRequested = false))
        assertEquals(500L, resolver.move(50f, SeekPrecision.Quarter, relativeRequested = true))
        assertEquals(600L, resolver.move(60f, SeekPrecision.Quarter, relativeRequested = true))
        assertEquals(700L, resolver.move(70f, SeekPrecision.Full, relativeRequested = false))
        assertEquals(800L, resolver.move(80f, SeekPrecision.Full, relativeRequested = false))
    }

    @Test fun `compact follows remaining viewport not chrome height alone`() {
        assertFalse(seekPreviewCompact(800f, 48, 24f, 80f))
        assertTrue(seekPreviewCompact(150f, 48, 24f, 80f))
        assertFalse(seekPreviewCompact(300f, 140, 24f, 80f))
    }
}
