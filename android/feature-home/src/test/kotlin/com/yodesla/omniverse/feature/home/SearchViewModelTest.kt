package com.yodesla.omniverse.feature.home

import androidx.paging.PagingSource
import com.yodesla.omniverse.core.data.CatalogRepository
import com.yodesla.omniverse.core.data.ChannelRow
import com.yodesla.omniverse.core.data.PosterRow
import com.yodesla.omniverse.core.data.SearchHit
import com.yodesla.omniverse.core.data.SearchFilteredPage
import com.yodesla.omniverse.core.data.SearchFilters
import com.yodesla.omniverse.core.data.SearchRepository
import com.yodesla.omniverse.core.data.SearchSort
import com.yodesla.omniverse.core.data.ShowEverything
import com.yodesla.omniverse.core.data.Visibility
import com.yodesla.omniverse.core.model.Category
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.SourceId
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

private val src = SourceId("s")
private val keyA = ContentKey(src, ContentKind.VOD, RemoteId("a1"))
private val keyB = ContentKey(src, ContentKind.VOD, RemoteId("b1"))
private val posterA = PosterRow(keyA, "Movie A", null, null, null, RemoteId("a"))
private val posterB = PosterRow(keyB, "Movie B", null, null, null, RemoteId("b"))

@OptIn(ExperimentalCoroutinesApi::class)
class SearchHistoryTest {
    @kotlin.test.Test fun newestFirstDedupedCapped() {
        var h = emptyList<String>()
        for (i in 1..10) h = pushSearchHistory(h, "term $i")
        h = pushSearchHistory(h, "TERM 5")
        kotlin.test.assertEquals(SEARCH_HISTORY_MAX, h.size)
        kotlin.test.assertEquals("TERM 5", h.first())
        kotlin.test.assertEquals(1, h.count { it.equals("term 5", ignoreCase = true) })
        kotlin.test.assertEquals(h, pushSearchHistory(h, " "))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test fun channelAndProgrammeResultsToggleTheUnderlyingChannelFavorite() = runTest(dispatcher) {
        val key = ContentKey(src, ContentKind.LIVE, RemoteId("tv1"))
        val channel = ChannelRow(key, 1, "News", null, "news", 0, RemoteId("live"))
        val user = SearchFavoriteUser()
        val vm = SearchViewModel(FakeSearch(), FakeCatalog(channel), userData = user)
        vm.toggleChannelFavorite(HomeCard("channel", key, "News", null, null, isChannel = true))
        advanceUntilIdle()
        assertEquals(setOf(key), vm.state.value.favoriteChannels)
        vm.toggleChannelFavorite(HomeCard("programme", key, "Evening bulletin", "News", null, isChannel = true))
        advanceUntilIdle()
        assertEquals(emptySet(), vm.state.value.favoriteChannels)
    }

    @Test fun aNewlyLockedChannelCannotBeFavoritedFromOldSearchResults() = runTest(dispatcher) {
        val key = ContentKey(src, ContentKind.LIVE, RemoteId("tv1"))
        val channel = ChannelRow(key, 1, "News", null, "news", 0, RemoteId("live"))
        val vm = SearchViewModel(FakeSearch(), FakeCatalog(channel), visibility = flowOf { _, _, _ -> false }, userData = SearchFavoriteUser())
        vm.toggleChannelFavorite(HomeCard("channel", key, "News", null, null, isChannel = true))
        advanceUntilIdle()
        assertEquals(emptySet(), vm.state.value.favoriteChannels)
    }

    private fun resultIds(vm: SearchViewModel): List<String> =
        vm.state.value.rows.flatMap { row -> row.cards.map { it.open.remoteId.value } }

    @Test
    fun titlesAppearWhileGuideLookupIsStillPending() = runTest(dispatcher) {
        val guideGate = CompletableDeferred<Unit>()
        val vm = SearchViewModel(FakeSearch(guideGate), FakeCatalog(), ShowEverything)
        vm.onQuery("movie")
        advanceTimeBy(201)
        runCurrent()
        assertEquals(listOf("a1", "b1"), resultIds(vm))
        guideGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("a1", "b1"), resultIds(vm))
    }

    @Test
    fun showEverythingListsHitsFromBothCategories() = runTest(dispatcher) {
        val vm = SearchViewModel(FakeSearch(), FakeCatalog(), ShowEverything)
        vm.onQuery("m")
        advanceUntilIdle()
        assertEquals(listOf("a1", "b1"), resultIds(vm))
    }

    @Test
    fun typedSearchUsesGroupedTitleResults() = runTest(dispatcher) {
        val search = FakeSearch()
        val vm = SearchViewModel(search, FakeCatalog(), ShowEverything)
        vm.onQuery("movie")
        advanceUntilIdle()
        assertEquals(listOf("a1", "b1"), resultIds(vm))
        assertEquals("movie", search.filteredQuery)
        assertEquals(SearchFilters.None, search.filteredFilters)
    }

    @Test
    fun hiddenCategoryHitsAreAbsent() = runTest(dispatcher) {
        val vm = SearchViewModel(FakeSearch(), FakeCatalog(), flowOf { _, _, categoryId -> categoryId != "b" })
        vm.onQuery("m")
        advanceUntilIdle()
        assertEquals(listOf("a1"), resultIds(vm))
    }
    @Test
    fun unlockingVisibilityRefiltersLive() = runTest(dispatcher) {
        val visibility = MutableStateFlow<Visibility>({ _, _, categoryId -> categoryId != "b" })
        val vm = SearchViewModel(FakeSearch(), FakeCatalog(), visibility)
        vm.onQuery("m")
        advanceUntilIdle()
        assertEquals(listOf("a1"), resultIds(vm))
        visibility.value = { _, _, _ -> true }
        advanceUntilIdle()
        assertEquals(listOf("a1", "b1"), resultIds(vm))
    }

    // Task 107: the header "Clear" button — clearing empties the list and persists it to this profile.
    @Test
    fun clearHistoryEmptiesStateAndSavesEmptyToTheProfile() = runTest(dispatcher) {
        var saved: String? = null
        val vm = SearchViewModel(
            FakeSearch(), FakeCatalog(), ShowEverything,
            historySource = flowOf("alpha\nbeta"),
            saveHistory = { saved = it },
        )
        advanceUntilIdle()
        assertEquals(listOf("alpha", "beta"), vm.state.value.history)
        vm.clearHistory()
        advanceUntilIdle()
        assertEquals(emptyList<String>(), vm.state.value.history)
        assertEquals("", saved)
    }

    // Task 118: Back from a title opened in Search lands back on this screen, so the route needs to
    // know which card to hand focus back to — and only until it has actually done so.
    @Test
    fun resultOpenedSurvivesTheTitleRoundTripUntilFocusIsRestored() = runTest(dispatcher) {
        val vm = SearchViewModel(FakeSearch(), FakeCatalog(), ShowEverything)
        vm.onQuery("m")
        advanceUntilIdle()
        assertEquals(null, vm.state.value.focusCardId)
        vm.resultOpened(HomeCard("VOD-s-a1", keyA, "Movie A", null, null))
        assertEquals("VOD-s-a1", vm.state.value.focusCardId)
        // A re-run of the search pipeline (parental change, a new query) must not drop it.
        vm.onQuery("mo")
        advanceUntilIdle()
        assertEquals("VOD-s-a1", vm.state.value.focusCardId)
        vm.resultFocusHandled()
        assertEquals(null, vm.state.value.focusCardId)
    }

    // Task 118: the focus bookkeeping is the only thing that changes — the query and its results are
    // exactly what they were when the title was opened.
    @Test
    fun resultFocusBookkeepingLeavesQueryAndResultsAlone() = runTest(dispatcher) {
        val vm = SearchViewModel(FakeSearch(), FakeCatalog(), ShowEverything)
        vm.onQuery("m")
        advanceUntilIdle()
        val before = vm.state.value
        vm.resultOpened(HomeCard("VOD-s-a1", keyA, "Movie A", null, null))
        vm.resultFocusHandled()
        val after = vm.state.value
        assertEquals(before.query, after.query)
        assertEquals(before.rows, after.rows)
        assertEquals(before.searched, after.searched)
        assertEquals(before.history, after.history)
    }
}

private class FakeSearch(private val guideGate: CompletableDeferred<Unit>? = null) : SearchRepository {
    var filteredQuery: String? = null
    var filteredFilters: SearchFilters? = null
    override suspend fun search(query: String, limitPerKind: Int): Map<ContentKind, List<SearchHit>> =
        mapOf(ContentKind.VOD to listOf(SearchHit(keyA, "Movie A"), SearchHit(keyB, "Movie B")))
    override suspend fun searchProgrammes(query: String, nowMs: Long, windowMs: Long, limit: Int): List<com.yodesla.omniverse.core.data.ProgrammeHit> {
        guideGate?.await()
        return emptyList()
    }
    override suspend fun searchFiltered(query: String, filters: SearchFilters, excludedCategoryKeys: Collection<String>, limitPerKind: Int, sort: SearchSort): Map<ContentKind, SearchFilteredPage> {
        filteredQuery = query
        filteredFilters = filters
        return mapOf(ContentKind.VOD to SearchFilteredPage(listOf(posterA, posterB)))
    }
}

private class FakeCatalog(private val live: ChannelRow? = null) : CatalogRepository {
    override fun categories(sourceId: SourceId, kind: ContentKind, includeHidden: Boolean): Flow<List<Category>> = TODO()
    override fun channels(sourceId: SourceId, categoryId: RemoteId): PagingSource<Int, ChannelRow> = TODO()
    override fun channelsAll(sourceId: SourceId, excludedCategories: Collection<String>): androidx.paging.PagingSource<Int, com.yodesla.omniverse.core.data.ChannelRow> = TODO()
    override suspend fun channelListAll(sourceId: SourceId, excludedCategories: Collection<String>): List<com.yodesla.omniverse.core.data.ChannelRow> = emptyList()
    override suspend fun channelOrderAll(sourceId: SourceId, excludedCategories: Collection<String>): List<com.yodesla.omniverse.core.model.ContentKey> = emptyList()
    override suspend fun channelList(sourceId: SourceId, categoryId: RemoteId): List<ChannelRow> = TODO()
    override suspend fun channelNeighbours(key: ContentKey, categoryId: RemoteId): Pair<ChannelRow?, ChannelRow?> = TODO()
    override suspend fun channelOrder(sourceId: SourceId, categoryId: RemoteId): List<ContentKey> = TODO()
    override suspend fun channel(key: ContentKey): ChannelRow? = live?.takeIf { it.key == key }
    override fun vod(sourceId: SourceId, categoryId: RemoteId?): PagingSource<Int, PosterRow> = TODO()
    override fun series(sourceId: SourceId, categoryId: RemoteId?): PagingSource<Int, PosterRow> = TODO()
    override fun vodAll(excludedCategoryKeys: Collection<String>): PagingSource<Int, PosterRow> = TODO()
    override fun seriesAll(excludedCategoryKeys: Collection<String>): PagingSource<Int, PosterRow> = TODO()
    override fun recentlyAdded(kind: ContentKind, limit: Int): Flow<List<PosterRow>> = TODO()
    override suspend fun poster(key: ContentKey): PosterRow? =
        when (key) {
            keyA -> posterA
            keyB -> posterB
            else -> null
        }
    override fun counts(sourceId: SourceId): Flow<Map<ContentKind, Long>> = TODO()
}

private class SearchFavoriteUser : com.yodesla.omniverse.core.data.UserDataRepository {
    private val saved = MutableStateFlow<List<ContentKey>>(emptyList())
    override fun favorites(kind: ContentKind?): Flow<List<ContentKey>> = saved.map { keys -> keys.filter { kind == null || it.kind == kind } }
    override fun isFavorite(key: ContentKey): Flow<Boolean> = saved.map { key in it }
    override suspend fun setFavorite(key: ContentKey, favorite: Boolean) { saved.value = if (favorite) (saved.value + key).distinct() else saved.value - key }
    override suspend fun saveProgress(key: ContentKey, parentId: RemoteId?, positionMs: Long, durationMs: Long?) = Unit
    override fun continueWatching(limit: Int): Flow<List<com.yodesla.omniverse.core.data.Progress>> = flowOf(emptyList())
    override suspend fun progress(key: ContentKey): com.yodesla.omniverse.core.data.Progress? = null
    override suspend fun recordChannelWatched(key: ContentKey) = Unit
    override fun recentChannels(limit: Int): Flow<List<ContentKey>> = flowOf(emptyList())
    override suspend fun setHidden(key: ContentKey, hidden: Boolean) = Unit
    override fun setting(key: String): Flow<String?> = flowOf(null)
    override suspend fun putSetting(key: String, value: String) = Unit
}
