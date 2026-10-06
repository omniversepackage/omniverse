package com.yodesla.omniverse.feature.home
import com.yodesla.omniverse.designsystem.requestFocusWhenReady

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.yodesla.omniverse.core.data.CatalogRepository
import com.yodesla.omniverse.core.data.Clock
import com.yodesla.omniverse.core.data.EpgRepository
import com.yodesla.omniverse.core.data.UserDataRepository
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.progressPosterKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.designsystem.FocusCard
import com.yodesla.omniverse.designsystem.CosmicBackdrop
import com.yodesla.omniverse.designsystem.FocusPivot
import com.yodesla.omniverse.designsystem.LocalCompact
import com.yodesla.omniverse.designsystem.LogoImage
import com.yodesla.omniverse.designsystem.OmniButton
import com.yodesla.omniverse.designsystem.OmniMotion
import com.yodesla.omniverse.designsystem.OmniSpacing
import com.yodesla.omniverse.designsystem.OmniTheme
import com.yodesla.omniverse.designsystem.PosterCard
import com.yodesla.omniverse.designsystem.PosterFocus
import com.yodesla.omniverse.designsystem.PosterPrefetch
import com.yodesla.omniverse.designsystem.ProgressLine
import com.yodesla.omniverse.designsystem.displayTitle
import com.yodesla.omniverse.designsystem.rememberTimeText
import com.yodesla.omniverse.designsystem.touchClick
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.Flow
import com.yodesla.omniverse.core.data.PosterRow
import com.yodesla.omniverse.core.data.SmartCollectionFilter
import com.yodesla.omniverse.core.data.TitleIdentity
import com.yodesla.omniverse.core.data.ShowEverything
import com.yodesla.omniverse.core.data.Visibility
import com.yodesla.omniverse.core.data.NextEpisodeFinder
import com.yodesla.omniverse.core.data.NewEpisodes
import com.yodesla.omniverse.core.data.NewEpisodesNotice
import com.yodesla.omniverse.core.data.PickForMeOffer
import com.yodesla.omniverse.core.data.PickForMeSession
import com.yodesla.omniverse.core.data.newEpisodesLabel
import com.yodesla.omniverse.core.data.watchedShowKeys
import com.yodesla.omniverse.core.data.SourceAlert
import com.yodesla.omniverse.core.data.SourceHealthKeys
import com.yodesla.omniverse.core.data.sports.GameState
import com.yodesla.omniverse.core.data.sports.MyTeamsRepository

@Immutable
data class HomeCard(
    val id: String,
    /** What opening the card does: VOD/SERIES → detail, LIVE → play. */
    val open: ContentKey,
    val title: String,
    val subtitle: String?,
    val image: String?,
    val progress: Float? = null,
    val isChannel: Boolean = false,
    val categoryId: RemoteId? = null,
    /** Row it sits in, shown as the hero's overline ("RECENTLY ADDED MOVIES"). */
    val section: String = "",
    val titleIdentity: TitleIdentity? = null,
    /** Episode a show-detail open should be ready on (task 71 "New episodes"). */
    val focusEpisode: RemoteId? = null,
)

@Immutable
data class HomeRow(val id: String, val title: String, val cards: List<HomeCard>)

@Immutable
data class HomeState(
    val rows: List<HomeRow> = emptyList(),
    val hero: HomeCard? = null,
    val loaded: Boolean = false,
    /** Compact poster density (task 65): ~15% smaller posters on Home rows only. */
    val compact: Boolean = false,
    /** Every row type in the viewer's current order, for the Customize Home panel. */
    val panel: List<HomePanelEntry> = emptyList(),
    /** Task 105: a source expiring soon or repeatedly failing; null when every source is healthy. */
    val alert: SourceAlert? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val catalog: CatalogRepository,
    private val epg: EpgRepository,
    private val userData: UserDataRepository,
    private val clock: Clock,
    private val visibility: Flow<Visibility> = ShowEverything,
    /** Task 57 "Up next": next episode of a show whose latest watch is a completed episode (null = off). */
    private val upNext: NextEpisodeFinder? = null,
    /** Task 84d: warms the TMDB art cache for the rows about to appear (null or keyless build = off). */
    private val tmdb: com.yodesla.omniverse.core.data.metadata.TmdbEnricher? = null,
    /** Task 105: the weekly source-health banner (null = no health wiring). */
    private val sourceAlert: Flow<SourceAlert?>? = null,
    /** Task 103: the profile's followed teams against their own guides (null = no "My teams" row). */
    private val myTeams: MyTeamsRepository? = null,
    /**
     * Task 101: where the rows are actually built. Building them means a poster lookup per card plus
     * the layout maths, and on a cold start that used to run on the main thread inside
     * `viewModelScope` — i.e. it cost frames. Everything upstream of [flowOn] below runs here instead,
     * and only the finished rows are published to [state] on the main thread.
     */
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    // Task 90: the last removal that can still be undone, shown by the bottom-centre undo card.
    private val _undo = MutableStateFlow<com.yodesla.omniverse.core.data.UndoUi?>(null)
    val undo: StateFlow<com.yodesla.omniverse.core.data.UndoUi?> = _undo.asStateFlow()

    // Task 96: "Surprise me" — one unwatched pick per press; this session never repeats one.
    private val pickSession = PickForMeSession(catalog, userData)
    private val _pick = MutableStateFlow<PickForMeOffer?>(null)
    val pick: StateFlow<PickForMeOffer?> = _pick.asStateFlow()

    /** Pick (or re-pick for "Another") from both kinds, this viewer's exclusions and visibility. */
    fun surpriseMe() {
        viewModelScope.launch {
            val hidden = userData.hiddenCategoryKeys().first()
            _pick.value = pickSession.next(listOf(ContentKind.VOD, ContentKind.SERIES), hidden, visibility.first())
        }
    }

    fun dismissPick() { _pick.value = null }

    /** Task 69: "Hold OK for more options" hint — visible for the first three launches only. */
    val hintVisible: Flow<Boolean> = userData.setting(HINT_KEY).map { (it?.toIntOrNull() ?: 0) < 3 }

    @Volatile private var hintCounted = false

    /** Called when the hint actually shows. This VM lives exactly one app launch, so the flag makes the count per-launch. */
    fun hintShown() {
        if (hintCounted) return
        hintCounted = true
        viewModelScope.launch {
            val n = userData.setting(HINT_KEY).first()?.toIntOrNull() ?: 0
            if (n < 3) userData.putSetting(HINT_KEY, (n + 1).toString())
        }
    }

    /** Task 84d: batch-warm TMDB art for the titles on the rows about to appear (off = no request). */
    private fun prefetchArt(rows: List<HomeRow>) {
        val t = tmdb ?: return
        val ids = rows.flatMap { it.cards }.mapNotNull { card ->
            card.titleIdentity?.takeIf { it.namespace == "tmdb" }?.let { it.kind to it.externalId }
        }
        if (ids.isEmpty()) return
        val movies = ids.filter { it.first == ContentKind.VOD }.map { it.second }.distinct()
        val series = ids.filter { it.first == ContentKind.SERIES }.map { it.second }.distinct()
        viewModelScope.launch {
            if (movies.isNotEmpty()) t.prefetch(com.yodesla.omniverse.core.data.metadata.WikidataMetadata.Kind.MOVIE, movies)
            if (series.isNotEmpty()) t.prefetch(com.yodesla.omniverse.core.data.metadata.WikidataMetadata.Kind.SERIES, series)
        }
    }

    private companion object {
        const val HINT_KEY = "hint_hold_ok_seen"
    }

    init {
        // Task 105: the health store already filters out what this profile dismissed, so the state
        // simply mirrors the flow — null means healthy or already dismissed.
        sourceAlert?.onEach { alert -> _state.update { it.copy(alert = alert) } }?.launchIn(viewModelScope)
        // Task 57: a show whose latest progress is a completed episode rolls on to an "Up next"
        // card instead of vanishing; only shows with activity in the last 30 days qualify.
        val sinceMs = clock.nowMs() - 30L * 86_400_000L
        // savedProgress (task 63, M11): the full saved-progress list (finished watches included),
        // used only for recommendation exclusion — the CW row itself stays the 20-item presentation.
        // Task 89: the deduped CW source (one row per exact title, newest visible copy) shared with
        // Movies CW and the Netflix row; hidden categories decide which copy represents a title.
        val cwInput = combine(
            // Task 89 follow-up: the SQL-deduped source returned rows Home could not group (16 cards with 3x Armageddon on the TV);
            // Home keeps its own title-identity grouping on the raw list until that query is fixed.
            userData.continueWatching(40),
            userData.completedShows(sinceMs),
            userData.savedProgress(200),
        ) { progress, done, saved -> Triple(progress, done, saved) }
        val recentMovies = userData.hiddenCategoryKeys().flatMapLatest { keys -> catalog.recentlyAdded(ContentKind.VOD, 30, keys) }
        val recentShows = userData.hiddenCategoryKeys().flatMapLatest { keys -> catalog.recentlyAdded(ContentKind.SERIES, 30, keys) }
        // Task 95: spoiler-free mode (Settings › Playback, per profile) — an "Up next" card must not
        // spoil which episode comes next while protection is on.
        val cwWithSpoiler = cwInput.combine(userData.setting(UserDataRepository.SPOILER_FREE).map { com.yodesla.omniverse.core.data.SpoilerFree.enabled(it) }) { cw, spoilerOn -> cw to spoilerOn }
        // Task 97: My List and the Play next queue ride in together; the queue keeps viewer order.
        val favsAndQueue = userData.favorites().combine(userData.playNext()) { favs, queue -> favs to queue }
        // Task 103: "My teams" — the teams this profile follows, against its own guides. Guide-only on
        // purpose: no internet needed, and every card is a channel this viewer can actually tune to.
        val myTeamsRow: Flow<HomeRow?> = if (myTeams == null) flowOf(null) else visibility.flatMapLatest { vis ->
            flow { emit(myTeams.items { a -> vis("LIVE", a.channel.sourceId.value, a.categoryId.value) }) }
        }.map { items ->
            val cards = items.mapNotNull { item ->
                val o = item.options.firstOrNull() ?: return@mapNotNull null
                HomeCard(
                    id = "mt-${item.id}", open = o.key, categoryId = o.categoryId, title = item.title,
                    subtitle = (if (item.state == GameState.LIVE) "Live now" else "Kick-off") + "  ·  " + o.name,
                     image = item.imageUrl, section = "My teams", isChannel = true,
                )
            }
            if (cards.isEmpty()) null else HomeRow("myTeams", "My teams", cards)
        }
        combine(
            cwWithSpoiler,
            favsAndQueue,
            recentMovies,
            recentShows,
            visibility,
        ) { (cw, spoilerOn), (allFavs, queue), movies, shows, vis ->
            val (progress, doneShows, savedProgress) = cw
            val favs = allFavs.filter { it.kind == ContentKind.LIVE }
            fun ok(p: PosterRow) = p.categoryId?.let { vis(p.key.kind.name, p.key.sourceId.value, it.value) } ?: true
            val rows = ArrayList<HomeRow>()
            val watching = progress.mapNotNull { p ->
                val posterKey = progressPosterKey(p.key, p.parentId)
                val poster = posterKey?.let { catalog.poster(it) }?.takeIf { ok(it) } ?: return@mapNotNull null
                p.updatedMs to HomeCard(
                    id = "cw-${p.key.sourceId.value}-${p.key.kind.name}-${p.key.remoteId.value}", open = poster.key, title = displayTitle(poster.name, poster.year),
                    subtitle = timeLeftLabel(p.key.kind == ContentKind.EPISODE, p.positionMs, p.durationMs),
                    image = poster.posterUrl, progress = p.durationMs?.let { d -> p.positionMs.toFloat() / d },
                    titleIdentity = poster.titleIdentity(),
                )
            }
            val upNextCards = doneShows.mapNotNull { cs ->
                val ep = upNext?.nextAfter(cs.seriesKey, cs.lastEpisodeKey.remoteId) ?: return@mapNotNull null
                val poster = catalog.poster(cs.seriesKey)?.takeIf { ok(it) } ?: return@mapNotNull null
                cs.updatedMs to HomeCard(
                    id = "upnext-${poster.key.sourceId.value}-${poster.key.remoteId.value}", open = poster.key,
                    title = displayTitle(poster.name, poster.year),
                    subtitle = com.yodesla.omniverse.core.data.SpoilerFree.upNextSubtitle(ep.season, ep.number, spoilerOn),
                    image = poster.posterUrl, titleIdentity = poster.titleIdentity(),
                )
            }
            val cont = (watching + upNextCards).sortedByDescending { it.first }.map { it.second }
                .distinctBy { it.titleIdentity ?: it.open }.distinctBy { cwNameGroup(it.open.kind, it.title) } // the most recently watched copy of a title wins
            if (cont.isNotEmpty()) rows += HomeRow("continue", "Continue watching", cont.map { it.copy(section = "Continue watching") })
            // Task 97: Play next — the viewer's own queue, in their own order, right after Continue watching.
            val queued = queue.mapNotNull { k -> catalog.poster(k)?.takeIf(::ok) }.distinctBy(::titleGroup)
            if (queued.isNotEmpty()) rows += HomeRow("playnext", "Play next", queued.map {
                HomeCard("pn-${it.key.sourceId.value}-${it.key.kind.name}-${it.key.remoteId.value}", it.key, displayTitle(it.name, it.year), it.year?.toString(), it.posterUrl, section = "Play next", titleIdentity = it.titleIdentity())
            })
            // Task 71 "New episodes": shows this profile watches (progress in the last 60 days, or
            // in My List) whose post-sync snapshot flagged an episode it has not seen. The notices
            // are written by NewEpisodesDetector after each completed sync; this row only renders
            // them, dropping one once its episode has progress or once it is 14 days old.
            val newEps = watchedShowKeys(savedProgress, allFavs, clock.nowMs() - NewEpisodes.WATCH_WINDOW_MS, NewEpisodes.MAX_SHOWS_PER_SYNC)
                .mapNotNull { show ->
                    val notice = NewEpisodesNotice.decode(userData.setting(NewEpisodes.noticeKey(show.sourceId.value, show.remoteId.value)).first())
                        ?: return@mapNotNull null
                    if (clock.nowMs() - notice.detectedMs > NewEpisodes.NOTICE_TTL_MS) return@mapNotNull null
                    if (userData.progress(ContentKey(show.sourceId, ContentKind.EPISODE, RemoteId(notice.episodeId))) != null) return@mapNotNull null
                    val poster = catalog.poster(show)?.takeIf(::ok) ?: return@mapNotNull null
                    notice.detectedMs to HomeCard(
                        id = "newep-${show.sourceId.value}-${show.remoteId.value}", open = poster.key,
                        title = displayTitle(poster.name, poster.year),
                        subtitle = newEpisodesLabel(notice.count, notice.season, notice.number),
                        image = poster.posterUrl, section = "New episodes", titleIdentity = poster.titleIdentity(),
                        focusEpisode = RemoteId(notice.episodeId),
                    )
                }.sortedByDescending { it.first }.map { it.second }.distinctBy { it.titleIdentity ?: it.open }.take(30)
            if (newEps.isNotEmpty()) rows += HomeRow("newEpisodes", "New episodes", newEps)
            val channels = favs.mapNotNull { catalog.channel(it) }.filter { vis("LIVE", it.key.sourceId.value, it.categoryId.value) }
            if (channels.isNotEmpty()) {
                // Guide keys are per provider: look up now/next per source.
                val nowMs = clock.nowMs()
                val nnBySource = channels.groupBy { it.key.sourceId }.mapValues { (src, chs) -> epg.nowNext(src, chs.mapNotNull { it.epgKey }, nowMs) }
                rows += HomeRow("fav", "Favorite channels", channels.map { ch ->
                    HomeCard(
                        id = "ch-${ch.key.sourceId.value}-${ch.key.remoteId.value}", open = ch.key, title = ch.name,
                        subtitle = ch.epgKey?.let { nnBySource[ch.key.sourceId]?.get(it)?.now?.title }, image = ch.logoUrl, isChannel = true, categoryId = ch.categoryId,
                        section = "Favorite channels",
                    )
                })
            }
            // My List: movies and shows the viewer saved, one card per title across sources.
            val saved = allFavs.filter { it.kind == ContentKind.VOD || it.kind == ContentKind.SERIES }
                .mapNotNull { k -> catalog.poster(k)?.takeIf(::ok) }.distinctBy(::titleGroup)
            if (saved.isNotEmpty()) rows += HomeRow("mylist", "My List", saved.map {
                HomeCard("l-${it.key.sourceId.value}-${it.key.kind.name}-${it.key.remoteId.value}", it.key, displayTitle(it.name, it.year), it.year?.toString(), it.posterUrl, section = "My List", titleIdentity = it.titleIdentity())
            })
            // "Because you watched": up to 2 rows seeded by the 2 most recent distinct Continue Watching
            // titles (episodes seed their parent show). Skip rows with fewer than 4 results; never show
            // the seed or anything else the viewer already has saved progress on — exclusion uses the
            // full saved-progress list (task 63, M11), not the 20-item CW row, so completed movies and
            // older progress are excluded too.
            val watchedKeys = savedProgress.flatMap { p -> listOfNotNull(p.key, progressPosterKey(p.key, p.parentId)) }.toSet()
            val watchedGroups = (cont.map { it.open } + watchedKeys).mapNotNull { k -> catalog.poster(k)?.let(::titleGroup) }.toSet()
            val byw = ArrayList<HomeRow>()
            cont.take(2).forEach { seed ->
                val seedGroup = seed.titleIdentity?.let { "${seed.open.kind}:tmdb:${it.externalId}" } ?: "${seed.open.kind}:key:${seed.open.sourceId.value}:${seed.open.remoteId.value}"
                val rowTitle = "Because you watched ${seed.title}"
                val cards = catalog.similarTo(seed.open, 60) /* over-fetch: watched titles are filtered out */.asSequence().filter(::ok).distinctBy(::titleGroup)
                    .filter { p -> titleGroup(p) != seedGroup && titleGroup(p) !in watchedGroups }
                    .take(20).map { p ->
                        HomeCard("byw-${seed.open.kind}-${seed.open.sourceId.value}-${seed.open.remoteId.value}-${p.key.sourceId.value}-${p.key.remoteId.value}",
                            p.key, displayTitle(p.name, p.year), p.year?.toString(), p.posterUrl,
                            section = rowTitle, titleIdentity = p.titleIdentity())
                    }.toList()
                if (cards.size >= 4) byw += HomeRow("byw-${seed.open.kind}-${seed.open.sourceId.value}-${seed.open.remoteId.value}", rowTitle, cards)
            }
            if (byw.isNotEmpty()) {
                val insertAt = rows.indexOfFirst { it.id == "mylist" }.takeIf { it >= 0 }?.plus(1)
                    ?: rows.indexOfFirst { it.id == "continue" }.let { if (it < 0) 0 else it + 1 }
                rows.addAll(insertAt, byw)
            }
            if (movies.any(::ok)) rows += HomeRow("movies", "Recently added movies", movies.filter(::ok).distinctBy(::titleGroup).map {
                HomeCard("m-${it.key.sourceId.value}-${it.key.remoteId.value}", it.key, displayTitle(it.name, it.year), it.year?.toString(), it.posterUrl, section = "Recently added movies", titleIdentity = it.titleIdentity())
            })
            if (shows.any(::ok)) rows += HomeRow("shows", "Recently added shows", shows.filter(::ok).distinctBy(::titleGroup).map {
                HomeCard("s-${it.key.sourceId.value}-${it.key.remoteId.value}", it.key, displayTitle(it.name, it.year), it.year?.toString(), it.posterUrl, section = "Recently added shows", titleIdentity = it.titleIdentity())
            })
            rows
        }.onEach { rows -> prefetchArt(rows) }
        .combine(userData.collections()) { rows, collections ->
            rows to collections
        }.combine(userData.itemOverrides().combine(userData.titleOverrides()) { items, titles -> items to titles }) { (rows, collections), custom ->
            Triple(rows, collections, custom)
        }.combine(visibility) { (rows, collections, custom), vis ->
            val (overrides, titleOverrides) = custom
            val pinned = collections.filter { it.pinnedHome }.mapNotNull { collection ->
                val posters = if (collection.mode == "smart") {
                    SmartCollectionFilter.decode(collection.smartFilterJson)?.let { catalog.smartCollectionItems(collection.contentKind, it, 200) }.orEmpty()
                } else {
                    val concrete = userData.collectionItems(collection.id).mapNotNull { key ->
                        if (key.kind == collection.contentKind) catalog.poster(key) else null
                    }
                    val titles = userData.collectionTitles(collection.id).mapNotNull { identity ->
                        if (identity.kind != collection.contentKind) return@mapNotNull null
                        catalog.titleCandidates(identity).firstOrNull { candidate ->
                            vis(candidate.key.kind.name, candidate.key.sourceId.value, candidate.categoryId?.value.orEmpty())
                        }
                    }
                    concrete + titles
                }
                val cards = posters.asSequence().filter { poster ->
                    vis(poster.key.kind.name, poster.key.sourceId.value, poster.categoryId?.value.orEmpty())
                }.distinctBy { it.titleIdentity() ?: it.key }.take(100).map { poster ->
                    HomeCard("collection-${collection.id}-${poster.key.sourceId.value}-${poster.key.remoteId.value}", poster.key,
                        displayTitle(poster.name, poster.year), poster.year?.toString(), poster.posterUrl,
                        section = collection.name, titleIdentity = poster.titleIdentity())
                }.toList()
                if (cards.isEmpty()) null else HomeRow("collection-${collection.id}", collection.name, cards)
            }
            val insertAt = rows.indexOfFirst { it.id == "movies" || it.id == "shows" }.let { if (it < 0) rows.size else it }
            (rows.take(insertAt) + pinned + rows.drop(insertAt)).map { row ->
                row.copy(cards = row.cards.map { card ->
                    val custom = overrides[card.open]
                    val title = card.titleIdentity?.let(titleOverrides::get)
                    card.copy(title = title?.displayTitle ?: custom?.displayTitle ?: card.title,
                        image = title?.customPosterUrl ?: custom?.customPosterUrl ?: card.image)
                })
            }
        }.combine(myTeamsRow) { rows, mine -> if (mine == null) rows else listOf(mine) + rows }
        .combine(userData.setting("home_layout")) { natural, layoutRaw ->
            natural to parseHomeLayout(layoutRaw)
        }.combine(userData.setting("home_density")) { (natural, layout), densityRaw ->
            Triple(natural, layout, densityRaw == "compact")
        }.onEach { (natural, layout, compact) ->
            val rows = applyHomeLayout(natural, layout)
            _state.update { s ->
                // Refresh the focused hero from the new catalog, or choose a replacement if
                // its source/category was removed. Never leave stale artwork on Home.
                val current = rows.asSequence().flatMap { it.cards.asSequence() }
                    .firstOrNull { it.open == s.hero?.open && it.section == s.hero?.section }
                val fallback = rows.firstOrNull { row -> row.id != "myTeams" && row.cards.any { !it.isChannel } }
                    ?.cards?.firstOrNull { !it.isChannel }
                    ?: rows.firstOrNull()?.cards?.firstOrNull()
                // While the viewer is dragging a row in the panel, their live order wins until they drop it.
                val panel = if (moving) s.panel else computeHomePanel(natural, layout)
                s.copy(rows = rows, loaded = true, hero = current ?: fallback, compact = compact, panel = panel)
            }
        }.flowOn(compute).launchIn(viewModelScope)
    }

    // A getter, not a field: the init block above reads it before field initializers would run.
    @Volatile private var moving = false

    /** Hold-OK reorder in the panel: move row type [type] one step up (-1) or down (+1). */
    fun moveHomeRow(type: String, delta: Int) {
        moving = true
        _state.update { s ->
            val list = s.panel.toMutableList()
            val i = list.indexOfFirst { it.type == type }
            val j = i + delta
            if (i < 0 || j !in list.indices) return@update s
            list.add(j, list.removeAt(i))
            s.copy(panel = list)
        }
    }

    /** Drop the grabbed row: save the panel order (and current hidden set) for this profile. */
    fun finishHomeMove() {
        if (!moving) return
        val order = _state.value.panel.map { it.type }
        val hidden = _state.value.panel.filter { !it.visible && it.type != "continue" }.map { it.type }.toSet()
        moving = false
        viewModelScope.launch { userData.putSetting("home_layout", encodeHomeLayout(order, hidden)) }
    }

    /** Show or hide a row type. "Continue watching" can never be hidden. */
    fun setHomeRowVisible(type: String, visible: Boolean) {
        if (type == "continue") return
        _state.update { s -> s.copy(panel = s.panel.map { if (it.type == type) it.copy(visible = visible) else it }) }
        val order = _state.value.panel.map { it.type }
        val hidden = _state.value.panel.filter { !it.visible }.map { it.type }.toSet()
        viewModelScope.launch { userData.putSetting("home_layout", encodeHomeLayout(order, hidden)) }
    }

    /** Clear the saved layout: rows return to their default order with nothing hidden. */
    fun resetHomeLayout() {
        moving = false
        viewModelScope.launch { userData.putSetting("home_layout", "") }
    }

    fun setHomeCompact(compact: Boolean) {
        viewModelScope.launch { userData.putSetting("home_density", if (compact) "compact" else "comfortable") }
    }

    fun isFavorite(key: ContentKey) = userData.isFavorite(key)
    fun setFavorite(key: ContentKey, favorite: Boolean) { viewModelScope.launch { userData.setFavorite(key, favorite) } }

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

    // Task 97: Play next queue — toggle from any title menu; reorder from the row's own hold-OK menu.
    fun isQueued(key: ContentKey) = userData.isQueued(key)

    fun togglePlayNext(key: ContentKey, currentlyQueued: Boolean) {
        viewModelScope.launch { userData.setQueued(key, !currentlyQueued) }
    }

    fun moveQueued(key: ContentKey, up: Boolean) {
        viewModelScope.launch { userData.moveQueued(key, up) }
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

    /**
     * Task 105: "Got it" on the source alert. The fingerprint is stored per profile, so this dismissal
     * silences this problem for this viewer only — the same problem on another profile still shows, and
     * a new problem (a different expiry, a new failed check) shows again.
     */
    fun dismissSourceAlert() {
        val alert = _state.value.alert ?: return
        _state.update { it.copy(alert = null) }
        viewModelScope.launch { userData.putSetting(SourceHealthKeys.dismissed, alert.fingerprint) }
    }

    fun onFocus(card: HomeCard) {
        _state.update { it.copy(hero = card) }
    }
}

@Composable
fun HomeRoute(viewModel: HomeViewModel, onOpen: (HomeCard) -> Unit, modifier: Modifier = Modifier, onOpenNavigation: () -> Unit = {},
              /** Hold-OK menu: play [ContentKey] from its saved position (false) or from the start (true). */
              onPlay: (ContentKey, Boolean) -> Unit = { _, _ -> },
              /** Task 59: the active profile's avatar (top-right); clicking it opens the profile picker. */
              profile: com.yodesla.omniverse.core.data.Profile? = null,
              onProfileClick: () -> Unit = {}) {
    if (LocalCompact.current) {
        CompactHomeRoute(viewModel, onOpen, modifier)
        return
    }
    val s by viewModel.state.collectAsStateWithLifecycle()
    val undo by viewModel.undo.collectAsStateWithLifecycle()
    val c = OmniTheme.colors
    val first = remember { FocusRequester() }
    // Debounce hero changes so fast D-pad scrolling doesn't thrash image loads (PLAN.md §8.2).
    var hero by remember { mutableStateOf(s.hero) }
    LaunchedEffect(s.hero) { delay(250); hero = s.hero }
    var menuCard by remember { mutableStateOf<Pair<HomeCard, String>?>(null) } // card, the row id it was opened from
    // Home always opens at the top row (the list used to restore a scroll position near the bottom).
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // Compact density shrinks Home posters ~15% (124 → 105.dp); other screens are untouched.
    val posterW = if (s.compact) 105.dp else 124.dp
    // Task 69: the hold-OK hint shows once per Home session, first three launches only.
    val hint by viewModel.hintVisible.collectAsStateWithLifecycle(initialValue = false)
    var hintDone by remember { mutableStateOf(false) }
    // Task 102: which card has focus, so the posters ahead of it can be warmed before it lands.
    var posterFocus by remember { mutableStateOf<PosterFocus?>(null) }
    PosterPrefetch(
        rows = remember(s.rows) { s.rows.map { row -> row.cards.map { if (it.isChannel) null else it.image } } },
        focus = posterFocus,
        cardWidth = posterW,
    )

    CosmicBackdrop(modifier.fillMaxSize()) {
        // Home cards carry portrait posters, not backdrops. Keep the artwork legible instead of
        // cropping it into a wide, nearly empty background.
        Crossfade(hero?.image to hero?.isChannel, animationSpec = tween(OmniMotion.BACKDROP_CROSSFADE_MS), label = "hero", modifier = Modifier.align(Alignment.TopEnd)) { (url, isChannel) ->
            if (url != null || isChannel == true) {
                if (isChannel == true) Box(Modifier.fillMaxWidth(0.58f).height(174.dp)) {
                    LogoImage(url, hero?.title.orEmpty(), Modifier.align(Alignment.CenterEnd)
                        .padding(end = OmniSpacing.tvSide + OmniSpacing.l).size(160.dp, 90.dp), plain = true)
                } else {
                    // Key art as a soft backdrop behind the stage: a wide crop that melts into the
                    // galaxy on every side (alpha mask), so the top of Home is never an empty band.
                    AsyncImage(
                        model = url, contentDescription = null, contentScale = ContentScale.Crop,
                        alignment = Alignment.TopCenter,
                        modifier = Modifier.fillMaxWidth(0.6f).height(320.dp)
                            .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen; alpha = 0.85f }
                            .drawWithContent {
                                drawContent()
                                drawRect(androidx.compose.ui.graphics.Brush.horizontalGradient(0f to Color.Transparent, 0.45f to Color.Black.copy(alpha = 0.7f), 0.75f to Color.Black), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
                                drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(0f to Color.Black, 0.55f to Color.Black, 1f to Color.Transparent), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
                            },
                    )
                }
            }
        }
        // Kory (2026-10-03): no dark "curtain" band behind the stage text. The key art fades itself out
        // (alpha mask below) so the text sits on the galaxy, not on a hard-edged scrim.
        if (profile != null) {
            // Task 84b: the avatar is a circle, not a dark rounded-square glass plate. The card
            // itself is the circle: transparent container, no border, accent glow + the same
            // 1.06 focus scale as every other tile.
            Card(
                onClick = onProfileClick,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = OmniSpacing.l, end = OmniSpacing.tvSide)
                    .size(64.dp)
                    .touchClick(onProfileClick),
                shape = CardDefaults.shape(shape = CircleShape),
                scale = CardDefaults.scale(focusedScale = OmniMotion.FOCUS_SCALE),
                colors = CardDefaults.colors(
                    containerColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent,
                    pressedContainerColor = Color.Transparent,
                ),
                border = CardDefaults.border(border = Border.None, focusedBorder = Border.None, pressedBorder = Border.None),
                glow = CardDefaults.glow(
                    focusedGlow = Glow(elevationColor = c.accent.copy(alpha = 0.55f), elevation = 18.dp),
                ),
            ) {
                // tv Card content has no BoxScope receiver, so center the disc explicitly.
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    com.yodesla.omniverse.feature.home.profiles.ProfileAvatarCurrent(profile, 56.dp)
                }
            }
        }
        Text(
            rememberTimeText(), style = OmniTheme.type.numeric, color = c.textSecondary,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = OmniSpacing.l, end = OmniSpacing.tvSide + 136.dp)
                // The clock floats over the bright end of the art: a tight plate keeps it ≥4.5:1.
                .background(c.background.copy(alpha = 0.85f), androidx.compose.foundation.shape.RoundedCornerShape(6.dp)),
        )
        Column(Modifier.fillMaxSize()) {
            // A compact, fixed-height editorial stage keeps more rows visible without jumping.
            Column(
                // fillMaxWidth makes a trailing widthIn a no-op; reserve the clock + hero-poster column instead.
                Modifier.fillMaxWidth().height(174.dp).padding(start = OmniSpacing.tvSide, top = 28.dp, end = OmniSpacing.tvSide + 248.dp),
                verticalArrangement = Arrangement.spacedBy(OmniSpacing.xs),
            ) {
                Text((hero?.section ?: "").uppercase(), style = OmniTheme.type.overline, color = c.accent, maxLines = 1)
                // One line: the stage has a fixed height, a second line would run into the first row.
                Text(hero?.title ?: " ", style = OmniTheme.type.browseHero, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(hero?.subtitle ?: " ", style = OmniTheme.type.body, color = c.textSecondary, maxLines = 1)
            }
            s.alert?.let { alert -> SourceAlertBanner(alert, { viewModel.dismissSourceAlert() }, Modifier.padding(horizontal = OmniSpacing.tvSide)) }
            if (s.loaded && s.rows.isEmpty()) {
                Text(
                    "Your channels, movies and shows will appear here after the first sync.",
                    style = OmniTheme.type.body, color = c.textTertiary, modifier = Modifier.padding(OmniSpacing.tvSide),
                )
            }
            // The focused CARD lands here; leave room above it for its row header.
            FocusPivot(parentFraction = 0f, leading = 40.dp) {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(OmniSpacing.l),
                    contentPadding = PaddingValues(bottom = 160.dp),
                ) {
                    itemsIndexed(s.rows, key = { _, row -> row.id }) { rowIndex, row ->
                        Column {
                            Row(Modifier.padding(start = OmniSpacing.tvSide, bottom = OmniSpacing.xs), verticalAlignment = Alignment.Bottom) {
                                // Plain text: rows are arranged from Settings > Customize Home, not from here.
                                Text(row.title, style = OmniTheme.type.title, color = c.textPrimary)
                                Spacer(Modifier.width(OmniSpacing.m))
                                Text("${row.cards.size}", style = OmniTheme.type.caption, color = c.textTertiary)
                            }
                            FocusPivot {
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m),
                                    contentPadding = PaddingValues(horizontal = OmniSpacing.tvSide, vertical = OmniSpacing.s),
                                ) {
                                    itemsIndexed(row.cards, key = { _, card -> card.id }) { cardIndex, card ->
                                        val mod = Modifier
                                            .onFocusChanged {
                                                if (it.isFocused) {
                                                    viewModel.onFocus(card)
                                                    posterFocus = PosterFocus(rowIndex, cardIndex)
                                                }
                                            }
                                            .then(if (card == row.cards.first()) Modifier.onPreviewKeyEvent { e ->
                                                if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) { onOpenNavigation(); true } else false
                                            } else Modifier)
                                            .then(if (row == s.rows.first() && card == row.cards.first()) Modifier.focusRequester(first) else Modifier)
                                        if (card.isChannel) ChannelTile(card, mod) { onOpen(card) }
                                        else PosterCard(card.title, card.image, { onOpen(card) }, mod.width(posterW), card.subtitle, card.progress,
                                            onLongClick = { menuCard = card to row.id })
                                    }
                                    // Task 96: "Surprise me" sits last in Continue watching.
                                    if (row.id == "continue") item(key = "surprise-me") {
                                        SurpriseTile(posterW, Modifier) { viewModel.surpriseMe() }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (hint && !hintDone) {
            LaunchedEffect(Unit) { hintDone = true; viewModel.hintShown() }
            com.yodesla.omniverse.designsystem.HoldOkHint(
                Modifier.align(Alignment.BottomStart).padding(start = OmniSpacing.tvSide, bottom = OmniSpacing.l),
            )
        }
        menuCard?.let { (card, rowId) ->
            val fav by remember(card.open) { viewModel.isFavorite(card.open) }.collectAsState(initial = false)
            val queued by remember(card.open) { viewModel.isQueued(card.open) }.collectAsState(initial = false)
            com.yodesla.omniverse.designsystem.TitleMenu(
                title = card.title, subtitle = card.subtitle, posterUrl = card.image,
                actions = buildList {
                    addAll(com.yodesla.omniverse.designsystem.titleMenuActions(
                        resumeLabel = if ((card.progress ?: 0f) > 0f) "Resume" else null,
                        onResume = { onPlay(card.open, false) }, onStartOver = { onPlay(card.open, true) },
                        onDetails = { onOpen(card) }, inMyList = fav, onToggleMyList = { viewModel.toggleMyList(card.open, fav) },
                        inQueue = queued, onToggleQueue = { viewModel.togglePlayNext(card.open, queued) },
                        onRemoveContinue = if (rowId == "continue") ({ viewModel.removeFromContinue(card.open) }) else null,
                    ))
                    // Task 97: cards in the Play next row can be reordered in place.
                    if (rowId == "playnext") {
                        add(com.yodesla.omniverse.designsystem.MenuAction("Move up", { viewModel.moveQueued(card.open, up = true) }))
                        add(com.yodesla.omniverse.designsystem.MenuAction("Move down", { viewModel.moveQueued(card.open, up = false) }))
                    }
                },
                onDismiss = { menuCard = null },
            )
        }
        // Task 96: the "Tonight" card for the Surprise-me pick.
        val pick by viewModel.pick.collectAsStateWithLifecycle()
        pick?.let { offer ->
            PickForMeCard(
                offer = offer,
                onPlay = { p -> onPlay(p.key, false) },
                onAnother = { viewModel.surpriseMe() },
                onDetails = { p -> onOpen(pickCard(p)) },
                onDismiss = { viewModel.dismissPick() },
            )
        }
        undo?.let { ui ->
            com.yodesla.omniverse.designsystem.UndoCard(
                message = ui.message,
                onUndo = { viewModel.undoLast() },
                onDismiss = { viewModel.dismissUndo() },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = OmniSpacing.xl),
            )
        }
    }
    LaunchedEffect(s.rows.isNotEmpty()) { if (s.rows.isNotEmpty()) { listState.scrollToItem(0); first.requestFocusWhenReady() } }
}

// Phone layout: no clock, smaller hero, 120.dp posters, 16.dp side padding.
@Composable
private fun CompactHomeRoute(viewModel: HomeViewModel, onOpen: (HomeCard) -> Unit, modifier: Modifier = Modifier) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val c = OmniTheme.colors
    val first = remember { FocusRequester() }
    var hero by remember { mutableStateOf(s.hero) }
    LaunchedEffect(s.hero) { delay(250); hero = s.hero }
    val posterW = if (s.compact) 102.dp else 120.dp
    // Task 102: same prefetch on the phone layout (its rows pop in the same way).
    var posterFocus by remember { mutableStateOf<PosterFocus?>(null) }
    PosterPrefetch(
        rows = remember(s.rows) { s.rows.map { row -> row.cards.map { if (it.isChannel) null else it.image } } },
        focus = posterFocus,
        cardWidth = posterW,
    )

    Box(modifier.fillMaxSize().background(c.background)) {
        Crossfade(hero?.image to hero?.isChannel, animationSpec = tween(OmniMotion.BACKDROP_CROSSFADE_MS), label = "hero", modifier = Modifier.align(Alignment.TopEnd)) { (url, isChannel) ->
            if (url != null || isChannel == true) {
                Box(Modifier.fillMaxWidth(0.9f).height(220.dp)) {
                    if (isChannel == true) {
                        LogoImage(url, hero?.title.orEmpty(), Modifier.align(Alignment.TopEnd).padding(OmniSpacing.m).size(140.dp, 78.dp), plain = true)
                    } else {
                        AsyncImage(url, null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
                    }
                    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to c.background, 0.35f to c.background.copy(alpha = 0.55f), 0.7f to Color.Transparent)))
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to c.background)))
                }
            }
        }
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxWidth().height(220.dp).padding(start = 16.dp, top = 32.dp).widthIn(max = 460.dp),
                verticalArrangement = Arrangement.spacedBy(OmniSpacing.xs),
            ) {
                Text((hero?.section ?: "").uppercase(), style = OmniTheme.type.overline, color = c.accent, maxLines = 1)
                Text(hero?.title ?: " ", style = OmniTheme.type.title, color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(hero?.subtitle ?: " ", style = OmniTheme.type.body, color = c.textSecondary, maxLines = 1)
            }
            s.alert?.let { alert -> SourceAlertBanner(alert, { viewModel.dismissSourceAlert() }, Modifier.padding(horizontal = 16.dp)) }
            if (s.loaded && s.rows.isEmpty()) {
                Text(
                    "Your channels, movies and shows will appear here after the first sync.",
                    style = OmniTheme.type.body, color = c.textTertiary, modifier = Modifier.padding(16.dp),
                )
            }
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(OmniSpacing.m),
                contentPadding = PaddingValues(bottom = 32.dp),
            ) {
                itemsIndexed(s.rows, key = { _, row -> row.id }) { rowIndex, row ->
                    Column {
                        Row(Modifier.padding(start = 16.dp, bottom = OmniSpacing.xs), verticalAlignment = Alignment.Bottom) {
                            Text(row.title, style = OmniTheme.type.title, color = c.textPrimary)
                            Spacer(Modifier.width(OmniSpacing.m))
                            Text("${row.cards.size}", style = OmniTheme.type.caption, color = c.textTertiary)
                        }
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = OmniSpacing.m),
                        ) {
                            itemsIndexed(row.cards, key = { _, card -> card.id }) { cardIndex, card ->
                                val mod = Modifier
                                    .onFocusChanged {
                                        if (it.isFocused) {
                                            viewModel.onFocus(card)
                                            posterFocus = PosterFocus(rowIndex, cardIndex)
                                        }
                                    }
                                    .then(if (row == s.rows.first() && card == row.cards.first()) Modifier.focusRequester(first) else Modifier)
                                if (card.isChannel) ChannelTile(card, mod) { onOpen(card) }
                                else PosterCard(card.title, card.image, { onOpen(card) }, mod.width(posterW), card.subtitle, card.progress)
                            }
                            // Task 96: "Surprise me" sits last in Continue watching.
                            if (row.id == "continue") item(key = "surprise-me") {
                                SurpriseTile(posterW, Modifier) { viewModel.surpriseMe() }
                            }
                        }
                    }
                }
            }
        }
        // Task 96: the "Tonight" card for the Surprise-me pick.
        val pick by viewModel.pick.collectAsStateWithLifecycle()
        pick?.let { offer ->
            PickForMeCard(
                offer = offer,
                onPlay = { p -> onOpen(pickCard(p)) },
                onAnother = { viewModel.surpriseMe() },
                onDetails = { p -> onOpen(pickCard(p)) },
                onDismiss = { viewModel.dismissPick() },
            )
        }
    }
    LaunchedEffect(s.rows.isNotEmpty()) { if (s.rows.isNotEmpty()) first.requestFocusWhenReady() }
}

/**
 * Task 105: the source alert. One line — the source the viewer named, plus what is wrong with it
 * ("Expires in 5 days", "Login failed", "Keeps failing (3 checks in a row)"). Nothing the provider
 * sent is shown, and no URL or account detail is ever rendered here. OK / click dismisses it for this
 * profile; a genuinely new problem raises it again.
 */
@Composable
private fun SourceAlertBanner(alert: SourceAlert, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val c = OmniTheme.colors
    val t = OmniTheme.type
    FocusCard(onClick = onDismiss, modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = OmniSpacing.m, vertical = OmniSpacing.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m),
        ) {
            Text("SOURCE ALERT", style = t.overline, color = c.live, maxLines = 1)
            Text(
                "${alert.sourceName}: ${alert.text}",
                style = t.body, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text("Dismiss", style = t.caption, color = c.textSecondary, maxLines = 1)
        }
    }
}

@Composable
private fun ChannelTile(card: HomeCard, modifier: Modifier, onClick: () -> Unit) {
    val c = OmniTheme.colors
    FocusCard(onClick = onClick, modifier = modifier.size(220.dp, 124.dp)) {
        Column(Modifier.fillMaxSize().padding(OmniSpacing.m), verticalArrangement = Arrangement.SpaceBetween) {
            LogoImage(card.image, card.title, Modifier.size(96.dp, 54.dp))
            Column {
                Text(card.title, style = OmniTheme.type.body, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(card.subtitle ?: "Live", style = OmniTheme.type.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Task 96: the "Surprise me" tile that closes the Continue watching row. */
@Composable
private fun SurpriseTile(width: Dp, modifier: Modifier, onClick: () -> Unit) {
    val c = OmniTheme.colors
    FocusCard(onClick = onClick, modifier = modifier.width(width).aspectRatio(2f / 3f)) {
        Column(
            Modifier.fillMaxSize().padding(OmniSpacing.m),
            verticalArrangement = Arrangement.spacedBy(OmniSpacing.xs, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("✦", style = OmniTheme.type.browseHero, color = c.accent)
            Text("Surprise me", style = OmniTheme.type.body.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary, maxLines = 1)
            Text("Tonight's pick", style = OmniTheme.type.caption, color = c.textSecondary, maxLines = 1)
        }
    }
}

/** Task 96: a picked poster opened from the Tonight card rides the normal detail route. */
private fun pickCard(p: PosterRow) = HomeCard(
    id = "pick-${p.key.sourceId.value}-${p.key.remoteId.value}", open = p.key,
    title = displayTitle(p.name, p.year), subtitle = null, image = p.posterUrl,
    titleIdentity = p.titleIdentity(),
)

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

/**
 * Continue Watching only: when a copy has no TMDB id, group by kind + normalised name so the
 * same movie/show watched from two IPTV categories (or Plex + IPTV) shows once. Browse surfaces keep
 * exact-ID grouping; here a duplicate card is worse than a rare false merge (newest watch still wins).
 */
internal fun cwNameGroup(kind: ContentKind, title: String): String {
    val name = title.lowercase()
        .replace(Regex("""^\s*([a-z]{2,3}|4k|uhd|fhd|hd)\s*[|:\-▎]\s*"""), "")      // provider prefixes: "EN | ", "4K - "
        .replace(Regex("""\((19|20)\d{2}\)|\[[^]]*]|\b(4k|uhd|fhd|hd|1080p|720p|multi|sub|dub)\b"""), " ")
        .replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()
    return "$kind:name:$name"
}

/** Exact-ID grouping key: the same TMDB title from several sources shows once (first visible wins). */
private fun titleGroup(p: com.yodesla.omniverse.core.data.PosterRow): String =
    p.tmdbId?.takeIf { it.isNotBlank() }?.let { "${p.key.kind}:tmdb:$it" } ?: "${p.key.kind}:key:${p.key.sourceId.value}:${p.key.remoteId.value}"

/** "24 min left" / "1h 02m left" for Continue Watching; null when the length is unknown. */
internal fun timeLeftLabel(episode: Boolean, positionMs: Long, durationMs: Long?): String? {
    val left = ((durationMs ?: return if (episode) "Continue episode" else null) - positionMs).coerceAtLeast(0) / 60_000
    val time = if (left >= 60) "%dh %02dm left".format(left / 60, left % 60) else "${left.coerceAtLeast(1)} min left"
    return if (episode) "Episode  ·  $time" else time
}
