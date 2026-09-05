package cn.vectory.syncplayer.storage.volume

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * BUG-007 存储层组（计划 S10）：[FileVolume.existsStrict] 区分「确认缺失」与「访问失败」。
 *
 * 锁定实现不得用 `File.exists()`（其 false 混淆不存在与访问错误——FileVolume.kt 现状即如此）：
 * 经 Files.readAttributes 读属性——NoSuchFileException ⇒ false；其余 IOException
 * （父路径为普通文件的 ENOTDIR 类 FileSystemException）上抛，绝不误报 false——
 * 误报缺失 = 误授权删旧 final（数据丢失）。纯 JVM，无 Android 依赖。
 *
 * 断言异常类型与布尔结果——不断言用户文案。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FileVolumeExistsStrictTest {

    private lateinit var root: Path
    private lateinit var volume: FileVolume

    @Before
    fun setup() {
        root = Files.createTempDirectory("file-volume-strict-")
        volume = FileVolume(root.toFile())
    }

    @After
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `existsStrict distinguishes absence from access error`() = runTest {
        // 正向对照：存在的文件 → true
        val dir = Files.createDirectories(root.resolve("0"))
        val file = Files.createFile(dir.resolve("42.enc"))
        assertTrue(volume.existsStrict("0/42.enc"))

        // 不存在的路径（父目录存在）→ false（确认缺失）
        assertFalse(volume.existsStrict("0/absent.enc"))

        // 父路径是普通文件的子路径：属性读失败为 ENOTDIR 类 FileSystemException，
        // 非 NoSuchFileException → 上抛 IOException，不得误报 false
        val plainFile = Files.createFile(root.resolve("plain"))
        val thrown = runCatching { volume.existsStrict("plain/child.enc") }.exceptionOrNull()

        assertNotNull("access failure must never be collapsed into ABSENT", thrown)
        assertTrue("expected IOException but was $thrown", thrown is IOException)
    }
}
