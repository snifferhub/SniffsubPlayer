package com.sniffsub.sniff_sub_player.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.zIndex
import com.sniffsub.sniff_sub_player.adblock.AdBlockState
import com.sniffsub.sniff_sub_player.data.AppPreferences
import com.sniffsub.sniff_sub_player.data.Bookmark
import com.sniffsub.sniff_sub_player.settings.AdBlockSettingsActions
import com.sniffsub.sniff_sub_player.settings.AdBlockSettingsScreen
import com.sniffsub.sniff_sub_player.settings.BookmarksActions
import com.sniffsub.sniff_sub_player.settings.BookmarksScreen
import com.sniffsub.sniff_sub_player.settings.SettingsActions
import com.sniffsub.sniff_sub_player.settings.SettingsScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(
    preferences: AppPreferences,
    adBlockState: AdBlockState,
    settingsActions: SettingsActions,
    adBlockActions: AdBlockSettingsActions,
    onOpenBookmark: (Bookmark) -> Unit,
    onDeleteBookmark: (Bookmark) -> Unit,
    browserContent: @Composable (visible: Boolean) -> Unit,
) {
    var route by rememberSaveable { mutableStateOf("browser") }
    val browserVisible = route == "browser"
    Box(Modifier.fillMaxSize()) {
        // Keep native browser views alive while navigating through settings.
        Scaffold(
            modifier = Modifier.fillMaxSize().zIndex(if (browserVisible) 0f else -1f)
                .then(if (browserVisible) Modifier else Modifier.clearAndSetSemantics {}),
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("SniffSubPlayer") },
                    actions = {
                        IconButton(onClick = { route = "settings" }) {
                            Icon(Icons.Default.Settings, contentDescription = "设置")
                        }
                    },
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) { browserContent(browserVisible) }
        }
        if (!browserVisible) Surface(Modifier.fillMaxSize()) {
            when (route) {
                "settings" -> SettingsScreen(
                    preferences = preferences.copy(adBlockEnabled = adBlockState.isEnabled),
                    actions = settingsActions.copy(
                        onBack = { route = "browser" },
                        onOpenBookmarks = { route = "bookmarks" },
                        onOpenAdBlock = { route = "adblock" },
                    ),
                    showAppUpdates = false,
                )
                "bookmarks" -> BookmarksScreen(
                    bookmarks = preferences.bookmarks,
                    actions = BookmarksActions(
                        onBack = { route = "settings" },
                        onOpenBookmark = { bookmark ->
                            onOpenBookmark(bookmark)
                            route = "browser"
                        },
                        onDeleteBookmark = onDeleteBookmark,
                    ),
                )
                "adblock" -> AdBlockSettingsScreen(
                    state = adBlockState,
                    actions = adBlockActions.copy(onBack = { route = "settings" }),
                )
            }
        }
    }
}
