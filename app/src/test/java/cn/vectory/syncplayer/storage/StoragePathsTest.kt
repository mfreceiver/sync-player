package cn.vectory.syncplayer.storage

import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    // ======== safeResolveUserData ========

    @Test
    fun safeResolveUserData_empty_returnsRoot() {
        val result = storagePaths.safeResolveUserData("")
        assertEquals(storagePaths.userdataRoot.canonicalFile, result)
    }

    @Test
    fun safeResolveUserData_blank_returnsRoot() {
        val result = storagePaths.safeResolveUserData("   ")
        assertEquals(storagePaths.userdataRoot.canonicalFile, result)
    }

    @Test
    fun safeResolveUserData_normal_relative_path() {
        val result = storagePaths.safeResolveUserData("movies/action/video.mp4")
        val expected = File(storagePaths.userdataRoot.canonicalFile, "movies/action/video.mp4").canonicalFile
        assertEquals(expected, result)
    }

    @Test
    fun safeResolveUserData_traversal_dotdot_throws() {
        assertThrows(SecurityException::class.java) {
            storagePaths.safeResolveUserData("../escape.txt")
        }
    }

    @Test
    fun safeResolveUserData_double_traversal_throws() {
        assertThrows(SecurityException::class.java) {
            storagePaths.safeResolveUserData("../../etc/passwd")
        }
    }

    @Test
    fun safeResolveUserData_leading_slash_stripped() {
        // "/movies" should be treated as "movies" relative to userdataRoot
        val result = storagePaths.safeResolveUserData("/movies/test.mp4")
        val expected = File(storagePaths.userdataRoot.canonicalFile, "movies/test.mp4").canonicalFile
        assertEquals(expected, result)
    }

    @Test
    fun safeResolveUserData_repeated_slashes_normalized() {
        val result = storagePaths.safeResolveUserData("a//b///c")
        val expected = File(storagePaths.userdataRoot.canonicalFile, "a/b/c").canonicalFile
        assertEquals(expected, result)
    }

    @Test
    fun safeResolveUserData_lookalike_prefix_rejected() {
        // Create a sibling directory "userdata-evil" under filesDir
        val sibling = File(storagePaths.userdataRoot.parentFile, "userdata-evil")
        sibling.mkdirs()
        // Traversal from userdata to sibling should be rejected
        assertThrows(SecurityException::class.java) {
            storagePaths.safeResolveUserData("../userdata-evil/evil.txt")
        }
    }

    @Test
    fun safeResolveUserData_internal_dotdot_collapsed() {
        // "movies/../tv" should collapse to "tv" and be safe
        val dir = File(storagePaths.userdataRoot, "tv")
        dir.mkdirs()
        val result = storagePaths.safeResolveUserData("movies/../tv/show.mp4")
        val expected = File(storagePaths.userdataRoot.canonicalFile, "tv/show.mp4").canonicalFile
        assertEquals(expected, result)
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
