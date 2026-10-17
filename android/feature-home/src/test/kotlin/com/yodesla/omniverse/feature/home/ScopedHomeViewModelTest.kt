package com.yodesla.omniverse.feature.home

import androidx.paging.PagingSource
import com.yodesla.omniverse.core.data.CatalogRepository
import com.yodesla.omniverse.core.data.ChannelRow
import com.yodesla.omniverse.core.data.Clock
import com.yodesla.omniverse.core.data.CompletedShow
import com.yodesla.omniverse.core.data.EpgRepository
import com.yodesla.omniverse.core.data.LibraryCollection
import com.yodesla.omniverse.core.data.NextEpisodeFinder
import com.yodesla.omniverse.core.data.NewEpisodes
import com.yodesla.omniverse.core.data.NewEpisodesNotice
import com.yodesla.omniverse.core.data.NowNext
import com.yodesla.omniverse.core.data.PosterRow
import com.yodesla.omniverse.core.data.Progress
import com.yodesla.omniverse.core.data.UserDataRepository
import com.yodesla.omniverse.core.data.sports.FollowedTeam
import com.yodesla.omniverse.core.data.sports.FollowedTeams
import com.yodesla.omniverse.core.data.sports.FollowedTeamsStore
import com.yodesla.omniverse.core.data.sports.MyTeamsRepository
import com.yodesla.omniverse.core.data.sports.Sport
import com.yodesla.omniverse.core.data.sports.SportsAiring
import com.yodesla.omniverse.core.data.sports.SportsKind
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.Episode
import com.yodesla.omniverse.core.model.ProgrammeRecord
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.SourceId
import com.yodesla.omniverse.core.model.TimeWindow
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

private val src1 = SourceId("s1")

private fun sVod(source: SourceId, id: String) = ContentKey(source, ContentKind.VOD, RemoteId(id))
private fun sSeries(source: SourceId, id: String) = ContentKey(source, ContentKind.SERIES, RemoteId(id))
private fun sLive(source: SourceId, id: String) = ContentKey(source, ContentKind.LIVE, RemoteId(id))

private fun sPoster(
    key: ContentKey,
    name: String,
    tmdbId: String? = null,
    categoryId: RemoteId? = null,
    genre: String? = null,
): PosterRow = PosterRow(key, name, null, null, null, categoryId, tmdbId, genre = genre)

private fun HomeState.rowOrNull(id: String): HomeRow? = rows.firstOrNull { it.id == id }
private fun HomeState.allOpens(): List<ContentKey> = rows.flatMap { row -> row.cards.map { it.open } }
private fun HomeState.allCards() = rows.flatMap { it.cards }

/**
 * Task 201: a kind-scoped Home (Movies-only / Shows-only). These tests drive the real
 * [HomeViewModel] with a deliberately MIXED world - movies, shows, an in-progress episode, a
 * completed show with an up-next episode, a favourite live channel, a followed-team guide, a
 * mixed queue, mixed favourites, a pinned VOD and a pinned SERIES collection, and both
 * recently-added shelves - and assert that a scoped VM keeps only its own kind everywhere,
 * while a null VM keeps the normal mixed Home byte-for-byte.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScopedHomeViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.scopedVm(
        contentKind: ContentKind?,
        catalog: ScopedCatalog,
        epg: ScopedEpg,
        userData: ScopedUserData,
        clock: Clock = Clock { 1_000L },
        upNext: NextEpisodeFinder? = null,
        myTeams: MyTeamsRepository? = null,
    ): HomeViewModel = HomeViewModel(
        catalog, epg, userData, clock,
        upNext = upNext,
        myTeams = myTeams,
        compute = StandardTestDispatcher(testScheduler),
        contentKind = contentKind,
    )

    private fun myTeams(epg: ScopedEpg, userData: ScopedUserData) =
        MyTeamsRepository(epg, Clock { 1_000L }, FollowedTeamsStore(userData))

    @Test
    fun vodScopedHomeKeepsOnlyMoviesInEveryRowHeroQueueAndCollections() = runTest(dispatcher) {
        val w = MixedWorld()
        val catalog = w.catalog()
        val epg = w.epg()
        val userData = w.userData()
        val vm = scopedVm(ContentKind.VOD, catalog, epg, userData, myTeams = myTeams(epg, userData))
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state.loaded)
        assertTrue(state.allOpens().isNotEmpty())
        assertTrue(state.allOpens().all { it.kind == ContentKind.VOD }, "a Movies Home leaked: ${state.allOpens().filter { it.kind != ContentKind.VOD }}")
        assertTrue(state.allCards().none { it.isChannel }, "a Movies Home must carry no channel cards")
        assertEquals(ContentKind.VOD, assertNotNull(state.hero).open.kind)

        val cont = assertNotNull(state.rowOrNull("continue"))
        assertTrue(cont.cards.any { it.open == w.movieSeed })
        assertTrue(cont.cards.none { it.open == w.showSeed }, "a Movies Home must drop the in-progress show")

        assertEquals(setOf(w.movieFav), assertNotNull(state.rowOrNull("mylist")).cards.map { it.open }.toSet())
        assertEquals(listOf(w.movieQueue), assertNotNull(state.rowOrNull("playnext")).cards.map { it.open })
        assertNotNull(state.rowOrNull("movies"))
        assertNull(state.rowOrNull("shows"), "a Movies Home must not build the shows shelf")

        assertNotNull(state.rowOrNull("collection-colV"))
        assertNull(state.rowOrNull("collection-colS"))

        assertNull(state.rowOrNull("fav"))
        assertNull(state.rowOrNull("myTeams"))
        assertNull(state.rowOrNull("newEpisodes"))

        assertNotNull(state.rowOrNull("byw-VOD-s1-a"))
        assertTrue(state.rows.none { it.id.startsWith("byw-SERIES") }, "a Movies Home seeded a shows row")

        assertEquals(setOf(ContentKind.VOD), catalog.queriedKinds)
        assertEquals(0, catalog.channelLookups)
        assertEquals(0, epg.nowNextCalls)
    }

    @Test
    fun seriesScopedHomeKeepsShowsAndMapsEpisodeProgressAndUpNextToTheirShow() = runTest(dispatcher) {
        val w = MixedWorld()
        val catalog = w.catalog()
        val epg = w.epg()
        val userData = w.userData()
        val vm = scopedVm(ContentKind.SERIES, catalog, epg, userData, upNext = w.upNext(), myTeams = myTeams(epg, userData))
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state.loaded)
        assertTrue(state.allOpens().all { it.kind == ContentKind.SERIES }, "a Shows Home leaked: ${state.allOpens().filter { it.kind != ContentKind.SERIES }}")
        assertTrue(state.allCards().none { it.isChannel })
        assertEquals(ContentKind.SERIES, assertNotNull(state.hero).open.kind)

        val cont = assertNotNull(state.rowOrNull("continue"))
        val episodeCard = assertNotNull(cont.cards.firstOrNull { it.open == w.showSeed })
        assertTrue(episodeCard.subtitle.orEmpty().contains("min left"), "episode progress must roll up to the show: ${episodeCard.subtitle}")
        val upNextCard = assertNotNull(cont.cards.firstOrNull { it.open == w.showUpNext })
        assertTrue(upNextCard.subtitle.orEmpty().startsWith("Up next"), "completed show must roll to up next: ${upNextCard.subtitle}")
        assertTrue(cont.cards.none { it.open == w.movieSeed }, "a Shows Home must drop the movie watch")

        assertEquals(setOf(w.showFav), assertNotNull(state.rowOrNull("mylist")).cards.map { it.open }.toSet())
        assertEquals(listOf(w.showQueue), assertNotNull(state.rowOrNull("playnext")).cards.map { it.open })
        assertNotNull(state.rowOrNull("shows"))
        assertNull(state.rowOrNull("movies"), "a Shows Home must not build the movies shelf")

        assertNotNull(state.rowOrNull("collection-colS"))
        assertNull(state.rowOrNull("collection-colV"))

        val newEps = assertNotNull(state.rowOrNull("newEpisodes"))
        assertEquals(w.showSeed, newEps.cards.single().open)

        assertNull(state.rowOrNull("fav"))
        assertNull(state.rowOrNull("myTeams"))

        assertNotNull(state.rowOrNull("byw-SERIES-s1-ser1"))
        assertTrue(state.rows.none { it.id.startsWith("byw-VOD") }, "a Shows Home seeded a movies row")

        assertEquals(setOf(ContentKind.SERIES), catalog.queriedKinds)
        assertEquals(0, catalog.channelLookups)
        assertEquals(0, epg.nowNextCalls)
    }

    @Test
    fun scopedSurpriseMeOnlyEverOffersItsOwnKind() = runTest(dispatcher) {
        val w = MixedWorld()
        val movies = scopedVm(ContentKind.VOD, w.catalog(), w.epg(), w.userData())
        advanceUntilIdle()
        movies.surpriseMe()
        advanceUntilIdle()
        assertEquals(ContentKind.VOD, assertNotNull(assertNotNull(movies.pick.value).poster).key.kind)

        val shows = scopedVm(ContentKind.SERIES, w.catalog(), w.epg(), w.userData())
        advanceUntilIdle()
        shows.surpriseMe()
        advanceUntilIdle()
        assertEquals(ContentKind.SERIES, assertNotNull(assertNotNull(shows.pick.value).poster).key.kind)
    }

    @Test
    fun nullHomeKeepsBothKindsLiveChannelsAndQueriesBothShelves() = runTest(dispatcher) {
        val w = MixedWorld()
        val catalog = w.catalog()
        val epg = w.epg()
        val userData = w.userData()
        val vm = scopedVm(null, catalog, epg, userData, upNext = w.upNext(), myTeams = myTeams(epg, userData))
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state.allOpens().any { it.kind == ContentKind.VOD })
        assertTrue(state.allOpens().any { it.kind == ContentKind.SERIES })
        assertTrue(state.allCards().any { it.isChannel }, "the normal Home keeps live/channel cards")
        assertNotNull(state.rowOrNull("fav"))
        assertNotNull(state.rowOrNull("myTeams"))
        assertNotNull(state.rowOrNull("movies"))
        assertNotNull(state.rowOrNull("shows"))
        assertNotNull(state.rowOrNull("newEpisodes"))

        assertEquals(setOf(w.movieFav, w.showFav), assertNotNull(state.rowOrNull("mylist")).cards.map { it.open }.toSet())
        assertEquals(listOf(w.movieQueue, w.showQueue), assertNotNull(state.rowOrNull("playnext")).cards.map { it.open })
        assertNotNull(state.rowOrNull("collection-colV"))
        assertNotNull(state.rowOrNull("collection-colS"))

        assertNotNull(state.rowOrNull("byw-SERIES-s1-ser1"))
        assertNotNull(state.rowOrNull("byw-VOD-s1-a"))

        assertEquals(setOf(ContentKind.VOD, ContentKind.SERIES), catalog.queriedKinds)
        assertTrue(catalog.channelLookups > 0)
        assertTrue(epg.nowNextCalls > 0)
    }

    @Test
    fun scopedHomeRejectsKindsThatHaveNoLandingPage() = runTest(dispatcher) {
        assertFailsWith<IllegalArgumentException> {
            HomeViewModel(
                ScopedCatalog(), ScopedEpg(), ScopedUserData(), Clock { 0L },
                compute = StandardTestDispatcher(testScheduler),
                contentKind = ContentKind.LIVE,
            )
        }
    }
}

/** A deliberately mixed catalog that records which kinds and live data it was actually asked for. */
private class ScopedCatalog(
    private val posters: Map<ContentKey, PosterRow> = emptyMap(),
    private val channels: Map<ContentKey, ChannelRow> = emptyMap(),
    private val recentVod: List<PosterRow> = emptyList(),
    private val recentSeries: List<PosterRow> = emptyList(),
    private val similar: Map<ContentKey, List<PosterRow>> = emptyMap(),
) : CatalogRepository {
    val queriedKinds = mutableSetOf<ContentKind>()
    var channelLookups = 0

    override suspend fun poster(key: ContentKey): PosterRow? = posters[key]
    override suspend fun channel(key: ContentKey): ChannelRow? {
        channelLookups++
        return channels[key]
    }

    override suspend fun similarTo(seed: ContentKey, limit: Int): List<PosterRow> = similar[seed]?.take(limit) ?: emptyList()
    override fun recentlyAdded(kind: ContentKind, limit: Int): Flow<List<PosterRow>> {
        queriedKinds += kind
        return when (kind) {
            ContentKind.VOD -> flowOf(recentVod)
            ContentKind.SERIES -> flowOf(recentSeries)
            else -> flowOf(emptyList())
        }
    }

    override fun categories(sourceId: SourceId, kind: ContentKind, includeHidden: Boolean): Flow<List<com.yodesla.omniverse.core.model.Category>> = TODO()
    override fun channels(sourceId: SourceId, categoryId: RemoteId): PagingSource<Int, ChannelRow> = TODO()
    override fun channelsAll(sourceId: SourceId, excludedCategories: Collection<String>): PagingSource<Int, ChannelRow> = TODO()
    override suspend fun channelListAll(sourceId: SourceId, excludedCategories: Collection<String>): List<ChannelRow> = emptyList()
    override suspend fun channelOrderAll(sourceId: SourceId, excludedCategories: Collection<String>): List<ContentKey> = emptyList()
    override suspend fun channelList(sourceId: SourceId, categoryId: RemoteId): List<ChannelRow> = TODO()
    override suspend fun channelNeighbours(key: ContentKey, categoryId: RemoteId): Pair<ChannelRow?, ChannelRow?> = TODO()
    override suspend fun channelOrder(sourceId: SourceId, categoryId: RemoteId): List<ContentKey> = TODO()
    override fun vod(sourceId: SourceId, categoryId: RemoteId?): PagingSource<Int, PosterRow> = TODO()
    override fun series(sourceId: SourceId, categoryId: RemoteId?): PagingSource<Int, PosterRow> = TODO()
    override fun vodAll(excludedCategoryKeys: Collection<String>): PagingSource<Int, PosterRow> = TODO()
    override fun seriesAll(excludedCategoryKeys: Collection<String>): PagingSource<Int, PosterRow> = TODO()
    override fun counts(sourceId: SourceId): Flow<Map<ContentKind, Long>> = TODO()
}

private class ScopedUserData(
    private val progress: List<Progress> = emptyList(),
    private val favorites: List<ContentKey> = emptyList(),
    private val completedShows: List<CompletedShow> = emptyList(),
    private val saved: List<Progress> = progress,
    initialSettings: Map<String, String> = emptyMap(),
    queue: List<ContentKey> = emptyList(),
    private val collections: List<LibraryCollection> = emptyList(),
    private val collectionItems: Map<String, List<ContentKey>> = emptyMap(),
) : UserDataRepository {
    private val settingsFlow = MutableStateFlow(initialSettings)
    private val queueFlow = MutableStateFlow(queue)
    override fun continueWatching(limit: Int): Flow<List<Progress>> = flowOf(progress.take(limit))
    override fun savedProgress(limit: Int): Flow<List<Progress>> = flowOf(saved.take(limit))
    override fun completedShows(sinceMs: Long, limit: Int): Flow<List<CompletedShow>> =
        flowOf(completedShows.filter { it.updatedMs >= sinceMs }.take(limit))
    override fun favorites(kind: ContentKind?): Flow<List<ContentKey>> =
        flowOf(kind?.let { k -> favorites.filter { it.kind == k } } ?: favorites)
    override fun isFavorite(key: ContentKey): Flow<Boolean> = flowOf(favorites.contains(key))
    override suspend fun setFavorite(key: ContentKey, favorite: Boolean) {}
    override fun playNext(): Flow<List<ContentKey>> = queueFlow
    override fun isQueued(key: ContentKey): Flow<Boolean> = queueFlow.map { it.contains(key) }
    override suspend fun setQueued(key: ContentKey, queued: Boolean) {
        queueFlow.update { if (queued) (it + key).distinct() else it - key }
    }
    override suspend fun moveQueued(key: ContentKey, up: Boolean) {
        queueFlow.update { q ->
            val i = q.indexOf(key)
            val j = if (up) i - 1 else i + 1
            if (i < 0 || j < 0 || j >= q.size) q else q.toMutableList().apply { add(j, removeAt(i)) }
        }
    }
    override suspend fun saveProgress(key: ContentKey, parentId: RemoteId?, positionMs: Long, durationMs: Long?) {}
    override suspend fun progress(key: ContentKey): Progress? = progress.firstOrNull { it.key == key }
    override suspend fun recordChannelWatched(key: ContentKey) {}
    override fun recentChannels(limit: Int): Flow<List<ContentKey>> = flowOf(emptyList())
    override suspend fun setHidden(key: ContentKey, hidden: Boolean) {}
    override fun setting(key: String): Flow<String?> = settingsFlow.map { it[key] }
    override suspend fun putSetting(key: String, value: String) { settingsFlow.update { it + (key to value) } }
    override fun collections(): Flow<List<LibraryCollection>> = flowOf(collections)
    override suspend fun collectionItems(id: String): List<ContentKey> = collectionItems[id] ?: emptyList()
}

private class ScopedEpg : EpgRepository {
    var nowNextCalls = 0
    override suspend fun nowNext(sourceId: SourceId, epgKeys: Collection<String>, atMs: Long): Map<String, NowNext> {
        nowNextCalls++
        return emptyMap()
    }

    override suspend fun programmes(sourceId: SourceId, epgKey: String, window: TimeWindow): List<ProgrammeRecord> = emptyList()
    override suspend fun sportsSchedule(nowMs: Long, windowMs: Long, limit: Int): List<SportsAiring> = listOf(
        SportsAiring(
            channel = sLive(src1, "ch"), categoryId = RemoteId("cat"), channelName = "Sports One", logoUrl = null,
            title = "Chiefs vs Broncos", description = null, startMs = 0L, endMs = 10_000L,
            sport = Sport.FOOTBALL, kind = SportsKind.EVENT, teams = "Chiefs" to "Broncos", league = null,
        ),
    )
}

/**
 * One mixed world shared by every test: movies and shows in Continue Watching, My List and the
 * queue, both recently-added shelves and both pinned collections, plus a live favourite channel, a
 * followed team with a matching guide airing, an in-progress episode, a completed show with an
 * up-next episode and a new-episode notice on the watched show.
 */
private class MixedWorld {
    val movieSeed = sVod(src1, "a")
    val movieOld = sVod(src1, "old")
    val movieFav = sVod(src1, "fav")
    val movieQueue = sVod(src1, "q")
    val moviePick = sVod(src1, "pick")
    val showSeed = sSeries(src1, "ser1")
    val showRecent = sSeries(src1, "rec")
    val showFav = sSeries(src1, "fav")
    val showQueue = sSeries(src1, "q")
    val showPick = sSeries(src1, "pick")
    val showUpNext = sSeries(src1, "up")
    val channel = sLive(src1, "ch")
    private val similarMovies = (1..4).map { sVod(src1, "b$it") }
    private val similarShows = (1..4).map { sSeries(src1, "c$it") }

    private val posters: Map<ContentKey, PosterRow> = buildMap {
        put(movieSeed, sPoster(movieSeed, "Seed Movie"))
        put(movieOld, sPoster(movieOld, "Old Movie"))
        put(movieFav, sPoster(movieFav, "Fav Movie"))
        put(movieQueue, sPoster(movieQueue, "Queue Movie"))
        put(moviePick, sPoster(moviePick, "Pick Movie", genre = "Action"))
        similarMovies.forEach { put(it, sPoster(it, "Similar Movie ${it.remoteId.value}")) }
        put(showSeed, sPoster(showSeed, "Seed Show"))
        put(showRecent, sPoster(showRecent, "Recent Show"))
        put(showFav, sPoster(showFav, "Fav Show"))
        put(showQueue, sPoster(showQueue, "Queue Show"))
        put(showPick, sPoster(showPick, "Pick Show", genre = "Action"))
        put(showUpNext, sPoster(showUpNext, "Up Next Show"))
        similarShows.forEach { put(it, sPoster(it, "Similar Show ${it.remoteId.value}")) }
    }

    private val channelRow = ChannelRow(channel, 1, "Sports One", null, null, 0, RemoteId("cat"))
    private val recentVod = listOf(sPoster(movieSeed, "Seed Movie"), sPoster(moviePick, "Pick Movie", genre = "Action"))
    private val recentSeries = listOf(sPoster(showSeed, "Seed Show"), sPoster(showRecent, "Recent Show"), sPoster(showPick, "Pick Show", genre = "Action"))
    private val similar = mapOf(
        movieSeed to similarMovies.map { posters.getValue(it) },
        showSeed to similarShows.map { posters.getValue(it) },
    )
    private val progress = listOf(
        Progress(ContentKey(src1, ContentKind.EPISODE, RemoteId("e1-3")), RemoteId("ser1"), 10L * 60_000L, 34L * 60_000L, updatedMs = 300L),
        Progress(movieSeed, null, 1_000L, 2_000L, updatedMs = 200L),
        Progress(movieOld, null, 1_000L, 2_000L, updatedMs = 100L),
    )
    private val completed = listOf(CompletedShow(showUpNext, ContentKey(src1, ContentKind.EPISODE, RemoteId("e3")), updatedMs = 50L))
    private val favorites = listOf(movieFav, showFav, channel)
    private val queue = listOf(movieQueue, showQueue)
    private val collections = listOf(
        LibraryCollection("colV", "Movies Pin", ContentKind.VOD, pinnedHome = true),
        LibraryCollection("colS", "Shows Pin", ContentKind.SERIES, pinnedHome = true),
    )
    private val collectionItems = mapOf("colV" to listOf(movieFav), "colS" to listOf(showFav))
    private val notice = NewEpisodesNotice("e3-5", 3, 5, 2, detectedMs = 50L)
    private val settings = mapOf(
        NewEpisodes.noticeKey("s1", "ser1") to notice.encode(),
        FollowedTeams.KEY to FollowedTeams.encode(listOf(FollowedTeam("n:chiefs", "Chiefs", null))),
    )
    private val nextEp = Episode(src1, RemoteId("e4"), RemoteId("up"), 1, 4, "E4", null, 60, null, "mp4", null)

    fun catalog() = ScopedCatalog(
        posters, channels = mapOf(channel to channelRow),
        recentVod = recentVod, recentSeries = recentSeries, similar = similar,
    )

    fun userData() = ScopedUserData(
        progress, favorites = favorites, completedShows = completed, queue = queue,
        collections = collections, collectionItems = collectionItems, initialSettings = settings,
    )

    fun epg() = ScopedEpg()
    fun upNext() = NextEpisodeFinder { _, _ -> nextEp }
}
