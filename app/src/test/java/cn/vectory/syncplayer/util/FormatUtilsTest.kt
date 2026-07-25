package cn.vectory.syncplayer.util

import cn.vectory.syncplayer.ui.util.buildMetadataLine
import cn.vectory.syncplayer.ui.util.formatDuration
import cn.vectory.syncplayer.ui.util.formatFileSize
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatUtilsTest {

    // formatFileSize

    @Test
    fun `formatFileSize returns zero bytes for zero`() {
        assertEquals("0 B", formatFileSize(0))
    }

    @Test
    fun `formatFileSize returns zero bytes for negative`() {
        assertEquals("0 B", formatFileSize(-1))
    }

    @Test
    fun `formatFileSize returns one decimal byte for single byte`() {
        assertEquals("1.0 B", formatFileSize(1))
    }

    @Test
    fun `formatFileSize keeps bytes unit just below one kibibyte`() {
        assertEquals("1023.0 B", formatFileSize(1023))
    }

    @Test
    fun `formatFileSize switches to KB at exactly one kibibyte`() {
        assertEquals("1.0 KB", formatFileSize(1024))
    }

    @Test
    fun `formatFileSize switches to MB at exactly one mebibyte`() {
        assertEquals("1.0 MB", formatFileSize(1048576))
    }

    @Test
    fun `formatFileSize switches to GB at exactly one gibibyte`() {
        assertEquals("1.0 GB", formatFileSize(1073741824))
    }

    @Test
    fun `formatFileSize switches to TB for tebibyte-level value`() {
        // 2 * 1024^4
        assertEquals("2.0 TB", formatFileSize(2199023255552))
    }

    // formatDuration

    @Test
    fun `formatDuration returns empty string for null`() {
        assertEquals("", formatDuration(null))
    }

    @Test
    fun `formatDuration returns zero time for zero`() {
        assertEquals("0:00", formatDuration(0))
    }

    @Test
    fun `formatDuration returns zero time for negative`() {
        assertEquals("0:00", formatDuration(-1))
    }

    @Test
    fun `formatDuration formats seconds only under one minute`() {
        assertEquals("0:30", formatDuration(30000))
    }

    @Test
    fun `formatDuration formats minutes and seconds`() {
        assertEquals("1:05", formatDuration(65000))
    }

    @Test
    fun `formatDuration formats hours minutes seconds`() {
        assertEquals("1:01:01", formatDuration(3661000))
    }

    @Test
    fun `formatDuration formats whole hour`() {
        assertEquals("1:00:00", formatDuration(3600000))
    }

    // buildMetadataLine

    @Test
    fun `buildMetadataLine returns empty string when all fields empty`() {
        assertEquals("", buildMetadataLine(null, null, null, null, ""))
    }

    @Test
    fun `buildMetadataLine renders duration only`() {
        assertEquals("0:30", buildMetadataLine(30000, null, null, null, ""))
    }

    @Test
    fun `buildMetadataLine renders width and height only`() {
        assertEquals("1920×1080", buildMetadataLine(null, 1920, 1080, null, ""))
    }

    @Test
    fun `buildMetadataLine renders GAIN marker when hasGainAdjusted`() {
        assertEquals("GAIN", buildMetadataLine(null, null, null, null, "", hasGainAdjusted = true))
    }

    @Test
    fun `buildMetadataLine formats bitrate at or above one Mbps`() {
        // 2.5 Mbps
        assertEquals("2.5Mbps", buildMetadataLine(null, null, null, 2500000, ""))
    }

    @Test
    fun `buildMetadataLine formats exactly one million bps as Mbps boundary`() {
        // pins the >= 1_000_000 branch boundary; would fail if production used >
        assertEquals("1.0Mbps", buildMetadataLine(null, null, null, 1000000, ""))
    }

    @Test
    fun `buildMetadataLine formats bitrate below one Mbps as Kbps`() {
        // 500 Kbps
        assertEquals("500Kbps", buildMetadataLine(null, null, null, 500000, ""))
    }

    @Test
    fun `buildMetadataLine uppercases extension`() {
        assertEquals("MP4", buildMetadataLine(null, null, null, null, "mp4"))
    }

    @Test
    fun `buildMetadataLine combines all fields with separator`() {
        // duration 65000 -> "1:05"; dimensions; GAIN; bitrate 5 Mbps; uppercased extension
        val expected = "1:05 · 1280×720 · GAIN · 5.0Mbps · MP4"
        assertEquals(
            expected,
            buildMetadataLine(65000, 1280, 720, 5000000, "mp4", hasGainAdjusted = true)
        )
    }
}
