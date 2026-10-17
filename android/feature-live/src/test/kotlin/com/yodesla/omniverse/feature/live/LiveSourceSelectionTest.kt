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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
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

    @Test
    fun combinedGuideGroupsQualityCopiesAndKeepsProviderIdsAndEpgSeparate() = runTest(dispatcher) {
        fun row(source: SourceId, name: String) = ChannelRow(ContentKey(source, ContentKind.LIVE, RemoteId("1")), 1, name, null, "shared-epg", 0, RemoteId("sports"))
        val a = row(xtream, "ESPN HD")
        val b = row(m3u, "ESPN FHD")
        val c = row(m3u, "BBC News").copy(key = ContentKey(m3u, ContentKind.LIVE, RemoteId("2")))
        val repo = FakeSources(listOf(summary(xtream, SourceKind.XTREAM), summary(m3u, SourceKind.M3U)))
        val catalog = FakeCatalog(listOf(a, b, c))
        val epg = FakeEpg()
        val vm = GuideViewModel(repo, catalog, epg, Clock { 0L })
        runCurrent()
        assertEquals(2, vm.state.value.grid!!.channelCount)
        val secondCategory = vm.state.value.categories.last().id
        assertEquals(liveScopedId(m3u, "sports"), secondCategory)
        vm.selectCategory(secondCategory)
        runCurrent()
        assertEquals(2, vm.state.value.grid!!.channelCount)
        vm.state.value.grid!!.programmes(0, 0L, 100L)
        assertEquals(m3u, epg.requestedSources.last())
        val grouped = UnifiedLiveCatalog(repo, catalog).rows(LiveViewModel.ALL, xtream, { _, source, _ -> source != xtream.value })
        assertEquals(listOf(b, c), grouped)
    }

    @Test
    fun liveNowNextQueriesTheCorrectProviderForIdenticalEpgIds() = runTest(dispatcher) {
        val repo = FakeSources(listOf(summary(xtream, SourceKind.XTREAM), summary(m3u, SourceKind.M3U)))
        val epg = FakeEpg()
        val vm = LiveViewModel(repo, FakeCatalog(), epg, SettingsOnly(), Clock { 0L })
        val store = androidx.lifecycle.ViewModelStore().apply { put("live", vm) }
        runCurrent()
        vm.requestNowNext(listOf(liveScopedId(xtream, "same"), liveScopedId(m3u, "same")))
        runCurrent()
        advanceTimeBy(151)
        runCurrent()
        assertEquals(listOf(xtream, m3u), epg.nowNextSources)
        store.clear()
    }

    @Test
    fun addingOrRemovingProvidersUpdatesLiveAndGuideWithoutARestart() = runTest(dispatcher) {
        val repo = FakeSources(emptyList())
        val live = LiveViewModel(repo, FakeCatalog(), FakeEpg(), SettingsOnly(), Clock { 0L })
        val guide = GuideViewModel(repo, FakeCatalog(), FakeEpg(), Clock { 0L })
        runCurrent()
        assertEquals(true, live.state.value.noSources)
        repo.updates.value = listOf(summary(xtream, SourceKind.XTREAM), summary(m3u, SourceKind.M3U))
        runCurrent()
        assertEquals(false, live.state.value.noSources)
        assertEquals("All providers", live.state.value.sourceName)
        assertEquals(4, live.state.value.categories.size)
        assertEquals(4, guide.state.value.categories.size)
        repo.updates.value = emptyList()
        runCurrent()
        assertNull(live.state.value.sourceId)
        assertNull(guide.state.value.sourceId)
    }

    @Test
    fun providerFilterNarrowsGuideAndRestoresEverything() = runTest(dispatcher) {
        val repo = FakeSources(listOf(summary(xtream, SourceKind.XTREAM), summary(m3u, SourceKind.M3U)))
        fun row(source: SourceId) = ChannelRow(ContentKey(source, ContentKind.LIVE, RemoteId("1")), 1, "ESPN HD", null, "same", 0, RemoteId("sports"))
        val user = SettingsOnly()
        val vm = GuideViewModel(repo, FakeCatalog(listOf(row(xtream), row(m3u))), FakeEpg(), Clock { 0L }, userData = user)
        runCurrent()
        vm.providerFilter.toggle(xtream.value)
        runCurrent()
        assertEquals(liveScopedId(m3u, "1"), vm.state.value.grid!!.channel(0).id)
        assertEquals(3, vm.state.value.categories.size)
        vm.providerFilter.toggle(m3u.value)
        runCurrent()
        assertEquals(0, vm.state.value.grid!!.channelCount)
        vm.providerFilter.showAll()
        runCurrent()
        assertEquals(liveScopedId(xtream, "1"), vm.state.value.grid!!.channel(0).id)
        assertEquals(4, vm.state.value.categories.size)
    }

    // ---------------- fakes ----------------

    private class FakeSources(private val list: List<SourceSummary>) : SourceRepository {
        val updates = MutableStateFlow(list)
        override fun sources(): Flow<List<SourceSummary>> = updates
        override suspend fun add(config: SourceConfig) = error("unused")
        override suspend fun update(config: SourceConfig) = Unit
        override suspend fun remove(id: SourceId) = Unit
        override suspend fun config(id: SourceId): SourceConfig? = null
        override suspend fun contentSource(id: SourceId): ContentSource? = null
        override suspend fun probe(config: SourceConfig): AccountInfo = error("unused")
    }

    private inner class FakeCatalog(private val rows: List<ChannelRow> = emptyList()) : CatalogRepository {
        override fun categories(sourceId: SourceId, kind: ContentKind, includeHidden: Boolean): Flow<List<Category>> =
            flowOf(listOf(Category(sourceId, ContentKind.LIVE, RemoteId("sports"), "Sports", null, 0)))
        override fun channels(sourceId: SourceId, categoryId: RemoteId): PagingSource<Int, ChannelRow> = error("unused")
        override fun channelsAll(sourceId: SourceId, excludedCategories: Collection<String>): PagingSource<Int, ChannelRow> = error("unused")
        override suspend fun channelListAll(sourceId: SourceId, excludedCategories: Collection<String>): List<ChannelRow> = rows.filter { it.key.sourceId == sourceId && it.categoryId.value !in excludedCategories }
        override suspend fun channelOrderAll(sourceId: SourceId, excludedCategories: Collection<String>): List<ContentKey> = emptyList()
        override suspend fun channelList(sourceId: SourceId, categoryId: RemoteId): List<ChannelRow> = rows.filter { it.key.sourceId == sourceId && it.categoryId == categoryId }
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
        val requestedSources = mutableListOf<SourceId>()
        val nowNextSources = mutableListOf<SourceId>()
        override suspend fun nowNext(sourceId: SourceId, epgKeys: Collection<String>, atMs: Long): Map<String, NowNext> { nowNextSources += sourceId; return emptyMap() }
        override suspend fun programmes(sourceId: SourceId, epgKey: String, window: TimeWindow): List<ProgrammeRecord> { requestedSources += sourceId; return emptyList() }
    }

    private class SettingsOnly : UserDataRepository {
        private val settings = MutableStateFlow<Map<String, String>>(emptyMap())
        override fun favorites(kind: ContentKind?): Flow<List<ContentKey>> = flowOf(emptyList())
        override fun isFavorite(key: ContentKey): Flow<Boolean> = flowOf(false)
        override suspend fun setFavorite(key: ContentKey, favorite: Boolean) = Unit
        override suspend fun saveProgress(key: ContentKey, parentId: RemoteId?, positionMs: Long, durationMs: Long?) = Unit
        override fun continueWatching(limit: Int): Flow<List<Progress>> = flowOf(emptyList())
        override suspend fun progress(key: ContentKey): Progress? = null
        override suspend fun recordChannelWatched(key: ContentKey) = Unit
        override fun recentChannels(limit: Int): Flow<List<ContentKey>> = flowOf(emptyList())
        override suspend fun setHidden(key: ContentKey, hidden: Boolean) = Unit
        override fun setting(key: String): Flow<String?> = settings.map { it[key] }
        override suspend fun putSetting(key: String, value: String) { settings.value = settings.value + (key to value) }
        override fun hiddenCategoryKeys(): Flow<Set<String>> = flowOf(emptySet())
        override suspend fun setCategoryHidden(sourceId: SourceId, kind: ContentKind, categoryId: String, hidden: Boolean) = Unit
    }
}
