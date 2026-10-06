package com.yodesla.omniverse.feature.live

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.yodesla.omniverse.core.data.CatalogRepository
import com.yodesla.omniverse.core.data.ChannelRow
import com.yodesla.omniverse.core.data.Clock
import com.yodesla.omniverse.core.data.EpgRepository
import com.yodesla.omniverse.core.data.SourceRepository
import com.yodesla.omniverse.core.data.UserDataRepository
import com.yodesla.omniverse.core.data.reminders.Reminder
import com.yodesla.omniverse.core.data.reminders.ReminderStore
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.SourceId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale
import com.yodesla.omniverse.core.data.ShowEverything
import com.yodesla.omniverse.core.data.Visibility

@Immutable
data class CategoryUi(val id: String, val name: String, val special: Boolean = false)

@Immutable
data class NowNextUi(
    val nowTitle: String?,
    val nowProgress: Float?,
    val nextTitle: String?,
    val nextStart: String?,
    /** "8:00 – 9:45 PM" for the preview pane. */
    val nowTimes: String? = null,
    /** The next programme's window, so the hold-OK menu can offer "Remind me" for it. */
    val nextStartMs: Long? = null,
    val nextEndMs: Long? = null,
)

@Immutable
data class LiveUiState(
    val loading: Boolean = true,
    val noSources: Boolean = false,
    val sourceId: SourceId? = null,
    val sourceName: String = "",
    val categories: List<CategoryUi> = emptyList(),
    val selectedCategoryId: String? = null,
    val nowNext: Map<String, NowNextUi> = emptyMap(),
    val favorites: Set<ContentKey> = emptySet(),
    /** One-shot hint after a hold-OK menu action ("Reminder set"); cleared on the next category change. */
    val toast: String? = null,
    /** Task 84h: the language filter chip ("English only · 42 hidden"), null while the filter is off. */
    val langFilter: com.yodesla.omniverse.core.data.categories.CategoryLanguageSummary? = null,
    /** Task 84m: hold-OK category menu (provider categories only; pinned entries have none), null = closed. */
    val menuCategoryId: String? = null,
    /** Task 84m: one-shot "Category hidden · Undo" toast after hiding a category. */
    val hiddenNotice: com.yodesla.omniverse.designsystem.HiddenNotice? = null,
)

sealed interface LiveEvent {
    data class PlayChannel(val key: ContentKey, val categoryId: RemoteId) : LiveEvent
    /** Task 84g: OK on a category opens the full Guide filtered to it. */
    data class OpenGuide(val categoryId: String) : LiveEvent
}

/** PLAN.md §8.3 Simple-mode Live TV: categories + channel list with now/next. */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class LiveViewModel(
    private val sources: SourceRepository,
    private val catalog: CatalogRepository,
    private val epg: EpgRepository,
    private val userData: UserDataRepository,
    private val clock: Clock,
    /** Parental locks: hidden categories (and their channels in Favorites/Recent) while locked. */
    private val visibility: Flow<Visibility> = ShowEverything,
    /** Task 84h: Favorites / Recently watched obey parental + Kids but NEVER the category-language
     *  filter - a favourite always stays reachable. Defaults to [visibility] so callers without a
     *  separate language filter keep the pre-84h behavior. */
    private val favoritesVisibility: Flow<Visibility> = visibility,
    /** Task 84h: content for the header chip, null while the language filter is off. */
    private val langSummary: Flow<com.yodesla.omniverse.core.data.categories.CategoryLanguageSummary?> = flowOf(null),
) : ViewModel() {

    private val _state = MutableStateFlow(LiveUiState())
    val state: StateFlow<LiveUiState> = _state.asStateFlow()

    private val events = Channel<LiveEvent>(Channel.BUFFERED)
    val eventFlow: Flow<LiveEvent> = events.receiveAsFlow()

    /** Task 67: saved Multiview groups for this profile (settings key `multiview_groups`). */
    val groups = com.yodesla.omniverse.feature.live.multiview.MultiviewGroupStore(userData)

    /** Task 67: programme reminders, so the Live list's hold-OK menu can "Remind me" for the next show. */
    private val reminders = ReminderStore(userData, clock)

    private val selection = MutableStateFlow<Pair<SourceId, String>?>(null)
    /** Task 84g: true while a category is grabbed for hold-OK reorder; incoming lists must not clobber the move. */
    @Volatile private var moving = false
    /**
     * Task 84m: hidden categories (hold-OK › Hide category, Settings › Sources › Hidden categories)
     * drop out of the category list, "All channels", and saved-group opening — exactly like locked
     * ones — even for profiles the catalog SQL (default profile) does not filter.
     */
    private val categoryVisibility: Flow<Visibility> =
        visibility.combine(userData.hiddenCategoryKeys()) { v, h -> { k, s, c -> v(k, s, c) && "$k|$s|$c" !in h } }
    private val nowNextRequests = MutableSharedFlow<List<String>>(extraBufferCapacity = 8)
    private var lastRequested: List<String> = emptyList()
    /** Latest parental policy, so opening a saved group can skip channels it now denies. */
    private var latestVisibility: Visibility = { _, _, _ -> true }

    val channels: Flow<PagingData<ChannelRow>> = selection
        .flatMapLatest { sel ->
            when {
                sel == null -> flowOf(PagingData.empty())
                sel.second == FAVORITES -> combine(userData.favorites(ContentKind.LIVE), favoritesVisibility) { keys, vis ->
                    PagingData.from(keys.mapNotNull { k -> catalog.channel(k)?.takeIf { vis("LIVE", it.key.sourceId.value, it.categoryId.value) }?.copy(categoryId = RemoteId(FAVORITES)) })
                }
                sel.second == ALL -> combine(catalog.categories(sel.first, ContentKind.LIVE), categoryVisibility) { cats, vis ->
                    // Locked categories are excluded in SQL; a channel still shows if it is also in an open one.
                    cats.filterNot { vis("LIVE", sel.first.value, it.remoteId.value) }.map { it.remoteId.value }.toSet()
                }.distinctUntilChanged().flatMapLatest { excluded ->
                    Pager(PagingConfig(pageSize = 60, prefetchDistance = 30, enablePlaceholders = false)) {
                        catalog.channelsAll(sel.first, excluded)
                    }.flow
                }
                sel.second == RECENT -> combine(userData.recentChannels(), favoritesVisibility) { keys, vis ->
                    PagingData.from(keys.mapNotNull { k -> catalog.channel(k)?.takeIf { vis("LIVE", it.key.sourceId.value, it.categoryId.value) }?.copy(categoryId = RemoteId(RECENT)) })
                }
                else -> Pager(PagingConfig(pageSize = 60, prefetchDistance = 30, enablePlaceholders = false)) {
                    catalog.channels(sel.first, RemoteId(sel.second))
                }.flow
            }
        }
        .cachedIn(viewModelScope)

    init {
        viewModelScope.launch {
            val all = sources.sources().first()
            val src = all.firstLiveCapable()
            if (src == null) {
                _state.update { it.copy(loading = false, noSources = true) }
                return@launch
            }
            _state.update { it.copy(sourceId = src.id, sourceName = src.name) }
            observeCategories(src.id)
        }
        userData.favorites(ContentKind.LIVE)
            .onEach { favs -> _state.update { it.copy(favorites = favs.toSet()) } }
            .launchIn(viewModelScope)
        categoryVisibility
            .onEach { latestVisibility = it }
            .launchIn(viewModelScope)
        langSummary
            .onEach { s -> _state.update { it.copy(langFilter = s) } }
            .launchIn(viewModelScope)
        nowNextRequests
            .debounce(150)
            .onEach { keys -> loadNowNext(keys) }
            .launchIn(viewModelScope)
    }

    /**
     * Keep now/next honest while the screen is open. Started lazily by [loadNowNext] (so it only ever
     * runs once the UI has actually requested programme data) rather than as an unconditional loop —
     * an always-on `while(true) delay` schedules an unbounded future delay that deadlocks
     * `advanceUntilIdle()` in unit tests.
     */
    private var tickerJob: Job? = null
    private fun ensureTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = viewModelScope.launch {
            while (isActive) {
                delay(60_000)
                if (lastRequested.isNotEmpty()) loadNowNext(lastRequested)
            }
        }
    }

    private fun observeCategories(sourceId: SourceId) {
        combine(
            catalog.categories(sourceId, ContentKind.LIVE),
            userData.favorites(ContentKind.LIVE),
            userData.recentChannels(),
            categoryVisibility,
            userData.setting(ORDER_KEY),
        ) { cats, favs, recents, vis, saved ->
            favs.isNotEmpty() to buildList {
                // Always offered, so people can find them before they have any favourites.
                val pinned = listOfNotNull(
                    CategoryUi(FAVORITES, "Favorites", special = true),
                    CategoryUi(ALL, "All channels", special = true),
                    if (recents.isNotEmpty()) CategoryUi(RECENT, "Recently watched", special = true) else null,
                )
                val providers = cats.filter { vis("LIVE", sourceId.value, it.remoteId.value) }.map { CategoryUi(it.remoteId.value, it.name) }
                // Task 84g: the order saved from this profile (hold-OK move), same rule as VOD.
                addAll(mergeCategoryOrder(pinned, providers, saved.orEmpty().split('\n').filter { it.isNotBlank() }, { it.id }))
            }
        }
            .distinctUntilChanged()
            .onEach { (hasFavs, cats) ->
                if (moving) return@onEach // the viewer is mid-move; their live order wins until they drop it
                _state.update { s ->
                    // First visit: Favorites if there are any, otherwise All channels.
                    val selected = s.selectedCategoryId?.takeIf { id -> cats.any { it.id == id } }
                        ?: if (hasFavs) FAVORITES else ALL
                    s.copy(loading = false, categories = cats, selectedCategoryId = selected)
                }
                _state.value.selectedCategoryId?.let { selection.value = sourceId to it }
            }
            .launchIn(viewModelScope)
    }

    fun selectCategory(id: String) {
        val src = _state.value.sourceId ?: return
        if (_state.value.selectedCategoryId == id) return
        _state.update { it.copy(selectedCategoryId = id, toast = null) }
        selection.value = src to id
    }

    /**
     * Task 84g: OK on a category opens the full Guide filtered to it (Kory). The selection moves
     * first so the Guide — which follows the list's selection — and the trip back agree.
     * Dwell-selecting a category still just loads its channels; only OK opens the Guide.
     */
    fun openGuideForCategory(id: String) {
        selectCategory(id)
        events.trySend(LiveEvent.OpenGuide(id))
    }

    /** Task 84g: hold-OK reorder — nudge provider category [id] one step ([delta] = -1 up, +1 down); pinned entries never move. */
    fun moveCategory(id: String, delta: Int) {
        moving = true
        _state.update { s ->
            val pinned = s.categories.filter { it.special }
            val list = s.categories.filterNot { it.special }.toMutableList()
            val i = list.indexOfFirst { it.id == id }
            val j = i + delta
            if (i < 0 || j !in list.indices) return@update s
            list.add(j, list.removeAt(i))
            s.copy(categories = pinned + list)
        }
    }

    /** Task 84g: drop the grabbed category — save the order for this profile (same key/format as VOD's `category_order_VOD`). */
    fun finishMove() {
        if (!moving) return
        val ids = _state.value.categories.filterNot { it.special }.map { it.id }
        viewModelScope.launch {
            userData.putSetting(ORDER_KEY, ids.joinToString("\n"))
            moving = false
        }
    }

    /**
     * Task 84m: hold-OK on a provider category opens its menu (Move / Hide category / Cancel)
     * instead of picking it up. Pinned entries (Favorites, All channels, Recently watched) have no menu.
     */
    fun openCategoryMenu(id: String) {
        val cat = _state.value.categories.firstOrNull { it.id == id } ?: return
        if (cat.special) return
        _state.update { it.copy(menuCategoryId = id) }
    }

    fun closeCategoryMenu() {
        _state.update { it.copy(menuCategoryId = null) }
    }

    /**
     * Task 84m: Hide category — the category leaves the list at once (and All / the Guide / group
     * opening via [categoryVisibility]); selection and focus move to the next category (or the
     * previous one when it was last); the Undo toast restores it.
     */
    fun hideCategory(id: String) {
        val s = _state.value
        val cat = s.categories.firstOrNull { it.id == id } ?: return
        if (cat.special) return
        val src = s.sourceId ?: return
        val rest = s.categories.filterNot { it.id == id }
        val focus = rest.getOrNull(s.categories.indexOf(cat)) ?: rest.lastOrNull()
        val selected = focus?.id ?: ALL
        _state.update {
            it.copy(
                categories = rest,
                selectedCategoryId = selected,
                menuCategoryId = null,
                hiddenNotice = com.yodesla.omniverse.designsystem.HiddenNotice(id, cat.name),
            )
        }
        selection.value = src to selected
        viewModelScope.launch { userData.setCategoryHidden(src, ContentKind.LIVE, id, hidden = true) }
    }

    /** Task 84m: Undo — show the hidden category again and clear the toast. */
    fun undoHideCategory() {
        val n = _state.value.hiddenNotice ?: return
        val src = _state.value.sourceId ?: return
        _state.update { it.copy(hiddenNotice = null) }
        viewModelScope.launch { userData.setCategoryHidden(src, ContentKind.LIVE, n.categoryId, hidden = false) }
    }

    /** Task 84m: the Undo toast timed out. */
    fun clearHiddenNotice() {
        _state.update { it.copy(hiddenNotice = null) }
    }

    /** UI reports the epg keys of rows on screen; debounced so fast scrolling doesn't hammer the DB. */
    fun requestNowNext(epgKeys: List<String>) {
        if (epgKeys.isEmpty()) return
        nowNextRequests.tryEmit(epgKeys)
    }

    private suspend fun loadNowNext(keys: List<String>) {
        lastRequested = keys
        val at = clock.nowMs()
        val src = _state.value.sourceId ?: return
        val result = epg.nowNext(src, keys, at).mapValues { (_, nn) ->
            val now = nn.now
            NowNextUi(
                nowTitle = now?.title,
                nowProgress = now?.let { ((at - it.startMs).toFloat() / (it.endMs - it.startMs).coerceAtLeast(1)).coerceIn(0f, 1f) },
                nextTitle = nn.next?.title,
                nextStart = nn.next?.let { hhmm(it.startMs) },
                nowTimes = now?.let { "${hhmm(it.startMs)} – ${hhmm(it.endMs)}" },
                nextStartMs = nn.next?.startMs,
                nextEndMs = nn.next?.endMs,
            )
        }
        _state.update { it.copy(nowNext = it.nowNext + result) }
        ensureTicker()
    }

    fun toggleFavorite(row: ChannelRow) {
        viewModelScope.launch { userData.setFavorite(row.key, row.key !in _state.value.favorites) }
    }

    /**
     * Task 67: "Remind me" from the Live list's hold-OK menu — sets (or clears) a reminder for this
     * channel's NEXT programme. No-op when there is no future programme to remind about.
     */
    fun remindNext(row: ChannelRow) {
        val nn = row.epgKey?.let { _state.value.nowNext[it] } ?: return
        val start = nn.nextStartMs ?: return
        val end = nn.nextEndMs ?: return
        if (start <= clock.nowMs()) return
        val r = Reminder(row.key.sourceId.value, row.key.remoteId.value, row.categoryId.value, start, end, nn.nextTitle ?: "", row.name)
        viewModelScope.launch {
            val set = reminders.toggle(r)
            _state.update { it.copy(toast = if (set) "Reminder set for ${hhmm(start)}" else "Reminder removed") }
        }
    }

    fun onChannelClick(row: ChannelRow) {
        events.trySend(LiveEvent.PlayChannel(row.key, row.categoryId))
    }

    /**
     * Task 67: the channels from a saved group that can actually be opened now — a channel whose row
     * is gone, or whose category the CURRENT policy denies, is skipped. Callers open the group only
     * when at least [com.yodesla.omniverse.feature.live.multiview.MultiviewLayout.MIN_TILES] survive.
     */
    suspend fun openableGroupChannels(keys: List<ContentKey>): List<ContentKey> = keys.filter { k ->
        val row = catalog.channel(k) ?: return@filter false
        latestVisibility("LIVE", k.sourceId.value, row.categoryId.value)
    }

    private fun hhmm(ms: Long): String = com.yodesla.omniverse.designsystem.ClockFormat.format(ms)

    companion object {
        const val FAVORITES = "__favorites"
        const val RECENT = "__recent"
        const val ALL = com.yodesla.omniverse.core.data.ALL_CHANNELS
        /** Task 84g: per-profile category order, same `category_order_<KIND>` family as VOD (profile-scoped + backup-safe in core/data). */
        const val ORDER_KEY = "category_order_LIVE"
    }
}
