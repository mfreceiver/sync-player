package cn.vectory.syncplayer.settings

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.vectory.syncplayer.R
import cn.vectory.syncplayer.data.repository.SyncLogRepository
import cn.vectory.syncplayer.data.local.entity.SyncLogEntity
import cn.vectory.syncplayer.data.repository.UserPreferencesRepository
import cn.vectory.syncplayer.di.IoDispatcher
import cn.vectory.syncplayer.security.AuthCapabilityInfo
import cn.vectory.syncplayer.security.BiometricAuthManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import androidx.fragment.app.FragmentActivity
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

data class SettingsState(
    val volumeBalance: Boolean = false,
    val quickRecycle: Boolean = true,
    val secureLockEnabled: Boolean = false,
    /** null = 应用内部默认卷；非 null = SAF tree URI 字符串 */
    val mediaStorageTreeUri: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val syncLogRepository: SyncLogRepository,
    private val userPrefs: UserPreferencesRepository,
    private val biometricAuthManager: BiometricAuthManager,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _settingsState = MutableStateFlow<SettingsState?>(null)
    val settingsState: StateFlow<SettingsState?> = _settingsState.asStateFlow()

    /**
     * F04：媒体存储位置显示名的状态源。URI 变化（或进入设置页首读）时在
     * [ioDispatcher] 上异步解析 SAF tree 显示名——组合期零阻塞，Screen 只读本状态。
     * null = 解析中/解析失败（Screen 回退固定资源通用文案，绝不回退 URI 派生文本，
     * 即不走 fallbackUriLabel）。latest-wins：新 URI 请求即刻清空为占位并领取代次，
     * 慢旧请求的结果按代次丢弃。
     */
    private val _storageLocationLabel = MutableStateFlow<String?>(null)
    val storageLocationLabel: StateFlow<String?> = _storageLocationLabel.asStateFlow()

    /** 最近一次发起解析的 URI（去重，避免其他偏好变化触发的重发射重复解析）。 */
    private var requestedLocationUri: String? = null

    /** 解析代次：发布前比对，旧请求失配即丢弃（同 refreshEpoch 协议）。 */
    private val locationResolveEpoch = AtomicLong(0)

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
                    maybeResolveStorageLocation(base.mediaStorageTreeUri)
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

    /**
     * F04：URI 变化（或进入设置页首读）时在 [ioDispatcher] 上异步解析显示名。
     * 同 URI 重发射不重复解析；null URI 直接清空；新请求即刻置占位（null）；
     * 发布前比对代次——慢旧请求不得覆盖新 URI 状态（latest-wins）。
     */
    private fun maybeResolveStorageLocation(treeUri: String?) {
        if (treeUri == requestedLocationUri) return
        requestedLocationUri = treeUri
        val epoch = locationResolveEpoch.incrementAndGet()   // 领取代次：旧解析发布权即时失效
        _storageLocationLabel.value = null                   // 新请求进入占位态，旧 URI 残留显示一并清空
        if (treeUri == null) return
        viewModelScope.launch {
            val resolved = withContext(ioDispatcher) {
                try {
                    StorageManagementViewModel.resolveTreeDisplayNameOrNull(context, treeUri)
                } catch (e: CancellationException) {
                    throw e                                  // 红线 3：CE 始终先传播
                } catch (_: Exception) {
                    null                                     // 失败/无权限 → null 哨兵（UI 回退固定资源文案）
                }
            }
            if (locationResolveEpoch.get() != epoch) return@launch   // latest-wins：乱序守卫
            _storageLocationLabel.value = resolved
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
