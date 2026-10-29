package com.yodesla.omniverse.feature.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.yodesla.omniverse.core.data.*
import com.yodesla.omniverse.core.data.metadata.TmdbEnricher
import com.yodesla.omniverse.core.data.sports.*
import com.yodesla.omniverse.core.model.*
import com.yodesla.omniverse.designsystem.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** What a For You pick is: decides its tile, what OK does, and what the hero says about it. */
enum class PickKind { TITLE, RESUME, CHANNEL, GAME, STORY }

/**
 * One focusable thing on For You and everything the hero, the feature card and the tiles say about
 * it. [card] carries what opening it does plus title, poster and backdrop; the rest is presentation.
 */
@Immutable
data class ForYouPick(
    val card: HomeCard,
    val kind: PickKind,
    /** Why it is here: "Because you watched Shōgun", "Pick up where you left off". */
    val reason: String,
    /** "Movie  ·  2003  ·  Drama  ·  R  ·  2h 34m  ·  7.7/10" */
    val meta: String? = null,
    val plot: String? = null,
    /** TMDB title logo, drawn in place of the title text when known. */
    val logoUrl: String? = null,
    /** On air right now: a favourite channel's programme, a followed team's game in progress. */
    val live: Boolean = false,
    /** A followed team's game none of this viewer's channels carry: OK opens Live Sports. */
    val opensSports: Boolean = false,
    /** The channel a game is on (its hold-OK channel menu is titled with it). */
    val channel: String? = null,
    val story: InterestStory? = null,
)

enum class TileStyle { NOW, POSTER, CHANNEL, GAME, STORY }

@Immutable
data class ForYouShelf(
    val id: String,
    /** Row title (Cinematic) and section headline (Magazine): "Because you watched The Dark Knight". */
    val title: String,
    /** Magazine kicker over the headline: "MORE LIKE THIS". */
    val kicker: String,
    val picks: List<ForYouPick>,
    val caption: String? = null,
)

@Immutable
data class ForYouState(
    val layout: String = ForYouLayout.CINEMATIC,
    /** The saved layout has loaded: until then nothing is drawn, so the page never opens in the wrong one. */
    val layoutKnown: Boolean = false,
    /** Top personal picks: Cinematic's first poster row, the Magazine's feature and shortlist. */
    val featured: List<ForYouPick> = emptyList(),
    /** Resume, followed teams playing now, what is on the favourite channels. */
    val rightNow: List<ForYouPick> = emptyList(),
    /** Because-you-watched rows, search picks, fresh arrivals. */
    val shelves: List<ForYouShelf> = emptyList(),
    val myList: List<ForYouPick> = emptyList(),
    val channels: List<ForYouPick> = emptyList(),
    val games: List<ForYouPick> = emptyList(),
    val teams: List<FollowedTeam> = emptyList(),
    val stories: List<ForYouPick> = emptyList(),
    /** At least one headline matches what this viewer watches, saves, searches or follows. */
    val storiesPersonal: Boolean = false,
    val newsEnabled: Boolean = true,
    val newsLoading: Boolean = false,
    /** True until the fast part (right now, saves, teams) is ready. */
    val loading: Boolean = true,
    /** True while the recommendations are still being worked out. */
    val picksLoading: Boolean = true,
    /** Picks come from this viewer's history and saves (false: fresh arrivals stand in). */
    val personal: Boolean = true,
)

@OptIn(ExperimentalCoroutinesApi::class)
class ForYouViewModel(
    private val catalog: CatalogRepository,
    private val user: UserDataRepository,
    private val search: SearchRepository,
    visibility: Flow<Visibility>,
    follows: FollowedTeamsStore,
    private val myTeams: MyTeamsRepository,
    excluded: Flow<Collection<String>>,
    private val allowNews: Boolean = true,
    /** What is on the favourite channels now (null: channel tiles show no programme). */
    private val epg: EpgRepository? = null,
    private val clock: Clock = Clock { System.currentTimeMillis() },
    /** Landscape art + title logos for the hero and the feature card (null: source art only). */
    private val tmdb: TmdbEnricher? = null,
    private val sources: SourceRepository? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(ForYouState())
    val state: StateFlow<ForYouState> = _state.asStateFlow()

    private val _focused = MutableStateFlow<ForYouPick?>(null)
    /** The pick the Cinematic hero describes. Only the hero reads it: focus moves never rebuild rows. */
    val focused: StateFlow<ForYouPick?> = _focused.asStateFlow()

    private val rawStories = MutableStateFlow<List<InterestStory>>(emptyList())
    @Volatile private var interests: List<String> = emptyList()

    private data class Input(
        val favorites: List<ContentKey>, val watching: List<Progress>, val saved: List<Progress>,
        val visible: Visibility, val history: String?, val teams: List<FollowedTeam>, val excluded: Collection<String>,
    )

    init {
        user.setting(ForYouLayout.KEY).onEach { v -> _state.update { it.copy(layout = ForYouLayout.parse(v), layoutKnown = true) } }.launchIn(viewModelScope)
        val newsOn = user.setting("for_you_news").map { allowNews && it != "false" }.distinctUntilChanged()
        viewModelScope.launch {
            newsOn.collectLatest { enabled ->
                _state.update { it.copy(newsEnabled = enabled, newsLoading = enabled && it.stories.isEmpty()) }
                rawStories.value = if (enabled) ForYouNews.fetch() else emptyList()
                _state.update { it.copy(newsLoading = false) }
            }
        }
        // Headlines are ranked against the latest interests without rebuilding any row.
        rawStories.onEach { publishStories() }.launchIn(viewModelScope)
        val progress = combine(user.continueWatching(24), user.savedProgress(60)) { w, s -> w to s }
        val input = combine(user.favorites(), progress, visibility, user.setting(SEARCH_HISTORY_KEY), follows.teams) { f, p, v, h, t ->
            Input(f, p.first, p.second, v, h, t, emptyList())
        }.combine(excluded) { i, e -> i.copy(excluded = e) }
        viewModelScope.launch { input.collectLatest { build(it) } }
    }

    private suspend fun build(i: Input) {
        val nowMs = clock.nowMs()
        // 1. The fast part (what is on now, what the viewer saved) shows before any recommendation query.
        val core = withContext(Dispatchers.Default) { buildCore(i, nowMs) }
        _state.update {
            it.copy(loading = false, rightNow = core.rightNow, myList = core.myList, channels = core.channels,
                games = core.games, teams = i.teams)
        }
        // Headlines rank against what this viewer actually has: titles and teams, never genres.
        interests = core.titles + i.teams.map { it.name }
        publishStories()
        // 2. Recommendations: titles like the latest watches and saves, then search history. Each
        // because-row is published as it lands, so the first picks show long before the last.
        buildPicks(i, core)
    }

    private class Core(
        val rightNow: List<ForYouPick>, val myList: List<ForYouPick>, val channels: List<ForYouPick>,
        val games: List<ForYouPick>, val seen: Set<ContentKey>, val posters: Map<ContentKey, PosterRow>,
        /** Title groups the viewer already has (watching, watched, saved): never recommended back. */
        val names: Set<String>,
        /** Their display titles, for ranking headlines. */
        val titles: List<String>,
    )

    private fun ok(i: Input, p: PosterRow) = p.categoryId?.let { i.visible(p.key.kind.name, p.key.sourceId.value, it.value) } ?: true

    private suspend fun buildCore(i: Input, nowMs: Long): Core {
        val watchedKeys = i.saved.mapNotNull { progressPosterKey(it.key, it.parentId) }
        val posterKeys = i.watching.mapNotNull { progressPosterKey(it.key, it.parentId) } + watchedKeys +
            i.favorites.filter { it.kind == ContentKind.VOD || it.kind == ContentKind.SERIES }
        val posters = catalog.posters(posterKeys.distinct())
        // Resume: newest first, one card per title (the same grouping as Home's Continue watching).
        val resume = i.watching.sortedByDescending { it.updatedMs }.mapNotNull { p ->
            val poster = progressPosterKey(p.key, p.parentId)?.let { posters[it] }?.takeIf { ok(i, it) } ?: return@mapNotNull null
            titlePick(poster, PickKind.RESUME, "Pick up where you left off", "cw-${p.key.sourceId.value}-${p.key.kind.name}-${p.key.remoteId.value}",
                subtitle = timeLeftLabel(p.key.kind == ContentKind.EPISODE, p.positionMs, p.durationMs),
                progress = p.durationMs?.takeIf { it > 0 }?.let { d -> (p.positionMs.toFloat() / d).coerceIn(0f, 1f) })
        }.distinctBy { it.card.titleIdentity ?: it.card.open }.distinctBy { cwNameGroup(it.card.open.kind, it.card.title) }.take(6)

        // Favourite channels and what they are showing now (guide keys are per provider).
        val channelRows = i.favorites.filter { it.kind == ContentKind.LIVE }.mapNotNull { catalog.channel(it) }
            .filter { i.visible("LIVE", it.key.sourceId.value, it.categoryId.value) }
        val guide = if (epg == null) emptyMap() else channelRows.groupBy { it.key.sourceId }
            .mapValues { (src, chs) -> epg.nowNext(src, chs.mapNotNull { it.epgKey }, nowMs) }
        val channels = channelRows.map { ch ->
            val nn = ch.epgKey?.let { guide[ch.key.sourceId]?.get(it) }
            val now = nn?.now?.takeIf { it.startMs <= nowMs && nowMs < it.endMs }
            ForYouPick(
                card = HomeCard("ch-${ch.key.sourceId.value}-${ch.key.remoteId.value}", ch.key, ch.name, now?.title ?: "On air now",
                    ch.logoUrl, isChannel = true, categoryId = ch.categoryId, section = "Your channels",
                    progress = now?.let { ((nowMs - it.startMs).toFloat() / (it.endMs - it.startMs).coerceAtLeast(1)).coerceIn(0f, 1f) }),
                kind = PickKind.CHANNEL, reason = "Live on ${ch.name}",
                meta = now?.let { n ->
                    "${ClockFormat.format(n.startMs)} – ${ClockFormat.format(n.endMs)}" + (nn?.next?.let { "  ·  Next: ${it.title}" } ?: "")
                },
                plot = now?.description?.takeIf { it.isNotBlank() }, live = now != null,
            )
        }

        // Followed teams: live games first, then the next kick-offs.
        val items = myTeams.items { a -> i.visible("LIVE", a.channel.sourceId.value, a.categoryId.value) }
        val games = items.map { g ->
            val option = g.options.firstOrNull()
            val live = g.state == GameState.LIVE
            ForYouPick(
                card = HomeCard("game-${g.id}", option?.key ?: ContentKey(SourceId("sports"), ContentKind.LIVE, RemoteId(g.id)), g.title,
                    listOfNotNull(if (live) "Live now" else kickoffLabel(g.startMs, nowMs), option?.name?.let { "on $it" }).joinToString("  ·  "),
                    g.imageUrl, isChannel = true, categoryId = option?.categoryId, section = g.league),
                kind = PickKind.GAME, reason = "${g.followed.name}  ·  ${g.league}",
                meta = g.detail.takeIf { it.isNotBlank() }, live = live, opensSports = option == null, channel = option?.name,
            ) to g
        }
        val soon = games.filter { (_, g) -> g.state != GameState.LIVE && g.startMs - nowMs in 0..12 * 3_600_000L }.map { it.first }

        val myList = i.favorites.filter { it.kind == ContentKind.VOD || it.kind == ContentKind.SERIES }
            .mapNotNull { k -> posters[k]?.takeIf { ok(i, it) } }.distinctBy(::titleGroup)
            .map { titlePick(it, PickKind.TITLE, "In your list", "list-${it.key.sourceId.value}-${it.key.kind.name}-${it.key.remoteId.value}") }

        val rightNow = (games.filter { it.first.live }.map { it.first } + resume.take(3) +
            channels.filter { it.live }.take(3) + soon.take(1) + resume.drop(3)).distinctBy { it.card.id }.take(8)
        val owned = (watchedKeys + i.favorites).mapNotNull { posters[it] }
        return Core(rightNow, myList, channels, games.map { it.first },
            (i.favorites + watchedKeys + resume.map { it.card.open }).toSet(), posters,
            (owned.map { nameGroup(it) } + owned.map(::titleGroup) + resume.map { cwNameGroup(it.card.open.kind, it.card.title) }).toSet(),
            (resume.map { it.card.title } + owned.map { displayTitle(it.name, it.year) }).distinct())
    }

    private fun nameGroup(p: PosterRow) = cwNameGroup(p.key.kind, displayTitle(p.name, p.year))

    private class Picks(val featured: List<ForYouPick>, val shelves: List<ForYouShelf>, val personal: Boolean)
    private class SeedRow(val key: ContentKey, val name: String, val saved: Boolean, val picks: List<ForYouPick>)

    private suspend fun buildPicks(i: Input, core: Core): Unit = coroutineScope {
        val seen = core.seen.toMutableSet()
        val groups = core.names.toMutableSet()
        val titles = setOf(ContentKind.VOD, ContentKind.SERIES)
        // One copy per title across sources and rows, and only titles that have a poster to show.
        fun claim(p: PosterRow): Boolean {
            if (p.key.kind !in titles || p.posterUrl.isNullOrBlank() || !ok(i, p) || p.key in seen) return false
            val name = nameGroup(p); val id = titleGroup(p)
            if (name in groups || id in groups) return false
            seen += p.key; groups += name; groups += id
            return true
        }
        val watched = i.saved.sortedByDescending { it.updatedMs }.mapNotNull { progressPosterKey(it.key, it.parentId) }.distinct()
        // Seeds: the latest watches first, then saves; each becomes its own "Because you…" row.
        val seeds = (watched.take(3).map { it to false } + i.favorites.filter { it.kind in titles }.take(3).map { it to true })
            .distinctBy { it.first }.take(4)
        // The genre scans run side by side; rows are still claimed in seed order, so de-duplication is stable.
        val similar = seeds.map { (key, _) -> async(Dispatchers.Default) { catalog.similarTo(key, 16) } }
        val rows = mutableListOf<SeedRow>()
        for ((n, seed) in seeds.withIndex()) {
            val (key, saved) = seed
            val found = similar[n].await()
            val p = (core.posters[key] ?: withContext(Dispatchers.Default) { catalog.poster(key) })?.takeIf { ok(i, it) } ?: continue
            val name = displayTitle(p.name, p.year)
            val reason = if (saved) "Because you saved $name" else "Because you watched $name"
            val picks = found.filter(::claim)
                .map { titlePick(it, PickKind.TITLE, reason, "rec-${key.remoteId.value}-${it.key.sourceId.value}-${it.key.remoteId.value}") }
            if (picks.isNotEmpty()) {
                rows += SeedRow(key, name, saved, picks)
                // Rows stream in only on the first build. Any settings write re-runs this (the settings
                // flows re-query), and streaming then would visibly reshuffle rows already on screen.
                if (_state.value.picksLoading) publishPicks(assemble(rows, emptyList()), done = false)
            }
        }
        val history = i.history.orEmpty().lineSequence().map { it.trim() }.filter { it.length >= 3 }.distinct().take(3).toList()
        val searched = mutableListOf<ForYouPick>()
        for (q in history) {
            // History keeps what was typed ("dark kn"), so the reason never quotes it.
            val found = withContext(Dispatchers.Default) { search.searchFiltered(q, SearchFilters.None, i.excluded, 8).values.flatMap { it.rows } }
            for (c in found) if (claim(c)) searched += titlePick(c, PickKind.TITLE, "From your searches", "q-${c.key.sourceId.value}-${c.key.remoteId.value}")
        }
        val picks = assemble(rows, searched)
        if (picks.featured.isNotEmpty()) {
            publishPicks(picks, done = true)
            return@coroutineScope
        }
        // Nothing to learn from yet: the newest arrivals stand in, movies and shows interleaved.
        val movies = withContext(Dispatchers.Default) { catalog.recentlyAdded(ContentKind.VOD, 16, i.excluded).first() }
        val shows = withContext(Dispatchers.Default) { catalog.recentlyAdded(ContentKind.SERIES, 16, i.excluded).first() }
        val fresh = (0 until maxOf(movies.size, shows.size)).flatMap { n -> listOfNotNull(movies.getOrNull(n), shows.getOrNull(n)) }
            .filter(::claim)
            .map { titlePick(it, PickKind.TITLE, "New on your sources", "new-${it.key.sourceId.value}-${it.key.kind.name}-${it.key.remoteId.value}") }
        val more = if (fresh.size > 8) listOf(ForYouShelf("fresh", "Fresh arrivals", "JUST ADDED", fresh.drop(8))) else emptyList()
        publishPicks(Picks(fresh.take(8), picks.shelves + more, personal = false), done = true)
    }

    /** Featured: round-robin across the because-rows so no single title dominates; the rest become rows. */
    private fun assemble(rows: List<SeedRow>, searched: List<ForYouPick>): Picks {
        // Search results are looser matches: they only lead when nothing watched or saved has rows.
        val lanes = rows.map { it.picks }.ifEmpty { listOf(searched) }
        val featured = mutableListOf<ForYouPick>()
        var depth = 0
        while (featured.size < 10 && lanes.any { it.size > depth }) {
            lanes.forEach { lane -> lane.getOrNull(depth)?.let { if (featured.size < 10) featured += it } }
            depth++
        }
        val taken = featured.map { it.card.open }.toSet()
        val shelves = mutableListOf<ForYouShelf>()
        for (row in rows) {
            val rest = row.picks.filter { it.card.open !in taken }
            if (rest.size >= 4) shelves += ForYouShelf("because-${row.key.sourceId.value}-${row.key.remoteId.value}",
                (if (row.saved) "Because you saved " else "Because you watched ") + row.name,
                if (row.saved) "FROM YOUR LIST" else "MORE LIKE THIS", rest)
        }
        val restSearched = searched.filter { it.card.open !in taken }
        if (restSearched.size >= 4) shelves += ForYouShelf("search", "From your searches", "YOU LOOKED FOR", restSearched)
        return Picks(featured, shelves, personal = true)
    }

    private var artWarm: Job? = null

    private fun publishPicks(p: Picks, done: Boolean) {
        _state.update { it.copy(featured = p.featured.map(::dressed), shelves = p.shelves, personal = p.personal, picksLoading = !done) }
        if (artWarm?.isActive != true) artWarm = viewModelScope.launch { warmArt() }
    }

    /** A pick wearing whatever art is already known for it, so a re-published row never loses its backdrop. */
    private fun dressed(pick: ForYouPick) = artCache[pick.card.open]?.let { pick.withArt(it) } ?: pick

    private fun titlePick(p: PosterRow, kind: PickKind, reason: String, id: String, subtitle: String? = null, progress: Float? = null) = ForYouPick(
        card = HomeCard(id, p.key, displayTitle(p.name, p.year), subtitle ?: p.year?.toString(), p.posterUrl, progress = progress,
            categoryId = p.categoryId, section = reason, titleIdentity = p.titleIdentity(), backdropUrl = p.backdropUrl),
        kind = kind, reason = reason, meta = titleMeta(p), plot = p.plot?.takeIf { it.isNotBlank() }, logoUrl = p.logoUrl,
    )

    private fun publishStories() {
        val words = interests
        // Only a match in the headline itself (3+) moves a story up; a stray word in a summary does not.
        fun score(s: InterestStory) = ForYouNews.relevance(s, words).let { if (it >= 3) it.coerceAtMost(6) else 0 }
        val ranked = rawStories.value.sortedWith(
            compareByDescending<InterestStory> { score(it) }.thenByDescending { it.publishedMs ?: 0L },
        ).take(14)
        val nowMs = clock.nowMs()
        _state.update { s ->
            s.copy(stories = ranked.map { storyPick(it, nowMs) }, storiesPersonal = ranked.any { score(it) > 0 })
        }
    }

    private fun storyPick(story: InterestStory, nowMs: Long) = ForYouPick(
        card = HomeCard("story-${story.url}", ContentKey(SourceId("news"), ContentKind.VOD, RemoteId(story.url)), story.title,
            listOfNotNull(story.section.takeIf { it.isNotBlank() }, ForYouNews.agoLabel(story.publishedMs, nowMs)).joinToString("  ·  "),
            story.imageUrl),
        kind = PickKind.STORY, reason = "The Guardian", plot = story.summary.takeIf { it.isNotBlank() }, story = story,
    )

    // ---- Art: TMDB backdrop + title logo for the focused pick (the hero) and the first picks (warm).

    private val artCache = LinkedHashMap<ContentKey, TitleArt>()
    private var artJob: Job? = null

    private suspend fun art(card: HomeCard): TitleArt {
        artCache[card.open]?.let { return it }
        val a = resolveTitleArt(card, sources, tmdb)
        if (artCache.size >= 96) artCache.remove(artCache.keys.first())
        artCache[card.open] = a
        return a
    }

    private fun ForYouPick.withArt(a: TitleArt) = copy(
        card = card.copy(backdropUrl = a.backdrop),
        logoUrl = logoUrl ?: a.logo,
        plot = plot ?: a.overview?.takeIf { it.isNotBlank() },
    )

    /** Dress the first three featured picks (the feature card and the first heroes), whatever they are by now. */
    private suspend fun warmArt() {
        if (sources == null && tmdb == null) return
        while (true) {
            val next = _state.value.featured.take(3).firstOrNull { it.card.open !in artCache } ?: return
            val a = art(next.card)
            _state.update { s -> s.copy(featured = s.featured.map { if (it.card.open == next.card.open) it.withArt(a) else it }) }
        }
    }

    fun onFocus(pick: ForYouPick) {
        if (_focused.value?.card?.id == pick.card.id) return
        _focused.value = pick
        artJob?.cancel()
        if (pick.kind != PickKind.TITLE && pick.kind != PickKind.RESUME) return
        if (sources == null && tmdb == null) return
        artJob = viewModelScope.launch {
            delay(250)
            val a = art(pick.card)
            if (_focused.value?.card?.id == pick.card.id) _focused.value = pick.withArt(a)
        }
    }

    fun setLayout(layout: String) { viewModelScope.launch { user.putSetting(ForYouLayout.KEY, ForYouLayout.parse(layout)) } }
    fun toggleNews() { viewModelScope.launch { user.putSetting("for_you_news", (!_state.value.newsEnabled).toString()) } }
    fun isFavorite(key: ContentKey): Flow<Boolean> = user.isFavorite(key)
    fun isQueued(key: ContentKey): Flow<Boolean> = user.isQueued(key)
    fun toggleMyList(key: ContentKey, inList: Boolean) { viewModelScope.launch { user.setFavorite(key, !inList) } }
    fun togglePlayNext(key: ContentKey, queued: Boolean) { viewModelScope.launch { user.setQueued(key, !queued) } }
}

/** "Movie  ·  2003  ·  Drama  ·  R  ·  2h 34m  ·  7.7/10": only what the catalog actually knows. */
internal fun titleMeta(p: PosterRow): String = listOfNotNull(
    if (p.key.kind == ContentKind.SERIES) "Series" else "Movie",
    p.year?.toString(),
    p.genre?.split(',', '/', '|', ';')?.map { it.trim() }?.firstOrNull { it.isNotEmpty() },
    p.certification?.takeIf { it.isNotBlank() },
    p.runtimeMin?.takeIf { it > 0 && p.key.kind != ContentKind.SERIES }?.let { if (it >= 60) "%dh %02dm".format(it / 60, it % 60) else "$it min" },
    p.rating?.takeIf { it.isFinite() && it > 0f && it <= 10f }?.let { "%.1f/10".format(it) },
).joinToString("  ·  ")

/** Everything a For You tile can do, so both layouts wire their tiles identically. */
@Stable
internal class ForYouActions(
    val onPick: (ForYouPick) -> Unit,
    val onHold: (ForYouPick) -> Unit,
    val onFocus: (ForYouPick) -> Unit,
    /** Left from the first tile of a row opens the main menu. */
    val onMenu: () -> Unit,
    val onSports: () -> Unit,
    val onLayout: (String) -> Unit,
    val onToggleNews: () -> Unit,
)

@Composable
fun ForYouRoute(vm: ForYouViewModel, name: String?, onOpen: (HomeCard) -> Unit, onMenu: () -> Unit,
    onMovies: () -> Unit, onShows: () -> Unit, onSports: () -> Unit, allowNews: Boolean = true,
    /** Resume (false) or start over (true): the same Detail auto-play Home's hold menu uses. */
    onPlay: (ContentKey, Boolean) -> Unit = { key, _ -> onOpen(HomeCard(key.remoteId.value, key, "", null, null)) }) {
    val s by vm.state.collectAsStateWithLifecycle()
    var article by remember { mutableStateOf<InterestStory?>(null) }
    var menu by remember { mutableStateOf<ForYouPick?>(null) }
    // A layout picked with the page's own switch keeps focus on that switch after the cross-fade.
    var fromSwitch by remember { mutableStateOf(false) }
    val actions = remember(vm) {
        ForYouActions(
            onPick = { pick ->
                when {
                    pick.kind == PickKind.STORY -> article = pick.story
                    pick.kind == PickKind.RESUME -> onPlay(pick.card.open, false)
                    pick.opensSports -> onSports()
                    else -> onOpen(pick.card)
                }
            },
            onHold = { pick -> if (pick.kind == PickKind.TITLE || pick.kind == PickKind.RESUME) menu = pick },
            onFocus = vm::onFocus,
            onMenu = onMenu,
            onSports = onSports,
            onLayout = { layout -> fromSwitch = true; vm.setLayout(layout) },
            onToggleNews = vm::toggleNews,
        )
    }
    if (!s.layoutKnown) {
        CosmicBackdrop(Modifier.fillMaxSize()) {}
    } else Crossfade(s.layout, animationSpec = tween(OmniMotion.SCREEN_FADE_MS), label = "forYouLayout") { layout ->
        if (layout == ForYouLayout.MAGAZINE) ForYouMagazine(s, name, actions, fromSwitch, allowNews)
        else ForYouCinematic(vm, s, name, actions, fromSwitch, allowNews)
    }
    menu?.let { pick ->
        val fav by remember(pick.card.open) { vm.isFavorite(pick.card.open) }.collectAsState(initial = false)
        val queued by remember(pick.card.open) { vm.isQueued(pick.card.open) }.collectAsState(initial = false)
        TitleMenu(
            title = pick.card.title, subtitle = pick.reason, posterUrl = pick.card.image,
            actions = titleMenuActions(
                resumeLabel = if (pick.kind == PickKind.RESUME) "Resume" else null,
                onResume = { onPlay(pick.card.open, false) }, onStartOver = { onPlay(pick.card.open, true) },
                onDetails = { onOpen(pick.card) }, inMyList = fav, onToggleMyList = { vm.toggleMyList(pick.card.open, fav) },
                inQueue = queued, onToggleQueue = { vm.togglePlayNext(pick.card.open, queued) }, onRemoveContinue = null,
            ),
            onDismiss = { menu = null },
        )
    }
    article?.let { story -> StoryDialog(story, onDismiss = { article = null }) }
}

@Composable
private fun StoryDialog(story: InterestStory, onDismiss: () -> Unit) {
    val c = OmniTheme.colors; val t = OmniTheme.type
    val uri = LocalUriHandler.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Row(Modifier.widthIn(max = 760.dp).glassSurface().padding(OmniSpacing.xl), horizontalArrangement = Arrangement.spacedBy(OmniSpacing.xl)) {
            story.imageUrl?.let {
                AsyncImage(it, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.width(280.dp).aspectRatio(16f / 10f).clip(RoundedCornerShape(OmniSpacing.cardCorner)).background(c.surface))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(OmniSpacing.m)) {
                Text(listOf("The Guardian", story.section).filter { it.isNotBlank() }.joinToString("  ·  ").uppercase(), style = t.overline, color = c.accent)
                Text(story.title, style = t.headline, color = c.textPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(story.summary, style = t.body, color = c.textSecondary, maxLines = 6, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m)) {
                    OmniButton("Read article", { runCatching { uri.openUri(story.url) } }, primary = true)
                    OmniButton("Close", onDismiss)
                }
            }
        }
    }
}
