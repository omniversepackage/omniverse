package com.yodesla.omniverse.feature.live.guide

import androidx.paging.PagingSource
import com.yodesla.omniverse.core.data.*
import com.yodesla.omniverse.core.data.guide.GuideFilter
import com.yodesla.omniverse.core.model.*
import com.yodesla.omniverse.core.source.ContentSource
import com.yodesla.omniverse.core.source.SourceConfig
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/** Task 98: the header filter chips apply within the category and are remembered per profile. */
@OptIn(ExperimentalCoroutinesApi::class)
class GuideFilterTest {
    private val dispatcher = StandardTestDispatcher()
    private val src = SourceId("s")
    private val catC = RemoteId("c")
    private val catD = RemoteId("d")

    private fun row(name: String, cat: RemoteId) =
        ChannelRow(ContentKey(src, ContentKind.LIVE, RemoteId(name.lowercase().replace(' ', '-'))), null, name, null, null, 0, cat)

    private val espn = row("ESPN HD", catC)
    private val cnn = row("CNN", catC)
    private val cartoon = row("Cartoon Network", catC)
    private val hbo = row("HBO", catC)
    private val espnD = row("ESPN HD", catD)
    private val cartoonD = row("Cartoon Network", catD)

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun sportsChipFiltersWithinTheCategoryAndIsPersisted() = runTest(dispatcher) {
        val data = FakeUserData()
        val vm = GuideViewModel(FakeSources(), FakeCatalog(), FakeEpg(), Clock { 0L }, ShowEverything, data)
        advanceUntilIdle()
        vm.selectCategory("c")
        advanceUntilIdle()
        assertEquals(4, vm.state.value.grid?.channelCount)
        vm.selectFilter(GuideFilter.SPORTS)
        advanceUntilIdle()
        assertEquals(GuideFilter.SPORTS, vm.state.value.filter)
        assertEquals("c", vm.state.value.selectedCategoryId)
        assertEquals(1, vm.state.value.grid?.channelCount)
        assertEquals("ESPN HD", vm.state.value.grid?.channel(0)?.name)
        assertEquals("SPORTS", data.puts["guide_filter"])
    }

    @Test
    fun savedFilterIsRestoredWhenTheGuideOpens() = runTest(dispatcher) {
        val vm = GuideViewModel(
            FakeSources(), FakeCatalog(), FakeEpg(), Clock { 0L }, ShowEverything,
            FakeUserData(settings = mapOf(GuideViewModel.FILTER_KEY to "MOVIES")),
        )
        advanceUntilIdle()
        assertEquals(GuideFilter.MOVIES, vm.state.value.filter)
        assertEquals(1, vm.state.value.grid?.channelCount)
        assertEquals("HBO", vm.state.value.grid?.channel(0)?.name)
    }

    @Test
    fun filterSurvivesCategorySwitchAndAllChipRestoresTheCategory() = runTest(dispatcher) {
        val vm = GuideViewModel(FakeSources(), FakeCatalog(), FakeEpg(), Clock { 0L }, ShowEverything, FakeUserData())
        advanceUntilIdle()
        vm.selectCategory("c")
        advanceUntilIdle()
        vm.selectFilter(GuideFilter.KIDS)
        advanceUntilIdle()
        assertEquals(1, vm.state.value.grid?.channelCount)
        assertEquals("Cartoon Network", vm.state.value.grid?.channel(0)?.name)
        vm.selectCategory("d")
        advanceUntilIdle()
        assertEquals(GuideFilter.KIDS, vm.state.value.filter)
        assertEquals(1, vm.state.value.grid?.channelCount)
        assertEquals("Cartoon Network", vm.state.value.grid?.channel(0)?.name)
        vm.selectFilter(GuideFilter.ALL)
        advanceUntilIdle()
        assertEquals(2, vm.state.value.grid?.channelCount)
    }

    @Test
    fun favoritesChipKeepsOnlyFavoritedRowsOfTheCategory() = runTest(dispatcher) {
        val data = FakeUserData(favorites = listOf(espn.key))
        val vm = GuideViewModel(FakeSources(), FakeCatalog(), FakeEpg(), Clock { 0L }, ShowEverything, data)
        advanceUntilIdle()
        vm.selectCategory("c")
        advanceUntilIdle()
        assertEquals(4, vm.state.value.grid?.channelCount)
        vm.selectFilter(GuideFilter.FAVORITES)
        advanceUntilIdle()
        assertEquals(1, vm.state.value.grid?.channelCount)
        assertEquals("ESPN HD", vm.state.value.grid?.channel(0)?.name)
    }

    @Test
    fun favoritesChipRefiltersWhenFavoritesChange() = runTest(dispatcher) {
        val data = FakeUserData(favorites = listOf(espn.key))
        val vm = GuideViewModel(FakeSources(), FakeCatalog(), FakeEpg(), Clock { 0L }, ShowEverything, data)
        advanceUntilIdle()
        vm.selectCategory("c")
        advanceUntilIdle()
        vm.selectFilter(GuideFilter.FAVORITES)
        advanceUntilIdle()
        assertEquals(1, vm.state.value.grid?.channelCount)
        data.favoritesFlow.value = listOf(espn.key, hbo.key)
        advanceUntilIdle()
        assertEquals(2, vm.state.value.grid?.channelCount)
    }

    private inner class FakeSources : SourceRepository {
        override fun sources(): Flow<List<SourceSummary>> = flowOf(listOf(SourceSummary(src, SourceKind.XTREAM, "Source", null, null, null)))
        override suspend fun add(config: SourceConfig): SourceId = error("unused")
        override suspend fun update(config: SourceConfig) = error("unused")
        override suspend fun remove(id: SourceId) = error("unused")
        override suspend fun config(id: SourceId): SourceConfig? = error("unused")
        override suspend fun contentSource(id: SourceId): ContentSource? = error("unused")
        override suspend fun probe(config: SourceConfig): AccountInfo = error("unused")
    }

    private inner class FakeCatalog : CatalogRepository {
        override fun categories(sourceId: SourceId, kind: ContentKind, includeHidden: Boolean): Flow<List<Category>> =
            flowOf(listOf(Category(src, ContentKind.LIVE, catC, "Category", null, 0), Category(src, ContentKind.LIVE, catD, "Other", null, 0)))
        override fun channels(sourceId: SourceId, categoryId: RemoteId): PagingSource<Int, ChannelRow> = error("unused")
        override fun channelsAll(sourceId: SourceId, excludedCategories: Collection<String>): PagingSource<Int, ChannelRow> = error("unused")
        override suspend fun channelListAll(sourceId: SourceId, excludedCategories: Collection<String>): List<ChannelRow> =
            listOf(espn, cnn, cartoon, hbo).map { it.copy(categoryId = RemoteId(ALL_CHANNELS)) }
        override suspend fun channelOrderAll(sourceId: SourceId, excludedCategories: Collection<String>): List<ContentKey> = emptyList()
        override suspend fun channelList(sourceId: SourceId, categoryId: RemoteId): List<ChannelRow> = when (categoryId.value) {
            "c" -> listOf(espn, cnn, cartoon, hbo)
            "d" -> listOf(espnD, cartoonD)
            else -> emptyList()
        }
        override suspend fun channelNeighbours(key: ContentKey, categoryId: RemoteId): Pair<ChannelRow?, ChannelRow?> = error("unused")
        override suspend fun channelOrder(sourceId: SourceId, categoryId: RemoteId): List<ContentKey> = error("unused")
        override suspend fun channel(key: ContentKey): ChannelRow? =
            (listOf(espn, cnn, cartoon, hbo, espnD, cartoonD) + channelListAll(src, emptyList())).firstOrNull { it.key == key }
        override fun vod(sourceId: SourceId, categoryId: RemoteId?): PagingSource<Int, PosterRow> = error("unused")
        override fun series(sourceId: SourceId, categoryId: RemoteId?): PagingSource<Int, PosterRow> = error("unused")
        override fun vodAll(excludedCategoryKeys: Collection<String>): PagingSource<Int, PosterRow> = error("unused")
        override fun seriesAll(excludedCategoryKeys: Collection<String>): PagingSource<Int, PosterRow> = error("unused")
        override fun recentlyAdded(kind: ContentKind, limit: Int): Flow<List<PosterRow>> = error("unused")
        override suspend fun poster(key: ContentKey): PosterRow? = error("unused")
        override fun counts(sourceId: SourceId): Flow<Map<ContentKind, Long>> = error("unused")
    }

    private class FakeEpg : EpgRepository {
        override suspend fun nowNext(sourceId: SourceId, epgKeys: Collection<String>, atMs: Long): Map<String, NowNext> = emptyMap()
        override suspend fun programmes(sourceId: SourceId, epgKey: String, window: TimeWindow): List<ProgrammeRecord> = emptyList()
    }

    private inner class FakeUserData(
        favorites: List<ContentKey> = emptyList(),
        settings: Map<String, String> = emptyMap(),
    ) : UserDataRepository {
        val favoritesFlow = MutableStateFlow(favorites)
        private val settingsFlow = MutableStateFlow(settings)
        val puts = mutableMapOf<String, String>()
        override fun favorites(kind: ContentKind?): Flow<List<ContentKey>> = favoritesFlow
        override fun isFavorite(key: ContentKey): Flow<Boolean> = favoritesFlow.map { key in it }
        override suspend fun setFavorite(key: ContentKey, favorite: Boolean) {
            favoritesFlow.value = if (favorite) favoritesFlow.value + key else favoritesFlow.value - key
        }
        override suspend fun saveProgress(key: ContentKey, parentId: RemoteId?, positionMs: Long, durationMs: Long?) = Unit
        override fun continueWatching(limit: Int): Flow<List<Progress>> = flowOf(emptyList())
        override suspend fun progress(key: ContentKey): Progress? = null
        override suspend fun recordChannelWatched(key: ContentKey) = Unit
        override fun recentChannels(limit: Int): Flow<List<ContentKey>> = flowOf(emptyList())
        override suspend fun setHidden(key: ContentKey, hidden: Boolean) = Unit
        override fun setting(key: String): Flow<String?> = settingsFlow.map { it[key] }
        override suspend fun putSetting(key: String, value: String) {
            puts[key] = value
            settingsFlow.value = settingsFlow.value + (key to value)
        }
    }
}
