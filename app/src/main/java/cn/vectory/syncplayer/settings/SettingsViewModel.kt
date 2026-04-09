package cn.vectory.syncplayer.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.vectory.syncplayer.browser.BrowserViewModel
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

    private val _defaultSortMode = MutableStateFlow(BrowserViewModel.SortMode.FILENAME_ASC)
    val defaultSortMode: StateFlow<BrowserViewModel.SortMode> = _defaultSortMode.asStateFlow()

    private val _defaultFilter = MutableStateFlow(BrowserViewModel.BrowseFilter.ALL)
    val defaultFilter: StateFlow<BrowserViewModel.BrowseFilter> = _defaultFilter.asStateFlow()

    private val _maxConcurrentDownloads = MutableStateFlow(1)
    val maxConcurrentDownloads: StateFlow<Int> = _maxConcurrentDownloads.asStateFlow()

    private val _connectionTimeout = MutableStateFlow(5)
    val connectionTimeout: StateFlow<Int> = _connectionTimeout.asStateFlow()

    private val _viewMode = MutableStateFlow(BrowserViewModel.ViewMode.LIST)
    val viewMode: StateFlow<BrowserViewModel.ViewMode> = _viewMode.asStateFlow()

    private val _gridColumns = MutableStateFlow(3)
    val gridColumns: StateFlow<Int> = _gridColumns.asStateFlow()

    private val _progressAlwaysVisible = MutableStateFlow(false)
    val progressAlwaysVisible: StateFlow<Boolean> = _progressAlwaysVisible.asStateFlow()

    private val _volumeBalance = MutableStateFlow(false)
    val volumeBalance: StateFlow<Boolean> = _volumeBalance.asStateFlow()

    private val _analyzeOnDownload = MutableStateFlow(false)
    val analyzeOnDownload: StateFlow<Boolean> = _analyzeOnDownload.asStateFlow()

    val debugMode: StateFlow<Boolean> = debugLogger.enabled
    private val _previousSessionSummary = MutableStateFlow(debugLogger.getPreviousSessionSummary())
    val previousSessionSummary: StateFlow<String> = _previousSessionSummary.asStateFlow()

    val syncLogs: StateFlow<List<SyncLogEntity>> = syncLogRepository.getRecentFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            _longPressSpeed.value = userPrefs.getLongPressSpeed()
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
            _maxConcurrentDownloads.value = userPrefs.getMaxConcurrentDownloads()
            _connectionTimeout.value = userPrefs.getConnectionTimeout()
            _viewMode.value = try {
                BrowserViewModel.ViewMode.valueOf(userPrefs.getViewMode())
            } catch (_: Exception) {
                BrowserViewModel.ViewMode.LIST
            }
            _gridColumns.value = userPrefs.getGridColumns()
            _progressAlwaysVisible.value = userPrefs.getProgressAlwaysVisible()
            _volumeBalance.value = userPrefs.getVolumeBalance()
            _analyzeOnDownload.value = userPrefs.getAnalyzeOnDownload()
        }
    }

    fun setLongPressSpeed(speed: Float) {
        _longPressSpeed.value = speed
        viewModelScope.launch { userPrefs.setLongPressSpeed(speed) }
    }

    fun setDefaultSortMode(mode: BrowserViewModel.SortMode) {
        _defaultSortMode.value = mode
        viewModelScope.launch { userPrefs.setDefaultSortMode(mode.name) }
    }

    fun setDefaultFilter(filter: BrowserViewModel.BrowseFilter) {
        _defaultFilter.value = filter
        viewModelScope.launch { userPrefs.setDefaultFilter(filter.name) }
    }

    fun setMaxConcurrentDownloads(max: Int) {
        _maxConcurrentDownloads.value = max
        viewModelScope.launch { userPrefs.setMaxConcurrentDownloads(max) }
    }

    fun setConnectionTimeout(timeoutSeconds: Int) {
        _connectionTimeout.value = timeoutSeconds
        viewModelScope.launch { userPrefs.setConnectionTimeout(timeoutSeconds) }
    }

    fun setViewMode(mode: BrowserViewModel.ViewMode) {
        _viewMode.value = mode
        viewModelScope.launch { userPrefs.setViewMode(mode.name) }
    }

    fun setGridColumns(columns: Int) {
        _gridColumns.value = columns.coerceIn(1, 5)
        viewModelScope.launch { userPrefs.setGridColumns(columns) }
    }

    fun setProgressAlwaysVisible(enabled: Boolean) {
        _progressAlwaysVisible.value = enabled
        viewModelScope.launch { userPrefs.setProgressAlwaysVisible(enabled) }
    }

    fun setVolumeBalance(enabled: Boolean) {
        _volumeBalance.value = enabled
        viewModelScope.launch { userPrefs.setVolumeBalance(enabled) }
    }

    fun setAnalyzeOnDownload(enabled: Boolean) {
        _analyzeOnDownload.value = enabled
        viewModelScope.launch { userPrefs.setAnalyzeOnDownload(enabled) }
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
