package cn.vectory.syncplayer.storage

import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.File

class StoragePathsTest {

    private lateinit var rootDir: File
    private lateinit var storagePaths: StoragePaths

    @Before
    fun setup() {
        rootDir = File(System.getProperty("java.io.tmpdir"), "storage-test-${System.nanoTime()}")
        val context = mockk<android.content.Context>()
        every { context.filesDir } returns File(rootDir, "files")
        storagePaths = StoragePaths(context)
    }

    @After
    fun cleanup() {
        rootDir.deleteRecursively()
    }

    // ======== normalizeAndValidateRelativePath ========

    @Test
    fun normalize_strips_leading_slash() {
        val result = storagePaths.normalizeAndValidateRelativePath("/foo/bar")
        assertEquals("foo/bar", result)
    }

    @Test
    fun normalize_strips_trailing_slash() {
        val result = storagePaths.normalizeAndValidateRelativePath("foo/bar/")
        assertEquals("foo/bar", result)
    }

    @Test
    fun normalize_collapses_internal_dotdot() {
        val dir = File(storagePaths.userdataRoot, "bar")
        dir.mkdirs()
        val result = storagePaths.normalizeAndValidateRelativePath("foo/../bar")
        assertEquals("bar", result)
    }

    @Test
    fun normalize_empty_returns_empty() {
        assertEquals("", storagePaths.normalizeAndValidateRelativePath(""))
        assertEquals("", storagePaths.normalizeAndValidateRelativePath("   "))
        assertEquals("", storagePaths.normalizeAndValidateRelativePath("/"))
    }

    @Test
    fun normalize_parent_escape_throws() {
        assertThrows(SecurityException::class.java) {
            storagePaths.normalizeAndValidateRelativePath("../../../escape")
        }
    }

    @Test
    fun normalize_middle_dot_unchanged() {
        // "foo.bar" should not be treated as traversal
        val result = storagePaths.normalizeAndValidateRelativePath("foo.bar/baz")
        assertEquals("foo.bar/baz", result)
    }

    @Test
    fun normalize_blank_after_trim_returns_empty() {
        assertEquals("", storagePaths.normalizeAndValidateRelativePath("  /  "))
    }
}
