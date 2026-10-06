package com.yodesla.omniverse.feature.vod

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.yodesla.omniverse.core.data.BrowseSort
import com.yodesla.omniverse.core.data.CatalogRepository
import com.yodesla.omniverse.core.data.ChannelRow
import com.yodesla.omniverse.core.data.CompletedShow
import com.yodesla.omniverse.core.data.LibraryCollection
import com.yodesla.omniverse.core.data.PosterRow
import com.yodesla.omniverse.core.data.Progress
import com.yodesla.omniverse.core.data.ShowEverything
import com.yodesla.omniverse.core.data.SmartCollectionFilter
import com.yodesla.omniverse.core.data.SourceRepository
import com.yodesla.omniverse.core.data.SourceSummary
import com.yodesla.omniverse.core.data.UserDataRepository
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
import com.yodesla.omniverse.core.source.ContentSource
import com.yodesla.omniverse.core.source.SourceConfig
import com.yodesla.omniverse.core.source.SyncDiagnostics
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

private val src = SourceId("s")
private val keyA = ContentKey(src, ContentKind.VOD, RemoteId("a1"))
private val posterA = PosterRow(keyA, "Movie A", null, null, null, RemoteId("a"))

/** Task 115: the Sort control cycles the four choices and every browse path asks for the sort. */
@OptIn(ExperimentalCoroutinesApi::class)
class VodBrowseSortTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
    private suspend fun collectOnce(vm: VodBrowseViewModel) {
        vm.items.first().flow.filterIsInstance<androidx.paging.PageEvent.Insert<PosterRow>>()
            .first().pages.flatMap { it.data }
    }

    @Test fun sortCyclesThroughAllFourChoices() = runTest(dispatcher) {
        val rec = RecordingCatalog()
        val vm = VodBrowseViewModel(ContentKind.VOD, SortFakeSources(), rec, SortFakeUserData(), ShowEverything)
        assertEquals(BrowseSort.PROVIDER, vm.state.value.sort)
        vm.toggleSort(); assertEquals(BrowseSort.ALPHABETICAL, vm.state.value.sort)
        vm.toggleSort(); assertEquals(BrowseSort.RELEASE_YEAR, vm.state.value.sort)
        vm.toggleSort(); assertEquals(BrowseSort.RATING, vm.state.value.sort)
        vm.toggleSort(); assertEquals(BrowseSort.PROVIDER, vm.state.value.sort)
    }

    @Test fun allSourcesPathRequestsTheChosenSort() = runTest(dispatcher) {
        val rec = RecordingCatalog()
        val vm = VodBrowseViewModel(ContentKind.VOD, SortFakeSources(), rec, SortFakeUserData(), ShowEverything)
        collectOnce(vm)
        assertEquals("vodAllSorted:PROVIDER", rec.lastCall)
        vm.setSort(BrowseSort.RELEASE_YEAR)
        collectOnce(vm)
        assertEquals("vodAllSorted:RELEASE_YEAR", rec.lastCall)
        vm.setSort(BrowseSort.RATING)
        collectOnce(vm)
        assertEquals("vodAllSorted:RATING", rec.lastCall)
    }

    @Test fun perSourcePathRequestsTheChosenSort() = runTest(dispatcher) {
        val rec = RecordingCatalog()
        val vm = VodBrowseViewModel(ContentKind.VOD, SortFakeSources(), rec, SortFakeUserData(), ShowEverything)
        vm.select(vm.state.value.categories.first { it.id != null }.id!!)
        collectOnce(vm)
        assertEquals("vodSorted:PROVIDER", rec.lastCall)
        vm.setSort(BrowseSort.ALPHABETICAL)
        collectOnce(vm)
        assertEquals("vodSorted:ALPHABETICAL", rec.lastCall)
    }

    @Test fun filteredPathRequestsTheChosenSort() = runTest(dispatcher) {
        val rec = RecordingCatalog()
        val vm = VodBrowseViewModel(ContentKind.VOD, SortFakeSources(), rec, SortFakeUserData(), ShowEverything)
        vm.setFilter(SmartCollectionFilter(yearFrom = 2000))
        collectOnce(vm)
        assertEquals("vodFilteredSorted:PROVIDER", rec.lastCall)
        vm.setSort(BrowseSort.RATING)
        collectOnce(vm)
        assertEquals("vodFilteredSorted:RATING", rec.lastCall)
    }

    @Test fun sortLabelsCoverAllFourChoices() {
        assertEquals("Provider", browseSortLabel(BrowseSort.PROVIDER))
        assertEquals("A–Z", browseSortLabel(BrowseSort.ALPHABETICAL))
        assertEquals("Year", browseSortLabel(BrowseSort.RELEASE_YEAR))
        assertEquals("Rating", browseSortLabel(BrowseSort.RATING))
    }

    // Task 117: a Library pages through the same SQL-sorted queries as browse; Crunchyroll keeps
    // its global anime queries, and the filter is NOT suppressed on a branded page's Library.
    @Test fun crunchyrollLibrarySortsThroughTheAnimeQueries() = runTest(dispatcher) {
        val rec = RecordingCatalog().apply { catName = "Crunchyroll" }
        val vm = VodBrowseViewModel(ContentKind.VOD, SortFakeSources(), rec, SortFakeUserData(), ShowEverything)
        vm.select(vm.state.value.categories.first { it.id != null }.id!!)
        vm.openLibrary()
        collectOnce(vm)
        assertEquals("animeLibrarySorted:PROVIDER", rec.lastCall)
        vm.setSort(BrowseSort.ALPHABETICAL)
        collectOnce(vm)
        assertEquals("animeLibrarySorted:ALPHABETICAL", rec.lastCall)
    }

    @Test fun crunchyrollLibraryFilterRunsInSql() = runTest(dispatcher) {
        val rec = RecordingCatalog().apply { catName = "Crunchyroll" }
        val vm = VodBrowseViewModel(ContentKind.VOD, SortFakeSources(), rec, SortFakeUserData(), ShowEverything)
        vm.select(vm.state.value.categories.first { it.id != null }.id!!)
        vm.openLibrary()
        vm.setFilter(SmartCollectionFilter(yearFrom = 2000))
        collectOnce(vm)
        assertEquals("animeLibraryFilteredSorted:PROVIDER", rec.lastCall)
    }

    @Test fun brandLibraryAppliesTheFilterTheServicePageIgnores() = runTest(dispatcher) {
        val rec = RecordingCatalog().apply { catName = "Netflix" }
        val vm = VodBrowseViewModel(ContentKind.VOD, SortFakeSources(), rec, SortFakeUserData(), ShowEverything)
        vm.select(vm.state.value.categories.first { it.id != null }.id!!)
        vm.setFilter(SmartCollectionFilter(yearFrom = 2000))
        collectOnce(vm)
        assertEquals("vodSorted:PROVIDER", rec.lastCall) // the service page owns its layout (task 84i)
        vm.openLibrary()
        collectOnce(vm)
        assertEquals("vodFilteredSorted:PROVIDER", rec.lastCall) // the Library is a browse page (task 117)
    }
}

private class SortListPagingSource<T : Any>(private val items: List<T>) : PagingSource<Int, T>() {
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, T> = LoadResult.Page(items, prevKey = null, nextKey = null)
    override fun getRefreshKey(state: PagingState<Int, T>): Int? = null
}

private class SortFakeSources : SourceRepository {
    override fun sources(): Flow<List<SourceSummary>> = flowOf(listOf(SourceSummary(src, SourceKind.XTREAM, "Mock", null, null, null)))
    override suspend fun add(config: SourceConfig): SourceId = TODO()
    override suspend fun update(config: SourceConfig) = TODO()
    override suspend fun remove(id: SourceId) = TODO()
    override suspend fun config(id: SourceId): SourceConfig? = TODO()
    override suspend fun contentSource(id: SourceId): ContentSource = SortFakeContentSource()
    override suspend fun probe(config: SourceConfig): AccountInfo = TODO()
}

private class SortFakeContentSource : ContentSource {
    override val id = src
    override val kind = SourceKind.XTREAM
    override val capabilities: Set<Capability> = emptySet()
    override suspend fun accountInfo(): AccountInfo = TODO()
    override fun liveCategories(): Flow<Category> = flowOf()
    override fun liveChannels(diagnostics: SyncDiagnostics): Flow<ChannelRecord> = flowOf()
    override fun vodCategories(): Flow<Category> = flowOf(Category(src, ContentKind.VOD, RemoteId("a"), "Cat A", null, 0))
    override fun vodItems(diagnostics: SyncDiagnostics): Flow<VodRecord> = flowOf()
    override fun seriesCategories(): Flow<Category> = flowOf()
    override fun series(diagnostics: SyncDiagnostics): Flow<SeriesRecord> = flowOf()
    override suspend fun vodDetail(id: RemoteId): VodDetail = TODO()
    override suspend fun seriesDetail(id: RemoteId): SeriesDetail = SeriesDetail(SeriesRecord(src, id, id.value, null, emptyList(), emptyList(), null, null, null, null, null, 0), null, null, listOf(Season(1, null, null, listOf(Episode(src, RemoteId("e1"), id, 1, 1, "E1", null, null, null, null, null)))))
    override fun epg(window: TimeWindow, channelKeys: Set<String>?, diagnostics: SyncDiagnostics): Flow<ProgrammeRecord> = flowOf()
    override suspend fun shortEpg(channelId: RemoteId, limit: Int): List<ProgrammeRecord> = emptyList()
    override suspend fun playback(request: PlaybackRequest): PlaybackSpec = TODO()
}

/** Records which browse method the VM called and with which sort; rows are constant. */
private class RecordingCatalog : CatalogRepository {
    var lastCall: String? = null
    var catName: String = "Cat A"
    private fun rows() = listOf(posterA)
    private fun record(tag: String, sort: BrowseSort) = SortListPagingSource(rows()).also { lastCall = "$tag:$sort" }
    override fun categories(sourceId: SourceId, kind: ContentKind, includeHidden: Boolean): Flow<List<Category>> =
        flowOf(listOf(Category(sourceId, kind, RemoteId("a"), catName, null, 0)))
    override fun channels(sourceId: SourceId, categoryId: RemoteId): PagingSource<Int, ChannelRow> = TODO()
    override fun channelsAll(sourceId: SourceId, excludedCategories: Collection<String>): PagingSource<Int, ChannelRow> = TODO()
    override suspend fun channelListAll(sourceId: SourceId, excludedCategories: Collection<String>): List<ChannelRow> = emptyList()
    override suspend fun channelOrderAll(sourceId: SourceId, excludedCategories: Collection<String>): List<ContentKey> = emptyList()
    override suspend fun channelList(sourceId: SourceId, categoryId: RemoteId): List<ChannelRow> = TODO()
    override suspend fun channelNeighbours(key: ContentKey, categoryId: RemoteId): Pair<ChannelRow?, ChannelRow?> = TODO()
    override suspend fun channelOrder(sourceId: SourceId, categoryId: RemoteId): List<ContentKey> = emptyList()
    override suspend fun channel(key: ContentKey): ChannelRow? = TODO()
    override fun vod(sourceId: SourceId, categoryId: RemoteId?): PagingSource<Int, PosterRow> = SortListPagingSource(rows())
    override fun series(sourceId: SourceId, categoryId: RemoteId?): PagingSource<Int, PosterRow> = SortListPagingSource(rows())
    override fun vodAll(excludedCategoryKeys: Collection<String>): PagingSource<Int, PosterRow> = SortListPagingSource(rows())
    override fun seriesAll(excludedCategoryKeys: Collection<String>): PagingSource<Int, PosterRow> = SortListPagingSource(rows())
    override fun vodSorted(sourceId: SourceId, categoryId: RemoteId?, sort: BrowseSort): PagingSource<Int, PosterRow> = record("vodSorted", sort)
    override fun seriesSorted(sourceId: SourceId, categoryId: RemoteId?, sort: BrowseSort): PagingSource<Int, PosterRow> = record("seriesSorted", sort)
    override fun vodAllSorted(excludedCategoryKeys: Collection<String>, sort: BrowseSort): PagingSource<Int, PosterRow> = record("vodAllSorted", sort)
    override fun seriesAllSorted(excludedCategoryKeys: Collection<String>, sort: BrowseSort): PagingSource<Int, PosterRow> = record("seriesAllSorted", sort)
    override fun animeLibrarySorted(kind: ContentKind, excludedCategoryKeys: Collection<String>, sort: BrowseSort): PagingSource<Int, PosterRow> = record("animeLibrarySorted", sort)
    override fun animeLibraryFilteredSorted(
        kind: ContentKind, excludedCategoryKeys: Collection<String>,
        filter: SmartCollectionFilter, sort: BrowseSort,
    ): PagingSource<Int, PosterRow> = record("animeLibraryFilteredSorted", sort)
    override fun vodFilteredSorted(
        sourceId: SourceId?, categoryId: RemoteId?, filter: SmartCollectionFilter,
        excludedCategoryKeys: Collection<String>, sort: BrowseSort,
    ): PagingSource<Int, PosterRow> = record("vodFilteredSorted", sort)
    override fun seriesFilteredSorted(
        sourceId: SourceId?, categoryId: RemoteId?, filter: SmartCollectionFilter,
        excludedCategoryKeys: Collection<String>, sort: BrowseSort,
    ): PagingSource<Int, PosterRow> = record("seriesFilteredSorted", sort)
    override suspend fun genreOptions(kind: ContentKind, sourceId: SourceId?, categoryId: RemoteId?, excludedCategoryKeys: Collection<String>): List<String> = listOf("Action")
    override fun recentlyAdded(kind: ContentKind, limit: Int): Flow<List<PosterRow>> = flowOf(emptyList())
    override suspend fun poster(key: ContentKey): PosterRow? = posterA
    override fun counts(sourceId: SourceId): Flow<Map<ContentKind, Long>> = TODO()
}

private class SortFakeUserData : UserDataRepository {
    override fun favorites(kind: ContentKind?): Flow<List<ContentKey>> = flowOf(emptyList())
    override fun isFavorite(key: ContentKey): Flow<Boolean> = flowOf(false)
    override suspend fun setFavorite(key: ContentKey, favorite: Boolean) = TODO()
    override suspend fun saveProgress(key: ContentKey, parentId: RemoteId?, positionMs: Long, durationMs: Long?) = TODO()
    override fun continueWatching(limit: Int): Flow<List<Progress>> = flowOf(emptyList())
    override fun completedShows(sinceMs: Long, limit: Int): Flow<List<CompletedShow>> = flowOf(emptyList())
    override fun hiddenCategoryKeys(): Flow<Set<String>> = flowOf(emptySet())
    override suspend fun setCategoryHidden(sourceId: SourceId, kind: ContentKind, categoryId: String, hidden: Boolean) {}
    override suspend fun progress(key: ContentKey): Progress? = null
    override suspend fun recordChannelWatched(key: ContentKey) = TODO()
    override fun recentChannels(limit: Int): Flow<List<ContentKey>> = flowOf(emptyList())
    override suspend fun setHidden(key: ContentKey, hidden: Boolean) = TODO()
    override fun setting(key: String): Flow<String?> = flowOf(null)
    override suspend fun putSetting(key: String, value: String) {}
    override suspend fun saveCollection(value: LibraryCollection) {}
}
