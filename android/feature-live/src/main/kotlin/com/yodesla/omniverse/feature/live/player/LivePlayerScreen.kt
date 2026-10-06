package com.yodesla.omniverse.feature.live.player

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.collectAsState
import com.yodesla.omniverse.player.EngineSubtitles
import com.yodesla.omniverse.designsystem.TrackOption
import com.yodesla.omniverse.designsystem.TrackPanel
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.yodesla.omniverse.core.data.KidsTimeController
import com.yodesla.omniverse.core.data.KidsTimeReason
import com.yodesla.omniverse.core.data.KidsTimeState
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.NowPlaying
import com.yodesla.omniverse.core.model.NowPlayingControls
import com.yodesla.omniverse.core.model.NowPlayingTrack
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.designsystem.OmniButton
import com.yodesla.omniverse.designsystem.OmniSpacing
import com.yodesla.omniverse.designsystem.OmniTheme
import com.yodesla.omniverse.feature.live.R
import com.yodesla.omniverse.player.EngineSurface
import com.yodesla.omniverse.player.FailureKind
import com.yodesla.omniverse.player.FrameRateMatchMode
import com.yodesla.omniverse.player.Media3ExoEngine
import com.yodesla.omniverse.player.PlayerState
import com.yodesla.omniverse.player.resolveSubtitleStyle
import com.yodesla.omniverse.player.RetryPolicy
import com.yodesla.omniverse.player.lines
import com.yodesla.omniverse.player.SleepTimer
import com.yodesla.omniverse.player.SleepTimerEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.ceil

/**
 * Full-screen live TV. Keys (Simple mode, PLAN.md §8.3):
 * Up/Down or CH± = surf · OK = banner · Back = hide banner, else exit · "Last"/0 = previous channel.
 */
@Composable
fun LivePlayerRoute(
    viewModel: LivePlayerViewModel,
    channel: ContentKey,
    categoryId: RemoteId,
    /** App-level live engine (see LiveSession): it keeps playing in the mini player after Back. */
    engine: Media3ExoEngine,
    onExit: () -> Unit,
    /** Task 91: app-level Now Playing holder (AppGraph); null in tests/dev screens. */
    nowPlaying: MutableStateFlow<NowPlaying?>? = null,
    nowPlayingControls: MutableStateFlow<NowPlayingControls?>? = null,
    /** Task 106: the app-wide Kids clock (AppGraph); null in tests/dev screens. */
    kidsTime: KidsTimeController? = null,
    /** Whether the "30 more minutes" offer can be made at all (no PIN set = no way to override). */
    kidsPinEnabled: Boolean = false,
    /** Opens the app-wide parental PIN pad. */
    onRequestKidsPin: () -> Unit = {},
) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val playerState by engine.state.collectAsStateWithLifecycle()
    val diagnostics by engine.diagnostics.collectAsStateWithLifecycle()
    // Task 106: the Kids clock for the active profile (limits + today's usage).
    val kids by (kidsTime?.state ?: flowOf(KidsTimeState())).collectAsStateWithLifecycle(initialValue = KidsTimeState())
    // Task 84j: per-profile subtitle appearance. Live has no timing control (no seek), only style.
    val subStyle by viewModel.subtitleStyle.collectAsStateWithLifecycle(initialValue = null)
    val subStyleSpec = remember(subStyle) { resolveSubtitleStyle(subStyle?.size, subStyle?.background, subStyle?.color, subStyle?.position) }
    // Task 100: per-profile frame-rate matching. Live streams are usually 50/60 Hz already, but a
    // 24p movie on a live channel gets the same treatment as VOD.
    val matchFrameRate by viewModel.matchFrameRate.collectAsStateWithLifecycle(initialValue = null)
    val frameRateMode = remember(matchFrameRate) { FrameRateMatchMode.of(matchFrameRate) }
    var showDiagnostics by remember { mutableStateOf(false) }
    val view = LocalView.current
    val context = LocalContext.current
    val focus = remember { FocusRequester() }
    // Task 77: per-session sleep timer (never persisted; it survives zaps because this composition
    // stays alive while you surf). Firing pauses the shared engine and leaves the player.
    val sleepTimer = remember { SleepTimer() }
    var sleepMenu by remember { mutableStateOf(false) }
    var sleepWarn by remember { mutableIntStateOf(0) }

    LaunchedEffect(channel) { viewModel.start(channel, categoryId) }
    LaunchedEffect(playerState) {
        val s = playerState
        if (s is PlayerState.Playing) viewModel.onPlaying()
        if (s is PlayerState.Failed) viewModel.onStreamFailed(s.kind)
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            when (val event = sleepTimer.tick()) {
                SleepTimerEvent.Fire -> { engine.pause(); onExit(); return@LaunchedEffect }
                is SleepTimerEvent.Warn -> sleepWarn = ceil(event.remainingMs / 1000.0).toInt().coerceAtLeast(1)
                SleepTimerEvent.None -> sleepWarn = 0
            }
        }
    }
    // Task 106: Kids time. Only real playing time is charged; zapping between channels keeps the
    // same session clock because this composition stays alive while you surf.
    LaunchedEffect(kidsTime) {
        if (kidsTime == null) return@LaunchedEffect
        kidsTime.reload()
        while (true) {
            delay(1_000)
            kidsTime.tick(playing = playerState is PlayerState.Playing)
        }
    }
    // At the daily limit or at bedtime the shared engine is paused and the stop card is shown; a
    // granted extension (parental PIN) resumes it. The PIN pad itself belongs to the app shell.
    var kidsWasBlocked by remember { mutableStateOf(false) }
    LaunchedEffect(kids.blocked) {
        if (kids.blocked != null) {
            if (playerState is PlayerState.Playing) engine.pause()
            kidsWasBlocked = true
        } else if (kidsWasBlocked) {
            kidsWasBlocked = false
            if (playerState is PlayerState.Paused) engine.resume()
        }
    }
    LaunchedEffect(showDiagnostics) {
        while (showDiagnostics) { engine.refreshDiagnostics(); delay(1000) }
    }
    val tracks by engine.tracks.collectAsState()
    // Task 91: while the full-screen player is open it owns the app-level Now Playing holder — a
    // read-only snapshot (channel + programme, no position: live has no seek) republished every 1 s
    // for the phone remote's card, plus audio/subtitle hooks. Seek hooks are no-ops on live TV.
    fun publishNowPlaying() {
        val np = nowPlaying ?: return
        val banner = ui.banner
        np.value = NowPlaying(
            title = banner?.nowTitle ?: banner?.name ?: "Live TV",
            posterUrl = banner?.logoUrl,
            kind = "LIVE",
            positionMs = 0L,
            durationMs = 0L,
            isPlaying = playerState is PlayerState.Playing || playerState is PlayerState.Buffering,
            isLive = true,
            channelName = banner?.name,
            audioTracks = tracks.audio.map { NowPlayingTrack(it.id, it.label) },
            selectedAudioId = tracks.audio.firstOrNull { it.selected }?.id,
            subtitleTracks = tracks.subtitles.map { NowPlayingTrack(it.id, it.label) },
            selectedSubtitleId = tracks.subtitles.firstOrNull { it.selected }?.id,
        )
    }
    val playbackControls = remember {
        object : NowPlayingControls {
            override fun seekTo(positionMs: Long) {} // live TV: no seeking
            override fun skipBy(deltaMs: Long) {}
            override fun selectAudio(trackId: String) { engine.selectAudio(trackId) }
            override fun selectSubtitle(trackId: String?) { engine.selectSubtitle(trackId) }
        }
    }
    DisposableEffect(nowPlaying, nowPlayingControls) {
        nowPlayingControls?.value = playbackControls
        publishNowPlaying()
        onDispose {
            if (nowPlayingControls?.value === playbackControls) {
                nowPlayingControls.value = null
                nowPlaying?.value = null
            }
        }
    }
    LaunchedEffect(nowPlaying) {
        while (true) {
            delay(1_000)
            publishNowPlaying()
        }
    }
    var showTracks by remember { mutableStateOf(false) }
    if (showTracks) {
        TrackPanel(
            audio = tracks.audio.map { TrackOption(it.id, it.label, it.selected) },
            subtitles = tracks.subtitles.map { TrackOption(it.id, it.label, it.selected) },
            onAudio = { engine.selectAudio(it) },
            onSubtitle = { engine.selectSubtitle(it) },
            onDismiss = { showTracks = false; runCatching { focus.requestFocus() } },
        )
    }
    if (sleepMenu) {
        val endRemaining = ui.banner?.nowEndMs?.let { it - System.currentTimeMillis() }?.takeIf { it > 0 }
        Dialog(onDismissRequest = { sleepMenu = false }) {
            Column(Modifier.width(440.dp).clip(RoundedCornerShape(16.dp)).background(OmniTheme.colors.background)
                .padding(OmniSpacing.xl), verticalArrangement = Arrangement.spacedBy(OmniSpacing.s)) {
                Text("Sleep timer", style = OmniTheme.type.title, color = OmniTheme.colors.textPrimary)
                Text("Playback stops and you leave the player when it fires.", style = OmniTheme.type.caption, color = OmniTheme.colors.textSecondary)
                OmniButton("Off", { sleepTimer.cancel(); sleepWarn = 0; sleepMenu = false })
                for (m in listOf(15, 30, 60, 90)) {
                    OmniButton("$m minutes", { sleepTimer.armMinutes(m); sleepWarn = 0; sleepMenu = false })
                }
                endRemaining?.let { remaining ->
                    OmniButton("End of this programme", { sleepTimer.armRemaining(remaining); sleepWarn = 0; sleepMenu = false })
                }
            }
        }
    }
    BackHandler { if (sleepMenu) sleepMenu = false else if (showDiagnostics) showDiagnostics = false else if (ui.bannerVisible) viewModel.hideBanner() else onExit() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionUp, Key.ChannelUp -> { viewModel.zapNext(); true }
                    Key.DirectionDown, Key.ChannelDown -> { viewModel.zapPrevious(); true }
                    Key.DirectionCenter, Key.Enter -> { if (sleepWarn > 0) { sleepTimer.cancel(); sleepWarn = 0; true } else { viewModel.showBanner(); true } }
                    // Play/Pause is otherwise unused on live TV: it opens the sleep timer (Task 77).
                    Key.MediaPlayPause -> { sleepMenu = true; true }
                    Key.Info -> { showDiagnostics = !showDiagnostics; true }
                    Key.DirectionLeft -> { showDiagnostics = !showDiagnostics; true }
                    Key.LastChannel, Key.Zero -> { viewModel.lastChannel(); true }
                    // Right (or the remote's Captions/Audio key) opens audio & subtitles.
                    Key.DirectionRight, Key.Captions, Key.MediaAudioTrack -> { showTracks = true; true }
                    else -> false
                }
            },
    ) {
        EngineSurface(engine, Modifier.fillMaxSize(), frameRateMode = frameRateMode)
        EngineSubtitles(engine, Modifier.fillMaxSize(), style = subStyleSpec)

        StatusLayer(playerState, ui.error, ui.switchTo, onRetry = { viewModel.retry() }, onSwitch = { viewModel.switchChannel(it) }, modifier = Modifier.align(Alignment.Center))
        // Task 81: while the engine's retry policy is reconnecting, keep the picture and show a
        // small chip — only a give-up earns the full-screen card.
        val loading = playerState as? PlayerState.Loading
        if (loading != null && loading.attempt > 1) {
            Text(
                context.getString(R.string.live_reconnecting, loading.attempt - 1, RetryPolicy.MAX_TRIES),
                style = OmniTheme.type.caption, color = OmniTheme.colors.textPrimary,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = OmniSpacing.l)
                    .clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.88f)).padding(horizontal = OmniSpacing.l, vertical = OmniSpacing.s),
            )
        }
        if (showDiagnostics) {
            Column(Modifier.align(Alignment.TopEnd).padding(24.dp).width(380.dp)
                .clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.86f)).padding(20.dp)) {
                Text("PLAYBACK INFO  ·  BACK TO CLOSE", style = OmniTheme.type.overline, color = OmniTheme.colors.accent)
                Spacer(Modifier.height(12.dp))
                diagnostics.lines().forEach { Text(it, style = OmniTheme.type.body, color = Color.White) }
            }
        }

        AnimatedVisibility(
            visible = ui.bannerVisible && ui.banner != null,
            enter = fadeIn(tween(OmniMotionMs)) + slideInVertically(tween(OmniMotionMs)) { it / 3 },
            exit = fadeOut(tween(OmniMotionMs)) + slideOutVertically(tween(OmniMotionMs)) { it / 3 },
            modifier = Modifier.align(Alignment.BottomStart),
        ) {
            ui.banner?.let { Banner(it) }
        }
        if (sleepWarn > 0) {
            Text(
                "Sleeping in $sleepWarn s — press OK to keep watching",
                style = OmniTheme.type.caption, color = OmniTheme.colors.textPrimary,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = OmniSpacing.l)
                    .clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.88f)).padding(horizontal = OmniSpacing.l, vertical = OmniSpacing.s),
            )
        }
        // Task 106: the 5-minute notice, then the stop card (drawn over everything).
        val kidsWarnMs = kids.warningMs
        if (kidsWarnMs != null && kids.blocked == null) {
            val kidsWarnMin = ceil(kidsWarnMs / 60_000.0).toInt().coerceAtLeast(1)
            Text(
                if (kids.warningReason == KidsTimeReason.BEDTIME) "Bedtime in $kidsWarnMin min" else "$kidsWarnMin min left today",
                style = OmniTheme.type.caption, color = OmniTheme.colors.textPrimary,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = OmniSpacing.l)
                    .clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.88f)).padding(horizontal = OmniSpacing.l, vertical = OmniSpacing.s),
            )
        }
        if (kids.blocked != null) {
            Column(
                Modifier.align(Alignment.Center).width(640.dp).clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.92f)).padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(OmniSpacing.m),
            ) {
                Text("Time is up for today", style = OmniTheme.type.title, color = OmniTheme.colors.textPrimary)
                Text(
                    if (kids.blocked == KidsTimeReason.BEDTIME) "This profile is in its bedtime window." else "This profile has used up its daily watching limit.",
                    style = OmniTheme.type.body, color = OmniTheme.colors.textSecondary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m), verticalAlignment = Alignment.CenterVertically) {
                    if (kidsPinEnabled) {
                        OmniButton("Enter PIN for 30 more minutes", onClick = onRequestKidsPin, primary = true)
                    } else {
                        Text("Ask a grown-up to set a parental PIN for more time.", style = OmniTheme.type.caption, color = OmniTheme.colors.textTertiary)
                    }
                    OmniButton("Stop watching", onClick = onExit)
                }
            }
        }
    }
}

private const val OmniMotionMs = 220

@Composable
private fun Banner(b: BannerUi) {
    val c = OmniTheme.colors
    val t = OmniTheme.type
    Box(
        Modifier
            .fillMaxWidth()
            // A tall, soft fade: the picture stays visible, the text stays readable. Task 69: the
            // fade now reaches ~85% by the first text line so secondary/tertiary rows hold ≥4.5:1
            // even over a bright frame.
            .background(Brush.verticalGradient(0f to Color.Transparent, 0.3f to c.background.copy(alpha = 0.85f), 1f to c.background.copy(alpha = 0.97f)))
            .padding(start = OmniSpacing.tvSide, end = OmniSpacing.tvSide, top = 96.dp, bottom = OmniSpacing.xl),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            // Logo on frosted glass (not a solid grey tile).
            Box(
                Modifier.size(128.dp, 72.dp).clip(RoundedCornerShape(12.dp)).background(c.glass),
                contentAlignment = Alignment.Center,
            ) {
                com.yodesla.omniverse.designsystem.LogoImage(b.logoUrl, b.name, Modifier.size(104.dp, 58.dp), plain = true)
            }
            Spacer(Modifier.width(OmniSpacing.l))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(RoundedCornerShape(4.dp)).background(c.live))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        listOfNotNull(b.number?.toString(), b.name).joinToString("  ").uppercase(),
                        style = t.overline, color = c.accent, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                // What's on first: the programme is the headline, the channel is context.
                Text(b.nowTitle ?: b.name, style = t.display, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m), verticalAlignment = Alignment.CenterVertically) {
                    b.nowTimes?.let { Text(it, style = t.numeric, color = c.textSecondary) }
                    b.nowProgress?.let { p -> com.yodesla.omniverse.designsystem.ProgressLine(p, Modifier.width(200.dp)) }
                    if (b.nowTitle == null) Text("No guide information", style = t.body, color = c.textTertiary)
                    b.nextTitle?.let {
                        Text(
                            listOfNotNull("Next", b.nextStart, it).joinToString("  \u00b7  "),
                            style = t.body, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                }
                // Task 77: the sleep timer is opened with the remote's Play/Pause key.
                Text("Play/Pause  Sleep timer", style = t.caption, color = c.textTertiary)
            }
            Spacer(Modifier.width(OmniSpacing.l))
            Text(com.yodesla.omniverse.designsystem.rememberTimeText(), style = t.numeric, color = c.textSecondary)
        }
    }
}

/**
 * Calm, honest feedback (PLAN.md §8.1 #5) — never a silent frozen frame. Task 81: only a give-up
 * (Failed / VM error) shows this card; mid-retry the caller shows a small chip instead. The card
 * carries "Try again", which re-opens the same channel with a fresh stream and resets the policy.
 */
@Composable
private fun StatusLayer(
    state: PlayerState,
    error: String?,
    switchTo: ChannelSwitchUi?,
    onRetry: () -> Unit,
    onSwitch: (ContentKey) -> Unit,
    modifier: Modifier,
) {
    val c = OmniTheme.colors
    val context = LocalContext.current
    val text: Pair<String, String?>? = when {
        error != null -> error to null
        state is PlayerState.Failed -> when (state.kind) {
            FailureKind.DENIED -> "Can't open this channel" to "The provider refused the stream — too many devices watching?"
            FailureKind.NOT_FOUND -> "Channel unavailable" to "The provider isn't broadcasting this channel right now."
            FailureKind.NETWORK -> "Connection lost" to "Check your internet, or try another channel."
            FailureKind.UNSUPPORTED -> "Unsupported stream" to "This channel uses a format this device can't play."
            FailureKind.OTHER -> "Something went wrong" to "Try another channel."
        }
        else -> null
    }
    val retryFocus = remember { FocusRequester() }
    val switchFocus = remember { FocusRequester() }
    LaunchedEffect(text?.first, switchTo?.key) {
        if (text == null) return@LaunchedEffect
        runCatching { if (switchTo != null) switchFocus.requestFocus() else retryFocus.requestFocus() }
    }
    AnimatedVisibility(visible = text != null, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Column(
            Modifier.clip(RoundedCornerShape(OmniSpacing.cardCorner)).background(c.scrim).padding(OmniSpacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text?.first ?: "", style = OmniTheme.type.headline, color = c.textPrimary)
            text?.second?.let {
                Spacer(Modifier.height(OmniSpacing.s))
                Text(it, style = OmniTheme.type.body, color = c.textSecondary)
            }
            Spacer(Modifier.height(OmniSpacing.m))
            switchTo?.let { alt ->
                OmniButton(context.getString(R.string.live_try_source, alt.name, alt.sourceName),
                    { onSwitch(alt.key) }, Modifier.focusRequester(switchFocus))
                Spacer(Modifier.height(OmniSpacing.s))
            }
            OmniButton(context.getString(R.string.live_try_again), onRetry, Modifier.focusRequester(retryFocus))
            Spacer(Modifier.height(OmniSpacing.s))
            Text("Up / Down to change channel  ·  Right  Audio & subtitles  ·  Left  Playback stats", style = OmniTheme.type.caption, color = c.textTertiary)
        }
    }
}
