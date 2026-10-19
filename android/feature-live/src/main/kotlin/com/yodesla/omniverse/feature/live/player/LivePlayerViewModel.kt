package com.yodesla.omniverse.feature.live.player
import com.yodesla.omniverse.feature.live.BIG_LIST
import com.yodesla.omniverse.feature.live.UnifiedLiveCatalog
import com.yodesla.omniverse.feature.live.liveChannelIdentity
import com.yodesla.omniverse.feature.live.liveScope
import com.yodesla.omniverse.feature.live.LiveProviderFilter

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yodesla.omniverse.core.data.CatalogRepository
import com.yodesla.omniverse.core.data.ChannelRow
import com.yodesla.omniverse.core.data.Clock
import com.yodesla.omniverse.core.data.EpgRepository
import com.yodesla.omniverse.core.data.SourceRepository
import com.yodesla.omniverse.core.data.UserDataRepository
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.PlaybackRequest
import com.yodesla.omniverse.core.model.PlaybackSpec
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.source.SourceException
import com.yodesla.omniverse.player.FRAME_RATE_MATCH_SETTING
import com.yodesla.omniverse.player.FailureKind
import com.yodesla.omniverse.player.SUBTITLE_STYLE_BACKGROUND
import com.yodesla.omniverse.player.SUBTITLE_STYLE_COLOR
import com.yodesla.omniverse.player.SUBTITLE_STYLE_POSITION
import com.yodesla.omniverse.player.SUBTITLE_STYLE_SIZE
import com.yodesla.omniverse.player.SubtitleStyleRaw
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class BannerUi(
    val number: Int?,
    val name: String,
    val logoUrl: String?,
    val nowTitle: String?,
    val nowTimes: String?,
    val nowProgress: Float?,
    val nextTitle: String?,
    val nextStart: String?,
    /** End of the current programme (Task 77 "end of this programme" sleep choice); null without EPG. */
    val nowEndMs: Long? = null,
)

@Immutable
data class ChannelSwitchUi(
    val key: ContentKey,
    val name: String,
    val sourceName: String,
)

@Immutable
data class LivePlayerUiState(
    /** What the banner shows — updates instantly on every zap press. */
    val banner: BannerUi? = null,
    val channelKey: ContentKey? = null,
    val bannerVisible: Boolean = true,
    /** The stream to play. Changes only after surfing settles (see [SURF_SETTLE_MS]). */
    val spec: PlaybackSpec? = null,
    val error: String? = null,
    /** Task 93: same channel name on another source, offered after this stream fails. */
    val switchTo: ChannelSwitchUi? = null,
    val channelSources: List<ChannelSwitchUi> = emptyList(),
    val sourceNotice: String? = null,
    /**
     * Task 94: the stream to pre-buffer on the hidden second engine — the next channel in the
     * direction last zapped. Non-null only while pre-loading is allowed (setting on, no
     * connection-limit refusal this session) and the viewer has settled on a channel.
     */
    val preload: PlaybackSpec? = null,
)

/**
 * Live playback logic (PLAN.md §7, §8.3 Simple-mode keys). The PlayerEngine itself lives in the UI
 * layer (it needs a Context); this VM decides WHAT plays and WHEN.
 *
 * Channel surfing: each Up/Down press moves the banner immediately, but the stream is only opened
 * once presses stop for [SURF_SETTLE_MS]. Rapid surfing therefore opens one upstream connection
 * instead of one per press (rule 7) and feels instant because the banner never waits.
 */
class LivePlayerViewModel(
    private val sources: SourceRepository,
    private val catalog: CatalogRepository,
    private val epg: EpgRepository,
    private val userData: UserDataRepository,
    private val clock: Clock,
    /** Parental locks: "All channels" zapping skips locked categories. */
    private val visibility: kotlinx.coroutines.flow.Flow<com.yodesla.omniverse.core.data.Visibility> = com.yodesla.omniverse.core.data.ShowEverything,
    /** Task 94: "Pre-load next channel" (per-profile, default ON). Off = no second engine ever. */
    private val preloadEnabled: kotlinx.coroutines.flow.Flow<Boolean> = flowOf(true),
) : ViewModel() {

    private val _state = MutableStateFlow(LivePlayerUiState())
    val state: StateFlow<LivePlayerUiState> = _state.asStateFlow()
    private val unified = UnifiedLiveCatalog(sources, catalog)
    private val providerFilter = LiveProviderFilter(sources, userData)
    private val attemptedSources = mutableSetOf<ContentKey>()
    private var sourceChoicesJob: Job? = null
    private var tuneGeneration = 0
    private val allowedVisibility = visibility.combine(userData.hiddenCategoryKeys()) { vis, hidden ->
        { kind: String, source: String, category: String -> vis(kind, source, category) && "$kind|$source|$category" !in hidden }
    }

    /** Task 84j: per-profile subtitle appearance for the live player (style only, no delay). */
    val subtitleStyle = combine(
        userData.setting(SUBTITLE_STYLE_SIZE),
        userData.setting(SUBTITLE_STYLE_BACKGROUND),
        userData.setting(SUBTITLE_STYLE_COLOR),
        userData.setting(SUBTITLE_STYLE_POSITION),
    ) { size, background, color, position -> SubtitleStyleRaw(size, background, color, position) }

    /** Task 100: the profile's "Match content frame rate" choice, applied by [EngineSurface]. */
    val matchFrameRate = userData.setting(FRAME_RATE_MATCH_SETTING)

    private var categoryId: RemoteId? = null
    /**
     * Channels Up/Down steps through, loaded once when playback starts. Zapping is then plain index
     * math on the main thread: rapid presses can't race each other, hidden channels never appear, and
     * the virtual Favorites/Recent lists zap like any category.
     */
    private var order: List<ContentKey> = emptyList()
    private var index = -1
    private var current: ChannelRow? = null
    private var playing: ContentKey? = null
    private var previous: ContentKey? = null
    private var settleJob: Job? = null
    private var bannerJob: Job? = null
    private var watchedJob: Job? = null

    // ---- Task 94: next-channel pre-buffering ----
    /** Direction of the last zap; the spare engine pre-buffers the next channel in THIS direction. */
    private var lastForward = true
    private var preloadSetting = true
    /** Session-only: a connection-limit refusal (403/509) turns pre-loading off until re-entry. */
    private var preloadDisabled = false
    private var preloadKey: ContentKey? = null
    private var preloadSpec: PlaybackSpec? = null
    private var preloadJob: Job? = null

    init {
        viewModelScope.launch {
            preloadEnabled.collect { on ->
                preloadSetting = on
                if (!on) clearPreload()
            }
        }
    }

    fun start(key: ContentKey, categoryId: RemoteId) {
        this.categoryId = categoryId
        viewModelScope.launch {
            val row = catalog.channel(key) ?: return@launch showError("This channel is no longer available.")
            if (!allowedVisibility.first()("LIVE", key.sourceId.value, row.categoryId.value)) return@launch showError("This channel is unavailable for this profile.")
            moveTo(row, settleMs = 0)
            if (categoryId.value == com.yodesla.omniverse.feature.live.LiveViewModel.ALL || categoryId.value.startsWith("__live:")) {
                val rows = unified.rows(categoryId.value, key.sourceId, allowedVisibility.first(), providerFilter.hiddenIds.first())
                // Identity match over every channel: off Main (All channels is tens of thousands).
                val identify = {
                    val id = liveChannelIdentity(row.name)
                    rows.map { it.key } to rows.indexOfFirst { liveChannelIdentity(it.name) == id }
                }
                val (o, i) = if (rows.size > BIG_LIST) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { identify() } else identify()
                order = o
                index = i
                schedulePreload()
                return@launch
            }
            order = when (categoryId.value) {
                com.yodesla.omniverse.feature.live.LiveViewModel.FAVORITES -> userData.favorites(com.yodesla.omniverse.core.model.ContentKind.LIVE).first()
                com.yodesla.omniverse.feature.live.LiveViewModel.RECENT -> userData.recentChannels().first()
                else -> catalog.channelOrder(key.sourceId, categoryId)
            }
            val hidden = providerFilter.hiddenIds.first()
            order = order.filter { it.sourceId.value !in hidden }
            index = order.indexOf(key)
            schedulePreload()
        }
    }

    fun zapNext() = zap(forward = true)
    fun zapPrevious() = zap(forward = false)

    /** Back-to-previous-channel ("last channel" key). */
    fun lastChannel() {
        val prev = previous ?: return
        viewModelScope.launch {
            catalog.channel(prev)?.let { moveTo(it, settleMs = 0) }
            order.indexOf(prev).takeIf { it >= 0 }?.let { index = it }
        }
    }

    /**
     * Task 81: the "Try again" button after the engine gave up on this channel. Same channel, fresh
     * stream: clearing [playing] forces a re-resolve (new URL/token — what a 403 "max connections"
     * refusal needs), and the null→spec transition makes the app-level engine re-open even when the
     * resolved spec is byte-identical to the failed one. The engine's retry policy resets with it.
     */
    fun retry() {
        val row = current ?: return
        attemptedSources.clear()
        tuneGeneration++
        playing = null
        settleJob?.cancel()
        clearPreload()
        _state.update { it.copy(spec = null, error = null, switchTo = null) }
        settleJob = viewModelScope.launch { open(row) }
    }

    /** After engine retries are exhausted, try each visible provider once for this channel. */
    fun onStreamFailed(kind: FailureKind = FailureKind.OTHER) {
        val row = current ?: return
        // Task 94: a provider refusal (403/509 = too many connections) means a second connection
        // would be refused too — stop pre-loading for the rest of the session.
        if (PreloadGuard.shouldDisable(kind)) clearPreload(preloadDisabled = true)
        if (!attemptedSources.add(row.key)) return
        val generation = tuneGeneration
        clearPreload()
        viewModelScope.launch {
            val alt = unified.alternatives(row, allowedVisibility.first()).firstOrNull { candidate -> attemptedSources.none { it.sourceId == candidate.key.sourceId } }
            if (current?.key != row.key || generation != tuneGeneration) return@launch
            if (alt == null) {
                _state.update { it.copy(error = "No more available sources for this channel. Try again or choose a source.") }
            } else {
                val name = sources.sources().first().firstOrNull { it.id == alt.key.sourceId }?.name ?: "backup source"
                moveTo(alt, settleMs = 0, resetFailures = false)
                _state.update { it.copy(sourceNotice = "Switched to $name after the stream failed") }
            }
        }
    }

    /** Task 94: the hidden pre-buffer engine failed; a refusal disables pre-loading this session. */
    fun onPreloadFailed(kind: FailureKind) {
        if (PreloadGuard.shouldDisable(kind)) clearPreload(preloadDisabled = true)
    }

    fun switchChannel(key: ContentKey) {
        if (key == current?.key && _state.value.error != null) { retry(); return }
        viewModelScope.launch {
            val row = catalog.channel(key) ?: return@launch showError("This channel is no longer available.")
            if (!allowedVisibility.first()("LIVE", key.sourceId.value, row.categoryId.value)) return@launch
            _state.update { it.copy(switchTo = null) }
            moveTo(row, settleMs = 0)
        }
    }

    fun showBanner() = flashBanner()

    fun hideBanner() {
        bannerJob?.cancel()
        _state.update { it.copy(bannerVisible = false) }
    }

    /** Called by the UI when the engine reports playback of the current spec. */
    fun onPlaying() {
        val key = playing ?: return
        watchedJob?.cancel()
        // Only count as "watched" after 5 s — surfing past a channel shouldn't pollute Recents.
        watchedJob = viewModelScope.launch {
            delay(WATCHED_AFTER_MS)
            if (playing == key) userData.recordChannelWatched(key)
        }
    }

    private fun zap(forward: Boolean) {
        if (order.size < 2 || index < 0) return
        lastForward = forward
        index = Math.floorMod(index + if (forward) 1 else -1, order.size)
        val target = order[index]
        viewModelScope.launch {
            val row = catalog.channel(target) ?: return@launch
            // Presses may resolve out of order: only the channel the index still points at wins.
            if (order.getOrNull(index) == target) moveTo(row, settleMs = SURF_SETTLE_MS)
        }
    }

    private suspend fun moveTo(row: ChannelRow, settleMs: Long, resetFailures: Boolean = true) {
        tuneGeneration++
        if (resetFailures) attemptedSources.clear()
        current = row
        _state.update { it.copy(channelKey = row.key, banner = bannerFor(row, null), error = null, switchTo = null, channelSources = emptyList(), sourceNotice = null) }
        sourceChoicesJob?.cancel()
        sourceChoicesJob = viewModelScope.launch {
            val names = sources.sources().first().associate { it.id to it.name }
            val choices = (listOf(row) + unified.alternatives(row, allowedVisibility.first())).map {
                ChannelSwitchUi(it.key, it.name, names[it.key.sourceId] ?: it.key.sourceId.value)
            }
            if (current?.key == row.key) _state.update { it.copy(channelSources = choices) }
        }
        flashBanner()
        // Guide text arrives a beat later; the banner never waits for it.
        viewModelScope.launch {
            val nn = row.epgKey?.let { epg.nowNext(row.key.sourceId, listOf(it), clock.nowMs())[it] }
            if (current == row && nn != null) _state.update { it.copy(banner = bannerFor(row, nn)) }
        }
        settleJob?.cancel()
        preloadJob?.cancel()
        settleJob = viewModelScope.launch {
            if (settleMs > 0) delay(settleMs)
            open(row)
        }
    }

    private suspend fun open(row: ChannelRow) {
        if (playing == row.key) return
        // Task 94: zapping onto the channel the spare engine already primed reuses its spec object
        // (the UI swaps engines on reference equality) instead of resolving a second stream.
        val cached = preloadSpec
        if (preloadKey == row.key && cached != null) {
            preloadKey = null
            preloadSpec = null
            if (playing != null) previous = playing
            playing = row.key
            _state.update { it.copy(spec = cached, error = null, switchTo = null, preload = null) }
            schedulePreload()
            return
        }
        clearPreload()
        val source = sources.contentSource(row.key.sourceId)
            ?: return showError("This source was removed.")
        val spec = try {
            source.playback(PlaybackRequest.Live(row.key.sourceId, row.key.remoteId))
        } catch (e: SourceException) {
            if (current?.key == row.key) { showError("This channel can't be played right now."); onStreamFailed() }
            return
        }
        if (playing != null) previous = playing
        playing = row.key
        _state.update { it.copy(spec = spec, error = null, switchTo = null, preload = null) }
        schedulePreload()
    }

    /**
     * Task 94: after [PRELOAD_AFTER_MS] on this channel, resolve the next channel in the direction
     * last zapped and publish it as [LivePlayerUiState.preload] for the hidden engine to buffer.
     * Cancelled by every zap; skipped when the setting is off or a refusal disabled it this session.
     */
    private fun schedulePreload() {
        preloadJob?.cancel()
        if (!preloadSetting || preloadDisabled) return
        if (order.size < 2 || index < 0) return
        val key = playing ?: return
        preloadJob = viewModelScope.launch {
            delay(PRELOAD_AFTER_MS)
            if (playing != key || !preloadSetting || preloadDisabled) return@launch
            val nextIdx = nextPreloadIndex(order.size, index, lastForward) ?: return@launch
            val nextKey = order.getOrNull(nextIdx) ?: return@launch
            if (nextKey == key) return@launch
            val row = catalog.channel(nextKey) ?: return@launch
            val source = sources.contentSource(row.key.sourceId) ?: return@launch
            val spec = try {
                source.playback(PlaybackRequest.Live(row.key.sourceId, row.key.remoteId))
            } catch (e: SourceException) {
                return@launch
            }
            if (playing != key || !preloadSetting || preloadDisabled) return@launch
            preloadKey = nextKey
            preloadSpec = spec
            _state.update { it.copy(preload = spec) }
        }
    }

    /** Task 111 L2: the Player route left composition (popped). Drop the resolved preload spec so the
     *  surviving VM on the Live back stack does not retain a token-bearing stream URL. */
    fun onScreenExit() {
        clearPreload()
    }

    /** Drop the pending/primed preload. [preloadDisabled] also turns it off for the whole session. */
    private fun clearPreload(preloadDisabled: Boolean = false) {
        if (preloadDisabled) this.preloadDisabled = true
        preloadJob?.cancel()
        preloadJob = null
        preloadKey = null
        preloadSpec = null
        _state.update { it.copy(preload = null) }
    }

    private fun flashBanner() {
        bannerJob?.cancel()
        _state.update { it.copy(bannerVisible = true) }
        bannerJob = viewModelScope.launch {
            delay(BANNER_MS)
            _state.update { it.copy(bannerVisible = false) }
        }
    }

    internal fun sameChannelName(a: String, b: String): Boolean =
        liveChannelIdentity(a) == liveChannelIdentity(b)

    private fun showError(message: String) = _state.update { it.copy(error = message) }

    private fun bannerFor(row: ChannelRow, nn: com.yodesla.omniverse.core.data.NowNext?): BannerUi {
        val now = clock.nowMs()
        val cur = nn?.now
        return BannerUi(
            number = row.number,
            name = row.name,
            logoUrl = row.logoUrl,
            nowTitle = cur?.title,
            nowTimes = cur?.let { "${hhmm(it.startMs)} – ${hhmm(it.endMs)}" },
            nowProgress = cur?.let { ((now - it.startMs).toFloat() / (it.endMs - it.startMs).coerceAtLeast(1)).coerceIn(0f, 1f) },
            nextTitle = nn?.next?.title,
            nextStart = nn?.next?.let { hhmm(it.startMs) },
            nowEndMs = cur?.endMs,
        )
    }

    private fun hhmm(ms: Long): String = com.yodesla.omniverse.designsystem.ClockFormat.format(ms)

    companion object {
        /** PLAN.md §9: a zap request goes out within 150 ms of the last press. */
        const val SURF_SETTLE_MS = 150L
        const val BANNER_MS = 4_000L
        const val WATCHED_AFTER_MS = 5_000L
        /** Task 94: dwell before the next channel is pre-buffered (rule: only after 2 s on a channel). */
        const val PRELOAD_AFTER_MS = 2_000L

        /**
         * Task 94: index of the channel to pre-buffer — the next one in the direction last zapped,
         * wrapping at both ends exactly like zapping does. Null when there is nothing to zap through.
         */
        internal fun nextPreloadIndex(size: Int, index: Int, forward: Boolean): Int? =
            if (size < 2 || index < 0) null else Math.floorMod(index + if (forward) 1 else -1, size)
    }
}
