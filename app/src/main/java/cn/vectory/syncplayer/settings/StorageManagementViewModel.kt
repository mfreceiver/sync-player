package cn.vectory.syncplayer.settings

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.vectory.syncplayer.R
import androidx.core.net.toUri
import cn.vectory.syncplayer.data.local.dao.VideoItemDao
import cn.vectory.syncplayer.data.local.dao.PlaybackRecordDao
import cn.vectory.syncplayer.data.local.dao.PendingSyncActionDao
import cn.vectory.syncplayer.data.repository.UserPreferencesRepository
import cn.vectory.syncplayer.storage.volume.EncryptedMediaStore
import cn.vectory.syncplayer.storage.volume.MediaVolumeProvider
import cn.vectory.syncplayer.storage.volume.MediaPaths
import cn.vectory.syncplayer.di.IoDispatcher
import cn.vectory.syncplayer.sync.SyncEngine
import cn.vectory.syncplayer.ui.util.formatFileSize
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

/**
 * F06：SAF 持久化授权（takePersistableUriPermission）失败的结构化错误。
 * 刻意不携带 URI / 底层 message 字段——错误可呈现、可重试，但无敏感信息可泄漏。
 */
sealed interface TreePermissionGrantError {
    data object Denied : TreePermissionGrantError
}

data class StorageState(
    val isExternal: Boolean = false,
    val externalPath: String? = null,
    val usableSpaceLabel: String = "—",
    val mediaFileCount: Int = 0,
    val canSwitch: Boolean = true,
    val canReset: Boolean = false,
    val isResetting: Boolean = false,
    val resetWarning: String? = null,
    /** 非 null = 最近一次 SAF 授权被拒（F06：不再静默）；授权成功即清空。 */
    val permissionError: TreePermissionGrantError? = null,
)

@HiltViewModel
class StorageManagementViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaVolumeProvider: MediaVolumeProvider,
    private val userPrefs: UserPreferencesRepository,
    private val videoItemDao: VideoItemDao,
    private val playbackRecordDao: PlaybackRecordDao,
    private val pendingSyncActionDao: PendingSyncActionDao,
    private val syncEngine: SyncEngine,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _state = MutableStateFlow(StorageState())
    val state: StateFlow<StorageState> = _state.asStateFlow()

    /**
     * BUG-004/005：refresh 代次失效（epoch）——发布顺序保证。每次 refresh 领取
     * 单调递增代次，任一时刻只有最新代次允许写 _state；旧代次在成功路径与
     * 降级路径的发布点都做代次比对，失配即丢弃，消灭"过期刷新覆盖"。
     * 注：代次检查与其后的状态赋值是相邻两条语句（无挂起点），当前 viewModelScope
     * 的 Main 单线程 confinement 下成立；若日后 refresh 移出 Main 或在检查与
     * 赋值之间插入挂起点，须改用锁/赋值后复检协议。
     */
    private val refreshEpoch = AtomicLong(0)

    init {
        refresh()
    }

    fun refresh() {
        val epoch = refreshEpoch.incrementAndGet()   // 领取代次；旧 refresh 发布权即时失效
        viewModelScope.launch {
            try {
                val treeUriStr = userPrefs.getMediaStorageTreeUri()
                val isExternal = treeUriStr != null
                val externalPath = if (isExternal) resolveTreeDisplayName(context, treeUriStr!!) else null
                val count = withContext(ioDispatcher) {
                    try { videoItemDao.getTotalItemCount() } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to query file count", e)
                        Int.MAX_VALUE  // fail-closed: unknown → treat as "has files" → can't switch
                    }
                }
                val usableSpaceLabel = withContext(ioDispatcher) {
                    try {
                        val bytes = mediaVolumeProvider.current().usableSpace()
                        if (bytes < 0L) "—" else formatFileSize(bytes)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) { "—" }
                }
                val syncRunning = syncEngine.progress.value.isRunning
                if (refreshEpoch.get() != epoch) return@launch   // 乱序守卫：发布前自检代次
                _state.value = StorageState(
                    isExternal = isExternal,
                    externalPath = externalPath,
                    usableSpaceLabel = usableSpaceLabel,
                    mediaFileCount = count,
                    canSwitch = count == 0 && !syncRunning,
                    canReset = count > 0 && !syncRunning,
                    isResetting = _state.value.isResetting,
                    resetWarning = _state.value?.resetWarning,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to refresh storage state", e)
                if (refreshEpoch.get() != epoch) return@launch   // 旧失败不得覆盖新成功
                // BUG-004 降级（URI 首读失败路径）：状态不可知 → 禁用切换/重置
                //（对存储位置变更 fail-closed，对齐 count 未知按 MAX_VALUE 的既有约定）；
                // 空间显示 "—"。
                _state.update { it.copy(usableSpaceLabel = "—", canSwitch = false, canReset = false) }
            }
        }
    }

    fun switchToInternal() {
        viewModelScope.launch {
            try {
                syncEngine.storageOperationMutex.withLock {
                    // Re-validate DB inside mutex — canSwitch may be stale
                    val count = videoItemDao.getTotalItemCount()
                    if (count > 0) return@withLock
                    userPrefs.setMediaStorageTreeUri(null)
                }
            } catch (e: CancellationException) {
                throw e                                    // CE：跳过 refresh（协程已取消）
            } catch (e: Exception) {
                Log.e(TAG, "Failed to switch to internal storage", e)   // BUG-005：不 crash
            }
            refresh()                                      // 内部已自守卫 + 领新代次（发布顺序闸门）
        }
    }

    fun switchToExternal(treeUri: String) {
        viewModelScope.launch {
            try {
                syncEngine.storageOperationMutex.withLock {
                    val count = videoItemDao.getTotalItemCount()
                    if (count > 0) return@withLock
                    userPrefs.setMediaStorageTreeUri(treeUri)
                }
            } catch (e: CancellationException) {
                throw e                                    // CE：跳过 refresh（协程已取消）
            } catch (e: Exception) {
                Log.e(TAG, "Failed to switch to external storage", e)   // BUG-005：不 crash
            }
            refresh()                                      // 内部已自守卫 + 领新代次（发布顺序闸门）
        }
    }

    fun resetStorage() {
        viewModelScope.launch {
            _state.update { it.copy(isResetting = true, resetWarning = null) }
            try {
                var deleted = 0
                var failed = 0
                withContext(ioDispatcher) {
                    // 🔴 Real mutex: prevents concurrent sync (executeSync/dryRun/executeDelete
                    // all acquire the same storageOperationMutex). Eliminates TOCTOU race.
                    syncEngine.storageOperationMutex.withLock {
                        val vol = mediaVolumeProvider.current()
                        for (entry in vol.list("")) {
                            // 🔴 Strict ASCII digits only — rejects +1, -1, foo, 01, etc.
                            val bucket = if (entry.isNotEmpty() && entry.all { it in '0'..'9' }) entry.toLongOrNull() else null
                            if (bucket != null && bucket >= 0) {
                                for (name in vol.list(entry)) {
                                    if (isValidMediaFileName(name, bucket)) {
                                        if (vol.delete("$entry/$name")) deleted++ else failed++
                                    }
                                }
                            } else if (entry == "thumbs") {
                                for (name in vol.list("thumbs")) {
                                    if (isValidThumbFileName(name)) {
                                        if (vol.delete("thumbs/$name")) deleted++ else failed++
                                    }
                                }
                            }
                        }
                        if (failed > 0) Log.w(TAG, "Reset: $deleted deleted, $failed failed")
                        videoItemDao.deleteAll()
                        pendingSyncActionDao.deleteAll()
                        playbackRecordDao.deleteAll()
                    }
                }
                // 🔴 Failure feedback: show warning if some files couldn't be deleted
                val warning = if (failed > 0) context.getString(R.string.storage_reset_partial, deleted, failed) else null
                _state.update { it.copy(isResetting = false, resetWarning = warning) }
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reset storage", e)
                _state.update { it.copy(isResetting = false, resetWarning = context.getString(R.string.storage_reset_failed)) }
            }
        }
    }

    /**
     * F06：SAF take 流程中 takePersistableUriPermission 抛 SecurityException 时由
     * Screen 调用——错误进入状态（Screen 呈现 + 重试入口），不再静默吞掉。
     */
    fun onTreePermissionDenied() {
        _state.update { it.copy(permissionError = TreePermissionGrantError.Denied) }
    }

    /** F06：授权成功（take 通过、即将切换）时清除错误状态。 */
    fun onTreePermissionGranted() {
        _state.update { it.copy(permissionError = null) }
    }

    /**
     * Only matches files produced by [MediaPaths.mediaRelPath] (+ temp variants).
     * Strict ASCII digits only — rejects +1, -1, empty, unicode digits.
     */
    private fun isValidMediaFileName(name: String, bucket: Long): Boolean {
        val idStr = when {
            name.endsWith(".enc.enc.tmp") -> name.dropLast(12)  // legacy double-.enc temp
            name.endsWith(".enc.tmp") -> name.dropLast(8)        // current temp
            name.endsWith(".enc") -> name.dropLast(4)            // final media
            else -> return false
        }
        if (idStr.isEmpty() || idStr.any { it !in '0'..'9' }) return false
        return idStr.toLongOrNull()?.let { it / 1000 == bucket } ?: false
    }

    private fun isValidThumbFileName(name: String): Boolean {
        if (!name.endsWith(".enc")) return false
        val idStr = name.dropLast(4)
        return idStr.isNotEmpty() && idStr.all { it in '0'..'9' } && idStr.toLongOrNull() != null
    }

    companion object {
        private const val TAG = "StorageManagementVM"

        /** 尽量解析 SAF tree 目录显示名；失败则回退到 URI 末段。 */
        fun resolveTreeDisplayName(context: Context, treeUriStr: String): String =
            resolveTreeDisplayNameOrNull(context, treeUriStr) ?: fallbackUriLabel(treeUriStr)

        /**
         * F04：解析 SAF tree 目录显示名；失败/无权限返回 null（安全哨兵，由调用方
         * 回退固定资源通用文案）。**不得**用 [fallbackUriLabel] 作此路径的回退——
         * 其解析失败时会返回完整 URI 字符串（URI 派生文本不可进 UI）。
         */
        fun resolveTreeDisplayNameOrNull(context: Context, treeUriStr: String): String? {
            return try {
                val uri = treeUriStr.toUri()
                val docId = android.provider.DocumentsContract.getTreeDocumentId(uri)
                val docUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(uri, docId)
                context.contentResolver.query(
                    docUri,
                    arrayOf(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                    null, null, null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
                }
            } catch (e: CancellationException) {
                // 红线：取消必须先传播，绝不吞——解析被取消时抛 CE 而非安全哨兵 null。
                throw e
            } catch (_: Exception) {
                null
            }
        }

        private fun fallbackUriLabel(treeUriStr: String): String {
            val decoded = android.net.Uri.decode(treeUriStr)
            val last = decoded.substringAfterLast(':').substringAfterLast('/')
            return last.ifBlank { treeUriStr }
        }
    }
}
