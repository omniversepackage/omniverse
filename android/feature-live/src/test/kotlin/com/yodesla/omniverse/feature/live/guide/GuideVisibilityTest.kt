package com.yodesla.omniverse.feature.live.guide

import androidx.paging.PagingSource
import com.yodesla.omniverse.core.data.*
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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class GuideVisibilityTest {
    private val dispatcher = StandardTestDispatcher()
    private val src = SourceId("s")
    private val cat = RemoteId("c")
    private val row = ChannelRow(ContentKey(src, ContentKind.LIVE, RemoteId("1")), 1, "Channel", null, null, 0, cat)
    private val visibility = MutableStateFlow<Visibility>({ _, _, _ -> true })

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun favoritesGridDropsLockedChannelWhenSessionRelocks() = runTest(dispatcher) {
        val vm = GuideViewModel(FakeSources(), FakeCatalog(), FakeEpg(), Clock { 0L }, visibility, FakeUserData())
        advanceUntilIdle()
        assertEquals(GuideViewModel.FAVORITES, vm.state.value.selectedCategoryId)
        assertEquals(1, vm.state.value.grid?.channelCount)
        assertEquals(true, vm.state.value.grid?.channel(0)?.favorite)

        visibility.value = { _, _, category -> category != "c" }
        advanceUntilIdle()
        assertEquals(GuideViewModel.FAVORITES, vm.state.value.selectedCategoryId)
        assertEquals(0, vm.state.value.grid?.channelCount)
    }

    @Test
    fun kidsHardFilterKeepsLockedCategoryOutOfTheGuideEvenDuringAnUnlockedSession() = runTest(dispatcher) {
        // Audit 73 M2: Guide is fed graph.vodVisibility; on a Kids profile the hard filter denies
        // the locked category with no unlock, so its channels never enter the grid at all.
        visibility.value = { _, _, category -> category != "c" }
        val vm = GuideViewModel(FakeSources(), FakeCatalog(), FakeEpg(), Clock { 0L }, visibility, FakeUserData())
        advanceUntilIdle()
        assertEquals(0, vm.state.value.grid?.channelCount)
    }

    // Task 84m: a category hidden from the Live list (hold-OK › Hide category) counts as locked in
    // the Guide too — gone from the category list, and the grid re-filters when it changes.
    @Test
    fun hiddenCategoryDropsOutOfTheGuideListAndSelectionFallsBack() = runTest(dispatcher) {
        val hidden = MutableStateFlow<Set<String>>(emptySet())
        val vm = GuideViewModel(FakeSources(), FakeCatalog(), FakeEpg(), Clock { 0L }, visibility, FakeUserData(hidden))
        advanceUntilIdle()
        vm.selectCategory("c")
        advanceUntilIdle()
        assertEquals(1, vm.state.value.grid?.channelCount)
        hidden.value = setOf("LIVE|s|c")
        advanceUntilIdle()
        assertEquals(false, vm.state.value.categories.any { it.id == "c" })
        assertEquals(GuideViewModel.FAVORITES, vm.state.value.selectedCategoryId)
        hidden.value = emptySet()
        advanceUntilIdle()
        assertEquals(true, vm.state.value.categories.any { it.id == "c" })
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
            flowOf(listOf(Category(src, ContentKind.LIVE, cat, "Category", null, 0)))
        override fun channels(sourceId: SourceId, categoryId: RemoteId): PagingSource<Int, ChannelRow> = error("unused")
        override fun channelsAll(sourceId: SourceId, excludedCategories: Collection<String>): androidx.paging.PagingSource<Int, com.yodesla.omniverse.core.data.ChannelRow> = TODO()
        override suspend fun channelListAll(sourceId: SourceId, excludedCategories: Collection<String>): List<com.yodesla.omniverse.core.data.ChannelRow> = emptyList()
        override suspend fun channelOrderAll(sourceId: SourceId, excludedCategories: Collection<String>): List<com.yodesla.omniverse.core.model.ContentKey> = emptyList()
        override suspend fun channelList(sourceId: SourceId, categoryId: RemoteId): List<ChannelRow> = listOf(row)
        override suspend fun channelNeighbours(key: ContentKey, categoryId: RemoteId): Pair<ChannelRow?, ChannelRow?> = error("unused")
        override suspend fun channelOrder(sourceId: SourceId, categoryId: RemoteId): List<ContentKey> = error("unused")
        override suspend fun channel(key: ContentKey): ChannelRow? = row.takeIf { it.key == key }
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
        private val hidden: Flow<Set<String>> = flowOf(emptySet()),
    ) : UserDataRepository {
        override fun favorites(kind: ContentKind?): Flow<List<ContentKey>> = flowOf(listOf(row.key))
        override fun isFavorite(key: ContentKey): Flow<Boolean> = flowOf(true)
        override suspend fun setFavorite(key: ContentKey, favorite: Boolean) = Unit
        override suspend fun saveProgress(key: ContentKey, parentId: RemoteId?, positionMs: Long, durationMs: Long?) = Unit
        override fun continueWatching(limit: Int): Flow<List<Progress>> = flowOf(emptyList())
        override suspend fun progress(key: ContentKey): Progress? = null
        override suspend fun recordChannelWatched(key: ContentKey) = Unit
        override fun recentChannels(limit: Int): Flow<List<ContentKey>> = flowOf(emptyList())
        override suspend fun setHidden(key: ContentKey, hidden: Boolean) = Unit
        override fun setting(key: String): Flow<String?> = flowOf(null)
        override suspend fun putSetting(key: String, value: String) = Unit
        override fun hiddenCategoryKeys(): Flow<Set<String>> = hidden
    }
}
