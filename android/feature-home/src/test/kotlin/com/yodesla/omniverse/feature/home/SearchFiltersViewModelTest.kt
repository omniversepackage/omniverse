package com.yodesla.omniverse.feature.home

import com.yodesla.omniverse.core.data.CatalogRepository
import com.yodesla.omniverse.core.data.ChannelRow
import com.yodesla.omniverse.core.data.PosterRow
import com.yodesla.omniverse.core.data.SearchFilteredPage
import com.yodesla.omniverse.core.data.SearchFilters
import com.yodesla.omniverse.core.data.SearchHit
import com.yodesla.omniverse.core.data.SearchRepository
import com.yodesla.omniverse.core.data.ShowEverything
import com.yodesla.omniverse.core.data.Visibility
import com.yodesla.omniverse.core.model.Category
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.SourceId
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

private val fsrc = SourceId("s")
private val fKeyA = ContentKey(fsrc, ContentKind.VOD, RemoteId("a1"))
private val fKeyB = ContentKey(fsrc, ContentKind.VOD, RemoteId("b1"))
private val fPosterA = PosterRow(fKeyA, "Movie A", null, 1980, 8.0f, RemoteId("a"))
private val fPosterB = PosterRow(fKeyB, "Movie B", null, 1985, 7.0f, RemoteId("b"))

/**
 * Task 122: the MAIN search filter chips at the ViewModel level — blank-query discovery, live
 * re-filtering on unlock, clear/reset, "Show more" paging past the per-kind cap, SQL excluded keys
 * and typo suppression.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchFiltersViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun resultIds(vm: SearchViewModel): List<String> =
        vm.state.value.rows.flatMap { row -> row.cards.map { it.open.remoteId.value } }

    @Test
    fun blankQueryWithFiltersDiscoversTitles() = runTest(dispatcher) {
        val search = FakeFilteredSearch()
        search.filtered = mapOf(ContentKind.VOD to SearchFilteredPage(listOf(fPosterA, fPosterB)))
        val vm = SearchViewModel(search, FilterFakeCatalog(), ShowEverything)
        vm.toggleMovies()
        vm.toggleDecade(1980)
        vm.toggleAnime()
        advanceUntilIdle()
        assertEquals("", vm.state.value.query)
        assertTrue(vm.state.value.searched) // never a false "nothing found" for a filtered blank query
        assertEquals(listOf("a1", "b1"), resultIds(vm))
        // The chips arrive at the repository as one AND-ed filter set with inclusive decade bounds.
        assertEquals(SearchFilters(movies = true, yearFrom = 1980, yearTo = 1989, anime = true), search.lastFilters)
        assertEquals("", search.lastQuery)
        // Same card-id scheme as typed results, so focusCardId survives a path switch.
        assertEquals(listOf("VOD-s-a1", "VOD-s-b1"), vm.state.value.rows.first().cards.map { it.id })
    }

    @Test
    fun blankQueryWithoutFiltersKeepsTheInvitation() = runTest(dispatcher) {
        val vm = SearchViewModel(FakeFilteredSearch(), FilterFakeCatalog(), ShowEverything)
        advanceUntilIdle()
        assertFalse(vm.state.value.searched)
        assertTrue(vm.state.value.rows.isEmpty())
    }

    @Test
    fun unlockingVisibilityRefiltersFilteredResultsLive() = runTest(dispatcher) {
        val visibility = MutableStateFlow<Visibility>({ _, _, categoryId -> categoryId != "b" })
        val search = FakeFilteredSearch()
        search.filtered = mapOf(ContentKind.VOD to SearchFilteredPage(listOf(fPosterA, fPosterB)))
        val vm = SearchViewModel(search, FilterFakeCatalog(), visibility)
        vm.toggleMovies()
        advanceUntilIdle()
        assertEquals(listOf("a1"), resultIds(vm))
        visibility.value = { _, _, _ -> true }
        advanceUntilIdle()
        assertEquals(listOf("a1", "b1"), resultIds(vm))
    }

    @Test
    fun sqlExcludedKeysReachTheRepository() = runTest(dispatcher) {
        val search = FakeFilteredSearch()
        search.filtered = mapOf(ContentKind.VOD to SearchFilteredPage(listOf(fPosterA)))
        val vm = SearchViewModel(search, FilterFakeCatalog(), ShowEverything, excludedCategoryKeys = flowOf(listOf("VOD|s|b")))
        vm.toggleMovies()
        advanceUntilIdle()
        // Switched-off / locked categories reach the SQL predicate, not just the per-card check.
        assertEquals(listOf("VOD|s|b"), search.lastExcluded)
    }

    @Test
    fun clearFiltersResetsChipsAndReturnsToTypedSearch() = runTest(dispatcher) {
        val search = FakeFilteredSearch()
        search.filtered = mapOf(ContentKind.VOD to SearchFilteredPage(listOf(fPosterA)))
        val vm = SearchViewModel(search, FilterFakeCatalog(), ShowEverything)
        vm.toggleMovies()
        vm.toggleAnime()
        advanceUntilIdle()
        assertTrue(vm.state.value.filters.active)
        vm.clearFilters()
        advanceUntilIdle()
        assertEquals(SearchFilters.None, vm.state.value.filters)
        assertFalse(vm.state.value.searched) // blank query + no filters = the invitation again
    }

    @Test
    fun tappingTheActiveDecadeClearsItAndAnotherMoves() = runTest(dispatcher) {
        val vm = SearchViewModel(FakeFilteredSearch(), FilterFakeCatalog(), ShowEverything)
        vm.toggleDecade(1980)
        assertEquals(Pair(1980, 1989), vm.state.value.filters.let { it.yearFrom to it.yearTo })
        vm.toggleDecade(1990)
        assertEquals(Pair(1990, 1999), vm.state.value.filters.let { it.yearFrom to it.yearTo })
        vm.toggleDecade(1990)
        assertEquals(null to null, vm.state.value.filters.let { it.yearFrom to it.yearTo })
    }

    @Test
    fun ratingAndGenreChipsToggleOneAtATime() = runTest(dispatcher) {
        val vm = SearchViewModel(FakeFilteredSearch(), FilterFakeCatalog(), ShowEverything)
        vm.toggleRating(8f)
        assertEquals(8f, vm.state.value.filters.ratingAtLeast)
        vm.toggleRating(9f)
        assertEquals(9f, vm.state.value.filters.ratingAtLeast) // one rating at a time
        vm.toggleRating(9f)
        assertEquals(null, vm.state.value.filters.ratingAtLeast)
        vm.toggleGenre("Action")
        vm.toggleGenre("Drama")
        assertEquals(listOf("Action", "Drama"), vm.state.value.filters.genres) // OR-ed like the browse panel
        vm.toggleGenre("action")
        assertEquals(listOf("Drama"), vm.state.value.filters.genres) // case-insensitive removal
    }

    @Test
    fun truncatedCapIsSurfacedNotSilent() = runTest(dispatcher) {
        val search = FakeFilteredSearch()
        search.filtered = mapOf(ContentKind.VOD to SearchFilteredPage(listOf(fPosterA), more = true))
        val vm = SearchViewModel(search, FilterFakeCatalog(), ShowEverything)
        vm.toggleMovies()
        advanceUntilIdle()
        assertTrue(vm.state.value.truncated)
    }

    @Test
    fun showMorePagesPastTheFirstCapAndResetsOnANewSearch() = runTest(dispatcher) {
        // Task 122 review: a blank-query decade can hold far more than 24 titles. The label alone
        // would leave the rest unreachable, so "Show more" must actually fetch them.
        val search = FakeFilteredSearch()
        search.total = 60
        val vm = SearchViewModel(search, FilterFakeCatalog(), ShowEverything)
        vm.toggleMovies()
        advanceUntilIdle()
        assertEquals(SEARCH_PAGE, search.lastLimit)
        assertEquals(SEARCH_PAGE, resultIds(vm).size)
        assertTrue(vm.state.value.truncated)
        vm.showMore()
        advanceUntilIdle()
        assertEquals(SEARCH_PAGE * 2, search.lastLimit)
        assertEquals(SEARCH_PAGE * 2, resultIds(vm).size)
        assertTrue(vm.state.value.truncated)
        vm.showMore()
        advanceUntilIdle()
        assertEquals(SEARCH_PAGE * 3, search.lastLimit) // 60 matches < 72 → everything is on screen
        assertEquals(60, resultIds(vm).size)
        assertFalse(vm.state.value.truncated)
        // A new query or a new filter set starts a fresh page: a stale deep page must not follow.
        vm.onQuery("anime")
        advanceUntilIdle()
        assertEquals(SEARCH_PAGE, search.lastLimit)
        vm.toggleAnime()
        advanceUntilIdle()
        assertEquals(SEARCH_PAGE, search.lastLimit)
        // The row stays bounded: "Show more" cannot grow past SEARCH_PAGE_MAX per kind.
        repeat(30) { vm.showMore() }
        advanceUntilIdle()
        assertEquals(SEARCH_PAGE_MAX, search.lastLimit)
    }

    @Test
    fun didYouMeanRespectsActiveFilters() = runTest(dispatcher) {
        val search = FakeFilteredSearch() // recentTitles has "Movie A"; typed search finds nothing
        val vm = SearchViewModel(search, FilterFakeCatalog(), ShowEverything)
        vm.onQuery("Movie B")
        advanceUntilIdle()
        assertEquals("Movie A", vm.state.value.didYouMean) // no filters: the closest title is offered
        vm.toggleMovies()
        advanceUntilIdle()
        assertNull(vm.state.value.didYouMean) // filters on: an off-filter suggestion would be a lie
    }

    @Test
    fun decadesOfferedComeFromTheData() = runTest(dispatcher) {
        val vm = SearchViewModel(FakeFilteredSearch(), FilterFakeCatalog(), ShowEverything)
        advanceUntilIdle()
        assertEquals(listOf(1980), vm.state.value.decades)
    }
}

private class FakeFilteredSearch : SearchRepository {
    var filtered: Map<ContentKind, SearchFilteredPage> = emptyMap()
    /** When > 0, [searchFiltered] behaves like a real paged query: [total] matches, capped at the limit. */
    var total: Int = 0
    var lastQuery: String? = null
    var lastFilters: SearchFilters? = null
    var lastExcluded: Collection<String> = emptyList()
    var lastLimit: Int = 0

    override suspend fun search(query: String, limitPerKind: Int): Map<ContentKind, List<SearchHit>> = emptyMap()

    override suspend fun searchFiltered(
        query: String,
        filters: SearchFilters,
        excludedCategoryKeys: Collection<String>,
        limitPerKind: Int,
    ): Map<ContentKind, SearchFilteredPage> {
        lastQuery = query; lastFilters = filters; lastExcluded = excludedCategoryKeys; lastLimit = limitPerKind
        if (total == 0) return filtered
        val rows = (1..total.coerceAtMost(limitPerKind)).map { i ->
            PosterRow(ContentKey(fsrc, ContentKind.VOD, RemoteId("m$i")), "Title $i", null, 1980, 8.0f, RemoteId("a"))
        }
        return mapOf(ContentKind.VOD to SearchFilteredPage(rows, more = total > limitPerKind))
    }

    override suspend fun decadeOptions(kinds: Collection<ContentKind>, excludedCategoryKeys: Collection<String>): List<Int> = listOf(1980)

    override suspend fun recentTitles(limit: Int): List<PosterRow> = listOf(fPosterA)
}

private class FilterFakeCatalog : CatalogRepository {
    override fun categories(sourceId: SourceId, kind: ContentKind, includeHidden: Boolean): Flow<List<Category>> = TODO()
    override fun channels(sourceId: SourceId, categoryId: RemoteId): androidx.paging.PagingSource<Int, ChannelRow> = TODO()
    override fun channelsAll(sourceId: SourceId, excludedCategories: Collection<String>): androidx.paging.PagingSource<Int, ChannelRow> = TODO()
    override suspend fun channelListAll(sourceId: SourceId, excludedCategories: Collection<String>): List<ChannelRow> = emptyList()
    override suspend fun channelOrderAll(sourceId: SourceId, excludedCategories: Collection<String>): List<ContentKey> = emptyList()
    override suspend fun channelList(sourceId: SourceId, categoryId: RemoteId): List<ChannelRow> = TODO()
    override suspend fun channelNeighbours(key: ContentKey, categoryId: RemoteId): Pair<ChannelRow?, ChannelRow?> = TODO()
    override suspend fun channelOrder(sourceId: SourceId, categoryId: RemoteId): List<ContentKey> = TODO()
    override suspend fun channel(key: ContentKey): ChannelRow? = TODO()
    override fun vod(sourceId: SourceId, categoryId: RemoteId?): androidx.paging.PagingSource<Int, PosterRow> = TODO()
    override fun series(sourceId: SourceId, categoryId: RemoteId?): androidx.paging.PagingSource<Int, PosterRow> = TODO()
    override fun vodAll(excludedCategoryKeys: Collection<String>): androidx.paging.PagingSource<Int, PosterRow> = TODO()
    override fun seriesAll(excludedCategoryKeys: Collection<String>): androidx.paging.PagingSource<Int, PosterRow> = TODO()
    override fun recentlyAdded(kind: ContentKind, limit: Int): Flow<List<PosterRow>> = TODO()
    override suspend fun poster(key: ContentKey): PosterRow? = when (key) {
        fKeyA -> fPosterA
        fKeyB -> fPosterB
        else -> null
    }
    override fun counts(sourceId: SourceId): Flow<Map<ContentKind, Long>> = TODO()
}
