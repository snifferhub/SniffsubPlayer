package com.sniffsub.sniff_sub_player.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.LayoutInflater
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sniffsub.sniff_sub_player.browser.MediaCandidate
import com.sniffsub.sniff_sub_player.R
import com.sniffsub.sniff_sub_player.subtitle.SubtitleCue
import com.sniffsub.sniff_sub_player.subtitle.SubtitleRepository
import com.sniffsub.sniff_sub_player.subtitle.SubtitleSearchScreen
import com.sniffsub.sniff_sub_player.subtitle.subtitleAt
import kotlinx.coroutines.delay
import kotlin.math.max

private val PlayerAccent = Color(0xFFFF9FBD)

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    candidate: MediaCandidate,
    subtitleRepository: SubtitleRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var showSubtitleSearch by remember { mutableStateOf(false) }
    var cues by remember { mutableStateOf<List<SubtitleCue>>(emptyList()) }
    var currentSubtitle by remember { mutableStateOf("") }
    var controlsVisible by remember { mutableStateOf(true) }
    var interactionRevision by remember { mutableIntStateOf(0) }
    var feedbackRevision by remember { mutableIntStateOf(0) }
    var feedbackText by remember { mutableStateOf("") }
    var feedbackVisible by remember { mutableStateOf(false) }
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var scrubPositionMs by remember { mutableStateOf<Long?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }
    val player = remember(candidate.url, candidate.referer, candidate.userAgent) {
        createPlayer(context, candidate)
    }
    val attachedView = remember(player) { arrayOfNulls<PlayerView>(1) }

    fun keepControlsVisible() {
        controlsVisible = true
        interactionRevision += 1
    }

    fun seekBy(deltaMs: Long) {
        val target = clampSeekPosition(currentPositionMs + deltaMs, durationMs)
        player.seekTo(target)
        currentPositionMs = target
        feedbackText = if (deltaMs < 0) "后退 10 秒" else "前进 10 秒"
        feedbackRevision += 1
        keepControlsVisible()
    }

    fun exitPlayback() {
        attachedView[0]?.player = null
        attachedView[0]?.keepScreenOn = false
        player.pause()
        player.clearVideoSurface()
        onBack()
    }

    BackHandler {
        if (showSubtitleSearch) showSubtitleSearch = false else exitPlayback()
    }

    DisposableEffect(activity) {
        val previousOrientation = activity?.requestedOrientation
        val window = activity?.window
        val insetsController = if (window == null) null else {
            WindowInsetsControllerCompat(window, window.decorView)
        }
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            insetsController?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController?.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (previousOrientation != null) activity?.requestedOrientation = previousOrientation
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    DisposableEffect(player) {
        onDispose {
            attachedView[0]?.player = null
            attachedView[0]?.keepScreenOn = false
            player.playWhenReady = false
            player.clearVideoSurface()
            player.release()
        }
    }

    DisposableEffect(player, lifecycle) {
        var resumePlayback = false
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                resumePlayback = player.playWhenReady
                player.pause()
                attachedView[0]?.keepScreenOn = false
            } else if (event == Lifecycle.Event.ON_RESUME && resumePlayback) {
                resumePlayback = false
                attachedView[0]?.keepScreenOn = true
                player.play()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(player, cues) {
        while (true) {
            val rawDuration = player.duration
            durationMs = if (rawDuration == C.TIME_UNSET || rawDuration < 0) 0L else rawDuration
            if (scrubPositionMs == null) currentPositionMs = max(0L, player.currentPosition)
            isPlaying = player.isPlaying
            statusText = player.playerError?.localizedMessage.orEmpty()
            currentSubtitle = subtitleAt(cues, player.currentPosition)
            delay(200)
        }
    }

    LaunchedEffect(controlsVisible, interactionRevision, isPlaying) {
        if (controlsVisible && isPlaying) {
            delay(3_000)
            controlsVisible = false
        }
    }

    LaunchedEffect(feedbackRevision) {
        if (feedbackRevision > 0) {
            feedbackVisible = true
            delay(750)
            feedbackVisible = false
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
                keepControlsVisible()
            },
            modifier = modifier,
        )
        return
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            factory = {
                createVideoPlayerView(it).apply {
                    attachedView[0] = this
                    this.player = player
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    keepScreenOn = true
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = { it.player = player },
            onReset = null,
            onRelease = {
                it.player = null
                it.keepScreenOn = false
                if (attachedView[0] === it) attachedView[0] = null
            },
            modifier = Modifier.fillMaxSize(),
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(player) {
                    detectTapGestures(
                        onTap = {
                            controlsVisible = !controlsVisible
                            if (controlsVisible) interactionRevision += 1
                        },
                        onDoubleTap = { offset ->
                            seekBy(if (offset.x < size.width / 2f) -10_000L else 10_000L)
                        },
                    )
                },
        )

        if (currentSubtitle.isNotBlank()) {
            Text(
                text = currentSubtitle,
                color = Color.White,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(0.88f)
                    .padding(bottom = if (controlsVisible) 74.dp else 28.dp),
            )
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            PlayerControls(
                isPlaying = isPlaying,
                currentPositionMs = scrubPositionMs ?: currentPositionMs,
                durationMs = durationMs,
                statusText = statusText,
                hasSubtitles = cues.isNotEmpty(),
                onClose = ::exitPlayback,
                onSubtitles = {
                    keepControlsVisible()
                    showSubtitleSearch = true
                },
                onSeekBack = { seekBy(-10_000L) },
                onTogglePlayback = {
                    if (player.isPlaying) {
                        player.pause()
                    } else {
                        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0L)
                        player.play()
                    }
                    isPlaying = player.isPlaying
                    keepControlsVisible()
                },
                onSeekForward = { seekBy(10_000L) },
                onScrub = {
                    scrubPositionMs = it
                    keepControlsVisible()
                },
                onScrubFinished = {
                    scrubPositionMs?.let { target ->
                        player.seekTo(target)
                        currentPositionMs = target
                    }
                    scrubPositionMs = null
                    keepControlsVisible()
                },
            )
        }

        AnimatedVisibility(
            visible = feedbackVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.68f),
                contentColor = Color.White,
                shape = RoundedCornerShape(50),
            ) {
                Text(
                    text = feedbackText,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 14.dp),
                )
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
internal fun createVideoPlayerView(context: Context): PlayerView =
    LayoutInflater.from(context).inflate(R.layout.player_view, null, false) as PlayerView

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
private fun PlayerControls(
    isPlaying: Boolean,
    currentPositionMs: Long,
    durationMs: Long,
    statusText: String,
    hasSubtitles: Boolean,
    onClose: () -> Unit,
    onSubtitles: () -> Unit,
    onSeekBack: () -> Unit,
    onTogglePlayback: () -> Unit,
    onSeekForward: () -> Unit,
    onScrub: (Long) -> Unit,
    onScrubFinished: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.68f),
                    0.34f to Color.Transparent,
                    0.62f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.82f),
                ),
            ),
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayerControlButton(
                icon = Icons.Default.Close,
                contentDescription = "关闭播放器",
                onClick = onClose,
                size = 42.dp,
                iconSize = 22.dp,
            )
            if (statusText.isNotBlank()) {
                Text(
                    text = statusText,
                    color = Color.White.copy(alpha = 0.78f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Surface(
                onClick = onSubtitles,
                color = Color.White.copy(alpha = 0.16f),
                contentColor = Color.White,
                shape = RoundedCornerShape(50),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Subtitles,
                        contentDescription = "字幕设置",
                        tint = if (hasSubtitles) PlayerAccent else Color.White,
                        modifier = Modifier.size(21.dp),
                    )
                    Spacer(Modifier.width(7.dp))
                    Text("字幕", style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        Row(
            modifier = Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(42.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayerControlButton(
                icon = Icons.Default.Replay10,
                contentDescription = "后退 10 秒",
                onClick = onSeekBack,
                size = 58.dp,
                iconSize = 30.dp,
            )
            PlayerControlButton(
                icon = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "暂停" else "播放",
                onClick = onTogglePlayback,
                size = 74.dp,
                iconSize = 40.dp,
            )
            PlayerControlButton(
                icon = Icons.Default.Forward10,
                contentDescription = "前进 10 秒",
                onClick = onSeekForward,
                size = 58.dp,
                iconSize = 30.dp,
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatPlayerTime(currentPositionMs),
                color = Color.White.copy(alpha = 0.92f),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.width(58.dp),
            )
            Slider(
                thumb = {},
                track = { sliderState ->
                    SliderDefaults.Track(
                        sliderState = sliderState,
                        enabled = durationMs > 0,
                        colors = SliderDefaults.colors(
                            activeTrackColor = PlayerAccent,
                            inactiveTrackColor = Color.White.copy(alpha = 0.35f),
                        ),
                        thumbTrackGapSize = 0.dp,
                        drawStopIndicator = null,
                    )
                },
                value = currentPositionMs.coerceIn(0L, max(1L, durationMs)).toFloat(),
                onValueChange = { onScrub(it.toLong()) },
                onValueChangeFinished = onScrubFinished,
                valueRange = 0f..max(1L, durationMs).toFloat(),
                enabled = durationMs > 0,
                colors = SliderDefaults.colors(
                    thumbColor = PlayerAccent,
                    activeTrackColor = PlayerAccent,
                    inactiveTrackColor = Color.White.copy(alpha = 0.35f),
                ),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatPlayerTime(durationMs),
                color = Color.White.copy(alpha = 0.92f),
                textAlign = TextAlign.End,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.width(58.dp),
            )
        }
    }
}

@Composable
private fun PlayerControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    size: Dp,
    iconSize: Dp,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .size(size)
            .shadow(12.dp, CircleShape),
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.18f),
        contentColor = Color.White,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

internal fun clampSeekPosition(positionMs: Long, durationMs: Long): Long =
    positionMs.coerceIn(0L, if (durationMs > 0) durationMs else Long.MAX_VALUE)

internal fun formatPlayerTime(timeMs: Long): String {
    val totalSeconds = max(0L, timeMs) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

@UnstableApi
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

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun parseSubtitleSafely(text: String): List<SubtitleCue> =
    runCatching { com.sniffsub.sniff_sub_player.subtitle.parseSubtitles(text) }.getOrDefault(emptyList())
