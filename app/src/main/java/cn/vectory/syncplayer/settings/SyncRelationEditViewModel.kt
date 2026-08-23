package cn.vectory.syncplayer.settings

import android.content.Context
import android.provider.DocumentsContract
import androidx.core.net.toUri
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
import cn.vectory.syncplayer.data.repository.UserPreferencesRepository
import cn.vectory.syncplayer.di.IoDispatcher
import cn.vectory.syncplayer.storage.StoragePaths
import cn.vectory.syncplayer.sync.RemoteFileClientFactory
import cn.vectory.syncplayer.sync.classifyRemoteError
import cn.vectory.syncplayer.sync.RemoteBrowseError
import cn.vectory.syncplayer.sync.syncErrorMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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

@HiltViewModel
class SyncRelationEditViewModel @Inject constructor(
    private val syncRelationRepository: SyncRelationRepository,
    private val syncSourceRepository: SyncSourceRepository,
    private val remoteFileClientFactory: RemoteFileClientFactory,
    private val storagePaths: StoragePaths,
    private val userPreferencesRepository: UserPreferencesRepository,
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

    private val _saveResult = MutableStateFlow<String?>(null)
    val saveResult = _saveResult.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _browseError = MutableStateFlow<RemoteBrowseError?>(null)
    val browseError = _browseError.asStateFlow()

    private val _isBrowsing = MutableStateFlow(false)
    val isBrowsing = _isBrowsing.asStateFlow()

    private val _isTesting = MutableStateFlow(false)
    val isTesting = _isTesting.asStateFlow()

    private val _testResult = MutableStateFlow<String?>(null)
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
        viewModelScope.launch {
            val treeUri = userPreferencesRepository.getMediaStorageTreeUri()
            val info = if (treeUri != null) {
                StorageInfo(
                    typeLabel = context.getString(R.string.storage_method_external),
                    pathLabel = extractDisplayName(treeUri)
                )
            } else {
                StorageInfo(
                    typeLabel = context.getString(R.string.storage_current_internal),
                    pathLabel = context.filesDir.absolutePath + "/userdata"
                )
            }
            _storageInfo.value = info
        }
    }

    /** 尽量解析 SAF tree 目录显示名；失败则回退到 docId 末段或原 URI 字符串。 */
    private fun extractDisplayName(treeUriStr: String): String {
        return try {
            val uri = treeUriStr.toUri()
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val docUri = DocumentsContract.buildDocumentUriUsingTree(uri, docId)
            context.contentResolver.query(
                docUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)?.takeIf { it.isNotBlank() }
                } else {
                    null
                }
            } ?: docId.substringAfterLast(':').substringAfterLast('/').ifBlank { docId }
        } catch (_: Exception) {
            treeUriStr
        }
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
    // Directory materializes on first sync under EncryptedMediaStore.

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
                _testResult.value = context.getString(R.string.relation_select_source_first)
                return@launch
            }
            val path = _remotePath.value.trim()
            if (path.isBlank()) {
                _testResult.value = context.getString(R.string.relation_input_remote_first)
                return@launch
            }
            _isTesting.value = true
            _testResult.value = null
            val client = remoteFileClientFactory.create(source.protocol)
            try {
                client.connect(source, path)
                client.listDirectories(path)
                _testResult.value = context.getString(R.string.relation_dir_accessible)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _testResult.value = when (classifyRemoteError(e)) {
                    RemoteBrowseError.PATH_NOT_FOUND -> context.getString(R.string.relation_dir_not_exist)
                    RemoteBrowseError.TIMEOUT -> context.getString(R.string.relation_timeout_check_network)
                    RemoteBrowseError.OTHER -> context.getString(R.string.relation_access_failed, syncErrorMessage(e, context))
                }
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
            if (_name.value.isBlank() || _selectedSourceId.value == 0L ||
                _remotePath.value.isBlank() || _localBasePath.value.isBlank()
            ) {
                _error.value = context.getString(R.string.relation_complete_fields)
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
                        syncSourceId = _selectedSourceId.value,
                        remotePath = _remotePath.value.trim(),
                        localBasePath = normalizedBasePath
                    )
                )
                _saveResult.value = context.getString(R.string.relation_save_success)
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
            syncRelationRepository.delete(relationId)
            _saveResult.value = context.getString(R.string.relation_delete_success)
        }
    }
}
