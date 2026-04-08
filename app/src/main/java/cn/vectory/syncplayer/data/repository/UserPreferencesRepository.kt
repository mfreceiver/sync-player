package cn.vectory.syncplayer.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
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

    suspend fun getLongPressSpeed(): Float = longPressSpeed.first()
    suspend fun getOrientation(): String? = orientation.first()
    suspend fun getDefaultSortMode(): String = defaultSortMode.first()
    suspend fun getDefaultFilter(): String = defaultFilter.first()
    suspend fun getDebugMode(): Boolean = debugMode.first()

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
}
