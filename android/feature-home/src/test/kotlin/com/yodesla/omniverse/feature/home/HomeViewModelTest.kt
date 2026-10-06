package com.yodesla.omniverse.feature.home

import androidx.paging.PagingSource
import com.yodesla.omniverse.core.data.CatalogRepository
import com.yodesla.omniverse.core.data.ChannelRow
import com.yodesla.omniverse.core.data.Clock
import com.yodesla.omniverse.core.data.CompletedShow
import com.yodesla.omniverse.core.data.EpgRepository
import com.yodesla.omniverse.core.data.NextEpisodeFinder
import com.yodesla.omniverse.core.data.NewEpisodes
import com.yodesla.omniverse.core.data.NewEpisodesNotice
import com.yodesla.omniverse.core.data.NowNext
import com.yodesla.omniverse.core.data.PosterRow
import com.yodesla.omniverse.core.data.Progress
import com.yodesla.omniverse.core.data.ShowEverything
import com.yodesla.omniverse.core.data.UserDataRepository
import com.yodesla.omniverse.core.data.Visibility
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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

private val src1 = SourceId("s1")
private val src2 = SourceId("s2")

private fun vodKey(source: SourceId, id: String) = ContentKey(source, ContentKind.VOD, RemoteId(id))
private fun seriesKey(source: SourceId, id: String) = ContentKey(source, ContentKind.SERIES, RemoteId(id))
private fun liveKey(source: SourceId, id: String) = ContentKey(source, ContentKind.LIVE, RemoteId(id))

private fun poster(
    key: ContentKey,
    name: String,
    tmdbId: String? = null,
    categoryId: RemoteId? = null,
): PosterRow = PosterRow(key, name, null, null, null, categoryId, tmdbId)

private fun HomeState.rowOrNull(id: String): HomeRow? = rows.firstOrNull { it.id == id }

private fun HomeState.allOpens(): List<ContentKey> = rows.flatMap { row -> row.cards.map { it.open } }

/**
 * Task 101: Home builds its rows on an injected dispatcher instead of the main thread, so the tests
 * inject one that shares [runTest]'s scheduler — that keeps `advanceUntilIdle()` in charge of exactly
 * when the rows get built, and proves nothing in the row-building chain runs on Main.
 */
private fun TestScope.homeVm(
    catalog: CatalogRepository,
    epg: EpgRepository,
    userData: UserDataRepository,
    clock: Clock,
    visibility: Flow<Visibility> = ShowEverything,
    upNext: NextEpisodeFinder? = null,
    tmdb: com.yodesla.omniverse.core.data.metadata.TmdbEnricher? = null,
    sourceAlert: Flow<com.yodesla.omniverse.core.data.SourceAlert?>? = null,
): HomeViewModel = HomeViewModel(
    catalog, epg, userData, clock, visibility, upNext, tmdb,
    sourceAlert = sourceAlert,
    compute = StandardTestDispatcher(testScheduler),
)

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun continueWatchingCollapsesSameTmdbIdToMostRecentCard() = runTest(dispatcher) {
        val keyA = vodKey(src1, "a")
        val keyB = vodKey(src2, "b")
        val posters = mapOf(
            keyA to poster(keyA, "Movie (source 1)", tmdbId = "tt123"),
            keyB to poster(keyB, "Movie (source 2)", tmdbId = "tt123"),
        )
        // continueWatching is most-recent-first: keyA is the newer watch.
        val progress = listOf(
            Progress(keyA, null, 1_000L, 2_000L, updatedMs = 200L),
            Progress(keyB, null, 1_000L, 2_000L, updatedMs = 100L),
        )
        val vm = homeVm(HomeFakeCatalog(posters), HomeFakeEpg(), HomeFakeUserData(progress), Clock { 0L })
        advanceUntilIdle()

        val row = assertNotNull(vm.state.value.rowOrNull("continue"))
        assertEquals(1, row.cards.size)
        assertEquals(keyA, row.cards.first().open)
    }

    @Test
    fun continueWatchingKeepsSeparateCardsWithoutTmdbId() = runTest(dispatcher) {
        val keyA = vodKey(src1, "a")
        val keyB = vodKey(src2, "b")
        val posters = mapOf(
            keyA to poster(keyA, "Movie A", tmdbId = null),
            keyB to poster(keyB, "Movie B", tmdbId = "   "),
        )
        val progress = listOf(
            Progress(keyA, null, 1_000L, 2_000L, updatedMs = 200L),
            Progress(keyB, null, 1_000L, 2_000L, updatedMs = 100L),
        )
        val vm = homeVm(HomeFakeCatalog(posters), HomeFakeEpg(), HomeFakeUserData(progress), Clock { 0L })
        advanceUntilIdle()

        val row = assertNotNull(vm.state.value.rowOrNull("continue"))
        assertEquals(2, row.cards.size)
        assertEquals(setOf(keyA, keyB), row.cards.map { it.open }.toSet())
    }

    @Test
    fun continueWatchingKeysAreUniqueAcrossSourcesAndKinds() = runTest(dispatcher) {
        val movieA = vodKey(src1, "123")
        val movieB = vodKey(src2, "123")
        val episode = ContentKey(src1, ContentKind.EPISODE, RemoteId("123"))
        val show = seriesKey(src1, "show")
        val posters = mapOf(movieA to poster(movieA, "Movie A"), movieB to poster(movieB, "Movie B"), show to poster(show, "Show"))
        val progress = listOf(
            Progress(movieA, null, 1_000L, 2_000L, 300L),
            Progress(movieB, null, 1_000L, 2_000L, 200L),
            Progress(episode, show.remoteId, 1_000L, 2_000L, 100L),
        )
        val vm = homeVm(HomeFakeCatalog(posters), HomeFakeEpg(), HomeFakeUserData(progress), Clock { 0L })
        advanceUntilIdle()
        val cards = assertNotNull(vm.state.value.rowOrNull("continue")).cards
        assertEquals(3, cards.size)
        assertEquals(3, cards.map { it.id }.distinct().size)
    }

    @Test
    fun myListDoesNotMergeMoviesWithShowsSharingIds() = runTest(dispatcher) {
        val movie = vodKey(src1, "123")
        val show = seriesKey(src1, "123")
        for (tmdb in listOf("456", null)) {
            val posters = mapOf(movie to poster(movie, "Movie", tmdb), show to poster(show, "Show", tmdb))
            val vm = homeVm(HomeFakeCatalog(posters), HomeFakeEpg(), HomeFakeUserData(favorites = listOf(movie, show)), Clock { 0L })
            advanceUntilIdle()
            val cards = assertNotNull(vm.state.value.rowOrNull("mylist")).cards
            assertEquals(setOf(movie, show), cards.map { it.open }.toSet())
            assertEquals(2, cards.map { it.id }.distinct().size)
        }
    }

    @Test
    fun myListRowHoldsVodAndSeriesFavoritesDedupedByTmdbId() = runTest(dispatcher) {
        val favVod1 = vodKey(src1, "v1")
        val favVod2 = vodKey(src1, "v2")
        val favSeries = seriesKey(src1, "s1")
        val favLive = liveKey(src1, "c1")
        val channel = ChannelRow(favLive, 1, "Channel One", null, null, 0, RemoteId("cat"))
        val posters = mapOf(
            favVod1 to poster(favVod1, "Movie One", tmdbId = "tt1"),
            favVod2 to poster(favVod2, "Movie One (dup)", tmdbId = "tt1"),
            favSeries to poster(favSeries, "Show One", tmdbId = "tt2"),
        )
        val favorites = listOf(favVod1, favVod2, favSeries, favLive)
        val vm = homeVm(
            HomeFakeCatalog(posters, channels = mapOf(favLive to channel)),
            HomeFakeEpg(),
            HomeFakeUserData(favorites = favorites),
            Clock { 0L },
        )
        advanceUntilIdle()

        val mylist = assertNotNull(vm.state.value.rowOrNull("mylist"))
        assertEquals(2, mylist.cards.size)
        assertTrue(mylist.cards.none { it.open == favLive })
        assertEquals(setOf(favVod1, favSeries), mylist.cards.map { it.open }.toSet())

        val fav = assertNotNull(vm.state.value.rowOrNull("fav"))
        assertTrue(fav.cards.any { it.open == favLive })
    }

    @Test
    fun posterInHiddenCategoryAppearsInNeitherContinueNorMyList() = runTest(dispatcher) {
        val visibleKey = vodKey(src1, "v")
        val hiddenKey = vodKey(src1, "h")
        val posters = mapOf(
            visibleKey to poster(visibleKey, "Kids Movie", tmdbId = "ttv", categoryId = RemoteId("kids")),
            hiddenKey to poster(hiddenKey, "Adult Movie", tmdbId = "tth", categoryId = RemoteId("adult")),
        )
        val progress = listOf(
            Progress(visibleKey, null, 1_000L, 2_000L, updatedMs = 200L),
            Progress(hiddenKey, null, 1_000L, 2_000L, updatedMs = 100L),
        )
        val favorites = listOf(visibleKey, hiddenKey)
        val visibility: Flow<Visibility> = flowOf { _, _, categoryId -> categoryId != "adult" }
        val vm = homeVm(
            HomeFakeCatalog(posters),
            HomeFakeEpg(),
            HomeFakeUserData(progress, favorites),
            Clock { 0L },
            visibility,
        )
        advanceUntilIdle()

        val state = vm.state.value
        assertNotNull(state.rowOrNull("continue"))
        assertNotNull(state.rowOrNull("mylist"))
        val opens = state.allOpens()
        assertTrue(opens.contains(visibleKey))
        assertTrue(opens.none { it == hiddenKey })
    }

    @Test
    fun episodeProgressSubtitleUsesTimeLeftLabel() = runTest(dispatcher) {
        val episode = ContentKey(src1, ContentKind.EPISODE, RemoteId("e1"))
        val parent = RemoteId("ser1")
        val series = seriesKey(src1, "ser1")
        val posters = mapOf(series to poster(series, "Show One", tmdbId = "tts"))
        // 10 min watched of a 34 min episode -> 24 min left.
        val progress = listOf(Progress(episode, parent, 10L * 60_000L, 34L * 60_000L, updatedMs = 100L))
        val vm = homeVm(HomeFakeCatalog(posters), HomeFakeEpg(), HomeFakeUserData(progress), Clock { 0L })
        advanceUntilIdle()

        val row = assertNotNull(vm.state.value.rowOrNull("continue"))
        assertEquals(1, row.cards.size)
        assertEquals("Episode  ·  24 min left", row.cards.first().subtitle)
    }

    @Test
    fun completedEpisodeRollsTheShowForwardToAnUpNextCard() = runTest(dispatcher) {
        val series = seriesKey(src1, "ser1")
        val posters = mapOf(series to poster(series, "Show One", tmdbId = "tts"))
        val done = CompletedShow(series, ContentKey(src1, ContentKind.EPISODE, RemoteId("e3")), updatedMs = 100L)
        val nextEp = Episode(src1, RemoteId("e4"), RemoteId("ser1"), 2, 4, "E4", null, 60, null, "mp4", null)
        val vm = homeVm(
            HomeFakeCatalog(posters),
            HomeFakeEpg(),
            HomeFakeUserData(completedShows = listOf(done)),
            Clock { 0L },
            upNext = NextEpisodeFinder { _, _ -> nextEp },
        )
        advanceUntilIdle()

        val row = assertNotNull(vm.state.value.rowOrNull("continue"))
        assertEquals(1, row.cards.size)
        assertEquals(series, row.cards.first().open)
        assertEquals("Up next  ·  S2 · E4", row.cards.first().subtitle)
    }

    @Test
    fun spoilerFreeModeDropsTheEpisodeIdentityFromUpNextCards() = runTest(dispatcher) {
        val series = seriesKey(src1, "ser1")
        val posters = mapOf(series to poster(series, "Show One", tmdbId = "tts"))
        val done = CompletedShow(series, ContentKey(src1, ContentKind.EPISODE, RemoteId("e3")), updatedMs = 100L)
        val nextEp = Episode(src1, RemoteId("e4"), RemoteId("ser1"), 2, 4, "E4", null, 60, null, "mp4", null)
        val vm = HomeViewModel(
            HomeFakeCatalog(posters),
            HomeFakeEpg(),
            HomeFakeUserData(completedShows = listOf(done), initialSettings = mapOf("spoiler_free" to "true")),
            Clock { 0L },
            upNext = NextEpisodeFinder { _, _ -> nextEp },
            compute = StandardTestDispatcher(testScheduler),
        )
        advanceUntilIdle()

        val row = assertNotNull(vm.state.value.rowOrNull("continue"))
        assertEquals("Up next", row.cards.first().subtitle)
    }

    @Test
    fun finishedLastEpisodeOfAShowLeavesNoContinueCard() = runTest(dispatcher) {
        val series = seriesKey(src1, "ser1")
        val posters = mapOf(series to poster(series, "Show One", tmdbId = "tts"))
        val done = CompletedShow(series, ContentKey(src1, ContentKind.EPISODE, RemoteId("e24")), updatedMs = 100L)
        val vm = homeVm(
            HomeFakeCatalog(posters),
            HomeFakeEpg(),
            HomeFakeUserData(completedShows = listOf(done)),
            Clock { 0L },
            upNext = NextEpisodeFinder { _, _ -> null }, // season finale: nothing up next
        )
        advanceUntilIdle()

        assertEquals(null, vm.state.value.rowOrNull("continue"))
    }

    @Test
    fun inProgressShowStaysInContinueWatchingEvenWhenEarlierEpisodesFinished() = runTest(dispatcher) {
        val series = seriesKey(src1, "ser1")
        val posters = mapOf(series to poster(series, "Show One", tmdbId = "tts"))
        val episode = ContentKey(src1, ContentKind.EPISODE, RemoteId("e4"))
        val progress = listOf(Progress(episode, RemoteId("ser1"), 10L * 60_000L, 34L * 60_000L, updatedMs = 200L))
        val done = CompletedShow(series, ContentKey(src1, ContentKind.EPISODE, RemoteId("e3")), updatedMs = 100L)
        val nextEp = Episode(src1, RemoteId("e4"), RemoteId("ser1"), 2, 4, "E4", null, 60, null, "mp4", null)
        val vm = homeVm(
            HomeFakeCatalog(posters),
            HomeFakeEpg(),
            HomeFakeUserData(progress, completedShows = listOf(done)),
            Clock { 0L },
            upNext = NextEpisodeFinder { _, _ -> nextEp },
        )
        advanceUntilIdle()

        // The partial watch is newer, so the show appears once, as the in-progress card.
        val row = assertNotNull(vm.state.value.rowOrNull("continue"))
        assertEquals(1, row.cards.size)
        assertEquals("Episode  ·  24 min left", row.cards.first().subtitle)
    }

    @Test
    fun becauseYouWatchedRowUsesMostRecentSeedTitleAndSitsAfterContinueWatching() = runTest(dispatcher) {
        val seed = vodKey(src1, "a")
        val posters = mapOf(seed to poster(seed, "Seed Movie", tmdbId = "tta"))
        val similar = mapOf(seed to (1..4).map { i -> poster(vodKey(src1, "b$i"), "Similar $i") })
        val progress = listOf(Progress(seed, null, 1_000L, 2_000L, updatedMs = 100L))
        val vm = homeVm(
            HomeFakeCatalog(posters, similar = similar.toMap()),
            HomeFakeEpg(),
            HomeFakeUserData(progress),
            Clock { 0L },
        )
        advanceUntilIdle()

        val state = vm.state.value
        val row = assertNotNull(state.rowOrNull("byw-VOD-s1-a"))
        assertEquals("Because you watched Seed Movie", row.title)
        assertEquals(4, row.cards.size)
        assertTrue(row.cards.all { it.section == row.title })
        assertTrue(row.cards.none { it.open == seed })
        assertEquals(state.rows.indexOfFirst { it.id == "continue" } + 1, state.rows.indexOfFirst { it.id == "byw-VOD-s1-a" })
    }

    @Test
    fun becauseYouWatchedRowDropsCardsTheVisibilityFunctionHides() = runTest(dispatcher) {
        val seed = vodKey(src1, "a")
        val hidden = vodKey(src1, "adult1")
        val posters = mapOf(seed to poster(seed, "Seed Movie", tmdbId = "tta"))
        val similar = mapOf(
            seed to listOf(
                poster(vodKey(src1, "b1"), "Similar 1"),
                poster(vodKey(src1, "b2"), "Similar 2"),
                poster(vodKey(src1, "b3"), "Similar 3"),
                poster(hidden, "Adult Similar", categoryId = RemoteId("adult")),
                poster(vodKey(src1, "b4"), "Similar 4"),
            ),
        )
        val progress = listOf(Progress(seed, null, 1_000L, 2_000L, updatedMs = 100L))
        val visibility: Flow<Visibility> = flowOf { _, _, categoryId -> categoryId != "adult" }
        val vm = homeVm(
            HomeFakeCatalog(posters, similar = similar),
            HomeFakeEpg(),
            HomeFakeUserData(progress),
            Clock { 0L },
            visibility,
        )
        advanceUntilIdle()

        val row = assertNotNull(vm.state.value.rowOrNull("byw-VOD-s1-a"))
        assertEquals(4, row.cards.size)
        assertTrue(row.cards.none { it.open == hidden })
    }

    @Test
    fun becauseYouWatchedExcludesFinishedMoviesThatAreNotInContinueWatching() = runTest(dispatcher) {
        val seed = vodKey(src1, "a")
        val finished = vodKey(src1, "c")
        val posters = mapOf(seed to poster(seed, "Seed Movie", tmdbId = "tta"), finished to poster(finished, "Finished Movie"))
        val similar = mapOf(seed to (1..4).map { i -> poster(vodKey(src1, "b$i"), "Similar $i") } + poster(finished, "Finished Movie"))
        val cw = listOf(Progress(seed, null, 1_000L, 2_000L, updatedMs = 100L))
        // A finished watch: saved progress, but never in Continue Watching.
        val saved = cw + Progress(finished, null, 2_000L, 2_000L, updatedMs = 90L, completed = true)
        val vm = homeVm(
            HomeFakeCatalog(posters, similar = similar),
            HomeFakeEpg(),
            HomeFakeUserData(cw, saved = saved),
            Clock { 0L },
        )
        advanceUntilIdle()

        val row = assertNotNull(vm.state.value.rowOrNull("byw-VOD-s1-a"))
        assertEquals(4, row.cards.size)
        assertTrue(row.cards.none { it.open == finished }, "a completed movie outside the CW row must still be excluded (task 63, M11)")
    }

    @Test
    fun becauseYouWatchedExcludesProgressBeyondTheContinueWatchingLimit() = runTest(dispatcher) {
        val seed = vodKey(src1, "a")
        val deep = vodKey(src1, "deep")
        val posters = mapOf(seed to poster(seed, "Seed Movie", tmdbId = "tta"), deep to poster(deep, "Deep Watch"))
        val similar = mapOf(seed to (1..4).map { i -> poster(vodKey(src1, "b$i"), "Similar $i") } + poster(deep, "Deep Watch"))
        val cw = listOf(Progress(seed, null, 1_000L, 2_000L, updatedMs = 300L)) +
            (1..20).map { i -> Progress(vodKey(src1, "f$i"), null, 1_000L, 2_000L, updatedMs = 200L - i) }
        // 'deep' is the 21st saved title: outside the 20-item CW row, but still saved progress.
        val saved = cw + Progress(deep, null, 1_000L, 2_000L, updatedMs = 50L)
        val vm = homeVm(
            HomeFakeCatalog(posters, similar = similar),
            HomeFakeEpg(),
            HomeFakeUserData(cw.take(20), saved = saved),
            Clock { 0L },
        )
        advanceUntilIdle()

        val row = assertNotNull(vm.state.value.rowOrNull("byw-VOD-s1-a"))
        assertEquals(4, row.cards.size)
        assertTrue(row.cards.none { it.open == deep }, "progress beyond the CW limit must still be excluded (task 63, M11)")
    }

    // Task 97 — Play next: the viewer's queue is its own row, in queue order, after Continue watching.

    @Test
    fun playNextRowSitsAfterContinueWatchingInQueueOrder() = runTest(dispatcher) {
        val first = vodKey(src1, "q1")
        val second = seriesKey(src1, "q2")
        val cont = vodKey(src1, "cw")
        val posters = mapOf(
            cont to poster(cont, "Watching"),
            first to poster(first, "Queued One"),
            second to poster(second, "Queued Two"),
        )
        val progress = listOf(Progress(cont, null, 1_000L, 2_000L, updatedMs = 100L))
        val vm = HomeViewModel(
            HomeFakeCatalog(posters), HomeFakeEpg(),
            HomeFakeUserData(progress, queue = listOf(first, second)), Clock { 0L },
            compute = StandardTestDispatcher(testScheduler),
        )
        advanceUntilIdle()

        val ids = vm.state.value.rows.map { it.id }
        assertEquals(ids.indexOfFirst { it == "continue" } + 1, ids.indexOfFirst { it == "playnext" })
        val row = assertNotNull(vm.state.value.rowOrNull("playnext"))
        assertEquals("Play next", row.title)
        assertEquals(listOf(first, second), row.cards.map { it.open })
        assertTrue(row.cards.all { it.section == "Play next" })
    }

    @Test
    fun playNextRowDropsHiddenTitlesAndToggleUpdatesIt() = runTest(dispatcher) {
        val ok = vodKey(src1, "ok")
        val hidden = vodKey(src1, "adult")
        val posters = mapOf(
            ok to poster(ok, "Queued Movie", categoryId = RemoteId("kids")),
            hidden to poster(hidden, "Adult Movie", categoryId = RemoteId("adult")),
        )
        val visibility: Flow<Visibility> = flowOf { _, _, categoryId -> categoryId != "adult" }
        val vm = HomeViewModel(
            HomeFakeCatalog(posters), HomeFakeEpg(),
            HomeFakeUserData(queue = listOf(ok, hidden)), Clock { 0L }, visibility,
            compute = StandardTestDispatcher(testScheduler),
        )
        advanceUntilIdle()

        assertEquals(listOf(ok), assertNotNull(vm.state.value.rowOrNull("playnext")).cards.map { it.open })

        vm.togglePlayNext(ok, currentlyQueued = true)
        advanceUntilIdle()
        assertEquals(null, vm.state.value.rowOrNull("playnext"))

        vm.togglePlayNext(ok, currentlyQueued = false)
        advanceUntilIdle()
        assertEquals(listOf(ok), assertNotNull(vm.state.value.rowOrNull("playnext")).cards.map { it.open })
    }

    @Test
    fun moveQueuedReordersThePlayNextRow() = runTest(dispatcher) {
        val a = vodKey(src1, "qa")
        val b = vodKey(src1, "qb")
        val posters = mapOf(a to poster(a, "First"), b to poster(b, "Second"))
        val vm = HomeViewModel(
            HomeFakeCatalog(posters), HomeFakeEpg(),
            HomeFakeUserData(queue = listOf(a, b)), Clock { 0L },
            compute = StandardTestDispatcher(testScheduler),
        )
        advanceUntilIdle()
        vm.moveQueued(b, up = true)
        advanceUntilIdle()
        assertEquals(listOf(b, a), assertNotNull(vm.state.value.rowOrNull("playnext")).cards.map { it.open })
    }

    @Test
    fun playNextIsAFixedHomeRowTypeThatLayoutCanOrderAndHide() {
        assertEquals("playnext", homeRowType("playnext"))
        val rows = listOf(
            HomeRow("continue", "Continue watching", emptyList()),
            HomeRow("playnext", "Play next", emptyList()),
            HomeRow("mylist", "My list", emptyList()),
        )
        val layout = HomeLayout(order = listOf("playnext", "continue"), hidden = setOf("mylist"))
        assertEquals(listOf("playnext", "continue"), applyHomeLayout(rows, layout).map { it.id })
        val entry = assertNotNull(computeHomePanel(rows, HomeLayout()).firstOrNull { it.type == "playnext" })
        assertTrue(entry.hideable, "Play next must be hideable like every row except Continue watching")
    }

    // Task 65 — Customize Home: the saved layout reorders/hides rows as the viewer arranged them.

    private fun customizeCatalog(): HomeFakeCatalog {
        val cont = vodKey(src1, "cw"); val ml = vodKey(src1, "ml"); val live = liveKey(src1, "ch")
        val movie = vodKey(src1, "mv"); val show = seriesKey(src1, "sh")
        val channel = ChannelRow(live, 1, "Channel One", null, null, 0, RemoteId("cat"))
        return HomeFakeCatalog(
            mapOf(
                cont to poster(cont, "Watching"), ml to poster(ml, "Saved"),
                movie to poster(movie, "New Movie"), show to poster(show, "New Show"),
            ),
            channels = mapOf(live to channel),
            recentVod = listOf(poster(movie, "New Movie")),
            recentSeries = listOf(poster(show, "New Show")),
        )
    }

    private fun customizeUserData(layout: String?) = HomeFakeUserData(
        progress = listOf(Progress(vodKey(src1, "cw"), null, 1_000L, 2_000L, updatedMs = 100L)),
        favorites = listOf(vodKey(src1, "ml"), liveKey(src1, "ch")),
        initialSettings = layout?.let { mapOf("home_layout" to it) }.orEmpty(),
    )

    @Test
    fun homeLayoutReordersAndHidesRows() = runTest(dispatcher) {
        val layout = "order=shows,movies,mylist,fav,continue\nhidden=fav"
        val vm = homeVm(customizeCatalog(), HomeFakeEpg(), customizeUserData(layout), Clock { 0L })
        advanceUntilIdle()
        // Saved order wins; "fav" is hidden; "continue" stays even though it is last in the order.
        assertEquals(listOf("shows", "movies", "mylist", "continue"), vm.state.value.rows.map { it.id })
    }

    @Test
    fun continueWatchingRowCanNeverBeHidden() = runTest(dispatcher) {
        val layout = "order=continue\nhidden=continue,movies"
        val vm = homeVm(customizeCatalog(), HomeFakeEpg(), customizeUserData(layout), Clock { 0L })
        advanceUntilIdle()
        val ids = vm.state.value.rows.map { it.id }
        assertTrue(ids.contains("continue"), "Continue watching must survive being marked hidden")
        assertTrue(!ids.contains("movies"), "a hidden row type must drop")
    }

    @Test
    fun unknownRowTypeInSavedOrderKeepsDefaultPositions() = runTest(dispatcher) {
        // "ghost-collection" is not a row that exists; unlisted rows keep their default relative order.
        val layout = "order=shows,ghost-collection,continue\nhidden="
        val vm = homeVm(customizeCatalog(), HomeFakeEpg(), customizeUserData(layout), Clock { 0L })
        advanceUntilIdle()
        assertEquals(listOf("shows", "continue", "fav", "mylist", "movies"), vm.state.value.rows.map { it.id })
    }

    @Test
    fun resetHomeLayoutRestoresDefaultOrderAndShowsHiddenRows() = runTest(dispatcher) {
        val layout = "order=shows,movies,mylist,fav,continue\nhidden=movies"
        val vm = homeVm(customizeCatalog(), HomeFakeEpg(), customizeUserData(layout), Clock { 0L })
        advanceUntilIdle()
        assertEquals(listOf("shows", "mylist", "fav", "continue"), vm.state.value.rows.map { it.id })
        vm.resetHomeLayout()
        advanceUntilIdle()
        assertEquals(listOf("continue", "fav", "mylist", "movies", "shows"), vm.state.value.rows.map { it.id })
    }

    // Task 101 — cold start: building the rows (a poster lookup per card plus the layout maths) must
    // not run on the main thread, or it costs the frames Home is trying to draw.

    @Test
    fun homeRowsAreBuiltOnTheComputeDispatcherNotOnMain() = runTest(dispatcher) {
        val key = vodKey(src1, "a")
        val posters = mapOf(key to poster(key, "Movie A", tmdbId = "tt1"))
        val progress = listOf(Progress(key, null, 1_000L, 2_000L, updatedMs = 200L))
        val vm = homeVm(HomeFakeCatalog(posters), HomeFakeEpg(), HomeFakeUserData(progress), Clock { 0L })
        // Main is unconfined in these tests, so anything built on it has already run by now.
        assertTrue(!vm.state.value.loaded, "the row build must not run on the main dispatcher")
        advanceUntilIdle()
        assertTrue(vm.state.value.loaded)
        assertNotNull(vm.state.value.rowOrNull("continue"))
    }

    @Test
    fun rowsLandFromLocalDataAloneWithNothingToSync() = runTest(dispatcher) {
        val key = vodKey(src1, "a")
        val posters = mapOf(key to poster(key, "Movie A", tmdbId = "tt1"))
        val progress = listOf(Progress(key, null, 1_000L, 2_000L, updatedMs = 200L))
        val vm = homeVm(HomeFakeCatalog(posters), HomeFakeEpg(), HomeFakeUserData(progress), Clock { 0L })
        advanceUntilIdle()
        // No source, no sync, no network in this graph: the cached rows are Home's first paint.
        assertEquals(listOf("continue"), vm.state.value.rows.map { it.id })
        assertEquals(key, vm.state.value.rows.single().cards.single().open)
    }

    // Task 71 — New episodes: shows the viewer watches whose post-sync snapshot flagged an unseen episode.

    private fun TestScope.newEpisodesVm(
        posters: Map<ContentKey, PosterRow>,
        progress: List<Progress>,
        notice: NewEpisodesNotice?,
        favorites: List<ContentKey> = emptyList(),
        nowMs: Long = 1_000L,
        visibility: Flow<Visibility> = ShowEverything,
    ): HomeViewModel {
        val settings = notice?.let { mapOf(NewEpisodes.noticeKey("s1", "ser1") to it.encode()) }.orEmpty()
        return homeVm(
            HomeFakeCatalog(posters),
            HomeFakeEpg(),
            HomeFakeUserData(progress, favorites = favorites, initialSettings = settings),
            Clock { nowMs },
            visibility,
        )
    }

    private val watchedEpisode = Progress(ContentKey(src1, ContentKind.EPISODE, RemoteId("e1-3")), RemoteId("ser1"), 1_000L, 2_000L, updatedMs = 100L)

    @Test
    fun newEpisodesRowSitsRightAfterContinueWatchingAndNamesTheUnseenEpisode() = runTest(dispatcher) {
        val series = seriesKey(src1, "ser1")
        val posters = mapOf(series to poster(series, "Show One", tmdbId = "tts"))
        val vm = newEpisodesVm(posters, listOf(watchedEpisode), NewEpisodesNotice("e3-5", 3, 5, 1, detectedMs = 50L))
        advanceUntilIdle()
        val ids = vm.state.value.rows.map { it.id }
        val row = assertNotNull(vm.state.value.rowOrNull("newEpisodes"))
        assertEquals("New episodes", row.title)
        assertEquals(ids.indexOfFirst { it == "continue" } + 1, ids.indexOfFirst { it == "newEpisodes" })
        val card = row.cards.single()
        assertEquals(series, card.open)
        assertEquals("New · S3 · E5", card.subtitle)
        assertEquals(RemoteId("e3-5"), card.focusEpisode)
        assertEquals("New episodes", card.section)
    }

    @Test
    fun severalUnseenEpisodesCountThemselvesOnTheCard() = runTest(dispatcher) {
        val series = seriesKey(src1, "ser1")
        val posters = mapOf(series to poster(series, "Show One", tmdbId = "tts"))
        val vm = newEpisodesVm(posters, listOf(watchedEpisode), NewEpisodesNotice("e3-5", 3, 5, 3, detectedMs = 50L))
        advanceUntilIdle()
        assertEquals("3 new episodes", assertNotNull(vm.state.value.rowOrNull("newEpisodes")).cards.single().subtitle)
    }

    @Test
    fun theCardDropsOnceTheViewerHasProgressOnTheFlaggedEpisode() = runTest(dispatcher) {
        val series = seriesKey(src1, "ser1")
        val posters = mapOf(series to poster(series, "Show One", tmdbId = "tts"))
        val seen = Progress(ContentKey(src1, ContentKind.EPISODE, RemoteId("e3-5")), RemoteId("ser1"), 1_000L, 2_000L, updatedMs = 200L)
        val vm = newEpisodesVm(posters, listOf(seen), NewEpisodesNotice("e3-5", 3, 5, 1, detectedMs = 50L))
        advanceUntilIdle()
        assertEquals(null, vm.state.value.rowOrNull("newEpisodes"))
    }

    @Test
    fun theCardExpiresFourteenDaysAfterTheEpisodeWasFirstFlagged() = runTest(dispatcher) {
        val series = seriesKey(src1, "ser1")
        val posters = mapOf(series to poster(series, "Show One", tmdbId = "tts"))
        val fresh = newEpisodesVm(posters, listOf(watchedEpisode), NewEpisodesNotice("e3-5", 3, 5, 1, detectedMs = 1_000L - NewEpisodes.NOTICE_TTL_MS + 1L))
        advanceUntilIdle()
        assertNotNull(fresh.state.value.rowOrNull("newEpisodes"))
        val stale = newEpisodesVm(posters, listOf(watchedEpisode), NewEpisodesNotice("e3-5", 3, 5, 1, detectedMs = 1_000L - NewEpisodes.NOTICE_TTL_MS - 1L))
        advanceUntilIdle()
        assertEquals(null, stale.state.value.rowOrNull("newEpisodes"))
    }

    @Test
    fun newEpisodesRowRespectsBrowseVisibility() = runTest(dispatcher) {
        val series = seriesKey(src1, "ser1")
        val posters = mapOf(series to poster(series, "Show One", categoryId = RemoteId("adult")))
        val vm = newEpisodesVm(posters, listOf(watchedEpisode), NewEpisodesNotice("e3-5", 3, 5, 1, detectedMs = 50L),
            visibility = flowOf { _, _, categoryId -> categoryId != "adult" })
        advanceUntilIdle()
        assertEquals(null, vm.state.value.rowOrNull("newEpisodes"))
    }

    @Test
    fun aMyListShowGetsANewEpisodesCardWithoutAnyProgress() = runTest(dispatcher) {
        val series = seriesKey(src1, "ser1")
        val posters = mapOf(series to poster(series, "Show One", tmdbId = "tts"))
        val vm = newEpisodesVm(posters, emptyList(), NewEpisodesNotice("e3-5", 3, 5, 2, detectedMs = 50L), favorites = listOf(series))
        advanceUntilIdle()
        val row = assertNotNull(vm.state.value.rowOrNull("newEpisodes"))
        assertEquals("2 new episodes", row.cards.single().subtitle)
        assertEquals(null, vm.state.value.rowOrNull("continue"))
    }

    @Test
    fun aShowTheViewerDoesNotWatchGetsNoCardEvenWithAStoredNotice() = runTest(dispatcher) {
        val series = seriesKey(src1, "ser1")
        val posters = mapOf(series to poster(series, "Show One", tmdbId = "tts"))
        val old = Progress(ContentKey(src1, ContentKind.EPISODE, RemoteId("e1-3")), RemoteId("ser1"), 1_000L, 2_000L, updatedMs = 1_000L - NewEpisodes.WATCH_WINDOW_MS - 1L)
        val vm = newEpisodesVm(posters, listOf(old), NewEpisodesNotice("e3-5", 3, 5, 1, detectedMs = 50L))
        advanceUntilIdle()
        assertEquals(null, vm.state.value.rowOrNull("newEpisodes"))
    }

    @Test
    fun newEpisodesIsAFixedHomeRowTypeThatLayoutCanOrderAndHide() {
        assertEquals("newEpisodes", homeRowType("newEpisodes"))
        val rows = listOf(
            HomeRow("continue", "Continue watching", emptyList()),
            HomeRow("newEpisodes", "New episodes", emptyList()),
            HomeRow("shows", "Recently added shows", emptyList()),
        )
        val layout = HomeLayout(order = listOf("newEpisodes", "continue"), hidden = setOf("shows"))
        assertEquals(listOf("newEpisodes", "continue"), applyHomeLayout(rows, layout).map { it.id })
        val entry = assertNotNull(computeHomePanel(rows, HomeLayout()).firstOrNull { it.type == "newEpisodes" })
        assertTrue(entry.hideable, "only Continue watching is un-hideable")
    }

    @Test
    fun surpriseMeFollowsWatchedGenresSkipsProgressAndNeverRepeats() = runTest(dispatcher) {
        val watchedKey = vodKey(src1, "w")
        val posters = mapOf(
            watchedKey to PosterRow(watchedKey, "Watched Action", null, 2019, 8f, null, "tt1", genre = "Action"),
        )
        val recent = listOf(
            PosterRow(vodKey(src1, "a"), "Action Fresh", null, 2019, 7.8f, null, "tt2", genre = "Action"),
            PosterRow(vodKey(src1, "b"), "Comedy Fresh", null, 2019, 9.4f, null, "tt3", genre = "Comedy"),
            PosterRow(watchedKey, "Watched Action", null, 2019, 8f, null, "tt1", genre = "Action"),
        )
        val progress = listOf(Progress(watchedKey, null, 1_000L, 2_000L, updatedMs = 100L))
        val vm = HomeViewModel(HomeFakeCatalog(posters, recentVod = recent), HomeFakeEpg(), HomeFakeUserData(progress), Clock { 0L }, compute = StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        vm.surpriseMe()
        advanceUntilIdle()
        // The viewer watches Action, so the Action title wins despite the lower rating.
        assertEquals("Action Fresh", assertNotNull(vm.pick.value).poster?.name)

        vm.surpriseMe() // "Another": the session never repeats a title.
        advanceUntilIdle()
        assertEquals("Comedy Fresh", assertNotNull(vm.pick.value).poster?.name)

        vm.surpriseMe() // watched + already-offered leaves nothing.
        advanceUntilIdle()
        assertNull(assertNotNull(vm.pick.value).poster)
    }

    // Task 105: the source-health banner.
    @Test
    fun sourceAlertShowsOnHomeUntilDismissed() = runTest(dispatcher) {
        val alert = com.yodesla.omniverse.core.data.SourceAlert("s1", "IPTV", "Expires in 5 days", "s1|EXPIRING|20700")
        val userData = HomeFakeUserData()
        val vm = homeVm(HomeFakeCatalog(), HomeFakeEpg(), userData, Clock { 0L }, sourceAlert = flowOf(alert))
        advanceUntilIdle()
        val shown = assertNotNull(vm.state.value.alert)
        assertEquals("IPTV", shown.sourceName)
        assertEquals("Expires in 5 days", shown.text)

        vm.dismissSourceAlert()
        advanceUntilIdle()
        assertNull(vm.state.value.alert, "dismissed for this profile")
        assertEquals(
            alert.fingerprint,
            userData.setting(com.yodesla.omniverse.core.data.SourceHealthKeys.dismissed).first(),
            "the dismissal is stored so Home stays quiet after a restart",
        )
    }

    @Test
    fun healthySourcesGiveNoBanner() = runTest(dispatcher) {
        val vm = homeVm(HomeFakeCatalog(), HomeFakeEpg(), HomeFakeUserData(), Clock { 0L }, sourceAlert = flowOf(null))
        advanceUntilIdle()
        assertNull(vm.state.value.alert)
    }
}

private class HomeFakeCatalog(
    private val posters: Map<ContentKey, PosterRow> = emptyMap(),
    private val channels: Map<ContentKey, ChannelRow> = emptyMap(),
    private val recentVod: List<PosterRow> = emptyList(),
    private val recentSeries: List<PosterRow> = emptyList(),
    private val similar: Map<ContentKey, List<PosterRow>> = emptyMap(),
) : CatalogRepository {
    override suspend fun poster(key: ContentKey): PosterRow? = posters[key]
    override suspend fun channel(key: ContentKey): ChannelRow? = channels[key]
    override suspend fun similarTo(seed: ContentKey, limit: Int): List<PosterRow> = similar[seed]?.take(limit) ?: emptyList()
    override fun recentlyAdded(kind: ContentKind, limit: Int): Flow<List<PosterRow>> = when (kind) {
        ContentKind.VOD -> flowOf(recentVod)
        ContentKind.SERIES -> flowOf(recentSeries)
        else -> flowOf(emptyList())
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

private class HomeFakeUserData(
    private val progress: List<Progress> = emptyList(),
    private val favorites: List<ContentKey> = emptyList(),
    private val completedShows: List<CompletedShow> = emptyList(),
    private val saved: List<Progress> = progress,
    initialSettings: Map<String, String> = emptyMap(),
    queue: List<ContentKey> = emptyList(),
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
}

private class HomeFakeEpg : EpgRepository {
    override suspend fun nowNext(sourceId: SourceId, epgKeys: Collection<String>, atMs: Long): Map<String, NowNext> = emptyMap()
    override suspend fun programmes(sourceId: SourceId, epgKey: String, window: TimeWindow): List<ProgrammeRecord> = emptyList()
}
