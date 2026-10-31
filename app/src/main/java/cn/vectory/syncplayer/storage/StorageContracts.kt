package cn.vectory.syncplayer.storage

/**
 * 冻结契约（设计文档 2026-10-31「接口先行」节）。实施者 A/B 共享；接口变更须经双方签字。
 */

/** 存储格式：PLAIN = 内部明文；CTR_V2 = 外部 AES-CTR（generation 域隔离 key，8B IV）。 */
enum class StorageFormat { PLAIN, CTR_V2 }

/** 行级存储状态：PENDING = 初始化/下载中（Provider 不公开）；READY = 可读。 */
enum class StorageState { PENDING, READY }

/** 卷 id：`internal` 或 `external:<uuid>`。数组下标/绝对 /storage 路径禁止持久化。 */
object VolumeIds {
    const val INTERNAL = "internal"
    const val EXTERNAL_PREFIX = "external:"
    fun external(uuid: String): String = EXTERNAL_PREFIX + uuid
    fun isExternal(volumeId: String): Boolean = volumeId.startsWith(EXTERNAL_PREFIX)
    fun externalUuid(volumeId: String): String? =
        volumeId.removePrefix(EXTERNAL_PREFIX).takeIf { volumeId.startsWith(EXTERNAL_PREFIX) && it.isNotEmpty() }
}

/** 写入目标快照（一次同步开始时快照的默认卷；existing 行优先走自身卷）。 */
data class VolumeTarget(val volumeId: String)

/**
 * 不可变存储引用（单次 entity 快照语义）。iv 必须复制持有，禁止可变数组别名。
 * 不合法组合 fail-closed：PLAIN ⇒ iv/generation/datasetId 为 null；CTR_V2 ⇒ iv 8B、
 * generation 与 datasetId 非空。禁止「iv 缺失就按明文」。
 */
class StorageRef(
    val id: Long,
    val volumeId: String,
    val datasetId: String?,
    val generation: String?,
    val format: StorageFormat,
    iv: ByteArray?,
    val plainSize: Long,
) {
    val iv: ByteArray? = iv?.copyOf()

    init {
        when (format) {
            StorageFormat.PLAIN -> {
                check(iv == null && generation == null && datasetId == null) {
                    "PLAIN ref must not carry iv/generation/datasetId"
                }
            }
            StorageFormat.CTR_V2 -> {
                check(iv != null && iv.size == 8) { "CTR_V2 ref requires 8-byte iv" }
                check(!generation.isNullOrBlank()) { "CTR_V2 ref requires generation" }
                check(!datasetId.isNullOrBlank()) { "CTR_V2 ref requires datasetId" }
            }
        }
        check(plainSize >= 0) { "plainSize must be non-negative" }
    }

    override fun equals(other: Any?): Boolean = other is StorageRef &&
        other.id == id && other.volumeId == volumeId && other.generation == generation &&
        other.format == format && other.plainSize == plainSize &&
        other.iv.contentEquals(iv)

    override fun hashCode(): Int {
        var h = id.hashCode() * 31 + volumeId.hashCode()
        h = h * 31 + (generation?.hashCode() ?: 0)
        h = h * 31 + format.hashCode()
        h = h * 31 + plainSize.hashCode()
        h = h * 31 + (iv?.contentHashCode() ?: 0)
        return h
    }

    companion object {
        /** internal 行的规范引用（PLAIN、无 iv/generation）。 */
        fun internal(id: Long, plainSize: Long): StorageRef =
            StorageRef(id, VolumeIds.INTERNAL, null, null, StorageFormat.PLAIN, null, plainSize)
    }
}

/**
 * 一次下载 attempt：新的 external generation/IV 每 attempt 刷新；失败全量重下，不续写。
 * [attemptId] 由 store 创建（随机）。
 */
class WriteAttempt(
    val ref: StorageRef,
    val attemptId: String,
) {
    init {
        check(ref.id > 0) { "attempt requires a committed row id" }
    }
}

/**
 * 按目标卷空间查询结果。Unavailable 携带结构化原因（区别于空间不足）：
 * TODO(B): STORAGE_UNAVAILABLE / STORAGE_IO_TRANSIENT 映射到新 SyncErrorCode。
 */
sealed class SpaceResult {
    data class Available(val bytes: Long) : SpaceResult()
    data class Unavailable(val reason: Reason) : SpaceResult() {
        enum class Reason { VOLUME_MISSING, IO_ERROR }
    }
}

/** 存储/密钥层失败的结构化异常（不携带底层 message/path；TODO(B): 映射新 SyncErrorCode）。 */
class StorageAccessFailure(val reason: Reason, cause: Throwable? = null) : Exception(null, cause) {
    enum class Reason { VOLUME_UNAVAILABLE, IO_TRANSIENT, KEY_UNAVAILABLE, INVALID_REF }
}

/** 挂起取 datasetId（Room 单行）。fun interface 以规避 Dagger 对 suspend lambda 的通配符匹配问题。 */
fun interface DatasetIdSource {
    suspend operator fun invoke(): String
}
