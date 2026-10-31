package cn.vectory.syncplayer.storage.volume

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes

/**
 * 普通 File 后端卷（内部与外部都用它；构造传 volumeId/root，不硬编码 internal）。
 *
 * 路径安全：拒绝绝对路径、空段、`..`/`.` 段；canonical 包含关系校验确保不逃出 root；
 * 符号链接末级按 NOFOLLOW 语义处理（strictStat 非 regular ⇒ IoError；openRead 对
 * 末级符号链接直接拒绝——宁可 IO 失败也不跟随到卷外）。
 */
class FileVolume(
    override val volumeId: String,
    private val rootDir: File,
) : MediaVolume {

    init {
        require(rootDir.isDirectory || rootDir.mkdirs()) { "volume root unavailable" }
    }

    private fun resolve(relPath: String): File {
        require(!relPath.startsWith('/')) { "absolute path rejected" }
        val segments = relPath.split('/')
        require(segments.isNotEmpty() && segments.none { it.isEmpty() || it == ".." || it == "." }) {
            "invalid relative path"
        }
        val rootCanonical = rootDir.canonicalFile
        val file = File(rootCanonical, relPath).canonicalFile
        if (file != rootCanonical && !file.path.startsWith(rootCanonical.path + File.separator)) {
            throw IOException("path escapes volume root")
        }
        return file
    }

    override fun openRead(relPath: String): SeekableReadHandle {
        val f = resolve(relPath)
        // A9：末级符号链接不跟随（readAttributes NOFOLLOW 命中链接自身 ⇒ 非 regular）。
        val attrs = readAttributes(f)
        if (!attrs.isRegularFile) throw IOException("not a regular file")
        val raf = RandomAccessFile(f, "r")
        return object : SeekableReadHandle {
            override fun seek(position: Long) = raf.seek(position)
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                raf.read(buffer, offset, length)
            override fun size(): Long = raf.length()
            override fun close() = raf.close()
        }
    }

    override fun openWrite(relPath: String): OutputStream {
        val f = resolve(relPath)
        f.parentFile?.mkdirs()
        return FileOutputStream(f)
    }

    override fun atomicRename(fromRel: String, toRel: String): Boolean {
        val src = resolve(fromRel)
        val dst = resolve(toRel)
        dst.parentFile?.mkdirs()
        return src.renameTo(dst)
    }

    override fun strictStat(relPath: String): FileStat {
        val f = try {
            resolve(relPath)
        } catch (_: Exception) {
            return FileStat.IoError("resolve")
        }
        return try {
            val attrs = readAttributes(f)
            if (!attrs.isRegularFile) FileStat.IoError("not-regular")
            else FileStat.Exists(attrs.size())
        } catch (_: NoSuchFileException) {
            FileStat.Missing
        } catch (_: IOException) {
            FileStat.IoError("io")
        } catch (_: SecurityException) {
            FileStat.IoError("security")
        }
    }

    override fun delete(relPath: String): Boolean = try {
        resolve(relPath).delete()
    } catch (_: Exception) {
        false
    }

    override fun listOwned(dirRelPath: String): List<String>? = try {
        resolve(dirRelPath).list()?.toList() // null = 不可信查询，原样上抛
    } catch (_: Exception) {
        null
    }

    override fun usableSpace(): Long = try {
        rootDir.usableSpace
    } catch (_: Exception) {
        -1L
    }

    private fun readAttributes(f: File): BasicFileAttributes =
        Files.readAttributes(f.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
}
