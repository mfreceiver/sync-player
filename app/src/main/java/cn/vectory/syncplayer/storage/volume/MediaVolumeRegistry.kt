package cn.vectory.syncplayer.storage.volume

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import cn.vectory.syncplayer.storage.StoragePaths
import cn.vectory.syncplayer.storage.VolumeIds
import cn.vectory.syncplayer.storage.VolumeTarget
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 卷解析器（store/Provider 消费的窄接口；测试可注入 fake）。
 * 每次 resolve 重新解析 root——已打开 FD 绑定当时文件，不随设置切换。
 */
interface VolumeResolver {
    val internalVolumeId: String

    /** null = 卷不可用（未挂载/未登记/身份不确定）。绝不重定向另一张卡。 */
    fun resolve(volumeId: String): MediaVolume?

    /** 当前默认写入卷快照（existing 为 null 时的新增文件目标）。 */
    fun defaultWriteTarget(): VolumeTarget

    /** 卷当前是否可用（拔卡中途 fail-closed 的操作前后验证辅助）。 */
    fun isAvailable(volumeId: String): Boolean

    /** 已登记卷 id 列表（internal 在前）。 */
    fun registeredVolumeIds(): List<String>
}

/** 外部卷候选（枚举结果；登记与否是另一回事）。 */
data class ExternalCandidate(val uuid: String, val dir: File)

/** android.os.storage.StorageVolume 摘要（可 fake）。 */
data class VolumeMeta(val uuid: String?, val removable: Boolean, val state: String)

/**
 * 卷注册表：internal（filesDir/userdata）+ 可选已登记 external。
 *
 * - 从 [Context.getExternalFilesDirs] 枚举候选（数组下标仅用于枚举，不持久化）；
 *   以 [StorageManager.getStorageVolume] 确认 removable/UUID/MEDIA_MOUNTED；
 *   UUID 为空/身份不确定 ⇒ 不登记。
 * - 登记外部卷前在其 userdata-v2 目录做小型 write/read/rename/delete 能力检查；
 *   不删除非本应用布局内容、不创建可疑失挂载路径。
 * - volumeId 持久化逻辑见上（`internal` / `external:<uuid>`）；默认写入卷的持久化
 *   归实施者 B（UserPreferencesRepository/设置页）；本类只持运行时快照，
 *   [setRegisteredExternal] 由启动恢复/设置页调用。
 */
@Singleton
class MediaVolumeRegistry @Inject constructor(
    @ApplicationContext private val context: Context,
    private val storagePaths: StoragePaths,
) : VolumeResolver {

    override val internalVolumeId: String = VolumeIds.INTERNAL

    @Volatile
    private var defaultVolumeId: String = VolumeIds.INTERNAL

    /** 已登记外部卷：uuid → app 专属目录（每次 mount 周期重新解析）。 */
    @Volatile
    private var registeredExternal: Map<String, File> = emptyMap()

    override fun resolve(volumeId: String): MediaVolume? = try {
        when {
            volumeId == VolumeIds.INTERNAL -> internalVolume()
            VolumeIds.isExternal(volumeId) -> resolveExternalVerified(volumeId)
            else -> null
        }
    } catch (_: Exception) {
        null // 卷根不可用（未挂载等）——fail-closed，不 fallback
    }

    /**
     * P0-5：外部卷每次关键操作（store 每个 IO 前都 resolve）重新复核挂载状态：
     * 受控根必须已存在（绝不 mkdirs 重建）+ StorageManager 确认 removable +
     * MEDIA_MOUNTED + UUID 与登记条目匹配。任一不满足 ⇒ null（卷不可用），
     * 绝不重定向另一张卡、绝不沿缓存路径写进失挂载后重建的空挂载点。
     */
    private fun resolveExternalVerified(volumeId: String): MediaVolume? {
        val uuid = VolumeIds.externalUuid(volumeId) ?: return null
        val dir = registeredExternal[uuid] ?: return null
        if (!dir.isDirectory) return null // 挂载点缺失/未挂载——不创建假根
        val sm = context.getSystemService(StorageManager::class.java) ?: return null
        val meta = try {
            storageVolumeMeta(sm, dir)
        } catch (_: Exception) {
            null
        } ?: return null
        if (!meta.removable || meta.state != Environment.MEDIA_MOUNTED) return null
        if (meta.uuid != uuid) return null // 身份不匹配（换了卡）——不启用
        return FileVolume(volumeId, dir)
    }

    override fun defaultWriteTarget(): VolumeTarget = VolumeTarget(defaultVolumeId)

    override fun isAvailable(volumeId: String): Boolean = resolve(volumeId) != null

    override fun registeredVolumeIds(): List<String> =
        listOf(VolumeIds.INTERNAL) + registeredExternal.keys.sorted().map { VolumeIds.external(it) }

    // ---- 登记 / 默认卷（运行时状态；持久化由 B） ----

    /** 设置默认写入卷：必须已登记且当前可用，否则返回 false（不改变现状）。 */
    fun setDefaultWriteVolume(volumeId: String): Boolean {
        if (volumeId == VolumeIds.INTERNAL) {
            defaultVolumeId = volumeId
            return true
        }
        if (isAvailable(volumeId)) {
            defaultVolumeId = volumeId
            return true
        }
        return false
    }

    /**
     * 枚举当前挂载的外部候选（removable + MEDIA_MOUNTED + UUID 确定）。
     * 阻塞系统调用，调用方负责 IO dispatcher。
     */
    fun enumerateExternalCandidates(): List<ExternalCandidate> {
        val sm = context.getSystemService(StorageManager::class.java) ?: return emptyList()
        val dirs = context.getExternalFilesDirs(null) ?: return emptyList()
        val out = mutableListOf<ExternalCandidate>()
        for (dir in dirs) {
            if (dir == null) continue
            if (dir == dirs.firstOrNull()) continue // index 0 = internal
            val meta = try {
                storageVolumeMeta(sm, dir)
            } catch (_: Exception) {
                null
            } ?: continue
            if (!meta.removable || meta.state != Environment.MEDIA_MOUNTED) continue
            val uuid = meta.uuid ?: continue // UUID 不确定身份 ⇒ 不启用
            out.add(ExternalCandidate(uuid, dir))
        }
        return out
    }

    /** android 层摘取（测试可覆写）。 */
    protected open fun storageVolumeMeta(sm: StorageManager, dir: File): VolumeMeta? {
        val v: StorageVolume = sm.getStorageVolume(dir) ?: return null
        return VolumeMeta(v.uuid, v.isRemovable, v.state)
    }

    /**
     * 登记外部卷（做能力检查：write/read/rename/delete 于其 `userdata-v2/.capability`）。
     * 返回 volumeId；检查失败返回 null（不登记）。阻塞 IO，调用方负责 IO dispatcher。
     */
    fun registerExternal(candidate: ExternalCandidate, datasetId: String?): String? {
        val volumeId = VolumeIds.external(candidate.uuid)
        // 挂载点必须已真实存在（P0-5：登记路径同样不 mkdirs 外部根）。
        if (!candidate.dir.isDirectory) return null
        val volume = FileVolume(volumeId, candidate.dir)
        val probeRel = probeRelPath(datasetId)
        return try {
            val sink = volume.openWrite(probeRel)
            sink.use { it.write(byteArrayOf(0x70)) }
            val handle = volume.openRead(probeRel)
            val buf = ByteArray(1)
            handle.use {
                it.seek(0)
                if (it.read(buf, 0, 1) != 1 || buf[0] != 0x70.toByte()) return null
            }
            val renamed = probeRel + ".ok"
            if (!volume.atomicRename(probeRel, renamed)) return null
            volume.delete(renamed)
            registeredExternal = registeredExternal + (candidate.uuid to candidate.dir)
            volumeId
        } catch (_: Exception) {
            null
        }
    }

    /** 注销（设置页显式操作）；不影响已打开 FD。 */
    fun unregisterExternal(volumeId: String) {
        val uuid = VolumeIds.externalUuid(volumeId) ?: return
        registeredExternal = registeredExternal - uuid
        if (defaultVolumeId == volumeId) defaultVolumeId = VolumeIds.INTERNAL
    }

    private fun probeRelPath(datasetId: String?): String =
        cn.vectory.syncplayer.storage.MediaPaths.externalRootRel(datasetId ?: "probe") + "/.capability"

    private fun internalVolume(): MediaVolume =
        FileVolume(VolumeIds.INTERNAL, storagePaths.userdataRoot)
}
