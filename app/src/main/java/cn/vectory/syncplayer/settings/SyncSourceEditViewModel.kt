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

    val name = MutableStateFlow("")
    val protocol = MutableStateFlow(SyncProtocol.SMB)
    val host = MutableStateFlow("")
    val port = MutableStateFlow("445")
    val username = MutableStateFlow("")
    val password = MutableStateFlow("")
    val isTesting = MutableStateFlow(false)
    val testResult = MutableStateFlow<String?>(null)
    val isSaving = MutableStateFlow(false)
    val saveResult = MutableStateFlow<String?>(null)

    init {
        if (sourceId > 0L) {
            viewModelScope.launch {
                val existing = syncSourceRepository.getById(sourceId)
                if (existing != null) {
                    name.value = existing.name
                    protocol.value = existing.protocol
                    host.value = existing.host
                    port.value = existing.port.toString()
                    username.value = existing.username
                    password.value = existing.password
                }
            }
        }
    }

    fun setProtocol(p: SyncProtocol) {
        protocol.value = p
        port.value = if (p == SyncProtocol.SMB) "445" else "22"
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
        name = name.value.trim(),
        protocol = protocol.value,
        host = host.value.trim(),
        port = port.value.toIntOrNull() ?: if (protocol.value == SyncProtocol.SMB) 445 else 22,
        username = username.value.trim(),
        password = password.value
    )
}
