package cn.vectory.syncplayer.settings

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.vectory.syncplayer.R
import cn.vectory.syncplayer.data.repository.SyncLogRepository
import cn.vectory.syncplayer.data.local.entity.SyncLogEntity
import cn.vectory.syncplayer.data.model.OrientationPreference
import cn.vectory.syncplayer.data.repository.UserPreferencesRepository
import cn.vectory.syncplayer.security.AuthCapabilityInfo
import cn.vectory.syncplayer.security.BiometricAuthManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.fragment.app.FragmentActivity
import java.io.IOException

data class SettingsState(
    val volumeBalance: Boolean = false,
    val quickRecycle: Boolean = true,
    val secureLockEnabled: Boolean = false,
    val followSystemOrientation: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val syncLogRepository: SyncLogRepository,
    private val userPrefs: UserPreferencesRepository,
    private val biometricAuthManager: BiometricAuthManager,
) : ViewModel() {

    private val _settingsState = MutableStateFlow<SettingsState?>(null)
    val settingsState: StateFlow<SettingsState?> = _settingsState.asStateFlow()

    private val _authCapabilityInfo = MutableStateFlow(biometricAuthManager.getAuthCapabilityInfo())
    val authCapabilityInfo: StateFlow<AuthCapabilityInfo> = _authCapabilityInfo.asStateFlow()

    fun refreshAuthCapability() {
        _authCapabilityInfo.value = biometricAuthManager.getAuthCapabilityInfo()
    }

    val syncLogs: StateFlow<List<SyncLogEntity>> = syncLogRepository.getRecentFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            try {
                combine(
                    userPrefs.volumeBalance,
                    userPrefs.quickRecycle,
                    userPrefs.secureLockEnabled,
                    userPrefs.orientation,
                ) { volumeBalance, quickRecycle, secureLockEnabled, orientation ->
                    SettingsState(
                        volumeBalance = volumeBalance,
                        quickRecycle = quickRecycle,
                        secureLockEnabled = secureLockEnabled,
                        followSystemOrientation = orientation == OrientationPreference.FOLLOW_SYSTEM,
                    )
                }
                .retryWhen { cause, attempt ->
                    if (cause !is IOException) return@retryWhen false
                    // BUG-003：DataStore 读 IO 故障是文档化的瞬态面；退避后重订阅
                    // 全部偏好流，防止单次 IOException 永久冻结设置状态。
                    // CancellationException 非 IOException → 原样传播（红线 3）。
                    delay(ioRetryBackoffMs(attempt))
                    true
                }
                .collect { base ->
                    _settingsState.value = base
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load settings", e)
            }
        }
    }

    fun setVolumeBalance(enabled: Boolean) {
        viewModelScope.launch {
            try {
                userPrefs.setVolumeBalance(enabled)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set volume balance", e)
            }
        }
    }

    fun setQuickRecycle(enabled: Boolean) {
        viewModelScope.launch {
            try {
                userPrefs.setQuickRecycle(enabled)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set quick recycle", e)
            }
        }
    }

    // AND-089: OrientationPreference existed as repo CRUD + spec but had no
    // consumer — wire it to a real settings toggle consumed by the player.
    fun setFollowSystemOrientation(enabled: Boolean) {
        viewModelScope.launch {
            try {
                userPrefs.setOrientation(
                    if (enabled) OrientationPreference.FOLLOW_SYSTEM else OrientationPreference.FOLLOW_VIDEO
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set orientation preference", e)
            }
        }
    }

    fun setSecureLockEnabled(enabled: Boolean) {
        viewModelScope.launch {
            try {
                userPrefs.setSecureLockEnabled(enabled)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set secure lock", e)
            }
        }
    }

    fun authenticateAndToggle(activity: FragmentActivity, enable: Boolean) {
        biometricAuthManager.authenticate(
            activity = activity,
            title = context.getString(
                if (enable) R.string.biometric_title_enable_lock else R.string.biometric_title_disable_lock
            ),
            subtitle = context.getString(
                if (enable) R.string.biometric_subtitle_enable_lock else R.string.biometric_subtitle_disable_lock
            ),
            onSuccess = { setSecureLockEnabled(enable) },
            onError = { /* no-op on error */ }
        )
    }

    fun clearLogs() {
        viewModelScope.launch {
            try {
                syncLogRepository.clearOldLogs()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear logs", e)
            }
        }
    }

    /** Re-enable the first-run onboarding card on the browser root. */
    fun resetOnboardingDismissed() {
        viewModelScope.launch {
            try {
                userPrefs.setOnboardingDismissed(false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reset onboarding dismissal", e)
            }
        }
    }

    companion object {
        private const val TAG = "SettingsViewModel"
        private const val SETTINGS_RETRY_INITIAL_MS = 1_000L
        private const val SETTINGS_RETRY_MAX_MS = 30_000L

        /** 指数退避：1s,2s,4s,8s,16s,30s(封顶)。attempt 先收敛到 [0,5] 再转 Int。 */
        internal fun ioRetryBackoffMs(attempt: Long): Long =
            minOf(SETTINGS_RETRY_MAX_MS, SETTINGS_RETRY_INITIAL_MS shl attempt.coerceIn(0L, 5L).toInt())
    }
}
