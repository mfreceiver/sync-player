package cn.vectory.syncplayer.storage.volume

import java.io.Closeable
import java.io.IOException
import java.io.OutputStream

/** 随机读句柄（seek/read 串行性由调用方持锁保证）。 */
interface SeekableReadHandle : Closeable {
    fun seek(position: Long)
    fun read(buffer: ByteArray, offset: Int, length: Int): Int
    fun size(): Long
}

/** strictStat 三态：仅「卷身份/根健康下的明确缺失」= MISSING。 */
sealed class FileStat {
    data class Exists(val size: Long) : FileStat()
    data object Missing : FileStat()
    data class IoError(val reason: String) : FileStat()
}

/**
 * 哑字节后端卷：openRead/openWrite/atomicRename/strictStat/delete/listOwned/space。
 * 不能知道 AES（加解密在 crypto 层）。路径只由 id/dataset/generation 派生；
 * 实现必须校验根内包含关系、拒绝绝对路径/../符号链接与越界，且不将
 * File.exists=false 或 listFiles=null 当作「不存在/空卷」（那由 strictStat/listOwned
 * 的三态与上层 fail-closed 处理）。
 */
interface MediaVolume {
    val volumeId: String

    /** 打开随机读句柄；文件缺失/IO 失败抛 [IOException]。 */
    @Throws(IOException::class)
    fun openRead(relPath: String): SeekableReadHandle

    /** 打开覆盖写流（父目录自动创建）。 */
    @Throws(IOException::class)
    fun openWrite(relPath: String): OutputStream

    /** 同卷同目录原子 rename；返回 false = rename 未发生（绝不退化 copy+delete）。 */
    fun atomicRename(fromRel: String, toRel: String): Boolean

    /** 三态严格 stat（A9 NOFOLLOW 语义：末级符号链接 ⇒ IoError）。 */
    fun strictStat(relPath: String): FileStat

    /** 删除；文件不存在返回 false（幂等）。 */
    fun delete(relPath: String): Boolean

    /** 列目录条目名；listFiles=null（不可信查询）返回 null，绝不返回空列表冒充。 */
    fun listOwned(dirRelPath: String): List<String>?

    /** 本卷可用空间（字节）。不可查询返回 -1（上层转 SpaceResult.Unavailable）。 */
    fun usableSpace(): Long
}
