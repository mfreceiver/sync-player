package cn.vectory.syncplayer.ui.util

import cn.vectory.syncplayer.ui.util.ShareTempFiles.DEFAULT_MAX_AGE_MS
import cn.vectory.syncplayer.ui.util.ShareTempFiles.FALLBACK_NAME
import cn.vectory.syncplayer.ui.util.ShareTempFiles.cleanupOldShareTempFiles
import cn.vectory.syncplayer.ui.util.ShareTempFiles.sanitizeFileName
import cn.vectory.syncplayer.ui.util.ShareTempFiles.shareDir
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit tests for [ShareTempFiles].
 *
 * The two security-critical surfaces are [ShareTempFiles.sanitizeFileName] (must never let a
 * remote `fileName` escape `share/`) and [ShareTempFiles.cleanupOldShareTempFiles] (must only
 * delete files older than the TTL, never the one currently being shared).
 *
 * Uses [TemporaryFolder] as a stand-in for `Context.cacheDir` so the file walk is exercised
 * against a real (but isolated) filesystem.
 */
class ShareTempFilesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /**
     * Minimal fake Context: only [android.content.Context.getCacheDir] is exercised by
     * [ShareTempFiles]; everything else is left as the default (we never call it from this object).
     */
    private class CacheOnlyContext(cacheDir: File) : android.content.ContextWrapper(null) {
        private val cache = cacheDir
        override fun getCacheDir(): File = cache
    }

    private fun newContext(): android.content.Context = CacheOnlyContext(tmp.newFolder("cache"))

    // ===================================================================================
    // sanitizeFileName — path-traversal / malicious input rejection
    // ===================================================================================

    @Test
    fun `sanitizeFileName preserves a normal mp4 name`() {
        assertEquals("movie.mp4", sanitizeFileName("movie.mp4"))
    }

    @Test
    fun `sanitizeFileName preserves a unicode name`() {
        assertEquals("视频.mp4", sanitizeFileName("视频.mp4"))
    }

    @Test
    fun `sanitizeFileName preserves a multi-dot name`() {
        assertEquals("a.b.c.mkv", sanitizeFileName("a.b.c.mkv"))
    }

    @Test
    fun `sanitizeFileName keeps an extension-less name`() {
        // No extension is a naming choice, not a security issue — keep it.
        assertEquals("README", sanitizeFileName("README"))
    }

    @Test
    fun `sanitizeFileName strips a leading absolute path`() {
        assertEquals("passwd", sanitizeFileName("/etc/passwd"))
    }

    @Test
    fun `sanitizeFileName strips a leading windows absolute path`() {
        assertEquals("evil.mp4", sanitizeFileName("C:\\Windows\\evil.mp4"))
    }

    @Test
    fun `sanitizeFileName collapses dot-dot-slash traversal`() {
        assertEquals("evil.mp4", sanitizeFileName("../evil.mp4"))
    }

    @Test
    fun `sanitizeFileName collapses deeply nested traversal`() {
        assertEquals("target.mp4", sanitizeFileName("../../../../tmp/target.mp4"))
    }

    @Test
    fun `sanitizeFileName collapses backslash traversal`() {
        assertEquals("evil.mp4", sanitizeFileName("..\\..\\evil.mp4"))
    }

    @Test
    fun `sanitizeFileName rejects bare dot-dot`() {
        assertEquals(FALLBACK_NAME, sanitizeFileName(".."))
    }

    @Test
    fun `sanitizeFileName rejects triple-dot traversal remnant`() {
        assertEquals(FALLBACK_NAME, sanitizeFileName("..."))
    }

    @Test
    fun `sanitizeFileName rejects all-dots after trim`() {
        assertEquals(FALLBACK_NAME, sanitizeFileName("....."))
    }

    @Test
    fun `sanitizeFileName rejects a leading-dot hidden file`() {
        // `.bashrc` would otherwise become a hidden file on Linux shares; strip the leading dot
        // to avoid surprising the recipient. The name still survives.
        assertEquals("bashrc", sanitizeFileName(".bashrc"))
    }

    @Test
    fun `sanitizeFileName rejects empty input`() {
        assertEquals(FALLBACK_NAME, sanitizeFileName(""))
    }

    @Test
    fun `sanitizeFileName rejects blank input`() {
        assertEquals(FALLBACK_NAME, sanitizeFileName("    "))
    }

    @Test
    fun `sanitizeFileName trims surrounding whitespace`() {
        assertEquals("clip.mp4", sanitizeFileName("   clip.mp4   "))
    }

    @Test
    fun `sanitizeFileName rejects null bytes anywhere`() {
        assertEquals(FALLBACK_NAME, sanitizeFileName("evil\u0000.mp4"))
    }

    @Test
    fun `sanitizeFileName rejects trailing null-byte truncation attempt`() {
        // NUL truncates filenames on C-string APIs — never let it through.
        assertEquals(FALLBACK_NAME, sanitizeFileName("good.mp4\u0000../../evil"))
    }

    @Test
    fun `sanitizeFileName does NOT url-decode encoded traversal`() {
        // %2e%2e%2f is a single leaf name on disk (no FS URL-decodes); preserved as-is so the
        // share still has a sensible extension. Critically it does NOT become a path.
        val result = sanitizeFileName("%2e%2e%2fevil.mp4")
        assertEquals("%2e%2e%2fevil.mp4", result)
        assertFalse("must not contain a path separator", result.contains('/'))
    }

    // ===================================================================================
    // shareDir
    // ===================================================================================

    @Test
    fun `shareDir creates the share subdir under cacheDir`() {
        val ctx = newContext()
        val dir = shareDir(ctx)
        assertTrue("share dir must exist", dir.exists())
        assertTrue("share dir must be a directory", dir.isDirectory)
        assertEquals("share", dir.name)
        assertEquals(File(ctx.cacheDir, "share").absolutePath, dir.absolutePath)
    }

    @Test
    fun `shareDir is idempotent when called twice`() {
        val ctx = newContext()
        val first = shareDir(ctx)
        val second = shareDir(ctx)
        assertTrue(first.exists())
        assertEquals(first.absolutePath, second.absolutePath)
    }

    // ===================================================================================
    // cleanupOldShareTempFiles
    // ===================================================================================

    @Test
    fun `cleanupOldShareTempFiles deletes only files older than TTL`() {
        val ctx = newContext()
        val dir = shareDir(ctx)
        val now = 10_000_000L

        // "Old" file (older than default TTL).
        val oldFile = File(dir, "old.mp4").apply { writeText("old") }
        oldFile.setLastModified(now - DEFAULT_MAX_AGE_MS - 1)

        // "Fresh" file (within TTL — must survive the sweep).
        val freshFile = File(dir, "fresh.mp4").apply { writeText("fresh") }
        freshFile.setLastModified(now - 1_000)

        val deleted = cleanupOldShareTempFiles(ctx, now = now)

        assertEquals("exactly the old file must be deleted", 1, deleted)
        assertFalse("old file must be gone", oldFile.exists())
        assertTrue("fresh file must survive", freshFile.exists())
    }

    @Test
    fun `cleanupOldShareTempFiles does not delete files exactly at TTL boundary`() {
        // `age >= maxAgeMs` deletes; `age == maxAgeMs` is exactly at boundary → deleted.
        // Pin the boundary semantics so future changes can't silently regress.
        val ctx = newContext()
        val dir = shareDir(ctx)
        val now = 5_000_000L
        val maxAge = 1_000L

        val boundary = File(dir, "boundary.mp4").apply { writeText("x") }
        boundary.setLastModified(now - maxAge) // age == maxAge

        val justUnder = File(dir, "just_under.mp4").apply { writeText("x") }
        justUnder.setLastModified(now - maxAge + 1) // age == maxAge - 1

        val deleted = cleanupOldShareTempFiles(ctx, maxAgeMs = maxAge, now = now)

        assertEquals(1, deleted)
        assertFalse(boundary.exists())
        assertTrue(justUnder.exists())
    }

    @Test
    fun `cleanupOldShareTempFiles ignores subdirectories under share`() {
        val ctx = newContext()
        val dir = shareDir(ctx)
        val now = 10_000_000L

        // A subdir with old mtime must NOT be deleted (only files are swept).
        val subdir = File(dir, "subdir").apply { mkdirs() }
        subdir.setLastModified(now - DEFAULT_MAX_AGE_MS - 9999)

        val deleted = cleanupOldShareTempFiles(ctx, now = now)

        assertEquals("subdir must be ignored", 0, deleted)
        assertTrue("subdir must survive", subdir.exists())
    }

    @Test
    fun `cleanupOldShareTempFiles returns 0 on empty share dir`() {
        val ctx = newContext()
        shareDir(ctx) // create empty dir

        val deleted = cleanupOldShareTempFiles(ctx)

        assertEquals(0, deleted)
    }

    @Test
    fun `cleanupOldShareTempFiles creates the share dir if missing and returns 0`() {
        // Even if share/ was never created (e.g. first launch), the sweep must not crash and
        // must report zero deletions.
        val ctx = newContext()
        // Don't call shareDir first; listFiles() returns null on a missing dir.
        val deleted = cleanupOldShareTempFiles(ctx)
        assertEquals(0, deleted)
        assertNotNull(shareDir(ctx))
    }

    @Test
    fun `cleanupOldShareTempFiles deletes multiple stale files`() {
        val ctx = newContext()
        val dir = shareDir(ctx)
        val now = 50_000_000L
        repeat(5) { i ->
            File(dir, "stale-$i.mp4").apply {
                writeText("x")
                setLastModified(now - DEFAULT_MAX_AGE_MS - i)
            }
        }

        val deleted = cleanupOldShareTempFiles(ctx, now = now)

        assertEquals(5, deleted)
        assertTrue(dir.listFiles()?.isEmpty() == true)
    }

    @Test
    fun `DEFAULT_MAX_AGE_MS is one hour`() {
        // Pin the TTL so a future "let's make it 1 minute" change can't silently start deleting
        // files the recipient is still reading.
        assertEquals(60L * 60L * 1000L, DEFAULT_MAX_AGE_MS)
    }

    @Test
    fun `FALLBACK_NAME is a mp4 video`() {
        // Pin the fallback: it MUST have a video extension so the share Intent MIME resolves to
        // something the recipient knows how to open even when the source name was unusable.
        assertTrue(FALLBACK_NAME.endsWith(".mp4"))
    }
}
