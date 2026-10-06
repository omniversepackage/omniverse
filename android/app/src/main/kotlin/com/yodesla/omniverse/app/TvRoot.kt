package com.yodesla.omniverse.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.platform.LocalConfiguration
import com.yodesla.omniverse.designsystem.LocalCompact
import com.yodesla.omniverse.designsystem.OmniTheme
import com.yodesla.omniverse.designsystem.BottomNav
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.focusGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarViewWeek
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.SportsSoccer
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yodesla.omniverse.core.brand.BrandConfig
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.SourceId
import com.yodesla.omniverse.core.model.progressPosterKey
import com.yodesla.omniverse.designsystem.LocalVisualTier
import com.yodesla.omniverse.designsystem.NavItem
import com.yodesla.omniverse.designsystem.NavRail
import com.yodesla.omniverse.designsystem.TvNavRailWidth
import com.yodesla.omniverse.designsystem.requestFocusWhenReady
import com.yodesla.omniverse.feature.home.HomeRoute
import com.yodesla.omniverse.feature.home.HomeViewModel
import com.yodesla.omniverse.feature.home.SearchRoute
import com.yodesla.omniverse.feature.home.SearchViewModel
import com.yodesla.omniverse.feature.home.SettingsRoute
import com.yodesla.omniverse.feature.home.settings.OmniActionRow
import com.yodesla.omniverse.feature.home.settings.SettingsCategory
import com.yodesla.omniverse.feature.home.settings.SettingsSlot
import com.yodesla.omniverse.feature.home.SettingsViewModel
import com.yodesla.omniverse.feature.live.LiveRoute
import com.yodesla.omniverse.feature.live.LiveViewModel
import com.yodesla.omniverse.feature.live.guide.GuideRoute
import com.yodesla.omniverse.feature.live.guide.GuideViewModel
import com.yodesla.omniverse.feature.live.player.LivePlayerRoute
import com.yodesla.omniverse.feature.live.player.LivePlayerViewModel
import com.yodesla.omniverse.feature.live.multiview.MultiviewRoute
import com.yodesla.omniverse.feature.live.multiview.MultiviewViewModel
import com.yodesla.omniverse.feature.vod.DetailRoute
import com.yodesla.omniverse.feature.vod.DetailViewModel
import com.yodesla.omniverse.feature.vod.PlayItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import androidx.compose.runtime.rememberCoroutineScope
import com.yodesla.omniverse.feature.onboarding.OnboardingRoute
import com.yodesla.omniverse.android.update.UpdatePrompt
import com.yodesla.omniverse.android.update.UpdateViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.yodesla.omniverse.feature.onboarding.OnboardingViewModel
import java.util.UUID
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yodesla.omniverse.core.model.PlaybackRequest
import com.yodesla.omniverse.core.model.PlaybackSpec
import com.yodesla.omniverse.core.data.UserDataRepository
import com.yodesla.omniverse.core.data.Visibility
import com.yodesla.omniverse.feature.vod.VodBrowseRoute
import com.yodesla.omniverse.feature.vod.VodBrowseViewModel
import com.yodesla.omniverse.feature.vod.VodPlayerRoute
import com.yodesla.omniverse.core.data.ProfileRepository
import com.yodesla.omniverse.core.data.shouldShowProfilePicker
import com.yodesla.omniverse.core.data.profileAdminNeedsPin
import com.yodesla.omniverse.feature.home.profiles.ManageProfilesRoute
import com.yodesla.omniverse.feature.home.profiles.ProfileEditorRoute
import com.yodesla.omniverse.feature.home.profiles.ProfilesViewModel
import com.yodesla.omniverse.feature.home.profiles.WhosWatchingRoute
import com.yodesla.omniverse.android.parental.PinPad

internal sealed interface Screen {
    /** Top-level sections live beside the nav rail. */
    sealed interface Section : Screen { val id: String }
    data object Home : Section { override val id = "home" }
    /** [inGuide]: open straight into the full guide grid (Task 107: Back from a search-opened channel). */
    data class Live(val inGuide: Boolean = false) : Section { override val id = "live" }
    data object Sports : Section { override val id = "sports" }
    data object Movies : Section { override val id = "movies" }
    data object Shows : Section { override val id = "shows" }
    /** [query]: a voice / global-search text to pre-fill the field (task 80); null = normal open. */
    data class Search(val query: String? = null) : Section { override val id = "search" }
    data object Settings : Section { override val id = "settings" }
    data object DevGuide : Section { override val id = "dev" }

    /** Full-screen destinations. */
    data class Player(val key: ContentKey, val categoryId: RemoteId) : Screen
    /** 2-4 live channels at once (Task 55/67): the channels come from the app-wide session, so
     *  leaving and re-entering resumes the same grid. Each tile runs its own engine inside the screen. */
    data object Multiview : Screen
    /** [autoPlay]: null = just show; false = resume playback at once; true = start over at once. */
    data class Detail(val key: ContentKey, val autoPlay: Boolean? = null, val focusEpisode: RemoteId? = null) : Screen
    data class VodPlay(val item: PlayItem) : Screen
    /** Add-a-provider flow. [firstRun] = the app has no sources yet (it is the root screen).
     *  [editSourceId] non-null = re-entering the login of that existing source (L3): the same form,
     *  pre-filled, saving over the same source instead of adding a new one. */
    data class Onboarding(val firstRun: Boolean, val editSourceId: SourceId? = null) : Screen
    /** Profiles (task 59): startup picker, management list, editor for one profile. */
    data object WhosWatching : Screen
    data object ManageProfiles : Screen
    data class ProfileEdit(val id: String?) : Screen
}

private val sections: Map<String, Screen.Section> =
    listOf(Screen.Home, Screen.Search(), Screen.Settings, Screen.Live(), Screen.Sports, Screen.Movies, Screen.Shows, Screen.DevGuide).associateBy { it.id }

/**
 * Task 107 + 118: the stack rewrite behind "Back from a search result goes to" (Settings › Playback).
 * Only a channel opened from Search is rewritten: with "Guide or details page" the Search entry under
 * the player becomes the Live section in guide mode, so Back lands on the Guide on that channel with
 * the stream still running. A title opened from Search keeps the Search entry (task 118, Kory): Back
 * returns to the SAME Search screen — same query, results, focus and scroll position. null = leave
 * Search in place (option "Search", or a destination with no landing section).
 */
internal fun searchBackReplacement(pushed: Screen, toGuideOrDetails: Boolean): Screen? {
    if (!toGuideOrDetails) return null
    return when (pushed) {
        is Screen.Player -> Screen.Live(inGuide = true)
        else -> null
    }
}

/**
 * Task 118: the stack mutation [openCard] performs, kept separate so the search → title → Back path is
 * testable. The Search entry under a screen opened from Search becomes its Back landing (a channel →
 * the Guide); a title leaves it alone, so popping the detail returns to the Search screen the results
 * were on. Any other screen under the new one is untouched — normal Back for every other route.
 */
internal fun openCardOnStack(stack: MutableList<Screen>, dest: Screen, toGuideOrDetails: Boolean) {
    if (stack.last() is Screen.Search) searchBackReplacement(dest, toGuideOrDetails)?.let { stack[stack.lastIndex] = it }
    stack.add(dest)
}

@Composable
/** The app shell. [mobile] = phone/tablet: bottom bar instead of the D-pad rail, same screens. */
fun TvRoot(brand: BrandConfig, graph: AppGraph, mobile: Boolean = false) {
    val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
    // Detail/player routes remove the section from composition; retain its saveable browse state
    // until Back returns (category-panel visibility and scroll positions).
    val sectionState = rememberSaveableStateHolder()
    var lastPlayed by remember { mutableStateOf<ContentKey?>(null) }
    // Task 107/118: "Back from a search result goes to" (Settings › Playback), per-profile. Since task
    // 118 it only decides the landing for a channel opened from Search; a title always returns to Search.
    val backFromSearchRaw by graph.userData.setting(UserDataRepository.BACK_FROM_SEARCH).collectAsStateWithLifecycle(initialValue = null)
    val backToGuideOrDetails = !com.yodesla.omniverse.core.data.backFromSearchGoesToSearch(backFromSearchRaw)
    var railOpen by remember { mutableStateOf(false) }
    val appScope = rememberCoroutineScope()
    val railFocus = remember { FocusRequester() }
    val contentFocus = remember { FocusRequester() }
    fun push(s: Screen) { stack.add(s) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun replaceTop(s: Screen) { stack[stack.lastIndex] = s }

    // Task 101: the first drawn frame is the reference point of the startup timeline, and the point
    // from which the "sync no later than 2 s after the first frame" rule is measured.
    LaunchedEffect(Unit) { withFrameNanos { graph.firstFrameDrawn() } }
    fun openSection(id: String) {
        val target = sections[id] ?: return
        railOpen = false
        stack.clear()
        stack.add(Screen.Home)
        if (target != Screen.Home) stack.add(target)
    }
    // ---- Live session: ONE engine for fullscreen + the mini player (Back from a channel keeps it
    // playing in a corner box while browsing the Guide/list). One upstream connection throughout.
    val context = androidx.compose.ui.platform.LocalContext.current
    val liveVm = viewModel(key = "player") { LivePlayerViewModel(graph.sources, graph.catalog, graph.epg, graph.userData, graph.clock, visibility = graph.vodVisibility, preloadEnabled = graph.userData.setting(UserDataRepository.PRELOAD_NEXT_CHANNEL).map { it != "false" }) }
    val liveUi by liveVm.state.collectAsStateWithLifecycle()
    var liveActive by remember { mutableStateOf(false) }
    // Task 94: two engines so a zap can swap onto a stream that is already buffered. The spare is
    // never playing (no surface, playWhenReady=false) and is released on leaving the player/background.
    var liveEngine by remember { mutableStateOf(com.yodesla.omniverse.player.Media3ExoEngine(context, graph.userAgent)) }
    var spareEngine by remember { mutableStateOf<com.yodesla.omniverse.player.Media3ExoEngine?>(null) }
    var sparePrimed by remember { mutableStateOf<PlaybackSpec?>(null) }
    var resumeTick by remember { mutableStateOf(0) }
    fun releaseSpare() { spareEngine?.release(); spareEngine = null; sparePrimed = null }
    fun swapEngines() {
        val spare = spareEngine ?: return
        liveEngine.release()
        liveEngine = spare
        spareEngine = null
        sparePrimed = null
        spare.adopt() // the primed stream starts playing on the surface the UI just re-attached
    }
    val hostView = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { liveEngine.release(); spareEngine?.release() } }
    androidx.compose.runtime.DisposableEffect(liveActive) {
        hostView.keepScreenOn = liveActive
        onDispose { hostView.keepScreenOn = false }
    }
    // App backgrounded: drop the stream (providers allow 1-2 connections), rejoin on return.
    var liveStoppedInBackground by remember { mutableStateOf(false) }
    androidx.lifecycle.compose.LifecycleStartEffect(Unit) {
        if (liveStoppedInBackground) {
            liveStoppedInBackground = false
            resumeTick++
        }
        onStopOrDispose { releaseSpare(); if (liveActive) { liveEngine.stop(); liveStoppedInBackground = true } }
    }
    fun stopLive() { releaseSpare(); if (liveActive) { liveEngine.stop(); liveActive = false } }

    fun openCard(key: ContentKey, categoryId: RemoteId?) {
        val dest: Screen = if (key.kind == ContentKind.LIVE && categoryId != null) { lastPlayed = key; liveActive = true; Screen.Player(key, categoryId) }
        else Screen.Detail(key)
        // Task 107/118: opened from Search — a channel's entry under it becomes the Guide landing, a
        // title leaves Search in place so Back returns to the same results.
        openCardOnStack(stack, dest, backToGuideOrDetails)
    }

    fun addSource() { if (stack.last() !is Screen.Onboarding) push(Screen.Onboarding(firstRun = false)) }

    // Phone remote (task 76): a phone's play/key requests go through the SAME navigation the app
    // already uses. D-pad / play-pause keys are injected into this Activity only (not system-wide).
    LaunchedEffect(Unit) {
        graph.phoneRemote.actions.collect { a ->
            when (a) {
                is com.yodesla.omniverse.feature.home.phoneremote.PhoneRemoteAction.Key -> {
                    val code = when (a.key) {
                        com.yodesla.omniverse.feature.home.phoneremote.PhoneRemoteKey.Up -> 19
                        com.yodesla.omniverse.feature.home.phoneremote.PhoneRemoteKey.Down -> 20
                        com.yodesla.omniverse.feature.home.phoneremote.PhoneRemoteKey.Left -> 21
                        com.yodesla.omniverse.feature.home.phoneremote.PhoneRemoteKey.Right -> 22
                        com.yodesla.omniverse.feature.home.phoneremote.PhoneRemoteKey.Ok -> 23
                        com.yodesla.omniverse.feature.home.phoneremote.PhoneRemoteKey.Back -> 4
                        com.yodesla.omniverse.feature.home.phoneremote.PhoneRemoteKey.PlayPause -> 85
                    }
                    (context as? android.app.Activity)?.let { act ->
                        val t = android.os.SystemClock.uptimeMillis()
                        act.dispatchKeyEvent(android.view.KeyEvent(t, t, android.view.KeyEvent.ACTION_DOWN, code, 0))
                        act.dispatchKeyEvent(android.view.KeyEvent(t, t, android.view.KeyEvent.ACTION_UP, code, 0))
                    }
                }
                is com.yodesla.omniverse.feature.home.phoneremote.PhoneRemoteAction.PlayChannel -> { stopLive(); openCard(a.key, a.categoryId) }
                is com.yodesla.omniverse.feature.home.phoneremote.PhoneRemoteAction.PlayMovie -> { stopLive(); push(Screen.Detail(a.key, autoPlay = false)) } // false = resume at once
                is com.yodesla.omniverse.feature.home.phoneremote.PhoneRemoteAction.OpenShow -> { stopLive(); push(Screen.Detail(a.key)) }
                // Task 91 Now Playing controls: only the active player (which registered its hooks in
                // the holder while it played) can act; with nothing playing the holder is null.
                is com.yodesla.omniverse.feature.home.phoneremote.PhoneRemoteAction.Seek -> graph.nowPlayingControls.value?.seekTo(a.positionMs)
                is com.yodesla.omniverse.feature.home.phoneremote.PhoneRemoteAction.Skip -> graph.nowPlayingControls.value?.skipBy(a.deltaMs)
                is com.yodesla.omniverse.feature.home.phoneremote.PhoneRemoteAction.SelectAudio -> graph.nowPlayingControls.value?.selectAudio(a.trackId)
                is com.yodesla.omniverse.feature.home.phoneremote.PhoneRemoteAction.SelectSubtitle -> graph.nowPlayingControls.value?.selectSubtitle(a.trackId)
            }
        }
    }

    // Android TV home-screen card → that title's detail page (Back returns to Home).
    LaunchedEffect(Unit) {
        DeepLinks.pending.collect { key ->
            if (key != null) {
                DeepLinks.pending.value = null
                // The link is exported: anyone can send it, so it must pass the same parental
                // check as browsing (a locked title stays behind the PIN).
                val poster = graph.catalog.poster(key)
                val allowed = graph.browseVisibility.first()
                if (poster != null && (poster.categoryId?.let { allowed(key.kind.name, key.sourceId.value, it.value) } ?: true)) {
                    stopLive(); railOpen = false
                    stack.clear(); stack.add(Screen.Home); push(Screen.Detail(key))
                }
            }
        }
    }

    // Remote mic / Google TV search (task 80): same path as the deep link — the spoken text opens
    // the app's own Search section with the field pre-filled.
    LaunchedEffect(Unit) {
        VoiceSearch.pending.collect { q ->
            if (q != null) {
                VoiceSearch.pending.value = null
                stopLive(); railOpen = false
                stack.clear(); stack.add(Screen.Home); stack.add(Screen.Search(q))
            }
        }
    }

    // First run: no providers yet → onboarding is the whole app. Debug builds auto-add the mock
    // provider instead (AppGraph.devBootstrap), so they only reach onboarding via "+ Add a source".
    val sourceList by graph.sources.sources().collectAsStateWithLifecycle(initialValue = null)
    val needsFirstRun = !BuildConfig.DEV_TOOLS && sourceList?.isEmpty() == true
    LaunchedEffect(needsFirstRun) {
        if (needsFirstRun && stack.none { it is Screen.Onboarding }) { stack.clear(); stack.add(Screen.Onboarding(firstRun = true)) }
    }

    // ---- Profiles (task 59). The startup question is answered once per process: the flag lives on
    // AppGraph so the switch-restart does not ask again, while a fresh process asks per the setting.
    val profilesVm = viewModel(key = "profiles") { ProfilesViewModel(graph.profiles, graph.userData) }
    val profileList by graph.profiles.profiles().collectAsStateWithLifecycle(initialValue = null)
    val currentProfile by graph.profiles.current().collectAsStateWithLifecycle(initialValue = null)
    val pickerPref by graph.userData.setting(ProfileRepository.PICKER_KEY).collectAsStateWithLifecycle(initialValue = null)
    val pinEnabled by graph.parental.enabled.collectAsStateWithLifecycle(initialValue = false)
    val phoneState by graph.phoneRemote.state.collectAsStateWithLifecycle()
    var pendingSwitch by remember { mutableStateOf<String?>(null) }
    var pendingAdmin by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pendingAdminTitle by remember { mutableStateOf<String?>(null) }
    var pinForSwitch by remember { mutableStateOf(false) }
    var pinError by remember { mutableStateOf<String?>(null) }

    // Crash notice (tasks 78 + 104): per profile. Each profile is told once about each crash — the
    // newest crash epoch it has already seen is a viewer setting on the active profile, so another
    // profile's notice is untouched and a newer crash is news to every profile again.
    var crashCard by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // Task 101: the report folder and the settings row are read off the main thread — startup
        // file IO was another thing standing between the process start and the first frame.
        val crashDir = CrashLog.dirOf(context)
        val found = runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val newest = CrashLog.newestEpochMs(crashDir)
                val seen = graph.userData.setting(CrashLog.NOTICE_SETTING_KEY).first()?.toLongOrNull()
                if (CrashLog.noticeDue(newest, seen)) {
                    graph.userData.putSetting(CrashLog.NOTICE_SETTING_KEY, newest.toString())
                    true
                } else false
            }
        }.getOrElse {
            // The database was not ready yet: fall back to the flag file, once.
            if (CrashLog.flagExists(crashDir)) { CrashLog.deleteFlag(crashDir); true } else false
        }
        if (found) crashCard = true
    }
    LaunchedEffect(crashCard) { if (crashCard) { kotlinx.coroutines.delay(10_000); crashCard = false } }

    fun doSwitch(id: String) {
        pinForSwitch = false; pendingSwitch = null; pinError = null
        appScope.launch {
            graph.switchProfileTo(id)
            // singleTask + CLEAR_TASK: a genuinely fresh activity (new ViewModels, new composition)
            // so every screen reloads under the new profile id; the AppGraph itself survives.
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                val intent = android.content.Intent(context, MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                (context as? android.app.Activity)?.finish()
            }
        }
    }
    fun requestSwitch(id: String) {
        val cur = graph.currentProfileCache
        if (cur == id) return
        // Leaving a Kids profile always asks for the parental PIN (when one is set).
        if (currentProfile?.isKids == true && pinEnabled) { pendingSwitch = id; pinForSwitch = true; pinError = null }
        else doSwitch(id)
    }
    fun requestProfileAdmin(action: () -> Unit) {
        if (profileAdminNeedsPin(currentProfile?.isKids == true, pinEnabled)) {
            pendingAdmin = action
            pendingAdminTitle = "Enter the parental PIN to manage profiles"
            pinForSwitch = true
            pinError = null
        } else action()
    }
    /**
     * M4 (audit 73): Restore / Replace rewrite every profile's data, so they go through the same
     * PIN pad as profile admin — and ask for the PIN whenever one is set, not only on a Kids
     * profile: a kid holding the remote on an adult profile can still wipe the whole device.
     */
    fun requestBackupAdmin(action: () -> Unit) {
        if (backupRestoreNeedsPin(pinEnabled)) {
            pendingAdmin = action
            pendingAdminTitle = "Enter the parental PIN to restore this backup"
            pinForSwitch = true
            pinError = null
        } else action()
    }
    /**
     * Task 106: a Kids player that hit the daily limit or bedtime offers 30 more minutes for the
     * parental PIN. The PIN pad is the app-wide one (the player features do not depend on the
     * parental module); the player keeps its own "Time is up" card while this is open.
     */
    fun requestKidsExtension() {
        if (!pinEnabled) return
        pendingAdmin = { appScope.launch { graph.kidsTime.grantExtraMinutes() } }
        pendingAdminTitle = "Enter the parental PIN for 30 more minutes"
        pinForSwitch = true
        pinError = null
    }
    fun resolveProfile(id: String) {
        AppGraph.profileGateResolved = true
        if (stack.lastOrNull() is Screen.WhosWatching) pop()
        requestSwitch(id)
    }
    LaunchedEffect(profileList, currentProfile, pickerPref, needsFirstRun) {
        val list = profileList ?: return@LaunchedEffect
        val cur = currentProfile ?: return@LaunchedEffect
        if (needsFirstRun) return@LaunchedEffect
        if (!AppGraph.profileGateResolved && shouldShowProfilePicker(list.size, pickerPref != "false", cur.isKids)) {
            if (stack.none { it is Screen.WhosWatching }) push(Screen.WhosWatching)
        } else {
            AppGraph.profileGateResolved = true
            if (stack.lastOrNull() is Screen.WhosWatching) pop()
        }
    }
    // Saving a new profile switches to it right away (PIN-gated like any switch).
    val profilesUi by profilesVm.state.collectAsStateWithLifecycle()
    LaunchedEffect(profilesUi.createdId) {
        profilesUi.createdId?.let { id -> profilesVm.consumeCreated(); requestSwitch(id) }
    }

    val navItems = remember {
        buildList {
            add(NavItem("search", "Search", Icons.Outlined.Search))
            add(NavItem("home", "Home", Icons.Outlined.Home))
            add(NavItem("live", "Live TV", Icons.Outlined.LiveTv))
            add(NavItem("sports", "Live Sports", Icons.Outlined.SportsSoccer))
            add(NavItem("movies", "Movies", Icons.Outlined.Movie))
            add(NavItem("shows", "Shows", Icons.Outlined.Tv))
            if (BuildConfig.DEV_TOOLS && !mobile) add(NavItem("dev", "Guide preview", Icons.Outlined.Science))
            add(NavItem("settings", "Settings", Icons.Outlined.Settings))
        }
    }

    val top = stack.last()
    // Live keeps playing only fullscreen or as the mini player under the Live tab.
    LaunchedEffect(top) {
        // Task 111 L2: leaving the Player route drops the resolved (token-bearing) preload spec so
        // the surviving Live-section VM does not retain a stream URL.
        if (top !is Screen.Player) liveVm.onScreenExit()
        if (top !is Screen.Player && top !is Screen.Live) stopLive()
    }
    // Task 94: one effect drives play/swap/prime so release and swap can never race each other.
    LaunchedEffect(liveUi.spec, liveUi.preload, liveActive, top is Screen.Player, resumeTick) {
        val spec = liveUi.spec
        if (!liveActive || spec == null) { releaseSpare(); return@LaunchedEffect }
        if (liveEngine.currentSpec !== spec) {
            if (sparePrimed === spec) swapEngines() else { releaseSpare(); liveEngine.zap(spec) }
        }
        val primed = liveUi.preload
        if (top !is Screen.Player || primed == null) { releaseSpare(); return@LaunchedEffect }
        val existing = spareEngine
        if (existing != null && sparePrimed === primed) return@LaunchedEffect
        if (existing != null) releaseSpare()
        val fresh = com.yodesla.omniverse.player.Media3ExoEngine(context, graph.userAgent)
        spareEngine = fresh
        sparePrimed = primed
        fresh.prime(primed)
    }
    // Task 94: a failed pre-buffer is not shown to the viewer; a refusal disables pre-loading.
    LaunchedEffect(spareEngine) {
        val e = spareEngine ?: return@LaunchedEffect
        e.state.collect { s ->
            if (s is com.yodesla.omniverse.player.PlayerState.Failed) {
                liveVm.onPreloadFailed(s.kind)
                releaseSpare()
            }
        }
    }
    // Task 106: the mini live player keeps the shared engine alive outside the player route, so the
    // Kids clock has to keep ticking there too — and the stream stops when the limit bites.
    val kidsState by graph.kidsTime.state.collectAsStateWithLifecycle()
    var kidsNotice by remember { mutableStateOf(false) }
    LaunchedEffect(liveActive, top) {
        if (!liveActive || top is Screen.Player) return@LaunchedEffect
        graph.kidsTime.reload()
        while (true) {
            delay(1_000)
            graph.kidsTime.tick(playing = liveEngine.state.value is com.yodesla.omniverse.player.PlayerState.Playing)
        }
    }
    LaunchedEffect(kidsState.blocked, liveActive, top) {
        if (kidsState.blocked != null) {
            if (liveActive) { stopLive(); kidsNotice = true }
            // Task 111 H2: a block also leaves Multiview (its own clock has already stopped the tiles).
            if (top is Screen.Multiview) { pop(); kidsNotice = true }
        } else kidsNotice = false
    }
    val miniPlayer: (@Composable (Modifier) -> Unit)? =
        if (liveActive) { m -> com.yodesla.omniverse.feature.live.player.MiniLivePlayer(liveEngine, liveUi.banner, m) } else null
    BackHandler(enabled = stack.size > 1 || (!mobile && top is Screen.Section)) {
        if (!mobile && top is Screen.Section) railOpen = !railOpen else pop()
    }
    // While the switch-PIN pad is up, Back cancels the PIN instead of popping the screen behind it.
    BackHandler(enabled = pinForSwitch) { pinForSwitch = false; pendingSwitch = null; pendingAdmin = null; pendingAdminTitle = null; pinError = null }
    CompositionLocalProvider(
        LocalVisualTier provides rememberVisualTier(),
        LocalCompact provides (mobile && LocalConfiguration.current.screenWidthDp < 600),
    ) {
        if (top is Screen.Section && mobile) {
            Column(Modifier.fillMaxSize().background(OmniTheme.colors.background).safeDrawingPadding()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    sectionState.SaveableStateProvider(top.id) { key(top) { SectionContent(top, graph, lastPlayed, ::openCard, ::push, ::addSource, miniPlayer = miniPlayer, appScope = appScope, onRequirePin = ::requestBackupAdmin, onSetPickerAtStart = { enabled, update -> requestProfileAdmin { update(enabled) } }, onCreateProfile = { requestProfileAdmin { profilesVm.openNew(); push(Screen.ProfileEdit(null)) } }) } }
                }
                // Search sits in the top-level list on TV (rail top); on a phone it's just another tab.
                BottomNav(navItems, selectedId = top.id, onSelect = ::openSection)
            }
        } else if (top is Screen.Section) {
            // Browsing stays full-width. When navigation opens, reserve its width so the
            // category column never ends up hidden behind the rail.
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier.fillMaxSize()
                        .padding(start = if (railOpen) TvNavRailWidth else 0.dp)
                        .focusRequester(contentFocus)
                        // Moving Right out of the rail hides it at once: it only exists to pick a section.
                        .onFocusChanged { if (it.hasFocus && railOpen) railOpen = false }
                        .focusProperties {
                            onExit = {
                                if (requestedFocusDirection == FocusDirection.Left) {
                                    railOpen = true
                                    cancelFocusChange()
                                }
                            }
                        }
                        .focusGroup(),
                ) {
                    sectionState.SaveableStateProvider(top.id) { key(top) { SectionContent(top, graph, lastPlayed, ::openCard, ::push, ::addSource, onOpenNavigation = { railOpen = true }, miniPlayer = miniPlayer, appScope = appScope, onSearch = { openSection("search") }, onManageProfiles = { requestProfileAdmin { push(Screen.ManageProfiles) } }, onRequirePin = ::requestBackupAdmin, onSetPickerAtStart = { enabled, update -> requestProfileAdmin { update(enabled) } }, onCreateProfile = { requestProfileAdmin { profilesVm.openNew(); push(Screen.ProfileEdit(null)) } }) } }
                }
                if (railOpen) NavRail(navItems, selectedId = top.id, onSelect = ::openSection, selectedRequester = railFocus)
            }
            LaunchedEffect(railOpen, top) {
                if (railOpen) railFocus.requestFocusWhenReady()
                else contentFocus.requestFocusWhenReady()
            }
        } else {
            ParentalRouteGate(top, graph, onDenied = { openSection("home") }) {
                key(stack.size, top) {
                    when (top) {
                        is Screen.Player -> {
                            LivePlayerRoute(liveVm, top.key, top.categoryId, liveEngine, onExit = { pop() },
                                nowPlaying = graph.nowPlaying, nowPlayingControls = graph.nowPlayingControls,
                                kidsTime = graph.kidsTime, kidsPinEnabled = pinEnabled, onRequestKidsPin = ::requestKidsExtension)
                        }
                        is Screen.Detail -> {
                            val vm = viewModel(key = "detail-${top.key}") { DetailViewModel(top.key, graph.sources, graph.userData, graph.catalog, graph.parental.visibility, graph.metadataEnricher, graph.rottenTomatoes, tmdb = graph.tmdbEnricher, focusEpisode = top.focusEpisode) }
                            DetailRoute(vm, graph.parental.visibility, onOpen = { push(Screen.Detail(it)) }) { item -> push(Screen.VodPlay(item)) }
                            LaunchedEffect(Unit) { vm.load() } // refresh resume points after playback
                            // Hold-menu Resume / Start over: play as soon as the title has loaded. The flag is
                            // cleared first so Back from the player lands on this detail page, not a replay.
                            top.autoPlay?.let { fromStart ->
                                LaunchedEffect(top) {
                                    val st = vm.state.first { !it.loading && (it.movie != null || it.series != null) }
                                    val item = if (st.movie != null) vm.moviePlayItem(fromStart) else st.series?.let { series ->
                                        val all = series.seasons.flatMap { it.episodes }
                                        val last = st.episodeProgress.values.maxByOrNull { it.updatedMs }?.let { p -> all.firstOrNull { it.remoteId == p.key.remoteId } }
                                        (last ?: all.firstOrNull())?.let { vm.episodePlayItem(it, fromStart) }
                                    }
                                    replaceTop(top.copy(autoPlay = null))
                                    item?.let { push(Screen.VodPlay(it)) }
                                }
                            }
                        }
                        is Screen.VodPlay -> {
                            // Task 91: the phone's Now Playing card needs poster art; PlayItem carries
                            // only ids, so resolve it here through the catalog (episode → its series).
                            var vodPoster by remember(top.item) { mutableStateOf<String?>(null) }
                            LaunchedEffect(top.item) {
                                val posterKey = progressPosterKey(top.item.key, top.item.parentId) ?: top.item.key
                                vodPoster = graph.catalog.poster(posterKey)?.posterUrl
                            }
                            VodPlayerRoute(
                                item = top.item, sources = graph.sources, userData = graph.userData, userAgent = graph.userAgent,
                                communitySkipLookup = graph.communitySkipLookup,
                                onPlayNext = { replaceTop(Screen.VodPlay(it)) },
                                // Task 97: queue handoff — open the title's detail page and play at once
                                // (resume point / next unwatched episode), Back then lands on that page.
                                onPlayQueued = { key -> replaceTop(Screen.Detail(key, autoPlay = false)) },
                                onExit = { pop() },
                                nowPlaying = graph.nowPlaying, nowPlayingControls = graph.nowPlayingControls,
                                posterUrl = vodPoster,
                                catalog = graph.catalog, visibility = graph.parental.visibility,
                                kidsTime = graph.kidsTime, kidsPinEnabled = pinEnabled, onRequestKidsPin = ::requestKidsExtension,
                            )
                        }
                        is Screen.Onboarding -> {
                            val vm = viewModel(key = "onboarding-${stack.size}") {
                                OnboardingViewModel(
                                    graph.sources, graph.sync, graph.userData, brand.features,
                                    newId = { UUID.randomUUID().toString() },
                                    syncScope = graph.appScope, skipWelcome = !top.firstRun,
                                    plexLinker = graph.plexLinker,
                                    editSourceId = top.editSourceId,
                                )
                            }
                            OnboardingRoute(vm, brand.appName, onFinished = { if (top.editSourceId != null) pop() else openSection(if (top.firstRun) "home" else "live") }, brand.disclaimer)
                        }
                        is Screen.Multiview -> {
                            // M1 (audit 73): the tiles are judged by graph.vodVisibility — the exact
                            // object ParentalRouteGate gates this route with — so gate and tiles can
                            // never disagree; the view model drops denied tiles and reports closed
                            // when none remain.
                            val vm = viewModel(key = "multiview") { MultiviewViewModel(graph.sources, graph.catalog, graph.epg, graph.clock, visibility = graph.vodVisibility, hiddenCategoryKeys = graph.userData.hiddenCategoryKeys()) }
                            val mvClosed by vm.closed.collectAsStateWithLifecycle()
                            LaunchedEffect(mvClosed) { if (mvClosed) pop() }
                            // The grid is the app-wide session: Back leaves it intact, re-entering resumes it.
                            val sessionChannels by graph.multiviewSession.channels.collectAsStateWithLifecycle()
                            MultiviewRoute(
                                vm, sessionChannels, graph.userAgent,
                                onPlay = { k, cat -> openCard(k, cat) },
                                onExit = { pop() },
                                onSwap = { old, new -> graph.multiviewSession.replace(old, new) },
                                onRemove = { k -> graph.multiviewSession.remove(k) },
                                onEnd = { graph.multiviewSession.clear(); pop() },
                                onSaveGroup = { appScope.launch { graph.multiviewGroups.saveAuto(graph.multiviewSession.channels.value) } },
                                kidsTime = graph.kidsTime,
                            )
                        }
                        is Screen.WhosWatching -> WhosWatchingRoute(
                            profilesVm,
                            onSelect = { p -> resolveProfile(p.id) },
                            onManage = { requestProfileAdmin { push(Screen.ManageProfiles) } },
                            onAdd = { requestProfileAdmin { profilesVm.openNew(); push(Screen.ProfileEdit(null)) } },
                        )
                        is Screen.ManageProfiles -> ManageProfilesRoute(
                            profilesVm,
                            onEdit = { p -> profilesVm.openEdit(p.id); push(Screen.ProfileEdit(p.id)) },
                            onAdd = { profilesVm.openNew(); push(Screen.ProfileEdit(null)) },
                            onDone = { pop() },
                        )
                        is Screen.ProfileEdit -> ProfileEditorRoute(profilesVm, onExit = { profilesVm.closeEditor(); pop() })
                        is Screen.Section -> Unit
                    }
                }
            }
        }
        // Task 106: the mini live player was stopped by a Kids limit. The full-screen players show
        // their own card; this covers the browsing screens the mini player lives on.
        if (kidsNotice && kidsState.blocked != null && top !is Screen.Player && !pinForSwitch) {
            Box(Modifier.fillMaxSize().background(OmniTheme.colors.background.copy(alpha = 0.92f)), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Column(
                    Modifier.width(640.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                        .background(OmniTheme.colors.surface).padding(28.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(com.yodesla.omniverse.designsystem.OmniSpacing.m),
                ) {
                    androidx.tv.material3.Text("Time is up for today", style = OmniTheme.type.title, color = OmniTheme.colors.textPrimary)
                    androidx.tv.material3.Text(
                        if (kidsState.blocked == com.yodesla.omniverse.core.data.KidsTimeReason.BEDTIME) "This profile is in its bedtime window." else "This profile has used up its daily watching limit.",
                        style = OmniTheme.type.body, color = OmniTheme.colors.textSecondary,
                    )
                    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(com.yodesla.omniverse.designsystem.OmniSpacing.m)) {
                        if (pinEnabled) com.yodesla.omniverse.designsystem.OmniButton("Enter PIN for 30 more minutes", onClick = ::requestKidsExtension, primary = true)
                        com.yodesla.omniverse.designsystem.OmniButton("Dismiss", onClick = { kidsNotice = false })
                    }
                }
            }
        }
        // Switch-PIN pad (task 59): leaving a Kids profile needs the parental PIN. Overlays everything.
        if (pinForSwitch) {
            Box(Modifier.fillMaxSize().background(OmniTheme.colors.background.copy(alpha = 0.94f))) {
                PinPad(
                    title = pendingAdminTitle ?: "Enter the parental PIN to switch profiles",
                    onDone = { pin ->
                        val target = pendingSwitch
                        val admin = pendingAdmin
                        appScope.launch {
                            val ok = (target != null || admin != null) && graph.parental.check(pin)
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                if (ok && target != null) doSwitch(target)
                                else if (ok && admin != null) {
                                    pinForSwitch = false; pendingAdmin = null; pendingAdminTitle = null; pinError = null
                                    admin()
                                } else pinError = "Wrong PIN. Try again."
                            }
                        }
                    },
                    onCancel = { pinForSwitch = false; pendingSwitch = null; pendingAdmin = null; pendingAdminTitle = null; pinError = null },
                    error = pinError,
                )
            }
        }
        // Update prompt overlays everything (renders nothing while Hidden).
        val updateVm = viewModel(key = "update") { UpdateViewModel(graph.updateChecker, graph.apkInstaller) }
        var lastUpdateCheck by remember { mutableStateOf(0L) }
        LifecycleResumeEffect(Unit) {
            // Back from "install unknown apps" settings → continue a pending install.
            updateVm.retryInstall()
            // TV apps stay alive for days: re-check on resume, at most every 1 h.
            val now = System.currentTimeMillis()
            if (now - lastUpdateCheck > UPDATE_CHECK_EVERY_MS) { lastUpdateCheck = now; updateVm.checkNow() }
            onPauseOrDispose { }
        }
        // Guide reminders: "Starting now" card over any screen; Watch tunes the channel.
        val reminderStore = remember(graph) { com.yodesla.omniverse.core.data.reminders.ReminderStore(graph.userData, graph.clock) }
        com.yodesla.omniverse.feature.live.guide.ReminderBanner(reminderStore, graph.clock, graph.parental.visibility) { key, categoryId ->
            stack.clear(); stack.add(Screen.Home); openCard(key, categoryId)
        }
        UpdatePrompt(updateVm)
        // Crash notice (task 78): a small card at the top, same family as the phone chip.
        if (crashCard) {
            Box(Modifier.fillMaxSize().padding(top = com.yodesla.omniverse.designsystem.OmniSpacing.m, end = com.yodesla.omniverse.designsystem.OmniSpacing.tvSide), contentAlignment = androidx.compose.ui.Alignment.TopEnd) {
                Box(
                    Modifier.background(OmniTheme.colors.elevated.copy(alpha = 0.92f), androidx.compose.foundation.shape.RoundedCornerShape(999.dp))
                        .padding(horizontal = com.yodesla.omniverse.designsystem.OmniSpacing.l, vertical = com.yodesla.omniverse.designsystem.OmniSpacing.s),
                ) {
                    androidx.tv.material3.Text(stringResource(R.string.crash_card_text), style = OmniTheme.type.caption, color = OmniTheme.colors.accent)
                }
            }
        }
        // Phone remote (task 76): a quiet chip so the viewer knows a phone is driving the TV.
        if (phoneState.connected) {
            Box(Modifier.fillMaxSize().padding(top = com.yodesla.omniverse.designsystem.OmniSpacing.m, end = com.yodesla.omniverse.designsystem.OmniSpacing.tvSide), contentAlignment = androidx.compose.ui.Alignment.TopEnd) {
                Box(
                    Modifier.background(OmniTheme.colors.elevated.copy(alpha = 0.92f), androidx.compose.foundation.shape.RoundedCornerShape(999.dp))
                        .padding(horizontal = com.yodesla.omniverse.designsystem.OmniSpacing.l, vertical = com.yodesla.omniverse.designsystem.OmniSpacing.s),
                ) {
                    androidx.tv.material3.Text("Phone connected", style = OmniTheme.type.caption, color = OmniTheme.colors.accent)
                }
            }
        }
    }
}

/** Recheck an open destination when the PIN session expires or the app returns from background. */
@Composable
private fun ParentalRouteGate(top: Screen, graph: AppGraph, onDenied: () -> Unit, content: @Composable () -> Unit) {
    // null until the real parental policy arrives: judging against a placeholder denied every
    // route on first composition and bounced a freshly opened channel back to Home.
    val visibility by graph.vodVisibility.collectAsStateWithLifecycle(initialValue = null as Visibility?)
    // A new key hides the previous destination immediately while the current category is checked.
    // Keyed on the destination ONLY: the policy re-emits on any settings write (e.g. saving the
    // player's picture mode), and keying on it tore down and rebuilt the player from the start.
    // The open destination stays mounted while the policy is re-checked; only a "no" closes it.
    key(top) {
        var allowed by remember { mutableStateOf<Boolean?>(null) }
        LaunchedEffect(visibility) {
            visibility?.let { allowed = routeIsVisible(top, graph, it) }
        }
        LaunchedEffect(allowed) { if (allowed == false) onDenied() }
        if (allowed == true) content()
        else Box(Modifier.fillMaxSize().background(OmniTheme.colors.background))
    }
}

private suspend fun routeIsVisible(top: Screen, graph: AppGraph, visible: Visibility): Boolean {
    val key = when (top) {
        is Screen.Player -> top.key
        is Screen.Detail -> top.key
        // Plex media versions save progress under a synthetic key. Gate the real catalog
        // movie/series, just as Continue Watching resolves that progress to its poster.
        is Screen.VodPlay -> progressPosterKey(top.item.key, top.item.parentId) ?: top.item.key
        // M1: Multiview survives only while SOME session channel passes the current LIVE visibility
        // (a channel whose row is gone cannot be validated, so it counts as denied); the view model
        // removes the individual denied/missing tiles.
        is Screen.Multiview -> return multiviewRouteAllowed(graph.multiviewSession.channels.value, graph.catalog, visible)
        else -> return true
    }
    val category = if (key.kind == ContentKind.LIVE) graph.catalog.channel(key)?.categoryId
        else graph.catalog.poster(key)?.categoryId
    return category?.let { visible(key.kind.name, key.sourceId.value, it.value) } ?: false
}

/**
 * M1: the Multiview route policy. The route is worth showing only while SOME channel passes the
 * current LIVE visibility (a channel whose row is gone cannot be validated, so it counts as
 * denied); the view model removes the individual denied/missing tiles.
 */
internal suspend fun multiviewRouteAllowed(
    channels: List<ContentKey>,
    catalog: com.yodesla.omniverse.core.data.CatalogRepository,
    visible: Visibility,
): Boolean = channels.any { key ->
    val category = catalog.channel(key)?.categoryId
    category != null && visible(key.kind.name, key.sourceId.value, category.value)
}

/** Task 111 H2: the Guide/Live Multiview entry is hidden while a Kids limit or bedtime block is up. */
internal fun multiviewEntryAllowed(kidsBlocked: Boolean): Boolean = !kidsBlocked

/**
 * M4 (audit 73): the destructive backup actions (Restore / Replace) need the parental PIN whenever
 * one is set — they rewrite every profile's data, so the profile-admin Kids-only gate is not enough.
 */
internal fun backupRestoreNeedsPin(pinEnabled: Boolean): Boolean = pinEnabled

private const val UPDATE_CHECK_EVERY_MS = 1 * 60 * 60_000L

@Composable
private fun SectionContent(
    s: Screen.Section,
    graph: AppGraph,
    lastPlayed: ContentKey?,
    openCard: (ContentKey, RemoteId?) -> Unit,
    push: (Screen) -> Unit,
    addSource: () -> Unit,
    onOpenNavigation: () -> Unit = {},
    onSearch: () -> Unit = {},
    onManageProfiles: () -> Unit = {},
    /** Runs [action] immediately, or after the parental PIN when one is set (M4: backup restore). */
    onRequirePin: (() -> Unit) -> Unit = { it() },
    onSetPickerAtStart: (Boolean, (Boolean) -> Unit) -> Unit = { enabled, update -> update(enabled) },
    /** Task 84b: guest mode "Create a profile" (shell opens the editor behind the PIN gate). */
    onCreateProfile: () -> Unit = {},
    /** Non-null while a live channel is playing: the corner box for the Guide / channel list. */
    miniPlayer: (@Composable (Modifier) -> Unit)? = null,
    /** Outlives this section (opening a player disposes it), so saves launched here finish. */
    appScope: kotlinx.coroutines.CoroutineScope,
) {
    when (s) {
        Screen.Home -> {
            val vm = viewModel(key = "home") { HomeViewModel(graph.catalog, graph.epg, graph.userData, graph.clock, visibility = graph.browseVisibility, upNext = com.yodesla.omniverse.core.data.UpNextResolver(graph.sources), tmdb = graph.tmdbEnricher, sourceAlert = graph.sourceHealth.alert(), myTeams = graph.myTeams) }
            val homeState by vm.state.collectAsStateWithLifecycle()
            // Task 101: cached rows on screen is the signal that lets the startup sync begin.
            LaunchedEffect(homeState) {
                if (homeState.loaded) graph.homeRowsShown(homeState.rows.size, homeState.rows.sumOf { it.cards.size })
            }
            val curProfile by graph.profiles.current().collectAsStateWithLifecycle(initialValue = null)
            // Task 84b: the Guest is "no profile" - Home shows no avatar chip in that state.
            HomeRoute(vm, onOpen = { card -> if (card.focusEpisode != null) push(Screen.Detail(card.open, focusEpisode = card.focusEpisode)) else openCard(card.open, card.categoryId) }, onOpenNavigation = onOpenNavigation, onPlay = { key, fromStart -> push(Screen.Detail(key, fromStart)) }, profile = curProfile?.takeIf { !it.isGuest }, onProfileClick = { push(Screen.WhosWatching) })
        }
        Screen.Sports -> {
            val vm = viewModel(key = "sports") {
                com.yodesla.omniverse.feature.live.sports.SportsViewModel(graph.epg, graph.clock, graph.browseVisibility,
                    com.yodesla.omniverse.core.data.reminders.ReminderStore(graph.userData, graph.clock),
                    feed = graph.sportsFeed,
                    onlineSetting = graph.userData.setting("sports_online").map { it != "false" },
                    saveOnline = { on -> graph.userData.putSetting("sports_online", on.toString()) }, follows = graph.followedTeams)
            }
            com.yodesla.omniverse.feature.live.sports.SportsRoute(vm, onWatch = { key, cat -> openCard(key, cat) }, onOpenNavigation = onOpenNavigation)
        }
        is Screen.Live -> {
            // One "Live" tab, two views: the channel list (Right opens the guide) and the full guide
            // grid (Back/Left returns to the full-width list). The last view used is remembered.
            val scope = appScope
            val savedView by graph.userData.setting(LIVE_VIEW_KEY).collectAsStateWithLifecycle(initialValue = null)
            // Task 107: Back from a channel opened from Search lands straight in the Guide on it.
            var view by remember { mutableStateOf<String?>(if (s.inGuide) "guide" else null) }
            val shown = view ?: savedView ?: "list"
            fun switchTo(v: String) { view = v; scope.launch { graph.userData.putSetting(LIVE_VIEW_KEY, v) } }
            if (shown == "guide") {
                // M2 (audit 73): Live / Guide use graph.vodVisibility — the same parental-PIN +
                // Kids-hard-filter policy as Home/Movies/Shows and the route gate.
                val vm = viewModel(key = "guide") { GuideViewModel(graph.sources, graph.catalog, graph.epg, graph.clock, visibility = graph.vodVisibility, favoritesVisibility = graph.favoritesVisibility, userData = graph.userData) }
                fun leaveGuide() { switchTo("list") }
                // The Guide shows whatever category is picked in the channel list's left column.
                val listVm = viewModel(key = "live") { LiveViewModel(graph.sources, graph.catalog, graph.epg, graph.userData, graph.clock, visibility = graph.vodVisibility, favoritesVisibility = graph.favoritesVisibility, langSummary = graph.categoryLanguageSummary) }
                val listCategory = listVm.state.collectAsStateWithLifecycle().value.selectedCategoryId
                LaunchedEffect(listCategory) {
                    // Task 84g: the Guide now handles Recently watched too, so sync every category.
                    listCategory?.let { vm.selectCategory(it) }
                }
                BackHandler { leaveGuide() }
                val kidsState by graph.kidsTime.state.collectAsStateWithLifecycle()
                GuideRoute(vm, onPlay = { k, cat -> openCard(k, cat) }, onExitLeft = ::leaveGuide, miniPlayer = miniPlayer, focusKey = lastPlayed, session = graph.multiviewSession, onOpenMultiview = { push(Screen.Multiview) }, multiviewEnabled = multiviewEntryAllowed(kidsState.blocked != null), onPlayCatchup = { e ->
                    val minutes = ((e.endMs - e.startMs + 59_999) / 60_000).toInt()
                    push(Screen.VodPlay(PlayItem(
                        request = PlaybackRequest.Catchup(e.key.sourceId, e.key.remoteId, e.startMs, minutes),
                        key = e.key, parentId = null, title = e.title, resumeMs = 0, trackProgress = false,
                    )))
                })
            } else {
                val vm = viewModel(key = "live") { LiveViewModel(graph.sources, graph.catalog, graph.epg, graph.userData, graph.clock, visibility = graph.vodVisibility, favoritesVisibility = graph.favoritesVisibility, langSummary = graph.categoryLanguageSummary) }
                // Back from a channel lands in the full Guide on that channel, not the list (Kory).
                LiveRoute(vm, onPlay = { k, cat -> switchTo("guide"); openCard(k, cat) }, onAddSource = addSource, playingKey = lastPlayed, onOpenGuide = { switchTo("guide") }, onOpenGuideForCategory = { switchTo("guide") }, onOpenNavigation = onOpenNavigation, miniPlayer = miniPlayer, onSearch = onSearch, session = graph.multiviewSession, onOpenMultiview = { push(Screen.Multiview) }, onMultiview = { keys -> graph.multiviewSession.setAll(keys); push(Screen.Multiview) }, onOpenLanguageFilter = { push(Screen.Settings) })
            }
        }
        Screen.Movies -> {
            val vm = viewModel(key = "browse-vod") { VodBrowseViewModel(ContentKind.VOD, graph.sources, graph.catalog, graph.userData, visibility = graph.browseVisibility, excludedCategoryKeys = graph.vodExcludedCategoryKeys, rottenTomatoes = graph.rottenTomatoes, tmdb = graph.tmdbEnricher, profiles = graph.profiles) }
            VodBrowseRoute(vm, "Movies", onPlay = { key, fromStart -> push(Screen.Detail(key, fromStart)) }, onOpenNavigation = onOpenNavigation, onSearch = onSearch) { push(Screen.Detail(it)) }
        }
        Screen.Shows -> {
            val vm = viewModel(key = "browse-series") { VodBrowseViewModel(ContentKind.SERIES, graph.sources, graph.catalog, graph.userData, visibility = graph.browseVisibility, excludedCategoryKeys = graph.vodExcludedCategoryKeys, rottenTomatoes = graph.rottenTomatoes, tmdb = graph.tmdbEnricher, profiles = graph.profiles) }
            VodBrowseRoute(vm, "Shows", onPlay = { key, fromStart -> push(Screen.Detail(key, fromStart)) }, onOpenNavigation = onOpenNavigation, onSearch = onSearch) { push(Screen.Detail(it)) }
        }
        is Screen.Search -> {
            val vm = viewModel(key = "search") { SearchViewModel(graph.search, graph.catalog, visibility = graph.browseVisibility,
                historySource = graph.userData.setting(com.yodesla.omniverse.feature.home.SEARCH_HISTORY_KEY),
                saveHistory = { graph.userData.putSetting(com.yodesla.omniverse.feature.home.SEARCH_HISTORY_KEY, it) },
                reminders = com.yodesla.omniverse.core.data.reminders.ReminderStore(graph.userData, graph.clock), clock = graph.clock,
                initialQuery = s.query) }
            // The VM outlives the section (key "search"), so a voice query arriving on a re-entry
            // must be pushed in explicitly; initialQuery only covers the VM's first creation.
            LaunchedEffect(s.query) { s.query?.let(vm::onQuery) }
            SearchRoute(vm, onOpen = { card -> openCard(card.open, card.categoryId) })
        }
        Screen.Settings -> {
            val vm = viewModel(key = "settings") { SettingsViewModel(graph.sources, graph.sync, graph.userData, graph.clock, graph.publicBuild, catalog = graph.catalog) }
            // Customize Home lives in Settings (not on Home itself): same HomeViewModel as the Home screen.
            var customizeHome by remember { mutableStateOf(false) }
            val curProfile by graph.profiles.current().collectAsStateWithLifecycle(initialValue = null)
            Box(Modifier.fillMaxSize()) {
                SettingsRoute(vm, graph.brand.appName, BuildConfig.VERSION_NAME, graph.brand.disclaimer, onAddSource = addSource, onOpenProfiles = onManageProfiles, onSetPickerAtStart = { enabled -> onSetPickerAtStart(enabled, vm::setPickerAtStart) }, guestMode = curProfile?.isGuest == true, onCreateProfile = onCreateProfile,
                    extraCategories = mapOf<SettingsCategory, SettingsSlot>(
                        SettingsCategory.Display to { OmniActionRow("Customize Home", "Open", onClick = { customizeHome = true }) },
                        SettingsCategory.AboutAndUpdates to {
                            // Manual update check (the app also checks on its own at most every 6 h).
                            val updateVm = viewModel(key = "update") { com.yodesla.omniverse.android.update.UpdateViewModel(graph.updateChecker, graph.apkInstaller) }
                            var checkedAt by remember { mutableStateOf<Long?>(null) }
                            val upd by updateVm.state.collectAsStateWithLifecycle()
                            OmniActionRow(
                                "Check for updates", "v${BuildConfig.VERSION_NAME}",
                                secondary = if (checkedAt != null && upd is com.yodesla.omniverse.android.update.UpdateUiState.Hidden) "You're on the latest version." else null,
                                onClick = { checkedAt = System.currentTimeMillis(); updateVm.checkNow() },
                            )
                            // Task 78: crash reports (local only).
                            DiagnosticsSection(graph)
                        },
                        SettingsCategory.ProfilesAndKids to { ParentalSection(graph) },
                        SettingsCategory.BackupRestore to { BackupSection(graph, requirePin = onRequirePin) },
                        SettingsCategory.PhoneRemote to {
                            // Task 76: keep the LAN server up while this pane is showing, even with the switch Off,
                            // so the QR/code are ready the moment the viewer turns it on.
                            androidx.compose.runtime.DisposableEffect(Unit) {
                                graph.phoneRemote.setScreenVisible(true)
                                onDispose { graph.phoneRemote.setScreenVisible(false) }
                            }
                            val prState by graph.phoneRemote.state.collectAsStateWithLifecycle()
                            com.yodesla.omniverse.feature.home.phoneremote.PhoneRemotePanel(
                                prState, { graph.phoneRemote.setEnabled(it) }, Modifier,
                            )
                        },
                    ),
                    sourceExtras = { id -> SourceExtras(graph, id, onReenterLogin = { push(Screen.Onboarding(firstRun = false, editSourceId = id)) }) })
                if (customizeHome) {
                    val homeVm = viewModel(key = "home") { HomeViewModel(graph.catalog, graph.epg, graph.userData, graph.clock, visibility = graph.browseVisibility, upNext = com.yodesla.omniverse.core.data.UpNextResolver(graph.sources), tmdb = graph.tmdbEnricher, sourceAlert = graph.sourceHealth.alert(), myTeams = graph.myTeams) }
                    val hs by homeVm.state.collectAsStateWithLifecycle()
                    com.yodesla.omniverse.feature.home.HomeCustomizePanel(homeVm, hs.panel, hs.compact, onDismiss = { customizeHome = false }, modifier = Modifier.fillMaxSize())
                }
            }
        }
        Screen.DevGuide -> DevGuideScreen(onExit = {})
    }
}

private const val LIVE_VIEW_KEY = "live_view"
