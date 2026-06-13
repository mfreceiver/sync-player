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
import cn.vectory.syncplayer.storage.StoragePaths
import cn.vectory.syncplayer.sync.RemoteFileClientFactory
import cn.vectory.syncplayer.sync.classifyRemoteError
import cn.vectory.syncplayer.sync.RemoteBrowseError
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class SyncRelationEditViewModel @Inject constructor(
    private val syncRelationRepository: SyncRelationRepository,
    private val syncSourceRepository: SyncSourceRepository,
    private val remoteFileClientFactory: RemoteFileClientFactory,
    private val storagePaths: StoragePaths,
    @ApplicationContext private val context: Context,
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

    private val _currentLocalBrowsePath = MutableStateFlow("")
    val currentLocalBrowsePath = _currentLocalBrowsePath.asStateFlow()

    private val _localDirectories = MutableStateFlow<List<String>>(emptyList())
    val localDirectories = _localDirectories.asStateFlow()

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

    init {
        loadSources()
        if (relationId > 0) loadRelation()
    }

    private fun loadSources() {
        viewModelScope.launch {
            _availableSources.value = syncSourceRepository.getAllWithCredentials()
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
    fun selectSource(id: Long) { _selectedSourceId.value = id }

    // --- Local directory browsing (multi-level) ---

    fun browseLocalDirectories() {
        val baseDir = if (_currentLocalBrowsePath.value.isEmpty()) {
            storagePaths.userdataRoot
        } else {
            File(storagePaths.userdataRoot, _currentLocalBrowsePath.value)
        }
        _localDirectories.value = baseDir.listFiles()
            ?.filter { it.isDirectory }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()
    }

    fun navigateLocalInto(dirName: String) {
        val current = _currentLocalBrowsePath.value
        _currentLocalBrowsePath.value = if (current.isEmpty()) dirName else "$current/$dirName"
        browseLocalDirectories()
    }

    fun navigateLocalUp() {
        val current = _currentLocalBrowsePath.value
        if (current.isEmpty()) return
        val lastSlash = current.lastIndexOf('/')
        _currentLocalBrowsePath.value = if (lastSlash > 0) current.substring(0, lastSlash) else ""
        browseLocalDirectories()
    }

    fun confirmLocalSelection() {
        _localBasePath.value = _currentLocalBrowsePath.value
    }

    fun createLocalFolder(name: String) {
        val baseDir = if (_currentLocalBrowsePath.value.isEmpty()) {
            storagePaths.userdataRoot
        } else {
            File(storagePaths.userdataRoot, _currentLocalBrowsePath.value)
        }
        val newDir = File(baseDir, name)
        if (!newDir.exists()) newDir.mkdirs()
        browseLocalDirectories()
    }

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
            } catch (e: Exception) {
                _browseError.value = classifyRemoteError(e)
                _remoteDirectories.value = emptyList()
            } finally {
                _isBrowsing.value = false
                try { client.disconnect() } catch (_: Exception) {}
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
            } catch (e: Exception) {
                _testResult.value = when (classifyRemoteError(e)) {
                    RemoteBrowseError.PATH_NOT_FOUND -> context.getString(R.string.relation_dir_not_exist)
                    RemoteBrowseError.TIMEOUT -> context.getString(R.string.relation_timeout_check_network)
                    RemoteBrowseError.OTHER -> context.getString(R.string.relation_access_failed, e.message)
                }
            } finally {
                _isTesting.value = false
                try { client.disconnect() } catch (_: Exception) {}
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
            val conflicts = syncRelationRepository.checkBasePathConflict(
                _localBasePath.value, if (relationId > 0) relationId else 0
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
                        localBasePath = _localBasePath.value.trim()
                    )
                )
                _saveResult.value = context.getString(R.string.relation_save_success)
            } catch (e: Exception) {
                _error.value = context.getString(R.string.relation_save_failed, e.message)
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
