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
import javax.inject.Inject

data class StorageState(
    val isExternal: Boolean = false,
    val externalPath: String? = null,
    val usableSpaceLabel: String = "—",
    val mediaFileCount: Int = 0,
    val canSwitch: Boolean = true,
    val canReset: Boolean = false,
    val isResetting: Boolean = false,
    val resetWarning: String? = null,
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

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
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
        }
    }

    fun switchToInternal() {
        viewModelScope.launch {
            syncEngine.storageOperationMutex.withLock {
                // Re-validate DB inside mutex — canSwitch may be stale
                val count = videoItemDao.getTotalItemCount()
                if (count > 0) return@withLock
                userPrefs.setMediaStorageTreeUri(null)
            }
            refresh()
        }
    }

    fun switchToExternal(treeUri: String) {
        viewModelScope.launch {
            syncEngine.storageOperationMutex.withLock {
                val count = videoItemDao.getTotalItemCount()
                if (count > 0) return@withLock
                userPrefs.setMediaStorageTreeUri(treeUri)
            }
            refresh()
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
        fun resolveTreeDisplayName(context: Context, treeUriStr: String): String {
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
                } ?: fallbackUriLabel(treeUriStr)
            } catch (_: Exception) {
                fallbackUriLabel(treeUriStr)
            }
        }

        private fun fallbackUriLabel(treeUriStr: String): String {
            val decoded = android.net.Uri.decode(treeUriStr)
            val last = decoded.substringAfterLast(':').substringAfterLast('/')
            return last.ifBlank { treeUriStr }
        }
    }
}
