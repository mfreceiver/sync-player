package cn.vectory.syncplayer.storage

import androidx.annotation.VisibleForTesting
import cn.vectory.syncplayer.di.IoDispatcher
import cn.vectory.syncplayer.sync.SyncErrorCode
import cn.vectory.syncplayer.sync.SyncException
import cn.vectory.syncplayer.sync.SyncOperation
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Result of [MediaFileStore.checkExistence]. */
sealed class MediaExistence {
    data object EXISTS : MediaExistence()
    data object NOT_FOUND : MediaExistence()
    data class IO_ERROR(val reason: String) : MediaExistence()
}

/** tmp 文件存在性三态探测结果。 */
enum class TmpProbeState { PRESENT, ABSENT, UNKNOWN }

/**
 * 媒体文件的本地存储门面（单卷 = `filesDir/userdata`，平文明文）。
 *
 * 职责：下载 sink（tmp → finalize 两阶段提交）、随机读文件解析、缩略图读写、
 * 删除/存在性探测、可用空间、孤儿清扫。崩溃安全契约（与加密无关，历史沿用）：
 *
 *  - 下载先写 `MediaPaths.TMP_SUFFIX` 临时文件，[finalize] 原子 rename 为 final；
 *  - [openWriteSink] 返回的流在 close 时校验写入字节数 == 预期长度，不等则抛
 *    [IOException]（传输截断绝不落地为 final）；
 *  - [probePendingTmp] 三态探测：仅「严格确认未命中」返回 ABSENT，一切不可信查询
 *    返回 UNKNOWN，调用方保守处理（只删行、不删 final）。
 *
 * 单卷后不再有卷所有权语义：volumeId 概念随多卷支持一并移除。
 */
@Singleton
class MediaFileStore @Inject constructor(
    private val storagePaths: StoragePaths,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val root: File get() = storagePaths.userdataRoot

    // ---- 路径解析（仅由 Long id 派生，无用户输入，无遍历风险） ----

    fun mediaFile(id: Long): File = File(root, MediaPaths.mediaRelPath(id))

    fun thumbFile(id: Long): File = File(root, MediaPaths.thumbRelPath(id))

    private fun tmpMediaFile(id: Long): File = File(root, MediaPaths.mediaRelPath(id) + MediaPaths.TMP_SUFFIX)

    // ---- 下载写入（两阶段提交） ----

    /**
     * 打开 id [id] 的下载 sink（写入 `.tmp` 临时文件）。返回的流 close 时若写入
     * 字节数 ≠ [expectedLength] 抛 [IOException]（底层流仍会被关闭）；调用方收到
     * 异常后负责 [cancel] 清理 tmp。打开失败抛 [SyncException]（STORAGE_ERROR），
     * UI 可呈现准确文案。
     */
    suspend fun openWriteSink(id: Long, expectedLength: Long): OutputStream {
        require(expectedLength >= 0) { "expectedLength must be non-negative" }
        val tmp = tmpMediaFile(id)
        val raw: FileOutputStream = try {
            withContext(ioDispatcher) {
                tmp.parentFile?.mkdirs()
                FileOutputStream(tmp)
            }
        } catch (e: IOException) {
            throw SyncException(SyncErrorCode.STORAGE_ERROR, SyncOperation.DOWNLOAD, path = MediaPaths.mediaRelPath(id), cause = e)
        }
        return LengthVerifyingOutputStream(raw, expectedLength)
    }

    /** finalize：原子把 `.tmp` 提升为 final。返回 false = rename 未发生。 */
    suspend fun finalize(id: Long): Boolean = withContext(ioDispatcher) {
        val src = tmpMediaFile(id)
        val dst = mediaFile(id)
        dst.parentFile?.mkdirs()
        src.renameTo(dst)
    }

    /** 丢弃 tmp（幂等：文件不存在时 delete 返回 false，无害）。 */
    suspend fun cancel(id: Long) {
        withContext(ioDispatcher) { tmpMediaFile(id).delete() }
    }

    /**
     * tmp 存在性三态探测。ABSENT 仅在底层 [existsStrict] 返回完整、严格的
     * 「确认未命中」时可解读为「rename 已消费 tmp」；UNKNOWN = 探测自身失败，
     * 调用方必须保守处理（只删行、不删 final）。
     */
    suspend fun probePendingTmp(id: Long): TmpProbeState = try {
        if (existsStrict(tmpMediaFile(id))) TmpProbeState.PRESENT else TmpProbeState.ABSENT
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        TmpProbeState.UNKNOWN
    }

    /**
     * A7: final 完整性探测（预览只读）。单次文件属性读取判定
     * [MediaProbeState]：MISSING 仅来自明确 NoSuchFileException，权限/其它
     * IO/非 regular/不确定一律 fail-closed 为 IO_ERROR（绝不用
     * File.exists 吞异常后再判缺失）。阻塞属性读取在 IO dispatcher。
     * 不写入、不产生日志（不泄漏 path/底层 message）。
     */
    suspend fun probeFinalized(id: Long, expectedSize: Long): MediaProbeState =
        withContext(ioDispatcher) {
            MediaFinalProbeCore.probe(root, id, expectedSize, NioFileAttributeReader)
        }

    // ---- 读侧 ----

    /**
     * finalize 后的可读性探针：final 文件存在且长度 == [expectedSize]。
     * 任何失败抛异常（绝不静默通过），调用方的 finalize 补偿链包装为
     * STORAGE_ERROR 并单点回滚。
     */
    suspend fun verifyReadable(id: Long, expectedSize: Long) {
        require(expectedSize >= 0) { "expectedSize must be non-negative: $expectedSize" }
        withContext(ioDispatcher) {
            val f = mediaFile(id)
            if (!f.exists()) {
                throw IOException("read probe: final media missing for id=$id")
            }
            val actual = f.length()
            if (actual != expectedSize) {
                throw IOException("read probe: size $actual != expected $expectedSize")
            }
        }
    }

    // ---- 删除 / 存在性 ----

    @VisibleForTesting
    internal var attributeReader: (File) -> BasicFileAttributes = { file ->
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
    }

    suspend fun delete(id: Long): Boolean = withContext(ioDispatcher) { mediaFile(id).delete() }

    @Deprecated("Use checkExistence for fail-closed 3-state detection", ReplaceWith("checkExistence(id)"))
    suspend fun exists(id: Long): Boolean = withContext(ioDispatcher) { mediaFile(id).exists() }

    @Deprecated("Use checkThumbExistence for fail-closed 3-state detection", ReplaceWith("checkThumbExistence(id)"))
    suspend fun thumbExists(id: Long): Boolean = withContext(ioDispatcher) { thumbFile(id).exists() }

    suspend fun deleteThumb(id: Long): Boolean = withContext(ioDispatcher) { thumbFile(id).delete() }

    /**
     * 严格存在性：仅「全路径完整查询且确认未命中」返回 false；一切「查询不可信/
     * 不完整」形态上抛 IOException/Exception，绝不与「不存在」合并——「误报缺失」在 tmp
     * 探测语境 = 误授权删旧 final（数据丢失）。
     */
    suspend fun existsStrict(file: File): Boolean = withContext(ioDispatcher) {
        try {
            attributeReader(file)
            true
        } catch (e: NoSuchFileException) {
            false
        }
    }

    suspend fun existsStrict(id: Long): Boolean = existsStrict(mediaFile(id))

    suspend fun thumbExistsStrict(id: Long): Boolean = existsStrict(thumbFile(id))

    /**
     * 对指定文件进行三态严格存在性检查。
     * - [MediaExistence.EXISTS]: 成功读取到文件属性（文件确实存在且可访问）
     * - [MediaExistence.NOT_FOUND]: 仅且仅底层确凿抛出 [NoSuchFileException]（文件确实不存在）
     * - [MediaExistence.IO_ERROR]: 权限、挂载、I/O 等其它任何异常（不可推断为不存在）
     * [CancellationException] 始终先传播。
     */
    suspend fun checkFileExistence(file: File): MediaExistence = withContext(ioDispatcher) {
        try {
            attributeReader(file)
            MediaExistence.EXISTS
        } catch (e: NoSuchFileException) {
            MediaExistence.NOT_FOUND
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            MediaExistence.IO_ERROR(e.javaClass.simpleName)
        }
    }

    /**
     * 三态严格存在性检查（孤儿扫描与删除后存在性确认）。
     * - [MediaExistence.EXISTS] / [MediaExistence.NOT_FOUND]: definitive
     * - [MediaExistence.IO_ERROR]: 访问失败；调用方不得视为孤儿或已删除
     */
    suspend fun checkExistence(id: Long): MediaExistence = checkFileExistence(mediaFile(id))

    suspend fun checkThumbExistence(id: Long): MediaExistence = checkFileExistence(thumbFile(id))

    // ---- 空间 ----

    /** 可用空间（阻塞 IO，调用方需在 IO 上下文调用）。错误返回 0。 */
    fun usableSpace(): Long = root.usableSpace

    // ---- 缩略图 ----

    suspend fun writeThumb(id: Long, bytes: ByteArray) {
        withContext(ioDispatcher) {
            val f = thumbFile(id)
            f.parentFile?.mkdirs()
            FileOutputStream(f).use { it.write(bytes) }
        }
    }

    // ---- 清扫（重置存储 / 版本升级孤儿回收） ----

    /**
     * 删除本应用管辖的全部媒体与缩略图（数字桶目录 + thumbs 目录，含 tmp）。
     * 根目录下其他条目（marker 等）不动。返回 (已删除条目数, 删除失败数)。
     */
    suspend fun deleteAllMedia(): Pair<Int, Int> = withContext(ioDispatcher) {
        val counts = intArrayOf(0, 0)
        for (entry in root.listFiles().orEmpty()) {
            if (isOwnedDirectory(entry)) walkDelete(entry, counts)
        }
        counts[0] to counts[1]
    }

    /**
     * 一次性孤儿清扫（v4 去加密断代收尾）。DB 已由破坏性迁移清空，旧媒体文件
     * 不再被任何行引用。marker 文件保证只执行一次；marker 写入失败无妨——下次
     * 启动重新清扫，清扫本身幂等。返回删除的条目数。
     */
    suspend fun purgeOrphansIfNeeded(): Int {
        val marker = File(root, PURGE_MARKER)
        if (marker.exists()) return 0
        val (deleted, _) = deleteAllMedia()
        try {
            withContext(ioDispatcher) { marker.writeBytes(byteArrayOf(1)) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // marker 写入 best-effort
        }
        return deleted
    }

    /** 根下属于本应用媒体布局的目录：数字桶（`"<id/1000>"`）或 [MediaPaths.THUMBS_DIR]。 */
    private fun isOwnedDirectory(file: File): Boolean {
        if (!file.isDirectory) return false
        val name = file.name
        if (name == MediaPaths.THUMBS_DIR) return true
        val bucket = name.toLongOrNull() ?: return false
        return bucket >= 0 && name.all { it in '0'..'9' }
    }

    /** 递归删除；计数只统计文件（与重置存储的历史语义一致），目录删除失败计失败。 */
    private fun walkDelete(file: File, counts: IntArray) {
        if (file.isDirectory) {
            file.listFiles().orEmpty().forEach { walkDelete(it, counts) }
        }
        if (file.isFile) {
            if (file.delete()) counts[0]++ else counts[1]++
        } else if (!file.delete()) {
            counts[1]++
        }
    }
}

/**
 * 写入计数包装：close 时校验累计写入 == [expectedLength]，不等抛 [IOException]。
 * 底层流在任何路径下都会被关闭；校验异常优先于底层 close 异常（后者 addSuppressed）。
 */
private class LengthVerifyingOutputStream(
    private val raw: FileOutputStream,
    private val expectedLength: Long,
) : OutputStream() {
    private var written = 0L

    override fun write(b: Int) {
        raw.write(b)
        written++
    }

    override fun write(b: ByteArray) {
        raw.write(b)
        written += b.size
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        raw.write(b, off, len)
        written += len
    }

    override fun flush() = raw.flush()

    override fun close() {
        var closeFailure: IOException? = null
        try {
            raw.close()
        } catch (e: IOException) {
            closeFailure = e
        }
        if (written != expectedLength) {
            val mismatch = IOException("download length mismatch: wrote $written, expected $expectedLength")
            closeFailure?.let { mismatch.addSuppressed(it) }
            throw mismatch
        }
        closeFailure?.let { throw it }
    }
}

private const val PURGE_MARKER = ".storage_v4_purged"
