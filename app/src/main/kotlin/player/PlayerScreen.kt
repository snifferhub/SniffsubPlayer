package com.sniffsub.sniff_sub_player.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
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
import kotlin.math.roundToInt

private val PlayerAccent = Color(0xFFFF9FBD)

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun PlayerScreen(
    candidate: MediaCandidate,
    subtitleRepository: SubtitleRepository,
    pictureInPicture: PlayerPictureInPicture,
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
    var feedbackText by remember { mutableStateOf<String?>(null) }
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var scrubPositionMs by remember { mutableStateOf<Long?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }
    val player = remember(candidate.url, candidate.referer, candidate.userAgent) {
        createPlayer(context, candidate)
    }
    val attachedView = remember(player) { arrayOfNulls<PlayerView>(1) }
    var videoSize by remember(player) { mutableStateOf(player.videoSize) }
    var canEnterPictureInPicture by remember(player) { mutableStateOf(false) }
    var autoEnterPictureInPicture by remember(player) { mutableStateOf(false) }
    var playerBounds by remember { mutableStateOf<Rect?>(null) }
    val isInPictureInPicture = pictureInPicture.isShowing
    val temporarySpeed = remember(player) {
        TemporaryPlaybackSpeed({ player.playbackParameters }, { player.playbackParameters = it })
    }
    var seekSession by remember(player) { mutableStateOf<HorizontalSeekSession?>(null) }
    var holdingSpeed by remember(player) { mutableStateOf(false) }
    val levelControls = remember(context, activity) { PlayerLevelControls(context, activity?.window) }
    var levelSession by remember { mutableStateOf<VerticalLevelSession?>(null) }
    var adjustingVolume by remember { mutableStateOf(false) }
    var levelFeedback by remember { mutableStateOf<String?>(null) }

    fun clearFeedback() {
        feedbackText = null
        feedbackRevision += 1
    }
    var gesturesEnabled by remember(lifecycle) {
        mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }

    fun stopHoldingSpeed() {
        temporarySpeed.stop()
        holdingSpeed = false
    }

    fun cancelGestures() {
        stopHoldingSpeed()
        if (seekSession != null) scrubPositionMs = null
        seekSession = null
        levelSession = null
        levelFeedback = null
        clearFeedback()
    }

    fun keepControlsVisible() {
        controlsVisible = true
        interactionRevision += 1
    }

    fun seekBy(deltaMs: Long) {
        val target = clampSeekPosition(currentPositionMs + deltaMs, durationMs)
        player.seekTo(target)
        currentPositionMs = target
        feedbackText = if (deltaMs < 0) "后退 15 秒" else "前进 15 秒"
        feedbackRevision += 1
        keepControlsVisible()
    }

    fun exitPlayback() {
        pictureInPicture.updateVideo(null, VideoSize.UNKNOWN, autoEnter = false)
        gesturesEnabled = false
        cancelGestures()
        attachedView[0]?.player = null
        player.pause()
        player.clearVideoSurface()
        onBack()
    }

    BackHandler(enabled = !isInPictureInPicture) {
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

    DisposableEffect(levelControls) {
        onDispose { levelControls.restoreBrightness() }
    }

    DisposableEffect(player, lifecycle, pictureInPicture) {
        fun updatePictureInPicturePlayback() {
            videoSize = player.videoSize
            canEnterPictureInPicture = videoSize.width > 0 && videoSize.height > 0 &&
                player.playerError == null &&
                (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING)
            autoEnterPictureInPicture = canEnterPictureInPicture && player.playWhenReady
        }

        var mediaSession: MediaSession? = null
        var resumePlayback = false
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    if (pictureInPicture.isSupported) {
                        mediaSession = MediaSession.Builder(context, player).build()
                    }
                    if (resumePlayback) {
                        resumePlayback = false
                        player.play()
                    }
                }
                Lifecycle.Event.ON_RESUME -> gesturesEnabled = true
                Lifecycle.Event.ON_PAUSE -> {
                    gesturesEnabled = false
                    cancelGestures()
                }
                Lifecycle.Event.ON_STOP -> {
                    mediaSession?.release()
                    mediaSession = null
                    resumePlayback = player.playWhenReady
                    player.pause()
                }
                else -> Unit
            }
        }
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                updatePictureInPicturePlayback()
            }
        }
        player.addListener(listener)
        lifecycle.addObserver(lifecycleObserver)
        updatePictureInPicturePlayback()
        onDispose {
            pictureInPicture.updateVideo(null, VideoSize.UNKNOWN, autoEnter = false)
            lifecycle.removeObserver(lifecycleObserver)
            player.removeListener(listener)
            mediaSession?.release()
            temporarySpeed.stop()
            attachedView[0]?.player = null
            player.playWhenReady = false
            player.clearVideoSurface()
            player.release()
        }
    }

    SideEffect {
        if (!isInPictureInPicture) {
            val source = playerBounds.takeIf { canEnterPictureInPicture && !showSubtitleSearch }
            pictureInPicture.updateVideo(source, videoSize, autoEnterPictureInPicture)
        }
    }

    DisposableEffect(player, lifecycle, activity) {
        val window = activity?.window
        val flag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        val wasKeepingScreenOn = window?.attributes?.flags?.and(flag) != 0

        fun updateKeepScreenOn() {
            if (player.isPlaying && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                window?.addFlags(flag)
            } else if (!wasKeepingScreenOn) {
                window?.clearFlags(flag)
            }
        }

        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateKeepScreenOn()
            }
        }
        val observer = LifecycleEventObserver { _, _ -> updateKeepScreenOn() }
        player.addListener(listener)
        lifecycle.addObserver(observer)
        updateKeepScreenOn()
        onDispose {
            player.removeListener(listener)
            lifecycle.removeObserver(observer)
            if (!wasKeepingScreenOn) window?.clearFlags(flag)
        }
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

    LaunchedEffect(controlsVisible, interactionRevision, isPlaying, seekSession, holdingSpeed, levelSession) {
        if (controlsVisible && isPlaying && seekSession == null && !holdingSpeed && levelSession == null) {
            delay(3_000)
            controlsVisible = false
        }
    }

    LaunchedEffect(feedbackText, feedbackRevision) {
        if (feedbackText != null) {
            delay(750)
            feedbackText = null
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
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = { it.player = player },
            onReset = null,
            onRelease = {
                it.player = null
                if (attachedView[0] === it) attachedView[0] = null
            },
            modifier = Modifier.fillMaxSize().onGloballyPositioned { coordinates ->
                if (!isInPictureInPicture) {
                    val bounds = coordinates.boundsInWindow()
                    playerBounds = Rect(
                        bounds.left.roundToInt(),
                        bounds.top.roundToInt(),
                        bounds.right.roundToInt(),
                        bounds.bottom.roundToInt(),
                    )
                }
            },
        )

        if (currentSubtitle.isNotBlank()) {
            val subtitleBottom = when {
                isInPictureInPicture -> 10.dp
                controlsVisible -> 74.dp
                else -> 28.dp
            }
            Text(
                text = currentSubtitle,
                color = Color.White,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Bold,
                style = if (isInPictureInPicture) {
                    MaterialTheme.typography.bodySmall
                } else {
                    MaterialTheme.typography.titleLarge
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(0.88f)
                    .padding(bottom = subtitleBottom),
            )
        }

        if (isInPictureInPicture) return@Box

        PlayerGestureSurface(
            enabled = gesturesEnabled,
            onTap = {
                controlsVisible = !controlsVisible
                if (controlsVisible) interactionRevision += 1
            },
            onDoubleTap = { forward -> seekBy(if (forward) 15_000L else -15_000L) },
            onSeekStart = {
                clearFeedback()
                val seekDuration = player.duration
                if (player.isCurrentMediaItemSeekable && seekDuration > 0L) {
                    seekSession = HorizontalSeekSession(player.currentPosition, seekDuration)
                    scrubPositionMs = seekSession?.targetPositionMs
                    keepControlsVisible()
                    true
                } else {
                    false
                }
            },
            onSeekDelta = { delta, width ->
                scrubPositionMs = seekSession?.dragBy(delta, width)
            },
            onSeekFinish = { commit ->
                if (commit) seekSession?.let { session ->
                    player.seekTo(session.targetPositionMs)
                    currentPositionMs = session.targetPositionMs
                }
                seekSession = null
                scrubPositionMs = null
                keepControlsVisible()
            },
            onHoldStart = {
                clearFeedback()
                if (player.playWhenReady && player.playbackState != Player.STATE_ENDED) {
                    temporarySpeed.start()
                    holdingSpeed = true
                }
            },
            onHoldEnd = ::stopHoldingSpeed,
            onVerticalStart = { rightHalf ->
                clearFeedback()
                adjustingVolume = rightHalf
                levelSession = VerticalLevelSession(
                    startLevel = if (rightHalf) levelControls.volume else levelControls.brightness,
                    rangePerScreen = if (rightHalf) 0.25f else 0.75f,
                    minimum = if (rightHalf) 0f else 0.02f,
                )
            },
            onVerticalDelta = { delta, height ->
                levelSession?.dragBy(delta, height)?.let { level ->
                    if (adjustingVolume) {
                        levelControls.volume = level
                        levelFeedback = "音量 ${(levelControls.volume * 100).roundToInt()}%"
                    } else {
                        levelControls.brightness = level
                        levelFeedback = "亮度 ${(levelControls.brightness * 100).roundToInt()}%"
                    }
                }
            },
            onVerticalEnd = {
                levelSession = null
                feedbackText = levelFeedback
                feedbackRevision += 1
                levelFeedback = null
                interactionRevision += 1
            },
            modifier = Modifier
                .fillMaxSize()
                .testTag("player_gesture_surface"),
        )

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
                showPictureInPictureButton = pictureInPicture.isSupported,
                pictureInPictureEnabled = canEnterPictureInPicture && playerBounds?.isEmpty == false,
                onPictureInPicture = {
                    cancelGestures()
                    if (!pictureInPicture.enter()) {
                        Toast.makeText(context, R.string.player_picture_in_picture_unavailable, Toast.LENGTH_SHORT)
                            .show()
                    }
                },
                onClose = ::exitPlayback,
                onSubtitles = {
                    cancelGestures()
                    keepControlsVisible()
                    showSubtitleSearch = true
                },
                onSeekBack = { seekBy(-15_000L) },
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
                onSeekForward = { seekBy(15_000L) },
                onScrub = {
                    clearFeedback()
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
            visible = feedbackText != null || seekSession != null || levelFeedback != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            PlayerFeedbackBubble(
                text = when {
                    seekSession != null -> " ${formatPlayerTime(scrubPositionMs ?: currentPositionMs)} / ${formatPlayerTime(durationMs)}"
                    levelFeedback != null -> levelFeedback.orEmpty()
                    else -> feedbackText
                },
            )
        }

        AnimatedVisibility(
            visible = holdingSpeed,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 64.dp),
        ) {
            PlayerFeedbackBubble("2×")
        }
    }
}

@Composable
private fun PlayerFeedbackBubble(text: String?) {
    var lastNonEmptyText by remember { mutableStateOf(text.orEmpty()) }
    if (!text.isNullOrEmpty()) {
        // AnimatedVisibility retains this composable while fading out.
        SideEffect { lastNonEmptyText = text }
    }

    Surface(
        color = Color.Black.copy(alpha = 0.68f),
        contentColor = Color.White,
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = text ?: lastNonEmptyText,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 14.dp),
        )
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
    showPictureInPictureButton: Boolean,
    pictureInPictureEnabled: Boolean,
    onPictureInPicture: () -> Unit,
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
            if (showPictureInPictureButton) {
                PlayerControlButton(
                    icon = Icons.Default.PictureInPictureAlt,
                    contentDescription = stringResource(R.string.player_picture_in_picture),
                    onClick = onPictureInPicture,
                    size = 42.dp,
                    iconSize = 22.dp,
                    enabled = pictureInPictureEnabled,
                )
                Spacer(Modifier.width(10.dp))
            }
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
                icon = Icons.Default.Replay,
                contentDescription = "后退 15 秒",
                onClick = onSeekBack,
                size = 58.dp,
                iconSize = 30.dp,
                centerLabel = "15",
            )
            PlayerControlButton(
                icon = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "暂停" else "播放",
                onClick = onTogglePlayback,
                size = 74.dp,
                iconSize = 40.dp,
            )
            PlayerControlButton(
                icon = Icons.Default.Replay,
                contentDescription = "前进 15 秒",
                onClick = onSeekForward,
                size = 58.dp,
                iconSize = 30.dp,
                centerLabel = "15",
                mirrorIcon = true,
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
    centerLabel: String? = null,
    mirrorIcon: Boolean = false,
    enabled: Boolean = true,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(size)
            .shadow(12.dp, CircleShape),
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.18f),
        contentColor = Color.White.copy(alpha = if (enabled) 1f else 0.38f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier
                    .size(iconSize)
                    .scale(scaleX = if (mirrorIcon) -1f else 1f, scaleY = 1f),
            )
            if (centerLabel != null) {
                Text(
                    text = centerLabel,
                    fontSize = (iconSize.value * 0.32f).sp,
                    lineHeight = (iconSize.value * 0.32f).sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp).clearAndSetSemantics {},
                )
            }
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
