package cn.vectory.syncplayer.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.vectory.syncplayer.data.model.SyncProtocol
import cn.vectory.syncplayer.data.model.SyncSource
import cn.vectory.syncplayer.data.repository.SyncSourceRepository
import cn.vectory.syncplayer.sync.smb.SmbFileClient
import cn.vectory.syncplayer.sync.sftp.SftpFileClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SyncSourceEditViewModel @Inject constructor(
    private val syncSourceRepository: SyncSourceRepository,
    private val smbFileClient: SmbFileClient,
    private val sftpFileClient: SftpFileClient,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val sourceId: Long = savedStateHandle.get<Long>("sourceId") ?: 0L

    private val _name = MutableStateFlow("")
    val name: StateFlow<String> = _name.asStateFlow()
    fun setName(value: String) { _name.value = value }

    private val _protocol = MutableStateFlow(SyncProtocol.SMB)
    val protocol: StateFlow<SyncProtocol> = _protocol.asStateFlow()
    fun setProtocol(p: SyncProtocol) {
        _protocol.value = p
        _port.value = if (p == SyncProtocol.SMB) "445" else "22"
    }

    private val _host = MutableStateFlow("")
    val host: StateFlow<String> = _host.asStateFlow()
    fun setHost(value: String) { _host.value = value }

    private val _port = MutableStateFlow("445")
    val port: StateFlow<String> = _port.asStateFlow()
    fun setPort(value: String) { _port.value = value }

    private val _username = MutableStateFlow("")
    val username: StateFlow<String> = _username.asStateFlow()
    fun setUsername(value: String) { _username.value = value }

    private val _password = MutableStateFlow("")
    val password: StateFlow<String> = _password.asStateFlow()
    fun setPassword(value: String) { _password.value = value }
    val isTesting = MutableStateFlow(false)
    val testResult = MutableStateFlow<String?>(null)
    val isSaving = MutableStateFlow(false)
    val saveResult = MutableStateFlow<String?>(null)

    init {
        if (sourceId > 0L) {
            viewModelScope.launch {
                val existing = syncSourceRepository.getById(sourceId)
                if (existing != null) {
                    _name.value = existing.name
                    _protocol.value = existing.protocol
                    _host.value = existing.host
                    _port.value = existing.port.toString()
                    _username.value = existing.username
                    _password.value = existing.password
                }
            }
        }
    }

    fun testConnection() {
        viewModelScope.launch {
            isTesting.value = true
            testResult.value = null
            try {
                val source = buildSource()
                val client = when (source.protocol) {
                    SyncProtocol.SMB -> smbFileClient
                    SyncProtocol.SFTP -> sftpFileClient
                }
                client.testConnection(source, "/")
                testResult.value = "连接成功"
            } catch (e: Exception) {
                testResult.value = "连接失败: ${e.message}"
            } finally {
                isTesting.value = false
            }
        }
    }

    fun save() {
        viewModelScope.launch {
            isSaving.value = true
            saveResult.value = null
            try {
                syncSourceRepository.save(buildSource())
                saveResult.value = "保存成功"
            } catch (e: Exception) {
                saveResult.value = "保存失败: ${e.message}"
            } finally {
                isSaving.value = false
            }
        }
    }

    fun deleteSource() {
        viewModelScope.launch {
            isSaving.value = true
            saveResult.value = null
            try {
                val count = syncSourceRepository.getRelationCount(sourceId)
                if (count > 0) {
                    saveResult.value = "该数据源仍有 $count 个同步关系，请先删除所有同步关系"
                } else {
                    syncSourceRepository.delete(sourceId)
                    saveResult.value = "删除成功"
                }
            } catch (e: Exception) {
                saveResult.value = "删除失败: ${e.message}"
            } finally {
                isSaving.value = false
            }
        }
    }

    private fun buildSource() = SyncSource(
        id = if (sourceId > 0L) sourceId else 0L,
        name = _name.value.trim(),
        protocol = _protocol.value,
        host = _host.value.trim(),
        port = _port.value.toIntOrNull() ?: if (_protocol.value == SyncProtocol.SMB) 445 else 22,
        username = _username.value.trim(),
        password = _password.value
    )
}
