package cn.vectory.syncplayer.storage

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes

/**
 * A7 本地 final 完整性探测类型（预览只读：只判定，绝不写 DB/文件）。
 *
 * 语义红线：
 *  - 探测结果四态 [MediaProbeState]，由**单次**文件属性读取取得
 *    存在性/regular/长度（无 File.exists 吞异常后再判缺失的二次路径）；
 *  - 仅明确 [NoSuchFileException] 判 MISSING；权限/其它 IO/非 regular/
 *    一切不确定 ⇒ IO_ERROR（fail-closed，绝不猜测）；
 *  - 不产生日志：不泄漏 path 或底层 message。
 *
 * 探测核心 [MediaFinalProbeCore] 为阻塞调用，调用方负责 IO dispatcher
 * （[MediaFileStore.probeFinalized] / scanner 侧挂起包装）。
 */

/** final 完整性探测结果。 */
enum class MediaProbeState {
    /** 存在、regular、长度与 DB 记录一致——扫描可按原逻辑 SKIP。 */
    VALID,

    /** 明确不存在（仅 [NoSuchFileException]）——可生成同 id DOWNLOAD 修复候选。 */
    MISSING,

    /** 存在但长度 ≠ DB 记录（截断/异常增长）——同 id DOWNLOAD 修复候选。 */
    SIZE_MISMATCH,

    /** 权限/IO/非 regular/不确定——整次扫描按存储故障处理（fail-closed）。 */
    IO_ERROR,
}

/**
 * 可注入的单次文件属性读取（生产 = NIO readAttributes；测试注入受控故障，
 * 权限语义不以特权环境假装通过）。
 */
fun interface FileAttributeReader {
    @Throws(IOException::class)
    fun read(path: File): BasicFileAttributes
}

/**
 * 生产属性读取：单次 [Files.readAttributes] 同步取得存在性/regular/size。
 *
 * A9：同一读取携带 [LinkOption.NOFOLLOW_LINKS]——final 位置的**末级**符号链接
 * 取链接自身属性（isRegularFile=false ⇒ IO_ERROR），等长/异长/dangling 一律
 * fail-closed，不因跟随到目标而误判 VALID/SIZE_MISMATCH。
 * 仅处理末级链接：中间目录仍按 NIO 默认解析，本探针不声称
 * 中间目录防穿越能力（安全架构不变）。
 */
object NioFileAttributeReader : FileAttributeReader {
    override fun read(path: File): BasicFileAttributes =
        Files.readAttributes(path.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
}

/**
 * 探测核心（storage 包内部共享）。路径解析与 [MediaFileStore.mediaFile]
 * 同源（[MediaPaths.mediaRelPath]），不做任何写入、不产生任何日志。
 */
internal object MediaFinalProbeCore {

    fun probe(root: File, id: Long, expectedSize: Long, reader: FileAttributeReader): MediaProbeState {
        val file = File(root, MediaPaths.mediaRelPath(id))
        val attrs = try {
            reader.read(file)
        } catch (_: NoSuchFileException) {
            return MediaProbeState.MISSING
        } catch (_: IOException) {
            return MediaProbeState.IO_ERROR
        } catch (_: SecurityException) {
            return MediaProbeState.IO_ERROR
        }
        if (!attrs.isRegularFile) return MediaProbeState.IO_ERROR
        return if (attrs.size() == expectedSize) MediaProbeState.VALID else MediaProbeState.SIZE_MISMATCH
    }
}

/** 挂起版探测接口（scanner 等调用方注入；实现自持 IO 调度）。 */
fun interface MediaFinalProbe {
    suspend fun probe(id: Long, expectedSize: Long): MediaProbeState
}
