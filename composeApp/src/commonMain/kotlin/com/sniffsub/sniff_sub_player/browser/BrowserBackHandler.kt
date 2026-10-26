package com.sniffsub.sniff_sub_player.browser

import androidx.compose.runtime.Composable

@Composable
internal expect fun BrowserBackHandler(enabled: Boolean, onBack: () -> Unit)
