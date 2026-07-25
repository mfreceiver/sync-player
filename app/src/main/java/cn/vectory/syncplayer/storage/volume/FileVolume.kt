package cn.vectory.syncplayer.storage.volume

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile

class FileVolume(val root: File) : MediaVolume {
    override val volumeId: String = "internal"
    private fun resolve(relPath: String): File {
        require(!relPath.startsWith('/') && relPath.split('/').none { it == ".." })
        val file = File(root, relPath).canonicalFile
        require(file.toPath().startsWith(root.canonicalFile.toPath())) { "path escapes volume" }
        return file
    }

    override suspend fun openWrite(relPath: String): OutputStream {
        val f = resolve(relPath); f.parentFile?.mkdirs()
        return FileOutputStream(f)
    }
    override suspend fun openRead(relPath: String): SeekableReadHandle {
        val raf = RandomAccessFile(resolve(relPath), "r")
        return object : SeekableReadHandle {
            override fun seek(position: Long) { raf.seek(position) }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = raf.read(buffer, offset, length)
            override fun size(): Long = raf.length()
            override fun close() { raf.close() }
        }
    }
    override suspend fun list(dirRelPath: String): List<String> = resolve(dirRelPath).list()?.toList() ?: emptyList()
    override suspend fun delete(relPath: String): Boolean = resolve(relPath).delete()
    override suspend fun exists(relPath: String): Boolean = resolve(relPath).exists()
    override suspend fun mkdirs(dirRelPath: String) { resolve(dirRelPath).mkdirs() }
    override suspend fun rename(from: String, to: String): Boolean {
        val src = resolve(from); val dst = resolve(to); dst.parentFile?.mkdirs()
        return src.renameTo(dst)
    }
    override fun usableSpace(): Long = root.usableSpace
}