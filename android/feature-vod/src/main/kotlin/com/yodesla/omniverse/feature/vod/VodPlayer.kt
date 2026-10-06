package com.yodesla.omniverse.feature.vod

import androidx.activity.compose.BackHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import androidx.compose.runtime.collectAsState
import com.yodesla.omniverse.player.EngineSubtitles
import com.yodesla.omniverse.designsystem.TrackOption
import com.yodesla.omniverse.designsystem.TrackPanel
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.yodesla.omniverse.core.data.CatalogRepository
import com.yodesla.omniverse.core.data.KidsTimeController
import com.yodesla.omniverse.core.data.KidsTimeReason
import com.yodesla.omniverse.core.data.KidsTimeState
import com.yodesla.omniverse.core.data.PosterRow
import com.yodesla.omniverse.core.data.SourceRepository
import com.yodesla.omniverse.core.data.SkipSettings
import com.yodesla.omniverse.core.data.UserDataRepository
import com.yodesla.omniverse.core.data.Visibility
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.NowPlaying
import com.yodesla.omniverse.core.model.NowPlayingControls
import com.yodesla.omniverse.core.model.NowPlayingTrack
import com.yodesla.omniverse.core.model.SkipMarker
import com.yodesla.omniverse.core.source.SourceException
import com.yodesla.omniverse.designsystem.OmniSpacing
import com.yodesla.omniverse.designsystem.OmniMotion
import com.yodesla.omniverse.designsystem.OmniTheme
import com.yodesla.omniverse.designsystem.OmniButton
import com.yodesla.omniverse.designsystem.ProgressLine
import com.yodesla.omniverse.designsystem.requestFocusWhenReady
import com.yodesla.omniverse.player.EngineSurface
import com.yodesla.omniverse.player.FailureKind
import com.yodesla.omniverse.player.Media3ExoEngine
import com.yodesla.omniverse.player.SkipSuggestion
import com.yodesla.omniverse.player.SuggestionKind
import com.yodesla.omniverse.player.SkipSuggestionMatcher
import com.yodesla.omniverse.player.SkipReference
import com.yodesla.omniverse.player.PlayerState
import com.yodesla.omniverse.player.RetryPolicy
import com.yodesla.omniverse.player.SelectedTrackRole
import com.yodesla.omniverse.player.SubtitleMode
import com.yodesla.omniverse.player.TrackPreference
import com.yodesla.omniverse.player.matchingTrackId
import com.yodesla.omniverse.player.lines
import com.yodesla.omniverse.player.SleepTimer
import com.yodesla.omniverse.player.SleepTimerEvent
import com.yodesla.omniverse.player.FRAME_RATE_MATCH_SETTING
import com.yodesla.omniverse.player.FrameRateMatchMode
import com.yodesla.omniverse.player.SUBTITLE_STYLE_BACKGROUND
import com.yodesla.omniverse.player.SUBTITLE_STYLE_COLOR
import com.yodesla.omniverse.player.SUBTITLE_STYLE_POSITION
import com.yodesla.omniverse.player.SUBTITLE_STYLE_SIZE
import com.yodesla.omniverse.player.SubtitleStyleDialog
import com.yodesla.omniverse.player.formatSubtitleDelay
import com.yodesla.omniverse.player.resolveSubtitleStyle
import com.yodesla.omniverse.player.stepSubtitleDelay
import com.yodesla.omniverse.player.subtitleStyleSummary
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlin.math.ceil

/**
 * Task 97: first Play next entry the player may offer — skips finished keys (the movie just watched,
 * or the series of the episode just watched), drops keys with no poster, and applies the current
 * profile's visibility. Pure so the JVM test can drive it without a player.
 */
internal suspend fun pickQueueOffer(
    queue: List<ContentKey>,
    finished: Set<ContentKey>,
    posterOf: suspend (ContentKey) -> PosterRow?,
    visible: Visibility?,
): Pair<ContentKey, String>? {
    for (key in queue) {
        if (key in finished) continue
        val poster = posterOf(key) ?: continue
        val ok = visible?.let { v -> poster.categoryId?.let { v(poster.key.kind.name, poster.key.sourceId.value, it.value) } ?: true } ?: true
        if (ok) return poster.key to poster.name
    }
    return null
}

/**
 * VOD / episode playback (PLAN.md §7): resume, progress saved every 10 s and on exit, ±10 s seek
 * (held keys accelerate to 30 s), and a 10-second "Up next" card for series.
 */
@Composable
fun VodPlayerRoute(
    item: PlayItem,
    sources: SourceRepository,
    userData: UserDataRepository,
    userAgent: String,
    communitySkipLookup: CommunitySkipLookup? = null,
    onPlayNext: (PlayItem) -> Unit,
    /** Task 97: end-of-item handoff to the Play next queue (movie / last episode); null disables the offer. */
    onPlayQueued: ((ContentKey) -> Unit)? = null,
    onExit: () -> Unit,
    /** Task 91: app-level Now Playing holder (AppGraph); null in tests/dev screens. */
    nowPlaying: MutableStateFlow<NowPlaying?>? = null,
    nowPlayingControls: MutableStateFlow<NowPlayingControls?>? = null,
    /** Task 91: poster art for the phone card; PlayItem itself carries only ids. */
    posterUrl: String? = null,
    /** Task 93: catalog + current-profile visibility for the "Try another copy" fallback; null disables it. */
    catalog: CatalogRepository? = null,
    visibility: Flow<Visibility>? = null,
    /** Task 106: the app-wide Kids clock (AppGraph); null in tests and dev screens. */
    kidsTime: KidsTimeController? = null,
    /** Whether the "30 more minutes" offer can be made at all (no PIN set = no way to override). */
    kidsPinEnabled: Boolean = false,
    /** Opens the app-wide parental PIN pad. */
    onRequestKidsPin: () -> Unit = {},
) {
    val context = LocalContext.current
    val engine = remember { Media3ExoEngine(context, userAgent) }
    val state by engine.state.collectAsStateWithLifecycle()
    val diagnostics by engine.diagnostics.collectAsStateWithLifecycle()
    // Task 106: the Kids clock for the active profile (limits + today's usage). Empty when no clock.
    val kids by (kidsTime?.state ?: flowOf(KidsTimeState())).collectAsStateWithLifecycle(initialValue = KidsTimeState())
    var showDiagnostics by remember { mutableStateOf(false) }
    val view = LocalView.current
    val focus = remember { FocusRequester() }
    val nextFocus = remember { FocusRequester() }
    val skipFocus = remember { FocusRequester() }
    val skipEditorFocus = remember { FocusRequester() }
    val skipEditorFirstFocus = remember { FocusRequester() }
    val suggestionFocus = remember { FocusRequester() }
    var nextButtonFocused by remember { mutableStateOf(false) }
    var skipButtonFocused by remember { mutableStateOf(false) }
    var skipEditorButtonFocused by remember { mutableStateOf(false) }
    var pictureButtonFocused by remember { mutableStateOf(false) }
    var suggestionButtonFocused by remember { mutableStateOf(false) }
    var overlay by remember { mutableStateOf(true) }
    var lastInput by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var position by remember { mutableLongStateOf(item.resumeMs) }
    var duration by remember { mutableLongStateOf(0L) }
    val effectiveDuration = duration.takeIf { it > 0 } ?: item.durationMs?.takeIf { it > 0 } ?: 0L
    // M3: the item's final progress is decided once (before navigating) and every later teardown
    // save becomes a no-op, so a stale engine position can never overwrite a completion.
    val completion = remember(item.key) { CompletionGuard() }
    var error by remember { mutableStateOf<String?>(null) }
    var upNextIn by remember { mutableIntStateOf(-1) }
    // Task 97: head of the Play next queue offered when the finished item has no next episode.
    var queuedOffer by remember(item.key) { mutableStateOf<Pair<ContentKey, String>?>(null) }
    // Task 93: after the refresh retries are spent the player offers the other copies of this
    // title. Exactly one alternative counts down automatically; several open a chooser panel.
    var fallback by remember(item.key) { mutableStateOf<List<FallbackChoice>?>(null) }
    var fallbackLoaded by remember(item.key) { mutableStateOf(false) }
    var fallbackCountdown by remember(item.key) { mutableIntStateOf(-1) }
    var fallbackCancelled by remember(item.key) { mutableStateOf(false) }
    var fallbackPanel by remember(item.key) { mutableStateOf(false) }
    var switchingCopy by remember(item.key) { mutableStateOf(false) }
    val fallbackScope = rememberCoroutineScope()
    val fallbackFocus = remember { FocusRequester() }
    var fallbackButtonFocused by remember { mutableStateOf(false) }
    var showSkipEditor by remember { mutableStateOf(false) }
    val editScope = rememberCoroutineScope()
    val localSkip by userData.localSkipPoints(item.key).collectAsStateWithLifecycle(initialValue = emptyMap())
    val introMode by userData.setting(SkipSettings.INTRO_MODE).collectAsStateWithLifecycle(initialValue = null)
    val creditsMode by userData.setting(SkipSettings.CREDITS_MODE).collectAsStateWithLifecycle(initialValue = null)
    // Picture mode (Fit / Stretch / Zoom), remembered per show (or per movie) like Plex's display setting.
    val pictureKey = remember(item.key, item.parentId) { pictureModeKey(item.key, item.parentId) }
    val pictureMode by userData.setting(pictureKey).collectAsStateWithLifecycle(initialValue = null)
    val pictureScope = rememberCoroutineScope()
    // Audio / subtitle tracks, remembered per show (its episodes share it) or per movie, like picture mode.
    val trackPrefKey = remember(item.key, item.parentId) { tracksKey(item.key, item.parentId) }
    val trackPrefRaw by userData.setting(trackPrefKey).collectAsStateWithLifecycle(initialValue = null)
    val trackPref = remember(trackPrefRaw) { TrackPreference.decode(trackPrefRaw) }
    val trackScope = rememberCoroutineScope()
    // Task 77: per-session sleep timer (never persisted; every new playback starts at Off).
    val sleepTimer = remember { SleepTimer() }
    var sleepMenu by remember { mutableStateOf(false) }
    var sleepWarn by remember { mutableIntStateOf(0) }
    val sleepOffChoice = stringResource(R.string.sleep_timer_choice_off)
    var sleepChoice by remember { mutableStateOf(sleepOffChoice) }
    var sleepAtEnd by remember(item.key) { mutableStateOf(false) }
    var sleepButtonFocused by remember { mutableStateOf(false) }
    val isEpisode = item.key.kind == ContentKind.EPISODE
    val sleepEndLabel = stringResource(if (isEpisode) R.string.sleep_timer_end_of_episode else R.string.sleep_timer_end_of_movie)
    val sleepEndChoice = stringResource(if (isEpisode) R.string.sleep_timer_choice_end_of_episode else R.string.sleep_timer_choice_end_of_movie)
    val matchFrameRate by userData.setting(FRAME_RATE_MATCH_SETTING).collectAsStateWithLifecycle(initialValue = null)
    val frameRateMode = remember(matchFrameRate) { FrameRateMatchMode.of(matchFrameRate) }
    // Task 95: spoiler-free mode (Settings › Playback, per profile) — the next-episode card must not
    // spoil the title while protection is on.
    val spoilerFreeRaw by userData.setting(UserDataRepository.SPOILER_FREE).collectAsStateWithLifecycle(initialValue = null)
    val communityLookupEnabled by userData.setting(SkipSettings.COMMUNITY_LOOKUP)
        .collectAsStateWithLifecycle(initialValue = "false")
    val localAnalysisEnabled by userData.setting(SkipSettings.LOCAL_ANALYSIS)
        .collectAsStateWithLifecycle(initialValue = "false")
    var localSuggestion by remember(item.key) { mutableStateOf<SkipSuggestion?>(null) }
    var introAnalysisAttempted by remember(item.key) { mutableStateOf(false) }
    var creditsAnalysisAttempted by remember(item.key) { mutableStateOf(false) }
    val seriesKey = item.parentId?.takeIf { item.key.kind == ContentKind.EPISODE }
        ?.let { ContentKey(item.key.sourceId, ContentKind.SERIES, it) }
    val seriesSkip by (seriesKey?.let(userData::localSkipPoints) ?: flowOf(emptyMap()))
        .collectAsStateWithLifecycle(initialValue = emptyMap())

    fun save() {
        // Once the final state is decided (credits skip / natural end), a teardown save using the
        // old engine position must not run: it would reset completed and restore the old position.
        if (!completion.allowTeardownSave()) return
        val pos = engine.positionMs
        val dur = engine.durationMs
        if (item.trackProgress && pos > 5_000) heartbeatScope.launch { userData.saveProgress(item.key, item.parentId, pos, dur) }
    }
    // Session heartbeat (Plex): tells the server we're still here, so it doesn't kill the stream.
    fun heartbeat(playerState: String) {
        // After a completion is decided, report that completed state, not the pre-skip position.
        val (pos, dur) = completion.heartbeatPosition(engine.positionMs, engine.durationMs)
        // Detached scope so "stopped" still goes out while the screen closes.
        heartbeatScope.launch {
            sources.contentSource(item.key.sourceId)?.reportPlayback(item.request, playerState, pos, dur)
        }
    }
    // Decide the completed state once and write it before navigating; later teardown saves no-op.
    fun finalizeCompleted() {
        if (completion.isDecided) return
        val final = completion.decideCompleted(engine.positionMs, engine.durationMs, effectiveDuration.takeIf { it > 0 })
        if (!item.trackProgress) return
        heartbeatScope.launch {
            if (final.durationMs != null) userData.saveProgress(item.key, item.parentId, final.positionMs, final.durationMs)
            else userData.setWatched(item.key, item.parentId, true, null)
        }
    }

    // Task 77: back to Off — also what "press OK to keep watching" does.
    fun cancelSleep() { sleepTimer.cancel(); sleepAtEnd = false; sleepChoice = sleepOffChoice; sleepWarn = 0 }

    DisposableEffect(item) {
        view.keepScreenOn = true
        onDispose {
            save()
            heartbeat("stopped")
            view.keepScreenOn = false
            engine.release()
        }
    }
    // Background (Home, another app): save, drop the stream (frees the provider connection slot),
    // and resume from the same spot when the app comes back.
    var openedSpec by remember { mutableStateOf<com.yodesla.omniverse.core.model.PlaybackSpec?>(null) }
    var analysisStreamKey by remember { mutableStateOf<ContentKey?>(null) }
    var communityMarkers by remember(item.key) { mutableStateOf(emptyList<SkipMarker>()) }
    var communityLookupDone by remember(item.key) { mutableStateOf(false) }
    var communityLookupRunning by remember(item.key) { mutableStateOf(false) }
    var resumeAt by remember { mutableStateOf(-1L) }
    LifecycleStartEffect(item) {
        if (resumeAt >= 0) {
            openedSpec?.let { analysisStreamKey = item.key; engine.play(it, startPositionMs = resumeAt) }
            resumeAt = -1L
        }
        onStopOrDispose {
            if (openedSpec != null) {
                resumeAt = engine.positionMs
                save()
                heartbeat("stopped")
                analysisStreamKey = null
                engine.stop()
            }
        }
    }
    // Viewer's default audio/subtitle language (Settings › Playback), applied before the stream opens.
    val audioLang by userData.setting(PREF_AUDIO_LANGUAGE).collectAsStateWithLifecycle(initialValue = null)
    val subtitlePref by userData.setting(PREF_SUBTITLES).collectAsStateWithLifecycle(initialValue = null)
    // Task 84j: per-profile subtitle appearance (Settings › Playback) + a per-session delay that
    // resets each time a new video opens. The delay is never persisted.
    val subSize by userData.setting(SUBTITLE_STYLE_SIZE).collectAsStateWithLifecycle(initialValue = null)
    val subBg by userData.setting(SUBTITLE_STYLE_BACKGROUND).collectAsStateWithLifecycle(initialValue = null)
    val subColor by userData.setting(SUBTITLE_STYLE_COLOR).collectAsStateWithLifecycle(initialValue = null)
    val subPos by userData.setting(SUBTITLE_STYLE_POSITION).collectAsStateWithLifecycle(initialValue = null)
    val subStyleSpec = remember(subSize, subBg, subColor, subPos) { resolveSubtitleStyle(subSize, subBg, subColor, subPos) }
    val subStyleSummary = remember(subSize, subBg, subColor, subPos) { subtitleStyleSummary(subSize, subBg, subColor, subPos) }
    var subtitleDelayMs by remember(item.key) { mutableStateOf(0L) }
    var showStyleDialog by remember { mutableStateOf(false) }
    // With no per-show memory the global default owns selection. When this show has one, the tracks
    // effect below owns it (it also applies the global default for any part the remembered language
    // can't cover), so the two must not fight over the same parameters.
    LaunchedEffect(audioLang, subtitlePref, trackPref) {
        if (!trackPref.isEmpty) return@LaunchedEffect
        engine.setLanguagePreferences(audioLang?.takeIf { it.isNotBlank() }, subtitlePref)
    }
    LaunchedEffect(item) {
        val source = sources.contentSource(item.key.sourceId)
        if (source == null) { error = "This source was removed."; return@LaunchedEffect }
        try {
            val spec = source.playback(item.request)
            openedSpec = spec
            analysisStreamKey = item.key
            engine.play(spec, startPositionMs = item.resumeMs)
        } catch (e: SourceException) {
            error = "This title can't be played right now."
        }
    }
    // Position ticker + periodic progress save.
    LaunchedEffect(item) {
        var tick = 0
        while (true) {
            delay(500)
            position = engine.positionMs
            duration = engine.durationMs ?: 0L
            if (++tick % 20 == 0) {
                save()
                if (openedSpec != null) heartbeat(if (state is PlayerState.Paused) "paused" else "playing")
            }
            if (overlay && localSuggestion == null && !nextButtonFocused && !skipButtonFocused && !skipEditorButtonFocused && !suggestionButtonFocused && !pictureButtonFocused && !sleepButtonFocused && System.currentTimeMillis() - lastInput > 4_000 && state is PlayerState.Playing) overlay = false
        }
    }
    // Sleep-timer tick (Task 77): warn 60 s before firing, leave the player when it fires. The
    // "end of this episode" choice is measured against the real stream position, not a fixed clock.
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            when (val event = sleepTimer.tick()) {
                SleepTimerEvent.Fire -> { onExit(); return@LaunchedEffect }
                is SleepTimerEvent.Warn -> sleepWarn = ceil(event.remainingMs / 1000.0).toInt().coerceAtLeast(1)
                SleepTimerEvent.None -> {
                    val dur = duration.takeIf { it > 0 } ?: item.durationMs?.takeIf { it > 0 } ?: 0L
                    val endRemaining = if (sleepAtEnd && dur > 0 && state is PlayerState.Playing) dur - position else Long.MAX_VALUE
                    sleepWarn = if (endRemaining <= SleepTimer.WARNING_MS) ceil(endRemaining / 1000.0).toInt().coerceAtLeast(1) else 0
                }
            }
        }
    }
    // Task 106: Kids time. The clock is charged only while the engine is actually playing, so
    // paused or buffering time never spends a child's allowance.
    LaunchedEffect(kidsTime) {
        if (kidsTime == null) return@LaunchedEffect
        kidsTime.reload()
        while (true) {
            delay(1_000)
            kidsTime.tick(playing = state is PlayerState.Playing)
        }
    }
    // At the daily limit or at bedtime the player pauses itself and shows the "Time is up" card;
    // a granted extension (parental PIN) resumes it. The PIN pad itself belongs to the app shell.
    var kidsWasBlocked by remember { mutableStateOf(false) }
    LaunchedEffect(kids.blocked) {
        if (kids.blocked != null) {
            if (state is PlayerState.Playing) engine.pause()
            kidsWasBlocked = true
            overlay = true
        } else if (kidsWasBlocked) {
            kidsWasBlocked = false
            if (state is PlayerState.Paused) engine.resume()
        }
    }
    // Task 81 give-up path: the engine's retry policy exhausted its same-URL reconnects (or the
    // server refused the session: instant 503s). Retrying the same URL can't work, so ask the
    // source for a fresh one and resume where we were (twice max; play() resets the policy).
    // Task 93: once those retries are spent (or the failure was never retryable), offer the
    // other copies of the same title instead of a dead end.
    var refreshes by remember { mutableIntStateOf(0) }
    suspend fun offerFallbackCopies() {
        if (fallbackLoaded || catalog == null || visibility == null || switchingCopy) return
        fallbackLoaded = true
        val allowed = runCatching { visibility.first() }.getOrNull() ?: return
        val choices = runCatching { loadFallbackChoices(item, sources, userData, catalog, allowed) }
            .getOrDefault(emptyList())
        if (choices.isEmpty()) return
        fallback = choices
        if (choices.size >= 2) fallbackPanel = true
    }
    suspend fun switchToFallback(choice: FallbackChoice) {
        if (switchingCopy) return
        switchingCopy = true
        fallbackCountdown = -1
        val at = engine.positionMs.takeIf { it > 0 } ?: position
        val from = duration.takeIf { it > 0 } ?: item.durationMs
        val next = runCatching { resolveFallbackItem(choice, item, at, from, sources, userData) }.getOrNull()
        if (next != null) {
            onPlayNext(next)
        } else {
            error = "That copy can't be played right now."
            switchingCopy = false
            fallbackPanel = true
        }
    }
    LaunchedEffect(state) {
        val failed = state as? PlayerState.Failed ?: return@LaunchedEffect
        if (failed.kind == FailureKind.NETWORK && refreshes < 2) {
            val source = sources.contentSource(item.key.sourceId) ?: return@LaunchedEffect
            refreshes++
            val at = engine.positionMs.takeIf { it > 0 } ?: position
            try {
                val spec = source.playback(item.request)
                openedSpec = spec
                analysisStreamKey = item.key
                engine.play(spec, startPositionMs = at)
            } catch (_: SourceException) {
                offerFallbackCopies()
            }
            return@LaunchedEffect
        }
        offerFallbackCopies()
    }
    LaunchedEffect(state) {
        if (state is PlayerState.Playing) {
            refreshes = 0
            fallbackLoaded = false
            fallback = null
            fallbackPanel = false
            fallbackCancelled = false
            fallbackCountdown = -1
            switchingCopy = false
        }
    }
    // Exactly one alternative: switch on a 5-second countdown the viewer can cancel.
    LaunchedEffect(fallback) {
        val only = fallback?.singleOrNull() ?: return@LaunchedEffect
        for (s in FALLBACK_COUNTDOWN_S downTo 1) {
            if (fallbackCancelled || fallbackPanel) return@LaunchedEffect
            fallbackCountdown = s
            delay(1_000)
        }
        if (!fallbackCancelled && !fallbackPanel) switchToFallback(only)
    }
    LaunchedEffect(fallbackCountdown) {
        if (fallbackCountdown > 0) runCatching { fallbackFocus.requestFocusWhenReady() }
    }
    LaunchedEffect(fallbackPanel) {
        if (fallbackPanel) runCatching { fallbackFocus.requestFocusWhenReady() }
    }

    // Key this to playback readiness, not the mutable completion guard or every duration tick.
    // Flipping communityLookupDone when a request starts must not cancel that same request.
    LaunchedEffect(item.key, communityLookupEnabled, introMode, creditsMode,
        state is PlayerState.Playing, openedSpec != null) {
        if (communityLookupEnabled != "true" || (introMode == SkipSettings.OFF && creditsMode == SkipSettings.OFF) ||
            communityLookupDone || state !is PlayerState.Playing || openedSpec == null) return@LaunchedEffect
        val tmdbId = item.tmdbId?.takeIf { it.isNotBlank() }.orEmpty()
        if (tmdbId.isEmpty() && (item.season == null || item.episode == null || item.seriesLookupTitle.isNullOrBlank())) {
            return@LaunchedEffect
        }
        communityLookupDone = true
        communityLookupRunning = true
        val runtimeMs = effectiveDuration.takeIf { it > 0 }
        communityMarkers = try {
            communitySkipLookup?.lookup(CommunitySkipQuery(tmdbId, item.season, item.episode, runtimeMs,
                item.seriesLookupTitle, item.seriesYear)).orEmpty()
        } catch (cancelled: CancellationException) {
            communityLookupDone = false
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        } finally {
            communityLookupRunning = false
        }
    }
    LaunchedEffect(localAnalysisEnabled) {
        engine.setSkipAnalysisEnabled(localAnalysisEnabled == "true")
    }
    LaunchedEffect(item.key, analysisStreamKey, localAnalysisEnabled, duration, state) {
        if (localAnalysisEnabled != "true" || localSuggestion != null || analysisStreamKey != item.key || state !is PlayerState.Playing) return@LaunchedEffect
        engine.skipAnalysisRevision.collect {
            if (localSuggestion != null) return@collect
            val lastPosition = engine.skipAnalysisLatestPositionMs() ?: return@collect
            val introWindowEnd = minOf(240_000L, duration.takeIf { it > 0 } ?: 240_000L)
            val candidate = when {
                !introAnalysisAttempted && lastPosition >= introWindowEnd - 5_000L -> {
                    introAnalysisAttempted = true
                    val frames = engine.skipAnalysisSnapshot()
                    val reference = SkipReference(
                        confirmedIntroEndMs = localSkip["intro_end"] ?: seriesSkip["intro_end"],
                        confirmedCreditsStartMs = localSkip["credits_start"]
                            ?: seriesSkip["credits_remaining"]?.let { creditsStartFromRemaining(effectiveDuration, it) },
                    )
                    withContext(Dispatchers.Default) {
                        SkipSuggestionMatcher().match(frames, duration.takeIf { it > 0 }, reference).intro
                    }
                }
                !creditsAnalysisAttempted && duration > 0 && lastPosition >= duration - 5_000L -> {
                    creditsAnalysisAttempted = true
                    val frames = engine.skipAnalysisSnapshot()
                    val reference = SkipReference(
                        confirmedIntroEndMs = localSkip["intro_end"] ?: seriesSkip["intro_end"],
                        confirmedCreditsStartMs = localSkip["credits_start"]
                            ?: seriesSkip["credits_remaining"]?.let { creditsStartFromRemaining(effectiveDuration, it) },
                    )
                    withContext(Dispatchers.Default) {
                        SkipSuggestionMatcher().match(frames, effectiveDuration, reference).credits
                    }
                }
                else -> null
            }
            if (candidate != null) {
                localSuggestion = candidate
                overlay = true
                lastInput = System.currentTimeMillis()
            }
        }
    }
    // Task 97: the finished item has no next episode → offer the first queued title that still
    // resolves to a poster and passes the current profile's visibility, skipping the title just watched.
    suspend fun queueOffer(): Pair<ContentKey, String>? {
        val cat = catalog ?: return null
        val vis = runCatching { visibility?.first() }.getOrNull()
        val queue = runCatching { userData.playNext().first() }.getOrNull().orEmpty()
        val finished = buildSet {
            add(item.key)
            item.parentId?.let { add(ContentKey(item.key.sourceId, ContentKind.SERIES, it)) }
        }
        return pickQueueOffer(queue, finished, { k -> runCatching { cat.poster(k) }.getOrNull() }, vis)
    }
    // End of item → "Up next" countdown for series, else the Play next queue offer, else exit. With the
    // sleep timer set to "end of this episode/movie" (Task 77), finishing exits instead of auto-playing.
    LaunchedEffect(state) {
        if (state is PlayerState.Ended) {
            finalizeCompleted() // decide once; the countdown's periodic saves and the exit save no-op
            if (sleepAtEnd) return@LaunchedEffect onExit()
            // Task 111 H1: a blocked child never auto-advances into the next episode.
            if (kids.blocked != null) return@LaunchedEffect onExit()
            val next = item.next
            if (next != null) {
                for (s in 10 downTo 1) { upNextIn = s; delay(1_000) }
                onPlayNext(next)
            } else {
                val offer = if (onPlayQueued != null) queueOffer() else null
                if (offer == null) return@LaunchedEffect onExit()
                queuedOffer = offer
                for (s in 10 downTo 1) { upNextIn = s; delay(1_000) }
                onPlayQueued?.invoke(offer.first)
            }
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(showDiagnostics) {
        while (showDiagnostics) { engine.refreshDiagnostics(); delay(1000) }
    }
    LaunchedEffect(localSuggestion?.positionMs) {
        if (localSuggestion != null) runCatching { suggestionFocus.requestFocusWhenReady() }
    }
    val tracks by engine.tracks.collectAsState()
    // Task 91: while this player is open it owns the app-level Now Playing holder — a read-only
    // snapshot republished every 1 s for the phone remote's card, plus control hooks the shell
    // routes phone seek/skip/track requests to. Teardown clears them (only if we still own them,
    // so an auto-advanced player replacing this one is never wiped).
    fun publishNowPlaying() {
        val np = nowPlaying ?: return
        val audioLabels = dedupeLabels(tracks.audio.map { trackLabel(it.language, it.label, it.forcedOnly, true) })
        val subtitleLabels = dedupeLabels(tracks.subtitles.map { trackLabel(it.language, it.label, it.forcedOnly, false) })
        np.value = NowPlaying(
            title = item.title,
            posterUrl = posterUrl,
            kind = item.key.kind.name,
            positionMs = engine.positionMs,
            durationMs = engine.durationMs ?: effectiveDuration,
            isPlaying = state is PlayerState.Playing || state is PlayerState.Buffering,
            isLive = false,
            channelName = null,
            audioTracks = tracks.audio.mapIndexed { i, t -> NowPlayingTrack(t.id, audioLabels[i]) },
            selectedAudioId = tracks.audio.firstOrNull { it.selected }?.id,
            subtitleTracks = tracks.subtitles.mapIndexed { i, t -> NowPlayingTrack(t.id, subtitleLabels[i]) },
            selectedSubtitleId = tracks.subtitles.firstOrNull { it.selected }?.id,
        )
    }
    val playbackControls = remember {
        object : NowPlayingControls {
            override fun seekTo(positionMs: Long) = engine.seekTo(positionMs)
            override fun skipBy(deltaMs: Long) = engine.seekBy(deltaMs)
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
    LaunchedEffect(item) {
        while (true) {
            delay(1_000)
            publishNowPlaying()
        }
    }
    // Apply this show's remembered tracks once they are known: match by language + role, never by index,
    // and fall back to the viewer's global default for any part whose language is absent. Once per item,
    // so a manual pick in the panel is not immediately overwritten by this effect.
    var audioTracksApplied by remember(item.key) { mutableStateOf(false) }
    var subtitleTracksApplied by remember(item.key) { mutableStateOf(false) }
    LaunchedEffect(item.key, trackPref, tracks) {
        if (trackPref.isEmpty) return@LaunchedEffect
        val audio = tracks.audio
        val subs = tracks.subtitles
        val globalAudio = audioLang?.takeIf { it.isNotBlank() }
        if (!audioTracksApplied && audio.isNotEmpty()) {
            audioTracksApplied = true
            val remembered = trackPref.audioLanguage
            val effective = if (remembered != null && matchingTrackId(audio, remembered, null) != null) remembered else audioLang
            engine.preferAudioLanguage(effective?.takeIf { it.isNotBlank() })
        }
        if (!subtitleTracksApplied && subs.isNotEmpty()) {
            subtitleTracksApplied = true
            when (trackPref.subtitleMode) {
                SubtitleMode.OFF -> engine.selectSubtitle(null)
                SubtitleMode.FORCED -> {
                    val id = matchingTrackId(subs, trackPref.subtitleLanguage, true)
                    if (id != null) engine.selectSubtitle(id) else engine.applySubtitleDefault(subtitlePref, globalAudio)
                }
                SubtitleMode.FULL -> {
                    val id = matchingTrackId(subs, trackPref.subtitleLanguage, false)
                    if (id != null) engine.selectSubtitle(id) else engine.applySubtitleDefault(subtitlePref, globalAudio)
                }
                null -> engine.applySubtitleDefault(subtitlePref, globalAudio)
            }
        }
    }
    // Task 100: the refresh-rate request lives in EngineSurface (it owns the video surface); this
    // screen only decides the mode from the profile's setting.
    val localMarkers = buildList {
        (localSkip["intro_end"] ?: seriesSkip["intro_end"])?.takeIf { it > 5_000 && (effectiveDuration == 0L || it < effectiveDuration) }
            ?.let { add(SkipMarker("intro", 0, it)) }
        (localSkip["credits_start"] ?: seriesSkip["credits_remaining"]?.let { creditsStartFromRemaining(effectiveDuration, it) })
            ?.takeIf { effectiveDuration > 0 && it in 5_000 until effectiveDuration }
            ?.let { add(SkipMarker("credits", it, effectiveDuration)) }
    }
    val activeLocalSkip = localMarkers.firstOrNull {
        isUsableSkipMarker(it, effectiveDuration) && position >= it.startMs && position < it.endMs
    }
    val activeSourceSkip = openedSpec?.skipMarkers.orEmpty().map { fitToDuration(it, effectiveDuration) }.firstOrNull {
        isUsableSkipMarker(it, effectiveDuration) && position >= it.startMs && position < it.endMs
    }
    val activeCommunitySkip = communityMarkers.map { fitToDuration(it, effectiveDuration) }.firstOrNull {
        isUsableSkipMarker(it, effectiveDuration) && position >= it.startMs && position < it.endMs
    }
    val activeSkip = activeLocalSkip ?: activeSourceSkip ?: activeCommunitySkip
    val activeSkipMode = skipModeForType(activeSkip?.type, introMode, creditsMode)
    var autoSkippedMarkers by remember(item.key) { mutableStateOf(emptySet<String>()) }
    // Skipping end credits on a series goes straight to the next episode (Netflix-style) instead of
    // seeking to the very end and waiting out the Up-next countdown.
    val skipTo: (SkipMarker) -> Unit = { marker ->
        val next = item.next
        if (marker.type == "credits" && next != null) {
            // Decide + write the completion before navigating; the disposal save that navigation
            // triggers is then a no-op, so it can't overwrite it with the pre-skip position.
            finalizeCompleted()
            onPlayNext(next)
        } else engine.seekTo(marker.endMs)
    }
    LaunchedEffect(activeSkip?.type, activeSkipMode) {
        if (activeSkip != null && activeSkipMode == SkipSettings.BUTTON) overlay = true
    }
    LaunchedEffect(activeSkip?.type, activeSkip?.startMs, activeSkip?.endMs, activeSkipMode, state, position) {
        val marker = activeSkip ?: return@LaunchedEffect
        val target = autoSkipTarget(marker, activeSkipMode, position,
            state is PlayerState.Playing && openedSpec != null, autoSkippedMarkers, effectiveDuration)
        if (target != null) {
            autoSkippedMarkers = autoSkippedMarkers + markerToken(marker)
            skipTo(marker.copy(endMs = target))
            overlay = false
        }
    }
    var showTracks by remember { mutableStateOf(false) }
    if (showTracks) {
        val audioLabels = dedupeLabels(tracks.audio.map { trackLabel(it.language, it.label, it.forcedOnly, true) })
        val subtitleLabels = dedupeLabels(tracks.subtitles.map { trackLabel(it.language, it.label, it.forcedOnly, false) })
        TrackPanel(
            audio = tracks.audio.mapIndexed { i, t -> TrackOption(t.id, audioLabels[i], t.selected) },
            subtitles = tracks.subtitles.mapIndexed { i, t -> TrackOption(t.id, subtitleLabels[i], t.selected) },
            onAudio = { id ->
                engine.selectAudio(id)
                val language = engine.selectedAudioLanguage() ?: tracks.audio.firstOrNull { it.id == id }?.language
                val remembered = trackPref.copy(audioLanguage = language)
                trackScope.launch { userData.putSetting(trackPrefKey, remembered.encode()) }
            },
            onSubtitle = { id ->
                engine.selectSubtitle(id)
                val remembered = if (id == null) {
                    trackPref.copy(subtitleMode = SubtitleMode.OFF, subtitleLanguage = null)
                } else {
                    val picked = tracks.subtitles.firstOrNull { it.id == id }
                    val role = engine.selectedSubtitle() ?: picked?.let { SelectedTrackRole(it.language, it.forcedOnly) }
                    val mode = if (role?.forcedOnly == true) SubtitleMode.FORCED else SubtitleMode.FULL
                    trackPref.copy(subtitleMode = mode, subtitleLanguage = role?.language)
                }
                trackScope.launch { userData.putSetting(trackPrefKey, remembered.encode()) }
            },
            onDismiss = { showTracks = false; runCatching { focus.requestFocus() } },
            subtitleStyleSummary = subStyleSummary,
            onOpenSubtitleStyle = { showTracks = false; showStyleDialog = true },
            subtitleDelayLabel = formatSubtitleDelay(subtitleDelayMs),
            onSubtitleDelay = { dir -> subtitleDelayMs = stepSubtitleDelay(subtitleDelayMs, dir) },
        )
    }
    if (showStyleDialog) {
        SubtitleStyleDialog(
            size = subSize,
            background = subBg,
            color = subColor,
            position = subPos,
            onChange = { key, value -> trackScope.launch { userData.putSetting(key, value) } },
            onDismiss = { showStyleDialog = false; runCatching { focus.requestFocus() } },
        )
    }
    if (showSkipEditor) {
        LaunchedEffect(Unit) { skipEditorFirstFocus.requestFocusWhenReady() }
        Dialog(onDismissRequest = { showSkipEditor = false }) {
            Column(Modifier.width(480.dp).heightIn(max = 640.dp).clip(RoundedCornerShape(16.dp))
                .background(OmniTheme.colors.background).verticalScroll(rememberScrollState()).padding(OmniSpacing.xl)) {
                Text("Skip points for this video", style = OmniTheme.type.title, color = OmniTheme.colors.textPrimary)
                val onlineStatus = when {
                    communityLookupEnabled != "true" -> "Online lookup is off in Settings."
                    item.season == null || item.episode == null -> "This episode has no season/episode number for online skip points."
                    item.tmdbId.isNullOrBlank() && item.seriesLookupTitle.isNullOrBlank() -> "This source has no title or catalog ID for online skip points."
                    communityLookupRunning -> "Checking online skip points for this episode."
                    communityMarkers.isNotEmpty() -> "Online skip points loaded: ${communityMarkers.joinToString { it.type }}."
                    communityLookupDone -> "No usable online skip points loaded."
                    item.tmdbId.isNullOrBlank() -> "Checking the series title for online skip points."
                    else -> "Waiting for playback to check online skip points."
                }
                Text(onlineStatus, style = OmniTheme.type.body, color = OmniTheme.colors.textSecondary)
                Text("Save the current position; only this item and profile are affected.",
                    style = OmniTheme.type.body, color = OmniTheme.colors.textSecondary)
                Spacer(Modifier.height(OmniSpacing.m))
                OmniButton("Set intro end here · ${fmt(position)}", {
                    if (position >= 5_000 && (duration == 0L || position < duration - 5_000)) {
                        editScope.launch { userData.setLocalSkipPoint(item.key, "intro_end", position) }
                        showSkipEditor = false
                    }
                }, Modifier.focusRequester(skipEditorFirstFocus))
                localSkip["intro_end"]?.let { OmniButton("Clear intro point · ${fmt(it)}", {
                    editScope.launch { userData.setLocalSkipPoint(item.key, "intro_end", null) }; showSkipEditor = false
                }) }
                if (seriesKey != null) {
                    OmniButton("Use this intro point for the series", {
                        if (position >= 5_000 && (duration == 0L || position < duration - 5_000)) {
                            editScope.launch { userData.setLocalSkipPoint(seriesKey, "intro_end", position) }
                            showSkipEditor = false
                        }
                    })
                    seriesSkip["intro_end"]?.let { OmniButton("Clear series intro · ${fmt(it)}", {
                        editScope.launch { userData.setLocalSkipPoint(seriesKey, "intro_end", null) }; showSkipEditor = false
                    }) }
                }
                if (duration > 0) {
                    OmniButton("Set credits start here · ${fmt(position)}", {
                        if (position >= 5_000 && position < duration - 5_000) {
                            editScope.launch { userData.setLocalSkipPoint(item.key, "credits_start", position) }
                            showSkipEditor = false
                        }
                    })
                }
                localSkip["credits_start"]?.let { OmniButton("Clear credits point · ${fmt(it)}", {
                    editScope.launch { userData.setLocalSkipPoint(item.key, "credits_start", null) }; showSkipEditor = false
                }) }
                if (seriesKey != null && effectiveDuration > 0) {
                    OmniButton("Use time-before-end credits point for series", {
                        if (position >= 5_000 && position < effectiveDuration - 5_000) {
                            editScope.launch { userData.setLocalSkipPoint(seriesKey, "credits_remaining", effectiveDuration - position) }
                            showSkipEditor = false
                        }
                    })
                    seriesSkip["credits_remaining"]?.let { OmniButton("Clear series credits · ${fmt(it)} before end", {
                        editScope.launch { userData.setLocalSkipPoint(seriesKey, "credits_remaining", null) }; showSkipEditor = false
                    }) }
                }
                OmniButton("Done", { showSkipEditor = false })
            }
        }
    }
    if (sleepMenu) {
        Dialog(onDismissRequest = { sleepMenu = false }) {
            Column(Modifier.width(440.dp).clip(RoundedCornerShape(16.dp)).background(OmniTheme.colors.background)
                .padding(OmniSpacing.xl), verticalArrangement = Arrangement.spacedBy(OmniSpacing.s)) {
                Text(stringResource(R.string.sleep_timer_title), style = OmniTheme.type.title, color = OmniTheme.colors.textPrimary)
                Text(stringResource(R.string.sleep_timer_hint), style = OmniTheme.type.caption, color = OmniTheme.colors.textSecondary)
                OmniButton(stringResource(R.string.sleep_timer_off), { cancelSleep(); sleepMenu = false })
                for (m in listOf(15, 30, 60, 90)) {
                    val minuteLabel = stringResource(R.string.sleep_timer_minutes_format, m)
                    val minuteChoice = stringResource(R.string.sleep_timer_choice_minutes_format, m)
                    OmniButton(minuteLabel, {
                        sleepTimer.armMinutes(m); sleepAtEnd = false; sleepChoice = minuteChoice; sleepWarn = 0; sleepMenu = false
                    })
                }
                OmniButton(sleepEndLabel, { sleepTimer.cancel(); sleepAtEnd = true; sleepChoice = sleepEndChoice; sleepWarn = 0; sleepMenu = false })
            }
        }
    }
    BackHandler {
        if (fallbackPanel) { fallbackPanel = false; fallbackCancelled = true }
        else if (fallbackCountdown > 0) { fallbackCancelled = true; fallbackPanel = true }
        else if (sleepMenu) sleepMenu = false
        else if (showSkipEditor) showSkipEditor = false
        else if (showDiagnostics) showDiagnostics = false
        else if (upNextIn > 0) { upNextIn = -1; onExit() }
        else if (overlay) overlay = false
        else onExit()
    }

    // Task 114: every overlay control that can hold D-pad focus, listed once. The root must never
    // spend a direction on seeking/stats/a panel while one of them has focus, or the control loses
    // the key and stops being reachable — that is exactly how "Next episode ›" became unhittable:
    // the Sleep timer button sits immediately left of Next and was missing from the Left/Right
    // guard, so Right seeked +10 s instead of handing focus to Next.
    val rowControlFocused = nextButtonFocused || skipButtonFocused || skipEditorButtonFocused ||
        suggestionButtonFocused || pictureButtonFocused || sleepButtonFocused
    val panelControlFocused = fallbackButtonFocused
    val skipButtonVisible = activeSkip != null && activeSkipMode == SkipSettings.BUTTON

    Box(
        Modifier.fillMaxSize().background(Color.Black).focusRequester(focus).focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                lastInput = System.currentTimeMillis()
                val repeat = e.nativeKeyEvent.repeatCount
                val step = if (repeat > 6) 30_000L else 10_000L
                when (e.key) {
                    Key.Info -> { showDiagnostics = !showDiagnostics; true }
                    Key.MediaNext -> {
                        if (kids.blocked != null) true
                        else if (item.next != null) { onPlayNext(item.next); true }
                        else { queuedOffer?.let { onPlayQueued?.invoke(it.first) }; queuedOffer != null }
                    }
                    Key.DirectionCenter, Key.Enter, Key.MediaPlayPause, Key.Spacebar -> {
                        if ((rowControlFocused || panelControlFocused) && (e.key == Key.DirectionCenter || e.key == Key.Enter)) return@onPreviewKeyEvent false
                        // Task 111 H1: once the allowance is spent or it is bedtime, no key resumes or
                        // starts playback — the press just re-raises the "Time is up" card.
                        if (kids.blocked != null) { overlay = true; true }
                        else if (sleepWarn > 0) { cancelSleep(); true }
                        else if (upNextIn > 0) { if (item.next != null) onPlayNext(item.next) else queuedOffer?.let { onPlayQueued?.invoke(it.first) }; true }
                        else if (!overlay) { overlay = true; true }
                        else { if (state is PlayerState.Playing) engine.pause() else engine.resume(); true }
                    }
                    Key.DirectionRight -> if (routePlayerDpad(PlayerDirection.RIGHT, rowControlFocused, panelControlFocused, overlay, skipButtonVisible) == PlayerDpadRoute.HAND_TO_CONTROL) false else { overlay = true; engine.seekBy(step); true }
                    Key.DirectionLeft -> if (routePlayerDpad(PlayerDirection.LEFT, rowControlFocused, panelControlFocused, overlay, skipButtonVisible) == PlayerDpadRoute.HAND_TO_CONTROL) false else { overlay = true; engine.seekBy(-step); true }
                    Key.MediaFastForward -> { overlay = true; engine.seekBy(step); true }
                    Key.MediaRewind -> { overlay = true; engine.seekBy(-step); true }
                    // Down on the visible overlay (or the remote's Captions/Audio key) opens the track panel.
                    Key.DirectionDown -> when (routePlayerDpad(PlayerDirection.DOWN, rowControlFocused, panelControlFocused, overlay, skipButtonVisible)) {
                        PlayerDpadRoute.TRACKS -> { showTracks = true; true }
                        PlayerDpadRoute.FOCUS_SKIP -> { skipFocus.requestFocus(); true }
                        PlayerDpadRoute.FOCUS_SKIP_POINTS -> { skipEditorFocus.requestFocus(); true }
                        else -> { overlay = true; true }
                    }
                    Key.Captions, Key.MediaAudioTrack -> { showTracks = true; true }
                    // Up leaves the action row back to the picture; only from the picture does it toggle stats.
                    Key.DirectionUp -> when (routePlayerDpad(PlayerDirection.UP, rowControlFocused, panelControlFocused, overlay, skipButtonVisible)) {
                        PlayerDpadRoute.PICTURE -> { focus.requestFocus(); true }
                        PlayerDpadRoute.STATS -> { showDiagnostics = !showDiagnostics; true }
                        else -> { overlay = true; true }
                    }
                    else -> false
                }
            },
    ) {
                    EngineSurface(engine, Modifier.fillMaxSize(), contentScale = PictureMode.of(pictureMode).scale, frameRateMode = frameRateMode)
        EngineSubtitles(engine, Modifier.fillMaxSize(), style = subStyleSpec, offsetMs = subtitleDelayMs)
        if (showDiagnostics) {
            Column(Modifier.align(Alignment.TopEnd).padding(24.dp).width(380.dp)
                .clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.86f)).padding(20.dp)) {
                Text("PLAYBACK INFO  ·  BACK TO CLOSE", style = OmniTheme.type.overline, color = OmniTheme.colors.accent)
                Spacer(Modifier.height(12.dp))
                diagnostics.lines().forEach { Text(it, style = OmniTheme.type.body, color = Color.White) }
            }
        }
        val c = OmniTheme.colors
        val status = when {
            error != null -> error
            state is PlayerState.Failed -> when ((state as PlayerState.Failed).kind) {
                FailureKind.DENIED -> "The provider refused the stream — too many devices watching?"
                FailureKind.NOT_FOUND -> "This title isn't available on the provider anymore."
                FailureKind.UNSUPPORTED -> "This file uses a format this device can't play."
                else -> "Playback stopped. Check your connection and try again."
            }
            else -> null
        }
        if (status != null && fallback == null) {
            Text(status, style = OmniTheme.type.title, color = c.textPrimary, modifier = Modifier.align(Alignment.Center).clip(RoundedCornerShape(12.dp)).background(c.scrim).padding(OmniSpacing.l))
        }
        // Task 93: the failed copy is not a dead end when the catalog has another copy of the same
        // title. One alternative counts down automatically; several open a chooser.
        val choices = fallback
        if (choices != null && !switchingCopy) {
            val only = choices.singleOrNull()
            if (only != null && fallbackCountdown > 0 && !fallbackPanel) {
                Column(
                    Modifier.align(Alignment.Center).clip(RoundedCornerShape(12.dp)).background(c.scrim)
                        .padding(OmniSpacing.l),
                    verticalArrangement = Arrangement.spacedBy(OmniSpacing.s),
                ) {
                    Text(stringResource(R.string.vod_switching_copy_format, only.sourceName, fallbackCountdown),
                        style = OmniTheme.type.title, color = c.textPrimary)
                    OmniButton(stringResource(R.string.vod_cancel_switch), {
                        fallbackCancelled = true
                        fallbackPanel = true
                    }, Modifier.focusRequester(fallbackFocus).onFocusChanged { fallbackButtonFocused = it.hasFocus })
                }
            } else if (fallbackPanel) {
                Dialog(onDismissRequest = { fallbackPanel = false; fallbackCancelled = true }) {
                    Column(Modifier.width(560.dp).heightIn(max = 640.dp).clip(RoundedCornerShape(16.dp))
                        .background(OmniTheme.colors.background).verticalScroll(rememberScrollState()).padding(OmniSpacing.xl),
                        verticalArrangement = Arrangement.spacedBy(OmniSpacing.s)) {
                        Text(stringResource(R.string.vod_choose_copy_title), style = OmniTheme.type.title, color = OmniTheme.colors.textPrimary)
                        Text(error ?: stringResource(R.string.vod_choose_copy_hint),
                            style = OmniTheme.type.body, color = OmniTheme.colors.textSecondary)
                        choices.forEachIndexed { index, choice ->
                            val mod = if (index == 0) Modifier.focusRequester(fallbackFocus) else Modifier
                            OmniButton(choice.label, { fallbackScope.launch { switchToFallback(choice) } }, mod)
                        }
                        OmniButton(stringResource(R.string.vod_cancel_switch), { fallbackPanel = false; fallbackCancelled = true })
                    }
                }
            } else {
                Column(
                    Modifier.align(Alignment.Center).clip(RoundedCornerShape(12.dp)).background(c.scrim)
                        .padding(OmniSpacing.l),
                    verticalArrangement = Arrangement.spacedBy(OmniSpacing.s),
                ) {
                    Text(stringResource(R.string.vod_playback_stopped_try_copy), style = OmniTheme.type.title, color = c.textPrimary)
                    OmniButton(stringResource(R.string.vod_try_another_copy), { fallbackPanel = true },
                        Modifier.focusRequester(fallbackFocus).onFocusChanged { fallbackButtonFocused = it.hasFocus })
                }
            }
        }
        // Task 81: the engine's retry policy reconnects at the last known position; show a small
        // chip over the picture instead of covering it. Only a give-up shows the error text above.
        val loading = state as? PlayerState.Loading
        if (loading != null && loading.attempt > 1) {
            Text(
                context.getString(R.string.vod_reconnecting, loading.attempt - 1, RetryPolicy.MAX_TRIES),
                style = OmniTheme.type.caption, color = c.textPrimary,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = OmniSpacing.l)
                    .clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.88f)).padding(horizontal = OmniSpacing.l, vertical = OmniSpacing.s),
            )
        }
        AnimatedVisibility(
            overlay,
            enter = fadeIn(animationSpec = tween(OmniMotion.SCREEN_FADE_MS)),
            exit = fadeOut(animationSpec = tween(OmniMotion.SCREEN_FADE_MS)),
            modifier = Modifier.align(Alignment.BottomStart),
        ) {
            Column(
                // Soft cinematic fade into the picture instead of a hard-edged band.
                Modifier.fillMaxWidth().background(Brush.verticalGradient(
                    0f to c.background.copy(alpha = 0f), 0.35f to c.background.copy(alpha = 0.82f), 1f to c.background.copy(alpha = 0.97f),
                )).padding(start = OmniSpacing.tvSide, end = OmniSpacing.tvSide, top = OmniSpacing.xxl * 2, bottom = OmniSpacing.xl),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.title, modifier = Modifier.weight(1f), style = OmniTheme.type.headline, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    localSuggestion?.let { suggestion ->
                        val label = if (suggestion.kind == SuggestionKind.INTRO_END) "intro end" else "credits start"
                        OmniButton(
                            "Confirm $label · ${fmt(suggestion.positionMs)}",
                            {
                                val point = if (suggestion.kind == SuggestionKind.INTRO_END) "intro_end" else "credits_start"
                                val confirmedMarker = if (suggestion.kind == SuggestionKind.INTRO_END) {
                                    SkipMarker("intro", 0L, suggestion.positionMs)
                                } else {
                                    SkipMarker("credits", suggestion.positionMs, duration)
                                }
                                // Confirmation teaches future playback; it must not surprise-seek now,
                                // especially when a credits prompt arrives near the episode end.
                                autoSkippedMarkers = autoSkippedMarkers + markerToken(confirmedMarker)
                                editScope.launch { userData.setLocalSkipPoint(item.key, point, suggestion.positionMs) }
                                localSuggestion = null
                            },
                            Modifier.focusRequester(suggestionFocus).onFocusChanged { suggestionButtonFocused = it.hasFocus },
                        )
                        Spacer(Modifier.width(OmniSpacing.s))
                        OmniButton("Dismiss", { localSuggestion = null }, Modifier.onFocusChanged { suggestionButtonFocused = it.hasFocus })
                        Spacer(Modifier.width(OmniSpacing.m))
                    }
                    activeSkip?.takeIf { activeSkipMode == SkipSettings.BUTTON }?.let { marker ->
                        OmniButton(
                            if (marker.type == "intro") "Skip intro  ›" else "Skip credits  ›",
                            { skipTo(marker); overlay = false },
                            Modifier.focusRequester(skipFocus).onFocusChanged { skipButtonFocused = it.hasFocus },
                        )
                        Spacer(Modifier.width(OmniSpacing.m))
                    }
                    OmniButton("Picture: ${PictureMode.of(pictureMode).label}", {
                        pictureScope.launch { userData.putSetting(pictureKey, PictureMode.of(pictureMode).next().name) }
                    }, Modifier.onFocusChanged { pictureButtonFocused = it.hasFocus })
                    Spacer(Modifier.width(OmniSpacing.m))
                    OmniButton("Skip points", { showSkipEditor = true }, Modifier.focusRequester(skipEditorFocus).onFocusChanged { skipEditorButtonFocused = it.hasFocus })
                    Spacer(Modifier.width(OmniSpacing.m))
                    OmniButton(stringResource(R.string.sleep_timer_button_format, sleepChoice), { sleepMenu = true }, Modifier.onFocusChanged { sleepButtonFocused = it.hasFocus })
                    item.next?.let { next ->
                        Spacer(Modifier.width(OmniSpacing.m))
                        OmniButton("Next episode  ›", { onPlayNext(next) }, Modifier.focusRequester(nextFocus).onFocusChanged { nextButtonFocused = it.hasFocus })
                    }
                }
                Spacer(Modifier.height(OmniSpacing.m))
                ProgressLine(if (duration > 0) position.toFloat() / duration else 0f, Modifier.fillMaxWidth())
                Spacer(Modifier.height(OmniSpacing.s))
                Row {
                    Text(fmt(position), style = OmniTheme.type.numeric, color = c.textSecondary)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "OK ${if (state is PlayerState.Paused) "Play" else "Pause"}  ·  ← / → Skip 10s  ·  ↓ Controls  ·  ↑ Stats",
                        style = OmniTheme.type.caption, color = c.textTertiary,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(if (duration > 0) "-" + fmt(duration - position) else "", style = OmniTheme.type.numeric, color = c.textSecondary)
                }
            }
        }
        if (upNextIn > 0 && (item.next != null || queuedOffer != null)) {
            Column(
                Modifier.align(Alignment.BottomEnd).padding(OmniSpacing.tvSide).width(440.dp)
                    .clip(RoundedCornerShape(12.dp)).background(c.elevated).padding(OmniSpacing.l),
            ) {
                Text(if (item.next != null) "Up next in $upNextIn" else "Up next in your queue in $upNextIn", style = OmniTheme.type.caption, color = c.accent)
                Text(
                    item.next?.let { com.yodesla.omniverse.core.data.SpoilerFree.nextEpisodeTitle(it.title, com.yodesla.omniverse.core.data.SpoilerFree.enabled(spoilerFreeRaw)) }
                        ?: queuedOffer?.second.orEmpty(),
                    style = OmniTheme.type.title, color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Text("OK to play now · BACK to stop", style = OmniTheme.type.caption, color = c.textTertiary)
            }
        }
        if (sleepWarn > 0) {
            Text(
                stringResource(R.string.sleep_timer_warning_format, sleepWarn),
                style = OmniTheme.type.caption, color = c.textPrimary,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = OmniSpacing.l)
                    .clip(RoundedCornerShape(10.dp)).background(c.scrim).padding(horizontal = OmniSpacing.l, vertical = OmniSpacing.s),
            )
        }
        // Task 106: the 5-minute notice, then the stop card. The card is drawn over everything and
        // the overlay is forced open with it, so a paused Kids player can't be left without a way out.
        val kidsWarnMs = kids.warningMs
        if (kidsWarnMs != null && kids.blocked == null) {
            Text(
                stringResource(
                    if (kids.warningReason == KidsTimeReason.BEDTIME) R.string.kids_time_warning_bedtime_format
                    else R.string.kids_time_warning_limit_format,
                    ceil(kidsWarnMs / 60_000.0).toInt().coerceAtLeast(1),
                ),
                style = OmniTheme.type.caption, color = c.textPrimary,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = OmniSpacing.l)
                    .clip(RoundedCornerShape(10.dp)).background(c.scrim).padding(horizontal = OmniSpacing.l, vertical = OmniSpacing.s),
            )
        }
        if (kids.blocked != null) {
            Column(
                Modifier.align(Alignment.Center).width(640.dp).clip(RoundedCornerShape(OmniSpacing.cardCorner))
                    .background(c.scrim).padding(OmniSpacing.xl),
                verticalArrangement = Arrangement.spacedBy(OmniSpacing.m),
            ) {
                Text(stringResource(R.string.kids_time_up_title), style = OmniTheme.type.title, color = c.textPrimary)
                Text(
                    stringResource(
                        if (kids.blocked == KidsTimeReason.BEDTIME) R.string.kids_time_up_bedtime else R.string.kids_time_up_limit,
                    ),
                    style = OmniTheme.type.body, color = c.textSecondary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m), verticalAlignment = Alignment.CenterVertically) {
                    if (kidsPinEnabled) {
                        OmniButton(stringResource(R.string.kids_time_up_pin), onClick = onRequestKidsPin, primary = true)
                    } else {
                        Text(stringResource(R.string.kids_time_up_no_pin), style = OmniTheme.type.caption, color = c.textTertiary)
                    }
                    OmniButton(stringResource(R.string.kids_time_up_stop), onClick = onExit)
                }
            }
        }
    }
}

internal fun markerToken(marker: SkipMarker): String = "${marker.type}:${marker.startMs}:${marker.endMs}"

/** The authoritative final progress for an item: position + duration (null duration = unknown length). */
internal data class FinalProgress(val positionMs: Long, val durationMs: Long?)

/**
 * Decides an item's final progress exactly once and turns every later teardown/dispose save into a
 * no-op, so a stale engine position can never overwrite a completion (M3). The decision is made
 * synchronously before navigating, so correctness does not depend on coroutine launch order.
 */
internal class CompletionGuard {
    private var final: FinalProgress? = null
    val isDecided: Boolean get() = final != null

    /** Record the completed state once. Idempotent: later calls return the first decision. */
    fun decideCompleted(enginePositionMs: Long, engineDurationMs: Long?, knownDurationMs: Long?): FinalProgress {
        final?.let { return it }
        val dur = engineDurationMs?.takeIf { it > 0 } ?: knownDurationMs?.takeIf { it > 0 }
        val decided = if (dur != null) FinalProgress(dur, dur)
            else FinalProgress(enginePositionMs.coerceAtLeast(1L), null)
        final = decided
        return decided
    }

    /** A teardown/dispose save may write only while no final decision exists. */
    fun allowTeardownSave(): Boolean = final == null

    /** What the final "stopped" heartbeat must report: the completed state once decided. */
    fun heartbeatPosition(defaultPositionMs: Long, defaultDurationMs: Long?): Pair<Long, Long?> =
        final?.let { it.positionMs to it.durationMs } ?: (defaultPositionMs to defaultDurationMs)
}

/** The four D-pad directions the player root routes (Task 114). */
internal enum class PlayerDirection { UP, DOWN, LEFT, RIGHT }

/** What the player root does with one D-pad press. */
internal enum class PlayerDpadRoute {
    /** The focused overlay control owns the key; the root must not consume it (focus move / click). */
    HAND_TO_CONTROL,

    /** Hand D-pad focus back to the video surface, where Left/Right seek. */
    PICTURE,

    /** Seek the picture; only ever chosen while no overlay control holds focus. */
    SEEK_FORWARD,
    SEEK_BACK,

    /** Open the audio/subtitle panel. */
    TRACKS,

    /** Move focus to the Skip intro/credits prompt, else the Skip points button, else open the overlay. */
    FOCUS_SKIP,
    FOCUS_SKIP_POINTS,
    OVERLAY,

    /** Toggle the playback-info panel. */
    STATS,
}

/**
 * Task 114: the player root's D-pad routing table, extracted so the rule is unit-testable.
 *
 * The invariant: while an overlay control holds focus the root may not spend a direction on seeking,
 * stats or a panel. It would take the key away from the control, and a control that never receives
 * the key in its own direction can never be highlighted. [rowControlFocused] is the overlay action
 * row (suggestion, Skip, Picture, Skip points, Sleep timer, Next episode); [panelControlFocused] is a
 * control outside that row (the copy-switch chooser) that still needs Left/Right/Center left alone.
 * Seeking is chosen only while the picture surface holds focus, so seeking keeps working there.
 */
internal fun routePlayerDpad(
    direction: PlayerDirection,
    rowControlFocused: Boolean,
    panelControlFocused: Boolean,
    overlayVisible: Boolean,
    skipButtonVisible: Boolean,
): PlayerDpadRoute = when (direction) {
    PlayerDirection.LEFT -> if (rowControlFocused || panelControlFocused) PlayerDpadRoute.HAND_TO_CONTROL else PlayerDpadRoute.SEEK_BACK
    PlayerDirection.RIGHT -> if (rowControlFocused || panelControlFocused) PlayerDpadRoute.HAND_TO_CONTROL else PlayerDpadRoute.SEEK_FORWARD
    PlayerDirection.DOWN -> when {
        rowControlFocused -> PlayerDpadRoute.TRACKS
        overlayVisible && skipButtonVisible -> PlayerDpadRoute.FOCUS_SKIP
        overlayVisible -> PlayerDpadRoute.FOCUS_SKIP_POINTS
        else -> PlayerDpadRoute.OVERLAY
    }
    PlayerDirection.UP -> when {
        rowControlFocused -> PlayerDpadRoute.PICTURE
        overlayVisible -> PlayerDpadRoute.STATS
        else -> PlayerDpadRoute.OVERLAY
    }
}

/** Online data only supplies timestamps; the viewer's per-kind preference controls presentation. */
internal fun skipModeForType(type: String?, introMode: String?, creditsMode: String?): String =
    when (type) {
        "intro" -> introMode
        "credits" -> creditsMode
        else -> null
    }?.takeIf { it == SkipSettings.AUTO || it == SkipSettings.BUTTON || it == SkipSettings.OFF }
        ?: SkipSettings.BUTTON

/**
 * Online credits markers often end at the catalogue runtime, which can run a few seconds past
 * this stream's real length (different encodes). Clamp such overhangs (up to 90 s) to the end
 * instead of rejecting the marker, otherwise credits never skip.
 */
internal fun fitToDuration(marker: SkipMarker, durationMs: Long): SkipMarker =
    if (durationMs > 0 && marker.endMs > durationMs && marker.startMs < durationMs && marker.endMs - durationMs <= 90_000L)
        marker.copy(endMs = durationMs) else marker

/** Reject malformed/out-of-runtime markers instead of letting a stale timestamp seek past the item. */
internal fun isUsableSkipMarker(marker: SkipMarker, durationMs: Long): Boolean =
    (marker.type == "intro" || marker.type == "credits") && marker.startMs >= 0 && marker.endMs > marker.startMs &&
        (durationMs <= 0 || (marker.startMs < durationMs && marker.endMs <= durationMs))

/** Convert a series' "time remaining" credit point only when it can land inside the episode. */
internal fun creditsStartFromRemaining(durationMs: Long, remainingMs: Long): Long? =
    if (durationMs <= 0 || remainingMs < 5_000 || remainingMs >= durationMs) null
    else (durationMs - remainingMs).takeIf { it >= 5_000 && it < durationMs }

/** Only confirmed local points or source-provided markers are passed here; suggestions are not. */
internal fun autoSkipTarget(
    marker: SkipMarker?, mode: String, positionMs: Long, playing: Boolean, alreadySkipped: Set<String>,
    durationMs: Long = 0,
): Long? {
    if (marker == null || mode != SkipSettings.AUTO || !playing || markerToken(marker) in alreadySkipped) return null
    if (!isUsableSkipMarker(marker, durationMs)) return null
    if (positionMs < marker.startMs || positionMs + 500 >= marker.endMs) return null
    return marker.endMs
}

/** Outlives the player screen so the final "stopped" heartbeat isn't cancelled with it. */
private val heartbeatScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** Video picture mode: Fit = letterbox/pillarbox (default), Stretch = fill the screen, Zoom = fill and crop. */
internal enum class PictureMode(val label: String, val scale: androidx.compose.ui.layout.ContentScale) {
    FIT("Fit", androidx.compose.ui.layout.ContentScale.Fit),
    STRETCH("Stretch", androidx.compose.ui.layout.ContentScale.FillBounds),
    ZOOM("Zoom", androidx.compose.ui.layout.ContentScale.Crop);

    fun next(): PictureMode = entries[(ordinal + 1) % entries.size]

    companion object {
        fun of(saved: String?): PictureMode = entries.firstOrNull { it.name == saved } ?: FIT
    }
}

/** Per show (episodes share their show's setting) or per movie. */
internal fun pictureModeKey(key: com.yodesla.omniverse.core.model.ContentKey, parentId: com.yodesla.omniverse.core.model.RemoteId?): String =
    "picture_mode_${key.sourceId.value}_${parentId?.value ?: key.remoteId.value}"

/** Per show (its episodes share the track choice) or per movie; the same scoping as picture mode. */
internal fun tracksKey(key: com.yodesla.omniverse.core.model.ContentKey, parentId: com.yodesla.omniverse.core.model.RemoteId?): String =
    "tracks_${key.sourceId.value}_${parentId?.value ?: key.remoteId.value}"

/** Settings keys for the viewer's default audio language (ISO 639, blank = stream default) and subtitles mode. */
const val PREF_AUDIO_LANGUAGE = "pref_audio_language"
const val PREF_SUBTITLES = "pref_subtitles"
