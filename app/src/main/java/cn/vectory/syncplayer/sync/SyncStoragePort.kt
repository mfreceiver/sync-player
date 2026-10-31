package cn.vectory.syncplayer.sync

import cn.vectory.syncplayer.data.local.dao.StorageDatasetDao
import cn.vectory.syncplayer.data.repository.UserPreferencesRepository
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.storage.DatasetIdSource
import cn.vectory.syncplayer.storage.MediaExistence
import cn.vectory.syncplayer.storage.MediaFileStore
import cn.vectory.syncplayer.storage.MediaPaths
import cn.vectory.syncplayer.storage.MediaProbeState
import cn.vectory.syncplayer.storage.StorageAccessFailure
import cn.vectory.syncplayer.storage.StorageFormat
import cn.vectory.syncplayer.storage.StorageRef
import cn.vectory.syncplayer.storage.TmpProbeState
import cn.vectory.syncplayer.storage.VolumeIds
import cn.vectory.syncplayer.storage.VolumeTarget
import cn.vectory.syncplayer.storage.WriteAttempt
import cn.vectory.syncplayer.storage.volume.FileStat
import cn.vectory.syncplayer.storage.volume.MediaVolume
import cn.vectory.syncplayer.storage.volume.MediaVolumeRegistry
import dagger.Lazy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 包 B 的存储端口层（外部 SD 加密媒体存储 v2，任务包 B）。
 *
 * sync 层只依赖 [SyncStoragePort] 这一个抽象，不直接触碰 A 的 [MediaFileStore]/
 * [MediaVolumeRegistry]；两个实现：
 *  - [DualVolumeSyncStoragePort]（生产）：包装 A 的冻结契约实现（StorageRef/WriteAttempt/
 *    VolumeResolver/StorageAccessFailure），负责 (a) StorageAccessFailure → [SyncException]
 *    新错误码的 sink 边界转换，(b) 卷登记/默认卷的持久化恢复（UserPreferences）。
 *  - [LegacySyncStoragePort]（测试/单卷回退）：1:1 委托现有单卷 [MediaFileStore]，
 *    对以 mock store 为基座的既有测试完全透传。
 *
 * 语义约束（设计文档 §同步，冻结）：
 *  - 每轮同步在 storageOperationMutex 内对默认卷做一次快照（[snapshotDefaultVolume]）；
 *  - existing 行按其自身 volumeId 选目标（[resolveRef] → 非 null 时沿用其卷），
 *    切换默认卷只影响新文件；
 *  - 写路径三阶段：newAttempt → openWriteSink → finalize/verifyReadable；
 *  - DB 提交走 CAS（[updateSyncStorageMetadata] 的 expectedOldGeneration），0 行即失败；
 *  - 卷不可用必须表现为结构化错误（[SyncErrorCode.STORAGE_UNAVAILABLE] 或 probe
 *    IO_ERROR → E8），绝不因“缺卡”推导出删除。
 */
const val VOLUME_ID_INTERNAL = VolumeIds.INTERNAL

/** 媒体文件在卷上的持久化格式（对应契约 StorageFormat；legacy 单卷为 PLAIN）。 */
enum class SyncStorageFormat { PLAIN, CTR_V2 }

/** 写入目标卷。 */
data class SyncVolumeTarget(val volumeId: String) {
    companion object {
        val INTERNAL = SyncVolumeTarget(VOLUME_ID_INTERNAL)
    }
}

/**
 * 一个媒体行的存储引用快照（对应契约 StorageRef；包内镜像，字段同构）。
 */
data class SyncStorageRef(
    val id: Long,
    val volumeId: String,
    val datasetId: String? = null,
    val generation: String? = null,
    val format: SyncStorageFormat = SyncStorageFormat.PLAIN,
    val iv: ByteArray? = null,
    val plainSize: Long = 0L,
)

/**
 * 一次写尝试（对应契约 WriteAttempt）。[impl] 携带 A 的真实 attempt（legacy 实现为
 * 纯镜像值，impl=null）；外部卷每次 attempt 刷新 generation/IV，失败全量重下不续写。
 */
class SyncWriteAttempt(
    val ref: SyncStorageRef,
    val attemptId: String,
    internal val impl: Any? = null,
) {
    val id: Long get() = ref.id
    val target: SyncVolumeTarget get() = SyncVolumeTarget(ref.volumeId)
}

/** 按卷读取可用空间的结果：读失败/卷离线结构化为错误码，不回退 Throwable.message。 */
sealed interface SyncSpaceOutcome {
    data class Available(val usableBytes: Long) : SyncSpaceOutcome
    data class Unavailable(val code: SyncErrorCode) : SyncSpaceOutcome
}

/** 设置页卷信息（用户可见层只拿标签资源，不暴露 UUID/路径）。 */
data class SyncVolumeInfo(
    val volumeId: String,
    val isExternal: Boolean,
    val isAvailable: Boolean,
    val usableBytes: Long? = null,
    val ownsRows: Boolean = false,
    val isDefault: Boolean = false,
)

/**
 * sync 包对存储层的唯一依赖面。所有方法 suspend；底层阻塞 IO 由实现方负责调度。
 */
interface SyncStoragePort {

    /** 当前默认写入卷快照（调用方须在 storageOperationMutex 内取一次，整轮复用）。 */
    suspend fun snapshotDefaultVolume(): SyncVolumeTarget

    /** 读取某媒体行的存储引用；无引用（新文件）或引用不合法返回 null。 */
    suspend fun resolveRef(id: Long): SyncStorageRef?

    suspend fun defaultWriteVolumeId(): String

    /** 校验并持久化默认写入卷；返回 false 表示卷不存在/不可激活。 */
    suspend fun setDefaultWriteVolume(volumeId: String): Boolean

    fun isExternalVolume(volumeId: String): Boolean

    /** 卷此刻是否可达（legacy：内部卷恒 true）。 */
    fun isVolumeAvailable(volumeId: String): Boolean

    suspend fun listVolumes(): List<SyncVolumeInfo>

    /** 设置页显式登记外部卷（能力检查通过才生效）；返回 volumeId 或 null。 */
    suspend fun registerExternalVolume(): String?

    /** 设置页显式注销外部卷（不影响已打开 FD）；默认卷被注销时回落内部卷。 */
    suspend fun unregisterExternalVolume(volumeId: String): Boolean

    // ---- 写路径（三阶段提交） ----

    suspend fun newAttempt(id: Long, target: SyncVolumeTarget, expectedLength: Long): SyncWriteAttempt

    /** 打开写 sink；实现必须在边界把底层 IO/加密异常转换为 [SyncException]。 */
    suspend fun openWriteSink(attempt: SyncWriteAttempt): OutputStream

    suspend fun finalize(attempt: SyncWriteAttempt): Boolean

    suspend fun verifyReadable(attempt: SyncWriteAttempt)

    /** 取消未 finalize 的 tmp（attempt 未知时为安全 no-op）。 */
    suspend fun cancel(attempt: SyncWriteAttempt)

    suspend fun probePendingTmp(attempt: SyncWriteAttempt): TmpProbeState

    /** 删除已 finalize 的媒体文件（回滚用，幂等）。 */
    suspend fun deleteFinal(attempt: SyncWriteAttempt): Boolean

    /**
     * 扫描期本地 final 探测。卷不可用必须返回 [MediaProbeState.IO_ERROR]（→ E8
     * 清空 toDownload/toDelete，绝不按缺卡推导删除）。
     */
    suspend fun probeFinalized(id: Long, expectedSize: Long): MediaProbeState

    // ---- 空间 ----

    suspend fun usableSpace(target: SyncVolumeTarget): SyncSpaceOutcome

    // ---- 删除 / 存在性（按行路由到其所在卷） ----

    suspend fun checkMediaExistence(id: Long): MediaExistence
    suspend fun checkThumbExistence(id: Long): MediaExistence
    suspend fun deleteMedia(id: Long): Boolean
    suspend fun deleteThumb(id: Long): Boolean

    /** 有 DB 行占用的卷集合（重置前可用性检查用）。 */
    suspend fun ownedVolumeIds(): Set<String>

    /** 删除某卷上本应用 dataset 的全部媒体（重置用，替代 deleteAllMedia）。 */
    suspend fun deleteAllOwned(volumeId: String, datasetId: String? = null): Pair<Int, Int>

    /**
     * CAS 提交同步元数据（storageState → READY）。expectedOldGeneration 非 null 时按
     * 世代比较，0 行受影响即失败（不复活并发删除行）。attempt.ref 携带卷/格式/IV/世代。
     */
    suspend fun updateSyncStorageMetadata(
        attempt: SyncWriteAttempt,
        expectedOldGeneration: String?,
        fileSize: Long,
        remoteModifiedTime: Long?,
        dateSynced: Long,
    ): Int
}

/** 契约 [StorageAccessFailure.Reason] → 新 [SyncErrorCode]（设计 §错误映射）。 */
internal fun StorageAccessFailure.toSyncErrorCode(): SyncErrorCode = when (reason) {
    StorageAccessFailure.Reason.VOLUME_UNAVAILABLE -> SyncErrorCode.STORAGE_UNAVAILABLE
    StorageAccessFailure.Reason.IO_TRANSIENT -> SyncErrorCode.STORAGE_IO_TRANSIENT
    StorageAccessFailure.Reason.KEY_UNAVAILABLE -> SyncErrorCode.KEY_UNAVAILABLE
    StorageAccessFailure.Reason.INVALID_REF -> SyncErrorCode.STORAGE_ERROR
}

/**
 * 生产实现：包装 A 的双卷 [MediaFileStore] + [MediaVolumeRegistry] + Room v5 列。
 *
 * - **持久化归属 B**：登记的外部卷 uuid 集合与默认写入卷存 UserPreferencesRepository；
 *   [ensureVolumesConfigured] 在同步开始/设置页刷新时恢复（枚举→重登记→恢复默认卷）。
 * - **边界转换**：[StorageAccessFailure] / IO 异常在端口边界转 [SyncException]，
 *   不吞 SyncException（CancellationException 先传播）。
 */
@Singleton
internal class DualVolumeSyncStoragePort @Inject constructor(
    private val storeLazy: Lazy<MediaFileStore>,
    private val registry: MediaVolumeRegistry,
    private val videoRepository: VideoRepository,
    private val datasetDao: StorageDatasetDao,
    private val userPreferencesRepository: UserPreferencesRepository,
    @cn.vectory.syncplayer.di.IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : SyncStoragePort {

    private val store: MediaFileStore get() = storeLazy.get()
    private val configureMutex = Mutex()

    /** 枚举 + 重登记 + 恢复默认卷（幂等；同步开始与设置页刷新时调用）。 */
    private suspend fun ensureVolumesConfigured() {
        configureMutex.withLock {
            withContext(ioDispatcher) {
                val datasetId = datasetDao.get()?.datasetId
                val persisted = userPreferencesRepository.getRegisteredExternalVolumes()
                val candidates = try {
                    registry.enumerateExternalCandidates()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    emptyList()
                }
                val registered = candidates
                    .filter { candidate -> candidate.uuid in persisted }
                    .mapNotNull { candidate -> registry.registerExternal(candidate, datasetId) }
                // 持久化集合收敛为“当前仍可登记”的子集（拔走的卷下次插回再恢复）。
                if (registered.toSet() != persisted) {
                    userPreferencesRepository.setRegisteredExternalVolumes(registered.toSet())
                }
                // 恢复默认卷：不可用（未插卡）时回落内部卷并持久化回落。
                val prefDefault = userPreferencesRepository.getDefaultWriteVolume()
                val ok = registry.setDefaultWriteVolume(prefDefault)
                if (!ok && prefDefault != VolumeIds.INTERNAL) {
                    registry.setDefaultWriteVolume(VolumeIds.INTERNAL)
                    userPreferencesRepository.setDefaultWriteVolume(VolumeIds.INTERNAL)
                }
            }
        }
    }

    override suspend fun snapshotDefaultVolume(): SyncVolumeTarget {
        ensureVolumesConfigured()
        return SyncVolumeTarget(registry.defaultWriteTarget().volumeId)
    }

    override suspend fun resolveRef(id: Long): SyncStorageRef? {
        val entity = videoRepository.getById(id) ?: return null
        val format = when (entity.storageFormat) {
            StorageFormat.CTR_V2.name -> SyncStorageFormat.CTR_V2
            else -> SyncStorageFormat.PLAIN
        }
        val datasetId = if (format == SyncStorageFormat.CTR_V2) {
            datasetDao.get()?.datasetId ?: return null
        } else null
        // 非法组合（CTR_V2 缺 iv/generation 等）→ null：目标卷回退默认卷整行重下。
        if (format == SyncStorageFormat.CTR_V2 && (entity.iv == null || entity.generation == null)) {
            return null
        }
        return SyncStorageRef(
            id = entity.id,
            volumeId = entity.volumeId,
            datasetId = datasetId,
            generation = entity.generation,
            format = format,
            iv = entity.iv?.copyOf(),
            plainSize = entity.fileSize,
        )
    }

    override suspend fun defaultWriteVolumeId(): String =
        registry.defaultWriteTarget().volumeId

    override suspend fun setDefaultWriteVolume(volumeId: String): Boolean {
        ensureVolumesConfigured()
        val ok = registry.setDefaultWriteVolume(volumeId)
        if (ok) userPreferencesRepository.setDefaultWriteVolume(volumeId)
        return ok
    }

    override fun isExternalVolume(volumeId: String): Boolean = VolumeIds.isExternal(volumeId)

    override fun isVolumeAvailable(volumeId: String): Boolean = registry.isAvailable(volumeId)

    override suspend fun listVolumes(): List<SyncVolumeInfo> {
        ensureVolumesConfigured()
        val owned = ownedVolumeIds()
        val defaultVolumeId = registry.defaultWriteTarget().volumeId
        return registry.registeredVolumeIds().map { volumeId ->
            val space = if (registry.isAvailable(volumeId)) {
                when (val r = store.space(VolumeTarget(volumeId))) {
                    is cn.vectory.syncplayer.storage.SpaceResult.Available -> r.bytes.takeIf { it >= 0 }
                    is cn.vectory.syncplayer.storage.SpaceResult.Unavailable -> null
                }
            } else null
            SyncVolumeInfo(
                volumeId = volumeId,
                isExternal = VolumeIds.isExternal(volumeId),
                isAvailable = registry.isAvailable(volumeId),
                usableBytes = space,
                ownsRows = volumeId in owned,
                isDefault = volumeId == defaultVolumeId,
            )
        }
    }

    override suspend fun registerExternalVolume(): String? {
        configureMutex.withLock {
            return withContext(ioDispatcher) {
                val datasetId = datasetDao.get()?.datasetId
                val candidate = registry.enumerateExternalCandidates().firstOrNull()
                    ?: return@withContext null
                val volumeId = registry.registerExternal(candidate, datasetId) ?: return@withContext null
                val uuid = VolumeIds.externalUuid(volumeId) ?: return@withContext null
                val persisted = userPreferencesRepository.getRegisteredExternalVolumes()
                userPreferencesRepository.setRegisteredExternalVolumes(persisted + uuid)
                volumeId
            }
        }
    }

    override suspend fun unregisterExternalVolume(volumeId: String): Boolean {
        configureMutex.withLock {
            return withContext(ioDispatcher) {
                val uuid = VolumeIds.externalUuid(volumeId) ?: return@withContext false
                registry.unregisterExternal(volumeId)
                val persisted = userPreferencesRepository.getRegisteredExternalVolumes()
                userPreferencesRepository.setRegisteredExternalVolumes(persisted - uuid)
                if (userPreferencesRepository.getDefaultWriteVolume() == volumeId) {
                    registry.setDefaultWriteVolume(VolumeIds.INTERNAL)
                    userPreferencesRepository.setDefaultWriteVolume(VolumeIds.INTERNAL)
                }
                true
            }
        }
    }

    // ---- 写路径 ----

    override suspend fun newAttempt(
        id: Long,
        target: SyncVolumeTarget,
        expectedLength: Long,
    ): SyncWriteAttempt {
        val attempt = storeGuarded(SyncOperation.DOWNLOAD) {
            store.newAttempt(id, VolumeTarget(target.volumeId), expectedLength)
        }
        return SyncWriteAttempt(
            ref = SyncStorageRef(
                id = attempt.ref.id,
                volumeId = attempt.ref.volumeId,
                datasetId = attempt.ref.datasetId,
                generation = attempt.ref.generation,
                format = when (attempt.ref.format) {
                    StorageFormat.PLAIN -> SyncStorageFormat.PLAIN
                    StorageFormat.CTR_V2 -> SyncStorageFormat.CTR_V2
                },
                iv = attempt.ref.iv?.copyOf(),
                plainSize = attempt.ref.plainSize,
            ),
            attemptId = attempt.attemptId,
            impl = attempt,
        )
    }

    override suspend fun openWriteSink(attempt: SyncWriteAttempt): OutputStream {
        val impl = attempt.impl as? WriteAttempt
            ?: throw SyncException(SyncErrorCode.STORAGE_ERROR, SyncOperation.DOWNLOAD)
        return storeGuarded(SyncOperation.DOWNLOAD) { store.openWriteSink(impl) }
    }

    override suspend fun finalize(attempt: SyncWriteAttempt): Boolean {
        val impl = attempt.impl as? WriteAttempt
            ?: throw SyncException(SyncErrorCode.FINALIZE_FAILED, SyncOperation.DOWNLOAD)
        return storeGuarded(SyncOperation.DOWNLOAD) { store.finalizeRef(impl) }
    }

    override suspend fun verifyReadable(attempt: SyncWriteAttempt) {
        val impl = attempt.impl as? WriteAttempt
            ?: throw SyncException(SyncErrorCode.STORAGE_ERROR, SyncOperation.DOWNLOAD)
        storeGuarded(SyncOperation.DOWNLOAD) { store.verifyReadable(impl.ref) }
    }

    override suspend fun cancel(attempt: SyncWriteAttempt) {
        val impl = attempt.impl as? WriteAttempt ?: return // 无底层 attempt ⇒ tmp 不可能存在
        storeGuarded(SyncOperation.DOWNLOAD) { store.cancelRef(impl) }
    }

    override suspend fun probePendingTmp(attempt: SyncWriteAttempt): TmpProbeState {
        val impl = attempt.impl as? WriteAttempt
            ?: return TmpProbeState.UNKNOWN
        return storeGuarded(SyncOperation.DOWNLOAD) { store.probePendingTmp(impl) }
    }

    override suspend fun deleteFinal(attempt: SyncWriteAttempt): Boolean {
        val impl = attempt.impl as? WriteAttempt
            ?: throw SyncException(SyncErrorCode.STORAGE_ERROR, SyncOperation.DOWNLOAD)
        return storeGuarded(SyncOperation.DOWNLOAD) { store.deleteRef(impl.ref) }
    }

    override suspend fun probeFinalized(id: Long, expectedSize: Long): MediaProbeState {
        val ref = resolveRef(id) ?: return MediaProbeState.IO_ERROR
        if (!registry.isAvailable(ref.volumeId)) return MediaProbeState.IO_ERROR // E8
        return storeGuarded(SyncOperation.SCAN) { store.probeFinalized(ref.toContract(), expectedSize) }
    }

    // ---- 空间 ----

    override suspend fun usableSpace(target: SyncVolumeTarget): SyncSpaceOutcome =
        try {
            when (val r = store.space(VolumeTarget(target.volumeId))) {
                is cn.vectory.syncplayer.storage.SpaceResult.Available ->
                    SyncSpaceOutcome.Available(r.bytes)
                is cn.vectory.syncplayer.storage.SpaceResult.Unavailable ->
                    SyncSpaceOutcome.Unavailable(
                        when (r.reason) {
                            cn.vectory.syncplayer.storage.SpaceResult.Unavailable.Reason.VOLUME_MISSING ->
                                SyncErrorCode.STORAGE_UNAVAILABLE
                            cn.vectory.syncplayer.storage.SpaceResult.Unavailable.Reason.IO_ERROR ->
                                SyncErrorCode.STORAGE_IO_TRANSIENT
                        }
                    )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: StorageAccessFailure) {
            SyncSpaceOutcome.Unavailable(e.toSyncErrorCode())
        }

    // ---- 删除 / 存在性（按行卷路由） ----

    override suspend fun checkMediaExistence(id: Long): MediaExistence =
        withRefOrLegacy(id, { ref ->
            mapStat(storeGuarded(SyncOperation.DELETE) { volumeOf(ref).strictStat(relMedia(ref)) })
        }) { store.checkExistence(id) }

    override suspend fun checkThumbExistence(id: Long): MediaExistence =
        withRefOrLegacy(id, { ref ->
            mapStat(storeGuarded(SyncOperation.DELETE) { volumeOf(ref).strictStat(relThumb(ref)) })
        }) { store.checkThumbExistence(id) }

    override suspend fun deleteMedia(id: Long): Boolean =
        withRefOrLegacy(id, { ref ->
            storeGuarded(SyncOperation.DELETE) { store.deleteRef(ref.toContract()) }
        }) { store.delete(id) }

    override suspend fun deleteThumb(id: Long): Boolean =
        withRefOrLegacy(id, { ref ->
            storeGuarded(SyncOperation.DELETE) { store.deleteThumb(ref.toContract()) }
        }) { store.deleteThumb(id) }

    override suspend fun ownedVolumeIds(): Set<String> =
        videoRepository.getAll().map { it.volumeId }.toSet()

    override suspend fun deleteAllOwned(volumeId: String, datasetId: String?): Pair<Int, Int> =
        withContext(ioDispatcher) {
            if (volumeId == VolumeIds.INTERNAL) {
                store.deleteAllMedia()
            } else {
                val ds = datasetId ?: datasetDao.get()?.datasetId
                    ?: return@withContext 0 to 0 // 无 dataset ⇒ 该卷无本应用数据
                val volume = registry.resolve(volumeId)
                    ?: return@withContext 0 to 0 // 卷不可达：由重置前守卫拒绝，不会走到这
                deleteTree(volume, MediaPaths.externalRootRel(ds))
            }
        }

    override suspend fun updateSyncStorageMetadata(
        attempt: SyncWriteAttempt,
        expectedOldGeneration: String?,
        fileSize: Long,
        remoteModifiedTime: Long?,
        dateSynced: Long,
    ): Int {
        val ref = attempt.ref
        return try {
            videoRepository.updateSyncStorageMetadata(
                id = ref.id,
                expectedOldGeneration = expectedOldGeneration,
                volumeId = ref.volumeId,
                storageFormat = when (ref.format) {
                    SyncStorageFormat.PLAIN -> StorageFormat.PLAIN
                    SyncStorageFormat.CTR_V2 -> StorageFormat.CTR_V2
                }.name,
                iv = ref.iv?.copyOf(),
                generation = ref.generation,
                storageState = cn.vectory.syncplayer.storage.StorageState.READY.name,
                fileSize = fileSize,
                remoteModifiedTime = remoteModifiedTime,
                dateSynced = dateSynced,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SyncException(SyncErrorCode.DATABASE_ERROR, SyncOperation.DOWNLOAD, cause = e)
        }
    }

    // ---- 内部工具 ----

    /** store 调用边界：StorageAccessFailure/IO → 结构化 SyncException；CE/SyncException 先传播。 */
    private suspend fun <T> storeGuarded(operation: SyncOperation, block: suspend () -> T): T {
        return try {
            withContext(ioDispatcher) { block() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SyncException) {
            throw e
        } catch (e: StorageAccessFailure) {
            throw SyncException(e.toSyncErrorCode(), operation, cause = e)
        } catch (e: Exception) {
            throw SyncException(SyncErrorCode.STORAGE_IO_TRANSIENT, operation, cause = e)
        }
    }

    private fun volumeOf(ref: SyncStorageRef): MediaVolume {
        val v = registry.resolve(ref.volumeId)
            ?: throw StorageAccessFailure(StorageAccessFailure.Reason.VOLUME_UNAVAILABLE)
        return v
    }

    private fun relMedia(ref: SyncStorageRef): String = when (ref.format) {
        SyncStorageFormat.PLAIN -> MediaPaths.mediaRelPath(ref.id)
        SyncStorageFormat.CTR_V2 -> MediaPaths.externalMediaRelPath(ref.datasetId!!, ref.id, ref.generation!!)
    }

    private fun relThumb(ref: SyncStorageRef): String = when (ref.format) {
        SyncStorageFormat.PLAIN -> MediaPaths.thumbRelPath(ref.id)
        SyncStorageFormat.CTR_V2 -> MediaPaths.externalThumbRelPath(ref.datasetId!!, ref.id, ref.generation!!)
    }

    private fun SyncStorageRef.toContract(): StorageRef = StorageRef(
        id = id, volumeId = volumeId, datasetId = datasetId, generation = generation,
        format = when (format) {
            SyncStorageFormat.PLAIN -> StorageFormat.PLAIN
            SyncStorageFormat.CTR_V2 -> StorageFormat.CTR_V2
        },
        iv = iv?.copyOf(), plainSize = plainSize,
    )

    private fun mapStat(stat: FileStat): MediaExistence = when (stat) {
        is FileStat.Exists -> MediaExistence.EXISTS
        is FileStat.Missing -> MediaExistence.NOT_FOUND
        is FileStat.IoError -> MediaExistence.IO_ERROR(stat.reason)
    }

    /** 有契约引用时走 [refAction]；行不存在/引用非法时回退 [legacyAction]（内部卷旧行为）。 */
    private suspend fun <T> withRefOrLegacy(
        id: Long,
        refAction: suspend (SyncStorageRef) -> T,
        legacyAction: suspend () -> T,
    ): T {
        return try {
            val r = resolveRef(id)
            if (r != null) refAction(r) else legacyAction()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            legacyAction()
        }
    }

    /** 递归删除外部卷上本应用 dataset 根（计数语义与 store.deleteAllMedia 一致）。 */
    private suspend fun deleteTree(volume: MediaVolume, rel: String): Pair<Int, Int> {
        val deleted = intArrayOf(0)
        val failed = intArrayOf(0)
        fun walk(dirRel: String) {
            val names = try {
                volume.listOwned(dirRel)
            } catch (_: Exception) {
                null
            } ?: run {
                failed[0]++
                return
            }
            for (name in names) {
                val childRel = "$dirRel/$name"
                val stat = try {
                    volume.strictStat(childRel)
                } catch (_: Exception) {
                    null
                }
                val isDir = stat == null // listOwned 能列出但 strictStat 失败 ⇒ 按目录尝试下钻
                if (isDir) {
                    walk(childRel)
                } else {
                    try {
                        if (volume.delete(childRel)) deleted[0]++ else {
                            val still = try {
                                volume.strictStat(childRel)
                            } catch (_: Exception) {
                                FileStat.IoError("recheck")
                            }
                            if (still is FileStat.Missing) deleted[0]++ else failed[0]++
                        }
                    } catch (_: Exception) {
                        failed[0]++
                    }
                }
            }
        }
        withContext(ioDispatcher) { walk(rel) }
        return deleted[0] to failed[0]
    }
}

/**
 * Legacy 单卷实现：适配现有 [MediaFileStore]（仅内部卷）。所有写/探测 1:1 委托，
 * 因此对以 mock MediaFileStore 为基座的既有测试完全透传。生产 DI 绑定
 * [DualVolumeSyncStoragePort]（见 di/SyncModule.kt）；本类保留给手工构造/测试。
 */
internal class LegacySyncStoragePort(
    private val storeLazy: Lazy<MediaFileStore>,
    private val videoRepository: VideoRepository? = null,
    private val defaultVolumeIdProvider: (suspend () -> String?)? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SyncStoragePort {

    private val store: MediaFileStore get() = storeLazy.get()

    override suspend fun snapshotDefaultVolume(): SyncVolumeTarget =
        SyncVolumeTarget(defaultWriteVolumeId())

    override suspend fun resolveRef(id: Long): SyncStorageRef? = null

    override suspend fun defaultWriteVolumeId(): String {
        val provider = defaultVolumeIdProvider ?: return VOLUME_ID_INTERNAL
        val raw = provider() ?: return VOLUME_ID_INTERNAL
        return raw.ifBlank { VOLUME_ID_INTERNAL }
    }

    override suspend fun setDefaultWriteVolume(volumeId: String): Boolean =
        volumeId == VOLUME_ID_INTERNAL // legacy 只有内部卷

    override fun isExternalVolume(volumeId: String): Boolean = false

    override fun isVolumeAvailable(volumeId: String): Boolean = true

    override suspend fun registerExternalVolume(): String? = null

    override suspend fun unregisterExternalVolume(volumeId: String): Boolean = false

    override suspend fun listVolumes(): List<SyncVolumeInfo> {
        val defaultVolumeId = defaultWriteVolumeId()
        return listOf(
            SyncVolumeInfo(
                volumeId = VOLUME_ID_INTERNAL,
                isExternal = false,
                isAvailable = true,
                usableBytes = withContext(ioDispatcher) { store.usableSpace() }.takeIf { it > 0 },
                ownsRows = true,
                isDefault = defaultVolumeId == VOLUME_ID_INTERNAL,
            )
        )
    }

    override suspend fun newAttempt(
        id: Long,
        target: SyncVolumeTarget,
        expectedLength: Long,
    ): SyncWriteAttempt = SyncWriteAttempt(
        ref = SyncStorageRef(id = id, volumeId = target.volumeId, plainSize = expectedLength),
        attemptId = id.toString(),
    )

    override suspend fun openWriteSink(attempt: SyncWriteAttempt): OutputStream =
        withContext(ioDispatcher) { store.openWriteSink(attempt.id, attempt.ref.plainSize) }

    override suspend fun finalize(attempt: SyncWriteAttempt): Boolean =
        withContext(ioDispatcher) { store.finalize(attempt.id) }

    override suspend fun verifyReadable(attempt: SyncWriteAttempt) {
        withContext(ioDispatcher) { store.verifyReadable(attempt.id, attempt.ref.plainSize) }
    }

    override suspend fun cancel(attempt: SyncWriteAttempt) {
        withContext(ioDispatcher) { store.cancel(attempt.id) }
    }

    override suspend fun probePendingTmp(attempt: SyncWriteAttempt): TmpProbeState =
        withContext(ioDispatcher) { store.probePendingTmp(attempt.id) }

    override suspend fun deleteFinal(attempt: SyncWriteAttempt): Boolean =
        withContext(ioDispatcher) { store.delete(attempt.id) }

    override suspend fun probeFinalized(id: Long, expectedSize: Long): MediaProbeState =
        withContext(ioDispatcher) { store.probeFinalized(id, expectedSize) }

    override suspend fun usableSpace(target: SyncVolumeTarget): SyncSpaceOutcome =
        SyncSpaceOutcome.Available(withContext(ioDispatcher) { store.usableSpace() })

    override suspend fun checkMediaExistence(id: Long): MediaExistence =
        withContext(ioDispatcher) { store.checkExistence(id) }

    override suspend fun checkThumbExistence(id: Long): MediaExistence =
        withContext(ioDispatcher) { store.checkThumbExistence(id) }

    override suspend fun deleteMedia(id: Long): Boolean =
        withContext(ioDispatcher) { store.delete(id) }

    override suspend fun deleteThumb(id: Long): Boolean =
        withContext(ioDispatcher) { store.deleteThumb(id) }

    override suspend fun ownedVolumeIds(): Set<String> = setOf(VOLUME_ID_INTERNAL)

    override suspend fun deleteAllOwned(volumeId: String, datasetId: String?): Pair<Int, Int> =
        withContext(ioDispatcher) { store.deleteAllMedia() }

    override suspend fun updateSyncStorageMetadata(
        attempt: SyncWriteAttempt,
        expectedOldGeneration: String?,
        fileSize: Long,
        remoteModifiedTime: Long?,
        dateSynced: Long,
    ): Int {
        val repo = videoRepository
            ?: throw SyncException(SyncErrorCode.DATABASE_ERROR, SyncOperation.DOWNLOAD)
        return repo.updateSyncMetadata(attempt.id, fileSize, remoteModifiedTime, dateSynced)
    }
}
