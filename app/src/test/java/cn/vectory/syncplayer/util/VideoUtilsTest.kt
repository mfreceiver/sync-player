package cn.vectory.syncplayer.util

import cn.vectory.syncplayer.ui.util.extractParentPath
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoUtilsTest {

    @Test
    fun `extractParentPath returns empty for file in root`() {
        val path = "movie.mp4"
        val expected = ""
        assertEquals(expected, extractParentPath(path))
    }

    @Test
    fun `extractParentPath returns parent for file in one-level folder`() {
        val path = "Movies/movie.mp4"
        val expected = "Movies"
        assertEquals(expected, extractParentPath(path))
    }

    @Test
    fun `extractParentPath returns parent path for nested folders`() {
        val path = "Downloads/Sci-Fi/interstellar.mkv"
        val expected = "Downloads/Sci-Fi"
        assertEquals(expected, extractParentPath(path))
    }

    @Test
    fun `extractParentPath returns correct path for folder itself`() {
        val path = "Downloads/Sci-Fi/"
        val expected = "Downloads/Sci-Fi"
        assertEquals(expected, extractParentPath(path))
    }
}
