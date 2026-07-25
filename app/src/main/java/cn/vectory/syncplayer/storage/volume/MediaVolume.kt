package cn.vectory.syncplayer.storage.volume

import java.io.Closeable
import java.io.OutputStream

interface SeekableReadHandle : Closeable {
    fun seek(position: Long)
    fun read(buffer: ByteArray, offset: Int, length: Int): Int
    fun size(): Long
}

interface MediaVolume {
    val volumeId: String
    suspend fun openWrite(relPath: String): OutputStream
    suspend fun openRead(relPath: String): SeekableReadHandle
    suspend fun list(dirRelPath: String): List<String>
    suspend fun delete(relPath: String): Boolean
    suspend fun exists(relPath: String): Boolean
    suspend fun mkdirs(dirRelPath: String)
    suspend fun rename(from: String, to: String): Boolean
    fun usableSpace(): Long
}