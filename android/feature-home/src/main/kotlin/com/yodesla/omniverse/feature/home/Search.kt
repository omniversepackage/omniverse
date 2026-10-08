package com.yodesla.omniverse.feature.home
import com.yodesla.omniverse.designsystem.requestFocusWhenReady

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Text
import com.yodesla.omniverse.core.data.CatalogRepository
import com.yodesla.omniverse.core.data.SearchRepository
import com.yodesla.omniverse.core.data.search.closest
import com.yodesla.omniverse.core.data.search.normalize
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.designsystem.FocusPivot
import com.yodesla.omniverse.designsystem.OmniSpacing
import com.yodesla.omniverse.designsystem.OmniTheme
import com.yodesla.omniverse.designsystem.PosterCard
import com.yodesla.omniverse.designsystem.displayTitle
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import com.yodesla.omniverse.core.data.ShowEverything
import com.yodesla.omniverse.core.data.Visibility
import com.yodesla.omniverse.feature.home.R

@Immutable
data class SearchState(val query: String = "", val rows: List<HomeRow> = emptyList(), val searched: Boolean = false, val message: String? = null,
    /** Most recent first, at most [SEARCH_HISTORY_MAX]. */
    val history: List<String> = emptyList(),
    /** Closest visible title offered when a search finds nothing (task 82 "Did you mean …?"). */
    val didYouMean: String? = null,
    /** Result opened most recently (task 118): Back from that title hands focus back to this card. */
    val focusCardId: String? = null,
    /** Task 122: the filter chips currently on. */
    val filters: com.yodesla.omniverse.core.data.SearchFilters = com.yodesla.omniverse.core.data.SearchFilters.None,
    /** Decades offered as chips (only decades that actually have titles), newest first. */
    val decades: List<Int> = emptyList(),
    /** Genre names offered as chips, A–Z, capped at [SEARCH_GENRE_CHIPS_MAX]. */
    val genres: List<String> = emptyList(),
    /** Filtered results hit the current per-kind limit: more matches exist beyond the shown cards. */
    val truncated: Boolean = false,
    /** Per-kind limit in force for filtered discovery; "Show more" raises it (task 122 review). */
    val limit: Int = SEARCH_PAGE)

const val SEARCH_HISTORY_MAX = 8
/** Filtered discovery page size (cards per kind per pass); "Show more" adds another page. */
const val SEARCH_PAGE = 24
/** Ceiling for filtered discovery: a whole decade of anime is reachable, the row stays bounded. */
const val SEARCH_PAGE_MAX = 240
/** Genre chips cap: the row scrolls, but a D-pad should never have to cross a hundred chips. */
const val SEARCH_GENRE_CHIPS_MAX = 20
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
) : ViewModel() {
    @Volatile private var programmeCards: Map<String, com.yodesla.omniverse.core.data.ProgrammeHit> = emptyMap()
    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()
    private val query = MutableStateFlow("")
    /** Task 122: the filter chips. Setting it re-runs the current search at once (no debounce). */
    private val filtersFlow = MutableStateFlow(com.yodesla.omniverse.core.data.SearchFilters.None)
    /** Task 122 review: per-kind limit for filtered discovery. "Show more" raises it; a new query
     *  or filter change resets it, so a stale deep page never follows a different filter set. */
    private val limitFlow = MutableStateFlow(SEARCH_PAGE)

    init {
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
        combine(query.debounce(200).distinctUntilChanged(), visibility, filtersFlow, excludedCategoryKeys.distinctUntilChanged(), limitFlow) { q, vis, f, off, limit ->
            SearchInput(q, vis, f, off, limit)
        }.mapLatest { input ->
            val q = input.q; val vis = input.vis; val f = input.f; val off = input.off; val limit = input.limit
            if (q.isBlank() && !f.active) return@mapLatest SearchResults(emptyList(), false, null)
            val built = if (f.active) buildFilteredRows(q, f, vis, off, limit) else buildRows(q, vis)
            var rows = built.rows
            var cards = built.programmeCards
            var truncated = built.truncated
            var titleCount = titleCount(rows)
            var suggestion: String? = null
            // Typo tolerance (task 82): no titles for a real query → retry the normalized form, then
            // offer the closest visible title as "Did you mean …?". The retry keeps the filters on;
            // the suggestion ignores them, so it only appears when no filter is active.
            if (titleCount == 0 && q.trim().length >= 4) {
                val normalized = normalize(q)
                if (normalized.isNotBlank() && normalized != q.trim().lowercase()) {
                    val retry = if (f.active) buildFilteredRows(normalized, f, vis, off, limit) else buildRows(normalized, vis)
                    val retryTitles = titleCount(retry.rows)
                    if (retryTitles > 0) { rows = retry.rows; cards = retry.programmeCards; truncated = retry.truncated; titleCount = retryTitles }
                }
                if (titleCount == 0 && !f.active) suggestion = closestVisibleTitle(q, vis)
            }
            programmeCards = cards
            SearchResults(rows, true, suggestion, truncated)
        }.onEach { result -> _state.update { it.copy(rows = result.rows, searched = result.searched, didYouMean = result.didYouMean, truncated = result.truncated, limit = limitFlow.value) } }
            .launchIn(viewModelScope)
        // Task 122: the chips come from the data — only decades that actually have titles and genres
        // that exist, for the kinds currently selected, under the same category exclusions.
        combine(filtersFlow, excludedCategoryKeys.distinctUntilChanged()) { f, off -> f.kinds to off }
            .mapLatest { (kinds, off) ->
                search.decadeOptions(kinds, off) to kinds.flatMap { catalog.genreOptions(it, null, null, off) }.distinct().sorted().take(SEARCH_GENRE_CHIPS_MAX)
            }
            .onEach { (decades, genres) -> _state.update { it.copy(decades = decades, genres = genres) } }
            .launchIn(viewModelScope)
    }

    private data class SearchInput(val q: String, val vis: Visibility, val f: com.yodesla.omniverse.core.data.SearchFilters, val off: Collection<String>, val limit: Int)

    /** One search pass: display rows plus the "On TV" cards that back them (kept for reminder toggles). */
    private class Built(val rows: List<HomeRow>, val programmeCards: Map<String, com.yodesla.omniverse.core.data.ProgrammeHit>, val truncated: Boolean = false)

    private data class SearchResults(val rows: List<HomeRow>, val searched: Boolean, val didYouMean: String?, val truncated: Boolean = false)

    private suspend fun buildRows(q: String, vis: Visibility): Built {
        val hits = search.search(q, limitPerKind = 24)
        val rows = ArrayList<HomeRow>()
        hits[ContentKind.LIVE]?.let { list ->
            rows += HomeRow("live", "Channels", list.mapNotNull { h ->
                val ch = catalog.channel(h.key)?.takeIf { vis("LIVE", it.key.sourceId.value, it.categoryId.value) } ?: return@mapNotNull null
                HomeCard("c-${h.key.sourceId.value}-${h.key.remoteId.value}", ch.key, ch.name, null, ch.logoUrl, isChannel = true, categoryId = ch.categoryId)
            })
        }
        // "On TV": guide programmes airing now or in the next 24 h (TiviMate-style EPG search).
        val now = clock.nowMs()
        val progs = search.searchProgrammes(q, now).filter { vis("LIVE", it.channel.sourceId.value, it.categoryId.value) }
        val progCards = progs.associateBy { "p-${it.channel.sourceId.value}-${it.channel.remoteId.value}-${it.startMs}" }
        if (progCards.isNotEmpty()) rows += HomeRow("ontv", "On TV", progCards.map { (id, h) ->
            val time = if (h.startMs <= now) "Now" else com.yodesla.omniverse.designsystem.ClockFormat.format(h.startMs)
            HomeCard(id, h.channel, h.title, "$time  ·  ${h.channelName}", h.logoUrl, isChannel = true, categoryId = h.categoryId)
        })
        for ((kind, label) in listOf(ContentKind.VOD to "Movies", ContentKind.SERIES to "Shows")) {
            hits[kind]?.let { list ->
                // One card per title: the best-ranked VISIBLE hit wins for each exact TMDB id; the
                // detail page offers the other sources/editions. Never grouped on title guesses.
                val visible = list.mapNotNull { h ->
                    catalog.poster(h.key)?.takeIf { p -> p.categoryId?.let { vis(kind.name, p.key.sourceId.value, it.value) } ?: true }
                }.distinctBy { p -> p.tmdbId?.takeIf { it.isNotBlank() }?.let { "tmdb:$it" } ?: "key:${p.key.sourceId.value}:${p.key.remoteId.value}" }
                if (visible.isNotEmpty()) rows += HomeRow(kind.name, label, visible.map { p ->
                    HomeCard("${kind.name}-${p.key.sourceId.value}-${p.key.remoteId.value}", p.key, displayTitle(p.name, p.year), p.year?.toString(), p.posterUrl)
                })
            }
        }
        return Built(rows.filter { it.cards.isNotEmpty() }, progCards)
    }

    /**
     * Task 122: Movies/Shows rows narrowed by [f]. Every filter runs in SQL before the limit, so a
     * blank query with filters active discovers matching titles across every source instead of
     * paging a recent-title subset. No Channels / On TV rows here: live and programme search is
     * the no-filter path (a decade or genre says nothing about a live channel).
     */
    private suspend fun buildFilteredRows(q: String, f: com.yodesla.omniverse.core.data.SearchFilters, vis: Visibility, off: Collection<String>, limit: Int): Built {
        val found = search.searchFiltered(q, f, off, limitPerKind = limit)
        val rows = ArrayList<HomeRow>()
        var truncated = false
        for ((kind, label) in listOf(ContentKind.VOD to "Movies", ContentKind.SERIES to "Shows")) {
            found[kind]?.let { page ->
                truncated = truncated || page.more
                val visible = page.rows.filter { p -> p.categoryId?.let { vis(kind.name, p.key.sourceId.value, it.value) } ?: true }
                    .distinctBy { p -> p.tmdbId?.takeIf { it.isNotBlank() }?.let { "tmdb:$it" } ?: "key:${p.key.sourceId.value}:${p.key.remoteId.value}" }
                if (visible.isNotEmpty()) rows += HomeRow(kind.name, label, visible.map { p ->
                    HomeCard("${kind.name}-${p.key.sourceId.value}-${p.key.remoteId.value}", p.key, displayTitle(p.name, p.year), p.year?.toString(), p.posterUrl)
                })
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
    fun toggleAnime() = setFilters(_state.value.filters.let { it.copy(anime = !it.anime) })

    /** Decade chip (1980 = 1980..1989): tapping the active decade clears it, tapping another moves. */
    fun toggleDecade(decade: Int) = setFilters(
        _state.value.filters.let {
            if (it.yearFrom == decade && it.yearTo == decade + 9) it.copy(yearFrom = null, yearTo = null)
            else it.copy(yearFrom = decade, yearTo = decade + 9)
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

    fun onQuery(q: String) {
        _state.update { it.copy(query = q, message = null) }
        limitFlow.value = SEARCH_PAGE
        query.value = q
    }
}

@Composable
fun SearchRoute(viewModel: SearchViewModel, onOpen: (HomeCard) -> Unit) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val c = OmniTheme.colors
    val field = remember { FocusRequester() }
    // Task 118: Back from a title opened here lands back on this screen. The list keeps its own
    // scroll position (saveable, retained by the shell's section holder); the card that was opened
    // gets focus back instead of the query field stealing it.
    val cardFocus = remember { FocusRequester() }
    val focusTarget = s.focusCardId
    com.yodesla.omniverse.designsystem.CosmicBackdrop(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().padding(top = OmniSpacing.xl)) {
        // Documented exception (task 69): the query field keeps material3's own focus treatment
        // (border + cursor) — it is a text input, not a tile.
        OutlinedTextField(
            value = s.query,
            onValueChange = viewModel::onQuery,
            singleLine = true,
            placeholder = { Text("Search channels, movies and shows", style = OmniTheme.type.body, color = c.textTertiary) },
            textStyle = OmniTheme.type.title.copy(color = c.textPrimary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { viewModel.rememberQuery() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = c.accent, unfocusedBorderColor = c.elevated, cursorColor = c.accent,
                focusedContainerColor = c.surface, unfocusedContainerColor = c.surface,
            ),
            modifier = Modifier.padding(horizontal = OmniSpacing.tvSide).widthIn(max = 900.dp).fillMaxWidth().focusRequester(field),
        )
        s.message?.let { Text(it, style = OmniTheme.type.body, color = c.accent, modifier = Modifier.padding(horizontal = OmniSpacing.tvSide, vertical = OmniSpacing.s)) }
        // Task 122: the filter bar sits between the field and the results, in its own FocusPivot, so
        // D-pad focus walks field → chips → rows and the results' pivot math stays untouched. Chips
        // come from the data (decades/genres that actually have titles); tapping the active one clears it.
        val f = s.filters
        FocusPivot {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m), contentPadding = PaddingValues(horizontal = OmniSpacing.tvSide, vertical = OmniSpacing.s)) {
                item(key = "f-movies") { com.yodesla.omniverse.designsystem.OmniButton("Movies", { viewModel.toggleMovies() }, primary = f.movies) }
                item(key = "f-shows") { com.yodesla.omniverse.designsystem.OmniButton("Shows", { viewModel.toggleShows() }, primary = f.shows) }
                item(key = "f-anime") { com.yodesla.omniverse.designsystem.OmniButton("Anime", { viewModel.toggleAnime() }, primary = f.anime) }
                items(s.decades, key = { "f-d-$it" }) { d ->
                    com.yodesla.omniverse.designsystem.OmniButton("${d}s", { viewModel.toggleDecade(d) }, primary = f.yearFrom == d && f.yearTo == d + 9)
                }
                items(SEARCH_RATING_CHIPS, key = { "f-r-$it" }) { r ->
                    com.yodesla.omniverse.designsystem.OmniButton("${r.toInt()}.0+", { viewModel.toggleRating(r) }, primary = f.ratingAtLeast == r)
                }
                if (f.active) item(key = "f-clear") { com.yodesla.omniverse.designsystem.OmniButton("Clear filters", { viewModel.clearFilters() }) }
            }
        }
        if (s.genres.isNotEmpty()) {
            FocusPivot {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m), contentPadding = PaddingValues(start = OmniSpacing.tvSide, end = OmniSpacing.tvSide, bottom = OmniSpacing.s)) {
                    items(s.genres, key = { "f-g-$it" }) { g ->
                        com.yodesla.omniverse.designsystem.OmniButton(g, { viewModel.toggleGenre(g) }, primary = f.genres.any { it.equals(g, ignoreCase = true) })
                    }
                }
            }
        }
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
        if (s.searched && s.rows.isEmpty()) {
            Column(Modifier.padding(horizontal = OmniSpacing.tvSide, vertical = OmniSpacing.xl), verticalArrangement = Arrangement.spacedBy(OmniSpacing.s)) {
                Text(stringResource(R.string.search_nothing_found), style = OmniTheme.type.browseHeading, color = c.textPrimary)
                // A blank query with filters active says "these filters", not "no titles match ''".
                if (s.query.isBlank()) Text(stringResource(R.string.search_nothing_found_filters), style = OmniTheme.type.body, color = c.textTertiary)
                else Text(stringResource(R.string.search_nothing_found_hint, s.query), style = OmniTheme.type.body, color = c.textTertiary)
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
                Text("SEARCH EVERYTHING", style = OmniTheme.type.overline, color = c.accent)
                Text("Channels, programmes, movies and shows", style = OmniTheme.type.browseHeading, color = c.textPrimary)
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
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m), contentPadding = PaddingValues(horizontal = OmniSpacing.tvSide, vertical = OmniSpacing.s)) {
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
        FocusPivot(parentFraction = 0.15f, leading = 0.dp) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(OmniSpacing.l), contentPadding = PaddingValues(top = OmniSpacing.l, bottom = 200.dp)) {
                items(s.rows, key = { it.id }) { row ->
                    Column {
                        Text(row.title, style = OmniTheme.type.title, color = c.textPrimary, modifier = Modifier.padding(start = OmniSpacing.tvSide, bottom = OmniSpacing.s))
                        FocusPivot {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(OmniSpacing.l), contentPadding = PaddingValues(horizontal = OmniSpacing.tvSide, vertical = OmniSpacing.s)) {
                                items(row.cards, key = { it.id }) { card ->
                                    val focused = card.id == focusTarget
                                    if (card.isChannel) SearchChannelTile(card, if (focused) Modifier.focusRequester(cardFocus) else Modifier) { viewModel.rememberQuery(); if (!viewModel.handle(card)) { viewModel.resultOpened(card); onOpen(card) } }
                                    else PosterCard(card.title, card.image, { viewModel.rememberQuery(); viewModel.resultOpened(card); onOpen(card) }, if (focused) Modifier.width(124.dp).focusRequester(cardFocus) else Modifier.width(124.dp), card.subtitle)
                                }
                            }
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
}

@Composable
private fun SearchChannelTile(card: HomeCard, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = OmniTheme.colors
    com.yodesla.omniverse.designsystem.FocusCard(onClick = onClick, modifier = modifier.width(240.dp).height(140.dp)) {
        Column(Modifier.fillMaxSize().padding(OmniSpacing.m), verticalArrangement = Arrangement.SpaceBetween) {
            com.yodesla.omniverse.designsystem.LogoImage(card.image, card.subtitle ?: card.title, Modifier.width(96.dp).height(54.dp))
            Column {
                Text(card.title, style = OmniTheme.type.body, color = c.textPrimary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                card.subtitle?.let { Text(it, style = OmniTheme.type.caption, color = c.textSecondary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
            }
        }
    }
}
