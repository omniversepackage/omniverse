package com.yodesla.omniverse.app

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.tv.material3.Text
import com.yodesla.omniverse.core.model.MimeHint
import com.yodesla.omniverse.core.model.PlaybackSpec
import com.yodesla.omniverse.core.model.Redact
import com.yodesla.omniverse.designsystem.OmniSpacing
import com.yodesla.omniverse.designsystem.OmniTheme
import com.yodesla.omniverse.player.EngineSurface
import com.yodesla.omniverse.player.Media3ExoEngine
import com.yodesla.omniverse.player.PlayerState

/**
 * DEBUG-ONLY harness (Phase 2 prep): plays the mock Xtream server from a real TV to measure
 * zap time and check the watchdog. Removed when the real Live TV screen lands (P2.4).
 */
@Composable
fun DevPlayerScreen(mockBase: String, onExit: () -> Unit) {
    val context = LocalContext.current
    val engine = remember { Media3ExoEngine(context, userAgent = "Omniverse-dev") }
    val specs = remember(mockBase) { mockSpecs(mockBase) }
    var index by remember { mutableIntStateOf(0) }
    var pressedAt by remember { mutableLongStateOf(0L) }
    var zapMs by remember { mutableLongStateOf(-1L) }
    val state by engine.state.collectAsState()
    val tracks by engine.tracks.collectAsState()
    val focus = remember { FocusRequester() }
    val view = LocalView.current

    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            engine.release()
        }
    }
    LaunchedEffect(index) {
        pressedAt = SystemClock.elapsedRealtime()
        zapMs = -1
        engine.zap(specs[index].second)
    }
    LaunchedEffect(state) {
        if (state is PlayerState.Playing && zapMs < 0) zapMs = SystemClock.elapsedRealtime() - pressedAt
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BackHandler(onBack = onExit)

    Box(
        Modifier
            .fillMaxSize()
            .background(OmniTheme.colors.background)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionUp, Key.ChannelUp -> { index = (index + 1) % specs.size; true }
                    Key.DirectionDown, Key.ChannelDown -> { index = (index - 1 + specs.size) % specs.size; true }
                    Key.DirectionRight -> { engine.seekBy(10_000); true }
                    Key.DirectionLeft -> { engine.seekBy(-10_000); true }
                    Key.DirectionCenter, Key.Enter -> {
                        if (state is PlayerState.Playing) engine.pause() else engine.resume(); true
                    }
                    else -> false
                }
            },
    ) {
        EngineSurface(engine, Modifier.fillMaxSize())
        Column(
            Modifier
                .align(Alignment.TopStart)
                .padding(horizontal = OmniSpacing.tvSide, vertical = OmniSpacing.tvTopBottom)
                .background(OmniTheme.colors.scrim)
                .padding(OmniSpacing.m),
        ) {
            val c = OmniTheme.colors
            Text(specs[index].first, style = OmniTheme.type.title, color = c.textPrimary)
            Text(stateLabel(state), style = OmniTheme.type.body, color = c.textSecondary)
            Text(
                "zap ${if (zapMs >= 0) "$zapMs ms" else "…"} · ${tracks.videoWidth}x${tracks.videoHeight}" +
                    " · audio ${tracks.audio.size} · subs ${tracks.subtitles.size}",
                style = OmniTheme.type.caption, color = c.textTertiary,
            )
            Text("Up/Down channel · Left/Right seek 10s · OK pause · BACK exit", style = OmniTheme.type.caption, color = c.textTertiary)
        }
    }
}

private fun stateLabel(s: PlayerState): String = when (s) {
    PlayerState.Idle -> "Idle"
    is PlayerState.Loading -> if (s.attempt > 1) "Reconnecting (attempt ${s.attempt})…" else "Loading…"
    is PlayerState.Playing -> "Playing"
    is PlayerState.Paused -> "Paused"
    is PlayerState.Buffering -> "Buffering…"
    is PlayerState.Ended -> "Ended"
    is PlayerState.Failed -> "Failed: ${s.kind} — ${s.detail}"
}

private fun mockSpecs(base: String): List<Pair<String, PlaybackSpec>> {
    fun spec(path: String, hint: MimeHint, live: Boolean): PlaybackSpec {
        val url = "$base$path"
        return PlaybackSpec(url = url, mimeHint = hint, isLive = live, seekable = !live, redacted = Redact.text(url))
    }
    return listOf(
        "Mock Live 1001 (TS)" to spec("/live/test/test/1001.ts", MimeHint.MPEG_TS, true),
        "Mock Live 1002 (TS)" to spec("/live/test/test/1002.ts", MimeHint.MPEG_TS, true),
        "Mock Live 1003 (HLS)" to spec("/live/test/test/1003.m3u8", MimeHint.HLS, true),
        "Mock Movie (MP4)" to spec("/movie/test/test/1001.mp4", MimeHint.MP4, false),
        "Mock Movie (MKV)" to spec("/movie/test/test/1002.mkv", MimeHint.MKV, false),
        "Wrong login (expect DENIED)" to spec("/live/test/wrong/1001.ts", MimeHint.MPEG_TS, true),
    )
}
