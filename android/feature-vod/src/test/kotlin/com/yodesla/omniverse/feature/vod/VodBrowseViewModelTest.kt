package com.yodesla.omniverse.feature.vod

import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingSource.LoadResult
import androidx.paging.PagingState
import com.yodesla.omniverse.core.data.CatalogRepository
import com.yodesla.omniverse.core.data.ChannelRow
import com.yodesla.omniverse.core.data.CompletedShow
import com.yodesla.omniverse.core.data.PosterRow
import com.yodesla.omniverse.core.data.Progress
import com.yodesla.omniverse.core.data.ShowEverything
import com.yodesla.omniverse.core.data.SourceRepository
import com.yodesla.omniverse.core.data.LibraryCollection
import com.yodesla.omniverse.core.data.SmartCollectionFilter
import com.yodesla.omniverse.core.data.SourceSummary
import com.yodesla.omniverse.core.data.UserDataRepository
import com.yodesla.omniverse.core.data.Visibility
import com.yodesla.omniverse.core.data.metadata.RottenTomatoes
import com.yodesla.omniverse.core.data.metadata.RtScores
import com.yodesla.omniverse.core.net.HttpClient
import com.yodesla.omniverse.core.net.HttpResponse
import com.yodesla.omniverse.core.model.AccountInfo
import com.yodesla.omniverse.core.model.Capability
import com.yodesla.omniverse.core.model.Category
import com.yodesla.omniverse.core.model.ChannelRecord
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.Episode
import com.yodesla.omniverse.core.model.PlaybackRequest
import com.yodesla.omniverse.core.model.PlaybackSpec
import com.yodesla.omniverse.core.model.ProgrammeRecord
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.Season
import com.yodesla.omniverse.core.model.SeriesDetail
import com.yodesla.omniverse.core.model.SeriesRecord
import com.yodesla.omniverse.core.model.SourceId
import com.yodesla.omniverse.core.model.SourceKind
import com.yodesla.omniverse.core.model.TimeWindow
import com.yodesla.omniverse.core.model.VodDetail
import com.yodesla.omniverse.core.model.VodRecord
import com.yodesla.omniverse.core.model.mediaProgressKey
import com.yodesla.omniverse.core.source.ContentSource
import com.yodesla.omniverse.core.source.SourceConfig
import com.yodesla.omniverse.core.source.SyncDiagnostics
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okio.Buffer
import okio.BufferedSource

private val src = SourceId("s")
private val keyA = ContentKey(src, ContentKind.VOD, RemoteId("a1"))
private val keyB = ContentKey(src, ContentKind.VOD, RemoteId("b1"))
private val posterA = PosterRow(keyA, "Movie A", null, null, null, RemoteId("a"))
private val posterB = PosterRow(keyB, "Movie B", null, null, null, RemoteId("b"))
private val keyMovie = ContentKey(src, ContentKind.VOD, RemoteId("m1"))
private val keyMovie2 = ContentKey(src, ContentKind.VOD, RemoteId("m2"))
private val keySeries = ContentKey(src, ContentKind.SERIES, RemoteId("s1"))
private val keyEp1 = ContentKey(src, ContentKind.EPISODE, RemoteId("e1"))
private val keyEp2 = ContentKey(src, ContentKind.EPISODE, RemoteId("e2"))
private val posterMovie = PosterRow(keyMovie, "Movie V", null, null, null, RemoteId("locked"))
private val posterMovie2 = PosterRow(keyMovie2, "Movie W", null, null, null, RemoteId("open"))
private val posterSeries = PosterRow(keySeries, "Show S", null, null, null, RemoteId("open"))

// M4/M5 fixtures: a locked show, its episodes, and a library movie.
private val keySeriesLocked = ContentKey(src, ContentKind.SERIES, RemoteId("s2"))
private val posterSeriesLocked = PosterRow(keySeriesLocked, "Locked Show", null, null, null, RemoteId("locked"))
private val keyLib = ContentKey(src, ContentKind.VOD, RemoteId("lib1"))
private val posterLib = PosterRow(keyLib, "Library Movie", null, null, null, RemoteId("lib1"))
private fun episode(id: String, series: String, number: Int) =
    Episode(src, RemoteId(id), RemoteId(series), 1, number, "E$number", null, null, null, null, null)
private fun seriesDetail(series: String, vararg episodes: Episode) = SeriesDetail(
    SeriesRecord(src, RemoteId(series), series, null, emptyList(), emptyList(), null, null, null, null, null, 0),
    null, null, listOf(Season(1, null, null, episodes.toList())),
)

@OptIn(ExperimentalCoroutinesApi::class)
class VodBrowseViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun showEverythingListsItemsFromBothCategories() = runTest(dispatcher) {
        val vm = VodBrowseViewModel(ContentKind.VOD, FakeSources(), FakeCatalog(), FakeUserData(), ShowEverything)
        val page = vm.items.first().itemInserts().first()
        assertEquals(listOf("a1", "b1"), page.map { it.key.remoteId.value })
    }

    @Test
    fun hiddenCategoryItemsAreAbsent() = runTest(dispatcher) {
        val hideB: Visibility = { _, _, categoryId -> categoryId != "b" }
        val vm = VodBrowseViewModel(ContentKind.VOD, FakeSources(), FakeCatalog(), FakeUserData(), flowOf(hideB))
        val page = vm.items.first().itemInserts().first()
        assertEquals(listOf("a1"), page.map { it.key.remoteId.value })
    }

    @Test
    fun unlockingVisibilityRefiltersLive() = runTest(dispatcher) {
        val visibility = MutableStateFlow<Visibility>({ _, _, categoryId -> categoryId != "b" })
        val vm = VodBrowseViewModel(ContentKind.VOD, FakeSources(), FakeCatalog(), FakeUserData(), visibility)
        val seen = mutableListOf<List<String>>()
        val job = launch {
            vm.items.collect { data ->
                seen += data.itemInserts().first().map { it.key.remoteId.value }
            }
        }
        advanceUntilIdle()
        assertEquals(listOf(listOf("a1")), seen)
        visibility.value = { _, _, _ -> true }
        advanceUntilIdle()
        job.cancel()
        assertEquals(listOf(listOf("a1"), listOf("a1", "b1")), seen)
    }

    @Test
    fun moviesShowsOnlyVodProgress() = runTest(dispatcher) {
        val posters = mapOf(keyMovie to posterMovie, keySeries to posterSeries)
        val rows = listOf(
            Progress(keyMovie, null, 30_000, 120_000, 3L),
            Progress(keyEp1, RemoteId("s1"), 15_000, 60_000, 2L),
            Progress(keySeries, null, 10_000, 60_000, 1L),
        )
        val vm = VodBrowseViewModel(ContentKind.VOD, FakeSources(), FakeCatalog(posters), FakeUserData(rows), ShowEverything)
        advanceUntilIdle()
        val card = vm.state.value.continueWatching.single()
        assertEquals(keyMovie, card.poster.key)
        assertEquals(0.25f, card.progress, 0.0001f)
        assertEquals(false, card.episode)
    }

    @Test
    fun showsResolvesEpisodeProgressToParentSeriesPoster() = runTest(dispatcher) {
        val posters = mapOf(keyMovie to posterMovie2, keySeries to posterSeries)
        val rows = listOf(
            Progress(keyEp1, RemoteId("s1"), 30_000, 60_000, 2L),
            Progress(keyMovie2, null, 20_000, 100_000, 1L),
        )
        val vm = VodBrowseViewModel(ContentKind.SERIES, FakeSources(), FakeCatalog(posters), FakeUserData(rows), ShowEverything)
        advanceUntilIdle()
        val card = vm.state.value.continueWatching.single()
        assertEquals(keySeries, card.poster.key)
        assertEquals(posterSeries.name, card.poster.name)
        assertEquals(true, card.episode)
        assertEquals(0.5f, card.progress, 0.0001f)
    }

    @Test
    fun multipleEpisodesOfOneShowYieldOneCardLatestProgressWins() = runTest(dispatcher) {
        val posters = mapOf(keySeries to posterSeries)
        // Older row listed first on purpose: the repository orders by updatedMs DESC, the fake mirrors that.
        val rows = listOf(
            Progress(keyEp1, RemoteId("s1"), 10_000, 60_000, 1L),
            Progress(keyEp2, RemoteId("s1"), 45_000, 60_000, 5L),
        )
        val vm = VodBrowseViewModel(ContentKind.SERIES, FakeSources(), FakeCatalog(posters), FakeUserData(rows), ShowEverything)
        advanceUntilIdle()
        val cards = vm.state.value.continueWatching
        assertEquals(1, cards.size)
        assertEquals(keySeries, cards.single().poster.key)
        assertEquals(0.75f, cards.single().progress, 0.0001f)
    }

    @Test
    fun lockedCategoryAbsentFromContinueWatchingUntilVisibilityUnlocks() = runTest(dispatcher) {
        val posters = mapOf(keyMovie to posterMovie, keyMovie2 to posterMovie2)
        val rows = listOf(
            Progress(keyMovie, null, 30_000, 120_000, 2L),
            Progress(keyMovie2, null, 25_000, 100_000, 1L),
        )
        val visibility = MutableStateFlow<Visibility>({ _, _, categoryId -> categoryId != "locked" })
        val vm = VodBrowseViewModel(ContentKind.VOD, FakeSources(), FakeCatalog(posters), FakeUserData(rows), visibility)
        advanceUntilIdle()
        assertEquals(listOf("m2"), vm.state.value.continueWatching.map { it.poster.key.remoteId.value })
        visibility.value = { _, _, _ -> true }
        advanceUntilIdle()
        assertEquals(listOf("m1", "m2"), vm.state.value.continueWatching.map { it.poster.key.remoteId.value })
    }

    @Test
    fun mediaVariantProgressResolvesToBaseMoviePoster() = runTest(dispatcher) {
        // Synthetic media key from Detail.kt's playback of a specific file; only the base poster exists in the catalog.
        val rows = listOf(Progress(mediaProgressKey(keyMovie, "media-1"), RemoteId("m1"), 30_000, 120_000, 1L))
        val vm = VodBrowseViewModel(
            ContentKind.VOD, FakeSources(), FakeCatalog(mapOf(keyMovie to posterMovie)), FakeUserData(rows), ShowEverything,
        )
        advanceUntilIdle()
        val card = vm.state.value.continueWatching.single()
        assertEquals(keyMovie, card.poster.key)
        assertEquals("Movie V", card.poster.name)
        assertEquals(0.25f, card.progress, 0.0001f)
        assertEquals(false, card.episode)
    }

    @Test
    fun twoMediaVersionsOfOneMovieYieldOneCardLatestProgressWins() = runTest(dispatcher) {
        val posters = mapOf(keyMovie to posterMovie)
        // Older row listed first on purpose: the repository orders by updatedMs DESC, the fake mirrors that.
        val rows = listOf(
            Progress(mediaProgressKey(keyMovie, "media-1"), RemoteId("m1"), 10_000, 100_000, 1L),
            Progress(mediaProgressKey(keyMovie, "media-2"), RemoteId("m1"), 45_000, 100_000, 5L),
        )
        val vm = VodBrowseViewModel(ContentKind.VOD, FakeSources(), FakeCatalog(posters), FakeUserData(rows), ShowEverything)
        advanceUntilIdle()
        val cards = vm.state.value.continueWatching
        assertEquals(1, cards.size)
        assertEquals(keyMovie, cards.single().poster.key)
        assertEquals(0.45f, cards.single().progress, 0.0001f)
    }

    @Test
    fun lockedMovieCategoryHiddenEvenWithMediaVariantProgress() = runTest(dispatcher) {
        val posters = mapOf(keyMovie to posterMovie, keyMovie2 to posterMovie2)
        val rows = listOf(
            Progress(mediaProgressKey(keyMovie, "media-1"), RemoteId("m1"), 30_000, 120_000, 2L),
            Progress(keyMovie2, null, 25_000, 100_000, 1L),
        )
        val visibility = MutableStateFlow<Visibility>({ _, _, categoryId -> categoryId != "locked" })
        val vm = VodBrowseViewModel(ContentKind.VOD, FakeSources(), FakeCatalog(posters), FakeUserData(rows), visibility)
        advanceUntilIdle()
        assertEquals(listOf("m2"), vm.state.value.continueWatching.map { it.poster.key.remoteId.value })
        visibility.value = { _, _, _ -> true }
        advanceUntilIdle()
        assertEquals(listOf("m1", "m2"), vm.state.value.continueWatching.map { it.poster.key.remoteId.value })
    }

    // M4: episode "Up next" cards belong to Shows only, gated by the poster's own (SERIES) kind.
    @Test
    fun moviesNeverReceiveEpisodeUpNextCards() = runTest(dispatcher) {
        val posters = mapOf(keySeries to posterSeries)
        val completed = listOf(CompletedShow(keySeries, keyEp1, 5L))
        val details = mapOf(RemoteId("s1") to seriesDetail("s1", episode("e1", "s1", 1), episode("e2", "s1", 2)))
        val vm = VodBrowseViewModel(
            ContentKind.VOD, FakeSources(details), FakeCatalog(posters),
            FakeUserData(completed = completed), ShowEverything,
        )
        advanceUntilIdle()
        assertTrue(vm.state.value.continueWatching.isEmpty())
    }

    @Test
    fun showsReceiveEpisodeUpNextCard() = runTest(dispatcher) {
        val posters = mapOf(keySeries to posterSeries)
        val completed = listOf(CompletedShow(keySeries, keyEp1, 5L))
        val details = mapOf(RemoteId("s1") to seriesDetail("s1", episode("e1", "s1", 1), episode("e2", "s1", 2)))
        val vm = VodBrowseViewModel(
            ContentKind.SERIES, FakeSources(details), FakeCatalog(posters),
            FakeUserData(completed = completed), ShowEverything,
        )
        advanceUntilIdle()
        val card = vm.state.value.continueWatching.single()
        assertEquals(keySeries, card.poster.key)
        assertEquals("Up next  ·  S1 · E2", card.upNextLabel)
        assertEquals(true, card.episode)
    }

    // Task 95: spoiler-free mode (Settings › Playback) drops the episode identity from "Up next".
    @Test
    fun spoilerFreeModeDropsTheEpisodeIdentityFromUpNextCards() = runTest(dispatcher) {
        val posters = mapOf(keySeries to posterSeries)
        val completed = listOf(CompletedShow(keySeries, keyEp1, 5L))
        val details = mapOf(RemoteId("s1") to seriesDetail("s1", episode("e1", "s1", 1), episode("e2", "s1", 2)))
        val vm = VodBrowseViewModel(
            ContentKind.SERIES, FakeSources(details), FakeCatalog(posters),
            FakeUserData(completed = completed, settings = mapOf("spoiler_free" to "true")), ShowEverything,
        )
        advanceUntilIdle()
        val card = vm.state.value.continueWatching.single()
        assertEquals("Up next", card.upNextLabel)
    }

    @Test
    fun lockedSeriesUpNextHiddenOnShowsAndMovies() = runTest(dispatcher) {
        val posters = mapOf(keySeriesLocked to posterSeriesLocked)
        val completed = listOf(CompletedShow(keySeriesLocked, ContentKey(src, ContentKind.EPISODE, RemoteId("ea")), 5L))
        val details = mapOf(RemoteId("s2") to seriesDetail("s2", episode("ea", "s2", 1), episode("eb", "s2", 2)))
        // The equivalent SERIES category is denied; VOD is allowed. Old code checked VOD on Movies.
        val vis: Visibility = { kind, _, categoryId -> !(kind == "SERIES" && categoryId == "locked") }
        val shows = VodBrowseViewModel(ContentKind.SERIES, FakeSources(details), FakeCatalog(posters), FakeUserData(completed = completed), flowOf(vis))
        val movies = VodBrowseViewModel(ContentKind.VOD, FakeSources(details), FakeCatalog(posters), FakeUserData(completed = completed), flowOf(vis))
        advanceUntilIdle()
        assertTrue(shows.state.value.continueWatching.isEmpty())
        assertTrue(movies.state.value.continueWatching.isEmpty())
    }

    // M5: a library switched off (browseVisibility) must filter Continue Watching and refresh on toggle.
    @Test
    fun hiddenLibraryRemovesContinueWatchingCardAndUnhideRestores() = runTest(dispatcher) {
        val posters = mapOf(keyLib to posterLib)
        val rows = listOf(Progress(keyLib, null, 30_000, 120_000, 3L))
        val hidden = MutableStateFlow<Set<String>>(emptySet())
        val visibility: Flow<Visibility> = hidden.map { off -> { k: String, s: String, c: String -> "$k|$s|$c" !in off } }
        val vm = VodBrowseViewModel(ContentKind.VOD, FakeSources(), FakeCatalog(posters), FakeUserData(rows, hiddenFlow = hidden), visibility)
        advanceUntilIdle()
        assertEquals(listOf("lib1"), vm.state.value.continueWatching.map { it.poster.key.remoteId.value })
        hidden.value = setOf("VOD|s|lib1")
        advanceUntilIdle()
        assertTrue(vm.state.value.continueWatching.isEmpty())
        hidden.value = emptySet()
        advanceUntilIdle()
        assertEquals(listOf("lib1"), vm.state.value.continueWatching.map { it.poster.key.remoteId.value })
    }

    @Test
    fun hiddenLibraryRemovesUpNextCardOnShows() = runTest(dispatcher) {
        val posters = mapOf(keySeries to posterSeries) // categoryId "open"
        val completed = listOf(CompletedShow(keySeries, keyEp1, 5L))
        val details = mapOf(RemoteId("s1") to seriesDetail("s1", episode("e1", "s1", 1), episode("e2", "s1", 2)))
        val hidden = MutableStateFlow<Set<String>>(emptySet())
        val visibility: Flow<Visibility> = hidden.map { off -> { k: String, s: String, c: String -> "$k|$s|$c" !in off } }
        val vm = VodBrowseViewModel(ContentKind.SERIES, FakeSources(details), FakeCatalog(posters), FakeUserData(completed = completed, hiddenFlow = hidden), visibility)
        advanceUntilIdle()
        assertEquals(1, vm.state.value.continueWatching.size)
        hidden.value = setOf("SERIES|s|open")
        advanceUntilIdle()
        assertTrue(vm.state.value.continueWatching.isEmpty())
    }

    // Task 84c: My List row data for the Netflix layout.
    @Test
    fun myListResolvesFavoritesToPostersAndUnfavorsLive() = runTest(dispatcher) {
        val posters = mapOf(keyA to posterA, keyB to posterB)
        val favs = MutableStateFlow(listOf(keyA, keyB))
        val vm = VodBrowseViewModel(ContentKind.VOD, FakeSources(), FakeCatalog(posters), FakeUserData(favs = favs), ShowEverything)
        advanceUntilIdle()
        assertEquals(listOf("a1", "b1"), vm.state.value.myList.map { it.key.remoteId.value })
        favs.value = listOf(keyB)
        advanceUntilIdle()
        assertEquals(listOf("b1"), vm.state.value.myList.map { it.key.remoteId.value })
    }

    @Test
    fun myListHonoursVisibilityLocks() = runTest(dispatcher) {
        val posters = mapOf(keyA to posterA, keyB to posterB)
        val hideB: Visibility = { _, _, categoryId -> categoryId != "b" }
        val vm = VodBrowseViewModel(ContentKind.VOD, FakeSources(), FakeCatalog(posters), FakeUserData(favs = flowOf(listOf(keyA, keyB))), flowOf(hideB))
        advanceUntilIdle()
        assertEquals(listOf("a1"), vm.state.value.myList.map { it.key.remoteId.value })
    }

    // Task 84c: Netflix CW card extras (S2:E5 + time left).
    @Test
    fun episodeProgressCardCarriesEpisodeLabelAndTimeLeft() = runTest(dispatcher) {
        val posters = mapOf(keySeries to posterSeries)
        val rows = listOf(Progress(keyEp2, RemoteId("s1"), 15_000, 75_000, 2L))
        val details = mapOf(RemoteId("s1") to seriesDetail("s1", episode("e1", "s1", 1), episode("e2", "s1", 2)))
        val vm = VodBrowseViewModel(ContentKind.SERIES, FakeSources(details), FakeCatalog(posters), FakeUserData(rows), ShowEverything)
        advanceUntilIdle()
        val card = vm.state.value.continueWatching.single()
        assertEquals("S1:E2", card.episodeLabel)
        assertEquals(60_000L, card.remainingMs)
    }

    @Test
    fun episodeLabelFallsBackToNullWhenProviderHasNoDetails() = runTest(dispatcher) {
        val posters = mapOf(keySeries to posterSeries)
        val rows = listOf(Progress(keyEp2, RemoteId("s1"), 15_000, 75_000, 2L))
        val vm = VodBrowseViewModel(ContentKind.SERIES, FakeSources(), FakeCatalog(posters), FakeUserData(rows), ShowEverything)
        advanceUntilIdle()
        val card = vm.state.value.continueWatching.single()
        assertEquals(null, card.episodeLabel)
        assertEquals(60_000L, card.remainingMs)
    }

    // Task 84i: browse filters — apply, persist, restore, save-as-collection, brand gating.
    @Test
    fun filterIsAppliedAndPersisted() = runTest(dispatcher) {
        val catalog = FakeCatalog()
        val data = FakeUserData()
        val vm = VodBrowseViewModel(ContentKind.VOD, FakeSources(), catalog, data, ShowEverything)
        advanceUntilIdle()
        val f = SmartCollectionFilter(started = false, completed = false)
        vm.setFilter(f)
        advanceUntilIdle()
        assertEquals(f, vm.state.value.filter)
        assertEquals(f.encode(), data.put["browse_filter_VOD"])
        val page = vm.items.first().itemInserts().first()
        assertEquals(listOf("a1", "b1"), page.map { it.key.remoteId.value })
        assertEquals(f, catalog.lastFilter)
    }

    @Test
    fun savedFilterIsRestoredOnOpen() = runTest(dispatcher) {
        val f = SmartCollectionFilter(yearFrom = 2010, yearTo = 2019, genres = listOf("Action"))
        val catalog = FakeCatalog()
        val vm = VodBrowseViewModel(
            ContentKind.VOD, FakeSources(), catalog,
            FakeUserData(settings = mapOf("browse_filter_VOD" to f.encode())), ShowEverything,
        )
        advanceUntilIdle()
        assertEquals(f, vm.state.value.filter)
        vm.items.first().itemInserts().first() // collecting the pager is what runs the filtered query
        assertEquals(f, catalog.lastFilter)
    }

    // Task 84k: Back on the filter panel now behaves like "Done" — the panel applies the draft with
    // setFilter before closing, so picks survive the close. "Clear filters" still applies at once.
    @Test
    fun panelBackAppliesPicksLikeDone() = runTest(dispatcher) {
        val saved = SmartCollectionFilter(started = false, completed = false)
        val catalog = FakeCatalog()
        val data = FakeUserData(settings = mapOf("browse_filter_VOD" to saved.encode()))
        val vm = VodBrowseViewModel(ContentKind.VOD, FakeSources(), catalog, data, ShowEverything)
        advanceUntilIdle()
        assertEquals(saved, vm.state.value.filter) // restored on open
        val draft = saved.copy(yearFrom = 2010, yearTo = 2019) // picks made in the panel, not yet applied
        vm.setFilter(draft) // Back == Done: apply the draft, then close
        advanceUntilIdle()
        assertEquals(draft, vm.state.value.filter)
        assertEquals(draft.encode(), data.put["browse_filter_VOD"])
        vm.items.first().itemInserts().first() // the grid re-queries with the applied picks
        assertEquals(draft, catalog.lastFilter)
        vm.clearFilters() // unchanged: "Clear filters" applies immediately
        advanceUntilIdle()
        assertEquals(SmartCollectionFilter(), vm.state.value.filter)
        assertEquals(SmartCollectionFilter().encode(), data.put["browse_filter_VOD"])
    }

    @Test
    fun saveCollectionFreezesSourceAndCategory() = runTest(dispatcher) {
        val data = FakeUserData()
        val vm = VodBrowseViewModel(ContentKind.VOD, FakeSources(), FakeCatalog(), data, ShowEverything)
        advanceUntilIdle()
        vm.setFilter(SmartCollectionFilter(started = false, completed = false))
        val chip = vm.state.value.categories.first { it.id != null }.id!!
        vm.select(chip)
        vm.saveCollection("Unwatched Cat A", true)
        advanceUntilIdle()
        val saved = data.collections.single()
        assertEquals("smart", saved.mode)
        assertEquals(ContentKind.VOD, saved.contentKind)
        assertTrue(saved.pinnedHome)
        val rule = SmartCollectionFilter.decode(saved.smartFilterJson)!!
        assertEquals("s", rule.sourceId)
        assertEquals("a", rule.categoryId)
        assertEquals(false, rule.started)
        assertEquals(false, rule.completed)
    }

    @Test
    fun brandedCategoryIgnoresTheFilter() = runTest(dispatcher) {
        val catalog = FakeCatalog(cats = listOf(RemoteId("nf") to "Netflix"))
        val vm = VodBrowseViewModel(ContentKind.VOD, FakeSources(), catalog, FakeUserData(), ShowEverything)
        advanceUntilIdle()
        vm.setFilter(SmartCollectionFilter(started = false, completed = false))
        advanceUntilIdle()
        vm.items.first().itemInserts().first() // plain "All" + filter → the filtered query runs
        assertTrue(catalog.filteredCalls > 0)
        val plainBefore = catalog.plainCalls
        val filteredBefore = catalog.filteredCalls
        vm.select(vm.state.value.categories.first { it.name == "Netflix" }.id!!)
        advanceUntilIdle()
        vm.items.first().itemInserts().first() // re-collect on the branded page
        assertTrue(vm.state.value.branded)
        assertEquals(filteredBefore, catalog.filteredCalls) // no filtered query for the branded page
        assertTrue(catalog.plainCalls > plainBefore)
        assertEquals(SmartCollectionFilter(started = false, completed = false), vm.state.value.filter) // kept for when they leave
    }

    // Task 84m: hold-OK › Hide category on the rail — the chip leaves at once (the same
    // switched-off mechanism as Settings › Sources › Libraries), Undo brings it back.
    @Test
    fun hideCategoryDropsItFromTheRailAndUndoRestores() = runTest(dispatcher) {
        val hidden = MutableStateFlow<Set<String>>(emptySet())
        val visibility: Flow<Visibility> = hidden.map { off -> { k: String, s: String, c: String -> "$k|$s|$c" !in off } }
        val data = FakeUserData(hiddenState = hidden)
        val vm = VodBrowseViewModel(ContentKind.VOD, FakeSources(), FakeCatalog(), data, visibility)
        advanceUntilIdle()
        val chipA = "s\u001Fa"
        vm.openCategoryMenu(chipA)
        advanceUntilIdle()
        assertEquals(chipA, vm.state.value.menuCategoryId)
        vm.hideCategory(chipA)
        advanceUntilIdle()
        assertEquals(listOf(null, "s\u001Fb"), vm.state.value.categories.map { it.id })
        assertEquals(null, vm.state.value.menuCategoryId)
        assertEquals(chipA, vm.state.value.hiddenNotice?.categoryId)
        assertEquals(setOf("VOD|s|a"), hidden.value)
        assertEquals("b", vm.state.value.selection?.second) // focus/selection moved to the next chip
        vm.undoHideCategory()
        advanceUntilIdle()
        assertEquals(null, vm.state.value.hiddenNotice)
        assertEquals(emptySet(), hidden.value)
        assertEquals(listOf(null, "s\u001Fa", "s\u001Fb"), vm.state.value.categories.map { it.id })
    }

    @Test
    fun hidingTheOnlyCategoryFallsBackToAll() = runTest(dispatcher) {
        val hidden = MutableStateFlow<Set<String>>(emptySet())
        val visibility: Flow<Visibility> = hidden.map { off -> { k: String, s: String, c: String -> "$k|$s|$c" !in off } }
        val vm = VodBrowseViewModel(ContentKind.VOD, FakeSources(), FakeCatalog(cats = listOf(RemoteId("a") to "Cat A")), FakeUserData(hiddenState = hidden), visibility)
        advanceUntilIdle()
        vm.hideCategory("s\u001Fa")
        advanceUntilIdle()
        assertEquals(listOf<String?>(null), vm.state.value.categories.map { it.id })
        assertEquals(null, vm.state.value.selection?.second)
        assertEquals("s\u001Fa", vm.state.value.hiddenNotice?.categoryId)
    }

    @Test
    fun chipsMapCriteriaAndRemovals() {
        val f = SmartCollectionFilter(
            started = false, completed = false, yearFrom = 2010, yearTo = 2019,
            genres = listOf("Action", "Drama"), ratingAtLeast = 7f, runtimeAtMostMin = 89,
            plexOnly = true, uhdOnly = true,
        )
        assertTrue(f.isBrowseActive())
        assertEquals(false, SmartCollectionFilter().isBrowseActive())
        val chips = browseFilterChips(f)
        assertEquals(listOf("Unwatched", "2010s", "Action", "Drama", "≥7.0/10", "≤89 min", "Plex only", "4K"), chips.map { it.first })
        val withoutWatched = chips.first().second
        assertEquals(null, withoutWatched.started)
        assertEquals(null, withoutWatched.completed)
        assertEquals(listOf("Drama"), chips[2].second.genres) // dropping Action keeps Drama
        assertEquals("Unwatched 2010s Action Drama ≥7 89min Plex 4K", defaultCollectionName(f))
        assertEquals("Watched", browseFilterChips(SmartCollectionFilter(completed = true)).single().first)
        assertEquals("In progress", browseFilterChips(SmartCollectionFilter(started = true, completed = false)).single().first)
    }

    // Task 116: the panel-hidden flag lives in the ViewModel (state.destinationOpen), not composable
    // state, so it survives a title-detail round-trip and Back reopens the same open destination.
    @Test
    fun selectingACategoryOpensTheDestinationAndHidesThePanel() = runTest(dispatcher) {
        val vm = VodBrowseViewModel(ContentKind.VOD, FakeSources(), FakeCatalog(), FakeUserData(), ShowEverything)
        advanceUntilIdle()
        assertFalse(vm.state.value.destinationOpen)
        vm.select(vm.state.value.categories.first { it.id != null }.id!!)
        assertTrue(vm.state.value.destinationOpen)
    }

    @Test
    fun theDestinationStaysOpenAcrossADetailReturn() = runTest(dispatcher) {
        val vm = VodBrowseViewModel(ContentKind.VOD, FakeSources(), FakeCatalog(), FakeUserData(), ShowEverything)
        advanceUntilIdle()
        vm.select(vm.state.value.categories.first { it.id != null }.id!!)
        // Opening a title pushes Screen.Detail and Back pops it; the screen recomposes and the browse
        // collectors re-run, but none of them touch destinationOpen — so the flag is still set on
        // return and the panel stays hidden (the bug was it reopening over the still-selected page).
        advanceUntilIdle()
        assertTrue(vm.state.value.destinationOpen)
    }

    @Test
    fun closingTheAnimeLibraryKeepsTheServiceDestinationOpen() = runTest(dispatcher) {
        val vm = VodBrowseViewModel(ContentKind.VOD, FakeSources(), FakeCatalog(), FakeUserData(), ShowEverything)
        advanceUntilIdle()
        vm.select(vm.state.value.categories.first { it.id != null }.id!!)
        vm.openAnimeLibrary()
        assertTrue(vm.state.value.destinationOpen)
        vm.closeAnimeLibrary()
        // Back from the library lands on the Crunchyroll page, which is still an open destination.
        assertTrue(vm.state.value.destinationOpen)
        assertFalse(vm.state.value.animeLibrary)
    }

    @Test
    fun showPanelIsTheOnlyThingThatRevealsTheCategoryPanel() = runTest(dispatcher) {
        val vm = VodBrowseViewModel(ContentKind.VOD, FakeSources(), FakeCatalog(), FakeUserData(), ShowEverything)
        advanceUntilIdle()
        vm.select(vm.state.value.categories.first { it.id != null }.id!!)
        vm.showPanel()
        assertFalse(vm.state.value.destinationOpen)
    }

    // Task 117: every destination gets a Library; Back returns to the page it opened from, panel
    // still hidden (task 116), and picking another category leaves the Library behind.
    @Test
    fun openingALibraryTakesTheScreenAndBackReturnsToThePage() = runTest(dispatcher) {
        val vm = VodBrowseViewModel(ContentKind.VOD, FakeSources(), FakeCatalog(), FakeUserData(), ShowEverything)
        advanceUntilIdle()
        vm.select(vm.state.value.categories.first { it.id != null }.id!!)
        vm.openLibrary()
        assertTrue(vm.state.value.libraryOpen)
        // "Cat A" is not Crunchyroll: the Library pages this category's own titles.
        assertFalse(vm.state.value.animeLibrary)
        vm.closeLibrary()
        assertFalse(vm.state.value.libraryOpen)
        assertTrue(vm.state.value.destinationOpen)
    }

    @Test
    fun crunchyrollLibraryKeepsItsGlobalAnimeScope() = runTest(dispatcher) {
        val vm = VodBrowseViewModel(
            ContentKind.VOD, FakeSources(),
            FakeCatalog(cats = listOf(RemoteId("cr") to "Crunchyroll")), FakeUserData(), ShowEverything,
        )
        advanceUntilIdle()
        vm.select(vm.state.value.categories.first { it.id != null }.id!!)
        vm.openLibrary()
        assertTrue(vm.state.value.libraryOpen)
        // Task 92 behavior preserved: Crunchyroll's Library stays the global anime grid.
        assertTrue(vm.state.value.animeLibrary)
    }

    @Test
    fun selectingAnotherCategoryClosesTheOpenLibrary() = runTest(dispatcher) {
        val vm = VodBrowseViewModel(ContentKind.VOD, FakeSources(), FakeCatalog(), FakeUserData(), ShowEverything)
        advanceUntilIdle()
        vm.select(vm.state.value.categories.first { it.id != null }.id!!)
        vm.openLibrary()
        vm.select(null)
        assertFalse(vm.state.value.libraryOpen)
    }

    // Task 121: the browse page's RT lookup — the one the Crunchyroll hero now shares. It must
    // carry the row's exact TMDB id and kind to the score client, and stay silent without one.
    @Test
    fun rtScoresUseTheRowsExactTmdbIdAndKind() = runTest(dispatcher) {
        val http = FakeRtHttp()
        val vm = VodBrowseViewModel(
            ContentKind.SERIES, FakeSources(), FakeCatalog(), FakeUserData(), ShowEverything,
            rottenTomatoes = RottenTomatoes(http),
        )
        val series = PosterRow(keySeries, "Show S", null, null, null, RemoteId("open"), tmdbId = "1429")
        assertEquals(RtScores(79, 96), vm.rtScores(series))
        // The score lookup asked for the SERIES property with the exact id — never a title guess.
        assertTrue(http.urls.any { it.contains("P4983") && it.contains("1429") })
        // A VOD row maps to the MOVIE property.
        val movie = PosterRow(keyMovie, "Movie V", null, null, null, RemoteId("locked"), tmdbId = "550")
        assertEquals(RtScores(79, 96), vm.rtScores(movie))
        assertTrue(http.urls.any { it.contains("P4947") && it.contains("550") })
    }

    @Test
    fun rtScoresAreSilentWithoutAnExactTmdbId() = runTest(dispatcher) {
        val http = FakeRtHttp()
        val vm = VodBrowseViewModel(
            ContentKind.SERIES, FakeSources(), FakeCatalog(), FakeUserData(), ShowEverything,
            rottenTomatoes = RottenTomatoes(http),
        )
        assertNull(vm.rtScores(posterSeries))
        val blank = PosterRow(keySeries, "Show S", null, null, null, RemoteId("open"), tmdbId = "   ")
        assertNull(vm.rtScores(blank))
        assertEquals(0, http.urls.size) // no exact id: no request, no score, no badge
    }
}

/** Canned Wikidata→RT answers (scores 79/96) recording every URL the score client asked for. */
private class FakeRtHttp : HttpClient {
    val urls = mutableListOf<String>()
    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
        urls += url
        val body = if (url.contains("sparql"))
            """{"results":{"bindings":[{"rt":{"value":"tv/attack_on_titan"}}]}}"""
        else
            """"criticsScore":{"score":"79"} "audienceScore":{"score":"96"}"""
        return object : HttpResponse {
            override val status = 200
            override val headers = emptyMap<String, String>()
            override val body: BufferedSource = Buffer().writeUtf8(body)
            override fun close() = Unit
        }
    }
}

/** Paging 3.5's item flow on [PagingData] is internal; this is the standard test-side accessor. */
@Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
private fun <T : Any> PagingData<T>.itemInserts(): Flow<List<T>> =
    flow.filterIsInstance<androidx.paging.PageEvent.Insert<T>>().map { event -> event.pages.flatMap { page -> page.data } }

private class ListPagingSource<T : Any>(private val items: List<T>) : PagingSource<Int, T>() {
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, T> =
        LoadResult.Page(items, prevKey = null, nextKey = null)
    override fun getRefreshKey(state: PagingState<Int, T>): Int? = null
}

private class FakeSources(
    private val details: Map<RemoteId, SeriesDetail> = emptyMap(),
) : SourceRepository {
    override fun sources(): Flow<List<SourceSummary>> =
        flowOf(listOf(SourceSummary(src, SourceKind.XTREAM, "Mock", null, null, null)))
    override suspend fun add(config: SourceConfig): SourceId = TODO()
    override suspend fun update(config: SourceConfig) = TODO()
    override suspend fun remove(id: SourceId) = TODO()
    override suspend fun config(id: SourceId): SourceConfig? = TODO()
    override suspend fun contentSource(id: SourceId): ContentSource = FakeContentSource(details)
    override suspend fun probe(config: SourceConfig): AccountInfo = TODO()
}

private class FakeContentSource(private val details: Map<RemoteId, SeriesDetail>) : ContentSource {
    override val id = src
    override val kind = SourceKind.XTREAM
    override val capabilities: Set<Capability> = emptySet()
    override suspend fun accountInfo(): AccountInfo = TODO()
    override fun liveCategories(): Flow<Category> = flowOf()
    override fun liveChannels(diagnostics: SyncDiagnostics): Flow<ChannelRecord> = flowOf()
    override fun vodCategories(): Flow<Category> = flowOf()
    override fun vodItems(diagnostics: SyncDiagnostics): Flow<VodRecord> = flowOf()
    override fun seriesCategories(): Flow<Category> = flowOf()
    override fun series(diagnostics: SyncDiagnostics): Flow<SeriesRecord> = flowOf()
    override suspend fun vodDetail(id: RemoteId): VodDetail = TODO()
    override suspend fun seriesDetail(id: RemoteId): SeriesDetail = details[id] ?: TODO()
    override fun epg(window: TimeWindow, channelKeys: Set<String>?, diagnostics: SyncDiagnostics): Flow<ProgrammeRecord> = flowOf()
    override suspend fun shortEpg(channelId: RemoteId, limit: Int): List<ProgrammeRecord> = emptyList()
    override suspend fun playback(request: PlaybackRequest): PlaybackSpec = TODO()
}

private class FakeCatalog(
    private val posters: Map<ContentKey, PosterRow> = mapOf(keyA to posterA, keyB to posterB),
    private val cats: List<Pair<RemoteId, String>>? = null,
) : CatalogRepository {
    var lastFilter: SmartCollectionFilter? = null
    var filteredCalls = 0
    var plainCalls = 0
    override fun categories(sourceId: SourceId, kind: ContentKind, includeHidden: Boolean): Flow<List<Category>> =
        flowOf(
            cats?.map { (id, name) -> Category(sourceId, kind, id, name, null, 0) } ?: listOf(
                Category(sourceId, kind, RemoteId("a"), "Cat A", null, 0),
                Category(sourceId, kind, RemoteId("b"), "Cat B", null, 1),
            ),
        )
    override fun channels(sourceId: SourceId, categoryId: RemoteId): PagingSource<Int, ChannelRow> = TODO()
    override fun channelsAll(sourceId: SourceId, excludedCategories: Collection<String>): androidx.paging.PagingSource<Int, com.yodesla.omniverse.core.data.ChannelRow> = TODO()
    override suspend fun channelListAll(sourceId: SourceId, excludedCategories: Collection<String>): List<com.yodesla.omniverse.core.data.ChannelRow> = emptyList()
    override suspend fun channelOrderAll(sourceId: SourceId, excludedCategories: Collection<String>): List<com.yodesla.omniverse.core.model.ContentKey> = emptyList()
    override suspend fun channelList(sourceId: SourceId, categoryId: RemoteId): List<ChannelRow> = TODO()
    override suspend fun channelNeighbours(key: ContentKey, categoryId: RemoteId): Pair<ChannelRow?, ChannelRow?> = TODO()
    override suspend fun channelOrder(sourceId: SourceId, categoryId: RemoteId): List<ContentKey> = TODO()
    override suspend fun channel(key: ContentKey): ChannelRow? = TODO()
    override fun vod(sourceId: SourceId, categoryId: RemoteId?): PagingSource<Int, PosterRow> =
        plain(posters.values.filter { it.key.kind == ContentKind.VOD })
    override fun series(sourceId: SourceId, categoryId: RemoteId?): PagingSource<Int, PosterRow> =
        plain(posters.values.filter { it.key.kind == ContentKind.SERIES })
    private fun plain(rows: List<PosterRow>): PagingSource<Int, PosterRow> {
        plainCalls++
        return ListPagingSource(rows)
    }
    override fun vodAll(excludedCategoryKeys: Collection<String>): PagingSource<Int, PosterRow> = plain(posters.values.filter { it.key.kind == ContentKind.VOD })
    override fun seriesAll(excludedCategoryKeys: Collection<String>): PagingSource<Int, PosterRow> = plain(posters.values.filter { it.key.kind == ContentKind.SERIES })
    // Task 84i: the fake applies the year/rating bounds in memory and records the filter it saw.
    override fun vodFiltered(
        sourceId: SourceId?, categoryId: RemoteId?, filter: SmartCollectionFilter,
        excludedCategoryKeys: Collection<String>, alphabetic: Boolean,
    ): PagingSource<Int, PosterRow> = filtered(ContentKind.VOD, filter)
    override fun seriesFiltered(
        sourceId: SourceId?, categoryId: RemoteId?, filter: SmartCollectionFilter,
        excludedCategoryKeys: Collection<String>, alphabetic: Boolean,
    ): PagingSource<Int, PosterRow> = filtered(ContentKind.SERIES, filter)
    private fun filtered(kind: ContentKind, filter: SmartCollectionFilter): PagingSource<Int, PosterRow> {
        lastFilter = filter
        filteredCalls++
        return ListPagingSource(posters.values.filter { it.key.kind == kind }.filter { p ->
            (filter.yearFrom == null || (p.year ?: 0) >= filter.yearFrom!!) &&
                (filter.yearTo == null || (p.year ?: Int.MAX_VALUE) <= filter.yearTo!!) &&
                (filter.ratingAtLeast == null || (p.rating ?: 0f) >= filter.ratingAtLeast!!)
        })
    }
    override suspend fun genreOptions(kind: ContentKind, sourceId: SourceId?, categoryId: RemoteId?, excludedCategoryKeys: Collection<String>): List<String> =
        listOf("Action", "Drama")
    override fun recentlyAdded(kind: ContentKind, limit: Int): Flow<List<PosterRow>> = TODO()
    override suspend fun poster(key: ContentKey): PosterRow? = posters[key]
    override fun counts(sourceId: SourceId): Flow<Map<ContentKind, Long>> = TODO()
}

private class FakeUserData(
    private val rows: List<Progress> = emptyList(),
    private val completed: List<CompletedShow> = emptyList(),
    private val hiddenFlow: Flow<Set<String>> = flowOf(emptySet()),
    private val favs: Flow<List<ContentKey>> = flowOf(emptyList()),
    private val settings: Map<String, String> = emptyMap(),
    private val hiddenState: MutableStateFlow<Set<String>>? = null,
) : UserDataRepository {
    /** Task 84i: settings written by the VM and collections saved by "Save as collection". */
    val put = mutableMapOf<String, String>()
    val collections = mutableListOf<LibraryCollection>()
    override fun favorites(kind: ContentKind?): Flow<List<ContentKey>> = favs
    override fun isFavorite(key: ContentKey): Flow<Boolean> = flowOf(false)
    override suspend fun setFavorite(key: ContentKey, favorite: Boolean) = TODO()
    override suspend fun saveProgress(key: ContentKey, parentId: RemoteId?, positionMs: Long, durationMs: Long?) = TODO()
    // Mirrors UserData.sq: ORDER BY updated_ms DESC LIMIT :limit.
    override fun continueWatching(limit: Int): Flow<List<Progress>> =
        flowOf(rows.sortedByDescending { it.updatedMs }.take(limit))
    override fun completedShows(sinceMs: Long, limit: Int): Flow<List<CompletedShow>> = flowOf(completed)
    override fun hiddenCategoryKeys(): Flow<Set<String>> = hiddenState ?: hiddenFlow
    override suspend fun setCategoryHidden(sourceId: SourceId, kind: ContentKind, categoryId: String, hidden: Boolean) {
        val st = hiddenState ?: return
        val key = "${kind.name}|${sourceId.value}|$categoryId"
        st.update { if (hidden) it + key else it - key }
    }
    override suspend fun progress(key: ContentKey): Progress? = TODO()
    override suspend fun recordChannelWatched(key: ContentKey) = TODO()
    override fun recentChannels(limit: Int): Flow<List<ContentKey>> = flowOf(emptyList())
    override suspend fun setHidden(key: ContentKey, hidden: Boolean) = TODO()
    override fun setting(key: String): Flow<String?> = flowOf(settings[key] ?: put[key])
    override suspend fun putSetting(key: String, value: String) { put[key] = value }
    override suspend fun saveCollection(value: LibraryCollection) { collections += value }
}
