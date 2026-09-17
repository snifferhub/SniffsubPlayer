package com.sniffsub.sniff_sub_player

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import com.sniffsub.sniff_sub_player.data.AppPreferencesRepository
import com.sniffsub.sniff_sub_player.data.DataStoreAppPreferencesRepository

private val Context.appPreferencesDataStore by preferencesDataStore(name = "app_preferences")

class AppContainer(applicationContext: Context) {
    val preferencesRepository: AppPreferencesRepository = DataStoreAppPreferencesRepository(
        dataStore = applicationContext.appPreferencesDataStore,
    )
}
