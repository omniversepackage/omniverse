package com.yodesla.omniverse.feature.live.guide

import com.yodesla.omniverse.core.data.reminders.Reminder
import com.yodesla.omniverse.core.data.reminders.ReminderStore

import kotlinx.coroutines.flow.flowOf

import com.yodesla.omniverse.core.data.UserDataRepository

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yodesla.omniverse.core.data.CatalogRepository
import com.yodesla.omniverse.core.data.ChannelRow
import com.yodesla.omniverse.core.data.Clock
import com.yodesla.omniverse.core.data.EpgRepository
import com.yodesla.omniverse.core.data.SourceRepository
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.SourceId
import com.yodesla.omniverse.core.model.TimeWindow
import com.yodesla.omniverse.designsystem.epg.EpgGridData
import com.yodesla.omniverse.designsystem.epg.GridChannel
import com.yodesla.omniverse.designsystem.epg.GridJump
import com.yodesla.omniverse.designsystem.epg.GridProgramme
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import com.yodesla.omniverse.core.data.ShowEverything
import com.yodesla.omniverse.core.data.Visibility
import com.yodesla.omniverse.core.data.guide.GuideFilter
import com.yodesla.omniverse.core.data.guide.GuideFilterClassifier
import com.yodesla.omniverse.feature.live.LiveViewModel
import com.yodesla.omniverse.feature.live.firstLiveCapable
import com.yodesla.omniverse.feature.live.mergeCategoryOrder

@Immutable
data class GuideCategory(val id: String, val name: String)

@Immutable
data class GuideUiState(
    val loading: Boolean = true,
    val sourceId: SourceId? = null,
    val categories: List<GuideCategory> = emptyList(),
    val selectedCategoryId: String? = null,
    /** Task 98: header filter chip applied within the selected category; remembered per profile. */
    val filter: GuideFilter = GuideFilter.ALL,
    /** Stable per category so the grid keeps its caches while the user moves around. */
    val grid: EpgGridData? = null,
    val focusedTitle: String? = null,
    val focusedDetail: String? = null,
    /** Header pieces for the focused cell (Midnight Cinema guide header). */
    val focusedChannel: String? = null,
    /** Bare channel name (no number): drives the logo fallback's initials and tint. */
    val focusedChannelName: String? = null,
    val focusedLogo: String? = null,
    val focusedTimes: String? = null,
    val focusedArchive: Boolean = false,
    val focusedLive: Boolean = false,
    /** Focused programme hasn't started: OK sets a reminder. */
    val focusedFuture: Boolean = false,
    val focusedFavorite: Boolean = false,
    /** One-shot hint after Hold OK ("Added to Favorites"); cleared on the next focus move. */
    val toast: String? = null,
    /** Bumped whenever reminders change so the grid redraws its reminder marks. */
    val reminderVersion: Int = 0,
    /** Local midnights the guide really has data for (header day strip), today first. */
    val dayStarts: List<Long> = emptyList(),
    /** Where the grid is looking (its selected time): highlights the strip, anchors the next jump. */
    val viewMs: Long = 0L,
    /** The grid is on the live edge, so the "Now" action has nothing to do. */
    val atNow: Boolean = true,
    /** One-shot request that scrolls the grid to a time (media keys, day strip). */
    val jump: GridJump? = null,
)

sealed interface GuideEvent {
    data class PlayChannel(val key: ContentKey, val categoryId: RemoteId) : GuideEvent
    /** A finished programme inside the channel's archive window. */
    data class PlayCatchup(val key: ContentKey, val startMs: Long, val endMs: Long, val title: String) : GuideEvent
}

/** Full-control TV guide (PLAN.md §8.4) over synced EPG data. */
class GuideViewModel(
    private val sources: SourceRepository,
    private val catalog: CatalogRepository,
    private val epg: EpgRepository,
    private val clock: Clock,
    private val visibility: Flow<Visibility> = ShowEverything,
    private val userData: UserDataRepository? = null,
    /** Task 84h: Favorites / Recently watched obey parental + Kids but NEVER the category-language
     *  filter - a favourite always stays reachable. Defaults to [visibility] so callers that have
     *  no separate language filter keep the pre-84h behavior. */
    private val favoritesVisibility: Flow<Visibility> = visibility,
) : ViewModel() {

    private val _state = MutableStateFlow(GuideUiState())
    val state: StateFlow<GuideUiState> = _state.asStateFlow()
    private val events = Channel<GuideEvent>(Channel.BUFFERED)
    val eventFlow: Flow<GuideEvent> = events.receiveAsFlow()
    private var channels: List<ChannelRow> = emptyList()
    private var favorites by mutableStateOf<Set<ContentKey>>(emptySet())
    private var categoryJob: Job? = null
    private var currentVisibility: Visibility = { _, _, _ -> true }
    private var currentFavoritesVisibility: Visibility = { _, _, _ -> true }
    private var lockedCategories: Set<String> = emptySet()
    private var requestedCategory: String? = null
    /** End of the last programme stored for this source (0 = unknown): how far forward a jump may go. */
    private var maxEndMs: Long = 0L
    /** Which source [maxEndMs] describes; the guide's extent is re-read only when that changes. */
    private var maxEndSource: SourceId? = null
    /** Makes repeated jumps to the same time distinct requests for the grid. */
    private var jumpSeq: Long = 0L
    private val favoritesFlow: Flow<List<ContentKey>> = userData?.favorites(ContentKind.LIVE) ?: flowOf(emptyList())
    private val recentsFlow: Flow<List<ContentKey>> = userData?.recentChannels() ?: flowOf(emptyList())
    /** Task 84g: the order saved by hold-OK move in the Live list, so both lists agree. */
    private val savedOrderFlow: Flow<String?> = userData?.setting(LiveViewModel.ORDER_KEY) ?: flowOf(null)
    /** Task 98: the filter chip last picked on this profile, restored when the guide opens. */
    private val filterFlow: Flow<String?> = userData?.setting(FILTER_KEY) ?: flowOf(null)
    /** Category names by id, so a chip can judge a channel by its category as well as its name. */
    private var categoryNames: Map<String, String> = emptyMap()
    /** Last value seen on [filterFlow]: only a real change re-applies it, so a chip press is never undone by its own write. */
    private var lastRememberedFilter: GuideFilter? = null
    /**
     * Task 84m: hidden categories (hold-OK › Hide category, Settings › Sources › Hidden categories)
     * count as locked here too — gone from the category list and from "All channels", and a change
     * in them re-filters the grid, exactly like a parental unlock does.
     */
    private val categoryVisibility: Flow<Visibility> =
        visibility.combine(userData?.hiddenCategoryKeys() ?: flowOf(emptySet())) { v, h -> { k, s, c -> v(k, s, c) && "$k|$s|$c" !in h } }
    private val reminders = userData?.let { ReminderStore(it, clock) }
    @Volatile private var reminderIds: Set<String> = emptySet()

    /** Whether the programme starting at [startMs] on grid [row] has a reminder (read at draw time). */
    fun isReminded(row: Int, startMs: Long): Boolean {
        val ch = channels.getOrNull(row) ?: return false
        return "${ch.key.sourceId.value}|${ch.key.remoteId.value}|$startMs" in reminderIds
    }

    init {
        viewModelScope.launch { favoritesVisibility.collect { currentFavoritesVisibility = it } }
        reminders?.reminders?.let { flow ->
            viewModelScope.launch {
                flow.collect { list ->
                    reminderIds = list.mapTo(HashSet()) { it.id }
                    _state.update { it.copy(reminderVersion = it.reminderVersion + 1) }
                }
            }
        }
        viewModelScope.launch {
            val src = sources.sources().first().firstLiveCapable() ?: run {
                _state.update { it.copy(loading = false) }
                return@launch
            }
            _state.update { it.copy(sourceId = src.id) }
            // Re-filters when parental locks change (e.g. unlocked in Settings).
            combine(catalog.categories(src.id, ContentKind.LIVE), categoryVisibility, favoritesFlow, recentsFlow, savedOrderFlow) { all, vis, favs, recents, saved ->
                val real = all.filter { vis("LIVE", src.id.value, it.remoteId.value) }.map { GuideCategory(it.remoteId.value, it.name) }
                val locked = all.filterNot { vis("LIVE", src.id.value, it.remoteId.value) }.map { it.remoteId.value }.toSet()
                // Favorites + All channels are always offered (Favorites explains itself when empty).
                val pinned = listOfNotNull(
                    GuideCategory(FAVORITES, "★ Favorites"),
                    GuideCategory(ALL, "All channels"),
                    if (recents.isNotEmpty()) GuideCategory(RECENT, "Recently watched") else null,
                )
                // Task 84g: provider categories follow the order saved from the Live list.
                Triple(mergeCategoryOrder(pinned, real, saved.orEmpty().split('\n').filter { it.isNotBlank() }, { it.id }), vis to locked, favs.toSet())
            }.combine(filterFlow) { base, savedFilter -> base to GuideFilter.fromName(savedFilter) }
                .collect { (base, rememberedFilter) ->
                val (cats, visAndLocked, favs) = base
                val (vis, locked) = visAndLocked
                // Compare what is actually locked, not the lambda: ANY settings write (a reminder,
                // a toggle) re-emits the parental flow with a new but equivalent lambda, and
                // reloading the grid then threw the viewer back to "now".
                val visibilityChanged = locked != lockedCategories
                lockedCategories = locked
                val favoritesChanged = favorites != favs
                val categoriesChanged = _state.value.categories != cats
                // Task 98: only a value that really changed on the setting flow re-applies — the
                // echo of the viewer's own chip press must not undo it, and a profile switch must.
                val filterChanged = rememberedFilter != lastRememberedFilter && rememberedFilter != _state.value.filter
                lastRememberedFilter = rememberedFilter
                currentVisibility = vis
                favorites = favs
                categoryNames = cats.associate { it.id to it.name }
                _state.update { it.copy(categories = cats, filter = if (filterChanged) rememberedFilter else it.filter) }
                val current = _state.value.selectedCategoryId
                val selected = current?.takeIf { id -> cats.any { it.id == id } }
                    ?: requestedCategory?.takeIf { id -> cats.any { it.id == id } }
                    ?: if (favs.isNotEmpty()) FAVORITES else ALL
                if (selected == null) {
                    categoryJob?.cancel()
                    channels = emptyList()
                    _state.update { it.copy(selectedCategoryId = null, grid = null, focusedTitle = null, focusedDetail = null) }
                } else if (selected != current || visibilityChanged || categoriesChanged || filterChanged ||
                    (favoritesChanged && (selected == FAVORITES || _state.value.filter == GuideFilter.FAVORITES))) {
                    selectCategory(selected, force = true)
                }
            }
        }
    }

    fun selectCategory(id: String) = selectCategory(id, force = false)

    private fun selectCategory(id: String, force: Boolean) {
        // Asked before the source/categories loaded (Guide opened from the list): apply once they do.
        val src = _state.value.sourceId ?: run { requestedCategory = id; return }
        if (!force && _state.value.selectedCategoryId == id && _state.value.grid != null) return
        categoryJob?.cancel()
        channels = emptyList()
        val vis = currentVisibility
        _state.update { it.copy(selectedCategoryId = id, loading = true, grid = null, focusedTitle = null, focusedDetail = null, focusedChannel = null, focusedChannelName = null, focusedLogo = null, focusedTimes = null) }
        categoryJob = viewModelScope.launch {
            val favVis = currentFavoritesVisibility
            val list = if (id == FAVORITES) {
                favoritesFlow.first().mapNotNull { k -> catalog.channel(k)?.takeIf { favVis("LIVE", it.key.sourceId.value, it.categoryId.value) } }
            } else if (id == ALL) {
                catalog.channelListAll(src, lockedCategories)
            } else if (id == RECENT) {
                recentsFlow.first().mapNotNull { k -> catalog.channel(k)?.takeIf { favVis("LIVE", it.key.sourceId.value, it.categoryId.value) } }
            } else catalog.channelList(src, RemoteId(id)).filter { vis("LIVE", it.key.sourceId.value, it.categoryId.value) }
            // Task 98: the header chip filters within the category; the grid only ever sees the rows that pass.
            val shown = applyFilter(list)
            channels = shown
            if (maxEndSource != src) {
                // One scan per source per visit: the guide's extent does not change when the viewer
                // switches category, and the query reads the whole programme table.
                maxEndSource = src
                maxEndMs = epg.maxProgrammeEnd(src)
            }
            val now = clock.nowMs()
            // A new category starts on "now" (that is where the grid begins); the strip follows the
            // data this source really has instead of a fixed 7-day guess.
            _state.update { it.copy(loading = false, grid = GridData(shown), dayStarts = guideDayStarts(now, maxEndMs), viewMs = now, atNow = true, jump = null) }
        }
    }

    /** Filter chip: applies within the current category and is remembered for this profile. */
    fun selectFilter(filter: GuideFilter) {
        if (_state.value.filter == filter) return
        _state.update { it.copy(filter = filter) }
        val repo = userData
        if (repo != null) viewModelScope.launch { repo.putSetting(FILTER_KEY, filter.name) }
        val id = _state.value.selectedCategoryId ?: return
        selectCategory(id, force = true)
    }

    private fun applyFilter(list: List<ChannelRow>): List<ChannelRow> {
        val filter = _state.value.filter
        if (filter == GuideFilter.ALL) return list
        val names = categoryNames
        return list.filter { GuideFilterClassifier.matches(filter, it.name, names[it.categoryId.value], it.key in favorites) }
    }

    fun onFocused(row: Int, programme: GridProgramme?) {
        val ch = channels.getOrNull(row) ?: return
        val title = programme?.title ?: "No information"
        val detail = buildString {
            append(ch.number?.let { "$it  " } ?: "").append(ch.name)
            if (programme != null) append("  ·  ").append(hhmm(programme.startMs)).append(" – ").append(hhmm(programme.endMs))
            if (programme?.hasArchive == true) append("  ·  Catch-up available")
        }
        val now = clock.nowMs()
        _state.update {
            it.copy(
                focusedTitle = title, focusedDetail = detail,
                focusedChannel = listOfNotNull(ch.number?.toString(), ch.name).joinToString("  "),
                focusedChannelName = ch.name,
                focusedLogo = ch.logoUrl,
                focusedTimes = programme?.let { p -> "${hhmm(p.startMs)} – ${hhmm(p.endMs)}" },
                focusedArchive = programme?.hasArchive == true,
                focusedLive = programme != null && now >= programme.startMs && now < programme.endMs,
                focusedFuture = programme != null && programme.startMs > now,
                focusedFavorite = ch.key in favorites,
                toast = null,
            )
        }
    }

    fun onSelect(row: Int, programme: GridProgramme?) {
        val ch = channels.getOrNull(row) ?: return
        // Task 84h: a favourite in a language-filtered category is still reachable and playable.
        if (!currentFavoritesVisibility("LIVE", ch.key.sourceId.value, ch.categoryId.value)) return
        if (programme?.hasArchive == true) {
            events.trySend(GuideEvent.PlayCatchup(ch.key, programme.startMs, programme.endMs, programme.title))
            return
        }
        // A programme that hasn't started: OK sets (or clears) a reminder instead of tuning.
        val store = reminders
        if (programme != null && store != null && programme.startMs > clock.nowMs()) {
            val r = Reminder(ch.key.sourceId.value, ch.key.remoteId.value, ch.categoryId.value,
                programme.startMs, programme.endMs, programme.title, ch.name)
            viewModelScope.launch {
                val set = store.toggle(r)
                _state.update { it.copy(toast = if (set) "Reminder set for ${hhmm(programme.startMs)}" else "Reminder removed") }
            }
            return
        }
        events.trySend(GuideEvent.PlayChannel(ch.key, RemoteId(_state.value.selectedCategoryId ?: return)))
    }

    /** Hold OK in the grid: toggle the row's channel as a favourite. */
    fun toggleFavorite(row: Int) {
        val ch = channels.getOrNull(row) ?: return
        if (!currentFavoritesVisibility("LIVE", ch.key.sourceId.value, ch.categoryId.value)) return
        val repo = userData ?: return
        val add = ch.key !in favorites
        favorites = if (add) favorites + ch.key else favorites - ch.key
        _state.update { it.copy(focusedFavorite = add, toast = if (add) "Added to Favorites" else "Removed from Favorites") }
        viewModelScope.launch { repo.setFavorite(ch.key, add) }
    }

    /** Grid row of [key] in the current category, or -1. */
    fun rowOf(key: ContentKey): Int = channels.indexOfFirst { it.key == key }

    /** The channel at grid [row], for the hold-OK menu (Multiview toggle needs its key). */
    fun channelKeyAt(row: Int): ContentKey? = channels.getOrNull(row)?.key

    /**
     * Fast-forward / Channel Up ([deltaMs] > 0) and Rewind / Channel Down (< 0): jump two hours at a
     * time. [fromMs] is the grid's own selected time, so a jump continues from where the viewer
     * scrolled instead of from a stale copy here. Returns the landing time, or null when the guide's
     * edge is already reached (catch-up floor behind, last stored programme ahead) — the key is then
     * ignored rather than throwing the viewer somewhere else.
     */
    fun jumpBy(deltaMs: Long, fromMs: Long): Long? {
        val now = clock.nowMs()
        val target = clampJump(fromMs, deltaMs, catchupFloorMs(now, maxCatchupDays()), guideCeilingMs(now, maxEndMs))
        return if (target == fromMs) null else publishJump(target)
    }

    /** "Now": back to the live edge on the same channel row (Guide key / Play-Pause / header button). */
    fun jumpToNow(): Long? = if (_state.value.atNow) null else publishJump(clock.nowMs())

    /** Day strip: 18:00 (prime time) of day [index], keeping the focused row. */
    fun jumpToDay(index: Int): Long? {
        val day = _state.value.dayStarts.getOrNull(index) ?: return null
        val now = clock.nowMs()
        return publishJump(primeTimeMs(day, catchupFloorMs(now, maxCatchupDays()), guideCeilingMs(now, maxEndMs)))
    }

    /** The grid reports every move, so jumps and the strip follow where the viewer actually is. */
    fun onViewTime(ms: Long) {
        if (_state.value.viewMs == ms) return
        _state.update { it.copy(viewMs = ms, atNow = isAtNow(ms, clock.nowMs())) }
    }

    private fun publishJump(targetMs: Long): Long {
        jumpSeq++
        _state.update { it.copy(jump = GridJump(jumpSeq, targetMs), viewMs = targetMs, atNow = isAtNow(targetMs, clock.nowMs())) }
        return targetMs
    }

    /** Widest catch-up window in this category: how far left the guide may go. */
    private fun maxCatchupDays(): Int = channels.maxOfOrNull { it.catchupDays } ?: 0

    private inner class GridData(private val list: List<ChannelRow>) : EpgGridData {
        override val channelCount = list.size
        override fun channel(index: Int): GridChannel = list[index].let { GridChannel(it.key.remoteId.value, it.number, it.name, it.logoUrl, it.key in favorites) }
        override suspend fun programmes(index: Int, fromMs: Long, toMs: Long): List<GridProgramme> {
            val ch = list.getOrNull(index) ?: return emptyList()
            val key = ch.epgKey ?: return emptyList()
            val now = clock.nowMs()
            return epg.programmes(ch.key.sourceId, key, TimeWindow(fromMs, toMs)).map {
                GridProgramme(
                    it.startMs, it.endMs, it.title,
                    // Archive only exists for finished programmes within the channel's catch-up window.
                    hasArchive = ch.catchupDays > 0 && it.endMs <= now && it.startMs >= now - ch.catchupDays * DAY_MS,
                )
            }
        }
    }

    companion object {
        private const val DAY_MS = 24 * 60 * 60_000L
        /** Same id as LiveViewModel's, so the player zaps within favourites. */
        const val FAVORITES = com.yodesla.omniverse.feature.live.LiveViewModel.FAVORITES
        const val ALL = com.yodesla.omniverse.feature.live.LiveViewModel.ALL
        /** Task 84g: same id as LiveViewModel's, so "Recently watched" opens the Guide with that filter. */
        const val RECENT = com.yodesla.omniverse.feature.live.LiveViewModel.RECENT
        /** Task 98: per-profile setting holding the last filter chip ([GuideFilter.name]). */
        const val FILTER_KEY = "guide_filter"
    }

    private fun hhmm(ms: Long): String = com.yodesla.omniverse.designsystem.ClockFormat.format(ms)
}
