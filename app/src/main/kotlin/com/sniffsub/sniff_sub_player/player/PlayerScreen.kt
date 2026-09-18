package com.sniffsub.sniff_sub_player.player

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.sniffsub.sniff_sub_player.browser.MediaCandidate
import com.sniffsub.sniff_sub_player.subtitle.SubtitleCue
import com.sniffsub.sniff_sub_player.subtitle.SubtitleRepository
import com.sniffsub.sniff_sub_player.subtitle.SubtitleSearchScreen
import com.sniffsub.sniff_sub_player.subtitle.subtitleAt
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    candidate: MediaCandidate,
    subtitleRepository: SubtitleRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var showSubtitleSearch by remember { mutableStateOf(false) }
    var cues by remember { mutableStateOf<List<SubtitleCue>>(emptyList()) }
    var currentSubtitle by remember { mutableStateOf("") }
    val player = remember(candidate.url, candidate.referer, candidate.userAgent) {
        createPlayer(context, candidate)
    }

    BackHandler(onBack = onBack)
    DisposableEffect(player) {
        onDispose { player.release() }
    }

    LaunchedEffect(player, cues) {
        while (true) {
            currentSubtitle = subtitleAt(cues, player.currentPosition)
            delay(200)
        }
    }

    if (showSubtitleSearch) {
        SubtitleSearchScreen(
            initialQuery = candidate.pageUrl,
            repository = subtitleRepository,
            onClose = { showSubtitleSearch = false },
            onSubtitleSelected = { text, _ ->
                cues = parseSubtitleSafely(text)
                showSubtitleSearch = false
            },
            modifier = modifier,
        )
        return
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("播放器") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showSubtitleSearch = true }) {
                        Icon(Icons.Default.Subtitles, contentDescription = "导入或搜索字幕")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    factory = { PlayerView(it).apply { this.player = player } },
                    modifier = Modifier.fillMaxSize(),
                )
                if (currentSubtitle.isNotBlank()) {
                    Text(
                        text = currentSubtitle,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.65f))
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(candidate.kind.name, style = MaterialTheme.typography.labelLarge)
                Button(onClick = { showSubtitleSearch = true }) {
                    Icon(Icons.Default.Subtitles, contentDescription = null)
                    Text("字幕")
                }
            }
        }
    }
}

private fun createPlayer(context: Context, candidate: MediaCandidate): ExoPlayer {
    val headers = buildMap {
        candidate.referer.takeIf(String::isNotBlank)?.let { put("Referer", it) }
        candidate.userAgent.takeIf(String::isNotBlank)?.let { put("User-Agent", it) }
    }
    val dataSourceFactory = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setDefaultRequestProperties(headers)
    return ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
        .build()
        .also { player ->
            player.setMediaItem(MediaItem.fromUri(candidate.url))
            player.prepare()
            player.playWhenReady = true
        }
}

private fun parseSubtitleSafely(text: String): List<SubtitleCue> =
    runCatching { com.sniffsub.sniff_sub_player.subtitle.parseSubtitles(text) }.getOrDefault(emptyList())
