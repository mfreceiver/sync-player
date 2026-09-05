package cn.vectory.syncplayer.settings

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.vectory.syncplayer.R
import cn.vectory.syncplayer.data.repository.SyncLogRepository
import cn.vectory.syncplayer.data.local.entity.SyncLogEntity
import cn.vectory.syncplayer.data.repository.UserPreferencesRepository
import cn.vectory.syncplayer.security.AuthCapabilityInfo
import cn.vectory.syncplayer.security.BiometricAuthManager
import cn.vectory.syncplayer.storage.volume.MediaVolumeProvider
import cn.vectory.syncplayer.ui.util.formatFileSize
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import androidx.fragment.app.FragmentActivity
import java.io.IOException

data class SettingsState(
    val volumeBalance: Boolean = false,
    val quickRecycle: Boolean = true,
    val secureLockEnabled: Boolean = false,
    /** null = 应用内部默认卷；非 null = SAF tree URI 字符串 */
    val mediaStorageTreeUri: String? = null,
    /** 可用空间文案，失败时为 "—" */
    val usableSpaceLabel: String = "—",
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val syncLogRepository: SyncLogRepository,
    private val userPrefs: UserPreferencesRepository,
    private val biometricAuthManager: BiometricAuthManager,
    private val mediaVolumeProvider: MediaVolumeProvider,
) : ViewModel() {

    private val _settingsState = MutableStateFlow<SettingsState?>(null)
    val settingsState: StateFlow<SettingsState?> = _settingsState.asStateFlow()

    val authCapabilityInfo: AuthCapabilityInfo = biometricAuthManager.getAuthCapabilityInfo()

    val syncLogs: StateFlow<List<SyncLogEntity>> = syncLogRepository.getRecentFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            try {
                combine(
                    userPrefs.volumeBalance,
                    userPrefs.quickRecycle,
                    userPrefs.secureLockEnabled,
                    userPrefs.mediaStorageTreeUri,
                ) { volumeBalance, quickRecycle, secureLockEnabled, mediaStorageTreeUri ->
                    SettingsState(
                        volumeBalance = volumeBalance,
                        quickRecycle = quickRecycle,
                        secureLockEnabled = secureLockEnabled,
                        mediaStorageTreeUri = mediaStorageTreeUri,
                        usableSpaceLabel = _settingsState.value?.usableSpaceLabel ?: "—",
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
                    refreshUsableSpace()
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

    fun setMediaStorageTreeUri(uri: String?) {
        viewModelScope.launch {
            try {
                userPrefs.setMediaStorageTreeUri(uri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set media storage tree uri", e)
            }
        }
    }

    fun clearMediaStorageTreeUri() {
        setMediaStorageTreeUri(null)
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

    private fun refreshUsableSpace() {
        viewModelScope.launch {
            val label = withContext(Dispatchers.IO) {
                try {
                    val bytes = mediaVolumeProvider.current().usableSpace()
                    if (bytes < 0L) "—" else formatFileSize(bytes)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to read usable space", e)
                    "—"
                }
            }
            _settingsState.update { state ->
                state?.copy(usableSpaceLabel = label)
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
