package cn.vectory.syncplayer.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.vectory.syncplayer.browser.BrowserViewModel
import cn.vectory.syncplayer.data.model.OrientationPreference
import cn.vectory.syncplayer.data.repository.SyncLogRepository
import cn.vectory.syncplayer.data.local.entity.SyncLogEntity
import cn.vectory.syncplayer.data.repository.UserPreferencesRepository
import cn.vectory.syncplayer.debug.DebugLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import java.io.File

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val syncLogRepository: SyncLogRepository,
    private val userPrefs: UserPreferencesRepository,
    private val debugLogger: DebugLogger
) : ViewModel() {

    private val _longPressSpeed = MutableStateFlow(2.0f)
    val longPressSpeed: StateFlow<Float> = _longPressSpeed.asStateFlow()

    private val _orientationPreference = MutableStateFlow(OrientationPreference.FOLLOW_VIDEO)
    val orientationPreference: StateFlow<OrientationPreference> = _orientationPreference.asStateFlow()

    private val _defaultSortMode = MutableStateFlow(BrowserViewModel.SortMode.FILENAME_ASC)
    val defaultSortMode: StateFlow<BrowserViewModel.SortMode> = _defaultSortMode.asStateFlow()

    private val _defaultFilter = MutableStateFlow(BrowserViewModel.BrowseFilter.ALL)
    val defaultFilter: StateFlow<BrowserViewModel.BrowseFilter> = _defaultFilter.asStateFlow()

    val debugMode: StateFlow<Boolean> = debugLogger.enabled
    private val _previousSessionSummary = MutableStateFlow(debugLogger.getPreviousSessionSummary())
    val previousSessionSummary: StateFlow<String> = _previousSessionSummary.asStateFlow()

    val syncLogs: StateFlow<List<SyncLogEntity>> = syncLogRepository.getRecentFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            _longPressSpeed.value = userPrefs.getLongPressSpeed()
            val orientStr = userPrefs.getOrientation()
            _orientationPreference.value = try {
                OrientationPreference.valueOf(orientStr ?: OrientationPreference.FOLLOW_VIDEO.name)
            } catch (_: Exception) {
                OrientationPreference.FOLLOW_VIDEO
            }
            _defaultSortMode.value = try {
                BrowserViewModel.SortMode.valueOf(userPrefs.getDefaultSortMode())
            } catch (_: Exception) {
                BrowserViewModel.SortMode.FILENAME_ASC
            }
            _defaultFilter.value = try {
                BrowserViewModel.BrowseFilter.valueOf(userPrefs.getDefaultFilter())
            } catch (_: Exception) {
                BrowserViewModel.BrowseFilter.ALL
            }
        }
    }

    fun setLongPressSpeed(speed: Float) {
        _longPressSpeed.value = speed
        viewModelScope.launch { userPrefs.setLongPressSpeed(speed) }
    }

    fun setOrientationPreference(pref: OrientationPreference) {
        _orientationPreference.value = pref
        viewModelScope.launch { userPrefs.setOrientation(pref.name) }
    }

    fun setDefaultSortMode(mode: BrowserViewModel.SortMode) {
        _defaultSortMode.value = mode
        viewModelScope.launch { userPrefs.setDefaultSortMode(mode.name) }
    }

    fun setDefaultFilter(filter: BrowserViewModel.BrowseFilter) {
        _defaultFilter.value = filter
        viewModelScope.launch { userPrefs.setDefaultFilter(filter.name) }
    }

    fun clearLogs() {
        viewModelScope.launch {
            syncLogRepository.clearOldLogs()
        }
    }

    fun enableDebugMode() {
        debugLogger.enable()
        _previousSessionSummary.value = debugLogger.getPreviousSessionSummary()
    }

    fun disableDebugMode() {
        debugLogger.disable()
        _previousSessionSummary.value = debugLogger.getPreviousSessionSummary()
    }

    fun exportPreviousLog(): File? {
        return debugLogger.exportPreviousLog()
    }

    fun clearPreviousLog() {
        debugLogger.clearPreviousLog()
        _previousSessionSummary.value = debugLogger.getPreviousSessionSummary()
    }
}
