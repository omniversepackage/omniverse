package com.yodesla.omniverse.feature.vod

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxWidth as fmw
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.yodesla.omniverse.core.data.CatalogRepository
import com.yodesla.omniverse.core.data.ChannelRow
import com.yodesla.omniverse.core.data.EpgRepository
import com.yodesla.omniverse.core.data.PosterRow
import com.yodesla.omniverse.core.data.UserDataRepository
import com.yodesla.omniverse.core.data.Visibility
import com.yodesla.omniverse.core.data.TitleIdentity
import com.yodesla.omniverse.core.data.metadata.AnimeHubFeeds
import com.yodesla.omniverse.core.data.metadata.ChannelArt
import com.yodesla.omniverse.core.data.metadata.DiscoveryFeed
import com.yodesla.omniverse.core.data.metadata.RottenTomatoes
import com.yodesla.omniverse.core.data.metadata.RtScores
import com.yodesla.omniverse.core.data.metadata.TmdbEnricher
import com.yodesla.omniverse.core.data.metadata.WikidataMetadata
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.progressPosterKey
import com.yodesla.omniverse.designsystem.FocusPivot
import com.yodesla.omniverse.designsystem.LocalVisualTier
import com.yodesla.omniverse.designsystem.OmniSpacing
import com.yodesla.omniverse.designsystem.OmniTheme
import com.yodesla.omniverse.designsystem.VisualTier
import com.yodesla.omniverse.designsystem.displayTitle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/** Task 142: the hub's four tabs. */
enum class AnimeTab(val label: String) { HOME("Home"), SERIES("Series"), MOVIES("Movies"), CHANNELS("Live channels") }

data class AnimeHubState(
    val loaded: Boolean = false,
    val tab: AnimeTab = AnimeTab.HOME,
    val featured: List<PosterRow> = emptyList(),
    /** Hero kicker per featured row uid ("#2 TRENDING SERIES THIS WEEK · TMDB"). */
    val heroKickers: Map<String, String> = emptyMap(),
    val continueWatching: List<ContinuePosterUi> = emptyList(),
    val shelves: List<AnimeShelf> = emptyList(),
    val channels: List<HubChannel> = emptyList(),
    val channelKind: ChannelKindFilter = ChannelKindFilter.ALL,
    val channelService: String? = null,
    val channelSort: ChannelSort = ChannelSort.FEATURED,
    val gridFilter: AnimeGridFilter = AnimeGridFilter(),
    val gridGenres: List<String> = emptyList(),
    val grid: List<PosterRow> = emptyList(),
    val gridRanks: Map<String, Int> = emptyMap(),
    val gridLoading: Boolean = false,
    /** Series / Movies homes: shelves and hero titles per kind. */
    val kindShelves: Map<ContentKind, List<AnimeShelf>> = emptyMap(),
    val kindFeatured: Map<ContentKind, List<PosterRow>> = emptyMap(),
    /** Series / Movies: true while the full filterable grid replaces that kind's home. */
    val gridOpen: Boolean = false,
    /** Task 143: the Anime-only search (Kory: "a search within the anime section that only searches anime"). */
    val searchOpen: Boolean = false,
    val searchQuery: String = "",
    val searchBusy: Boolean = false,
    val searchSeries: List<PosterRow> = emptyList(),
    val searchMovies: List<PosterRow> = emptyList(),
    val searchChannels: List<HubChannel> = emptyList(),
) {
    val animeChannels: List<HubChannel> get() = channels.filter { it.kind == AnimeChannelKind.ANIME }
    val cartoonChannels: List<HubChannel> get() = channels.filter { it.kind == AnimeChannelKind.CARTOON }
    val services: List<String> get() = channels.map { channelService(it.row.name) }.distinct()
    val shownChannels: List<HubChannel> get() = channelGrid(channels, channelKind, channelService, channelSort)
}

/**
 * Task 142 (Kory: "a full clone of Crunchyroll with every kind of animated show and movie, plus any
 * anime-style TV channels … REAL trending, not made up"): Explore › Anime. Titles come from one
 * cheap scan per kind ([CatalogRepository.animatedTitles]); trending comes only from TMDB's public
 * trending charts (Animation genre), matched to the library by exact TMDB id ([AnimeHubFeeds]).
 * Channel tiles carry TMDB colour logos/key art and the guide's now-playing title.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AnimeHubViewModel(
    private val catalog: CatalogRepository,
    private val epg: EpgRepository,
    private val userData: UserDataRepository,
    private val visibility: Flow<Visibility>,
    /** KIND|source|category keys this profile must not see (same set Search uses). */
    private val excludedCategoryKeys: Flow<Set<String>>,
    private val tmdb: TmdbEnricher? = null,
    private val rottenTomatoes: RottenTomatoes? = null,
    private val feeds: AnimeHubFeeds? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(AnimeHubState())
    val state: StateFlow<AnimeHubState> = _state.asStateFlow()
    val artVersion: Flow<Long> = tmdb?.artUpdated ?: flowOf(0L)
    // Declared before init: the init flows below can run immediately and read these.
    private var excluded: Set<String> = emptySet()
    /** Hero pools per page ("all" = Home, else ContentKind name), in chart order. */
    private var heroPools: Map<String, List<PosterRow>> = emptyMap()
    private var gridJob: kotlinx.coroutines.Job? = null
    private var homeJob: kotlinx.coroutines.Job? = null
    /** Every anime/animated title per kind (pool scan + charted titles filed under other categories). */
    private var pools: kotlinx.coroutines.Deferred<Map<ContentKind, List<HubTitle>>>? = null
    /** This week's TMDB animation chart rank per title ([hubId]) — the grids' Trending sort. */
    private var weekRanks: Map<String, Int> = emptyMap()
    private var channelRows: List<Pair<ChannelRow, AnimeChannelKind>> = emptyList()
    private var channelArt: Map<String, ChannelArt> = emptyMap()
    private var nowPlaying: Map<String, Pair<String, Float>> = emptyMap()

    init {
        excludedCategoryKeys.distinctUntilChanged().onEach { keys ->
            excluded = keys
            loadHome()
        }.launchIn(viewModelScope)
        visibility.onEach { vis -> loadChannels(vis) }.launchIn(viewModelScope)
        feeds?.let { f ->
            // Charts older than 6 h refresh on open; the page re-cuts its shelves when they land.
            viewModelScope.launch { runCatching { f.ensureTrending() }; loadHome() }
            f.channelArtCache().onEach { channelArt = it; publishChannels() }.launchIn(viewModelScope)
        }
        // Now playing on every hub channel, refreshed each minute while the hub is alive.
        viewModelScope.launch { while (true) { delay(60_000); refreshNowPlaying(); publishChannels() } }
        excludedCategoryKeys.flatMapLatest { keys -> userData.continueWatchingDeduped(80, keys) }
            .onEach { progress ->
                val cards = progress.mapNotNull { item ->
                    val posterKey = progressPosterKey(item.key, item.parentId) ?: return@mapNotNull null
                    val poster = catalog.poster(posterKey) ?: return@mapNotNull null
                    val category = poster.categoryId?.let { catalog.categoryName(poster.key.sourceId, poster.key.kind, it) }
                    if (!isAnimatedTitle(category, poster.genre)) return@mapNotNull null
                    ContinuePosterUi(
                        poster = poster,
                        progress = item.durationMs?.takeIf { it > 0 }?.let { item.positionMs.toFloat() / it }?.coerceIn(0f, 1f) ?: 0f,
                        episode = item.key.kind == ContentKind.EPISODE,
                        remainingMs = item.durationMs?.takeIf { it > 0 }?.let { (it - item.positionMs).coerceAtLeast(0L) },
                        positionMs = item.positionMs,
                        durationMs = item.durationMs,
                    )
                }.distinctBy { it.poster.titleIdentity() ?: it.poster.key }.distinctBy { it.poster.uid() }
                    .distinctBy { cwNameKey(it.poster) } // copies without TMDB ids (Plex + IPTV) collapse by name, like Movies/Shows
                    .take(20)
                prefetch(cards.map { it.poster })
                _state.update { it.copy(continueWatching = catalog.refillArt(cards.map { c -> c.poster }).zip(cards) { p, c -> c.copy(poster = p) }) }
            }.launchIn(viewModelScope)
        artVersion.onEach { refreshArt() }.launchIn(viewModelScope)
    }

    private suspend fun titles(kind: ContentKind): List<HubTitle> {
        val raw = runCatching { catalog.animatedTitles(kind, excluded) }.getOrDefault(emptyList())
        // Real ratings: drop the provider's placeholder score, then apply cached TMDB scores.
        val rows = runCatching { catalog.refillArt(dropPlaceholderRatings(raw.map { it.row })) }.getOrElse { raw.map { it.row } }
        return rows.zip(raw) { row, t -> HubTitle(row, t.anime) }
            .sortedWith(compareBy<HubTitle> { it.row.rating == null }.thenByDescending { it.row.rating ?: 0f })
    }

    private fun visible(row: PosterRow): Boolean =
        row.categoryId?.let { "${row.key.kind.name}|${row.key.sourceId.value}|${it.value}" !in excluded } ?: true

    /** A TMDB trending chart resolved to library copies by exact TMDB id: (row, chart rank, Japanese original). */
    private suspend fun chart(window: String, type: String): List<Triple<PosterRow, Int, Boolean>> {
        val f = feeds ?: return emptyList()
        val kind = if (type == "movie") ContentKind.VOD else ContentKind.SERIES
        return f.trending(window, type).first().titles.mapNotNull { t ->
            val row = runCatching { catalog.titleCandidates(TitleIdentity(kind, "tmdb", t.id)) }.getOrDefault(emptyList()).firstOrNull(::visible)
                ?: return@mapNotNull null
            Triple(row, t.rank, t.lang == "ja")
        }.distinctBy { it.first.hubId() }
    }


    /**
     * Home + the Series and Movies homes: pools and charts load together; trending + anime shelves and
     * the heroes publish first, the cartoon shelves follow. A title appears once per page (first shelf wins).
     */
    private fun loadHome() {
        homeJob?.cancel()
        homeJob = viewModelScope.launch {
            val S = ContentKind.SERIES
            val M = ContentKind.VOD
            val series = async { titles(S) }
            val movies = async { titles(M) }
            val weekTv = async { chart("week", "tv") }
            val weekMovie = async { chart("week", "movie") }
            val dayTv = async { chart("day", "tv") }
            val dayMovie = async { chart("day", "movie") }
            pools = async {
                fun withCharts(pool: List<HubTitle>, charts: List<Triple<PosterRow, Int, Boolean>>): List<HubTitle> {
                    val have = pool.mapTo(HashSet()) { it.row.hubId() }
                    return pool + charts.filter { it.first.hubId() !in have }.map { HubTitle(it.first, it.third) }
                }
                mapOf(S to withCharts(series.await(), weekTv.await()), M to withCharts(movies.await(), weekMovie.await()))
            }
            weekRanks = (weekTv.await() + weekMovie.await()).associate { it.first.hubId() to it.second }
            if (_state.value.gridOpen) loadGrid()
            val updated = feeds?.trending("week", "tv")?.first()?.at?.takeIf { it > 0 }?.let {
                java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
            }.orEmpty()
            val caption = "TMDB trending · Animation" + (if (updated.isNotEmpty()) " · updated $updated" else "")
            fun ranked(key: String, title: String, rows: List<Triple<PosterRow, Int, Boolean>>) =
                AnimeShelf(key, title, rows.map { it.first }, caption, rows.associate { it.first.uid() to it.second })
            val aS = series.await().filter { it.anime }.map { it.row }
            val aM = movies.await().filter { it.anime }.map { it.row }
            val cS = series.await().filterNot { it.anime }.map { it.row }
            val cM = movies.await().filterNot { it.anime }.map { it.row }
            fun newest(rows: List<PosterRow>) = rows.filter { it.year != null }.sortedByDescending { it.year }.take(60)
            fun classic(rows: List<PosterRow>) = ratedOnly(rows).filter { (it.year ?: 9999) <= 1999 }.take(40)
            val topSeries = ratedOnly(aS).take(60)
            val topMovies = ratedOnly(aM).take(60)
            val first = listOf(
                ranked("trend-week-tv", "Trending This Week · Shows", weekTv.await()),
                ranked("trend-week-movie", "Trending This Week · Movies", weekMovie.await()),
                ranked("trend-day-tv", "Trending Today · Shows", dayTv.await()),
                AnimeShelf("top-series", "Top anime series", topSeries),
                AnimeShelf("new-series", "New anime series", newest(aS)),
                AnimeShelf("top-movies", "Anime movies", topMovies),
                AnimeShelf("new-movies", "New anime movies", newest(aM)),
                AnimeShelf("classic", "Classic anime", interleave(classic(aS), classic(aM))),
            )
            val toons = listOf(
                AnimeShelf("cartoon-series", "Cartoons & animated series", ratedOnly(cS).take(60)),
                AnimeShelf("animated-movies", "Animated movies", ratedOnly(cM).take(60)),
                AnimeShelf("new-animated", "New animated movies", newest(cM)),
                AnimeShelf("new-cartoons", "New cartoon series", newest(cS)),
            )
            /**
             * The Series / Movies home (Kory: "a home screen for movies and the same for shows; filters
             * if they want them"): this kind only — its trending, the best and newest anime, a shelf per
             * leading provider genre, classics, then cartoons.
             */
            fun kindHome(kind: ContentKind, week: List<Triple<PosterRow, Int, Boolean>>, day: List<Triple<PosterRow, Int, Boolean>>,
                         anime: List<PosterRow>, animeTitles: List<HubTitle>, cartoons: List<PosterRow>): List<AnimeShelf> {
                val noun = if (kind == S) "series" else "movies"
                val label = if (kind == S) "Shows" else "Movies"
                val genres = topGenres(animeTitles, max = 5).map { g ->
                    AnimeShelf("g-$kind-$g", "$g anime", ratedOnly(anime).filter { r -> genresOf(r).any { it.equals(g, ignoreCase = true) } }.take(40))
                }
                return animeHubShelves(listOf(
                    ranked("k-week-$kind", "Trending This Week · $label", week),
                    ranked("k-day-$kind", "Also trending today", day),
                    AnimeShelf("k-top-$kind", "Top anime $noun", ratedOnly(anime).take(60)),
                    AnimeShelf("k-new-$kind", "New anime $noun", newest(anime)),
                ) + genres + listOf(
                    AnimeShelf("k-classic-$kind", "Classic anime $noun", classic(anime)),
                    AnimeShelf("k-toon-top-$kind", if (kind == S) "Cartoons & animated series" else "Animated movies", ratedOnly(cartoons).take(60)),
                    AnimeShelf("k-toon-new-$kind", if (kind == S) "New cartoon series" else "New animated movies", newest(cartoons)),
                ))
            }
            val kindShelves = mapOf(
                S to kindHome(S, weekTv.await(), dayTv.await(), aS, series.await().filter { it.anime }, cS),
                M to kindHome(M, weekMovie.await(), dayMovie.await(), aM, movies.await().filter { it.anime }, cM),
            )
            _state.update { it.copy(loaded = true, shelves = animeHubShelves(first + toons), kindShelves = kindShelves) }
            // Heroes: this week's chart when it is cached, else top rated. Gapless numbering within
            // what the library has (chart order kept).
            fun numbered(list: List<Triple<PosterRow, Int, Boolean>>) = list.mapIndexed { i, t -> t.copy(second = i + 1) }
            val nTv = numbered(weekTv.await()).take(8)
            val nMovie = numbered(weekMovie.await()).take(8)
            val kickers = (nTv + nMovie).associate { (row, rank, _) ->
                row.uid() to "#$rank TRENDING ${if (row.key.kind == S) "SERIES" else "MOVIE"} THIS WEEK · TMDB"
            }
            heroPools = mapOf(
                "all" to interleave(nTv, nMovie).map { it.first }.ifEmpty { interleave(topSeries.take(15), topMovies.take(15)) },
                S.name to nTv.map { it.first }.ifEmpty { topSeries.take(15) },
                M.name to nMovie.map { it.first }.ifEmpty { topMovies.take(15) },
            )
            heroPools.values.forEach { prefetch(it) }
            (first + toons + kindShelves.values.flatten()).forEach { prefetch(it.rows.take(10)) }
            publishHeroes(kickers)
        }
    }

    private suspend fun publishHeroes(kickers: Map<String, String>? = null) {
        val all = withArt(heroPools["all"].orEmpty())
        val byKind = mapOf(
            ContentKind.SERIES to withArt(heroPools[ContentKind.SERIES.name].orEmpty()),
            ContentKind.VOD to withArt(heroPools[ContentKind.VOD.name].orEmpty()),
        )
        _state.update { it.copy(featured = all, kindFeatured = byKind, heroKickers = kickers ?: it.heroKickers) }
    }

    /** Featured titles in [pool] order (chart order), key art required. */
    private suspend fun withArt(pool: List<PosterRow>): List<PosterRow> {
        val full = pool.map { runCatching { catalog.poster(it.key) }.getOrNull() ?: it }
        return catalog.refillArt(full).filter { !crunchyrollKeyArt(it).isNullOrBlank() }.distinctBy { it.uid() }.take(6)
    }

    private suspend fun refreshArt() {
        if (heroPools.isNotEmpty()) publishHeroes()
        val cw = _state.value.continueWatching
        if (cw.isNotEmpty()) _state.update { it.copy(continueWatching = catalog.refillArt(cw.map { c -> c.poster }).zip(cw) { p, c -> c.copy(poster = p) }) }
    }

    private suspend fun loadChannels(vis: Visibility) {
        val candidates = runCatching { epg.animeChannelCandidates(1500) }.getOrDefault(emptyList())
        val rows = animeChannelRows(candidates) { c -> vis(ContentKind.LIVE.name, c.key.sourceId.value, c.categoryId.value) }
        // Provider order across both kinds (candidates arrive in channel-number order).
        val order = candidates.mapIndexed { i, (c, _) -> c.uidKey() to i }.toMap()
        channelRows = rows.flatMap { (kind, list) -> list.map { it to kind } }.sortedBy { order[it.first.uidKey()] ?: Int.MAX_VALUE }
        publishChannels()
        refreshNowPlaying()
        publishChannels()
        feeds?.let { f -> viewModelScope.launch { runCatching { f.ensureChannelArt(channelRows.map { AnimeHubFeeds.channelCoreName(it.first.name) }) } } }
    }

    private suspend fun refreshNowPlaying() {
        val now = System.currentTimeMillis()
        val out = HashMap<String, Pair<String, Float>>()
        channelRows.map { it.first }.filter { !it.epgKey.isNullOrBlank() }.groupBy { it.key.sourceId }.forEach { (src, list) ->
            val nn = runCatching { epg.nowNext(src, list.mapNotNull { it.epgKey }, now) }.getOrDefault(emptyMap())
            for (ch in list) {
                val p = nn[ch.epgKey]?.now ?: continue
                val span = (p.endMs - p.startMs).coerceAtLeast(1)
                out[ch.uidKey()] = p.title to ((now - p.startMs).toFloat() / span).coerceIn(0f, 1f)
            }
        }
        nowPlaying = out
    }

    private fun publishChannels() {
        val list = channelRows.map { (row, kind) ->
            val now = nowPlaying[row.uidKey()]
            HubChannel(row, kind, channelArt[AnimeHubFeeds.channelCoreName(row.name).lowercase()], now?.first, now?.second)
        }
        _state.update { it.copy(channels = list) }
    }

    private fun prefetch(rows: List<PosterRow>) {
        val t = tmdb ?: return
        rows.groupBy { it.key.kind }.forEach { (kind, list) ->
            val ids = list.mapNotNull { it.tmdbId }.distinct()
            if (ids.isEmpty()) return@forEach
            val k = if (kind == ContentKind.SERIES) WikidataMetadata.Kind.SERIES else WikidataMetadata.Kind.MOVIE
            viewModelScope.launch { runCatching { t.prefetch(k, ids) } }
        }
    }

    fun selectTab(tab: AnimeTab) {
        if (_state.value.tab == tab) return
        _state.update { it.copy(tab = tab, grid = emptyList(), gridOpen = false) }
    }

    /** Series / Movies open on their home; the full grid with filters is one step further. */
    fun openGrid() {
        if (_state.value.gridOpen) return
        _state.update { it.copy(gridOpen = true) }
        loadGrid()
    }

    fun closeGrid() = _state.update { it.copy(gridOpen = false, grid = emptyList()) }

    private var searchJob: kotlinx.coroutines.Job? = null

    fun openSearch() = _state.update { it.copy(searchOpen = true) }

    fun closeSearch() {
        searchJob?.cancel()
        _state.update { it.copy(searchOpen = false, searchQuery = "", searchBusy = false, searchSeries = emptyList(), searchMovies = emptyList(), searchChannels = emptyList()) }
    }

    /** Typing shows the spinner at once; the in-memory search lands a beat later and the spinner clears. */
    fun onSearchQuery(q: String) {
        searchJob?.cancel()
        if (q.isBlank()) {
            _state.update { it.copy(searchQuery = q, searchBusy = false, searchSeries = emptyList(), searchMovies = emptyList(), searchChannels = emptyList()) }
            return
        }
        _state.update { it.copy(searchQuery = q, searchBusy = true) }
        searchJob = viewModelScope.launch {
            delay(120)
            val p = pools?.await().orEmpty()
            val (series, movies, channels) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                Triple(
                    searchHubTitles(p[ContentKind.SERIES].orEmpty(), q),
                    searchHubTitles(p[ContentKind.VOD].orEmpty(), q),
                    searchHubChannels(_state.value.channels, q),
                )
            }
            prefetch(series.take(20) + movies.take(20))
            _state.update { it.copy(searchSeries = series, searchMovies = movies, searchChannels = channels, searchBusy = false) }
        }
    }

    fun setGridFilter(filter: AnimeGridFilter) {
        if (_state.value.gridFilter == filter) return
        _state.update { it.copy(gridFilter = filter) }
        loadGrid()
    }

    fun setChannelKind(kind: ChannelKindFilter) = _state.update { it.copy(channelKind = kind) }
    fun setChannelService(service: String?) = _state.update { it.copy(channelService = service) }
    fun setChannelSort(sort: ChannelSort) = _state.update { it.copy(channelSort = sort) }

    /** Series / Movies tab: the kind's whole pool, filtered and ordered in memory. */
    private fun loadGrid() {
        val s = _state.value
        val kind = if (s.tab == AnimeTab.MOVIES) ContentKind.VOD else ContentKind.SERIES
        gridJob?.cancel()
        _state.update { it.copy(gridLoading = true) }
        gridJob = viewModelScope.launch {
            val all = pools?.await()?.get(kind).orEmpty().distinctBy { it.row.uid() }
            val rows = animeGrid(all, s.gridFilter, weekRanks)
            _state.update { it.copy(grid = rows, gridGenres = topGenres(all), gridRanks = weekRanks, gridLoading = false) }
            prefetch(rows.take(28))
        }
    }

    suspend fun titleNames(row: PosterRow): List<String> = listOfNotNull(
        row.name,
        row.categoryId?.let { runCatching { catalog.categoryName(row.key.sourceId, row.key.kind, it) }.getOrNull() },
    )

    /**
     * The highlighted title in full for the pinned banner: its TMDB logo, backdrop, certification and
     * plot (fetched now when not cached yet), keeping the provider genre when TMDB has none.
     */
    suspend fun detailed(row: PosterRow): PosterRow {
        val id = row.tmdbId
        val t = tmdb
        if (t != null && id != null) {
            val k = if (row.key.kind == ContentKind.SERIES) WikidataMetadata.Kind.SERIES else WikidataMetadata.Kind.MOVIE
            runCatching { t.prefetch(k, listOf(id)) }
        }
        val full = runCatching { catalog.poster(row.key) }.getOrNull() ?: row
        val filled = runCatching { catalog.refillArt(listOf(full)).firstOrNull() }.getOrNull() ?: full
        return filled.copy(genre = filled.genre ?: row.genre, rating = filled.rating ?: row.rating, year = filled.year ?: row.year)
    }

    suspend fun rtScores(row: PosterRow): RtScores? = rottenTomatoes?.scores(
        if (row.key.kind == ContentKind.SERIES) WikidataMetadata.Kind.SERIES else WikidataMetadata.Kind.MOVIE, row.tmdbId)

    fun isFavorite(key: ContentKey) = userData.isFavorite(key)
    fun setFavorite(key: ContentKey, favorite: Boolean) { viewModelScope.launch { userData.setFavorite(key, favorite) } }
}

private fun ChannelRow.uidKey() = key.sourceId.value + ":" + key.remoteId.value

/**
 * Task 142: Explore › Anime — the Crunchyroll-style page for everything animated. Black page, a
 * billboard led by TMDB's live animation chart, ranked trending rows, Crunchyroll's poster rows and
 * focus treatment. Series and Movies open on their own homes; their full grids with filters are one
 * step further ("Browse all"). Live channels carry premium tiles.
 */
@Composable
fun AnimeHubRoute(
    viewModel: AnimeHubViewModel,
    onOpen: (ContentKey) -> Unit,
    onPlay: (ContentKey, Boolean) -> Unit,
    onWatchChannel: (ContentKey, RemoteId) -> Unit,
    onOpenNavigation: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackHandler {
        when {
            state.searchOpen -> viewModel.closeSearch()
            state.gridOpen -> viewModel.closeGrid()
            state.tab != AnimeTab.HOME -> viewModel.selectTab(AnimeTab.HOME)
            else -> onOpenNavigation()
        }
    }
    val firstFocus = remember { FocusRequester() }
    val tabFocus = remember { FocusRequester() }
    // The hero swaps its placeholder for the first featured title once art resolves: re-land focus on it.
    LaunchedEffect(state.loaded, state.featured.isNotEmpty()) { if (state.loaded && state.tab == AnimeTab.HOME) { delay(60); runCatching { firstFocus.requestFocus() } } }
    // Left at the start of any row opens the navigation rail (the app-wide edge rule).
    val leftEdge: (Int) -> Modifier = { i ->
        if (i == 0) Modifier.onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) { onOpenNavigation(); true } else false } else Modifier
    }
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val alpha by animateFloatAsState(if (entered) 1f else 0f, tween(300), label = "animeEntry")
    Box(Modifier.fillMaxSize().background(Color.Black).alpha(alpha)) {
        androidx.compose.runtime.CompositionLocalProvider(LocalAnimeSearchOpen provides viewModel::openSearch) {
        if (state.searchOpen) AnimeSearch(state, viewModel, tabFocus, leftEdge, onOpen, onWatchChannel) else
        when (state.tab) {
            AnimeTab.HOME -> AnimeHome(
                state, viewModel, null, state.featured, state.shelves, state.continueWatching,
                firstFocus, tabFocus, leftEdge, onOpen, onPlay, onWatchChannel, onOpenNavigation,
            )
            AnimeTab.SERIES, AnimeTab.MOVIES -> {
                val kind = if (state.tab == AnimeTab.MOVIES) ContentKind.VOD else ContentKind.SERIES
                if (state.gridOpen) AnimeGrid(state, viewModel, tabFocus, leftEdge, onOpen)
                else AnimeHome(
                    state, viewModel, kind, state.kindFeatured[kind].orEmpty(), state.kindShelves[kind].orEmpty(),
                    state.continueWatching.filter { it.poster.key.kind == kind },
                    firstFocus, tabFocus, leftEdge, onOpen, onPlay, onWatchChannel, onOpenNavigation,
                )
            }
            AnimeTab.CHANNELS -> AnimeChannelsGrid(state, viewModel, tabFocus, leftEdge, onWatchChannel)
        }
        }
    }
}

/**
 * The top bar: the ANIME wordmark, the four tabs and an optional page action (Browse all / ◂ Home).
 * [compact] (Kory: the menu bar shrinks while you scroll down through the rows) animates it to a slim strip.
 */
@Composable
private fun AnimeTabs(
    current: AnimeTab, onSelect: (AnimeTab) -> Unit, tabFocus: FocusRequester, leftEdge: (Int) -> Modifier,
    compact: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = OmniTheme.colors
    val chipHeight by androidx.compose.animation.core.animateDpAsState(if (compact) 22.dp else 34.dp, tween(180), label = "tabsHeight")
    Row(
        Modifier.fillMaxWidth().padding(top = if (compact) 2.dp else OmniSpacing.s, bottom = 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else OmniSpacing.s),
    ) {
        Text(
            "ANIME", color = c.accent, maxLines = 1,
            style = TextStyle(fontFamily = OmniTheme.type.family, fontWeight = FontWeight.Black, fontSize = if (compact) 13.sp else 22.sp, letterSpacing = if (compact) 2.sp else 3.sp),
            modifier = Modifier.padding(start = OmniSpacing.focusEdge, end = if (compact) OmniSpacing.s else OmniSpacing.m),
        )
        AnimeTab.entries.forEachIndexed { i, tab ->
            AnimeChip(tab.label, tab == current, (if (tab == current) Modifier.focusRequester(tabFocus) else Modifier).then(leftEdge(i)),
                height = chipHeight, fontSize = if (compact) 10.sp else 13.sp) { onSelect(tab) }
        }
        // Task 143: the Anime-only search, on every tab (it searches this page only).
        val openSearch = LocalAnimeSearchOpen.current
        AnimeChip("Search", false, height = chipHeight, fontSize = if (compact) 10.sp else 13.sp) { openSearch() }
        if (trailing != null) {
            Spacer(Modifier.width(if (compact) OmniSpacing.s else OmniSpacing.l))
            trailing()
        }
    }
}

/** A Crunchyroll-style chip: accent fill when selected, focus border from the shared card. */
@Composable
private fun AnimeChip(
    label: String, selected: Boolean, modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 36.dp, fontSize: androidx.compose.ui.unit.TextUnit = 13.sp, onClick: () -> Unit,
) {
    val c = OmniTheme.colors
    CrunchyrollCard(modifier.height(height), onClick = onClick, onLongClick = {}, onFocus = {}) {
        Box(Modifier.fillMaxHeight().background(if (selected) c.accent else Color.Transparent).padding(horizontal = if (fontSize < 12.sp) OmniSpacing.s else OmniSpacing.m), contentAlignment = Alignment.Center) {
            Text(label.uppercase(), maxLines = 1, color = if (selected) Color.Black else c.textSecondary, style = CrunchKicker(OmniTheme.type).copy(fontSize = fontSize))
        }
    }
}

/** A labelled chip row ("SORT  [Trending] [Top rated] …"), horizontally scrollable when long. */
@Composable
private fun <T> ChipRow(label: String, options: List<T>, selected: T, name: (T) -> String, leftEdge: (Int) -> Modifier, onPick: (T) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = CrunchKicker(OmniTheme.type).copy(fontSize = 12.sp, letterSpacing = 1.5.sp), color = OmniTheme.colors.textSecondary,
            modifier = Modifier.width(104.dp).padding(start = OmniSpacing.focusEdge))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(OmniSpacing.s), contentPadding = PaddingValues(vertical = OmniSpacing.xs, horizontal = OmniSpacing.xs)) {
            itemsIndexed(options, key = { _, o -> name(o) }) { i, o -> AnimeChip(name(o), o == selected, leftEdge(i)) { onPick(o) } }
        }
    }
}

/** The pinned header's banner height: the same in both modes, so nothing jumps when the tabs shrink. */
private val BannerHeight = 172.dp

/** What the pinned banner shows while the viewer is in the rows: the highlighted title or channel. */
private data class Pick(val row: PosterRow? = null, val channel: HubChannel? = null, val kicker: String, val shelfKey: String)

/**
 * A hub home page. [kind] null = the main Home (mixed kinds, live channel rows); SERIES / VOD = that
 * tab's own home, with a "Browse all" action to the filterable grid.
 *
 * Kory: the tabs + banner are PINNED above the rows. On the buttons the tabs are full size and the
 * banner is the featured carousel; once focus moves down into the rows the tab bar shrinks to a slim
 * strip and the banner becomes the highlighted title's full banner (art, logo, rating, synopsis).
 */
@Composable
private fun AnimeHome(
    state: AnimeHubState,
    vm: AnimeHubViewModel,
    kind: ContentKind?,
    featured: List<PosterRow>,
    shelves: List<AnimeShelf>,
    continueWatching: List<ContinuePosterUi>,
    firstFocus: FocusRequester,
    tabFocus: FocusRequester,
    leftEdge: (Int) -> Modifier,
    onOpen: (ContentKey) -> Unit,
    onPlay: (ContentKey, Boolean) -> Unit,
    onWatchChannel: (ContentKey, RemoteId) -> Unit,
    onOpenNavigation: () -> Unit,
) {
    var heroIndex by remember(kind) { mutableIntStateOf(0) }
    var inRows by remember(kind) { mutableStateOf(false) }
    var pick by remember(kind) { mutableStateOf<Pick?>(null) }
    var heroTick by remember { mutableIntStateOf(0) }
    val onPick: (Pick) -> Unit = { pick = it; inRows = true }
    val hero = featured.getOrNull(heroIndex.coerceIn(0, (featured.size - 1).coerceAtLeast(0)))
    val cinematic = LocalVisualTier.current == VisualTier.CINEMATIC
    val listState = rememberLazyListState()
    // Switching to the Series / Movies home lands on its tab chip (the page below is new).
    LaunchedEffect(kind) { if (kind != null) runCatching { tabFocus.requestFocus() } }
    // Up from the first row returns to the Play button once the buttons are composed again.
    LaunchedEffect(heroTick) { if (heroTick > 0) { delay(60); runCatching { firstFocus.requestFocus() } } }
    // The highlighted title's full detail (logo, backdrop, plot, genres): basic row at once, enriched after a beat.
    val artVersion by vm.artVersion.collectAsStateWithLifecycle(initialValue = 0L)
    val pickedRow = pick?.row
    val shown by produceState<PosterRow?>(pickedRow, pickedRow?.uid(), artVersion) {
        value = pickedRow
        if (pickedRow != null) { delay(150); value = vm.detailed(pickedRow) }
    }
    val channelPick = if (inRows) pick?.channel else null
    // Backdrop art only (a poster stretched across the banner looks wrong); channels use their TMDB key art.
    val backdrop: PosterRow? = when {
        !inRows -> hero
        channelPick != null -> PosterRow(channelPick.row.key, channelPick.row.name, null, null, null, backdropUrl = channelPick.art?.backdropUrl)
        else -> shown?.copy(posterUrl = null)
    }
    val names: suspend (PosterRow) -> List<String> = vm::titleNames
    val noun = if (kind == ContentKind.VOD) "movies" else "series"
    Box(Modifier.fillMaxSize()) {
        CrunchyrollHeroBackdrop(backdrop, cinematic, Modifier.align(Alignment.TopEnd).fillMaxWidth().height(BannerHeight + 70.dp))
        Column(Modifier.fillMaxSize().padding(horizontal = CrunchSide)) {
            // PINNED: never scrolls with the rows.
            Column(Modifier.onFocusChanged { if (it.hasFocus) inRows = false }) {
                AnimeTabs(state.tab, vm::selectTab, tabFocus, leftEdge, compact = inRows, trailing = if (kind == null) null else {
                    { AnimeChip("Browse all $noun & filters ▸", false, height = if (inRows) 22.dp else 34.dp, fontSize = if (inRows) 10.sp else 13.sp) { vm.openGrid() } }
                })
                if (channelPick != null) {
                    ChannelBanner(channelPick, pick?.kicker.orEmpty(), Modifier.fillMaxWidth().height(BannerHeight))
                } else {
                    AnimeHero(
                        row = if (inRows) shown else hero,
                        kicker = if (inRows) pick?.kicker else hero?.let { state.heroKickers[it.uid()] },
                        card = if (inRows) null else continueWatching.firstOrNull { it.poster.uid() == hero?.uid() },
                        interactive = !inRows,
                        featuredCount = featured.size, index = heroIndex, vm = vm,
                        onHeroPage = { d -> if (featured.size > 1) heroIndex = crunchyrollHeroStep(heroIndex, featured.size, d) },
                        onPlay = onPlay, onOpen = onOpen, firstFocus = firstFocus, onOpenNavigation = onOpenNavigation,
                        loaded = state.loaded,
                        modifier = Modifier.fillMaxWidth().height(BannerHeight),
                    )
                }
            }
            Box(
                Modifier.weight(1f).fillMaxWidth().onPreviewKeyEvent { e ->
                    // Up from the FIRST row goes back to the Play buttons (they are not composed while the banner previews).
                    if (inRows && e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp) {
                        val firstKey = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.takeIf { it.index == 0 }?.key
                        if (firstKey != null && pick?.shelfKey == firstKey) { inRows = false; heroTick++; true } else false
                    } else false
                },
            ) {
                FocusPivot(parentFraction = 0f, leading = 40.dp) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 48.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (continueWatching.isNotEmpty()) item(key = "cw") {
                            CrunchyrollShelf("Continue Watching", compact = true) {
                                itemsIndexed(continueWatching, key = { _, c -> "cw-" + c.poster.uid() }) { i, card ->
                                    CompactContinueCard(card, leftEdge(i), onFocus = { onPick(Pick(row = card.poster, kicker = "CONTINUE WATCHING", shelfKey = "cw")) }) { onOpen(card.poster.key) }
                                }
                            }
                        }
                        if (kind == null) {
                            // Trending, anime channels, the anime shelves, cartoon channels, then the cartoon shelves.
                            val toonKeys = setOf("cartoon-series", "animated-movies", "new-animated", "new-cartoons")
                            shelves.filter { it.key.startsWith("trend") }.forEach { shelf -> posterShelf(shelf, names, leftEdge, onPick, onOpen) }
                            if (state.animeChannels.isNotEmpty()) item(key = "anime-channels") {
                                ChannelShelf("Anime channels · Live", "anime-channels", state.animeChannels, leftEdge, onPick, onWatchChannel)
                            }
                            shelves.filter { !it.key.startsWith("trend") && it.key !in toonKeys }.forEach { shelf -> posterShelf(shelf, names, leftEdge, onPick, onOpen) }
                            if (state.cartoonChannels.isNotEmpty()) item(key = "cartoon-channels") {
                                ChannelShelf("Cartoon channels · Live", "cartoon-channels", state.cartoonChannels, leftEdge, onPick, onWatchChannel)
                            }
                            shelves.filter { it.key in toonKeys }.forEach { shelf -> posterShelf(shelf, names, leftEdge, onPick, onOpen) }
                        } else {
                            shelves.forEach { shelf -> posterShelf(shelf, names, leftEdge, onPick, onOpen) }
                        }
                        if (state.loaded && shelves.isEmpty() && (kind != null || state.channels.isEmpty())) item(key = "empty") {
                            Text("No anime or animated $noun found in your sources yet.", style = OmniTheme.type.body, color = OmniTheme.colors.textSecondary,
                                modifier = Modifier.padding(start = OmniSpacing.focusEdge))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Compact Continue Watching card (Kory: "shrink that row WAY down"): 16:9 art with the title, episode
 * and time left over a bottom scrim and the resume bar on its edge — no text block underneath.
 */
@Composable
private fun CompactContinueCard(card: ContinuePosterUi, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit) {
    val row = card.poster
    val c = OmniTheme.colors
    val art = crunchyrollCardArt(row)
    val episode = crunchyrollEpisodeLabel(card.episodeSeason, card.episodeNumber)
    val minutes = crunchyrollMinutesLeft(card.positionMs, card.durationMs)
    CrunchyrollCard(modifier.width(LocalCrunchCardWidths.current.second).aspectRatio(16f / 9f), onClick = onClick, onLongClick = {}, onFocus = onFocus) {
        when (art) {
            is CrunchArt.WithUrl -> AsyncImage(model = art.url, contentDescription = row.name, contentScale = ContentScale.Crop,
                colorFilter = com.yodesla.omniverse.designsystem.VibrantArt, modifier = Modifier.fillMaxSize())
            CrunchArt.None -> Box(Modifier.fillMaxSize().background(c.elevated))
        }
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))))) {
            Text(displayTitle(row.name, row.year), style = OmniTheme.type.body.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold), color = Color.White,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = OmniSpacing.m))
            listOfNotNull(episode, minutes).joinToString("  ·  ").takeIf { it.isNotEmpty() }?.let {
                Text(it, style = CrunchKicker(OmniTheme.type).copy(fontSize = 10.sp), color = Color.White.copy(alpha = 0.8f), maxLines = 1,
                    modifier = Modifier.padding(horizontal = 6.dp))
            }
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.25f))) {
                Box(Modifier.fillMaxWidth(card.progress.coerceIn(0.01f, 1f)).fillMaxHeight().background(c.accent))
            }
        }
    }
}

private fun LazyListScope.posterShelf(
    shelf: AnimeShelf,
    names: suspend (PosterRow) -> List<String>,
    leftEdge: (Int) -> Modifier,
    onPick: (Pick) -> Unit,
    onOpen: (ContentKey) -> Unit,
) {
    item(key = shelf.key) {
        CrunchyrollShelf(shelf.title, null, compact = true) { // no TMDB/date caption (Kory)
            itemsIndexed(shelf.rows, key = { _, r -> shelf.key + "-" + r.uid() }) { i, row ->
                // Kory: number what the library actually has 1, 2, 3… (chart order kept, no gaps for missing titles).
                val ranked = shelf.ranks.isNotEmpty()
                val kicker = if (ranked) "#${i + 1} ${shelf.title.uppercase()}" else shelf.title.uppercase()
                RankedPoster(row, if (ranked) i + 1 else null, names, leftEdge(i), { onPick(Pick(row = row, kicker = kicker, shelfKey = shelf.key)) }) { onOpen(row.key) }
            }
        }
    }
}

/** A poster with its position (1, 2, 3… in chart order among titles you have) in an accent corner badge; null = plain poster. */
@Composable
private fun RankedPoster(row: PosterRow, rank: Int?, names: suspend (PosterRow) -> List<String>, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit) {
    Box(modifier) {
        CrunchyrollPosterCard(row, names, Modifier, onFocus = onFocus, onClick = onClick, onMenu = {})
        if (rank != null) {
            Box(
                Modifier.align(Alignment.TopStart).padding(5.dp)
                    .background(OmniTheme.colors.accent, RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            ) {
                Text("#$rank", style = CrunchKicker(OmniTheme.type).copy(fontSize = 13.sp, fontWeight = FontWeight.Black), color = Color.Black, maxLines = 1)
            }
        }
    }
}

/**
 * The billboard, compact: kicker, logo/title, one meta line, a one-line synopsis, then either the
 * buttons and carousel dots ([interactive], the featured title) or a hint (previewing a highlighted
 * row title). Same fixed height either way.
 */
@Composable
private fun AnimeHero(
    row: PosterRow?,
    kicker: String?,
    card: ContinuePosterUi?,
    interactive: Boolean,
    featuredCount: Int,
    index: Int,
    vm: AnimeHubViewModel,
    onHeroPage: (Int) -> Unit,
    onPlay: (ContentKey, Boolean) -> Unit,
    onOpen: (ContentKey) -> Unit,
    firstFocus: FocusRequester,
    onOpenNavigation: () -> Unit,
    loaded: Boolean,
    modifier: Modifier,
) {
    val c = OmniTheme.colors
    val t = OmniTheme.type
    val rt by produceState<Pair<ContentKey, RtScores>?>(null, row?.key) {
        value = null
        val r = row ?: return@produceState
        delay(350)
        value = vm.rtScores(r)?.let { r.key to it }
    }
    Box(modifier, contentAlignment = Alignment.CenterStart) {
        Box(
            Modifier.matchParentSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawRect(Brush.horizontalGradient(0f to Color.Black, 0.45f to Color.Black.copy(alpha = 0.85f), 0.75f to Color.Transparent))
                    drawContent()
                    drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.18f to Color.Black, 0.6f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
                },
        )
        val r = row
        if (r == null) {
            if (!interactive) return@Box
            // Still loading (or nothing rated yet): keep one focus target so the page is reachable.
            Column(Modifier.padding(start = OmniSpacing.focusEdge, top = OmniSpacing.l, bottom = OmniSpacing.l), verticalArrangement = Arrangement.spacedBy(OmniSpacing.m)) {
                Text(if (loaded) "Everything animated, in one place" else "Gathering every anime and cartoon…",
                    style = TextStyle(fontFamily = t.family, fontWeight = FontWeight.Bold, fontSize = 30.sp), color = c.textPrimary)
                CrunchyrollPlayButton("Browse series", Modifier.focusRequester(firstFocus).onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) { onOpenNavigation(); true } else false
                }, height = 40.dp) { vm.selectTab(AnimeTab.SERIES) }
            }
            return@Box
        }
        val subDub = rememberSubDubLabel(r, vm::titleNames)
        val genres = crunchyrollGenres(r.genre)
        val saved by remember(r.uid()) { vm.isFavorite(r.key) }.collectAsStateWithLifecycle(initialValue = false)
        val progress = card?.progress ?: 0f
        Column(Modifier.padding(start = OmniSpacing.focusEdge, top = OmniSpacing.s, bottom = OmniSpacing.xs).widthIn(max = 780.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                kicker ?: (if (r.key.kind == ContentKind.SERIES) "TOP RATED ANIME SERIES" else "TOP RATED ANIME MOVIE"),
                style = CrunchKicker(t).copy(fontSize = 12.sp, letterSpacing = 2.sp), color = c.accent, maxLines = 1,
            )
            if (!r.logoUrl.isNullOrBlank()) {
                AsyncImage(model = r.logoUrl, contentDescription = r.name, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart,
                    modifier = Modifier.height(44.dp).widthIn(max = 300.dp))
            } else {
                Text(displayTitle(r.name, r.year), maxLines = 1, overflow = TextOverflow.Ellipsis, color = c.textPrimary,
                    style = TextStyle(fontFamily = t.family, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 34.sp, letterSpacing = (-0.8).sp))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OmniSpacing.s)) {
                ageRatingLabel(r.certification, r.name)?.let { CrunchyrollAgeBox(it) }
                subDub?.let { Text(it.uppercase(), style = CrunchKicker(t).copy(fontSize = 12.sp), color = c.textSecondary, maxLines = 1) }
                if (genres.isNotEmpty()) Text(genres.joinToString(", "), modifier = Modifier.weight(1f, fill = false), style = t.body.copy(fontSize = 14.sp), color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                r.year?.let { Text(it.toString(), style = t.body.copy(fontSize = 14.sp), color = c.textSecondary) }
                r.rating?.takeIf { it > 0f }?.let { Text("★ ${"%.1f".format(it)}", style = t.body.copy(fontSize = 14.sp), color = c.accent) }
                crunchyrollHeroScores(r.key, rt?.first, rt?.second)?.let { RtBadges(it) }
            }
            r.plot?.let { Text(it, style = t.body.copy(fontSize = 13.sp, lineHeight = 17.sp), color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            if (!interactive) {
                Box(Modifier.height(40.dp), contentAlignment = Alignment.CenterStart) {
                    Text("OK opens details   ·   Up from the first row returns to Play / My List", style = CrunchKicker(t).copy(fontSize = 11.sp, letterSpacing = 0.8.sp), color = c.textSecondary.copy(alpha = 0.8f), maxLines = 1)
                }
                return@Column
            }
            var playFocused by remember { mutableStateOf(false) }
            var bookmarkFocused by remember { mutableStateOf(false) }
            Row(
                horizontalArrangement = Arrangement.spacedBy(OmniSpacing.s),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.onPreviewKeyEvent { e ->
                    when {
                        e.type != KeyEventType.KeyDown -> false
                        e.key == Key.DirectionRight && bookmarkFocused -> { onHeroPage(1); true }
                        // Left on the first button pages back; at the first title it opens the rail.
                        e.key == Key.DirectionLeft && playFocused -> { if (index == 0 || featuredCount < 2) onOpenNavigation() else onHeroPage(-1); true }
                        else -> false
                    }
                },
            ) {
                Box(Modifier.onFocusChanged { playFocused = it.hasFocus }) {
                    CrunchyrollPlayButton(
                        if (progress > 0f) "▶ Continue watching" else "▶ Start watching" + (if (r.key.kind == ContentKind.SERIES) " S1 E1" else ""),
                        Modifier.focusRequester(firstFocus), height = 40.dp,
                    ) { onPlay(r.key, progress <= 0f) }
                }
                CrunchyrollPlayButton("More info", height = 40.dp) { onOpen(r.key) }
                Box(Modifier.onFocusChanged { bookmarkFocused = it.hasFocus }) {
                    CrunchyrollBookmarkButton(saved, if (saved) "Remove from My List" else "Add to My List", size = 40.dp) { vm.setFavorite(r.key, !saved) }
                }
                Spacer(Modifier.width(OmniSpacing.m))
                CrunchyrollCarouselDots(featuredCount, index, Modifier)
            }
        }
    }
}

/** The pinned banner while a live channel tile is highlighted: its logo, name and what is on now. */
@Composable
private fun ChannelBanner(ch: HubChannel, kicker: String, modifier: Modifier) {
    val c = OmniTheme.colors
    val t = OmniTheme.type
    Box(modifier, contentAlignment = Alignment.CenterStart) {
        Box(
            Modifier.matchParentSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawRect(Brush.horizontalGradient(0f to Color.Black, 0.45f to Color.Black.copy(alpha = 0.85f), 0.75f to Color.Transparent))
                    drawContent()
                    drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.18f to Color.Black, 0.6f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
                },
        )
        val logo = ch.art?.logoUrl ?: ch.row.logoUrl
        var logoFailed by remember(logo) { mutableStateOf(false) }
        Column(Modifier.padding(start = OmniSpacing.focusEdge, top = OmniSpacing.s, bottom = OmniSpacing.xs).widthIn(max = 780.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OmniSpacing.s)) {
                Box(Modifier.background(Color(0xFFE50914), RoundedCornerShape(3.dp)).padding(horizontal = 6.dp, vertical = 1.dp)) {
                    Text("● LIVE", style = CrunchKicker(t).copy(fontSize = 11.sp), color = Color.White)
                }
                Text(kicker, style = CrunchKicker(t).copy(fontSize = 12.sp, letterSpacing = 2.sp), color = c.accent, maxLines = 1)
            }
            if (!logo.isNullOrBlank() && !logoFailed) {
                AsyncImage(model = logo, contentDescription = ch.row.name, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart, onError = { logoFailed = true },
                    modifier = Modifier.height(44.dp).widthIn(max = 300.dp))
            } else {
                Text(AnimeHubFeeds.channelCoreName(ch.row.name).ifBlank { ch.row.name }, maxLines = 1, overflow = TextOverflow.Ellipsis, color = c.textPrimary,
                    style = TextStyle(fontFamily = t.family, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 34.sp, letterSpacing = (-0.8).sp))
            }
            Text(ch.row.name, style = t.body.copy(fontSize = 14.sp), color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(ch.nowTitle?.let { "On now: $it" } ?: "No guide data for this channel", style = t.body.copy(fontSize = 13.sp, lineHeight = 17.sp), color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box(Modifier.height(40.dp), contentAlignment = Alignment.CenterStart) {
                Text("OK watches live   ·   Up from the first row returns to Play / My List", style = CrunchKicker(t).copy(fontSize = 11.sp, letterSpacing = 0.8.sp), color = c.textSecondary.copy(alpha = 0.8f), maxLines = 1)
            }
        }
    }
}

/** A live channel row on Home: premium 16:9 tiles; focus previews the channel in the pinned banner. */
@Composable
private fun ChannelShelf(title: String, shelfKey: String, channels: List<HubChannel>, leftEdge: (Int) -> Modifier, onPick: (Pick) -> Unit, onWatch: (ContentKey, RemoteId) -> Unit) {
    CrunchyrollShelf(title, "Logos & art: TMDB · Now playing: your guide", compact = true) {
        itemsIndexed(channels, key = { _, ch -> "ch-" + ch.row.uidKey() }) { i, ch ->
            ChannelTile(ch, leftEdge(i).width(LocalCrunchCardWidths.current.second * 1.25f),
                onFocus = { onPick(Pick(channel = ch, kicker = title.uppercase(), shelfKey = shelfKey)) }) { onWatch(ch.row.key, ch.row.categoryId) }
        }
    }
}

/** Vivid fallback gradients for channels without TMDB key art, picked stably from the name. */
private val ChannelGradients = listOf(
    listOf(Color(0xFFFF6A00), Color(0xFFEE0979)), listOf(Color(0xFF7F00FF), Color(0xFFE100FF)),
    listOf(Color(0xFF00C6FF), Color(0xFF0072FF)), listOf(Color(0xFFF7971E), Color(0xFFFFD200)),
    listOf(Color(0xFF11998E), Color(0xFF38EF7D)), listOf(Color(0xFFFC466B), Color(0xFF3F5EFB)),
    listOf(Color(0xFFFF512F), Color(0xFFDD2476)), listOf(Color(0xFF4776E6), Color(0xFF8E54E9)),
)

/**
 * Premium channel tile: the show's TMDB key art (or a vivid gradient), its colour TMDB logo (else the
 * provider's), a LIVE pill, and the guide's current programme with a progress bar along the bottom.
 */
@Composable
private fun ChannelTile(ch: HubChannel, modifier: Modifier, onFocus: () -> Unit = {}, onClick: () -> Unit) {
    val c = OmniTheme.colors
    val art = ch.art
    val gradient = ChannelGradients[Math.floorMod(channelNameKey(ch.row.name).hashCode(), ChannelGradients.size)]
    Column(modifier) {
        CrunchyrollCard(Modifier.fillMaxWidth().aspectRatio(16f / 9f), onClick = onClick, onLongClick = {}, onFocus = onFocus) {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(gradient)))
            val backdrop = art?.backdropUrl
            if (!backdrop.isNullOrBlank()) {
                AsyncImage(model = backdrop, contentDescription = null, contentScale = ContentScale.Crop, colorFilter = com.yodesla.omniverse.designsystem.VibrantArt, modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.15f), Color.Black.copy(alpha = 0.55f)))))
            } else {
                // Depth on the gradient so a white provider logo still reads.
                Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f)))))
            }
            val logo = art?.logoUrl ?: ch.row.logoUrl
            var logoFailed by remember(logo) { mutableStateOf(false) }
            if (!logo.isNullOrBlank() && !logoFailed) {
                AsyncImage(model = logo, contentDescription = ch.row.name, contentScale = ContentScale.Fit, onError = { logoFailed = true },
                    modifier = Modifier.fillMaxSize().padding(horizontal = OmniSpacing.xl, vertical = OmniSpacing.l + OmniSpacing.s))
            } else {
                Text(AnimeHubFeeds.channelCoreName(ch.row.name).ifBlank { ch.row.name }, style = OmniTheme.type.title.copy(fontWeight = FontWeight.Black, fontSize = 22.sp),
                    color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.Center).padding(OmniSpacing.m))
            }
            Box(Modifier.align(Alignment.TopStart).padding(OmniSpacing.s).background(Color(0xFFE50914), RoundedCornerShape(3.dp)).padding(horizontal = 6.dp, vertical = 1.dp)) {
                Text("● LIVE", style = CrunchKicker(OmniTheme.type).copy(fontSize = 11.sp), color = Color.White)
            }
            ch.nowTitle?.let { now ->
                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))) {
                    Text(now, style = OmniTheme.type.body.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = OmniSpacing.s, end = OmniSpacing.s, top = OmniSpacing.m, bottom = 4.dp))
                    Box(Modifier.fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.25f))) {
                        Box(Modifier.fillMaxWidth((ch.nowProgress ?: 0f).coerceIn(0.01f, 1f)).fillMaxHeight().background(c.accent))
                    }
                }
            }
        }
        Spacer(Modifier.height(OmniSpacing.s))
        Text(ch.row.name, style = OmniTheme.type.body.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Series / Movies: every anime and animated title of the kind, with sort, type, year and genre filters. */
@Composable
private fun AnimeGrid(state: AnimeHubState, vm: AnimeHubViewModel, tabFocus: FocusRequester, leftEdge: (Int) -> Modifier, onOpen: (ContentKey) -> Unit) {
    val c = OmniTheme.colors
    val f = state.gridFilter
    // Collapsed by default (Kory): one summary row; OK on it opens the full chip rows.
    var filtersOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.tab) { runCatching { tabFocus.requestFocus() } }
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = CrunchSide)) {
        val columns = 7
        val gap = OmniSpacing.m
        val cell = (maxWidth - OmniSpacing.focusEdge * 2 - gap * (columns - 1)) / columns
        CompositionLocalProvider(LocalCrunchCardWidths provides (cell to cell * 1.8f)) {
            FocusPivot(parentFraction = 0.3f, leading = 0.dp) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    contentPadding = PaddingValues(start = OmniSpacing.focusEdge, end = OmniSpacing.focusEdge, bottom = 64.dp),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                    verticalArrangement = Arrangement.spacedBy(OmniSpacing.l),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item(key = "tabs", span = { GridItemSpan(maxLineSpan) }) { AnimeTabs(state.tab, vm::selectTab, tabFocus, leftEdge, trailing = {
                        AnimeChip("◂ ${if (state.tab == AnimeTab.MOVIES) "Movies" else "Series"} home", false, height = 34.dp) { vm.closeGrid() }
                    }) }
                    item(key = "filters", span = { GridItemSpan(maxLineSpan) }) {
                        Column(verticalArrangement = Arrangement.spacedBy(OmniSpacing.s)) {
                            FiltersToggle(filtersOpen, listOfNotNull(f.sort.label, f.type.label, f.decade.label, f.genre ?: "All genres").joinToString("  ·  "), leftEdge) { filtersOpen = !filtersOpen }
                            if (filtersOpen) {
                            ChipRow("SORT", AnimeGridSort.entries, f.sort, { it.label }, leftEdge) { vm.setGridFilter(f.copy(sort = it)) }
                            ChipRow("TYPE", AnimeTypeFilter.entries, f.type, { it.label }, leftEdge) { vm.setGridFilter(f.copy(type = it)) }
                            ChipRow("YEAR", AnimeDecade.entries, f.decade, { it.label }, leftEdge) { vm.setGridFilter(f.copy(decade = it)) }
                            if (state.gridGenres.isNotEmpty()) ChipRow("GENRE", listOf<String?>(null) + state.gridGenres, f.genre, { it ?: "All genres" }, leftEdge) { vm.setGridFilter(f.copy(genre = it)) }
                            }
                            val what = if (state.tab == AnimeTab.MOVIES) "movies" else "series"
                            Text(
                                when {
                                    state.gridLoading -> "Loading…"
                                    f.sort == AnimeGridSort.TRENDING && state.grid.isEmpty() -> "None of TMDB's trending animation $what match right now. Try Top rated."
                                    f.sort == AnimeGridSort.TRENDING -> "${state.grid.size} $what from TMDB's trending chart this week (Animation) · in TMDB chart order"
                                    else -> "${state.grid.size} $what · anime & animation from every source"
                                },
                                style = OmniTheme.type.body, color = c.textSecondary, maxLines = 1,
                                modifier = Modifier.padding(start = OmniSpacing.focusEdge, top = OmniSpacing.xs),
                            )
                        }
                    }
                    gridItemsIndexed(state.grid, key = { _, r -> "g-" + r.uid() }) { i, row ->
                        val rank = if (f.sort == AnimeGridSort.TRENDING) i + 1 else null // gapless: 1, 2, 3… in chart order
                        RankedPoster(row, rank, vm::titleNames, leftEdge(i % columns), {}) { onOpen(row.key) }
                    }
                }
            }
        }
    }
}

/** Live channels: filter by kind and service, sort by featured / on now / name; premium tiles. */
@Composable
private fun AnimeChannelsGrid(state: AnimeHubState, vm: AnimeHubViewModel, tabFocus: FocusRequester, leftEdge: (Int) -> Modifier, onWatch: (ContentKey, RemoteId) -> Unit) {
    val c = OmniTheme.colors
    LaunchedEffect(state.tab) { runCatching { tabFocus.requestFocus() } }
    val columns = 4
    val shown = state.shownChannels
    var filtersOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        contentPadding = PaddingValues(start = CrunchSide + OmniSpacing.focusEdge, end = CrunchSide + OmniSpacing.focusEdge, bottom = 64.dp),
        horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m),
        verticalArrangement = Arrangement.spacedBy(OmniSpacing.l),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "tabs", span = { GridItemSpan(maxLineSpan) }) { AnimeTabs(state.tab, vm::selectTab, tabFocus, leftEdge) }
        item(key = "filters", span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(OmniSpacing.s)) {
                FiltersToggle(filtersOpen, listOf(state.channelKind.label, state.channelService ?: "All services", state.channelSort.label).joinToString("  ·  "), leftEdge) { filtersOpen = !filtersOpen }
                if (filtersOpen) {
                ChipRow("SHOW", ChannelKindFilter.entries, state.channelKind, { it.label }, leftEdge) { vm.setChannelKind(it) }
                ChipRow("SERVICE", listOf<String?>(null) + state.services, state.channelService, { it ?: "All services" }, leftEdge) { vm.setChannelService(it) }
                ChipRow("SORT", ChannelSort.entries, state.channelSort, { it.label }, leftEdge) { vm.setChannelSort(it) }
                }
                Text("${shown.size} channels · ${shown.count { it.nowTitle != null }} with guide data on now",
                    style = OmniTheme.type.body, color = c.textSecondary, modifier = Modifier.padding(start = OmniSpacing.focusEdge, top = OmniSpacing.xs))
            }
        }
        gridItemsIndexed(shown, key = { _, ch -> "c-" + ch.row.uidKey() }) { i, ch ->
            ChannelTile(ch, leftEdge(i % columns)) { onWatch(ch.row.key, ch.row.categoryId) }
        }
        if (shown.isEmpty()) item(key = "none", span = { GridItemSpan(maxLineSpan) }) {
            Text("No channels match these filters.", style = OmniTheme.type.body, color = c.textSecondary)
        }
    }
}

/** The collapsible filter header: "FILTERS ▸" plus the active choices; OK toggles the chip rows. */
@Composable
private fun FiltersToggle(open: Boolean, summary: String, leftEdge: (Int) -> Modifier, onToggle: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m), modifier = Modifier.padding(start = OmniSpacing.focusEdge)) {
        AnimeChip(if (open) "Filters ▾" else "Filters ▸", open, leftEdge(0), onClick = onToggle)
        Text(summary, style = OmniTheme.type.body, color = OmniTheme.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Task 143: opens the Anime-only search (provided by the route; AnimeTabs shows the chip on every tab). */
internal val LocalAnimeSearchOpen = androidx.compose.runtime.staticCompositionLocalOf<() -> Unit> { {} }

/**
 * Task 143 (Kory: "a search within the anime section that only searches the anime section"): searches
 * only what the Anime page holds. The tab you are on sets the scope — Home / Series / Movies / Live
 * channels — and the field says so. The spinner runs from the first keystroke until results land.
 */
@Composable
private fun AnimeSearch(
    state: AnimeHubState, vm: AnimeHubViewModel, tabFocus: FocusRequester, leftEdge: (Int) -> Modifier,
    onOpen: (ContentKey) -> Unit, onWatch: (ContentKey, RemoteId) -> Unit,
) {
    val c = OmniTheme.colors
    val field = remember { FocusRequester() }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    LaunchedEffect(Unit) { delay(80); runCatching { field.requestFocus() } }
    val scopeLabel = when (state.tab) {
        AnimeTab.HOME -> "all of Anime"
        AnimeTab.SERIES -> "anime series"
        AnimeTab.MOVIES -> "anime movies"
        AnimeTab.CHANNELS -> "anime & cartoon channels"
    }
    val showSeries = state.tab == AnimeTab.HOME || state.tab == AnimeTab.SERIES
    val showMovies = state.tab == AnimeTab.HOME || state.tab == AnimeTab.MOVIES
    val showChannels = state.tab == AnimeTab.HOME || state.tab == AnimeTab.CHANNELS
    val series = if (showSeries) state.searchSeries else emptyList()
    val movies = if (showMovies) state.searchMovies else emptyList()
    val channels = if (showChannels) state.searchChannels else emptyList()
    val total = series.size + movies.size + channels.size
    val names: suspend (PosterRow) -> List<String> = vm::titleNames
    Box(Modifier.fillMaxSize().padding(horizontal = CrunchSide)) {
        FocusPivot(parentFraction = 0f, leading = 40.dp) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(OmniSpacing.s)) {
                item(key = "tabs") {
                    AnimeTabs(state.tab, vm::selectTab, tabFocus, leftEdge, trailing = { AnimeChip("✕ Close search", false, height = 34.dp) { vm.closeSearch() } })
                }
                item(key = "field") {
                    Column {
                        androidx.compose.material3.OutlinedTextField(
                            value = state.searchQuery,
                            onValueChange = vm::onSearchQuery,
                            singleLine = true,
                            placeholder = { Text("Search " + scopeLabel + " only", style = OmniTheme.type.body, color = c.textTertiary) },
                            textStyle = OmniTheme.type.title.copy(color = c.textPrimary),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                            trailingIcon = { if (state.searchBusy) androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp, color = c.accent) },
                            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = c.accent, unfocusedBorderColor = c.elevated, cursorColor = c.accent,
                                focusedContainerColor = c.surface, unfocusedContainerColor = c.surface,
                            ),
                            modifier = Modifier.padding(start = OmniSpacing.focusEdge, top = OmniSpacing.s).widthIn(max = 760.dp).fillMaxWidth().focusRequester(field)
                                .onPreviewKeyEvent { e ->
                                    if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown) { focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down); true }
                                    else if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp) { runCatching { tabFocus.requestFocus() }; true }
                                    else false
                                },
                        )
                        if (state.searchBusy) androidx.compose.material3.LinearProgressIndicator(
                            modifier = Modifier.padding(start = OmniSpacing.focusEdge, top = OmniSpacing.xs).widthIn(max = 760.dp).fillMaxWidth().height(3.dp),
                            color = c.accent, trackColor = c.elevated,
                        )
                        Text(
                            when {
                                state.searchQuery.isBlank() -> "Searches only the Anime page, never the rest of your library."
                                state.searchBusy -> "Searching " + scopeLabel + "…"
                                total == 0 -> "Nothing in " + scopeLabel + " matches “" + state.searchQuery.trim() + "”."
                                else -> "$total in $scopeLabel"
                            },
                            style = OmniTheme.type.body, color = c.textSecondary, maxLines = 1,
                            modifier = Modifier.padding(start = OmniSpacing.focusEdge, top = OmniSpacing.xs),
                        )
                    }
                }
                if (series.isNotEmpty()) item(key = "r-series") {
                    CrunchyrollShelf("Series · ${series.size}", compact = true) {
                        itemsIndexed(series, key = { _, r -> "s-" + r.uid() }) { i, row -> RankedPoster(row, null, names, leftEdge(i), {}) { onOpen(row.key) } }
                    }
                }
                if (movies.isNotEmpty()) item(key = "r-movies") {
                    CrunchyrollShelf("Movies · ${movies.size}", compact = true) {
                        itemsIndexed(movies, key = { _, r -> "m-" + r.uid() }) { i, row -> RankedPoster(row, null, names, leftEdge(i), {}) { onOpen(row.key) } }
                    }
                }
                if (channels.isNotEmpty()) item(key = "r-channels") {
                    CrunchyrollShelf("Live channels · ${channels.size}", compact = true) {
                        itemsIndexed(channels, key = { _, ch -> "c-" + ch.row.uidKey() }) { i, ch ->
                            ChannelTile(ch, leftEdge(i).width(LocalCrunchCardWidths.current.second * 1.25f)) { onWatch(ch.row.key, ch.row.categoryId) }
                        }
                    }
                }
            }
        }
    }
}
