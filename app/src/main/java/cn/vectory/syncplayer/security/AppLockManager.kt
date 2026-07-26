package cn.vectory.syncplayer.security

import android.content.Context
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import cn.vectory.syncplayer.data.repository.UserPreferencesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppLockManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userPrefs: UserPreferencesRepository
) : LifecycleEventObserver {

    private val _isLocked = MutableStateFlow(false)
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    @Volatile private var cachedSecureLockEnabled = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var hasBeenInBackground = false
    private var isFirstStart = true

    fun lock() {
        _isLocked.value = true
    }

    fun unlock() {
        _isLocked.value = false
    }

    fun register() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        scope.launch {
            userPrefs.secureLockEnabled.collect { cachedSecureLockEnabled = it }
        }
    }

    override fun onStateChanged(source: LifecycleOwner, event: androidx.lifecycle.Lifecycle.Event) {
        when (event) {
            // 刻意使用 ON_STOP 而非 ON_PAUSE：ProcessLifecycleOwner 的 ON_PAUSE 在 resumedCounter
            // 归零后延迟分派，而生物识别弹窗在部分 OEM 上会让 Activity 走 onPause。若在 ON_PAUSE
            // 置锁，存在「认证成功解锁 → 延迟的 ON_PAUSE 分派 → 立刻重锁 → 再次弹认证」的锁死窗口。
            // 切换应用瞬间的内容泄漏（截屏/录屏/最近任务缩略图）已由 MainActivity 常开的
            // FLAG_SECURE 覆盖，无需靠提前置锁来兜。
            androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                hasBeenInBackground = true
            }
            androidx.lifecycle.Lifecycle.Event.ON_START -> {
                if (hasBeenInBackground || isFirstStart) {
                    isFirstStart = false
                    if (cachedSecureLockEnabled) {
                        _isLocked.value = true
                    }
                }
            }
            else -> {}
        }
    }
}
