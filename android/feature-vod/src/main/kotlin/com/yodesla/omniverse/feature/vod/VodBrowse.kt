package com.yodesla.omniverse.feature.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import androidx.paging.cachedIn
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.yodesla.omniverse.core.data.CatalogRepository
import com.yodesla.omniverse.core.data.BrowseSort
import com.yodesla.omniverse.core.data.PosterRow
import com.yodesla.omniverse.core.data.ItemOverride
import com.yodesla.omniverse.core.data.LibraryCollection
import com.yodesla.omniverse.core.data.SmartCollectionFilter
import com.yodesla.omniverse.core.data.TitleIdentity
import com.yodesla.omniverse.core.data.TitleOverride
import com.yodesla.omniverse.core.data.SourceRepository
import com.yodesla.omniverse.core.data.UserDataRepository
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.SourceId
import com.yodesla.omniverse.designsystem.BrandStage
import com.yodesla.omniverse.designsystem.CategoryDestinationAccent
import com.yodesla.omniverse.designsystem.CategoryDestinationHeader
import com.yodesla.omniverse.designsystem.CategorySelectionGlow
import com.yodesla.omniverse.designsystem.CategoryIdentity
import com.yodesla.omniverse.designsystem.categoryAccent
import com.yodesla.omniverse.designsystem.FocusCard
import com.yodesla.omniverse.designsystem.CosmicBackdrop
import com.yodesla.omniverse.designsystem.FocusPivot
import com.yodesla.omniverse.designsystem.LocalCompact
import com.yodesla.omniverse.designsystem.OmniSpacing
import com.yodesla.omniverse.designsystem.OmniTheme
import com.yodesla.omniverse.designsystem.PosterCard
import com.yodesla.omniverse.designsystem.PosterFocus
import com.yodesla.omniverse.designsystem.PosterPrefetch
import com.yodesla.omniverse.designsystem.adaptiveCellWidthDp
import com.yodesla.omniverse.designsystem.adaptiveColumnCount
import com.yodesla.omniverse.designsystem.posterRowsFromFlat
import com.yodesla.omniverse.designsystem.ProgressLine
import com.yodesla.omniverse.designsystem.displayTitle
import com.yodesla.omniverse.designsystem.OmniButton
import com.yodesla.omniverse.designsystem.requestFocusWhenReady
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import androidx.paging.filter
import kotlinx.coroutines.flow.combine
import com.yodesla.omniverse.core.model.SourceKind
import com.yodesla.omniverse.core.model.progressPosterKey
import com.yodesla.omniverse.core.data.ShowEverything
import com.yodesla.omniverse.core.data.PickForMeOffer
import com.yodesla.omniverse.core.data.PickForMeSession
import com.yodesla.omniverse.core.data.UpNextResolver
import com.yodesla.omniverse.core.data.Visibility

@Immutable
data class VodCategoryUi(val id: String?, val name: String)

/**
 * Task 107: rail item index of the selected category. The rail's LazyColumn puts its header
 * items (search, sort, filter) before the category chips, so the chip sits headerCount + its
 * position in the list; 0 (top) when the selection is not in the list.
 */
internal fun vodRailFocusIndex(headerCount: Int, categories: List<VodCategoryUi>, selected: String?): Int {
    val i = categories.indexOfFirst { it.id == selected }
    return if (i >= 0) headerCount + i else 0
}

/** Task 116: a browse action that changes whether the category panel is hidden behind a destination. */
internal enum class VodBrowsePanelAction { EnterDestination, RevealPanel, CloseLibrary }

/**
 * Task 116: whether the category side panel is hidden (a destination fills the screen). Entering a
 * category or the anime library hides it; Back / Left at the content's far-left edge reveals it;
 * closing the anime library lands back on the service page, which is still an open destination, so
 * it stays hidden. Returning from a title detail is deliberately NOT an action here — the flag lives
 * in the ViewModel, so it survives the detail round-trip and Back reopens the same destination.
 */
internal fun vodPanelHidden(current: Boolean, action: VodBrowsePanelAction): Boolean = when (action) {
    VodBrowsePanelAction.EnterDestination -> true
    VodBrowsePanelAction.RevealPanel -> false
    VodBrowsePanelAction.CloseLibrary -> current
}

@Immutable
data class ContinuePosterUi(
    val poster: PosterRow,
    val progress: Float,
    val episode: Boolean,
    /** Task 57 "Up next": subtitle for a show whose latest episode finished (no bar); null = in progress. */
    val upNextLabel: String? = null,
    /** Task 84c: ms left to finish the in-progress item (null when duration unknown). */
    val remainingMs: Long? = null,
    /** Task 84c: "S2:E5" for an in-progress episode (null when unknown or not an episode). */
    val episodeLabel: String? = null,
    /** Task 86b: the saved episode's own season/number (provider-supplied; null when unknown). */
    val episodeSeason: Int? = null,
    val episodeNumber: Int? = null,
    /** Raw resume position/runtime, so a layout can say "18m left" instead of only drawing a bar. */
    val positionMs: Long = 0,
    val durationMs: Long? = null,
)

@Immutable
data class VodBrowseState(
    val kind: ContentKind,
    val sourceId: SourceId? = null,
    val categories: List<VodCategoryUi> = emptyList(),
    val selected: String? = null,
    val noSources: Boolean = false,
    val continueWatching: List<ContinuePosterUi> = emptyList(),
    /** Task 115: the four-choice browse sort (provider / A–Z / release year / rating). */
    val sort: BrowseSort = BrowseSort.PROVIDER,
    /** Task 84c: My List posters (favorites), visibility-filtered, newest first by favorite order. */
    val myList: List<PosterRow> = emptyList(),
    /** Task 84c: active profile name for the "Continue Watching for X" row title. */
    val profileName: String? = null,
    /** Task 84c: (source, category) of the current selection; null or (null, _) = "All". */
    val selection: Pair<SourceId?, String?>? = null,
    /** Task 84i: the browse filter in effect (source/category are taken from the selection, not this). */
    val filter: SmartCollectionFilter = SmartCollectionFilter(),
    /** Task 84i: genre names present in the current scope, for the filter panel's pills. */
    val genreOptions: List<String> = emptyList(),
    /** Task 84i: true when the selected category is a branded service page (filters hidden). */
    val branded: Boolean = false,
    /** Task 84m: hold-OK category menu (provider categories only; "All" has none), null = closed. */
    val menuCategoryId: String? = null,
    /** Task 84m: one-shot "Category hidden · Undo" toast after hiding a category. */
    val hiddenNotice: com.yodesla.omniverse.designsystem.HiddenNotice? = null,
    /** Task 92: true while the Crunchyroll "Anime library" grid is open (Back returns to the service page). */
    val animeLibrary: Boolean = false,
    /** Task 117: true while any destination's Library grid fills the screen (Back returns to that page). */
    val libraryOpen: Boolean = false,
    /** Task 116: true while a destination (a category page or the anime library) fills the screen and the
     * category panel is hidden. Kept here (not in composable state) so it survives a title-detail
     * round-trip exactly like [selected]/[animeLibrary], so Back reopens the same open destination. */
    val destinationOpen: Boolean = false,
)

private data class BrowseInputs(
    val selection: Pair<SourceId?, String?>,
    val visibility: Visibility,
    val sort: BrowseSort,
    val overrides: Map<ContentKey, ItemOverride>,
    val titleOverrides: Map<TitleIdentity, TitleOverride>,
    val excludedCategoryKeys: Set<String>,
    val filter: SmartCollectionFilter,
    /** Task 117: which Library grid (if any) owns the screen; null = the normal destination page. */
    val library: LibraryScope? = null,
)

/** Task 117: Library scope — Anime = Crunchyroll's global anime set (task 92); Category = the selected destination's own titles. */
internal sealed interface LibraryScope {
    data object Anime : LibraryScope
    data object Category : LibraryScope
}

private fun PosterRow.withOverride(value: ItemOverride?, title: TitleOverride?): PosterRow = copy(
    name = title?.displayTitle ?: value?.displayTitle ?: name,
    posterUrl = title?.customPosterUrl ?: value?.customPosterUrl ?: posterUrl,
)

/** Movies (kind = VOD) or Shows (kind = SERIES): category rail + poster grid. */
@OptIn(ExperimentalCoroutinesApi::class)
class VodBrowseViewModel(
    private val kind: ContentKind,
    private val sources: SourceRepository,
    private val catalog: CatalogRepository,
    private val userData: UserDataRepository,
    private val visibility: Flow<Visibility> = ShowEverything,
    private val excludedCategoryKeys: Flow<Set<String>> = flowOf(emptySet()),
    /** Rotten Tomatoes lookup for the spotlight (null = off). */
    private val rottenTomatoes: com.yodesla.omniverse.core.data.metadata.RottenTomatoes? = null,
    /** Task 84d: warms the TMDB art cache for on-screen rows (null or keyless build = off). */
    private val tmdb: com.yodesla.omniverse.core.data.metadata.TmdbEnricher? = null,
    /** Task 84c: active profile name for the Netflix "Continue Watching for X" row (null = off). */
    private val profiles: com.yodesla.omniverse.core.data.ProfileRepository? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(VodBrowseState(kind))
    val state: StateFlow<VodBrowseState> = _state.asStateFlow()

    // Task 90: the last removal that can still be undone, shown by the bottom-centre undo card.
    private val _undo = MutableStateFlow<com.yodesla.omniverse.core.data.UndoUi?>(null)
    val undo: StateFlow<com.yodesla.omniverse.core.data.UndoUi?> = _undo.asStateFlow()

    // Task 96: "Surprise me" — one unwatched pick per press; this session never repeats one.
    private val pickSession = PickForMeSession(catalog, userData)
    private val _pick = MutableStateFlow<PickForMeOffer?>(null)
    val pick: StateFlow<PickForMeOffer?> = _pick.asStateFlow()

    /** Pick (or re-pick for "Another") from this page's kind, with the viewer's exclusions. */
    fun surpriseMe() {
        viewModelScope.launch {
            _pick.value = pickSession.next(listOf(kind), allExcluded.first(), currentVisibility)
        }
    }

    fun dismissPick() { _pick.value = null }

    /** Task 69: hold-OK hint, first three launches only. Home owns the launch count; this only reads it. */
    val hintVisible: Flow<Boolean> = userData.setting("hint_hold_ok_seen").map { (it?.toIntOrNull() ?: 0) < 3 }
    /** (source, category). source == null → "All": every source merged (Plex wins duplicates). */
    private val selection = MutableStateFlow<Pair<SourceId?, String?>?>(null)
    private val sort = MutableStateFlow(BrowseSort.PROVIDER)
    /** Task 84i: the browse filter; restored per profile/kind on open, saved on every change. */
    private val filters = MutableStateFlow(SmartCollectionFilter())
    /** Task 84i: a branded category ignores the filter (its service layout owns the page). */
    private val brandSelected = MutableStateFlow(false)
    /** Task 92/117: the Library grid that replaces the destination page while non-null (Anime = Crunchyroll's global set, Category = the selected destination's titles). */
    private val library = MutableStateFlow<LibraryScope?>(null)
    /** Task 84c: cache for cwEpisodeLabel; declared before init so the CW flow can use it during construction. */
    private val episodeLabels = HashMap<String, Pair<String?, Long>>()

    private val customizations = combine(userData.itemOverrides(), userData.titleOverrides()) { items, titles -> items to titles }
    /** Task 86b: the latest parental visibility, so a layout can re-check a row without re-querying. */
    private var currentVisibility: Visibility = { _, _, _ -> true }
    /** Task 57: resolves the next episode of a show whose latest watch is a completed episode. */
    private val upNextResolver = UpNextResolver(sources)
    // Parental locks and viewer-switched-off libraries share the All queries' exclusion keys.
    private val allExcluded = combine(excludedCategoryKeys, userData.hiddenCategoryKeys()) { locked, off -> locked + off }
    val items: Flow<PagingData<PosterRow>> = combine(selection.filterNotNull(), visibility, sort, customizations, allExcluded) { sel, vis, sortMode, custom, excluded ->
        BrowseInputs(sel, vis, sortMode, custom.first, custom.second, excluded, SmartCollectionFilter())
    }.combine(filters) { inputs, f -> inputs.copy(filter = f) }
        .combine(library) { inputs, lib -> inputs.copy(library = lib) }
        // Task 117: a Library grid is a browse page, so the filter applies even on a branded
        // destination; only the service page itself (library == null) ignores it.
        .combine(brandSelected) { inputs, branded -> if (branded && inputs.library == null) inputs.copy(filter = SmartCollectionFilter()) else inputs }
        .onEach { currentVisibility = it.visibility }
        .flatMapLatest { (sel, vis, sortMode, overrides, titles, excluded, filter, lib) ->
            val (src, cat) = sel
            // A fresh Pager per lock change: re-emitting one PagingData twice throws.
            Pager(PagingConfig(pageSize = 48, prefetchDistance = 24, enablePlaceholders = false)) {
                when {
                    // Task 92/117: the anime library owns the grid while it is open (its own SQL scope).
                    lib == LibraryScope.Anime -> if (filter.isBrowseActive()) catalog.animeLibraryFilteredSorted(kind, excluded, filter, sortMode)
                        else catalog.animeLibrarySorted(kind, excluded, sortMode)
                    // Task 84i: an active filter runs in SQL (single source or all-sources variants).
                    filter.isBrowseActive() -> if (kind == ContentKind.SERIES) catalog.seriesFilteredSorted(src, cat?.let(::RemoteId), filter, excluded, sortMode)
                        else catalog.vodFilteredSorted(src, cat?.let(::RemoteId), filter, excluded, sortMode)
                    // Task 115: every browse path sorts in SQL before paging.
                    src == null -> if (kind == ContentKind.SERIES) catalog.seriesAllSorted(excluded, sortMode) else catalog.vodAllSorted(excluded, sortMode)
                    kind == ContentKind.SERIES -> catalog.seriesSorted(src, cat?.let(::RemoteId), sortMode)
                    else -> catalog.vodSorted(src, cat?.let(::RemoteId), sortMode)
                }
            }.flow.map { page ->
                // "All" must not leak locked categories either.
                page.filter { p -> p.categoryId?.let { vis(kind.name, p.key.sourceId.value, it.value) } ?: true }
                    .map { p ->
                        val row = p.withOverride(overrides[p.key], p.titleIdentity()?.let(titles::get))
                        prefetchArt(listOf(row)) // task 84d: warm art as the page loads (client caps in flight)
                        row
                    }
            }
    }.cachedIn(viewModelScope)

    /** Task 84d: batch-warm TMDB art for the titles currently on screen (off = no request). */
    private fun prefetchArt(rows: List<PosterRow>) {
        val t = tmdb ?: return
        val ids = rows.mapNotNull { it.tmdbId }.distinct()
        if (ids.isEmpty()) return
        val tmdbKind = if (kind == ContentKind.SERIES) com.yodesla.omniverse.core.data.metadata.WikidataMetadata.Kind.SERIES
            else com.yodesla.omniverse.core.data.metadata.WikidataMetadata.Kind.MOVIE
        viewModelScope.launch { t.prefetch(tmdbKind, ids) }
    }

    /**
     * Task 87b: bumps each time the enricher actually stores art for on-screen rows (debounced at the
     * source, at most one bump per 1.5 s). No enricher / keyless build = the flow never fires.
     */
    val tmdbArtVersion: kotlinx.coroutines.flow.Flow<Long> =
        tmdb?.artUpdated ?: kotlinx.coroutines.flow.flowOf(0L)

    /**
     * Task 87b: re-fill art on rows already on screen. Same rows in the same order — the caller swaps
     * its list contents only, so paging, scroll position and focus are untouched.
     */
    suspend fun refreshArt(rows: List<PosterRow>): List<PosterRow> = catalog.refillArt(rows)

    init {
        profiles?.let { pr ->
            pr.current().onEach { p -> _state.update { it.copy(profileName = p.name) } }.launchIn(viewModelScope)
        }
        // Task 84i: restore this profile's last-used filter for this kind.
        viewModelScope.launch {
            SmartCollectionFilter.decode(userData.setting(filterKey).first())?.let { saved ->
                filters.value = saved
                _state.update { it.copy(filter = saved) }
            }
        }
        // Task 84i: the filter panel's genre pills track the current scope.
        combine(selection.filterNotNull(), allExcluded) { sel, ex -> sel to ex }
            .flatMapLatest { (sel, ex) ->
                val (src, cat) = sel
                flowOf(catalog.genreOptions(kind, src, cat?.let(::RemoteId), ex))
            }
            .onEach { opts -> _state.update { it.copy(genreOptions = opts) } }
            .launchIn(viewModelScope)
        // Task 84c: My List row for the Netflix layout — favorites resolved to posters, same
        // visibility + override rules as the grid. Favorite order (newest first) is kept.
        combine(userData.favorites(kind), visibility, customizations) { keys, vis, custom ->
            val (overrides, titles) = custom
            keys.take(60).mapNotNull { k ->
                val poster = catalog.poster(k) ?: return@mapNotNull null
                if (poster.categoryId?.let { !vis(kind.name, poster.key.sourceId.value, it.value) } == true) return@mapNotNull null
                poster.withOverride(overrides[poster.key], poster.titleIdentity()?.let(titles::get))
            }.distinctBy { it.titleIdentity() ?: it.key }
        }.onEach { rows -> prefetchArt(rows); _state.update { it.copy(myList = rows) } }.launchIn(viewModelScope)
        // Task 57: a show whose latest progress is a completed episode rolls on to an "Up next"
        // card instead of vanishing; only shows with activity in the last 30 days qualify.
        val sinceMs = System.currentTimeMillis() - 30L * 86_400_000L
        // Task 89: deduped CW source (one row per exact title, newest visible copy) shared with Home
        // and the Netflix "Continue Watching for …" row; allExcluded (locked + switched-off) picks the rep.
        // Task 95: spoiler-free mode (Settings › Playback, per profile) — an "Up next" card must not
        // spoil which episode comes next while protection is on.
        combine(allExcluded.flatMapLatest { keys -> userData.continueWatchingDeduped(100, keys) }, userData.completedShows(sinceMs), visibility, customizations, userData.setting(UserDataRepository.SPOILER_FREE).map { com.yodesla.omniverse.core.data.SpoilerFree.enabled(it) }) { progress, done, vis, custom, spoilerOn ->
            val (overrides, titles) = custom
            val watching = progress.mapIndexedNotNull { index, item ->
                val posterKey = progressPosterKey(item.key, item.parentId)?.takeIf { it.kind == kind }
                    ?: return@mapIndexedNotNull null
                val poster = catalog.poster(posterKey) ?: return@mapIndexedNotNull null
                if (poster.categoryId?.let { !vis(kind.name, poster.key.sourceId.value, it.value) } == true) return@mapIndexedNotNull null
                // Task 86b: "S1 E5" under a Continue Watching card. Episodes are not stored locally,
                // so this rides the same cached provider lookup the "Up next" cards already use.
                val where = if (item.key.kind == ContentKind.EPISODE && index < 12)
                    upNextResolver.episodeAt(posterKey, item.key.remoteId) else null
                ContinuePosterUi(
                    poster = poster.withOverride(overrides[poster.key], poster.titleIdentity()?.let(titles::get)),
                    progress = item.durationMs?.takeIf { it > 0 }?.let { item.positionMs.toFloat() / it }?.coerceIn(0f, 1f) ?: 0f,
                    episode = item.key.kind == ContentKind.EPISODE,
                    remainingMs = item.durationMs?.takeIf { it > 0 }?.let { (it - item.positionMs).coerceAtLeast(0L) },
                    episodeLabel = if (item.key.kind == ContentKind.EPISODE) cwEpisodeLabel(item) else null,
                    episodeSeason = where?.season,
                    episodeNumber = where?.number,
                    positionMs = item.positionMs,
                    durationMs = item.durationMs,
                )
            }
            // M4: episode "Up next" cards belong to Shows only, and are gated by the poster's own
            // kind (SERIES), never the destination kind — otherwise Movies shows a locked series.
            val upNextCards = if (kind != ContentKind.SERIES) emptyList() else done.mapNotNull { cs ->
                val ep = upNextResolver.nextAfter(cs.seriesKey, cs.lastEpisodeKey.remoteId) ?: return@mapNotNull null
                val poster = catalog.poster(cs.seriesKey) ?: return@mapNotNull null
                if (poster.categoryId?.let { !vis(poster.key.kind.name, poster.key.sourceId.value, it.value) } == true) return@mapNotNull null
                ContinuePosterUi(
                    poster = poster.withOverride(overrides[poster.key], poster.titleIdentity()?.let(titles::get)),
                    progress = 0f, episode = true,
                    upNextLabel = com.yodesla.omniverse.core.data.SpoilerFree.upNextSubtitle(ep.season, ep.number, spoilerOn),
                )
            }
            (watching + upNextCards).distinctBy { it.poster.titleIdentity() ?: it.poster.key }
                    // Copies without TMDB ids (two IPTV categories, Plex + IPTV) still collapse by kind + normalised name.
                    .distinctBy { cwNameKey(it.poster) } // one card per title; newest progress wins
        }
            // Task 92/117: inside a Library the Continue Watching strip is scoped to the library —
            // anime titles for the anime library, the destination's own titles for a category library.
            .combine(library) { cards, lib ->
                when (lib) {
                    LibraryScope.Anime -> cards.filter { isAnimePoster(it.poster) }
                    LibraryScope.Category -> cards.filter { inBrowseCategory(it.poster, selection.value) }
                    null -> cards
                }
            }
            .onEach { cards -> prefetchArt(cards.map { it.poster }); _state.update { it.copy(continueWatching = cards) } }.launchIn(viewModelScope)
        viewModelScope.launch {
            val all = sources.sources().first()
            if (all.isEmpty()) {
                _state.update { it.copy(noSources = true) }
                return@launch
            }
            selection.value = null to null
            // Chips: "All", then each source's categories (Plex libraries first).
            sources.sources().flatMapLatest { list ->
                val ordered = list.sortedBy { if (it.kind == SourceKind.PLEX) 0 else 1 }
                val flows = ordered.map { s ->
                    catalog.categories(s.id, kind).map { cats -> cats.map { s.id to it } }
                }
                if (flows.isEmpty()) kotlinx.coroutines.flow.flowOf(emptyList()) else combine(flows) { it.toList().flatten() }
            }.let { cats -> combine(cats, visibility) { list, vis -> list.filter { (sid, c) -> vis(kind.name, sid.value, c.remoteId.value) } } }
                .combine(userData.setting(orderKey)) { cats, saved ->
                    val ui = cats.map { (sid, c) -> VodCategoryUi(chipId(sid, c.remoteId.value), c.name) }
                    sortBySaved(ui, saved.orEmpty().split('\n').filter { it.isNotBlank() })
                }
                .onEach { cats ->
                    if (moving) return@onEach // the viewer is dragging; their live order wins until they drop it
                    _state.update { s -> s.copy(categories = listOf(VodCategoryUi(null, "All")) + cats) }
                }.launchIn(viewModelScope)
        }
    }

    // A getter, not a field: the init block above reads it before field initializers below would run.
    private val orderKey get() = "category_order_${kind.name}"
    private val filterKey get() = "browse_filter_${kind.name}"
    @Volatile private var moving = false

    /** Hold-OK reorder: move category [id] one step up (-1) or down (+1); "All" stays first. */
    fun moveCategory(id: String, delta: Int) {
        moving = true
        _state.update { s ->
            val list = s.categories.drop(1).toMutableList()
            val i = list.indexOfFirst { it.id == id }
            val j = i + delta
            if (i < 0 || j !in list.indices) return@update s
            list.add(j, list.removeAt(i))
            s.copy(categories = listOf(s.categories.first()) + list)
        }
    }

    /** Drop the grabbed category: save the order for this profile. */
    fun finishMove() {
        if (!moving) return
        val ids = _state.value.categories.drop(1).mapNotNull { it.id }
        viewModelScope.launch { userData.putSetting(orderKey, ids.joinToString("\n")); moving = false }
    }

    /** Task 84m: hold-OK on a category opens its menu (Move / Hide category / Cancel); "All" has none. */
    fun openCategoryMenu(id: String) {
        if (_state.value.categories.none { it.id == id }) return
        _state.update { it.copy(menuCategoryId = id) }
    }

    fun closeCategoryMenu() {
        _state.update { it.copy(menuCategoryId = null) }
    }

    /**
     * Task 84m: Hide category — the library is switched off for this profile (same mechanism as
     * Settings › Sources › Libraries); it leaves the rail at once and selection/focus move to the
     * next chip (or the previous one when it was last, "All" when it was the only one).
     */
    fun hideCategory(id: String) {
        val s = _state.value
        val cat = s.categories.firstOrNull { it.id == id } ?: return
        val rest = s.categories.filterNot { it.id == id }
        val focus = rest.getOrNull(s.categories.indexOf(cat)) ?: rest.lastOrNull()
        _state.update {
            it.copy(
                categories = rest,
                menuCategoryId = null,
                hiddenNotice = com.yodesla.omniverse.designsystem.HiddenNotice(id, cat.name),
            )
        }
        select(focus?.id)
        val (src, remote) = id.split(SEP, limit = 2).let { (sid, c) -> SourceId(sid) to c }
        viewModelScope.launch { userData.setCategoryHidden(src, kind, remote, hidden = true) }
    }

    /** Task 84m: Undo — show the hidden category again and clear the toast. */
    fun undoHideCategory() {
        val n = _state.value.hiddenNotice ?: return
        _state.update { it.copy(hiddenNotice = null) }
        val (src, remote) = n.categoryId.split(SEP, limit = 2).let { (sid, c) -> SourceId(sid) to c }
        viewModelScope.launch { userData.setCategoryHidden(src, kind, remote, hidden = false) }
    }

    /** Task 84m: the Undo toast timed out. */
    fun clearHiddenNotice() {
        _state.update { it.copy(hiddenNotice = null) }
    }

    fun select(id: String?) {
        val parsed = if (id == null) null to null else id.split(SEP, limit = 2).let { (s, c) -> SourceId(s) to c }
        // Task 84i: a service category owns its page; the browse filter only applies to plain ones.
        val branded = _state.value.categories.firstOrNull { it.id == id }?.name
            ?.let { com.yodesla.omniverse.designsystem.categoryBrand(it) } != null
        brandSelected.value = branded
        // Task 92/117: picking a category leaves any open Library behind.
        library.value = null
        // Task 116: entering a category hides the panel; the flag lives here so Back from a title
        // detail returns to this same open destination rather than the rail.
        _state.update { it.copy(selected = id, selection = parsed, branded = branded, animeLibrary = false, libraryOpen = false, destinationOpen = vodPanelHidden(it.destinationOpen, VodBrowsePanelAction.EnterDestination)) }
        selection.value = parsed
    }

    /**
     * Task 117: open this destination's Library. Crunchyroll keeps its task-92 global anime scope
     * (every anime title of this kind, all sources); every other brand's Library is the selected
     * category's own titles, paged/sorted/filtered through the normal browse queries.
     */
    fun openLibrary() {
        val anime = com.yodesla.omniverse.designsystem.categoryBrand(
            _state.value.categories.firstOrNull { it.id == _state.value.selected }?.name.orEmpty(),
        ) == com.yodesla.omniverse.designsystem.CategoryBrand.CRUNCHYROLL
        library.value = if (anime) LibraryScope.Anime else LibraryScope.Category
        _state.update { it.copy(animeLibrary = anime, libraryOpen = true, destinationOpen = vodPanelHidden(it.destinationOpen, VodBrowsePanelAction.EnterDestination)) }
    }

    /** Task 117: Back from any Library returns to the branded page it opened from (panel still hidden, task 116). */
    fun closeLibrary() {
        library.value = null
        _state.update { it.copy(animeLibrary = false, libraryOpen = false, destinationOpen = vodPanelHidden(it.destinationOpen, VodBrowsePanelAction.CloseLibrary)) }
    }

    /** Task 92: the Crunchyroll entry point (kept for its callers/tests; same as openLibrary on a Crunchyroll selection). */
    fun openAnimeLibrary() = openLibrary()

    /** Task 92: Back from the anime library returns to the Crunchyroll page it opened from. */
    fun closeAnimeLibrary() = closeLibrary()

    /** Task 116: Back / Left at the destination's far-left edge reveals the category panel. */
    fun showPanel() {
        _state.update { it.copy(destinationOpen = vodPanelHidden(it.destinationOpen, VodBrowsePanelAction.RevealPanel)) }
    }

    /** Task 92: anime check for a poster — its own category label plus the provider genre. */
    private suspend fun isAnimePoster(p: PosterRow): Boolean {
        val cat = p.categoryId ?: return false
        return com.yodesla.omniverse.core.data.AnimeRules.isAnime(catalog.categoryName(p.key.sourceId, kind, cat), p.genre)
    }

    /** Task 84i: apply a filter now and remember it for this profile/kind. */
    fun setFilter(value: SmartCollectionFilter) {
        filters.value = value
        _state.update { it.copy(filter = value) }
        viewModelScope.launch { userData.putSetting(filterKey, value.encode()) }
    }

    fun clearFilters() = setFilter(SmartCollectionFilter())

    /** Task 84i: freeze the current filter (plus the current source/category) as a smart collection. */
    fun saveCollection(name: String, pinned: Boolean) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val (src, cat) = _state.value.selection ?: (null to null)
        val rule = filters.value.copy(sourceId = src?.value, categoryId = cat)
        val id = java.util.UUID.randomUUID().toString()
        viewModelScope.launch {
            userData.saveCollection(LibraryCollection(id, trimmed, kind, pinnedHome = pinned, mode = "smart", smartFilterJson = rule.encode()))
        }
    }

    private fun chipId(source: SourceId, category: String) = "${source.value}$SEP$category"

    /** Task 84c: "S2:E5" for an in-progress episode; provider lookup is cached like UpNextResolver's. */
    private suspend fun cwEpisodeLabel(progress: com.yodesla.omniverse.core.data.Progress): String? {
        val key = progress.key
        val series = progress.parentId ?: return null
        val cacheKey = "${key.sourceId.value}|${series.value}|${key.remoteId.value}"
        episodeLabels[cacheKey]?.let { (label, at) ->
            if (System.currentTimeMillis() - at < EP_LABEL_TTL_MS) return label
        }
        val label = try {
            sources.contentSource(key.sourceId)?.seriesDetail(series)?.seasons
                ?.asSequence()?.flatMap { it.episodes.asSequence() }
                ?.firstOrNull { it.remoteId == key.remoteId }
                ?.let { "S${it.season}:E${it.number}" }
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            null // provider down or fake without details: the card just omits the label
        }
        if (episodeLabels.size > 128) episodeLabels.clear()
        episodeLabels[cacheKey] = label to System.currentTimeMillis()
        return label
    }

    suspend fun rtScores(row: PosterRow): com.yodesla.omniverse.core.data.metadata.RtScores? = rottenTomatoes?.scores(
        if (row.key.kind == ContentKind.SERIES) com.yodesla.omniverse.core.data.metadata.WikidataMetadata.Kind.SERIES
        else com.yodesla.omniverse.core.data.metadata.WikidataMetadata.Kind.MOVIE, row.tmdbId)

    fun isFavorite(key: ContentKey) = userData.isFavorite(key)
    fun setFavorite(key: ContentKey, favorite: Boolean) { viewModelScope.launch { userData.setFavorite(key, favorite) } }

    // Task 97: Play next queue toggle from the browse title menu.
    fun isQueued(key: ContentKey) = userData.isQueued(key)
    fun togglePlayNext(key: ContentKey, currentlyQueued: Boolean) {
        viewModelScope.launch { userData.setQueued(key, !currentlyQueued) }
    }

    // Task 90: removals capture an exact restore token so the undo card can put the row back.
    fun removeFromContinue(key: ContentKey) {
        viewModelScope.launch {
            val token = userData.removeFromContinueWatching(key)
            _undo.value = com.yodesla.omniverse.core.data.UndoUi("Removed from Continue Watching", token)
        }
    }

    /** My List toggle: removal keeps a token for undo; adding is a plain favourite write. */
    fun toggleMyList(key: ContentKey, currentlyInList: Boolean) {
        viewModelScope.launch {
            if (currentlyInList) {
                val token = userData.removeFromMyList(key)
                _undo.value = com.yodesla.omniverse.core.data.UndoUi("Removed from My List", token)
            } else {
                userData.setFavorite(key, true)
            }
        }
    }

    fun undoLast() {
        val ui = _undo.value ?: return
        _undo.value = null
        viewModelScope.launch {
            when (val token = ui.token) {
                is com.yodesla.omniverse.core.data.UndoToken.ContinueWatching -> userData.restoreContinueWatching(token)
                is com.yodesla.omniverse.core.data.UndoToken.Favorite -> userData.restoreMyList(token)
                else -> Unit
            }
        }
    }

    fun dismissUndo() { _undo.value = null }

    /** Task 86b: is this row's category visible to the current viewer right now? */
    fun isVisible(row: PosterRow): Boolean =
        row.categoryId?.let { currentVisibility(kind.name, row.key.sourceId.value, it.value) } ?: true

    /**
     * Task 86b: every name the provider gives this title — its own plus the names of its other
     * copies (exact TMDB id only, never a title/year guess). The Crunchyroll "Sub | Dub" line reads
     * these; the category's own name is added by the layout.
     */
    suspend fun titleNames(row: PosterRow): List<String> = when (row.key.kind) {
        ContentKind.SERIES -> listOf(row.name) + catalog.exactSeriesMatches(row.key).map { it.name }
        ContentKind.VOD -> listOf(row.name) + catalog.exactMovieMatches(row.key).map { it.name }
        else -> listOf(row.name)
    }

    /** Task 115: the Sort control cycles provider → A–Z → release year → rating → provider. */
    fun toggleSort() = setSort(
        when (sort.value) {
            BrowseSort.PROVIDER -> BrowseSort.ALPHABETICAL
            BrowseSort.ALPHABETICAL -> BrowseSort.RELEASE_YEAR
            BrowseSort.RELEASE_YEAR -> BrowseSort.RATING
            BrowseSort.RATING -> BrowseSort.PROVIDER
        },
    )

    fun setSort(value: BrowseSort) {
        sort.value = value
        _state.update { it.copy(sort = value) }
    }

    private companion object {
        const val SEP = ""
        const val EP_LABEL_TTL_MS = 30L * 60_000L
    }
}

@Composable
fun VodBrowseRoute(viewModel: VodBrowseViewModel, title: String, onOpenNavigation: () -> Unit = {}, onSearch: (() -> Unit)? = null,
                   /** Hold-OK menu: play from the saved position (false) or from the start (true). */
                   onPlay: (ContentKey, Boolean) -> Unit = { _, _ -> }, onOpen: (ContentKey) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val undo by viewModel.undo.collectAsStateWithLifecycle()
    val items = viewModel.items.collectAsLazyPagingItems()
    val c = OmniTheme.colors
    val firstPoster = remember { FocusRequester() }
    val firstContinue = remember { FocusRequester() }
    // This must be registered before the compact-layout return too: Back from a Library
    // returns to its branded destination on both TV and phone.
    androidx.activity.compose.BackHandler(enabled = state.libraryOpen) { viewModel.closeLibrary() }
    var focusedOnce by rememberSaveable { mutableStateOf(false) }
    // Continue Watching is the first destination when present; otherwise land on the grid.
    LaunchedEffect(items.itemCount > 0, state.continueWatching.isNotEmpty()) {
        if (!focusedOnce && (items.itemCount > 0 || state.continueWatching.isNotEmpty())) {
            focusedOnce = runCatching {
                if (state.continueWatching.isNotEmpty()) firstContinue.requestFocus() else firstPoster.requestFocus()
            }.isSuccess
        }
    }
    // OK on a category jumps into its posters once the new list has loaded (Kory: straight into the grid).
    var jumpToGrid by remember { mutableStateOf(false) }
    LaunchedEffect(jumpToGrid) {
        if (!jumpToGrid) return@LaunchedEffect
        kotlinx.coroutines.withTimeoutOrNull(500) {
            androidx.compose.runtime.snapshotFlow { items.loadState.refresh }.first { it is androidx.paging.LoadState.Loading }
        }
        androidx.compose.runtime.snapshotFlow { items.loadState.refresh !is androidx.paging.LoadState.Loading && items.itemCount > 0 }.first { it }
        firstPoster.requestFocusWhenReady()
        jumpToGrid = false
    }
    if (LocalCompact.current) {
        VodBrowseCompact(viewModel = viewModel, state = state, items = items, title = title, firstPoster = firstPoster, firstContinue = firstContinue, onPlay = onPlay, onOpen = onOpen)
        return
    }
    val selectedName = state.categories.firstOrNull { it.id == state.selected }?.name.orEmpty()
    val selectedTint = categoryAccent(selectedName)
    // Opening a category hides the category list so the page gets the whole screen. Back, or
    // Left at the content's far-left edge, brings it back (Kory, 2026-10-01). Task 116: the flag lives
    // in the ViewModel (state.destinationOpen), not composable state, so it survives a title-detail
    // round-trip and Back reopens the same destination with the panel still hidden.
    val railHidden = state.destinationOpen
    // Task 84i: the glass filter panel (Movies/Shows only, never on branded service pages).
    var showFilters by remember { mutableStateOf(false) }
    val selectedCat = remember { FocusRequester() }
    val uiScope = rememberCoroutineScope()
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    // Task 84m: declared here (not inside the rail) so the category menu's "Move" can grab the chip.
    var movingCat by remember { mutableStateOf<String?>(null) }
    // The release of the hold that picked the category up must not drop it again.
    var dropArmed by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = railHidden && !state.libraryOpen) { viewModel.showPanel() }
    // Task 107: the rail's own scroll state, so reopening it can land on the selected category
    // instead of its top (the chip must also be composed for selectedCat to focus it).
    val railState = rememberLazyListState()
    var railEverHidden by rememberSaveable { mutableStateOf(false) }
    val railHeaderCount = (if (onSearch != null) 1 else 0) + 1 +
        (if (com.yodesla.omniverse.designsystem.categoryBrand(selectedName) == null) 1 else 0)
    LaunchedEffect(railHidden) {
        if (railHidden) { railEverHidden = true; return@LaunchedEffect }
        // First composition shows the rail already open: leave focus on the grid/continue row.
        if (!railEverHidden) return@LaunchedEffect
        railState.requestScrollToItem(vodRailFocusIndex(railHeaderCount, state.categories, state.selected))
        selectedCat.requestFocusWhenReady()
    }
    // A service category re-themes the whole page (stage + colours) so it feels like that app.
    var menu by remember { mutableStateOf<TitleMenuTarget?>(null) }
    BrandStage(selectedName, Modifier.fillMaxSize()) {
    @Suppress("NAME_SHADOWING") val c = OmniTheme.colors
    // Service pages drop the page header: the spotlight carries the service mark in its corner and
    // the rows get the height (Kory, 2026-10-01). Sort lives in the category list.
    val serviceBrand = com.yodesla.omniverse.designsystem.categoryBrand(selectedName)
    Column(Modifier.fillMaxSize()) {
        if (serviceBrand == null || state.libraryOpen) Row(Modifier.fillMaxWidth().padding(horizontal = OmniSpacing.tvSide, vertical = OmniSpacing.m), verticalAlignment = Alignment.CenterVertically) {
            if (state.libraryOpen) {
                // Task 92/117: the Library header; Sort/Filter live here because the rail is hidden.
                Text(
                    stringResource(if (state.animeLibrary) R.string.vod_anime_library_title else R.string.vod_library_title),
                    style = OmniTheme.type.browseHeading, color = c.textPrimary,
                )
                com.yodesla.omniverse.designsystem.OmniButton(
                    "Sort: ${browseSortLabel(state.sort)}",
                    { viewModel.toggleSort() },
                    Modifier.padding(start = OmniSpacing.l),
                )
                // Task 117: Filter opens the same glass panel the plain browse pages use; the rail is hidden.
                val n = browseFilterChips(state.filter).size
                OmniButton(if (n > 0) "Filter · $n" else "Filter", { showFilters = true }, Modifier.padding(start = OmniSpacing.m))
                // Task 96: Surprise me sits next to Sort here too (the rail is hidden).
                OmniButton("Surprise me", { viewModel.surpriseMe() }, Modifier.padding(start = OmniSpacing.m))
            } else {
                Text(title, style = OmniTheme.type.browseHeading, color = c.textPrimary)
                if (selectedTint != null) {
                    CategoryDestinationAccent(
                        selectedName,
                        Modifier.padding(horizontal = OmniSpacing.m).width(2.dp).height(28.dp),
                    )
                    CategoryDestinationHeader(selectedName)
                }
                // Task 96: "Surprise me" in the Movies/Shows header.
                OmniButton("Surprise me", { viewModel.surpriseMe() }, Modifier.padding(start = OmniSpacing.l))
            }
        }
        if (state.noSources) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Add a source to browse ${title.lowercase()}.", style = OmniTheme.type.body, color = c.textSecondary)
            }
            return@BrandStage
        }
        // Task 69: the same hold-OK hint Home shows, at the bottom of the Movies/Shows grid.
        val hint by viewModel.hintVisible.collectAsStateWithLifecycle(initialValue = false)
        // Task 87b: art the prefetch stores after the rows were already built re-fills them in place.
        val artVersion by viewModel.tmdbArtVersion.collectAsStateWithLifecycle(initialValue = 0L)
        var hintDone by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxSize()) {
            androidx.compose.animation.AnimatedVisibility(
                visible = !railHidden,
                enter = androidx.compose.animation.expandHorizontally() + androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.shrinkHorizontally() + androidx.compose.animation.fadeOut(),
            ) {
            FocusPivot(parentFraction = 0.3f, leading = 0.dp) {
                LazyColumn(
                    state = railState,
                    modifier = Modifier.width(236.dp).fillMaxHeight().onPreviewKeyEvent { e ->
                        val grabbed = movingCat
                        if (grabbed != null) {
                            // Holding a category: Up/Down move it, OK or Back drops it where it is.
                            when (e.key) {
                                Key.DirectionUp -> { if (e.type == KeyEventType.KeyDown) viewModel.moveCategory(grabbed, -1); true }
                                Key.DirectionDown -> { if (e.type == KeyEventType.KeyDown) viewModel.moveCategory(grabbed, 1); true }
                                Key.DirectionCenter, Key.Enter -> {
                                    if (e.type == KeyEventType.KeyUp) {
                                        if (dropArmed) { movingCat = null; viewModel.finishMove() } else dropArmed = true
                                    }
                                    true
                                }
                                Key.Back -> { if (e.type == KeyEventType.KeyUp) { movingCat = null; viewModel.finishMove() }; true }
                                else -> true
                            }
                        } else if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) { onOpenNavigation(); true } else false
                    },
                    contentPadding = PaddingValues(start = OmniSpacing.tvSide, end = OmniSpacing.m, bottom = OmniSpacing.xxl),
                    verticalArrangement = Arrangement.spacedBy(OmniSpacing.s),
                ) {
                    if (onSearch != null) item(key = "__search") {
                        com.yodesla.omniverse.designsystem.SearchEntryCard(onSearch, Modifier.fillMaxWidth().height(44.dp))
                    }
                    item(key = "__sort") {
                        com.yodesla.omniverse.designsystem.FocusCard(onClick = { viewModel.toggleSort() }, modifier = Modifier.fillMaxWidth().height(44.dp)) {
                            Box(Modifier.fillMaxSize().padding(horizontal = OmniSpacing.m), contentAlignment = Alignment.CenterStart) {
                                Text("Sort: ${browseSortLabel(state.sort)}", style = OmniTheme.type.body, color = c.textSecondary, maxLines = 1)
                            }
                        }
                    }
                    // Task 84i: Filter sits under Sort; branded pages own their own layout instead.
                    if (serviceBrand == null) item(key = "__filter") {
                        val n = browseFilterChips(state.filter).size
                        com.yodesla.omniverse.designsystem.FocusCard(onClick = { showFilters = true }, modifier = Modifier.fillMaxWidth().height(44.dp)) {
                            Box(Modifier.fillMaxSize().padding(horizontal = OmniSpacing.m), contentAlignment = Alignment.CenterStart) {
                                Text(if (n > 0) "Filter · $n" else "Filter", style = OmniTheme.type.body,
                                    color = if (n > 0) c.accent else c.textSecondary, maxLines = 1)
                            }
                        }
                    }
                    items(state.categories, key = { it.id ?: "__all" }) { cat ->
                        val selected = cat.id == state.selected
                        val grabbed = movingCat != null && movingCat == cat.id
                        FocusCard(onClick = { viewModel.select(cat.id); jumpToGrid = true },
                            // Task 84m: hold OK opens the category menu (Move / Hide category / Cancel); "All" has none.
                            onLongClick = if (cat.id != null) ({ viewModel.openCategoryMenu(cat.id) }) else null,
                            modifier = Modifier.fillMaxWidth().height(44.dp).then(if (selected) Modifier.focusRequester(selectedCat) else Modifier)
                                .then(if (grabbed) Modifier.border(2.dp, c.accent, androidx.compose.foundation.shape.RoundedCornerShape(12.dp)) else Modifier)) {
                            if (selected) CategorySelectionGlow(cat.name, Modifier.fillMaxSize())
                            Box(Modifier.fillMaxSize().padding(horizontal = OmniSpacing.m), contentAlignment = Alignment.CenterStart) {
                                CategoryIdentity(cat.name, selected)
                            }
                            if (grabbed) Text("↕", style = OmniTheme.type.title, color = c.accent,
                                modifier = Modifier.align(Alignment.CenterEnd).padding(end = OmniSpacing.m))
                        }
                    }
                }
            }
            }
            val brand = com.yodesla.omniverse.designsystem.categoryBrand(selectedName)
            Box(Modifier.fillMaxSize().onPreviewKeyEvent { e ->
                // At the far-left edge there is nothing further left in the content: show the list again.
                if (railHidden && !state.libraryOpen && e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) {
                    if (!focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Left)) viewModel.showPanel()
                    true // handled either way: we already moved focus, or revealed the list
                } else false
            }) {
            // A service category gets that service's home layout (billboard + rows); others keep the grid.
            // Task 117: an open Library takes the whole screen (the grid path below), even on a brand.
            if (brand != null && !state.libraryOpen) BrandLayout(
                brand, items, title, firstPoster, onOpen,
                onMenu = { p -> menu = TitleMenuTarget(p, null, false) },
                scores = viewModel::rtScores,
                continueWatching = state.continueWatching,
                myList = state.myList,
                profileName = state.profileName,
                selection = state.selection,
                onCwMenu = { cw -> menu = TitleMenuTarget(cw.poster, cw.progress, true) },
                // Task 86b: Crunchyroll's own layout needs the play/My List actions too.
                onPlay = onPlay,
                inMyList = { p -> viewModel.isFavorite(p.key) },
                onToggleMyList = { p, inList -> viewModel.setFavorite(p.key, !inList) },
                titleNames = viewModel::titleNames, visible = viewModel::isVisible,
                // Task 87b: rows built before the prefetch finished get their art as soon as it lands.
                remapArt = viewModel::refreshArt, artVersion = artVersion,
                // Task 92/117: every brand page's Library button opens the full grid (Crunchyroll
                // keeps its global anime scope; the others page their own category).
                onAnimeLibrary = { viewModel.openAnimeLibrary(); jumpToGrid = true },
                onLibrary = { viewModel.openLibrary(); jumpToGrid = true },
            )
            else BoxWithConstraints(Modifier.fillMaxSize()) {
                // Task 102: the columns the adaptive grid actually lays out, so the prefetch window
                // walks the same rows the viewer sees and asks for art at the cell's own size.
                val columns = adaptiveColumnCount(maxWidth.value, 128f, OmniSpacing.l.value, OmniSpacing.s.value, OmniSpacing.tvSide.value)
                val cellWidth = adaptiveCellWidthDp(maxWidth.value, columns, OmniSpacing.l.value, OmniSpacing.s.value, OmniSpacing.tvSide.value)
                var gridFocus by remember { mutableStateOf<PosterFocus?>(null) }
                PosterPrefetch(
                    rows = remember(items.itemCount, columns) {
                        posterRowsFromFlat(items.itemSnapshotList.items.map { it?.posterUrl }, columns)
                    },
                    focus = gridFocus,
                    cardWidth = cellWidth,
                )
                FocusPivot(parentFraction = 0.25f, leading = 0.dp) {
                    val gridState = rememberLazyGridState()
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 128.dp),
                        state = gridState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = OmniSpacing.s, end = OmniSpacing.tvSide, top = OmniSpacing.s, bottom = OmniSpacing.xxl),
                        horizontalArrangement = Arrangement.spacedBy(OmniSpacing.l),
                        verticalArrangement = Arrangement.spacedBy(OmniSpacing.l),
                    ) {
                        if (state.continueWatching.isNotEmpty()) {
                            item(key = "continue-watching", span = { GridItemSpan(maxLineSpan) }) {
                                ContinueWatchingRow(state.continueWatching, onOpen, firstContinue) { cw -> menu = TitleMenuTarget(cw.poster, cw.progress, true) }
                            }
                        }
                        // Task 84i: active filters read as removable chips above the grid.
                        val chips = browseFilterChips(state.filter)
                        if (chips.isNotEmpty()) item(key = "__chips", span = { GridItemSpan(maxLineSpan) }) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(OmniSpacing.s)) {
                                items(chips, key = { it.first }) { (label, without) ->
                                    OmniButton(label, { viewModel.setFilter(without) })
                                }
                                item { OmniButton("Clear all", { viewModel.clearFilters() }) }
                            }
                        }
                        items(items.itemCount, key = items.itemKey { "${it.key.sourceId.value}:${it.key.remoteId.value}" }) { i ->
                            val p = items[i] ?: return@items
                            PosterCard(
                                title = displayTitle(p.name, p.year),
                                modifier = (if (i == 0) Modifier.focusRequester(firstPoster) else Modifier)
                                    .onFocusChanged { if (it.isFocused) gridFocus = PosterFocus(i / columns, i % columns) },
                                posterUrl = p.posterUrl,
                                subtitle = listOfNotNull(p.year?.toString(), p.rating?.let { "%.1f/10".format(it) }).joinToString("  ·  ").ifEmpty { null },
                                onClick = { onOpen(p.key) },
                                onLongClick = { menu = TitleMenuTarget(p, null, false) },
                            )
                        }
                    }
                }
            }
            if (hint && !hintDone) {
                LaunchedEffect(Unit) { hintDone = true }
                com.yodesla.omniverse.designsystem.HoldOkHint(
                    Modifier.align(Alignment.BottomStart).padding(start = OmniSpacing.tvSide, bottom = OmniSpacing.l),
                )
            }
            }
        }
    }
    menu?.let { m ->
        val fav by remember(m.poster.key) { viewModel.isFavorite(m.poster.key) }.collectAsStateWithLifecycle(initialValue = false)
        val queued by remember(m.poster.key) { viewModel.isQueued(m.poster.key) }.collectAsStateWithLifecycle(initialValue = false)
        com.yodesla.omniverse.designsystem.TitleMenu(
            title = displayTitle(m.poster.name, m.poster.year), subtitle = null, posterUrl = m.poster.posterUrl,
            actions = com.yodesla.omniverse.designsystem.titleMenuActions(
                resumeLabel = if ((m.progress ?: 0f) > 0f) "Resume" else null,
                onResume = { onPlay(m.poster.key, false) }, onStartOver = { onPlay(m.poster.key, true) },
                onDetails = { onOpen(m.poster.key) }, inMyList = fav, onToggleMyList = { viewModel.toggleMyList(m.poster.key, fav) },
                inQueue = queued, onToggleQueue = { viewModel.togglePlayNext(m.poster.key, queued) },
                onRemoveContinue = if (m.continueWatching) ({ viewModel.removeFromContinue(m.poster.key) }) else null,
            ),
            onDismiss = { menu = null },
        )
    }
    // Task 96: the "Tonight" card for the Surprise-me pick.
    val pick by viewModel.pick.collectAsStateWithLifecycle()
    pick?.let { offer ->
        PickForMeCard(
            offer = offer,
            onPlay = { p -> onPlay(p.key, false) },
            onAnother = { viewModel.surpriseMe() },
            onDetails = { p -> onOpen(p.key) },
            onDismiss = { viewModel.dismissPick() },
        )
    }
    // Task 84m: hold-OK on a category — Move / Hide category / Cancel, anchored next to the rail.
    val menuCat = state.menuCategoryId?.let { id -> state.categories.firstOrNull { it.id == id } }
    if (menuCat != null) {
        com.yodesla.omniverse.designsystem.CategoryMenu(
            title = menuCat.name,
            actions = listOf(
                com.yodesla.omniverse.designsystem.MenuAction("Move", onClick = {
                    viewModel.closeCategoryMenu()
                    movingCat = menuCat.id
                    dropArmed = false
                    uiScope.launch { runCatching { selectedCat.requestFocusWhenReady() } }
                }),
                com.yodesla.omniverse.designsystem.MenuAction("Hide category", onClick = { menuCat.id?.let { viewModel.hideCategory(it) } }, destructive = true),
                com.yodesla.omniverse.designsystem.MenuAction("Cancel", onClick = {
                    viewModel.closeCategoryMenu()
                    uiScope.launch { runCatching { selectedCat.requestFocusWhenReady() } }
                }),
            ),
            onDismiss = { viewModel.closeCategoryMenu() },
            modifier = Modifier.padding(start = 250.dp),
        )
    }
    // Task 84m: "Category hidden · Undo" toast; focus stays on the chip that took its place.
    state.hiddenNotice?.let { n ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
            com.yodesla.omniverse.designsystem.UndoNotice(
                message = "Category hidden",
                actionLabel = "Undo",
                onAction = { viewModel.undoHideCategory() },
                onDismiss = { viewModel.clearHiddenNotice() },
                modifier = Modifier.padding(start = OmniSpacing.tvSide, bottom = OmniSpacing.xl),
            )
        }
    }
    // Task 84m: after a hide the focused chip is gone; land on the one that took its place.
    LaunchedEffect(state.hiddenNotice?.categoryId) {
        if (state.hiddenNotice != null) runCatching { selectedCat.requestFocusWhenReady() }
    }
    // Task 84i: the glass filter panel; edits a draft and applies it on "Done".
    if (showFilters) {
        FilterPanel(
            initial = state.filter,
            genreOptions = state.genreOptions,
            onApply = { viewModel.setFilter(it) },
            onClear = { viewModel.clearFilters() },
            onSave = { name, pin -> viewModel.saveCollection(name, pin) },
            onDismiss = { showFilters = false },
        )
    }
    undo?.let { ui ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            com.yodesla.omniverse.designsystem.UndoCard(
                message = ui.message,
                onUndo = { viewModel.undoLast() },
                onDismiss = { viewModel.dismissUndo() },
                modifier = Modifier.padding(bottom = OmniSpacing.xl),
            )
        }
    }
    }
}

/** Target of the hold-OK menu; [continueWatching] enables "Remove from Continue Watching". */
private data class TitleMenuTarget(val poster: PosterRow, val progress: Float?, val continueWatching: Boolean)

/** Phone layout: title, horizontal category chips, adaptive poster grid. */
@Composable
private fun VodBrowseCompact(
    viewModel: VodBrowseViewModel,
    state: VodBrowseState,
    items: LazyPagingItems<PosterRow>,
    title: String,
    firstPoster: FocusRequester,
    firstContinue: FocusRequester,
    onPlay: (ContentKey, Boolean) -> Unit,
    onOpen: (ContentKey) -> Unit,
) {
    val c = OmniTheme.colors
    val selectedName = state.categories.firstOrNull { it.id == state.selected }?.name.orEmpty()
    val selectedTint = categoryAccent(selectedName)
    var showFilters by remember { mutableStateOf(false) }
    BrandStage(selectedName, Modifier.fillMaxSize()) {
    @Suppress("NAME_SHADOWING") val c = OmniTheme.colors
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = OmniSpacing.s), verticalAlignment = Alignment.CenterVertically) {
            // Task 117: the phone header reads "Library" while a Library grid is open.
            Text(
                if (state.libraryOpen) stringResource(if (state.animeLibrary) R.string.vod_anime_library_title else R.string.vod_library_title) else title,
                style = OmniTheme.type.title, color = c.textPrimary,
            )
            if (selectedTint != null) {
                Text(
                    selectedName,
                    style = OmniTheme.type.caption,
                    color = c.textSecondary,
                    modifier = Modifier.padding(start = OmniSpacing.s).widthIn(max = 160.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            CategoryDestinationAccent(
                selectedName,
                Modifier.padding(start = OmniSpacing.s).width(3.dp).height(14.dp),
            )
            Spacer(Modifier.weight(1f))
            OmniButton(browseSortLabel(state.sort), { viewModel.toggleSort() })
            // Task 96: "Surprise me" next to Sort on the phone layout too.
            OmniButton("Surprise me", { viewModel.surpriseMe() })
            // Task 117: branded pages get their Library on the phone layout too.
            if (com.yodesla.omniverse.designsystem.categoryBrand(selectedName) != null && !state.libraryOpen) {
                OmniButton(stringResource(R.string.vod_library_title), { viewModel.openLibrary() })
            }
            // Task 84i: the same filter panel on the phone layout; branded pages own their layout
            // except in the Library, where the grid path owns it (task 117).
            if (com.yodesla.omniverse.designsystem.categoryBrand(selectedName) == null || state.libraryOpen) {
                val n = browseFilterChips(state.filter).size
                OmniButton(if (n > 0) "Filter · $n" else "Filter", { showFilters = true })
            }
        }
        if (state.noSources) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Add a source to browse ${title.lowercase()}.", style = OmniTheme.type.body, color = c.textSecondary)
            }
            return@BrandStage
        }
        LazyRow(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = OmniSpacing.s),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.categories, key = { it.id ?: "__all" }) { cat ->
                val selected = cat.id == state.selected
                FocusCard(onClick = { viewModel.select(cat.id) }, modifier = Modifier.height(40.dp)) {
                    Box(Modifier.fillMaxHeight().padding(horizontal = OmniSpacing.m), contentAlignment = Alignment.CenterStart) {
                        CategoryIdentity(cat.name, selected)
                    }
                }
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 110.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.continueWatching.isNotEmpty()) {
                item(key = "continue-watching", span = { GridItemSpan(maxLineSpan) }) {
                    ContinueWatchingRow(state.continueWatching, onOpen, firstContinue)
                }
            }
            items(items.itemCount, key = items.itemKey { "${it.key.sourceId.value}:${it.key.remoteId.value}" }) { i ->
                val p = items[i] ?: return@items
                PosterCard(
                    title = displayTitle(p.name, p.year),
                    modifier = if (i == 0) Modifier.focusRequester(firstPoster) else Modifier,
                    posterUrl = p.posterUrl,
                    subtitle = listOfNotNull(p.year?.toString(), p.rating?.let { "%.1f/10".format(it) }).joinToString("  ·  ").ifEmpty { null },
                    onClick = { onOpen(p.key) },
                )
            }
        }
    }
    }
    if (showFilters) {
        FilterPanel(
            initial = state.filter,
            genreOptions = state.genreOptions,
            onApply = { viewModel.setFilter(it) },
            onClear = { viewModel.clearFilters() },
            onSave = { name, pin -> viewModel.saveCollection(name, pin) },
            onDismiss = { showFilters = false },
        )
    }
    // Task 96: the "Tonight" card for the Surprise-me pick.
    val pick by viewModel.pick.collectAsStateWithLifecycle()
    pick?.let { offer ->
        PickForMeCard(
            offer = offer,
            onPlay = { p -> onPlay(p.key, false) },
            onAnother = { viewModel.surpriseMe() },
            onDetails = { p -> onOpen(p.key) },
            onDismiss = { viewModel.dismissPick() },
        )
    }
}

/**
 * Task 96: the "Tonight: <Title> - 2019 - 7.8" card. Back closes; Play starts the title, Another
 * asks for a fresh pick (the session never repeats one), Details opens the normal detail screen.
 */
@Composable
private fun PickForMeCard(
    offer: PickForMeOffer,
    onPlay: (PosterRow) -> Unit,
    onAnother: () -> Unit,
    onDetails: (PosterRow) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = OmniTheme.colors
    val t = OmniTheme.type
    val p = offer.poster
    val firstFr = remember(p?.key) { FocusRequester() }
    Box(
        Modifier.fillMaxSize().background(Color(0xCC03060C))
            .onPreviewKeyEvent { e -> if (e.key == Key.Back && e.type == KeyEventType.KeyUp) { onDismiss(); true } else false },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.widthIn(max = 820.dp).clip(RoundedCornerShape(22.dp)).background(c.elevated).padding(OmniSpacing.xl),
            horizontalArrangement = Arrangement.spacedBy(OmniSpacing.xl),
        ) {
            Box(Modifier.width(170.dp).height(255.dp).clip(RoundedCornerShape(14.dp)).background(c.surface)) {
                p?.posterUrl?.takeIf { it.isNotBlank() }?.let {
                    AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
            Column(Modifier.widthIn(min = 320.dp, max = 460.dp), verticalArrangement = Arrangement.spacedBy(OmniSpacing.s)) {
                Text("Tonight", style = t.overline, color = c.accent, maxLines = 1)
                if (p == null) {
                    Text("Nothing new to pick", style = t.headline, color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("Everything recent is already watched.", style = t.body, color = c.textSecondary, maxLines = 1)
                } else {
                    Text(displayTitle(p.name, p.year), style = t.headline, color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(p.year?.toString(), p.rating?.let { "%.1f".format(it) }).joinToString(" - ").ifEmpty { "Fresh pick" },
                        style = t.body, color = c.textSecondary, maxLines = 1,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m)) {
                    if (p != null) OmniButton("Play", { onPlay(p) }, Modifier.focusRequester(firstFr), primary = true)
                    OmniButton("Another", onAnother, if (p == null) Modifier.focusRequester(firstFr) else Modifier)
                    if (p != null) OmniButton("Details", { onDetails(p) })
                }
            }
        }
    }
    LaunchedEffect(p?.key) { runCatching { firstFr.requestFocusWhenReady() } }
}

/** Task 84i: glass filter panel — pill rows per criterion; edits a draft, applies it on "Done". */
@Composable
private fun FilterPanel(
    initial: SmartCollectionFilter,
    genreOptions: List<String>,
    onApply: (SmartCollectionFilter) -> Unit,
    onClear: () -> Unit,
    onSave: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = OmniTheme.colors
    var draft by remember { mutableStateOf(initial) }
    var saving by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf(false) }
    val year = remember { java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) }
    // Task 84k: TV users close panels with Back, so Back applies the draft exactly like "Done".
    Dialog(onDismissRequest = { onApply(draft); onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.width(680.dp).heightIn(max = 840.dp).background(c.background, RoundedCornerShape(18.dp))
            .verticalScroll(rememberScrollState()).padding(OmniSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(OmniSpacing.m)) {
            Text("Filter", style = OmniTheme.type.title, color = c.textPrimary)
            FilterPillRow("Watched", listOf(
                "All" to (draft.started == null && draft.completed == null),
                "Unwatched" to (draft.started == false && draft.completed == false),
                "In progress" to (draft.started == true && draft.completed == false),
                "Watched" to (draft.completed == true),
            )) { i -> draft = when (i) {
                1 -> draft.copy(started = false, completed = false)
                2 -> draft.copy(started = true, completed = false)
                3 -> draft.copy(started = null, completed = true)
                else -> draft.copy(started = null, completed = null)
            } }
            val decades = generateSequence(year - year % 10) { it - 10 }.takeWhile { it >= 1950 }.toList()
            FilterPillRow("Year", buildList {
                add("Any" to (draft.yearFrom == null && draft.yearTo == null))
                add("This year" to (draft.yearFrom == year && draft.yearTo == year))
                decades.forEach { d -> add("${d}s" to (draft.yearFrom == d && draft.yearTo == d + 9)) }
            }) { i -> draft = when (i) {
                0 -> draft.copy(yearFrom = null, yearTo = null)
                1 -> draft.copy(yearFrom = year, yearTo = year)
                else -> draft.copy(yearFrom = decades[i - 2], yearTo = decades[i - 2] + 9)
            } }
            if (genreOptions.isNotEmpty()) FilterPillRow("Genre", genreOptions.map { g -> g to (draft.genres?.contains(g) == true) }) { i ->
                val g = genreOptions[i]
                val cur = draft.genres.orEmpty()
                draft = draft.copy(genres = (if (g in cur) cur - g else cur + g).ifEmpty { null })
            }
            FilterPillRow("Rating", listOf(
                "Any" to (draft.ratingAtLeast == null),
                "6+" to (draft.ratingAtLeast == 6f),
                "7+" to (draft.ratingAtLeast == 7f),
                "8+" to (draft.ratingAtLeast == 8f),
            )) { i -> draft = draft.copy(ratingAtLeast = when (i) { 1 -> 6f; 2 -> 7f; 3 -> 8f; else -> null }) }
            FilterPillRow("Runtime", listOf(
                "Any" to (draft.runtimeAtLeastMin == null && draft.runtimeAtMostMin == null),
                "<90 min" to (draft.runtimeAtMostMin == 89),
                "90–120 min" to (draft.runtimeAtLeastMin == 90 && draft.runtimeAtMostMin == 120),
                ">2 h" to (draft.runtimeAtLeastMin == 121),
            )) { i -> draft = when (i) {
                1 -> draft.copy(runtimeAtLeastMin = null, runtimeAtMostMin = 89)
                2 -> draft.copy(runtimeAtLeastMin = 90, runtimeAtMostMin = 120)
                3 -> draft.copy(runtimeAtLeastMin = 121, runtimeAtMostMin = null)
                else -> draft.copy(runtimeAtLeastMin = null, runtimeAtMostMin = null)
            } }
            FilterPillRow("Version", listOf(
                "4K available" to (draft.uhdOnly == true),
                "Plex only" to (draft.plexOnly == true),
            )) { i -> draft = when (i) {
                0 -> draft.copy(uhdOnly = if (draft.uhdOnly == true) null else true)
                else -> draft.copy(plexOnly = if (draft.plexOnly == true) null else true)
            } }
            if (saving) {
                OutlinedTextField(name, { name = it }, label = { Text("Collection name") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(OmniSpacing.s)) {
                    OmniButton(if (pin) "Pin on Home ✓" else "Pin on Home", { pin = !pin })
                    OmniButton("Save", { onSave(name.ifBlank { defaultCollectionName(draft) }, pin); onDismiss() }, primary = true)
                    OmniButton("Cancel", { saving = false })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(OmniSpacing.s)) {
                OmniButton("Save as collection", { saving = true; name = defaultCollectionName(draft) })
                OmniButton("Clear filters", { draft = SmartCollectionFilter(); onClear() })
                OmniButton("Done", { onApply(draft); onDismiss() }, primary = true)
            }
        }
    }
}

/** One labelled row of filter pills; the selected pill renders primary. */
@Composable
private fun FilterPillRow(label: String, options: List<Pair<String, Boolean>>, onSelect: (Int) -> Unit) {
    val c = OmniTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(OmniSpacing.s)) {
        Text(label, style = OmniTheme.type.caption, color = c.textSecondary)
        // Task 84k: leading padding + a left-anchored pivot keep the focused pill fully on screen;
        // centred focus used to cut its left end off ("is year" for "This year").
        FocusPivot(parentFraction = 0f, childFraction = 0f, leading = OmniSpacing.m) {
            LazyRow(
                Modifier.height(44.dp),
                contentPadding = PaddingValues(start = OmniSpacing.m, end = OmniSpacing.m),
                horizontalArrangement = Arrangement.spacedBy(OmniSpacing.s),
            ) {
                itemsIndexed(options) { i, (text, selected) -> OmniButton(text, { onSelect(i) }, primary = selected) }
            }
        }
    }
}

/** A short, horizontal resume shelf keeps the poster grid visible below it. */
@Composable
private fun ContinueWatchingRow(
    cards: List<ContinuePosterUi>,
    onOpen: (ContentKey) -> Unit,
    firstFocus: FocusRequester,
    onMenu: (ContinuePosterUi) -> Unit = {},
) {
    val c = OmniTheme.colors
    Column(Modifier.fillMaxWidth().padding(bottom = OmniSpacing.m)) {
        Text(
            "Continue watching", style = OmniTheme.type.title, color = c.textPrimary,
            modifier = Modifier.padding(start = OmniSpacing.s, bottom = OmniSpacing.s),
        )
        LazyRow(
            contentPadding = PaddingValues(start = OmniSpacing.s, end = OmniSpacing.tvSide),
            horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m),
        ) {
            items(cards, key = { "${it.poster.key.sourceId.value}:${it.poster.key.remoteId.value}" }) { card ->
                val poster = card.poster
                FocusCard(
                    onClick = { onOpen(poster.key) },
                    onLongClick = { onMenu(card) },
                    modifier = Modifier.width(238.dp).height(88.dp)
                        .then(if (card == cards.first()) Modifier.focusRequester(firstFocus) else Modifier),
                ) {
                    Row(Modifier.fillMaxSize().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (!poster.posterUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = poster.posterUrl, contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.width(56.dp).fillMaxHeight().clip(RoundedCornerShape(8.dp)),
                            )
                        } else {
                            Box(
                                Modifier.width(56.dp).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(c.elevated),
                                contentAlignment = Alignment.Center,
                            ) { Text("▶", style = OmniTheme.type.title, color = c.accent) }
                        }
                        Column(Modifier.weight(1f).padding(horizontal = OmniSpacing.s)) {
                            Text(displayTitle(poster.name, poster.year), style = OmniTheme.type.body, color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(OmniSpacing.xs))
                            Text(card.upNextLabel ?: if (card.episode) "Episode in progress" else "Movie in progress", style = OmniTheme.type.caption, color = c.textSecondary)
                            Spacer(Modifier.height(OmniSpacing.xs))
                            // An "Up next" card has nothing in progress: no bar.
                            if (card.upNextLabel == null) ProgressLine(card.progress, Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}

/** Task 115: the Sort control's label for the current choice (TV prefixes it with "Sort: "). */
internal fun browseSortLabel(sort: BrowseSort): String = when (sort) {
    BrowseSort.PROVIDER -> "Provider"
    BrowseSort.ALPHABETICAL -> "A–Z"
    BrowseSort.RELEASE_YEAR -> "Year"
    BrowseSort.RATING -> "Rating"
}

/** Saved ids first in their saved order; categories not in the list keep provider order after them. */
internal fun sortBySaved(cats: List<VodCategoryUi>, saved: List<String>): List<VodCategoryUi> {
    if (saved.isEmpty()) return cats
    val rank = saved.withIndex().associate { (i, id) -> id to i }
    return cats.withIndex().sortedWith(compareBy({ rank[it.value.id] ?: Int.MAX_VALUE }, { it.index })).map { it.value }
}

/** Continue Watching only: kind + normalised title, ignoring provider prefixes, quality tags and year. */
internal fun cwNameKey(p: PosterRow): String {
    val name = p.name.lowercase()
        .replace(Regex("""^\s*([a-z]{2,3}|4k|uhd|fhd|hd)\s*[|:\-▎]\s*"""), "")
        .replace(Regex("""\((19|20)\d{2}\)|\[[^]]*]|\b(4k|uhd|fhd|hd|1080p|720p|multi|sub|dub)\b"""), " ")
        .replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()
    return "${p.key.kind}:name:$name"
}

/** Task 84i: true when the filter excludes something. sourceId/categoryId are selection-scoped, never set here. */
internal fun SmartCollectionFilter.isBrowseActive(): Boolean =
    started != null || completed != null || yearFrom != null || yearTo != null ||
        ratingAtLeast != null || !genres.isNullOrEmpty() || !genreContains.isNullOrBlank() ||
        runtimeAtLeastMin != null || runtimeAtMostMin != null || plexOnly == true || uhdOnly == true

/** Task 84i: removable chips for the active filter — (label, filter with that one criterion removed). */
internal fun browseFilterChips(f: SmartCollectionFilter): List<Pair<String, SmartCollectionFilter>> {
    val chips = mutableListOf<Pair<String, SmartCollectionFilter>>()
    when {
        f.completed == true -> chips += "Watched" to f.copy(completed = null, started = null)
        f.started == true && f.completed == false -> chips += "In progress" to f.copy(started = null, completed = null)
        f.started == false && f.completed == false -> chips += "Unwatched" to f.copy(started = null, completed = null)
        f.started == true -> chips += "Started" to f.copy(started = null)
        f.started == false -> chips += "Unstarted" to f.copy(started = null)
        f.completed == false -> chips += "Not watched" to f.copy(completed = null)
    }
    f.yearFrom?.let { from ->
        val label = when {
            f.yearTo == null -> "$from or later"
            f.yearTo == from -> "$from"
            from % 10 == 0 && f.yearTo == from + 9 -> "${from}s"
            else -> "$from–${f.yearTo}"
        }
        chips += label to f.copy(yearFrom = null, yearTo = null)
    } ?: f.yearTo?.let { chips += "$it or earlier" to f.copy(yearTo = null) }
    f.genres?.filter { it.isNotBlank() }?.forEach { g ->
        val rest = f.genres!!.filter { it != g }
        chips += g to f.copy(genres = rest.ifEmpty { null })
    }
    f.genreContains?.takeIf { it.isNotBlank() }?.let { chips += "~$it" to f.copy(genreContains = null) }
    f.ratingAtLeast?.let { chips += "≥%.1f/10".format(it) to f.copy(ratingAtLeast = null) }
    when {
        f.runtimeAtLeastMin != null && f.runtimeAtMostMin != null ->
            chips += "${f.runtimeAtLeastMin}–${f.runtimeAtMostMin} min" to f.copy(runtimeAtLeastMin = null, runtimeAtMostMin = null)
        f.runtimeAtLeastMin != null -> chips += "≥${f.runtimeAtLeastMin} min" to f.copy(runtimeAtLeastMin = null)
        f.runtimeAtMostMin != null -> chips += "≤${f.runtimeAtMostMin} min" to f.copy(runtimeAtMostMin = null)
    }
    if (f.plexOnly == true) chips += "Plex only" to f.copy(plexOnly = null)
    if (f.uhdOnly == true) chips += "4K" to f.copy(uhdOnly = null)
    return chips
}

/** Task 84i: a sensible default collection name built from the active criteria ("Unwatched 2010s Action"). */
internal fun defaultCollectionName(f: SmartCollectionFilter): String {
    val parts = mutableListOf<String>()
    when {
        f.completed == true -> parts += "Watched"
        f.started == true && f.completed == false -> parts += "In progress"
        f.started == false && f.completed == false -> parts += "Unwatched"
        f.started == true -> parts += "Started"
        f.started == false -> parts += "Unstarted"
        f.completed == false -> parts += "Not watched"
    }
    f.yearFrom?.let { from ->
        parts += when {
            f.yearTo == null -> "${from}s+"
            f.yearTo == from -> "$from"
            from % 10 == 0 && f.yearTo == from + 9 -> "${from}s"
            else -> "$from-${f.yearTo}"
        }
    } ?: f.yearTo?.let { parts += "≤$it" }
    f.genres?.filter { it.isNotBlank() }?.forEach { parts += it }
    f.genreContains?.takeIf { it.isNotBlank() }?.let { parts += "~$it" }
    f.ratingAtLeast?.let { parts += "≥%.0f".format(it) }
    (f.runtimeAtLeastMin ?: f.runtimeAtMostMin)?.let { parts += "${it}min" }
    if (f.plexOnly == true) parts += "Plex"
    if (f.uhdOnly == true) parts += "4K"
    return parts.joinToString(" ").ifBlank { "Filter" }
}
