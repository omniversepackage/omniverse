package com.yodesla.omniverse.feature.home
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import com.yodesla.omniverse.designsystem.requestFocusWhenReady

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Text
import com.yodesla.omniverse.core.data.CatalogRepository
import com.yodesla.omniverse.core.data.PosterRow
import com.yodesla.omniverse.core.data.SearchRepository
import com.yodesla.omniverse.core.data.SearchSort
import com.yodesla.omniverse.core.data.search.closest
import com.yodesla.omniverse.core.data.search.normalize
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.designsystem.FocusPivot
import com.yodesla.omniverse.designsystem.OmniSpacing
import com.yodesla.omniverse.designsystem.OmniTheme
import com.yodesla.omniverse.designsystem.PosterCard
import com.yodesla.omniverse.designsystem.displayTitle
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import com.yodesla.omniverse.core.data.ShowEverything
import com.yodesla.omniverse.core.data.Visibility
import com.yodesla.omniverse.feature.home.R

@Immutable
data class SearchState(val query: String = "", val rows: List<HomeRow> = emptyList(), val searched: Boolean = false, val message: String? = null,
    val favoriteChannels: Set<com.yodesla.omniverse.core.model.ContentKey> = emptySet(),
    val loading: Boolean = false,
    /** Most recent first, at most [SEARCH_HISTORY_MAX]. */
    val history: List<String> = emptyList(),
    /** Closest visible title offered when a search finds nothing (task 82 "Did you mean …?"). */
    val didYouMean: String? = null,
    /** Result opened most recently (task 118): Back from that title hands focus back to this card. */
    val focusCardId: String? = null,
    /** Task 122: the filter chips currently on. */
    val filters: com.yodesla.omniverse.core.data.SearchFilters = com.yodesla.omniverse.core.data.SearchFilters.None,
    val sort: SearchSort = SearchSort.RELEVANCE,
    /** Decades offered as chips (only decades that actually have titles), newest first. */
    val decades: List<Int> = emptyList(),
    /** Genre names offered as chips, A–Z, capped at [SEARCH_GENRE_CHIPS_MAX]. */
    val genres: List<String> = emptyList(),
    /** Filtered results hit the current per-kind limit: more matches exist beyond the shown cards. */
    val truncated: Boolean = false,
    /** Per-kind limit in force for filtered discovery; "Show more" raises it (task 122 review). */
    val limit: Int = SEARCH_PAGE,
    /** Task 143: a search is pending or running (from the first keystroke to the last result row) — drives the spinner. */
    val busy: Boolean = false,
    /** Task 143: what the running search is doing right now ("Checking the TV guideâ¦"). */
    val note: String? = null,
    /** Task 143: which section this search belongs to (its placeholder and what it can return). */
    val scope: com.yodesla.omniverse.core.data.SearchScope = com.yodesla.omniverse.core.data.SearchScope.ALL)

const val SEARCH_HISTORY_MAX = 8
/** Filtered discovery page size (cards per kind per pass); "Show more" adds another page. */
const val SEARCH_PAGE = 24
/** Ceiling for filtered discovery: a whole decade of anime is reachable, the row stays bounded. */
const val SEARCH_PAGE_MAX = 240
/** Genre chips cap: the row scrolls, but a D-pad should never have to cross a hundred chips. */
const val SEARCH_GENRE_CHIPS_MAX = 20
/** Task 130: the "Anime & Animation" chip already covers these, so they are retired from the
 *  genre chip row (lowercase; compared case-insensitively) to leave ONE control. */
val SEARCH_RETIRED_GENRE_CHIPS = setOf("anime", "animation", "animated", "cartoon", "cartoons")
/** Rating chips offered (toggle the same value again to clear it). */
val SEARCH_RATING_CHIPS = listOf(7f, 8f, 9f)
/** Per-profile UserData setting holding recent searches, newline-separated. */
const val SEARCH_HISTORY_KEY = "search_history"

/** Puts [term] first, drops case-insensitive duplicates and keeps the newest [SEARCH_HISTORY_MAX]. */
fun pushSearchHistory(history: List<String>, term: String): List<String> {
    val t = term.trim().replace('\n', ' ').takeIf { it.length >= 2 } ?: return history
    return (listOf(t) + history.filterNot { it.equals(t, ignoreCase = true) }).take(SEARCH_HISTORY_MAX)
}

/** Unified search (PLAN.md §6.3): channels, movies and shows from the local FTS index. */
@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SearchViewModel(
    private val search: SearchRepository,
    private val catalog: CatalogRepository,
    private val visibility: Flow<Visibility> = ShowEverything,
    /** Lets "On TV" results that haven't started set a reminder; null hides that behaviour. */
    private val reminders: com.yodesla.omniverse.core.data.reminders.ReminderStore? = null,
    private val clock: com.yodesla.omniverse.core.data.Clock = com.yodesla.omniverse.core.data.Clock { System.currentTimeMillis() },
    /** Saved recent searches (newline-separated) and how to save them; defaults keep history in memory only. */
    historySource: Flow<String?> = kotlinx.coroutines.flow.flowOf(null),
    private val saveHistory: suspend (String) -> Unit = {},
    /** Text pre-filled from a voice / global-search intent (task 80); null = start empty. */
    private val initialQuery: String? = null,
    /** Task 122: SQL-side category exclusions for the filtered search (locked/Kids/language/hidden
     *  libraries). [visibility] still double-checks every row; this keeps hidden rows out of the
     *  TMDB dedup winner, so a locked Plex copy never hides its visible IPTV twin. */
    private val excludedCategoryKeys: Flow<Collection<String>> = kotlinx.coroutines.flow.flowOf(emptySet()),
    private val userData: com.yodesla.omniverse.core.data.UserDataRepository? = null,
    /** Task 143: the section this search is scoped to (Movies / Shows / Live TV search only their own content). */
    private val scope: com.yodesla.omniverse.core.data.SearchScope = com.yodesla.omniverse.core.data.SearchScope.ALL,
    private val tmdb: com.yodesla.omniverse.core.data.metadata.TmdbEnricher? = null,
) : ViewModel() {
    @Volatile private var programmeCards: Map<String, com.yodesla.omniverse.core.data.ProgrammeHit> = emptyMap()
    private val _state = MutableStateFlow(SearchState(scope = scope))
    val state: StateFlow<SearchState> = _state.asStateFlow()
    private val query = MutableStateFlow("")
    /** Task 122: the filter chips. Setting it re-runs the current search at once (no debounce). */
    private val filtersFlow = MutableStateFlow(com.yodesla.omniverse.core.data.SearchFilters.None)
    private val sortFlow = MutableStateFlow(SearchSort.RELEVANCE)
    /** Task 122 review: per-kind limit for filtered discovery. "Show more" raises it; a new query
     *  or filter change resets it, so a stale deep page never follows a different filter set. */
    private val limitFlow = MutableStateFlow(SEARCH_PAGE)

    init {
        tmdb?.artUpdated?.onEach {
            val current = _state.value.rows
            val cards = current.flatMap { it.cards }.filter { !it.isChannel && it.titleIdentity?.namespace == "tmdb" }
            if (cards.isNotEmpty()) {
                val refreshed = catalog.refillArt(cards.map { card ->
                    PosterRow(card.open, card.title, card.image, null, null, tmdbId = card.titleIdentity?.externalId)
                }).associateBy { it.key }
                _state.update { state -> if (state.rows != current) state else state.copy(rows = state.rows.map { row ->
                    row.copy(cards = row.cards.map { card -> card.copy(image = refreshed[card.open]?.posterUrl ?: card.image) })
                }) }
            }
        }?.launchIn(viewModelScope)
        userData?.favorites(ContentKind.LIVE)?.onEach { keys ->
            _state.update { it.copy(favoriteChannels = keys.toSet()) }
        }?.launchIn(viewModelScope)
        // Load the guide's title list while the user types, so "On TV" with no match costs nothing.
        if (scope.live) viewModelScope.launch { kotlinx.coroutines.delay(1_500); try { val t = System.currentTimeMillis(); search.warmGuideSearch(clock.nowMs()); android.util.Log.i("Search", "guide titles loaded in ${System.currentTimeMillis() - t} ms") } catch (c: CancellationException) { throw c } catch (_: Exception) {} }
        // Voice / global search (task 80): the spoken text arrives pre-filled and is searched at once.
        initialQuery?.trim()?.takeIf { it.isNotEmpty() }?.let { q ->
            _state.update { it.copy(query = q) }
            query.value = q
        }
        historySource.onEach { raw ->
            val list = raw.orEmpty().split('\n').map { it.trim() }.filter { it.isNotEmpty() }.take(SEARCH_HISTORY_MAX)
            _state.update { it.copy(history = list) }
        }.launchIn(viewModelScope)
        // Re-runs on parental lock/unlock and filter changes too, not just on typing.
        combine(query.debounce(150).distinctUntilChanged(), visibility, combine(filtersFlow, sortFlow) { f, sort -> f to sort }, excludedCategoryKeys.distinctUntilChanged(), limitFlow) { q, vis, filterSort, off, limit ->
            SearchInput(q, vis, filterSort.first, off, limit, filterSort.second)
        }.transformLatest { input ->
            val q = input.q; val vis = input.vis; val f = input.f; val off = input.off; val limit = input.limit; val sort = input.sort
            if (q.isBlank() && !f.active) {
                programmeCards = emptyMap()
                emit(SearchResults(emptyList(), false, null))
                return@transformLatest
            }
            // Clear the previous query immediately. The title section can appear while guide search
            // is still running, and transformLatest prevents an old query from publishing late rows.
            programmeCards = emptyMap()
            emit(SearchResults(emptyList(), false, null, loading = true, busy = true, note = "Searching…"))
            // Task 143: channels and the guide lookup start NOW, beside the title search, instead of after it.
            val live = !f.active && scope.live
            val par = kotlinx.coroutines.CoroutineScope(kotlin.coroutines.coroutineContext)
            val channelsJob = if (live) par.async { val t = System.currentTimeMillis(); val r = try { buildChannelRows(q, vis, off) } catch (c: CancellationException) { throw c } catch (_: Exception) { emptyList() }; android.util.Log.i("Search", "channels q='$q' -> ${r.sumOf { it.cards.size }} in ${System.currentTimeMillis() - t} ms"); r } else null
            val programmesJob = if (live) par.async { val t = System.currentTimeMillis(); val r = try { buildProgrammeRows(q, vis, off) } catch (c: CancellationException) { throw c } catch (_: Exception) { Built(emptyList(), emptyMap()) }; android.util.Log.i("Search", "guide q='$q' -> ${r.rows.sumOf { it.cards.size }} in ${System.currentTimeMillis() - t} ms"); r } else null
            val t0 = System.currentTimeMillis()
            if (!f.active && sort == SearchSort.RELEVANCE && q.isNotBlank()) {
                val indexed = buildFilteredRows(q, f, vis, off, limit, sort, prefixOnly = true)
                if (indexed.rows.isNotEmpty()) {
                    emit(SearchResults(indexed.rows, true, null, indexed.truncated, busy = true, note = "Finding more matches…"))
                    android.util.Log.i("Search", "indexed titles -> ${titleCount(indexed.rows)} in ${System.currentTimeMillis() - t0} ms")
                }
            }
            val built = buildFilteredRows(q, f, vis, off, limit, sort)
            android.util.Log.i("Search", "titles q='$q' scope=$scope filters=${f.active} -> ${titleCount(built.rows)} in ${System.currentTimeMillis() - t0} ms")
            var rows = built.rows
            var truncated = built.truncated
            var titleCount = titleCount(rows)
            emit(SearchResults(rows, true, null, truncated, loading = !f.active && scope.live && rows.isEmpty(), busy = !f.active && scope.live, note = "Checking live channels and the TV guide…"))
            // Typo tolerance (task 82): no titles for a real query → retry the normalized form, then
            // offer the closest visible title as "Did you mean …?". The retry keeps the filters on;
            // the suggestion ignores them, so it only appears when no filter is active.
            if (titleCount == 0 && q.trim().length >= 4) {
                val normalized = normalize(q)
                if (normalized.isNotBlank() && normalized != q.trim().lowercase()) {
                    val retry = buildFilteredRows(normalized, f, vis, off, limit, sort)
                    val retryTitles = titleCount(retry.rows)
                    if (retryTitles > 0) {
                        rows = retry.rows; truncated = retry.truncated; titleCount = retryTitles
                        emit(SearchResults(rows, true, null, truncated, busy = scope.live && !f.active))
                    }
                }
            }
            if (f.active) return@transformLatest
            if (!scope.live) {
                // A title-only section search (Movies / Shows): nothing else to wait for.
                val suggestion = if (titleCount == 0 && q.trim().length >= 4) closestVisibleTitle(q, vis) else null
                emit(SearchResults(rows, true, suggestion, truncated))
                return@transformLatest
            }
            val channels = channelsJob?.await() ?: emptyList()
            if (channels.isNotEmpty()) emit(SearchResults(channels + rows, true, null, truncated, busy = true, note = "Checking the TV guide…"))
            val programmes = programmesJob?.await() ?: Built(emptyList(), emptyMap())
            programmeCards = programmes.programmeCards
            val suggestion = if (scope.kinds.isNotEmpty() && titleCount == 0 && q.trim().length >= 4) closestVisibleTitle(q, vis) else null
            emit(SearchResults(channels + programmes.rows + rows, true, suggestion, truncated))
        }.onEach { result -> _state.update { it.copy(rows = result.rows, searched = result.searched, loading = result.loading, busy = result.busy, note = result.note, didYouMean = result.didYouMean, truncated = result.truncated, limit = limitFlow.value) } }
            .launchIn(viewModelScope)
        // Task 122: the chips come from the data — only decades that actually have titles and genres
        // that exist, for the kinds currently selected, under the same category exclusions.
        combine(filtersFlow, excludedCategoryKeys.distinctUntilChanged()) { f, off -> f.kinds to off }
            .distinctUntilChanged()
            .mapLatest { (kinds, off) ->
                search.decadeOptions(kinds, off) to kinds.flatMap { catalog.genreOptions(it, null, null, off) }
                    .filterNot { it.lowercase() in SEARCH_RETIRED_GENRE_CHIPS }
                    .distinct().sorted().take(SEARCH_GENRE_CHIPS_MAX)
            }
            .onEach { (decades, genres) -> _state.update { it.copy(decades = decades, genres = genres) } }
            .launchIn(viewModelScope)
    }

    private data class SearchInput(val q: String, val vis: Visibility, val f: com.yodesla.omniverse.core.data.SearchFilters, val off: Collection<String>, val limit: Int, val sort: SearchSort)

    /** One search pass: display rows plus the "On TV" cards that back them (kept for reminder toggles). */
    private class Built(val rows: List<HomeRow>, val programmeCards: Map<String, com.yodesla.omniverse.core.data.ProgrammeHit>, val truncated: Boolean = false)

    private data class SearchResults(val rows: List<HomeRow>, val searched: Boolean, val didYouMean: String?, val truncated: Boolean = false, val loading: Boolean = false, val busy: Boolean = false, val note: String? = null)

    private suspend fun buildChannelRows(q: String, vis: Visibility, excluded: Collection<String>): List<HomeRow> {
        val hits = search.searchLiveVisible(q, excluded, limit = 24)
        val rows = ArrayList<HomeRow>()
        if (hits.isNotEmpty()) {
            rows += HomeRow("live", "Channels", hits.mapNotNull { h ->
                val ch = catalog.channel(h.key)?.takeIf { vis("LIVE", it.key.sourceId.value, it.categoryId.value) } ?: return@mapNotNull null
                HomeCard("c-${h.key.sourceId.value}-${h.key.remoteId.value}", ch.key, ch.name, null, ch.logoUrl, isChannel = true, categoryId = ch.categoryId)
            })
        }
        return rows.filter { it.cards.isNotEmpty() }
    }

    private suspend fun buildProgrammeRows(q: String, vis: Visibility, excluded: Collection<String>): Built {
        val rows = ArrayList<HomeRow>()
        // "On TV": guide programmes airing now or in the next 24 h (TiviMate-style EPG search).
        val now = clock.nowMs()
        val progs = search.searchProgrammesVisible(q, now, excluded).filter { vis("LIVE", it.channel.sourceId.value, it.categoryId.value) }
        val progCards = progs.associateBy { "p-${it.channel.sourceId.value}-${it.channel.remoteId.value}-${it.startMs}" }
        if (progCards.isNotEmpty()) rows += HomeRow("ontv", "On TV", progCards.map { (id, h) ->
            val time = if (h.startMs <= now) "Now" else com.yodesla.omniverse.designsystem.ClockFormat.format(h.startMs)
            HomeCard(id, h.channel, h.title, "$time  ·  ${h.channelName}", h.logoUrl, isChannel = true, categoryId = h.categoryId)
        })
        return Built(rows, progCards)
    }

    /**
     * Task 122: Movies/Shows rows narrowed by [f]. Every filter runs in SQL before the limit, so a
     * blank query with filters active discovers matching titles across every source instead of
     * paging a recent-title subset. No Channels / On TV rows here: live and programme search is
     * the no-filter path (a decade or genre says nothing about a live channel).
     */
    private suspend fun buildFilteredRows(q: String, f: com.yodesla.omniverse.core.data.SearchFilters, vis: Visibility, off: Collection<String>, limit: Int, sort: SearchSort, prefixOnly: Boolean = false): Built {
        if (scope == com.yodesla.omniverse.core.data.SearchScope.LIVE) return Built(emptyList(), emptyMap())
        // Plain text with no chips takes the cheap FTS path; chips (or a non-relevance sort) keep the full SQL filter query.
        val found = if (!f.active && sort == SearchSort.RELEVANCE && q.isNotBlank()) {
            if (prefixOnly) search.searchTitlePrefixes(q, scope.kinds, off, limitPerKind = limit)
            else search.searchTitles(q, scope.kinds, off, limitPerKind = limit)
        } else {
            val forced = when (scope) {
                com.yodesla.omniverse.core.data.SearchScope.MOVIES -> f.copy(movies = true, shows = false)
                com.yodesla.omniverse.core.data.SearchScope.SHOWS -> f.copy(movies = false, shows = true)
                else -> f
            }
            search.searchFiltered(q, forced, off, limitPerKind = limit, sort = sort)
        }
        val rows = ArrayList<HomeRow>()
        var truncated = false
        for ((kind, label) in listOf(ContentKind.VOD to "Movies", ContentKind.SERIES to "Shows")) {
            found[kind]?.let { page ->
                truncated = truncated || page.more
                val visible = page.rows.filter { p -> p.categoryId?.let { vis(kind.name, p.key.sourceId.value, it.value) } ?: true }
                val withArt = catalog.refillArt(visible)
                if (withArt.isNotEmpty()) rows += HomeRow(kind.name, label, withArt.map { p ->
                    HomeCard("${kind.name}-${p.key.sourceId.value}-${p.key.remoteId.value}", p.key, displayTitle(p.name, p.year), p.year?.toString(), p.posterUrl,
                        titleIdentity = p.titleIdentity())
                })
                val ids = visible.mapNotNull { it.tmdbId }.distinct()
                if (ids.isNotEmpty()) viewModelScope.launch {
                    tmdb?.prefetch(if (kind == ContentKind.VOD) com.yodesla.omniverse.core.data.metadata.WikidataMetadata.Kind.MOVIE
                        else com.yodesla.omniverse.core.data.metadata.WikidataMetadata.Kind.SERIES, ids)
                }
            }
        }
        return Built(rows, emptyMap(), truncated)
    }

    /** Movies + shows cards in [rows] (the "title results" typo tolerance keys off). */
    private fun titleCount(rows: List<HomeRow>): Int =
        rows.sumOf { if (it.id == ContentKind.VOD.name || it.id == ContentKind.SERIES.name) it.cards.size else 0 }

    /** Closest visible recent title to [q] (Levenshtein ≤ 2 on the normalized form); null if none. */
    private suspend fun closestVisibleTitle(q: String, vis: Visibility): String? {
        val names = search.recentTitles(2000)
            .filter { p -> p.categoryId?.let { vis(p.key.kind.name, p.key.sourceId.value, it.value) } ?: true }
            .map { it.name }
        return closest(q, names, 2)
    }

    fun toggleChannelFavorite(card: HomeCard) {
        val user = userData ?: return
        if (!card.isChannel || card.open.kind != ContentKind.LIVE) return
        viewModelScope.launch {
            val row = catalog.channel(card.open) ?: return@launch
            if (!visibility.first()("LIVE", row.key.sourceId.value, row.categoryId.value)) return@launch
            val favorite = !user.isFavorite(card.open).first()
            user.setFavorite(card.open, favorite)
            _state.update { it.copy(message = if (favorite) "Channel added to Favorites" else "Channel removed from Favorites") }
        }
    }

    /**
     * An "On TV" result that hasn't started toggles a reminder and returns true; anything else
     * (including a programme airing now) returns false so the caller tunes the channel.
     */
    fun handle(card: HomeCard): Boolean {
        val hit = programmeCards[card.id] ?: return false
        val store = reminders ?: return false
        if (hit.startMs <= clock.nowMs()) return false
        viewModelScope.launch {
            val set = store.toggle(com.yodesla.omniverse.core.data.reminders.Reminder(
                hit.channel.sourceId.value, hit.channel.remoteId.value, hit.categoryId.value,
                hit.startMs, hit.endMs, hit.title, hit.channelName,
            ))
            _state.update { it.copy(message = if (set) "Reminder set: ${hit.title} at ${com.yodesla.omniverse.designsystem.ClockFormat.format(hit.startMs)}" else "Reminder removed: ${hit.title}") }
        }
        return true
    }

    /** Remember the current query (called when a result is opened or Search is pressed). */
    fun rememberQuery() {
        val next = pushSearchHistory(_state.value.history, _state.value.query)
        if (next == _state.value.history) return
        _state.update { it.copy(history = next) }
        viewModelScope.launch { saveHistory(next.joinToString("\n")) }
    }

    /**
     * A result is being opened (task 118): remember which card, so Back from the title can hand focus
     * back to it instead of dropping the viewer at the top of the field. The state lives here because
     * this view model outlives the Search section.
     */
    fun resultOpened(card: HomeCard) {
        if (_state.value.focusCardId == card.id) return
        _state.update { it.copy(focusCardId = card.id) }
    }

    /** The route restored focus already: the next time Search opens, the query field takes it. */
    fun resultFocusHandled() {
        if (_state.value.focusCardId == null) return
        _state.update { it.copy(focusCardId = null) }
    }

    fun clearHistory() {
        _state.update { it.copy(history = emptyList()) }
        viewModelScope.launch { saveHistory("") }
    }

    /** Task 122: applies [next] at once — the chips repaint immediately and the search re-runs.
     *  A new filter set starts a fresh page (task 122 review). */
    private fun setFilters(next: com.yodesla.omniverse.core.data.SearchFilters) {
        if (next == _state.value.filters) return
        _state.update { it.copy(filters = next, message = null) }
        limitFlow.value = SEARCH_PAGE
        filtersFlow.value = next
    }

    /**
     * Task 122 review: "Show more" — a blank-query decade can hold far more than one page, so the
     * label alone would leave most matches unreachable. Raises the per-kind limit by one page and
     * re-runs the same filtered query, up to [SEARCH_PAGE_MAX].
     */
    fun showMore() {
        val next = (limitFlow.value + SEARCH_PAGE).coerceAtMost(SEARCH_PAGE_MAX)
        if (next == limitFlow.value) return
        limitFlow.value = next
    }

    /** Kind chips: selecting one restricts the search to it; neither selected = both kinds. */
    fun toggleMovies() = setFilters(_state.value.filters.let { it.copy(movies = !it.movies) })
    fun toggleShows() = setFilters(_state.value.filters.let { it.copy(shows = !it.shows) })
    /** Task 130: the "Anime & Animation" chip — the internal [SearchFilters.anime] field name is
     *  kept (it ripples through the data layer and SQL), but it now means anime + animation. */
    fun toggleAnime() = setFilters(_state.value.filters.let { it.copy(anime = !it.anime) })

    /** Decade chips combine: 1980s + 1990s includes either decade. */
    fun toggleDecade(decade: Int) = setFilters(
        _state.value.filters.let {
            it.copy(decades = if (decade in it.decades) it.decades - decade else (it.decades + decade).sorted())
        },
    )

    /** Rating chip: minimum rating, one at a time; tapping the active one clears it. */
    fun toggleRating(min: Float) = setFilters(_state.value.filters.let { it.copy(ratingAtLeast = if (it.ratingAtLeast == min) null else min) })

    /** Genre chips: OR-ed like the browse filter panel; tapping the active one removes it. */
    fun toggleGenre(genre: String) = setFilters(
        _state.value.filters.let {
            if (it.genres.any { g -> g.equals(genre, ignoreCase = true) }) it.copy(genres = it.genres.filterNot { g -> g.equals(genre, ignoreCase = true) })
            else it.copy(genres = it.genres + genre)
        },
    )

    /** Clears every filter chip; the query text stays. */
    fun clearFilters() = setFilters(com.yodesla.omniverse.core.data.SearchFilters.None)

    fun setSort(next: SearchSort) {
        if (next == _state.value.sort) return
        _state.update { it.copy(sort = next) }
        limitFlow.value = SEARCH_PAGE
        sortFlow.value = next
    }

    fun onQuery(q: String) {
        // Busy from the first keystroke: the debounce and the query itself both show the spinner.
        _state.update { it.copy(query = q, message = null, busy = q.isNotBlank() || it.filters.active) }
        limitFlow.value = SEARCH_PAGE
        query.value = q
    }
}

@Composable
fun SearchRoute(viewModel: SearchViewModel, onOpen: (HomeCard) -> Unit) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    var channelMenu by remember { mutableStateOf<HomeCard?>(null) }
    val c = OmniTheme.colors
    val field = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val filterButton = remember { FocusRequester() }
    val sortButton = remember { FocusRequester() }
    var filterOpen by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }
    // Task 118: Back from a title opened here lands back on this screen. The list keeps its own
    // scroll position (saveable, retained by the shell's section holder); the card that was opened
    // gets focus back instead of the query field stealing it.
    val cardFocus = remember { FocusRequester() }
    val focusTarget = s.focusCardId
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    com.yodesla.omniverse.designsystem.CosmicBackdrop(Modifier.fillMaxSize()) {
    // Kory 2026-10-09: the field, filter chips, empty states, rows and "Show more" share ONE
    // LazyColumn so the header scrolls away when focus moves into the results (on a TV the
    // three header rows used to pin the posters below the screen edge).
    FocusPivot(parentFraction = 0.15f, leading = 0.dp) {
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(OmniSpacing.xs), contentPadding = PaddingValues(top = OmniSpacing.m, bottom = 48.dp)) {
            item(key = "__header") {
                Column {
                val f = s.filters
                // One line: field + Filters + Sort, so results start higher on the screen.
                Row(Modifier.fillMaxWidth().padding(horizontal = OmniSpacing.tvSide), horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m), verticalAlignment = Alignment.CenterVertically) {
                // Documented exception (task 69): the query field keeps material3's own focus treatment
                // (border + cursor) — it is a text input, not a tile.
                OutlinedTextField(
                    value = s.query,
                    onValueChange = viewModel::onQuery,
                    singleLine = true,
                    placeholder = { Text(searchPlaceholder(s.scope), style = OmniTheme.type.body, color = c.textTertiary) },
                    textStyle = OmniTheme.type.title.copy(color = c.textPrimary),
                    // Task 143: the spinner shows from the first keystroke until the last result row has landed.
                    trailingIcon = { if (s.busy) androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 3.dp, color = c.accent) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { viewModel.rememberQuery(); keyboard?.hide(); focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down) }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = c.accent, unfocusedBorderColor = c.elevated, cursorColor = c.accent,
                        focusedContainerColor = c.surface, unfocusedContainerColor = c.surface,
                    ),
                    modifier = Modifier.weight(1f).focusRequester(field)
                        // Inside the LazyColumn the field kept D-pad Down; hand it to the chips explicitly.
                        .onPreviewKeyEvent { e ->
                            if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && e.key == androidx.compose.ui.input.key.Key.DirectionDown) {
                                keyboard?.hide(); focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down); true
                            } else false
                        },
                )
                    com.yodesla.omniverse.designsystem.OmniButton("Filters${if (searchFilterCount(f) > 0) " · ${searchFilterCount(f)}" else ""}", { filterOpen = true }, modifier = Modifier.focusRequester(filterButton), primary = f.active)
                    com.yodesla.omniverse.designsystem.OmniButton("Sort · ${searchSortLabel(s.sort)}", { sortOpen = true }, modifier = Modifier.focusRequester(sortButton))
                }
                if (s.busy) {
                    androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.padding(horizontal = OmniSpacing.tvSide).fillMaxWidth().height(2.dp), color = c.accent, trackColor = c.elevated)
                    Text(s.note ?: "Searching…", style = OmniTheme.type.caption, color = c.textSecondary, modifier = Modifier.padding(horizontal = OmniSpacing.tvSide))
                }
                s.message?.let { Text(it, style = OmniTheme.type.body, color = c.accent, modifier = Modifier.padding(horizontal = OmniSpacing.tvSide, vertical = OmniSpacing.xs)) }
                if (f.active) Text(searchFilterSummary(f), style = OmniTheme.type.caption, color = c.textSecondary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = OmniSpacing.tvSide))
                }
            }
            item(key = "__state") {
                Column {
                if (s.loading && s.rows.isEmpty()) {
                    Text(stringResource(R.string.search_loading), style = OmniTheme.type.browseHeading, color = c.textSecondary,
                        modifier = Modifier.padding(horizontal = OmniSpacing.tvSide, vertical = OmniSpacing.xl))
                } else if (s.searched && !s.busy && s.rows.isEmpty()) {
                    Column(Modifier.padding(horizontal = OmniSpacing.tvSide, vertical = OmniSpacing.xl), verticalArrangement = Arrangement.spacedBy(OmniSpacing.s)) {
                        Text(stringResource(R.string.search_nothing_found), style = OmniTheme.type.browseHeading, color = c.textPrimary)
                        // A blank query with filters active says "these filters", not "no titles match ''".
                        if (s.query.isBlank()) Text(stringResource(R.string.search_nothing_found_filters), style = OmniTheme.type.body, color = c.textTertiary)
                        else Text(if (s.scope == com.yodesla.omniverse.core.data.SearchScope.ALL) stringResource(R.string.search_nothing_found_hint, s.query) else "No " + s.scope.label + " match “" + s.query + "”. Try fewer words.", style = OmniTheme.type.body, color = c.textTertiary)
                        // Typo tolerance (task 82): offer the closest visible title; picking it re-runs the search.
                        s.didYouMean?.let { suggestion ->
                            FocusPivot {
                                com.yodesla.omniverse.designsystem.OmniButton(
                                    stringResource(R.string.search_did_you_mean, suggestion),
                                    { viewModel.onQuery(suggestion) },
                                )
                            }
                        }
                    }
                } else if (!s.searched) {
                    // Empty state: a calm invitation instead of a blank page.
                    Column(Modifier.padding(horizontal = OmniSpacing.tvSide, vertical = OmniSpacing.xl), verticalArrangement = Arrangement.spacedBy(OmniSpacing.s)) {
                        Text(if (s.scope == com.yodesla.omniverse.core.data.SearchScope.ALL) "SEARCH EVERYTHING" else "SEARCH " + s.scope.label.uppercase() + " ONLY", style = OmniTheme.type.overline, color = c.accent)
                        Text(when (s.scope) {
                            com.yodesla.omniverse.core.data.SearchScope.ALL -> "Channels, programmes, movies and shows"
                            com.yodesla.omniverse.core.data.SearchScope.MOVIES -> "Only the movies in your library"
                            com.yodesla.omniverse.core.data.SearchScope.SHOWS -> "Only the shows in your library"
                            com.yodesla.omniverse.core.data.SearchScope.LIVE -> "Live channels and what is airing now"
                        }, style = OmniTheme.type.browseHeading, color = c.textPrimary)
                        Text("Type a title, a channel name or a number. Hold the mic button on your remote to search by voice.", style = OmniTheme.type.body, color = c.textTertiary)
                    }
                    if (s.history.isNotEmpty()) {
                        // Task 107: "Clear" sits right beside the header so it cannot be missed; the chip
                        // at the end of the row stays for anyone who scrolled the chips to its end.
                        Row(
                            Modifier.fillMaxWidth().padding(start = OmniSpacing.tvSide, end = OmniSpacing.tvSide, bottom = OmniSpacing.s),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m),
                        ) {
                            Text(stringResource(R.string.search_recent), style = OmniTheme.type.title, color = c.textPrimary)
                            com.yodesla.omniverse.designsystem.OmniButton(stringResource(R.string.search_clear_header), {
                                viewModel.clearHistory()
                                runCatching { field.requestFocus() } // the button is gone once the header clears
                            })
                        }
                        FocusPivot {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m), contentPadding = PaddingValues(horizontal = OmniSpacing.tvSide, vertical = OmniSpacing.focusEdge)) {
                                items(s.history, key = { it }) { term ->
                                    com.yodesla.omniverse.designsystem.OmniButton(term, { viewModel.onQuery(term) })
                                }
                                item(key = "__clear") {
                                    com.yodesla.omniverse.designsystem.OmniButton(stringResource(R.string.search_clear), {
                                        viewModel.clearHistory()
                                        runCatching { field.requestFocus() }
                                    })
                                }
                            }
                        }
                    }
                }
                }
            }
                items(s.rows, key = { it.id }) { row ->
                    // A new sort/filter/query starts at its first match; Back from a title with
                    // unchanged search state still restores the row's horizontal position.
                    val cardsState = rememberSaveable(s.sort, s.filters, s.query, row.cards.firstOrNull()?.id, saver = LazyListState.Saver) { LazyListState() }
                    val selectedResult = remember(row.id, s.query) { mutableStateOf<HomeCard?>(null) }
                    Column {
                        SearchResultHeading(row.title, selectedResult)
                        FocusPivot {
                            LazyRow(state = cardsState, horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m), contentPadding = PaddingValues(horizontal = OmniSpacing.tvSide, vertical = if (row.cards.firstOrNull()?.isChannel == true) 6.dp else 12.dp)) {
                                items(row.cards, key = { it.id }) { card ->
                                    val focused = card.id == focusTarget
                                    if (card.isChannel) SearchChannelTile(card, if (focused) Modifier.focusRequester(cardFocus) else Modifier,
                                        favorite = card.open in s.favoriteChannels, onLongClick = { channelMenu = card }) { viewModel.rememberQuery(); if (!viewModel.handle(card)) { viewModel.resultOpened(card); onOpen(card) } }
                                    else PosterCard(card.title, card.image, { viewModel.rememberQuery(); viewModel.resultOpened(card); onOpen(card) }, Modifier.width(100.dp).onFocusChanged { if (it.isFocused) selectedResult.value = card }.then(if (focused) Modifier.focusRequester(cardFocus) else Modifier), card.subtitle, compact = true)
                                }
                            }
                        }
                    }
                }
            if (s.truncated) item(key = "__more") {
                Column {
                // The cap is never silent (task 122 review): filtered queries say when matches exist beyond
                // the shown cards AND offer a reachable "Show more" — a decade of anime is more than a page.
                if (s.truncated) {
                    Text(stringResource(R.string.search_filters_truncated, s.limit), style = OmniTheme.type.caption, color = c.textTertiary, modifier = Modifier.padding(start = OmniSpacing.tvSide, end = OmniSpacing.tvSide, bottom = OmniSpacing.s))
                    FocusPivot {
                        com.yodesla.omniverse.designsystem.OmniButton(
                            stringResource(R.string.search_show_more),
                            { viewModel.showMore() },
                            modifier = Modifier.padding(start = OmniSpacing.tvSide, end = OmniSpacing.tvSide, bottom = OmniSpacing.s),
                        )
                    }
                }
                }
            }
        }
    }
    }
    LaunchedEffect(Unit) {
        // Returning from a title opened here: the card keeps focus. A fresh Search: the field takes it.
        // The flag is cleared only after the request, so the requester is still attached while it waits.
        val target = focusTarget
        val restored = target != null && cardFocus.requestFocusWhenReady()
        if (target != null) viewModel.resultFocusHandled()
        if (!restored) field.requestFocusWhenReady()
    }
    if (filterOpen) SearchFilterPanel(s, viewModel, onDismiss = { filterOpen = false; scope.launch { filterButton.requestFocusWhenReady() } })
    if (sortOpen) SearchSortPanel(s.sort, viewModel, onDismiss = { sortOpen = false; scope.launch { sortButton.requestFocusWhenReady() } })
    channelMenu?.let { card ->
        val favorite = card.open in s.favoriteChannels
        com.yodesla.omniverse.designsystem.TitleMenu(
            title = card.title, subtitle = card.subtitle, posterUrl = card.image,
            actions = listOf(com.yodesla.omniverse.designsystem.MenuAction(
                if (favorite) "Remove channel from Favorites" else "Add channel to Favorites",
                { viewModel.toggleChannelFavorite(card); channelMenu = null },
            )),
            onDismiss = { channelMenu = null },
        )
    }
}

private fun searchFilterCount(f: com.yodesla.omniverse.core.data.SearchFilters): Int =
    listOf(f.movies, f.shows, f.anime, f.ratingAtLeast != null).count { it } + f.decades.size + f.genres.size

private fun searchFilterSummary(f: com.yodesla.omniverse.core.data.SearchFilters): String = buildList {
    if (f.movies) add("Movies")
    if (f.shows) add("Shows")
    if (f.anime) add("Anime & Animation")
    f.decades.forEach { add("${it}s") }
    f.ratingAtLeast?.let { add("${it.toInt()}+ rating") }
    addAll(f.genres)
}.joinToString("  ·  ")

private fun searchSortLabel(sort: SearchSort): String = when (sort) {
    SearchSort.RELEVANCE -> "Default"
    SearchSort.TITLE_ASC -> "Title A–Z"
    SearchSort.TITLE_DESC -> "Title Z–A"
    SearchSort.YEAR_NEWEST -> "Newest year"
    SearchSort.YEAR_OLDEST -> "Oldest year"
    SearchSort.RATING -> "Highest rated"
}

@Composable
private fun SearchFilterPanel(s: SearchState, viewModel: SearchViewModel, onDismiss: () -> Unit) {
    val c = OmniTheme.colors
    val f = s.filters
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.68f))) {
            LazyColumn(
                Modifier.align(Alignment.CenterEnd).width(460.dp).fillMaxHeight().clip(RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp)).background(c.surface),
                contentPadding = PaddingValues(top = OmniSpacing.xxl, bottom = OmniSpacing.xxl),
                verticalArrangement = Arrangement.spacedBy(OmniSpacing.s),
            ) {
                item { Row(Modifier.fillMaxWidth().padding(horizontal = OmniSpacing.tvSide), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Refine results", style = OmniTheme.type.browseHeading, color = c.textPrimary)
                    com.yodesla.omniverse.designsystem.OmniButton("Done", onDismiss)
                } }
                if (f.active) item { com.yodesla.omniverse.designsystem.OmniButton("Clear all filters", { viewModel.clearFilters() }, modifier = Modifier.padding(horizontal = OmniSpacing.tvSide)) }
                // A scoped search (Movies / Shows) already fixes the type; the anime collection chip only fits the general search.
                if (s.scope == com.yodesla.omniverse.core.data.SearchScope.ALL) item { SearchFilterGroup("TYPE") {
                    com.yodesla.omniverse.designsystem.OmniButton("Movies", { viewModel.toggleMovies() }, primary = f.movies)
                    com.yodesla.omniverse.designsystem.OmniButton("Shows", { viewModel.toggleShows() }, primary = f.shows)
                } }
                if (s.scope == com.yodesla.omniverse.core.data.SearchScope.ALL) item { SearchFilterGroup("COLLECTION") {
                    com.yodesla.omniverse.designsystem.OmniButton("Anime & Animation", { viewModel.toggleAnime() }, primary = f.anime)
                } }
                if (s.decades.isNotEmpty()) item { SearchFilterGroup("DECADE") {
                    s.decades.forEach { d -> com.yodesla.omniverse.designsystem.OmniButton("${d}s", { viewModel.toggleDecade(d) }, primary = d in f.decades) }
                } }
                item { SearchFilterGroup("MINIMUM RATING") {
                    SEARCH_RATING_CHIPS.forEach { r -> com.yodesla.omniverse.designsystem.OmniButton("${r.toInt()}.0+", { viewModel.toggleRating(r) }, primary = f.ratingAtLeast == r) }
                } }
                if (s.genres.isNotEmpty()) item { SearchFilterGroup("GENRE") {
                    s.genres.forEach { g -> com.yodesla.omniverse.designsystem.OmniButton(g, { viewModel.toggleGenre(g) }, primary = f.genres.any { it.equals(g, ignoreCase = true) }) }
                } }
            }
        }
    }
}

@Composable
private fun SearchFilterGroup(title: String, buttons: @Composable () -> Unit) {
    val c = OmniTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(OmniSpacing.s)) {
        Text(title, style = OmniTheme.type.overline, color = c.textTertiary, modifier = Modifier.padding(horizontal = OmniSpacing.tvSide))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = OmniSpacing.tvSide), horizontalArrangement = Arrangement.spacedBy(OmniSpacing.s)) { buttons() }
    }
}

@Composable
private fun SearchSortPanel(sort: SearchSort, viewModel: SearchViewModel, onDismiss: () -> Unit) {
    val c = OmniTheme.colors
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.68f))) {
            LazyColumn(Modifier.align(Alignment.CenterEnd).width(400.dp).fillMaxHeight().clip(RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp)).background(c.surface), contentPadding = PaddingValues(OmniSpacing.xxl), verticalArrangement = Arrangement.spacedBy(OmniSpacing.s)) {
                item { Text("Sort movies & shows", style = OmniTheme.type.browseHeading, color = c.textPrimary) }
                items(SearchSort.entries) { choice ->
                    com.yodesla.omniverse.designsystem.OmniButton(searchSortLabel(choice), { viewModel.setSort(choice); onDismiss() }, primary = sort == choice)
                }
                item { com.yodesla.omniverse.designsystem.OmniButton("Done", onDismiss) }
            }
        }
    }
}

@Composable
private fun SearchChannelTile(card: HomeCard, modifier: Modifier = Modifier, favorite: Boolean = false, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val c = OmniTheme.colors
    // Compact strip (logo left, name + what is on right) so more rows fit on a 540 dp screen.
    com.yodesla.omniverse.designsystem.FocusCard(onClick = onClick, onLongClick = onLongClick, modifier = modifier.width(220.dp).height(52.dp)) {
        Row(Modifier.fillMaxSize().padding(horizontal = OmniSpacing.s), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OmniSpacing.s)) {
            com.yodesla.omniverse.designsystem.LogoImage(card.image, card.subtitle ?: card.title, Modifier.width(56.dp).height(34.dp))
            Column(Modifier.weight(1f)) {
                Text(card.title, style = OmniTheme.type.caption, color = c.textPrimary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                card.subtitle?.let { Text(it, style = OmniTheme.type.caption, color = c.textSecondary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
            }
            if (favorite) Text("★", style = OmniTheme.type.body, color = c.accent)
        }
    }
}

/** Task 143: the field's hint says exactly what this search covers. */
private fun searchPlaceholder(scope: com.yodesla.omniverse.core.data.SearchScope): String = when (scope) {
    com.yodesla.omniverse.core.data.SearchScope.ALL -> "Search channels, movies and shows"
    com.yodesla.omniverse.core.data.SearchScope.MOVIES -> "Search movies only"
    com.yodesla.omniverse.core.data.SearchScope.SHOWS -> "Search shows only"
    com.yodesla.omniverse.core.data.SearchScope.LIVE -> "Search live channels and what is on now"
}


@Composable
private fun SearchResultHeading(section: String, selection: androidx.compose.runtime.State<HomeCard?>) {
    val card = selection.value
    Column(Modifier.padding(horizontal = OmniSpacing.tvSide, vertical = OmniSpacing.xs)) {
        Text(section, style = OmniTheme.type.caption, color = OmniTheme.colors.textTertiary)
        Text(card?.let { listOfNotNull(it.title, it.subtitle).joinToString("  ·  ") } ?: " ",
            style = OmniTheme.type.title, color = OmniTheme.colors.textPrimary,
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}
