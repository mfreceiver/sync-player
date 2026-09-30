package cn.vectory.syncplayer.navigation

import android.content.Context
import android.util.Log
import cn.vectory.syncplayer.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.SecureRandom
import java.util.zip.CRC32
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * SavedStateHandle 里只存放的小型恢复令牌（P3 队列快照，P7 唯一身份协议）。
 *
 * 编码 `QSNAP2|<type>|<snapshotId>|<currentId>|<mode>`：格式版本、媒体类型、
 * 快照唯一身份（32 位小写 hex，SecureRandom 16 字节）、当前媒体 id 与模式 ——
 * 绝不携带完整 id 列表（完整列表落盘在私有快照文件里）。
 */
data class QueueToken(
    val type: QueueMediaType,
    val snapshotId: String,
    val currentId: Long,
    val mode: QueueToken.Mode,
) {
    enum class Mode(val wireName: String) { FULL("full"), SINGLE("single") }

    fun encode(): String =
        "$TOKEN_PREFIX|${type.wireName}|$snapshotId|$currentId|${mode.wireName}"

    companion object {
        /** 严格解析；任何字段缺失/非法返回 null（调用方降级为仅当前媒体恢复）。 */
        fun decode(raw: String?): QueueToken? {
            if (raw == null) return null
            val parts = raw.split("|")
            if (parts.size != 5 || parts[0] != TOKEN_PREFIX) return null
            val type = QueueMediaType.fromWire(parts[1]) ?: return null
            val snapshotId = parts[2]
            if (!SNAPSHOT_ID_REGEX.matches(snapshotId)) return null
            val currentId = parts[3].toLongOrNull() ?: return null
            val mode = when (parts[4]) {
                "full" -> Mode.FULL
                "single" -> Mode.SINGLE
                else -> return null
            }
            return QueueToken(type, snapshotId, currentId, mode)
        }
    }
}

/** 队列所属媒体类型；决定 SavedStateHandle 键与快照文件名。 */
enum class QueueMediaType(
    val wireName: String,
    val snapshotFileName: String,
) {
    VIDEO("video", "nav-queue-video.dat"),
    IMAGE("image", "nav-queue-image.dat");

    companion object {
        fun fromWire(wire: String): QueueMediaType? = entries.firstOrNull { it.wireName == wire }
    }
}

/** SavedStateHandle 键：只存上述小 token 字符串，不再存完整 id CSV。 */
const val QUEUE_VIDEO_TOKEN_KEY = "videoQueueToken"
const val QUEUE_IMAGE_TOKEN_KEY = "imageQueueToken"

private const val TOKEN_PREFIX = "QSNAP2"
private const val PAYLOAD_MAGIC = "SPQ2"
private const val SNAPSHOT_DIR_NAME = "queue_snapshots"

/** 快照身份：SecureRandom 16 字节的 32 位小写 hex。 */
private val SNAPSHOT_ID_REGEX = Regex("[0-9a-f]{32}")

/**
 * 导航恢复队列快照存储（P3；P7 起为唯一身份协议）：完整媒体 id 列表落盘在
 * 私有 no-backup 目录，SavedStateHandle 只保存 [QueueToken] 小字符串，避免
 * 进程恢复边界把大 CSV 塞进 Binder 事务/磁盘状态。
 *
 * 设计要点：
 * - 写入：`tmp` 写入 → flush → fd.sync() → close → 长度校验 → 原子 rename
 *   （两阶段提交红线，与 [cn.vectory.syncplayer.storage.MediaFileStore] 同一
 *   模式；父目录无 fsync，不提供断电级持久承诺，应用层由 CRC32 + 身份绑定
 *   兜底——损坏即拒绝并降级）。
 * - 快照身份（P7，替代旧计数器协议）：每次 prepare 写入生成随机唯一 id
 *   （SecureRandom 16 字节 hex），写入快照文件并编入 token。恢复绑定 =
 *   token.id 与**当前快照文件 id 精确相等**；不等（文件丢失后新写、损坏读
 *   不出 id、历史旧 id）一律拒绝 → 调用方降级 route 单项。无计数器/水位/
 *   单调性概念——随机身份（128 位 CSPRNG）不依赖历史水位，碰撞概率可
 *   忽略；身份丢失即失效。该"旧身份拒绝"指**冷磁盘绑定**：热路径同身份
 *   缓存列表复用为保留语义（见 [readForRestore] 内存快路径）。
 * - 完整性：格式头 + id 字段 + count 一致性 + CRC32 校验 + 长度预算；任何
 *   损坏/半写/身份/类型错配 → 拒绝快照（调用方降级为仅恢复当前单项，绝不整库）。
 * - 内存快路径：同 token（或同身份同当前项）未失效直接复用已解析列表，
 *   热路径零磁盘 IO。
 * - 预算：默认单文件 16 MiB / 100k 条 id；超限拒绝快照、退化为单项 token。
 * - 并发：所有写入经 [writeMutex] 串行化（签 id → 写文件在锁内串行）；写入
 *   内部无挂起点，取消不可能落在 tmp 与 rename 之间。身份随机性使并发写入
 *   互不绑定，无需跨写入排序。
 *
 * 可测性：内部构造函数注入目录与 dispatcher（JVM 单测用临时目录）。
 */
@Singleton
class QueueSnapshotStore internal constructor(
    private val snapshotDir: File,
    private val ioDispatcher: CoroutineDispatcher,
    private val maxFileBytes: Int,
    private val maxIds: Int,
) {

    @Inject
    constructor(
        @ApplicationContext context: Context,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ) : this(
        snapshotDir = File(context.noBackupFilesDir, SNAPSHOT_DIR_NAME),
        ioDispatcher = ioDispatcher,
        maxFileBytes = DEFAULT_MAX_FILE_BYTES,
        maxIds = DEFAULT_MAX_IDS,
    )

    private val writeMutex = Mutex()
    private val secureRandom = SecureRandom()

    /**
     * 生成快照唯一身份（SecureRandom 16 字节 → 32 位小写 hex）。internal：
     * 协调层最后防线降级 token 与测试构造合法 token 复用同一身份格式。
     */
    internal fun newSnapshotId(): String {
        val bytes = ByteArray(SNAPSHOT_ID_BYTES)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** 单类型单槽缓存：最新一次成功写入（或成功恢复）的 token→列表绑定。 */
    private class CacheEntry(
        val token: String?,
        val snapshotId: String,
        val ids: List<Long>,
        val writtenToDisk: Boolean,
    )

    @Volatile private var videoCache: CacheEntry? = null
    @Volatile private var imageCache: CacheEntry? = null

    private fun cacheFor(type: QueueMediaType): CacheEntry? = when (type) {
        QueueMediaType.VIDEO -> videoCache
        QueueMediaType.IMAGE -> imageCache
    }

    private fun setCache(type: QueueMediaType, entry: CacheEntry?) {
        when (type) {
            QueueMediaType.VIDEO -> videoCache = entry
            QueueMediaType.IMAGE -> imageCache = entry
        }
    }

    /**
     * 导航前调用：为即将进行的播放器/看图导航准备恢复令牌。
     *
     * 成功 → 返回 FULL token 且快照已在磁盘上（或复用既有同列表快照：仅
     * 签发携带同一快照身份、新当前项的 token，不重写文件）。
     * 任何失败（IO 异常 / 超预算）→ 返回 SINGLE 降级 token（仅含当前媒体，
     * 携带独立随机身份，不绑定任何文件），绝不抛异常、绝不阻断导航。
     *
     * @param ids 导航队列（有序）；调用方保证非空，这里再防御一次。
     */
    suspend fun prepare(type: QueueMediaType, ids: List<Long>, currentId: Long): String {
        val safeIds = if (ids.isEmpty()) listOf(currentId) else ids
        if (currentId !in safeIds) {
            // token 与列表必须自洽（恢复时会做 currentId ∈ ids 校验），
            // 不自洽的输入直接降级为单项恢复。
            return degradedToken(type, currentId)
        }
        val cached = cacheFor(type)
        if (cached != null && cached.writtenToDisk && cached.ids == safeIds) {
            // 列表未变（如同一文件夹换播另一条）：复用磁盘快照（同一身份），
            // 只签发携带新当前项的 token —— 满足"切换媒体更新当前 ID，不重写
            // 整个列表"。
            val token = QueueToken(type, cached.snapshotId, currentId, QueueToken.Mode.FULL).encode()
            setCache(type, CacheEntry(token, cached.snapshotId, safeIds, writtenToDisk = true))
            return token
        }
        return try {
            withContext(ioDispatcher) {
                writeMutex.withLock {
                    if (safeIds.size > maxIds) {
                        return@withLock degradedToken(type, currentId)
                    }
                    // 唯一身份协议：签发随机唯一 id → 写入文件 → 编入 token。
                    // 无计数器/水位/单调性；每次写入身份全新（128 位 CSPRNG，
                    // 碰撞概率可忽略），旧 token 与新文件绑定按精确相等校验、
                    // 不等即拒绝（冷磁盘绑定语义，热缓存同身份复用不受影响）。
                    val snapshotId = newSnapshotId()
                    val payload = encodePayload(snapshotId, safeIds)
                    if (payload.size > maxFileBytes) {
                        return@withLock degradedToken(type, currentId)
                    }
                    writeAtomic(type, payload)
                    val token = QueueToken(type, snapshotId, currentId, QueueToken.Mode.FULL).encode()
                    setCache(type, CacheEntry(token, snapshotId, safeIds, writtenToDisk = true))
                    token
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "queue snapshot write failed; degrade to single-item token", e)
            // 快照失败不阻断导航：降级 token 内含完整当前媒体信息，进程恢复时
            // 仍能恢复出当前单项（绝不会退回整库兜底）。
            degradedToken(type, currentId)
        }
    }

    /**
     * 恢复期调用：把 token 解析回有序 id 列表。
     *
     * 🟠(P4) [expectedType] 显式绑定媒体类型：token 类型不符即拒绝（返回 null）。
     * 返回 null = token 非法/错型/快照缺失/损坏/**身份不等**/与 token 不自洽，
     * 调用方必须降级为仅恢复当前单项。绝不抛可恢复异常（统一边界：CE 先传播，
     * 普通 Exception → null），不 catch Throwable 伪装成功。
     */
    suspend fun readForRestore(rawToken: String?, expectedType: QueueMediaType): List<Long>? {
        val token = QueueToken.decode(rawToken) ?: return null
        if (token.type != expectedType) return null
        if (token.mode == QueueToken.Mode.SINGLE) return listOf(token.currentId)

        // 内存快路径：同 token（或同身份同当前项）未失效 → 复用已解析列表（零 IO）。
        cacheFor(token.type)?.let { cached ->
            if (cached.writtenToDisk &&
                (cached.token == rawToken || cached.snapshotId == token.snapshotId) &&
                token.currentId in cached.ids
            ) {
                return cached.ids
            }
        }
        // 磁盘恢复统一边界：isFile/length/readBytes/parse/调度切换全部覆盖。
        return try {
            withContext(ioDispatcher) { readFromDisk(token, rawToken) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "queue snapshot restore read failed; degrade to single item", e)
            null
        }
    }

    /**
     * 进程恢复冷路径的同步包装。主线程阻塞风险如实声明：数据量有界
     * （≤[maxFileBytes]，实际 ≤ 数百 KB），但**阻塞时长无硬保证**；按消费方
     * entry 门控一次（见 [QueueNavigationCoordinator]，重组/普通导航不重复
     * 触发）。性能残余由主审登记，不在代码层承诺。热路径（同 token 未失效）
     * 在本方法内同步返回，零磁盘 IO。真实 IO 始终在 [ioDispatcher] 上执行。
     * 除进程恢复外不得调用。
     */
    fun readForRestoreBlocking(rawToken: String?, expectedType: QueueMediaType): List<Long>? =
        runBlocking { readForRestore(rawToken, expectedType) }

    private fun degradedToken(type: QueueMediaType, currentId: Long): String {
        // 快照被拒绝/失败：token 只保当前单项（进程恢复时还原为 [currentId]）。
        // 携带独立随机身份：SINGLE 恢复不绑定文件，身份字段仅为格式统一。
        setCache(type, null)
        return QueueToken(type, newSnapshotId(), currentId, QueueToken.Mode.SINGLE).encode()
    }

    // ---- 磁盘格式 -------------------------------------------------------
    //
    // SPQ2\n
    // id=<32 hex>\n
    // count=<decimal>\n
    // <id>\n (count lines)
    // CRC32=<8 hex uppercase>\n     ← CRC 覆盖其之前全部字节
    //
    // 半写/损坏检测：魔数、id 字段格式、行数与 count 一致性、id 可解析、
    // CRC32、长度预算，任一不满足即拒绝（身份未知 → 恢复降级单项）。

    private fun encodePayload(snapshotId: String, ids: List<Long>): ByteArray {
        val body = buildString {
            append(PAYLOAD_MAGIC).append('\n')
            append("id=").append(snapshotId).append('\n')
            append("count=").append(ids.size).append('\n')
            for (id in ids) {
                append(id).append('\n')
            }
        }.toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply { update(body) }
        val trailer = "CRC32=%08X\n".format(crc.value).toByteArray(Charsets.US_ASCII)
        return body + trailer
    }

    private fun parsePayload(bytes: ByteArray): Pair<String, List<Long>>? {
        if (bytes.isEmpty()) return null
        val text = String(bytes, Charsets.US_ASCII)
        val lines = text.split('\n').dropLast(1) // 末尾固定 '\n'，drop 掉空尾段
        if (lines.size < 4) return null
        if (lines[0] != PAYLOAD_MAGIC) return null
        val snapshotId = lines[1].removePrefix("id=")
        if (!SNAPSHOT_ID_REGEX.matches(snapshotId)) return null
        val count = lines[2].removePrefix("count=").toIntOrNull() ?: return null
        if (count <= 0 || count > maxIds) return null
        if (lines.size != count + 4) return null
        val ids = ArrayList<Long>(count)
        for (i in 0 until count) {
            val id = lines[3 + i].toLongOrNull() ?: return null
            ids.add(id)
        }
        val crcLine = lines[count + 3]
        val storedCrc = crcLine.removePrefix("CRC32=").toLongOrNull(16) ?: return null
        val bodyLen = bytes.size - crcLine.length - 1 // CRC 行 + 其前导换行之外的部分
        if (bodyLen <= 0 || bodyLen > bytes.size) return null
        val crc = CRC32().apply { update(bytes, 0, bodyLen) }
        if (crc.value != storedCrc) return null
        return snapshotId to ids
    }

    private fun snapshotFile(type: QueueMediaType): File = File(snapshotDir, type.snapshotFileName)

    /**
     * 两阶段提交：tmp 写入 → flush → fd.sync() → close → 长度校验 → 同目录
     * 原子 rename。锁内无挂起点。父目录不做 fsync，不提供断电级持久承诺——
     * 应用层由 CRC32 + 身份绑定兜底（损坏即拒绝降级，见类文档）。
     */
    private fun writeAtomic(type: QueueMediaType, bytes: ByteArray) {
        snapshotDir.mkdirs()
        val tmp = File(snapshotDir, type.snapshotFileName + ".tmp")
        val final = snapshotFile(type)
        // 上次进程在 rename 前死掉遗留的 tmp：写入前清理（fail-safe，尽力而为）。
        if (tmp.exists() && !tmp.delete()) {
            throw IOException("stale tmp snapshot could not be removed")
        }
        FileOutputStream(tmp).use { fos ->
            fos.write(bytes)
            fos.flush()
            fos.fd.sync()
        }
        if (tmp.length() != bytes.size.toLong()) {
            tmp.delete()
            throw IOException("snapshot tmp length mismatch after close")
        }
        // 同目录 rename 在 Linux 上是原子替换（与 MediaFileStore finalize 同语义）。
        if (!tmp.renameTo(final)) {
            tmp.delete()
            throw IOException("snapshot tmp rename failed")
        }
    }

    private fun readFromDisk(token: QueueToken, rawToken: String?): List<Long>? {
        // isFile/length/readBytes 的可恢复 IO 异常由 [readForRestore] 的统一
        // 边界兜底为 null，此处不再局部吞异常。
        val file = snapshotFile(token.type)
        if (!file.isFile) return null
        if (file.length() > maxFileBytes) return null
        val bytes = file.readBytes()
        val parsed = parsePayload(bytes) ?: return null
        val (snapshotId, ids) = parsed
        // 🔴(P7) 恢复绑定 = token.id 与当前文件 id 精确相等；不等（文件丢失后
        // 新写、损坏读不出 id、历史旧 id）一律拒绝 → 调用方降级 route 单项。
        //（冷磁盘绑定语义；热路径同身份缓存复用为保留语义，见快路径。）
        if (snapshotId != token.snapshotId) return null
        // token 绑定校验：token 声明的当前媒体必须在快照列表内，否则视为
        // token 与快照不自洽 → 拒绝（调用方降级为仅当前单项）。
        if (token.currentId !in ids) return null
        setCache(token.type, CacheEntry(rawToken, snapshotId, ids, writtenToDisk = true))
        return ids
    }

    companion object {
        private const val TAG = "QueueSnapshotStore"
        private const val SNAPSHOT_ID_BYTES = 16

        /** 大列表预算：10k ids ≈ 77 KB；16 MiB 为防御上限。 */
        const val DEFAULT_MAX_FILE_BYTES: Int = 16 * 1024 * 1024
        const val DEFAULT_MAX_IDS: Int = 100_000
    }
}
