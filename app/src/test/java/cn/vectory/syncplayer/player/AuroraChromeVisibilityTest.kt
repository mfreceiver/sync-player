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

                    assertEquals(!fullscreen && controls, result.showControlRow)
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
