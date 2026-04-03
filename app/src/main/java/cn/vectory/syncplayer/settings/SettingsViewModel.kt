package cn.vectory.syncplayer.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.vectory.syncplayer.data.model.ServerConfig
import cn.vectory.syncplayer.data.model.SyncProtocol
import cn.vectory.syncplayer.data.repository.ServerConfigRepository
import cn.vectory.syncplayer.data.repository.SyncLogRepository
import cn.vectory.syncplayer.data.local.entity.SyncLogEntity
import cn.vectory.syncplayer.sync.smb.SmbFileClient
import cn.vectory.syncplayer.sync.sftp.SftpFileClient
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val serverConfigRepository: ServerConfigRepository,
    private val smbFileClient: SmbFileClient,
    private val sftpFileClient: SftpFileClient,
    private val syncLogRepository: SyncLogRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _protocol = MutableStateFlow(SyncProtocol.SMB)
    val protocol: StateFlow<SyncProtocol> = _protocol.asStateFlow()

    private val _host = MutableStateFlow("")
    val host: StateFlow<String> = _host.asStateFlow()

    private val _port = MutableStateFlow("445")
    val port: StateFlow<String> = _port.asStateFlow()

    private val _username = MutableStateFlow("")
    val username: StateFlow<String> = _username.asStateFlow()

    private val _password = MutableStateFlow("")
    val password: StateFlow<String> = _password.asStateFlow()

    private val _remotePath = MutableStateFlow("")
    val remotePath: StateFlow<String> = _remotePath.asStateFlow()

    private val _longPressSpeed = MutableStateFlow(2.0f)
    val longPressSpeed: StateFlow<Float> = _longPressSpeed.asStateFlow()

    private val _isTesting = MutableStateFlow(false)
    val isTesting: StateFlow<Boolean> = _isTesting.asStateFlow()

    private val _testResult = MutableStateFlow<String?>(null)
    val testResult: StateFlow<String?> = _testResult.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _saveResult = MutableStateFlow<String?>(null)
    val saveResult: StateFlow<String?> = _saveResult.asStateFlow()

    private val _lastSyncTime = MutableStateFlow<String?>(null)
    val lastSyncTime: StateFlow<String?> = _lastSyncTime.asStateFlow()

    val syncLogs: StateFlow<List<SyncLogEntity>> = syncLogRepository.getRecentFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        loadConfig()
        val prefs = context.getSharedPreferences("player_prefs", Context.MODE_PRIVATE)
        _longPressSpeed.value = prefs.getFloat("long_press_speed", 2.0f)
    }

    fun loadConfig() {
        viewModelScope.launch {
            val config = serverConfigRepository.getConfig()
            if (config != null) {
                _protocol.value = config.protocol
                _host.value = config.host
                _port.value = config.port.toString()
                _username.value = config.username
                _password.value = config.password
                _remotePath.value = config.remotePath
                _lastSyncTime.value = config.lastSyncTime?.let {
                    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                        .format(it)
                }
            }
        }
    }

    fun setProtocol(p: SyncProtocol) {
        _protocol.value = p
        _port.value = if (p == SyncProtocol.SMB) "445" else "22"
    }

    fun setHost(v: String) { _host.value = v }
    fun setPort(v: String) { _port.value = v }
    fun setUsername(v: String) { _username.value = v }
    fun setPassword(v: String) { _password.value = v }
    fun setRemotePath(v: String) { _remotePath.value = v }

    fun setLongPressSpeed(speed: Float) {
        _longPressSpeed.value = speed
        val prefs = context.getSharedPreferences("player_prefs", Context.MODE_PRIVATE)
        prefs.edit().putFloat("long_press_speed", speed).apply()
    }

    fun testConnection() {
        viewModelScope.launch {
            _isTesting.value = true
            _testResult.value = null
            try {
                val config = buildConfig()
                val client = when (config.protocol) {
                    SyncProtocol.SMB -> smbFileClient
                    SyncProtocol.SFTP -> sftpFileClient
                }
                val success = client.testConnection(config)
                _testResult.value = if (success) "连接成功" else "连接失败"
            } catch (e: Exception) {
                _testResult.value = "连接失败: ${e.message}"
            } finally {
                _isTesting.value = false
            }
        }
    }

    fun saveConfig() {
        viewModelScope.launch {
            _isSaving.value = true
            _saveResult.value = null
            try {
                val config = buildConfig()
                serverConfigRepository.saveConfig(config)
                _saveResult.value = "保存成功"
            } catch (e: Exception) {
                _saveResult.value = "保存失败: ${e.message}"
            } finally {
                _isSaving.value = false
            }
        }
    }

    private fun buildConfig(): ServerConfig {
        return ServerConfig(
            protocol = _protocol.value,
            host = _host.value.trim(),
            port = _port.value.toIntOrNull() ?: 0,
            username = _username.value.trim(),
            password = _password.value,
            remotePath = _remotePath.value.trim()
        )
    }

    fun clearLogs() {
        viewModelScope.launch {
            syncLogRepository.clearOldLogs()
        }
    }
}
