package me.haroldmartin.codexeink.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "codex_eink_settings")

class AppPreferences(private val context: Context) {
    val alwaysConnected: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_ALWAYS_CONNECTED] ?: false
    }

    suspend fun setAlwaysConnected(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_ALWAYS_CONNECTED] = enabled
        }
    }

    private companion object {
        val KEY_ALWAYS_CONNECTED = booleanPreferencesKey("always_connected")
    }
}
