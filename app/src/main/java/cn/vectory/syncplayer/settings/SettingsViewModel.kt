package cn.vectory.syncplayer.settings

import android.content.Context
import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
// BrowserViewModel import removed as it's no longer used in Settings screen
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

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val syncLogRepository: SyncLogRepository,
    private val userPrefs: UserPreferencesRepository,
    @ApplicationContext private val context: Context,
    private val biometricAuthManager: BiometricAuthManager
) : ViewModel() {

    private val _longPressSpeed = MutableStateFlow(2.0f)
    val longPressSpeed: StateFlow<Float> = _longPressSpeed.asStateFlow()

    // Removed defaultSortMode/defaultFilter/viewMode/gridColumns as settings are deprecated

    private val _volumeBalance = MutableStateFlow(false)
    val volumeBalance: StateFlow<Boolean> = _volumeBalance.asStateFlow()

    enum class DoubleTapAction { TRASH, PLAY_PAUSE }
    private val _doubleTapAction = MutableStateFlow(DoubleTapAction.TRASH)
    val doubleTapAction: StateFlow<DoubleTapAction> = _doubleTapAction.asStateFlow()

    // SAF provider toggle state
    private val _safEnabled = MutableStateFlow(false)
    val safEnabled: StateFlow<Boolean> = _safEnabled.asStateFlow()

    // Secure lock toggle state
    private val _secureLockEnabled = MutableStateFlow(false)
    val secureLockEnabled: StateFlow<Boolean> = _secureLockEnabled.asStateFlow()
    val canAuthenticateBio: Boolean = biometricAuthManager.canAuthenticate()

    val syncLogs: StateFlow<List<SyncLogEntity>> = syncLogRepository.getRecentFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            _longPressSpeed.value = userPrefs.getLongPressSpeed()
            _volumeBalance.value = userPrefs.getVolumeBalance()
            _doubleTapAction.value = try {
                DoubleTapAction.valueOf(userPrefs.getDoubleTapAction())
            } catch (_: Exception) {
                DoubleTapAction.TRASH
            }
            // Load SAF provider state
            _safEnabled.value = userPrefs.getSafProviderEnabled()
            // Load secure lock state
            _secureLockEnabled.value = userPrefs.getSecureLockEnabled()
        }
    }

    fun setLongPressSpeed(speed: Float) {
        _longPressSpeed.value = speed
        viewModelScope.launch { userPrefs.setLongPressSpeed(speed) }
    }

    // Removed: setDefaultSortMode, setDefaultFilter, setViewMode, setGridColumns

    fun setVolumeBalance(enabled: Boolean) {
        _volumeBalance.value = enabled
        viewModelScope.launch { userPrefs.setVolumeBalance(enabled) }
    }

    fun setDoubleTapAction(action: DoubleTapAction) {
        _doubleTapAction.value = action
        viewModelScope.launch { userPrefs.setDoubleTapAction(action.name) }
    }

    // SAF provider toggle state
    fun setSafEnabled(enabled: Boolean) {
        _safEnabled.value = enabled
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

    // Secure lock related actions
    fun setSecureLockEnabled(enabled: Boolean) {
        _secureLockEnabled.value = enabled
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
