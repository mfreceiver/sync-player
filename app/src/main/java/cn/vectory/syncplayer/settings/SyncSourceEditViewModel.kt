package cn.vectory.syncplayer.settings

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.vectory.syncplayer.R
import cn.vectory.syncplayer.data.model.SyncProtocol
import cn.vectory.syncplayer.data.model.SyncSource
import cn.vectory.syncplayer.data.repository.SyncSourceRepository
import cn.vectory.syncplayer.sync.RemoteFileClientFactory
import cn.vectory.syncplayer.sync.syncErrorMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SyncSourceEditViewModel @Inject constructor(
    private val syncSourceRepository: SyncSourceRepository,
    private val remoteFileClientFactory: RemoteFileClientFactory,
    @ApplicationContext private val context: Context,
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
    private val _isTesting = MutableStateFlow(false)
    val isTesting: StateFlow<Boolean> = _isTesting.asStateFlow()

    private val _testResult = MutableStateFlow<String?>(null)
    val testResult: StateFlow<String?> = _testResult.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _saveResult = MutableStateFlow<String?>(null)
    val saveResult: StateFlow<String?> = _saveResult.asStateFlow()

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
            _isTesting.value = true
            _testResult.value = null
            try {
                val source = buildSource()
                val client = remoteFileClientFactory.create(source.protocol)
                client.testConnection(source, "/")
                _testResult.value = context.getString(R.string.source_connect_success)
            } catch (e: Exception) {
                _testResult.value = context.getString(R.string.source_connect_failed, syncErrorMessage(e, context))
            } finally {
                _isTesting.value = false
            }
        }
    }

    fun save() {
        viewModelScope.launch {
            _isSaving.value = true
            _saveResult.value = null
            try {
                syncSourceRepository.save(buildSource())
                _saveResult.value = context.getString(R.string.source_save_success)
            } catch (e: Exception) {
                _saveResult.value = context.getString(R.string.source_save_failed, syncErrorMessage(e, context))
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun deleteSource() {
        viewModelScope.launch {
            _isSaving.value = true
            _saveResult.value = null
            try {
                val count = syncSourceRepository.getRelationCount(sourceId)
                if (count > 0) {
                    _saveResult.value = context.getString(R.string.source_has_relations, count)
                } else {
                    syncSourceRepository.delete(sourceId)
                    _saveResult.value = context.getString(R.string.source_delete_success)
                }
            } catch (e: Exception) {
                _saveResult.value = context.getString(R.string.source_delete_failed, syncErrorMessage(e, context))
            } finally {
                _isSaving.value = false
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
