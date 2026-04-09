package cn.vectory.syncplayer.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "player_prefs")

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
        val DEBUG_MODE = booleanPreferencesKey("debug_mode")
        val MAX_CONCURRENT_DOWNLOADS = intPreferencesKey("max_concurrent_downloads")
        val VIEW_MODE = stringPreferencesKey("view_mode")
        val GRID_COLUMNS = intPreferencesKey("grid_columns")
        val CONNECTION_TIMEOUT = intPreferencesKey("connection_timeout")
    }

    val longPressSpeed: Flow<Float> = dataStore.data.map { it[Keys.LONG_PRESS_SPEED] ?: 2.0f }

    val orientation: Flow<String?> = dataStore.data.map { it[Keys.ORIENTATION] }

    val defaultSortMode: Flow<String> = dataStore.data.map {
        it[Keys.DEFAULT_SORT_MODE] ?: "FILENAME_ASC"
    }

    val defaultFilter: Flow<String> = dataStore.data.map {
        it[Keys.DEFAULT_FILTER] ?: "ALL"
    }

    val debugMode: Flow<Boolean> = dataStore.data.map { it[Keys.DEBUG_MODE] ?: false }

    val maxConcurrentDownloads: Flow<Int> = dataStore.data.map {
        (it[Keys.MAX_CONCURRENT_DOWNLOADS] ?: 1).coerceIn(1, 3)
    }

    val viewMode: Flow<String> = dataStore.data.map {
        it[Keys.VIEW_MODE] ?: "LIST"
    }

    val gridColumns: Flow<Int> = dataStore.data.map {
        (it[Keys.GRID_COLUMNS] ?: 3).coerceIn(1, 5)
    }

    val connectionTimeout: Flow<Int> = dataStore.data.map {
        (it[Keys.CONNECTION_TIMEOUT] ?: 5).coerceIn(3, 60)
    }

    suspend fun getLongPressSpeed(): Float = longPressSpeed.first()
    suspend fun getOrientation(): String? = orientation.first()
    suspend fun getDefaultSortMode(): String = defaultSortMode.first()
    suspend fun getDefaultFilter(): String = defaultFilter.first()
    suspend fun getDebugMode(): Boolean = debugMode.first()
    suspend fun getMaxConcurrentDownloads(): Int = maxConcurrentDownloads.first()
    suspend fun getViewMode(): String = viewMode.first()
    suspend fun getGridColumns(): Int = gridColumns.first()
    suspend fun getConnectionTimeout(): Int = connectionTimeout.first()

    suspend fun setLongPressSpeed(speed: Float) {
        dataStore.edit { it[Keys.LONG_PRESS_SPEED] = speed }
    }

    suspend fun setOrientation(pref: String) {
        dataStore.edit { it[Keys.ORIENTATION] = pref }
    }

    suspend fun setDefaultSortMode(mode: String) {
        dataStore.edit { it[Keys.DEFAULT_SORT_MODE] = mode }
    }

    suspend fun setDefaultFilter(filter: String) {
        dataStore.edit { it[Keys.DEFAULT_FILTER] = filter }
    }

    suspend fun setDebugMode(enabled: Boolean) {
        dataStore.edit { it[Keys.DEBUG_MODE] = enabled }
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
}
