package cn.vectory.syncplayer.browser

import cn.vectory.syncplayer.ui.components.resolveVideoGridAspectRatio
import cn.vectory.syncplayer.ui.components.VIDEO_GRID_MIN_ASPECT_RATIO
import cn.vectory.syncplayer.ui.components.VIDEO_GRID_MAX_ASPECT_RATIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoGridAspectRatioTest {
    @Test fun `staggered videos use portrait and landscape metadata`() {
        assertEquals(9f / 16f, resolveVideoGridAspectRatio(true, false, true, 1080, 1920), 0f)
        assertEquals(16f / 9f, resolveVideoGridAspectRatio(true, false, true, 1920, 1080), 0f)
    }

    @Test fun `regular grid stays four by three regardless of media metadata`() {
        assertEquals(4f / 3f, resolveVideoGridAspectRatio(false, false, true, 1080, 1920), 0f)
        assertEquals(4f / 3f, resolveVideoGridAspectRatio(false, true, false, 1080, 1920), 0f)
    }

    @Test fun `missing or invalid dimensions and non media fall back`() {
        listOf(null to 1920, 1080 to null, 0 to 1920, 1080 to 0, -1 to 1920).forEach { (width, height) ->
            assertEquals(4f / 3f, resolveVideoGridAspectRatio(true, false, true, width, height), 0f)
        }
        assertEquals(4f / 3f, resolveVideoGridAspectRatio(true, false, false, 1080, 1920), 0f)
    }

    @Test fun `staggered images retain metadata ratio`() {
        assertEquals(0.5f, resolveVideoGridAspectRatio(true, true, false, 100, 200), 0f)
    }

    @Test fun `extremely narrow metadata clamps to readable lower boundary`() {
        assertEquals(VIDEO_GRID_MIN_ASPECT_RATIO, resolveVideoGridAspectRatio(true, false, true, 1, 10_000), 0f)
    }

    @Test fun `extremely wide metadata clamps to readable upper boundary`() {
        assertEquals(VIDEO_GRID_MAX_ASPECT_RATIO, resolveVideoGridAspectRatio(true, false, true, 10_000, 1), 0f)
    }

    @Test fun `maximum integer dimensions remain finite and within declared interval`() {
        listOf(1 to Int.MAX_VALUE, Int.MAX_VALUE to 1, Int.MAX_VALUE to Int.MAX_VALUE).forEach { (width, height) ->
            val ratio = resolveVideoGridAspectRatio(true, false, true, width, height)
            assertTrue(ratio.isFinite())
            assertTrue(ratio in VIDEO_GRID_MIN_ASPECT_RATIO..VIDEO_GRID_MAX_ASPECT_RATIO)
        }
        assertEquals(VIDEO_GRID_MIN_ASPECT_RATIO, resolveVideoGridAspectRatio(true, true, false, 1, Int.MAX_VALUE), 0f)
        assertEquals(VIDEO_GRID_MAX_ASPECT_RATIO, resolveVideoGridAspectRatio(true, true, false, Int.MAX_VALUE, 1), 0f)
    }
}
