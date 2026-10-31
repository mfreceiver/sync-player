package cn.vectory.syncplayer.storage

import androidx.annotation.VisibleForTesting
import cn.vectory.syncplayer.crypto.ChunkedEncryptSink
import cn.vectory.syncplayer.crypto.CryptoScheme
import cn.vectory.syncplayer.crypto.MediaCryptoMaster
import cn.vectory.syncplayer.crypto.MediaKeyUnavailableException
import cn.vectory.syncplayer.di.IoDispatcher
import cn.vectory.syncplayer.storage.volume.FileStat
import cn.vectory.syncplayer.storage.volume.MediaVolume
import cn.vectory.syncplayer.storage.volume.SeekableReadHandle
import cn.vectory.syncplayer.storage.volume.VolumeResolver
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
import java.util.UUID
import javax.crypto.SecretKey
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
    /** v2 双卷解析器；null（纯测试构造）时仅 legacy/内部路径可用。 */
    private val volumeResolver: VolumeResolver? = null,
    /** v2 媒体主密钥；null 时 CTR_V2 路径抛 IllegalStateException（fail-closed）。 */
    private val cryptoMaster: MediaCryptoMaster? = null,
    /** v2 datasetId 源（storage_dataset 单行）；null 时外部写入路径不可用。 */
    private val datasetIdSource: DatasetIdSource? = null,
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

    // ---- v2 双卷（StorageRef/WriteAttempt；冻结契约见 StorageContracts）----

    private fun resolver(): VolumeResolver =
        volumeResolver ?: throw IllegalStateException("volume resolver not configured")

    private fun master(): MediaCryptoMaster =
        cryptoMaster ?: throw IllegalStateException("crypto master not configured")

    private suspend fun datasetId(): String =
        datasetIdSource?.invoke() ?: throw StorageAccessFailure(StorageAccessFailure.Reason.IO_TRANSIENT)

    /** 存储 IO 统一包装：KEY_UNAVAILABLE / VOLUME_UNAVAILABLE / IO_TRANSIENT 结构化上抛。 */
    private inline fun <T> storageIo(block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: StorageAccessFailure) {
        throw e
    } catch (e: MediaKeyUnavailableException) {
        // TODO(B): 映射新 SyncErrorCode.KEY_UNAVAILABLE；当前仅结构化异常，不改 SyncException.kt
        throw StorageAccessFailure(StorageAccessFailure.Reason.KEY_UNAVAILABLE, e)
    } catch (e: IllegalStateException) {
        throw e
    } catch (e: IOException) {
        throw classifyIoFailure(e)
    } catch (e: Exception) {
        throw StorageAccessFailure(StorageAccessFailure.Reason.IO_TRANSIENT, e)
    }

    /**
     * P1-9：IOException 不再一律 IO_TRANSIENT（可重试）。按设计文档错误映射节分类：
     * ENOSPC ⇒ INSUFFICIENT_SPACE、权限/只读 ⇒ ACCESS_DENIED（均终态，不进重试白名单）；
     * 其余保持 IO_TRANSIENT（可恢复 EIO 抖动）。
     */
    private fun classifyIoFailure(e: IOException): Exception {
        val fs = e as? java.nio.file.FileSystemException
            ?: e.cause as? java.nio.file.FileSystemException
        val reason = listOfNotNull(fs?.reason, fs?.message, e.message)
            .firstOrNull { !it.isNullOrBlank() }
            .orEmpty()
        return when {
            fs is java.nio.file.AccessDeniedException ||
                reason.contains("Permission denied", ignoreCase = true) ||
                reason.contains("Read-only file system", ignoreCase = true) ||
                reason.contains("EACCES", ignoreCase = true) ||
                reason.contains("EROFS", ignoreCase = true) ->
                SyncException(SyncErrorCode.ACCESS_DENIED, SyncOperation.DOWNLOAD, cause = e)
            reason.contains("No space left", ignoreCase = true) ||
                reason.contains("ENOSPC", ignoreCase = true) ->
                SyncException(SyncErrorCode.INSUFFICIENT_SPACE, SyncOperation.DOWNLOAD, cause = e)
            else -> StorageAccessFailure(StorageAccessFailure.Reason.IO_TRANSIENT, e)
        }
    }

    private fun requireVolume(volumeId: String): MediaVolume =
        resolver().resolve(volumeId)
            ?: throw StorageAccessFailure(StorageAccessFailure.Reason.VOLUME_UNAVAILABLE)

    private fun mediaRel(ref: StorageRef): String = when (ref.format) {
        StorageFormat.PLAIN -> MediaPaths.mediaRelPath(ref.id)
        StorageFormat.CTR_V2 -> MediaPaths.externalMediaRelPath(
            ref.datasetId!!, ref.id, ref.generation!!
        )
    }

    private fun thumbRel(ref: StorageRef): String = when (ref.format) {
        StorageFormat.PLAIN -> MediaPaths.thumbRelPath(ref.id)
        StorageFormat.CTR_V2 -> MediaPaths.externalThumbRelPath(
            ref.datasetId!!, ref.id, ref.generation!!
        )
    }

    private fun tmpRel(rel: String): String = rel + MediaPaths.TMP_SUFFIX

    /** 写目标选择：existing 行固定自身卷（不随默认切换）；新行用注册表默认快照。 */
    fun selectWriteTarget(existing: StorageRef?): VolumeTarget =
        VolumeTarget(existing?.volumeId ?: resolver().defaultWriteTarget().volumeId)

    /** 建 attempt：external 每 attempt 新 generation+IV（失败全量重下，不续写）。 */
    suspend fun newAttempt(id: Long, target: VolumeTarget, expectedLength: Long): WriteAttempt =
        withContext(ioDispatcher) {
            require(expectedLength >= 0) { "expectedLength must be non-negative" }
            val ref = if (target.volumeId == VolumeIds.INTERNAL) {
                StorageRef.internal(id, expectedLength)
            } else {
                StorageRef(
                    id = id,
                    volumeId = target.volumeId,
                    datasetId = datasetId(),
                    generation = CryptoScheme.newGeneration(),
                    format = StorageFormat.CTR_V2,
                    iv = CryptoScheme.newIv(),
                    plainSize = expectedLength
                )
            }
            WriteAttempt(ref, UUID.randomUUID().toString())
        }

    /**
     * attempt 下载 sink（tmp）。internal 走明文长度校验；external 为 CTR 有界缓冲
     * 加密（ChunkedEncryptSink，close 即长度校验）。物理密文长度核验由 [finalizeRef] 做。
     */
    suspend fun openWriteSink(attempt: WriteAttempt): OutputStream = withContext(ioDispatcher) {
        storageIo {
            val volume = requireVolume(attempt.ref.volumeId)
            val tmp = tmpRel(mediaRel(attempt.ref))
            when (attempt.ref.format) {
                StorageFormat.PLAIN ->
                    LengthVerifyingOutputStream(volume.openWrite(tmp), attempt.ref.plainSize)
                StorageFormat.CTR_V2 -> {
                    // P1-12：先取 contentKey（可能 KEY_UNAVAILABLE 失败），成功后再 openWrite；
                    // 反过来会在密钥失败时泄漏已打开的 tmp 文件流。
                    val contentKey = contentKey(attempt.ref)
                    val raw = volume.openWrite(tmp)
                    try {
                        ChunkedEncryptSink(raw, contentKey, attempt.ref.iv!!, attempt.ref.plainSize)
                    } catch (e: Exception) {
                        // 构造失败（cipher init 等）不留半开 FD。
                        try {
                            raw.close()
                        } catch (_: Exception) {
                        }
                        throw e
                    }
                }
            }
        }
    }

    /**
     * finalize：external 先 O(1) strictStat 校验 tmp 物理密文长度 == plainSize（CTR
     * 无填充，两值相等），再同卷同目录原子 rename；任何不符返回 false（rename 未发生）。
     * 卷缺失抛 VOLUME_UNAVAILABLE（拔卡不误判 NOT_FOUND——调用方按存储不可用处理）。
     */
    suspend fun finalizeRef(attempt: WriteAttempt): Boolean = withContext(ioDispatcher) {
        storageIo {
            val volume = requireVolume(attempt.ref.volumeId)
            val rel = mediaRel(attempt.ref)
            when (attempt.ref.format) {
                StorageFormat.PLAIN -> volume.atomicRename(tmpRel(rel), rel)
                StorageFormat.CTR_V2 -> {
                    when (val stat = volume.strictStat(tmpRel(rel))) {
                        is FileStat.Exists ->
                            if (stat.size == attempt.ref.plainSize) volume.atomicRename(tmpRel(rel), rel) else false
                        FileStat.Missing -> false
                        is FileStat.IoError ->
                            // TODO(B): 映射新 SyncErrorCode.STORAGE_IO_TRANSIENT
                            throw StorageAccessFailure(StorageAccessFailure.Reason.IO_TRANSIENT)
                    }
                }
            }
        }
    }

    /** 丢弃 tmp（幂等）。 */
    suspend fun cancelRef(attempt: WriteAttempt) {
        withContext(ioDispatcher) {
            try {
                requireVolume(attempt.ref.volumeId).delete(tmpRel(mediaRel(attempt.ref)))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 清理尽力而为：tmp 残留无害（下次 attempt 同 id 覆盖或孤儿清扫）。
            }
        }
    }

    /** tmp 三态探测：IoError/卷缺失 ⇒ UNKNOWN（调用方只删行、不删 final）。 */
    suspend fun probePendingTmp(attempt: WriteAttempt): TmpProbeState =
        withContext(ioDispatcher) {
            val volume = try {
                resolver().resolve(attempt.ref.volumeId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: return@withContext TmpProbeState.UNKNOWN
            when (volume.strictStat(tmpRel(mediaRel(attempt.ref)))) {
                is FileStat.Exists -> TmpProbeState.PRESENT
                FileStat.Missing -> TmpProbeState.ABSENT
                is FileStat.IoError -> TmpProbeState.UNKNOWN
            }
        }

    /** 随机读句柄（Provider/解析器用）。卷缺失/IO 失败抛 StorageAccessFailure。 */
    suspend fun openRead(ref: StorageRef): SeekableReadHandle = withContext(ioDispatcher) {
        storageIo { requireVolume(ref.volumeId).openRead(mediaRel(ref)) }
    }

    /** attempt 内容 key（HKDF 域隔离；KEY_UNAVAILABLE 结构化失败，绝不静默换钥）。 */
    fun contentKey(ref: StorageRef): SecretKey {
        val generation = ref.generation
            ?: throw StorageAccessFailure(StorageAccessFailure.Reason.INVALID_REF)
        return CryptoScheme.deriveContentKey(master().masterKey(), generation)
    }

    /** finalize 后可读性探针：strictStat == Exists(plainSize)（CTR 密文长 == 明文长）。 */
    suspend fun verifyReadable(ref: StorageRef) {
        withContext(ioDispatcher) {
            storageIo {
                when (val stat = requireVolume(ref.volumeId).strictStat(mediaRel(ref))) {
                    is FileStat.Exists ->
                        if (stat.size != ref.plainSize) {
                            throw StorageAccessFailure(StorageAccessFailure.Reason.IO_TRANSIENT)
                        }
                    FileStat.Missing ->
                        throw StorageAccessFailure(StorageAccessFailure.Reason.VOLUME_UNAVAILABLE)
                    is FileStat.IoError ->
                        throw StorageAccessFailure(StorageAccessFailure.Reason.IO_TRANSIENT)
                }
            }
        }
    }

    /** final 三态探测（ref 版）。 */
    suspend fun probeFinalized(ref: StorageRef, expectedSize: Long): MediaProbeState =
        withContext(ioDispatcher) {
            val volume = try {
                resolver().resolve(ref.volumeId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: return@withContext MediaProbeState.IO_ERROR
            when (val stat = volume.strictStat(mediaRel(ref))) {
                is FileStat.Exists ->
                    if (stat.size == expectedSize) MediaProbeState.VALID else MediaProbeState.SIZE_MISMATCH
                FileStat.Missing -> MediaProbeState.MISSING
                is FileStat.IoError -> MediaProbeState.IO_ERROR
            }
        }

    /** 删除正文（幂等语义沿用 volume.delete）。 */
    suspend fun deleteRef(ref: StorageRef): Boolean = withContext(ioDispatcher) {
        try {
            requireVolume(ref.volumeId).delete(mediaRel(ref))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /** 写缩略图：external 为 GCM（tmp→原子 rename）；internal 明文直写（tmp→rename）。 */
    suspend fun writeThumb(ref: StorageRef, bytes: ByteArray) {
        withContext(ioDispatcher) {
            storageIo {
                require(bytes.size <= CryptoScheme.THUMB_MAX_PLAIN) { "thumb too large" }
                val volume = requireVolume(ref.volumeId)
                val rel = thumbRel(ref)
                val payload = when (ref.format) {
                    StorageFormat.PLAIN -> bytes
                    StorageFormat.CTR_V2 ->
                        CryptoScheme.encryptThumb(CryptoScheme.deriveThumbKey(master().masterKey()), bytes)
                }
                volume.openWrite(tmpRel(rel)).use { it.write(payload) }
                volume.atomicRename(tmpRel(rel), rel)
            }
        }
    }

    /** 读缩略图明文：external 解密（GCM 失败/缺失返回 null——缩略图可再生）；internal 读文件。 */
    suspend fun readThumbBytes(ref: StorageRef): ByteArray? = withContext(ioDispatcher) {
        val volume = try {
            resolver().resolve(ref.volumeId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: throw StorageAccessFailure(StorageAccessFailure.Reason.VOLUME_UNAVAILABLE)
        when (ref.format) {
            StorageFormat.PLAIN -> {
                val handle = try {
                    volume.openRead(thumbRel(ref))
                } catch (_: Exception) {
                    return@withContext null
                }
                handle.use {
                    val out = ByteArray(it.size().toInt())
                    var read = 0
                    while (read < out.size) {
                        val n = it.read(out, read, out.size - read)
                        if (n < 0) break
                        read += n
                    }
                    if (read == out.size) out else out.copyOf(read)
                }
            }
            StorageFormat.CTR_V2 -> {
                val encoded = try {
                    val handle = volume.openRead(thumbRel(ref))
                    handle.use {
                        val out = ByteArray(it.size().toInt())
                        var read = 0
                        while (read < out.size) {
                            val n = it.read(out, read, out.size - read)
                            if (n < 0) break
                            read += n
                        }
                        if (read == out.size) out else null
                    }
                } catch (_: Exception) {
                    null
                } ?: return@withContext null
                try {
                    CryptoScheme.decryptThumb(CryptoScheme.deriveThumbKey(master().masterKey()), encoded)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    suspend fun deleteThumb(ref: StorageRef): Boolean = withContext(ioDispatcher) {
        try {
            requireVolume(ref.volumeId).delete(thumbRel(ref))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /** 目标卷空间查询（结构化，不吞错误为 0）。 */
    suspend fun space(target: VolumeTarget): SpaceResult = withContext(ioDispatcher) {
        val volume = try {
            resolver().resolve(target.volumeId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return@withContext SpaceResult.Unavailable(SpaceResult.Unavailable.Reason.VOLUME_MISSING)
        val bytes = volume.usableSpace()
        if (bytes >= 0) SpaceResult.Available(bytes)
        else SpaceResult.Unavailable(SpaceResult.Unavailable.Reason.IO_ERROR)
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
     * 不再被任何行引用。
     *
     * AND-061：marker 必须**先写后删**。旧顺序（删除后 best-effort 写 marker）
     * 在 marker 写入失败时会在下次启动重新全删——把清扫间隙新下载的、DB 仍
     * 引用的文件一并清掉。先写 marker 的最坏结果只是孤儿字节残留（占空间、
     * 无数据丢失）；[deleteAllMedia] 只删数字桶/thumbs 目录，不会动 marker。
     * 返回删除的条目数；marker 写失败时本轮不清扫、下次重试（此时尚未删除
     * 任何文件，重扫是安全的）。
     */
    suspend fun purgeOrphansIfNeeded(): Int {
        val marker = File(root, PURGE_MARKER)
        if (marker.exists()) return 0
        try {
            withContext(ioDispatcher) { marker.writeBytes(byteArrayOf(1)) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // marker 写不进去就不做本轮清扫——下次启动再试（此时什么都还没删）。
            return 0
        }
        val (deleted, _) = deleteAllMedia()
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
    private val raw: OutputStream,
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
