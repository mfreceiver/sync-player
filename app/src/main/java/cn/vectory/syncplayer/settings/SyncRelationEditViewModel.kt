package cn.vectory.syncplayer.settings

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.vectory.syncplayer.R
import cn.vectory.syncplayer.data.model.RemoteFile
import cn.vectory.syncplayer.data.model.SyncProtocol
import cn.vectory.syncplayer.data.model.SyncRelation
import cn.vectory.syncplayer.data.model.SyncSource
import cn.vectory.syncplayer.data.repository.SyncRelationRepository
import cn.vectory.syncplayer.data.repository.SyncSourceRepository
import cn.vectory.syncplayer.di.IoDispatcher
import cn.vectory.syncplayer.storage.StoragePaths
import cn.vectory.syncplayer.sync.RemoteFileClientFactory
import cn.vectory.syncplayer.sync.classifyRemoteError
import cn.vectory.syncplayer.sync.RemoteBrowseError
import cn.vectory.syncplayer.sync.SyncErrorCode
import cn.vectory.syncplayer.sync.SyncException
import cn.vectory.syncplayer.sync.syncErrorMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class StorageInfo(
    val typeLabel: String,  // "内部存储" 或 "外部存储"
    val pathLabel: String,  // 实际路径
)

/**
 * F09: typed test-drive outcome for the remote-path check. The ViewModel only carries
 * the type (+ structured [SyncErrorCode]); display strings are mapped at the Screen via
 * stringResource against existing entries — the ViewModel never produces copy.
 */
sealed interface RelationTestResult {
    /** 远端目录可访问（唯一成功态）。 */
    data object DirAccessible : RelationTestResult

    /** 前置条件不满足，未发起网络访问。 */
    data class PreconditionFailed(val reason: Reason) : RelationTestResult {
        enum class Reason { NO_SOURCE, NO_PATH }
    }

    /** 访问失败；[code] 为结构化错误码（对非 SyncException 的回退分类镜像 syncErrorMessage）。 */
    data class AccessFailed(val code: SyncErrorCode) : RelationTestResult
}

/** F09: typed save/delete outcome — 颜色与导航（仅 Saved 返回列表）由类型分支驱动。 */
sealed interface RelationSaveResult {
    data object Saved : RelationSaveResult
    data object Deleted : RelationSaveResult
    data object DeleteFailed : RelationSaveResult
}

/**
 * Protocol-aware remote path normalization for the save entry (L4-1).
 *
 * SMB and SFTP deliberately diverge on anchoring semantics:
 *  - SMB strips a leading `/` (share-root-relative model); the bare root itself is
 *    REJECTED (a whole-share sync is the BUG-006 E8 hole; the SMB client layer is
 *    already fail-closed — this only moves the failure from sync time to save time).
 *  - SFTP PRESERVES a leading `/` (absolute anchoring; stripping it would flip an
 *    absolute scan to server-cwd-relative). SFTP root `/` stays a legal sync target.
 *  - Bare `.` is REJECTED for both protocols: it is cwd-relative and drifting;
 *    rewriting it to `/` would flip the anchoring and scan the wrong directory.
 *  - `..` segments are REJECTED for both (sync roots must be canonical stable paths).
 *  - Empty segments (`a//b`) and non-first `.` segments (`a/./b`) collapse —
 *    anchor-preserving equivalences. Dots inside a name (`shows/v2.0`) stay as-is.
 *
 * Returns null when the input must be rejected. Pure function: no logging, no echo
 * of the input (red line #6).
 */
internal fun normalizeRemotePath(raw: String, protocol: SyncProtocol): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val absolute = protocol == SyncProtocol.SFTP && trimmed.startsWith("/")
    val segments = mutableListOf<String>()
    for (segment in trimmed.split('/')) {
        when {
            segment.isEmpty() || segment == "." -> Unit // collapse empty / dot-only segments
            segment == ".." -> return null              // traversal rejected for both protocols
            else -> segments.add(segment)
        }
    }
    if (segments.isEmpty()) {
        // Path consists solely of separators and `.` segments.
        return when {
            absolute -> "/"  // explicit SFTP absolute root — existing legal sync target
            else -> null     // bare `.` (cwd-relative) and SMB root — both rejected
        }
    }
    return (if (absolute) "/" else "") + segments.joinToString("/")
}

@HiltViewModel
class SyncRelationEditViewModel @Inject constructor(
    private val syncRelationRepository: SyncRelationRepository,
    private val syncSourceRepository: SyncSourceRepository,
    private val remoteFileClientFactory: RemoteFileClientFactory,
    private val storagePaths: StoragePaths,
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val relationId: Long = savedStateHandle.get<Long>("relationId") ?: 0L

    private val _name = MutableStateFlow("")
    val name = _name.asStateFlow()

    private val _selectedSourceId = MutableStateFlow(0L)
    val selectedSourceId = _selectedSourceId.asStateFlow()

    private val _remotePath = MutableStateFlow("")
    val remotePath = _remotePath.asStateFlow()

    private val _localBasePath = MutableStateFlow("")
    val localBasePath = _localBasePath.asStateFlow()

    private val _availableSources = MutableStateFlow<List<SyncSource>>(emptyList())
    val availableSources = _availableSources.asStateFlow()

    private val _remoteDirectories = MutableStateFlow<List<RemoteFile>>(emptyList())
    val remoteDirectories = _remoteDirectories.asStateFlow()

    private val _currentRemoteBrowsePath = MutableStateFlow("/")
    val currentRemoteBrowsePath = _currentRemoteBrowsePath.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving = _isSaving.asStateFlow()

    private val _saveResult = MutableStateFlow<RelationSaveResult?>(null)
    val saveResult = _saveResult.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _browseError = MutableStateFlow<RemoteBrowseError?>(null)
    val browseError = _browseError.asStateFlow()

    private val _isBrowsing = MutableStateFlow(false)
    val isBrowsing = _isBrowsing.asStateFlow()

    private val _isTesting = MutableStateFlow(false)
    val isTesting = _isTesting.asStateFlow()

    private val _testResult = MutableStateFlow<RelationTestResult?>(null)
    val testResult = _testResult.asStateFlow()

    private val _storageInfo = MutableStateFlow<StorageInfo?>(null)
    val storageInfo: StateFlow<StorageInfo?> = _storageInfo.asStateFlow()

    init {
        loadSources()
        if (relationId > 0) loadRelation()
        loadStorageInfo()
    }

    private fun loadSources() {
        viewModelScope.launch {
            _availableSources.value = syncSourceRepository.getAllWithCredentials()
        }
    }

    private fun loadStorageInfo() {
        _storageInfo.value = StorageInfo(
            typeLabel = context.getString(R.string.storage_current_internal),
            pathLabel = context.filesDir.absolutePath + "/userdata"
        )
    }

    private fun loadRelation() {
        viewModelScope.launch {
            val relation = syncRelationRepository.getById(relationId) ?: return@launch
            _name.value = relation.name
            _selectedSourceId.value = relation.syncSourceId
            _remotePath.value = relation.remotePath
            _localBasePath.value = relation.localBasePath
            _currentRemoteBrowsePath.value = relation.remotePath
        }
    }

    fun setName(value: String) { _name.value = value }
    fun setRemotePath(value: String) { _remotePath.value = value }
    fun setLocalBasePath(value: String) { _localBasePath.value = value }
    fun selectSource(id: Long) { _selectedSourceId.value = id }

    // Local base path is a pure logical path string (encrypted volume has no browsable plaintext dirs).
    // Directory materializes on first sync under MediaFileStore.

    // --- Remote directory browsing ---

    fun browseRemote() {
        viewModelScope.launch {
            val sourceId = _selectedSourceId.value
            val source = _availableSources.value.find { it.id == sourceId }
            if (source == null) {
                _error.value = context.getString(R.string.relation_select_source_first)
                return@launch
            }
            _error.value = null
            _browseError.value = null
            _isBrowsing.value = true
            val client = remoteFileClientFactory.create(source.protocol)
            try {
                client.connect(source, _currentRemoteBrowsePath.value)
                _remoteDirectories.value = client.listDirectories(_currentRemoteBrowsePath.value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _browseError.value = classifyRemoteError(e)
                _remoteDirectories.value = emptyList()
            } finally {
                _isBrowsing.value = false
                try {
                    client.disconnect()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
        }
    }

    fun clearBrowseError() {
        _browseError.value = null
    }

    fun testRemotePath() {
        viewModelScope.launch {
            val sourceId = _selectedSourceId.value
            val source = _availableSources.value.find { it.id == sourceId }
            if (source == null) {
                _testResult.value = RelationTestResult.PreconditionFailed(
                    RelationTestResult.PreconditionFailed.Reason.NO_SOURCE
                )
                return@launch
            }
            val path = _remotePath.value.trim()
            if (path.isBlank()) {
                _testResult.value = RelationTestResult.PreconditionFailed(
                    RelationTestResult.PreconditionFailed.Reason.NO_PATH
                )
                return@launch
            }
            _isTesting.value = true
            _testResult.value = null
            val client = remoteFileClientFactory.create(source.protocol)
            try {
                client.connect(source, path)
                client.listDirectories(path)
                _testResult.value = RelationTestResult.DirAccessible
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // F09: 只携带结构化码，展示由 Screen 映射。对非 SyncException 的回退
                // 分类镜像 syncErrorMessage（SocketTimeout/UnknownHost→超时族、
                // ConnectException→CONNECTION_REFUSED、其余→UNKNOWN）。
                _testResult.value = RelationTestResult.AccessFailed(
                    (e as? SyncException)?.code ?: when (e) {
                        is SocketTimeoutException -> SyncErrorCode.TIMEOUT
                        is UnknownHostException -> SyncErrorCode.UNKNOWN_HOST
                        is ConnectException -> SyncErrorCode.CONNECTION_REFUSED
                        else -> SyncErrorCode.UNKNOWN
                    }
                )
            } finally {
                _isTesting.value = false
                try {
                    client.disconnect()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
        }
    }

    fun navigateRemoteUp() {
        val current = _currentRemoteBrowsePath.value
        if (current.isEmpty() || current == "/") return
        val lastSlash = current.trimEnd('/').lastIndexOf('/')
        _currentRemoteBrowsePath.value = if (lastSlash > 0) current.substring(0, lastSlash) else "/"
        browseRemote()
    }

    fun navigateRemoteInto(dirName: String) {
        val base = _currentRemoteBrowsePath.value.trimEnd('/')
        _currentRemoteBrowsePath.value = if (base == "/") "/$dirName" else "$base/$dirName"
        browseRemote()
    }

    fun selectRemotePath() {
        _remotePath.value = _currentRemoteBrowsePath.value
    }

    // --- Save ---

    fun save() {
        viewModelScope.launch {
            _error.value = null
            _saveResult.value = null
            // L4-1: capture the protocol-relevant fields as ONE snapshot before the first
            // suspension point — validation, normalization and the persisted relation must
            // all derive from the same instant of input, so a source/path switch during the
            // suspension window can never store e.g. an SMB-normalized path on an SFTP source.
            val sourceId = _selectedSourceId.value
            val source = _availableSources.value.find { it.id == sourceId }
            val rawRemotePath = _remotePath.value
            if (_name.value.isBlank() || sourceId == 0L ||
                rawRemotePath.isBlank() || _localBasePath.value.isBlank()
            ) {
                _error.value = context.getString(R.string.relation_complete_fields)
                return@launch
            }
            if (source == null) {
                // Unknown protocol (source not loaded / stale id): fail closed. Persisting a
                // path normalized under an ASSUMED protocol could corrupt the anchoring
                // semantics of the other protocol.
                _error.value = context.getString(R.string.relation_invalid_remote_path)
                return@launch
            }
            val normalizedRemotePath = normalizeRemotePath(rawRemotePath, source.protocol)
            if (normalizedRemotePath == null) {
                _error.value = context.getString(R.string.relation_invalid_remote_path)
                return@launch
            }
            val normalizedBasePath = try {
                withContext(ioDispatcher) {
                    storagePaths.normalizeAndValidateRelativePath(_localBasePath.value)
                }
            } catch (e: SecurityException) {
                _error.value = context.getString(R.string.relation_invalid_base_path)
                return@launch
            }
            val conflicts = syncRelationRepository.checkBasePathConflict(
                normalizedBasePath, if (relationId > 0) relationId else 0
            )
            if (conflicts.isNotEmpty()) {
                _error.value = context.getString(R.string.relation_base_dir_conflict)
                return@launch
            }
            _isSaving.value = true
            try {
                syncRelationRepository.save(
                    SyncRelation(
                        id = if (relationId > 0) relationId else 0L,
                        name = _name.value.trim(),
                        syncSourceId = sourceId,
                        remotePath = normalizedRemotePath,
                        localBasePath = normalizedBasePath
                    )
                )
                _saveResult.value = RelationSaveResult.Saved
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = context.getString(R.string.relation_save_failed, syncErrorMessage(e, context))
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun deleteRelation() {
        viewModelScope.launch {
            if (relationId <= 0) return@launch
            _isSaving.value = true
            _saveResult.value = null
            try {
                syncRelationRepository.delete(relationId)
                _saveResult.value = RelationSaveResult.Deleted
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _saveResult.value = RelationSaveResult.DeleteFailed
            } finally {
                _isSaving.value = false
            }
        }
    }
}
