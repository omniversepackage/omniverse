package com.yodesla.omniverse.feature.live

import androidx.paging.PagingSource
import com.yodesla.omniverse.core.data.CatalogRepository
import com.yodesla.omniverse.core.data.ChannelRow
import com.yodesla.omniverse.core.data.Clock
import com.yodesla.omniverse.core.data.EpgRepository
import com.yodesla.omniverse.core.data.NowNext
import com.yodesla.omniverse.core.data.PosterRow
import com.yodesla.omniverse.core.data.Progress
import com.yodesla.omniverse.core.data.SourceRepository
import com.yodesla.omniverse.core.data.SourceSummary
import com.yodesla.omniverse.core.data.UserDataRepository
import com.yodesla.omniverse.core.model.AccountInfo
import com.yodesla.omniverse.core.model.Category
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.ProgrammeRecord
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.SeriesDetail
import com.yodesla.omniverse.core.model.SeriesRecord
import com.yodesla.omniverse.core.model.SourceId
import com.yodesla.omniverse.core.model.SourceKind
import com.yodesla.omniverse.core.model.TimeWindow
import com.yodesla.omniverse.core.model.VodDetail
import com.yodesla.omniverse.core.model.VodRecord
import com.yodesla.omniverse.core.source.ContentSource
import com.yodesla.omniverse.core.source.SourceConfig
import com.yodesla.omniverse.feature.live.guide.GuideViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Task 120: Live and Guide must pick the first LIVE-CAPABLE source in saved order, skipping a
 * non-live provider (Plex) that happens to sort first. EPG stays source-scoped; no cross-source merge.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveSourceSelectionTest {
    private val dispatcher = StandardTestDispatcher()
    private val plex = SourceId("plex")
    private val xtream = SourceId("xtream")
    private val m3u = SourceId("m3u")

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun summary(id: SourceId, kind: SourceKind) = SourceSummary(id, kind, id.value, null, null, null)

    @Test
    fun liveSkipsNonLivePlexAndSelectsTheIptvSource() = runTest(dispatcher) {
        val vm = LiveViewModel(
            FakeSources(listOf(summary(plex, SourceKind.PLEX), summary(xtream, SourceKind.XTREAM))),
            FakeCatalog(), FakeEpg(), SettingsOnly(), Clock { 0L },
        )
        runCurrent()
        assertEquals(xtream, vm.state.value.sourceId)
    }

    @Test
    fun guideSkipsNonLivePlexAndSelectsTheIptvSource() = runTest(dispatcher) {
        val vm = GuideViewModel(
            FakeSources(listOf(summary(plex, SourceKind.PLEX), summary(xtream, SourceKind.XTREAM))),
            FakeCatalog(), FakeEpg(), Clock { 0L },
        )
        runCurrent()
        assertEquals(xtream, vm.state.value.sourceId)
    }

    @Test
    fun livePreservesSavedOrderBetweenTwoIptvSources() = runTest(dispatcher) {
        val vm = LiveViewModel(
            FakeSources(listOf(summary(xtream, SourceKind.XTREAM), summary(m3u, SourceKind.M3U))),
            FakeCatalog(), FakeEpg(), SettingsOnly(), Clock { 0L },
        )
        runCurrent()
        assertEquals(xtream, vm.state.value.sourceId)
    }

    @Test
    fun guidePreservesSavedOrderBetweenTwoIptvSources() = runTest(dispatcher) {
        val vm = GuideViewModel(
            FakeSources(listOf(summary(m3u, SourceKind.M3U), summary(xtream, SourceKind.XTREAM))),
            FakeCatalog(), FakeEpg(), Clock { 0L },
        )
        runCurrent()
        assertEquals(m3u, vm.state.value.sourceId)
    }

    @Test
    fun noLiveCapableSourceLeavesLiveWithoutASource() = runTest(dispatcher) {
        val vm = LiveViewModel(
            FakeSources(listOf(summary(plex, SourceKind.PLEX))),
            FakeCatalog(), FakeEpg(), SettingsOnly(), Clock { 0L },
        )
        runCurrent()
        assertNull(vm.state.value.sourceId)
        assertEquals(true, vm.state.value.noSources)
    }

    // ---------------- fakes ----------------

    private class FakeSources(private val list: List<SourceSummary>) : SourceRepository {
        override fun sources(): Flow<List<SourceSummary>> = flowOf(list)
        override suspend fun add(config: SourceConfig) = error("unused")
        override suspend fun update(config: SourceConfig) = Unit
        override suspend fun remove(id: SourceId) = Unit
        override suspend fun config(id: SourceId): SourceConfig? = null
        override suspend fun contentSource(id: SourceId): ContentSource? = null
        override suspend fun probe(config: SourceConfig): AccountInfo = error("unused")
    }

    private inner class FakeCatalog : CatalogRepository {
        override fun categories(sourceId: SourceId, kind: ContentKind, includeHidden: Boolean): Flow<List<Category>> =
            flowOf(listOf(Category(sourceId, ContentKind.LIVE, RemoteId("sports"), "Sports", null, 0)))
        override fun channels(sourceId: SourceId, categoryId: RemoteId): PagingSource<Int, ChannelRow> = error("unused")
        override fun channelsAll(sourceId: SourceId, excludedCategories: Collection<String>): PagingSource<Int, ChannelRow> = error("unused")
        override suspend fun channelListAll(sourceId: SourceId, excludedCategories: Collection<String>): List<ChannelRow> = emptyList()
        override suspend fun channelOrderAll(sourceId: SourceId, excludedCategories: Collection<String>): List<ContentKey> = emptyList()
        override suspend fun channelList(sourceId: SourceId, categoryId: RemoteId): List<ChannelRow> = emptyList()
        override suspend fun channelNeighbours(key: ContentKey, categoryId: RemoteId): Pair<ChannelRow?, ChannelRow?> = null to null
        override suspend fun channel(key: ContentKey): ChannelRow? = null
        override suspend fun channelOrder(sourceId: SourceId, categoryId: RemoteId): List<ContentKey> = emptyList()
        override fun vod(sourceId: SourceId, categoryId: RemoteId?): PagingSource<Int, PosterRow> = error("unused")
        override fun series(sourceId: SourceId, categoryId: RemoteId?): PagingSource<Int, PosterRow> = error("unused")
        override fun vodAll(excludedCategoryKeys: Collection<String>): PagingSource<Int, PosterRow> = error("unused")
        override fun seriesAll(excludedCategoryKeys: Collection<String>): PagingSource<Int, PosterRow> = error("unused")
        override fun recentlyAdded(kind: ContentKind, limit: Int): Flow<List<PosterRow>> = flowOf(emptyList())
        override suspend fun poster(key: ContentKey): PosterRow? = null
        override fun counts(sourceId: SourceId): Flow<Map<ContentKind, Long>> = flowOf(emptyMap())
    }

    private class FakeEpg : EpgRepository {
        override suspend fun nowNext(sourceId: SourceId, epgKeys: Collection<String>, atMs: Long): Map<String, NowNext> = emptyMap()
        override suspend fun programmes(sourceId: SourceId, epgKey: String, window: TimeWindow): List<ProgrammeRecord> = emptyList()
    }

    private class SettingsOnly : UserDataRepository {
        override fun favorites(kind: ContentKind?): Flow<List<ContentKey>> = flowOf(emptyList())
        override fun isFavorite(key: ContentKey): Flow<Boolean> = flowOf(false)
        override suspend fun setFavorite(key: ContentKey, favorite: Boolean) = Unit
        override suspend fun saveProgress(key: ContentKey, parentId: RemoteId?, positionMs: Long, durationMs: Long?) = Unit
        override fun continueWatching(limit: Int): Flow<List<Progress>> = flowOf(emptyList())
        override suspend fun progress(key: ContentKey): Progress? = null
        override suspend fun recordChannelWatched(key: ContentKey) = Unit
        override fun recentChannels(limit: Int): Flow<List<ContentKey>> = flowOf(emptyList())
        override suspend fun setHidden(key: ContentKey, hidden: Boolean) = Unit
        override fun setting(key: String): Flow<String?> = flowOf(null)
        override suspend fun putSetting(key: String, value: String) = Unit
        override fun hiddenCategoryKeys(): Flow<Set<String>> = flowOf(emptySet())
        override suspend fun setCategoryHidden(sourceId: SourceId, kind: ContentKind, categoryId: String, hidden: Boolean) = Unit
    }
}
