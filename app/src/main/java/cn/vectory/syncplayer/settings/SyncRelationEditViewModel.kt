package cn.vectory.syncplayer.settings

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.vectory.syncplayer.data.model.RemoteFile
import cn.vectory.syncplayer.data.model.SyncProtocol
import cn.vectory.syncplayer.data.model.SyncRelation
import cn.vectory.syncplayer.data.model.SyncSource
import cn.vectory.syncplayer.data.repository.SyncRelationRepository
import cn.vectory.syncplayer.data.repository.SyncSourceRepository
import cn.vectory.syncplayer.storage.StoragePaths
import cn.vectory.syncplayer.sync.smb.SmbFileClient
import cn.vectory.syncplayer.sync.sftp.SftpFileClient
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
    private val smbFileClient: SmbFileClient,
    private val sftpFileClient: SftpFileClient,
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

    init {
        loadSources()
        if (relationId > 0) loadRelation()
    }

    private fun loadSources() {
        viewModelScope.launch {
            _availableSources.value = syncSourceRepository.getAll()
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
                _error.value = "请先选择数据源"
                return@launch
            }
            _error.value = null
            _browseError.value = null
            try {
                val client = when (source.protocol) {
                    SyncProtocol.SMB -> smbFileClient
                    SyncProtocol.SFTP -> sftpFileClient
                }
                client.connect(source, _currentRemoteBrowsePath.value)
                _remoteDirectories.value = client.listDirectories(_currentRemoteBrowsePath.value)
                client.disconnect()
            } catch (e: Exception) {
                _browseError.value = classifyRemoteError(e)
                try { when (source.protocol) {
                    SyncProtocol.SMB -> smbFileClient.disconnect()
                    SyncProtocol.SFTP -> sftpFileClient.disconnect()
                } } catch (_: Exception) {}
            }
        }
    }

    fun clearBrowseError() {
        _browseError.value = null
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
                _error.value = "请完善所有字段"
                return@launch
            }
            val conflicts = syncRelationRepository.checkBasePathConflict(
                _localBasePath.value, if (relationId > 0) relationId else 0
            )
            if (conflicts.isNotEmpty()) {
                _error.value = "本地基目录与现有同步关系冲突，基目录不能互相嵌套"
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
                _saveResult.value = "保存成功"
            } catch (e: Exception) {
                _error.value = "保存失败: ${e.message}"
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun deleteRelation() {
        viewModelScope.launch {
            if (relationId <= 0) return@launch
            syncRelationRepository.delete(relationId)
        }
    }
}
