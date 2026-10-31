package cn.vectory.syncplayer.storage.volume

import cn.vectory.syncplayer.storage.VolumeIds

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileVolumeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun volume(): FileVolume = FileVolume(VolumeIds.INTERNAL, tmp.newFolder())

    @Test
    fun writeReadRenameDeleteRoundtrip() {
        val v = volume()
        v.openWrite("a/b/c.bin").use { it.write(byteArrayOf(1, 2, 3)) }
        assertEquals(FileStat.Exists(3L), v.strictStat("a/b/c.bin"))
        v.openRead("a/b/c.bin").use { h ->
            val buf = ByteArray(3)
            assertEquals(3, h.read(buf, 0, 3))
            assertTrue(buf.contentEquals(byteArrayOf(1, 2, 3)))
        }
        assertTrue(v.atomicRename("a/b/c.bin", "a/b/c.final"))
        assertEquals(FileStat.Missing, v.strictStat("a/b/c.bin"))
        assertEquals(FileStat.Exists(3L), v.strictStat("a/b/c.final"))
        assertTrue(v.delete("a/b/c.final"))
        assertFalse(v.delete("a/b/c.final")) // 幂等
    }

    @Test
    fun traversalAndAbsolutePathsRejected() {
        val v = volume()
        listOf("../escape", "a/../../escape", "/abs", "a//b", "./a").forEach { rel ->
            // strictStat fail-soft：拒绝解析 ⇒ IoError（不抛出、不泄露）
            assertTrue("expected IoError for $rel", v.strictStat(rel) is FileStat.IoError)
            // openRead fail-fast：拒绝解析直接抛（不可读）
            val threw = try {
                v.openRead(rel); false
            } catch (_: Exception) {
                true
            }
            assertTrue("expected rejection for $rel", threw)
        }
        // strictStat 对可解析路径不抛（IoError 封装在返回值）——Missing 语义正确
        assertEquals(FileStat.Missing, v.strictStat("absent"))
    }

    @Test
    fun symlinkFinalSegmentIsIoErrorNotFollowed() {
        val root = tmp.newFolder()
        val v = FileVolume(VolumeIds.INTERNAL, root)
        val target = tmp.newFile()
        Files.createSymbolicLink(
            File(root, "link.bin").toPath(),
            target.toPath()
        )
        assertTrue(v.strictStat("link.bin") is FileStat.IoError)
    }

    @Test
    fun tmpThenAtomicRenameStaysInVolume() {
        val v = volume()
        v.openWrite("x/y.tmp").use { it.write(byteArrayOf(9)) }
        assertTrue(v.atomicRename("x/y.tmp", "x/y.enc"))
        assertEquals(FileStat.Exists(1L), v.strictStat("x/y.enc"))
    }

    @Test
    fun openWriteMissingVolumeRootFailsFast() {
        // 父级是普通文件 ⇒ mkdirs 必败 ⇒ 构造即 fail-fast
        val blocker = File(tmp.newFolder(), "not-a-dir")
        blocker.writeBytes(byteArrayOf(1))
        val bad = File(blocker, "deeper")
        val threw = try {
            FileVolume("external:u", bad); false
        } catch (_: Exception) {
            true
        }
        assertTrue(threw)
    }
}
