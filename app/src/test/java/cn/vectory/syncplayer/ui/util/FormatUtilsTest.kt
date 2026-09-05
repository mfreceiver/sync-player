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
 * test classes in the same JVM).
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
}
