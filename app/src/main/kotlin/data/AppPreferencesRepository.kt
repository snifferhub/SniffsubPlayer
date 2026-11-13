package com.sniffsub.sniff_sub_player.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

internal class DataStoreAppPreferencesRepository(
    private val dataStore: DataStore<Preferences>,
) : AppPreferencesRepository {
    override val values: Flow<AppPreferences> = dataStore.data.map { preferences ->
        AppPreferencesCodec.decode(preferences[APP_PREFERENCES_KEY])
    }

    override suspend fun update(transform: (AppPreferences) -> AppPreferences) {
        dataStore.edit { preferences ->
            val current = AppPreferencesCodec.decode(preferences[APP_PREFERENCES_KEY])
            preferences[APP_PREFERENCES_KEY] = AppPreferencesCodec.encode(transform(current))
        }
    }

    private companion object {
        val APP_PREFERENCES_KEY = stringPreferencesKey("app.preferences.v1")
    }
}
