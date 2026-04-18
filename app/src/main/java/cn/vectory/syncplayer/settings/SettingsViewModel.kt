package cn.vectory.syncplayer.settings

import android.content.Context
import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.vectory.syncplayer.data.repository.SyncLogRepository
import cn.vectory.syncplayer.data.local.entity.SyncLogEntity
import cn.vectory.syncplayer.data.repository.UserPreferencesRepository
import cn.vectory.syncplayer.provider.SyncDocumentsProvider
import cn.vectory.syncplayer.security.BiometricAuthManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.fragment.app.FragmentActivity

data class SettingsState(
    val volumeBalance: Boolean = false,
    val quickRecycle: Boolean = true,
    val safEnabled: Boolean = false,
    val secureLockEnabled: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val syncLogRepository: SyncLogRepository,
    private val userPrefs: UserPreferencesRepository,
    @ApplicationContext private val context: Context,
    private val biometricAuthManager: BiometricAuthManager
) : ViewModel() {

    private val _settingsState = MutableStateFlow<SettingsState?>(null)
    val settingsState: StateFlow<SettingsState?> = _settingsState.asStateFlow()

    val canAuthenticateBio: Boolean = biometricAuthManager.canAuthenticate()

    val syncLogs: StateFlow<List<SyncLogEntity>> = syncLogRepository.getRecentFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            combine(
                userPrefs.volumeBalance,
                userPrefs.quickRecycle,
                userPrefs.safProviderEnabled,
                userPrefs.secureLockEnabled
            ) { volumeBalance, quickRecycle, safEnabled, secureLockEnabled ->
                SettingsState(volumeBalance, quickRecycle, safEnabled, secureLockEnabled)
            }.collect { _settingsState.value = it }
        }
    }

    fun setVolumeBalance(enabled: Boolean) {
        viewModelScope.launch { userPrefs.setVolumeBalance(enabled) }
    }

    fun setQuickRecycle(enabled: Boolean) {
        viewModelScope.launch { userPrefs.setQuickRecycle(enabled) }
    }

    fun setSafEnabled(enabled: Boolean) {
        val componentName = ComponentName(context, SyncDocumentsProvider::class.java)
        val newState = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        context.packageManager.setComponentEnabledSetting(
            componentName,
            newState,
            PackageManager.DONT_KILL_APP
        )
        viewModelScope.launch { userPrefs.setSafProviderEnabled(enabled) }
    }

    fun setSecureLockEnabled(enabled: Boolean) {
        viewModelScope.launch { userPrefs.setSecureLockEnabled(enabled) }
    }

    fun authenticateAndToggle(activity: FragmentActivity, enable: Boolean) {
        biometricAuthManager.authenticate(
            activity = activity,
            title = if (enable) "启用安全锁" else "关闭安全锁",
            subtitle = "验证身份以${if (enable) "开启" else "关闭"}应用锁",
            onSuccess = { setSecureLockEnabled(enable) },
            onError = { /* no-op on error */ }
        )
    }

    fun clearLogs() {
        viewModelScope.launch {
            syncLogRepository.clearOldLogs()
        }
    }
}
