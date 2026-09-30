package cn.vectory.syncplayer.storage

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes

/**
 * A7 本地 final 完整性探测（预览只读）。
 *
 * 语义（红线：fail-closed）：
 *  - 单次文件属性读取（NIO readAttributes NOFOLLOW：存在性/regular/size 一次取得），
 *    绝不 File.exists 吞异常后再判缺失；
 *  - 仅明确 [NoSuchFileException] ⇒ MISSING；权限/其它 IO/非 regular（含末级
 *    符号链接，无论目标等长/异长/dangling）/不确定 ⇒ IO_ERROR；
 *  - 阻塞属性读取位于 IO dispatcher（store 侧 withContext）。
 *
 * A9 权限语义拆分：
 *  - EACCES ⇒ IO_ERROR 由受控 [AccessDeniedException] 注入用例**必测**（确定性）；
 *  - 内容不可读但属性可读仍 VALID——本探针只校验 regular+size，不是内容
 *    可读性/同长损坏校验；
 *  - 真实 EACCES fixture 仅在**独立同形 NIO 查询**先行确认拒绝生效时运行，
 *    能力不足（特权环境）则 Assume 跳过——绝不以 SUT 返回值反推前提。
 * 本文件所有断言为静态预期，未在本机执行（verifier 隔离 live）。
 */
class MediaFinalProbeTest {

    private lateinit var rootDir: File
    private lateinit var storagePaths: StoragePaths
    private lateinit var store: MediaFileStore

    private val expectedBytes = ByteArray(1000) { (it % 251).toByte() }

    @Before
    fun setUp() {
        rootDir = File(System.getProperty("java.io.tmpdir"), "media-final-probe-${System.nanoTime()}")
        val context = mockk<Context>()
        every { context.filesDir } returns File(rootDir, "files")
        storagePaths = StoragePaths(context)
        store = MediaFileStore(storagePaths, Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        rootDir.setWritable(true)
        rootDir.deleteRecursively()
    }

    private fun placeFinal(id: Long, bytes: ByteArray) {
        val f = store.mediaFile(id)
        f.parentFile.mkdirs()
        f.writeBytes(bytes)
    }

    // ------------------------------------------------ 真实文件系统（MediaFileStore.probeFinalized）

    @Test
    fun `existing final with exact size probes VALID without mutation`() = runTest {
        placeFinal(42L, expectedBytes)

        val state = store.probeFinalized(42L, expectedBytes.size.toLong())

        assertEquals(MediaProbeState.VALID, state)
        // 预览只读：探测不改变文件内容
        assertArrayEquals(expectedBytes, store.mediaFile(42L).readBytes())
    }

    @Test
    fun `missing final probes MISSING`() = runTest {
        assertEquals(MediaProbeState.MISSING, store.probeFinalized(42L, 1000L))
    }

    /** 只读红线：探测绝不创建文件/目录（StoragePaths 自身的 userdataRoot mkdirs 为既有行为，不计入）。 */
    @Test
    fun `probe is read-only and creates no files or directories`() = runTest {
        val userdata = storagePaths.userdataRoot // 预置基线（含 StoragePaths 自身 mkdirs 既有行为）
        val before = userdata.walkTopDown().toList().toSet()

        val state = store.probeFinalized(42L, 1000L)

        assertEquals(MediaProbeState.MISSING, state)
        assertEquals(
            "probe must not create the bucket dir or any file under userdata",
            before,
            userdata.walkTopDown().toList().toSet()
        )
    }

    @Test
    fun `short final probes SIZE_MISMATCH`() = runTest {
        placeFinal(42L, expectedBytes.copyOf(999))
        assertEquals(MediaProbeState.SIZE_MISMATCH, store.probeFinalized(42L, 1000L))
    }

    @Test
    fun `long final probes SIZE_MISMATCH`() = runTest {
        placeFinal(42L, expectedBytes.copyOf(1001))
        assertEquals(MediaProbeState.SIZE_MISMATCH, store.probeFinalized(42L, 1000L))
    }

    @Test
    fun `non-regular final (directory at final path) probes IO_ERROR`() = runTest {
        val f = store.mediaFile(42L)
        f.mkdirs()
        assertEquals(MediaProbeState.IO_ERROR, store.probeFinalized(42L, 1000L))
    }

    // ------------------------------------------------ A9: 末级符号链接一律 fail-closed（NOFOLLOW 单次读取）

    private fun placeSymlink(id: Long, target: File) {
        val f = store.mediaFile(id)
        f.parentFile.mkdirs()
        Files.createSymbolicLink(f.toPath(), target.toPath())
    }

    @Test
    fun `final-level symlink to equal-length regular file probes IO_ERROR`() = runTest {
        // fixture 前置：rootDir 先于 target 写入显式成立（store.root 为按需 getter，
        // 构造不创建目录——placeSymlink 的 mkdirs 在写 target 之后，来不及）
        check(rootDir.mkdirs() || rootDir.isDirectory) { "fixture root must exist before writing target" }
        // 目标与期望长度一致——跟随链接会误判 VALID；NOFOLLOW 取链接自身属性 ⇒ 非 regular ⇒ IO_ERROR
        val target = File(rootDir, "symlink-target-${System.nanoTime()}")
        target.writeBytes(expectedBytes)

        placeSymlink(42L, target)

        assertEquals(MediaProbeState.IO_ERROR, store.probeFinalized(42L, 1000L))
    }

    @Test
    fun `final-level symlink to different-length file probes IO_ERROR`() = runTest {
        check(rootDir.mkdirs() || rootDir.isDirectory) { "fixture root must exist before writing target" }
        val target = File(rootDir, "symlink-target-${System.nanoTime()}")
        target.writeBytes(expectedBytes.copyOf(999))

        placeSymlink(42L, target)

        assertEquals(MediaProbeState.IO_ERROR, store.probeFinalized(42L, 1000L))
    }

    @Test
    fun `dangling final-level symlink probes IO_ERROR not MISSING`() = runTest {
        // NOFOLLOW 下 dangling 链接仍可读链接自身属性（非 NoSuchFile）⇒ 非 regular ⇒ IO_ERROR
        val gone = File(rootDir, "gone-${System.nanoTime()}") // 从不创建

        placeSymlink(42L, gone)

        assertEquals(MediaProbeState.IO_ERROR, store.probeFinalized(42L, 1000L))
    }

    // ------------------------------------------------ A9: 权限语义拆分

    /**
     * 内容不可读但属性可读 ⇒ 仍 VALID：POSIX stat 只需父目录遍历权限，
     * 不需文件读位。本探针不是内容可读性/损坏校验，不得借机扩大语义。
     * 前提「内容确实不可读」用独立内容读取确认，特权环境可读则跳过。
     */
    @Test
    fun `content unreadable but attributes readable still probes VALID`() = runTest {
        placeFinal(42L, expectedBytes)
        val f = store.mediaFile(42L)
        assumeTrue("fixture must drop content read permission", f.setReadable(false, false))
        try {
            val contentUnreadable = try {
                f.inputStream().use { it.read() }
                false
            } catch (expected: IOException) {
                true
            }
            assumeTrue("content still readable (privileged env) — case not meaningful", contentUnreadable)

            assertEquals(MediaProbeState.VALID, store.probeFinalized(42L, 1000L))
        } finally {
            f.setReadable(true, true)
        }
    }

    /**
     * 真实 EACCES-on-stat：剥夺**父目录遍历权限**使属性读取本身被拒。
     * 前提用独立同形 NIO readAttributes 确认（绝不用 SUT 结果反推）；
     * 特权环境下独立查询仍成功 ⇒ Assume 跳过，不假称实测 EACCES。
     */
    @Test
    fun `parent traversal denial makes real probe IO_ERROR under verified EACCES`() = runTest {
        placeFinal(42L, expectedBytes)
        val parent = store.mediaFile(42L).parentFile!!
        try {
            assumeTrue("fixture must drop traversal permission", parent.setExecutable(false, false))
            val statDenied = try {
                Files.readAttributes(store.mediaFile(42L).toPath(), BasicFileAttributes::class.java)
                false
            } catch (expected: AccessDeniedException) {
                true
            }
            assumeTrue("stat still permitted (privileged env) — real EACCES not observable", statDenied)

            assertEquals(
                "traversal denial must be IO_ERROR, never MISSING/VALID",
                MediaProbeState.IO_ERROR,
                store.probeFinalized(42L, 1000L)
            )
        } finally {
            parent.setExecutable(true, true) // 恢复遍历，保证 tearDown 能递归删除
        }
    }

    // ------------------------------------------------ 受控注入（MediaFinalProbeCore + FileAttributeReader）

    @Test
    fun `explicit NoSuchFileException maps to MISSING`() {
        val reader = FileAttributeReader { throw NoSuchFileException("anywhere") }
        assertEquals(
            MediaProbeState.MISSING,
            MediaFinalProbeCore.probe(File("/nonexistent-root"), 42L, 1000L, reader)
        )
    }

    @Test
    fun `generic IOException maps to IO_ERROR`() {
        val reader = FileAttributeReader { throw IOException("disk tantrum") }
        assertEquals(
            MediaProbeState.IO_ERROR,
            MediaFinalProbeCore.probe(File("/nonexistent-root"), 42L, 1000L, reader)
        )
    }

    /** A9 必测：EACCES（AccessDeniedException 是 FileSystemException 子类，非 NoSuchFile）⇒ IO_ERROR。 */
    @Test
    fun `AccessDeniedException from controlled reader maps to IO_ERROR`() {
        val reader = FileAttributeReader { throw AccessDeniedException("final") }
        assertEquals(
            MediaProbeState.IO_ERROR,
            MediaFinalProbeCore.probe(File("/nonexistent-root"), 42L, 1000L, reader)
        )
    }

    @Test
    fun `SecurityException maps to IO_ERROR`() {
        val reader = FileAttributeReader { throw SecurityException("denied") }
        assertEquals(
            MediaProbeState.IO_ERROR,
            MediaFinalProbeCore.probe(File("/nonexistent-root"), 42L, 1000L, reader)
        )
    }

    @Test
    fun `non-regular attributes map to IO_ERROR`() {
        val attrs = mockk<BasicFileAttributes>()
        every { attrs.isRegularFile } returns false
        val reader = FileAttributeReader { attrs }
        assertEquals(
            MediaProbeState.IO_ERROR,
            MediaFinalProbeCore.probe(File("/nonexistent-root"), 42L, 1000L, reader)
        )
    }

    @Test
    fun `regular attributes with exact size map to VALID`() {
        val attrs = mockk<BasicFileAttributes>()
        every { attrs.isRegularFile } returns true
        every { attrs.size() } returns 1000L
        val reader = FileAttributeReader { attrs }
        assertEquals(
            MediaProbeState.VALID,
            MediaFinalProbeCore.probe(File("/nonexistent-root"), 42L, 1000L, reader)
        )
    }

    @Test
    fun `regular attributes with wrong size map to SIZE_MISMATCH`() {
        val attrs = mockk<BasicFileAttributes>()
        every { attrs.isRegularFile } returns true
        every { attrs.size() } returns 999L
        val reader = FileAttributeReader { attrs }
        assertEquals(
            MediaProbeState.SIZE_MISMATCH,
            MediaFinalProbeCore.probe(File("/nonexistent-root"), 42L, 1000L, reader)
        )
    }

    /** 单次读取红线：探测恰好调用 reader 一次（无 exists+attrs 双查）。 */
    @Test
    fun `probe performs exactly one attribute read`() {
        var reads = 0
        val attrs = mockk<BasicFileAttributes>()
        every { attrs.isRegularFile } returns true
        every { attrs.size() } returns 1000L
        val reader = FileAttributeReader {
            reads++
            attrs
        }
        MediaFinalProbeCore.probe(File("/nonexistent-root"), 42L, 1000L, reader)
        assertEquals(1, reads)
        // 第二次探测同样只读一次：防止未来实现引入 exists+attrs 双查路径
        MediaFinalProbeCore.probe(File("/nonexistent-root"), 42L, 1000L, reader)
        assertEquals(2, reads)
    }
}
