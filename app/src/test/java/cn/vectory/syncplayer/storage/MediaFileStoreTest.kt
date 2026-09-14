package cn.vectory.syncplayer.storage

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException

class MediaFileStoreTest {

    private lateinit var rootDir: File
    private lateinit var storagePaths: StoragePaths
    private lateinit var store: MediaFileStore

    @Before
    fun setup() {
        rootDir = File(System.getProperty("java.io.tmpdir"), "media-store-test-${System.nanoTime()}")
        rootDir.mkdirs()
        val context = mockk<android.content.Context>()
        every { context.filesDir } returns File(rootDir, "files")
        storagePaths = StoragePaths(context)
        store = MediaFileStore(storagePaths, Dispatchers.Unconfined)
    }

    @After
    fun cleanup() {
        rootDir.walkBottomUp().forEach {
            it.setReadable(true, false)
            it.setWritable(true, false)
            it.setExecutable(true, false)
        }
        rootDir.deleteRecursively()
    }

    @Test
    fun `checkExistence returns EXISTS when media file exists on disk`() = runTest {
        val id = 42L
        val file = store.mediaFile(id)
        file.parentFile?.mkdirs()
        file.writeBytes(byteArrayOf(1, 2, 3))

        val result = store.checkExistence(id)

        assertEquals(MediaExistence.EXISTS, result)
        assertTrue(store.existsStrict(id))
    }

    @Test
    fun `checkExistence returns NOT_FOUND when media file is missing`() = runTest {
        val id = 99L

        val result = store.checkExistence(id)

        assertEquals(MediaExistence.NOT_FOUND, result)
        assertFalse(store.existsStrict(id))
    }

    @Test
    fun `checkThumbExistence returns EXISTS when thumb file exists on disk`() = runTest {
        val id = 42L
        val thumb = store.thumbFile(id)
        thumb.parentFile?.mkdirs()
        thumb.writeBytes(byteArrayOf(4, 5, 6))

        val result = store.checkThumbExistence(id)

        assertEquals(MediaExistence.EXISTS, result)
        assertTrue(store.thumbExistsStrict(id))
    }

    @Test
    fun `checkThumbExistence returns NOT_FOUND when thumb file is missing`() = runTest {
        val id = 99L

        val result = store.checkThumbExistence(id)

        assertEquals(MediaExistence.NOT_FOUND, result)
        assertFalse(store.thumbExistsStrict(id))
    }

    @Test
    fun `checkExistence returns IO_ERROR when attributeReader throws AccessDeniedException`() = runTest {
        val id = 101L
        store.attributeReader = { throw AccessDeniedException(it.path, null, "Permission denied") }

        val result = store.checkExistence(id)

        assertTrue("Expected IO_ERROR, got $result", result is MediaExistence.IO_ERROR)
        assertEquals("AccessDeniedException", (result as MediaExistence.IO_ERROR).reason)
    }

    @Test
    fun `checkExistence returns IO_ERROR when attributeReader throws FileSystemException`() = runTest {
        val id = 102L
        store.attributeReader = { throw FileSystemException(it.path, null, "Mount failure") }

        val result = store.checkExistence(id)

        assertTrue("Expected IO_ERROR, got $result", result is MediaExistence.IO_ERROR)
        assertEquals("FileSystemException", (result as MediaExistence.IO_ERROR).reason)
    }

    @Test
    fun `existsStrict throws IOException when attributeReader encounters access or mount errors`() = runTest {
        val id = 103L
        store.attributeReader = { throw AccessDeniedException(it.path, null, "Permission denied") }

        try {
            store.existsStrict(id)
            fail("existsStrict must throw on AccessDeniedException")
        } catch (e: AccessDeniedException) {
            // Success
        }
    }

    @Test
    fun `checkExistence propagates CancellationException immediately`() = runTest {
        val id = 104L
        store.attributeReader = { throw CancellationException("cancel probe") }

        try {
            store.checkExistence(id)
            fail("checkExistence must rethrow CancellationException")
        } catch (e: CancellationException) {
            assertEquals("cancel probe", e.message)
        }
    }

    @Test
    fun `real filesystem permission denied reports IO_ERROR instead of NOT_FOUND`() = runTest {
        val id = 105L
        val file = store.mediaFile(id)
        val parent = file.parentFile!!
        parent.mkdirs()
        file.writeBytes(byteArrayOf(1, 2, 3))

        // Strip read/execute permissions from the parent directory
        val unexecutable = parent.setExecutable(false, false)
        val unreadable = parent.setReadable(false, false)

        if (unexecutable && unreadable) {
            // chmod 在 root（CI 容器默认用户）下返回成功，但 CAP_DAC_OVERRIDE 绕过 DAC 检查，
            // 永不产生 EACCES —— 权限模型在该环境下不可强制，EACCES 路径无法复现。
            // assumeTrue → 测试计为 skipped 而非 failed；EACCES→IO_ERROR 的映射逻辑
            // 已由上方 attributeReader 注入式测试覆盖。
            assumeTrue(
                "EACCES not enforceable (running as root/privileged); skipping real-FS probe",
                !parent.canRead()
            )
            try {
                val result = store.checkExistence(id)
                assertTrue(
                    "Permission denied must produce IO_ERROR, never NOT_FOUND (got $result)",
                    result is MediaExistence.IO_ERROR
                )
            } finally {
                parent.setReadable(true, false)
                parent.setExecutable(true, false)
            }
        }
    }
}
