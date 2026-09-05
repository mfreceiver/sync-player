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

    /**
     * 严格存在性（BUG-007，v3.1 收窄）：仅「**全路径完整查询且确认未命中**」返回 false；
     * 一切「查询不可信/不完整」形态**上抛** IOException（由 probePendingTmp 收敛 UNKNOWN），
     * 绝不与「不存在」合并——「误报缺失」在 tmp 探测语境 = 误授权删旧 final（数据丢失）。
     */
    suspend fun existsStrict(relPath: String): Boolean
}