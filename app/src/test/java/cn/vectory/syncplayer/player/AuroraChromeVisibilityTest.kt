package cn.vectory.syncplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuroraChromeVisibilityTest {

    @Test
    fun `every fullscreen controls and seek combination has one progress representation`() {
        listOf(false, true).forEach { fullscreen ->
            listOf(false, true).forEach { controls ->
                SeekSource.entries.forEach { source ->
                    val result = resolveAuroraChromeVisibility(fullscreen, controls, source)

                    // The full control row follows showControls identically in both
                    // modes — FULLSCREEN only changes the default (hidden on entry).
                    assertEquals(controls, result.showControlRow)
                    assertEquals(
                        1,
                        listOf(result.showProgressZone, result.showMinimizedProgress).count { it },
                    )
                    assertEquals(fullscreen || controls, result.showTopRow)
                }
            }
        }
    }

    @Test
    fun `fullscreen with visible controls shows the full control row`() {
        val result = resolveAuroraChromeVisibility(
            isFullscreen = true,
            showControls = true,
            seekSource = SeekSource.NONE,
        )

        assertTrue(result.showControlRow)
        assertTrue(result.showTopRow)
        assertTrue(result.showProgressZone)
        assertFalse(result.showMinimizedProgress)
    }

    @Test
    fun `fullscreen hidden controls gesture seek keeps active progress`() {
        val result = resolveAuroraChromeVisibility(
            isFullscreen = true,
            showControls = false,
            seekSource = SeekSource.GESTURE,
        )

        assertFalse(result.showControlRow)
        assertTrue(result.showProgressZone)
        assertFalse(result.showMinimizedProgress)
    }
}
