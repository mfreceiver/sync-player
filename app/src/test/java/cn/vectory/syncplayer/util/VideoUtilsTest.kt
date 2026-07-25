package cn.vectory.syncplayer.util

import cn.vectory.syncplayer.ui.util.extractParentPath
import cn.vectory.syncplayer.ui.util.getMimeType
import cn.vectory.syncplayer.ui.util.isImageFile
import cn.vectory.syncplayer.ui.util.isMediaFile
import cn.vectory.syncplayer.ui.util.isVideoFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    // isVideoFile

    @Test
    fun `isVideoFile returns true for normal mp4 extension`() {
        assertTrue(isVideoFile("movie.mp4"))
    }

    @Test
    fun `isVideoFile returns true for normal mkv extension`() {
        assertTrue(isVideoFile("movie.mkv"))
    }

    @Test
    fun `isVideoFile returns true for uppercase extension`() {
        assertTrue(isVideoFile("movie.MKV"))
    }

    @Test
    fun `isVideoFile returns false for filename without extension`() {
        assertFalse(isVideoFile("movie"))
    }

    @Test
    fun `isVideoFile returns false for non-video extension`() {
        assertFalse(isVideoFile("notes.txt"))
    }

    @Test
    fun `isVideoFile handles filename with multiple dots`() {
        assertTrue(isVideoFile("a.b.mp4"))
    }

    // isImageFile

    @Test
    fun `isImageFile returns true for jpg and jpeg extensions`() {
        assertTrue(isImageFile("photo.jpg"))
        assertTrue(isImageFile("photo.jpeg"))
    }

    @Test
    fun `isImageFile returns true for uppercase PNG extension`() {
        assertTrue(isImageFile("photo.PNG"))
    }

    @Test
    fun `isImageFile returns false for non-image extension`() {
        assertFalse(isImageFile("doc.pdf"))
    }

    // isMediaFile

    @Test
    fun `isMediaFile returns true for video file`() {
        assertTrue(isMediaFile("movie.mp4"))
    }

    @Test
    fun `isMediaFile returns true for image file`() {
        assertTrue(isMediaFile("photo.jpg"))
    }

    @Test
    fun `isMediaFile returns false for audio file`() {
        assertFalse(isMediaFile("song.mp3"))
    }

    @Test
    fun `isMediaFile returns false for non-media file`() {
        assertFalse(isMediaFile("notes.txt"))
    }

    // getMimeType - video extensions

    @Test
    fun `getMimeType returns video slash star for every video extension`() {
        listOf("mp4", "mkv", "avi", "mov", "flv", "wmv", "webm", "ts", "m4v", "3gp", "mpeg", "mpg")
            .forEach { ext ->
                assertEquals("extension .$ext", "video/*", getMimeType("file.$ext"))
            }
    }

    @Test
    fun `getMimeType returns video slash star for uppercase MP4`() {
        assertEquals("video/*", getMimeType("clip.MP4"))
    }

    // getMimeType - image extensions

    @Test
    fun `getMimeType returns image jpeg for jpg and jpeg`() {
        assertEquals("image/jpeg", getMimeType("photo.jpg"))
        assertEquals("image/jpeg", getMimeType("photo.jpeg"))
    }

    @Test
    fun `getMimeType returns image png for png`() {
        assertEquals("image/png", getMimeType("photo.png"))
    }

    @Test
    fun `getMimeType returns image gif for gif`() {
        assertEquals("image/gif", getMimeType("photo.gif"))
    }

    @Test
    fun `getMimeType returns image bmp for bmp`() {
        assertEquals("image/bmp", getMimeType("photo.bmp"))
    }

    @Test
    fun `getMimeType returns image webp for webp`() {
        assertEquals("image/webp", getMimeType("photo.webp"))
    }

    @Test
    fun `getMimeType returns image heic for heic and heif`() {
        assertEquals("image/heic", getMimeType("photo.heic"))
        assertEquals("image/heic", getMimeType("photo.heif"))
    }

    // getMimeType - audio extensions

    @Test
    fun `getMimeType returns audio mpeg for mp3`() {
        assertEquals("audio/mpeg", getMimeType("song.mp3"))
    }

    @Test
    fun `getMimeType returns audio flac for flac`() {
        assertEquals("audio/flac", getMimeType("song.flac"))
    }

    @Test
    fun `getMimeType returns audio wav for wav`() {
        assertEquals("audio/wav", getMimeType("song.wav"))
    }

    @Test
    fun `getMimeType returns audio aac for aac`() {
        assertEquals("audio/aac", getMimeType("song.aac"))
    }

    @Test
    fun `getMimeType returns audio ogg for ogg`() {
        assertEquals("audio/ogg", getMimeType("song.ogg"))
    }

    // getMimeType - text extensions

    @Test
    fun `getMimeType returns text plain for text-like extensions`() {
        assertEquals("text/plain", getMimeType("sub.srt"))
        assertEquals("text/plain", getMimeType("sub.ass"))
        assertEquals("text/plain", getMimeType("meta.nfo"))
        assertEquals("text/plain", getMimeType("notes.txt"))
    }

    // getMimeType - fallbacks

    @Test
    fun `getMimeType returns octet-stream for file without extension`() {
        assertEquals("application/octet-stream", getMimeType("noext"))
    }

    @Test
    fun `getMimeType returns octet-stream for unknown extension`() {
        assertEquals("application/octet-stream", getMimeType("weird.xyz"))
    }
}
