package com.sniffsub.sniff_sub_player

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import com.sniffsub.sniff_sub_player.adblock.AdBlockRepository
import com.sniffsub.sniff_sub_player.data.AppPreferencesRepository
import com.sniffsub.sniff_sub_player.data.DataStoreAppPreferencesRepository
import com.sniffsub.sniff_sub_player.subtitle.SubtitleRepository
import com.sniffsub.sniff_sub_player.util.HttpFetcher
import com.sniffsub.sniff_sub_player.util.UrlConnectionHttpFetcher

private val Context.appPreferencesDataStore by preferencesDataStore(name = "app_preferences")

class AppContainer(applicationContext: Context) {
    private val httpFetcher: HttpFetcher = UrlConnectionHttpFetcher()

    val preferencesRepository: AppPreferencesRepository = DataStoreAppPreferencesRepository(
        dataStore = applicationContext.appPreferencesDataStore,
    )
    val adBlockRepository = AdBlockRepository(
        preferencesRepository = preferencesRepository,
        httpFetcher = httpFetcher,
    )
    val subtitleRepository = SubtitleRepository(httpFetcher)
}
