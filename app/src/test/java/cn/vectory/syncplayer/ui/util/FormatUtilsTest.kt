package cn.vectory.syncplayer.ui.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * Unit tests for [formatFileSize] (BUG-021: ≥1 PiB inputs were mislabeled as TB because the
 * unit index was clamped but the divisor was not, and the units array stopped at TB).
 *
 * [formatFileSize] is a dependency-free top-level function — asserting its output string IS
 * asserting the function contract (numeric formatting, not user-facing copy).
 *
 * Locale discipline: `"%.1f".format` uses the JVM default Locale; a host default like `de`
 * would render decimal commas and turn every assertion red/green spuriously. Pin Locale.US
 * for the duration of each test and restore the saved default afterwards (no leak to other
 * test classes in the same JVM). The same discipline covers the merged [formatDuration] /
 * [buildMetadataLine] cases below (`%.1fMbps` is locale-sensitive).
 *
 * The formatDuration / buildMetadataLine / extra boundary cases were merged in from the
 * deleted `cn.vectory.syncplayer.util.FormatUtilsTest` duplicate (package/dir mismatch).
 */
class FormatUtilsTest {

    private val savedLocale = Locale.getDefault()

    @Before
    fun setUp() {
        Locale.setDefault(Locale.US)
    }

    @After
    fun tearDown() {
        Locale.setDefault(savedLocale)
    }

    @Test
    fun `formatFileSize pins legacy boundary values`() {
        // Green guard: every input below 1 PiB must keep its exact pre-fix output.
        assertEquals("0 B", formatFileSize(0L))
        assertEquals("0 B", formatFileSize(-5L))
        assertEquals("1.0 B", formatFileSize(1L))
        assertEquals("1.0 KB", formatFileSize(1024L))
        assertEquals("1.0 MB", formatFileSize(1L shl 20))
        assertEquals("1.0 GB", formatFileSize(1L shl 30))
        assertEquals("1.0 TB", formatFileSize(1L shl 40))
    }

    @Test
    fun `formatFileSize keeps bytes unit just below one kibibyte`() {
        // Sub-KiB boundary: must not round up to "1.0 KB" early.
        assertEquals("1023.0 B", formatFileSize(1023L))
    }

    @Test
    fun `formatFileSize switches to TB for tebibyte-level value`() {
        // 2 * 1024^4
        assertEquals("2.0 TB", formatFileSize(2199023255552L))
    }

    @Test
    fun `formatFileSize labels PiB correctly instead of TB`() {
        // Current behavior returns "1.0 TB" for 1 PiB (index clamped, divisor not) — RED.
        assertEquals("1.0 PB", formatFileSize(1L shl 50))
    }

    @Test
    fun `formatFileSize clamps beyond PiB to PB unit without corrupting the value`() {
        val oneEiB = 1L shl 60
        val out = formatFileSize(oneEiB)
        // Current behavior returns a TB label for EiB-scale input — RED.
        assertTrue(out.endsWith(" PB"))
        assertEquals("1024.0", out.removeSuffix(" PB"))
    }

    @Test
    fun `formatFileSize keeps sub-boundary TB values untouched`() {
        // 1.5×2^40 is exactly representable and far from any log10 boundary — pins that the
        // new clamp cannot mis-shift mid-band TB values.
        assertEquals("1.5 TB", formatFileSize((1.5 * (1L shl 40)).toLong()))
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
