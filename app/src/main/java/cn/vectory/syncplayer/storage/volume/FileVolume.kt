package cn.vectory.syncplayer.storage.volume

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
    override suspend fun mkdirs(dirRelPath: String): Unit = withContext(Dispatchers.IO) {
        resolve(dirRelPath).mkdirs()
    }
    override suspend fun rename(from: String, to: String): Boolean = withContext(Dispatchers.IO) {
        val src = resolve(from); val dst = resolve(to); dst.parentFile?.mkdirs()
        src.renameTo(dst)
    }
    override fun usableSpace(): Long = root.usableSpace
}
