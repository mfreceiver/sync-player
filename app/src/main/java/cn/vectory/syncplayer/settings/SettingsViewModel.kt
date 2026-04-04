package cn.vectory.syncplayer.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.vectory.syncplayer.data.model.OrientationPreference
import cn.vectory.syncplayer.data.repository.SyncLogRepository
import cn.vectory.syncplayer.data.local.entity.SyncLogEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val syncLogRepository: SyncLogRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _longPressSpeed = MutableStateFlow(2.0f)
    val longPressSpeed: StateFlow<Float> = _longPressSpeed.asStateFlow()

    private val _orientationPreference = MutableStateFlow(OrientationPreference.FOLLOW_VIDEO)
    val orientationPreference: StateFlow<OrientationPreference> = _orientationPreference.asStateFlow()

    val syncLogs: StateFlow<List<SyncLogEntity>> = syncLogRepository.getRecentFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        val prefs = context.getSharedPreferences("player_prefs", Context.MODE_PRIVATE)
        _longPressSpeed.value = prefs.getFloat("long_press_speed", 2.0f)
        val orientStr = prefs.getString("orientation", null)
        _orientationPreference.value = try {
            OrientationPreference.valueOf(orientStr ?: OrientationPreference.FOLLOW_VIDEO.name)
        } catch (_: Exception) {
            OrientationPreference.FOLLOW_VIDEO
        }
    }

    fun setLongPressSpeed(speed: Float) {
        _longPressSpeed.value = speed
        val prefs = context.getSharedPreferences("player_prefs", Context.MODE_PRIVATE)
        prefs.edit().putFloat("long_press_speed", speed).apply()
    }

    fun setOrientationPreference(pref: OrientationPreference) {
        _orientationPreference.value = pref
        val prefs = context.getSharedPreferences("player_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("orientation", pref.name).apply()
    }

    fun clearLogs() {
        viewModelScope.launch {
            syncLogRepository.clearOldLogs()
        }
    }
}
