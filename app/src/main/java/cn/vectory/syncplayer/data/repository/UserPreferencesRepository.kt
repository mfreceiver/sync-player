package cn.vectory.syncplayer.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import cn.vectory.syncplayer.data.model.OrientationPreference
import javax.inject.Inject
import javax.inject.Singleton

// AND-059: without a corruption handler, `data` throws CorruptionException into
// every collector/reader — one damaged write takes the app down wherever prefs
// are touched. Replacing the file with empty prefs is the least-bad recovery:
// every consumer already has a default for every key.
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "player_prefs",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

@Singleton
class UserPreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.dataStore

    private object Keys {
        val LONG_PRESS_SPEED = floatPreferencesKey("long_press_speed")
        val ORIENTATION = stringPreferencesKey("orientation")
        val DEFAULT_SORT_MODE = stringPreferencesKey("default_sort_mode")
        val DEFAULT_FILTER = stringPreferencesKey("default_filter")
        val BROWSE_MODE = stringPreferencesKey("browse_mode")
        val MAX_CONCURRENT_DOWNLOADS = intPreferencesKey("max_concurrent_downloads")
        val VIEW_MODE = stringPreferencesKey("view_mode")
        val GRID_COLUMNS = intPreferencesKey("grid_columns")
        val CONNECTION_TIMEOUT = intPreferencesKey("connection_timeout")
        val VOLUME_BALANCE = booleanPreferencesKey("volume_balance")
        val ANALYZE_ON_DOWNLOAD = booleanPreferencesKey("analyze_on_download")
        val DOUBLE_TAP_ACTION = stringPreferencesKey("double_tap_action")
        val QUICK_RECYCLE = booleanPreferencesKey("quick_recycle")
        val SECURE_LOCK_ENABLED = booleanPreferencesKey("secure_lock_enabled")
        val ONBOARDING_DISMISSED = booleanPreferencesKey("onboarding_dismissed")
        // 外部 SD v2（任务包 B）：默认写入卷 id（SyncStoragePort.VOLUME_ID_INTERNAL 等）。
        val DEFAULT_WRITE_VOLUME = stringPreferencesKey("default_write_volume")
        // 外部 SD v2（任务包 B）：已登记外部卷的 uuid 集合（启动/同步开始时重登记）。
        val REGISTERED_EXTERNAL_VOLUMES = stringSetPreferencesKey("registered_external_volumes")
    }

    val longPressSpeed: Flow<Float> = dataStore.data.map { it[Keys.LONG_PRESS_SPEED] ?: 2.0f }

    val orientation: Flow<OrientationPreference?> = dataStore.data.map { it[Keys.ORIENTATION]?.let { name -> try { OrientationPreference.valueOf(name) } catch (_: IllegalArgumentException) { null } } }

    val defaultSortMode: Flow<String> = dataStore.data.map {
        it[Keys.DEFAULT_SORT_MODE] ?: "FILENAME_ASC"
    }

    val defaultFilter: Flow<String> = dataStore.data.map {
        it[Keys.DEFAULT_FILTER] ?: "ALL"
    }

    val maxConcurrentDownloads: Flow<Int> = dataStore.data.map {
        (it[Keys.MAX_CONCURRENT_DOWNLOADS] ?: 1).coerceIn(1, 3)
    }

    val viewMode: Flow<String> = dataStore.data.map {
        it[Keys.VIEW_MODE] ?: "LIST"
    }

    val gridColumns: Flow<Int> = dataStore.data.map {
        (it[Keys.GRID_COLUMNS] ?: 3).coerceIn(1, 5)
    }

    val browseMode: Flow<String> = dataStore.data.map {
        it[Keys.BROWSE_MODE] ?: "DIRECTORY"
    }

    val connectionTimeout: Flow<Int> = dataStore.data.map {
        (it[Keys.CONNECTION_TIMEOUT] ?: 5).coerceIn(3, 60)
    }

    val volumeBalance: Flow<Boolean> = dataStore.data.map {
        it[Keys.VOLUME_BALANCE] ?: false
    }

    val analyzeOnDownload: Flow<Boolean> = dataStore.data.map {
        it[Keys.ANALYZE_ON_DOWNLOAD] ?: false
    }

    val doubleTapAction: Flow<String> = dataStore.data.map {
        it[Keys.DOUBLE_TAP_ACTION] ?: "TRASH"
    }

    val quickRecycle: Flow<Boolean> = dataStore.data.map {
        it[Keys.QUICK_RECYCLE] ?: true
    }

    // Secure lock enabled flag
    val secureLockEnabled: Flow<Boolean> = dataStore.data.map {
        it[Keys.SECURE_LOCK_ENABLED] ?: false
    }

    val onboardingDismissed: Flow<Boolean> = dataStore.data.map {
        it[Keys.ONBOARDING_DISMISSED] ?: false
    }

    // 外部 SD v2：默认写入卷（新文件落卷）；existing 行始终写回自身卷，不受此偏好影响。
    // 空值/异常值归一到内部卷 id（fail-safe：读不到偏好时永不指向不存在的卷）。
    val defaultWriteVolume: Flow<String> = dataStore.data.map {
        it[Keys.DEFAULT_WRITE_VOLUME]?.takeIf { id -> id.isNotBlank() }
            ?: cn.vectory.syncplayer.sync.VOLUME_ID_INTERNAL
    }

    // 外部 SD v2：已登记外部卷 uuid 集合（SyncStoragePort 启动/同步开始时据此重登记）。
    val registeredExternalVolumes: Flow<Set<String>> = dataStore.data.map {
        it[Keys.REGISTERED_EXTERNAL_VOLUMES] ?: emptySet()
    }

    suspend fun getLongPressSpeed(): Float = longPressSpeed.first()
    suspend fun getOrientation(): OrientationPreference? = orientation.first()
    suspend fun getDefaultSortMode(): String = defaultSortMode.first()
    suspend fun getDefaultFilter(): String = defaultFilter.first()
    suspend fun getMaxConcurrentDownloads(): Int = maxConcurrentDownloads.first()
    suspend fun getViewMode(): String = viewMode.first()
    suspend fun getGridColumns(): Int = gridColumns.first()
    suspend fun getConnectionTimeout(): Int = connectionTimeout.first()
    suspend fun getVolumeBalance(): Boolean = volumeBalance.first()
    suspend fun getAnalyzeOnDownload(): Boolean = analyzeOnDownload.first()
    suspend fun getDoubleTapAction(): String = doubleTapAction.first()
    suspend fun getQuickRecycle(): Boolean = quickRecycle.first()

    suspend fun getSecureLockEnabled(): Boolean = secureLockEnabled.first()

    suspend fun getBrowseMode(): String = browseMode.first()

    suspend fun setLongPressSpeed(speed: Float) {
        dataStore.edit { it[Keys.LONG_PRESS_SPEED] = speed }
    }

    suspend fun setOrientation(pref: OrientationPreference) {
        dataStore.edit { it[Keys.ORIENTATION] = pref.name }
    }

    suspend fun setDefaultSortMode(mode: String) {
        dataStore.edit { it[Keys.DEFAULT_SORT_MODE] = mode }
    }

    suspend fun setDefaultFilter(filter: String) {
        dataStore.edit { it[Keys.DEFAULT_FILTER] = filter }
    }

    suspend fun setMaxConcurrentDownloads(max: Int) {
        dataStore.edit { it[Keys.MAX_CONCURRENT_DOWNLOADS] = max.coerceIn(1, 3) }
    }

    suspend fun setViewMode(mode: String) {
        dataStore.edit { it[Keys.VIEW_MODE] = mode }
    }

    suspend fun setGridColumns(columns: Int) {
        dataStore.edit { it[Keys.GRID_COLUMNS] = columns.coerceIn(1, 5) }
    }

    suspend fun setConnectionTimeout(timeoutSeconds: Int) {
        dataStore.edit { it[Keys.CONNECTION_TIMEOUT] = timeoutSeconds.coerceIn(3, 60) }
    }

    suspend fun setVolumeBalance(enabled: Boolean) {
        dataStore.edit { it[Keys.VOLUME_BALANCE] = enabled }
    }

    suspend fun setAnalyzeOnDownload(enabled: Boolean) {
        dataStore.edit { it[Keys.ANALYZE_ON_DOWNLOAD] = enabled }
    }

    suspend fun setDoubleTapAction(action: String) {
        dataStore.edit { it[Keys.DOUBLE_TAP_ACTION] = action }
    }

    suspend fun setQuickRecycle(enabled: Boolean) {
        dataStore.edit { it[Keys.QUICK_RECYCLE] = enabled }
    }

    suspend fun setSecureLockEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.SECURE_LOCK_ENABLED] = enabled }
    }

    suspend fun setBrowseMode(mode: String) {
        dataStore.edit { it[Keys.BROWSE_MODE] = mode }
    }

    suspend fun getOnboardingDismissed(): Boolean = onboardingDismissed.first()

    suspend fun setOnboardingDismissed(dismissed: Boolean) {
        dataStore.edit { it[Keys.ONBOARDING_DISMISSED] = dismissed }
    }

    suspend fun getDefaultWriteVolume(): String = defaultWriteVolume.first()

    suspend fun getRegisteredExternalVolumes(): Set<String> = registeredExternalVolumes.first()

    suspend fun setRegisteredExternalVolumes(uuids: Set<String>) {
        dataStore.edit { it[Keys.REGISTERED_EXTERNAL_VOLUMES] = uuids }
    }

    suspend fun setDefaultWriteVolume(volumeId: String) {
        dataStore.edit { it[Keys.DEFAULT_WRITE_VOLUME] = volumeId }
    }
}
