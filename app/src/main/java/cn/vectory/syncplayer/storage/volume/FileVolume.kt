package cn.vectory.syncplayer.storage.volume

import android.annotation.SuppressLint
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FileVolume(val root: File) : MediaVolume {
    override val volumeId: String = "internal"
    private fun resolve(relPath: String): File {
        require(!relPath.startsWith('/') && relPath.split('/').none { it == ".." })
        val file = File(root, relPath).canonicalFile
        require(file.toPath().startsWith(root.canonicalFile.toPath())) { "path escapes volume" }
        return file
    }

    override suspend fun openWrite(relPath: String): OutputStream = withContext(Dispatchers.IO) {
        val f = resolve(relPath); f.parentFile?.mkdirs()
        FileOutputStream(f)
    }
    override suspend fun openRead(relPath: String): SeekableReadHandle = withContext(Dispatchers.IO) {
        val raf = RandomAccessFile(resolve(relPath), "r")
        object : SeekableReadHandle {
            override fun seek(position: Long) { raf.seek(position) }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = raf.read(buffer, offset, length)
            override fun size(): Long = raf.length()
            override fun close() { raf.close() }
        }
    }
    override suspend fun list(dirRelPath: String): List<String> = withContext(Dispatchers.IO) {
        resolve(dirRelPath).list()?.toList() ?: emptyList()
    }
    override suspend fun delete(relPath: String): Boolean = withContext(Dispatchers.IO) {
        resolve(relPath).delete()
    }
    override suspend fun exists(relPath: String): Boolean = withContext(Dispatchers.IO) {
        resolve(relPath).exists()
    }

    /**
     * BUG-007 existsStrict：**不得用 File.exists()**（其 false 混淆「不存在」与「访问失败」）。
     * 经 Files.readAttributes 读文件属性：NoSuchFileException ⇒ false（确认缺失）；
     * 其余 IOException（访问拒绝 / ENOTDIR 类——如父路径是普通文件）上抛，绝不折算「不存在」。
     */
    override suspend fun existsStrict(relPath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            java.nio.file.Files.readAttributes(
                resolve(relPath).toPath(),
                java.nio.file.attribute.BasicFileAttributes::class.java
            )
            true
        } catch (e: java.nio.file.NoSuchFileException) {
            false
        }
    }
    override suspend fun mkdirs(dirRelPath: String): Unit = withContext(Dispatchers.IO) {
        resolve(dirRelPath).mkdirs()
    }
    override suspend fun rename(from: String, to: String): Boolean = withContext(Dispatchers.IO) {
        val src = resolve(from); val dst = resolve(to); dst.parentFile?.mkdirs()
        src.renameTo(dst)
    }
    // Deliberately conservative fail-safe pre-check (SyncEngine contract): reports only
    // currently-available bytes and intentionally ignores evictable cache, so a
    // pessimistic estimate fails the sync instead of risking ENOSPC mid-write.
    @SuppressLint("UsableSpace")
    override fun usableSpace(): Long = root.usableSpace
}
