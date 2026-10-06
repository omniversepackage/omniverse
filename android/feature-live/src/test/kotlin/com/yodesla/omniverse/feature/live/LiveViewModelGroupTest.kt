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
import com.yodesla.omniverse.core.data.Visibility
import com.yodesla.omniverse.core.model.AccountInfo
import com.yodesla.omniverse.core.model.Category
import com.yodesla.omniverse.core.model.ChannelRecord
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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Task 67: opening a saved group skips channels that are gone or that the CURRENT parental policy
 * denies, so a stale group never tries to play a locked or deleted channel. When fewer than two
 * survive the caller is expected to refuse to open it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveViewModelGroupTest {
    private val dispatcher = StandardTestDispatcher()
    private val src = SourceId("s")
    private val general = RemoteId("general")
    private val adult = RemoteId("adult")
    private val visibility = MutableStateFlow<Visibility>({ _, _, _ -> true })
    private val gone = mutableSetOf<String>()

    private fun key(id: String) = ContentKey(src, ContentKind.LIVE, RemoteId(id))
    private val a = key("1")
    private val b = key("2")
    private val c = key("3") // adult category
    private val g = key("9") // no catalog row: the channel is gone

    private val rows = listOf(
        ChannelRow(a, 1, "Ch 1", null, "e1", 0, general),
        ChannelRow(b, 2, "Ch 2", null, "e2", 0, general),
        ChannelRow(c, 3, "Ch 3", null, "e3", 0, adult),
    )

    @BeforeTest fun setUp() {
        Dispatchers.setMain(dispatcher)
        gone.clear()
        visibility.value = { _, _, _ -> true }
    }

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private suspend fun vm(): LiveViewModel =
        LiveViewModel(FakeSources(), FakeCatalog(), FakeEpg(), SettingsOnly(), Clock { 0L }, visibility = visibility)

    private val denyAdult: Visibility = { kind, _, cat -> !(kind == "LIVE" && cat == "adult") }

    // LiveViewModel has a perpetual now/next ticker, so advanceUntilIdle() would deadlock chasing its
    // infinite delays. runCurrent() runs the queued init collectors (including the visibility one) at the
    // current virtual time without ever advancing into a future delay — which is all these tests need.
    @Test
    fun openingAGroupSkipsGoneChannels() = runTest(dispatcher) {
        gone += "9"
        val vm = vm()
        runCurrent()
        assertEquals(listOf(a, b, c), vm.openableGroupChannels(listOf(a, b, c, g)))
    }

    @Test
    fun openingAGroupSkipsChannelsThePolicyDenies() = runTest(dispatcher) {
        visibility.value = denyAdult
        val vm = vm()
        runCurrent()
        assertEquals(listOf(a, b), vm.openableGroupChannels(listOf(a, b, c)))
    }

    @Test
    fun kidsHardFilterPolicySkipsLockedChannelsEvenDuringAnUnlockedSession() = runTest(dispatcher) {
        // Audit 73 M2: the Live list is fed graph.vodVisibility — on a Kids profile the hard filter
        // denies the locked category with no unlock, so opening a group skips it like the gate does.
        visibility.value = { kind, _, cat -> !(kind == "LIVE" && cat == "adult") }
        val vm = vm()
        runCurrent()
        assertEquals(listOf(a, b), vm.openableGroupChannels(listOf(a, b, c)))
    }

    @Test
    fun aGroupWithTooFewOpenableChannelsRemainsBelowTwo() = runTest(dispatcher) {
        gone += "9"
        visibility.value = denyAdult
        val vm = vm()
        runCurrent()
        // c denied, g gone → only a survives, which is below the two-tile minimum.
        assertEquals(listOf(a), vm.openableGroupChannels(listOf(a, c, g)))
    }

    // ---------------- fakes ----------------

    private inner class FakeSources : SourceRepository {
        override fun sources(): Flow<List<SourceSummary>> =
            flowOf(listOf(SourceSummary(src, SourceKind.XTREAM, "S", null, null, null)))
        override suspend fun add(config: SourceConfig) = src
        override suspend fun update(config: SourceConfig) = Unit
        override suspend fun remove(id: SourceId) = Unit
        override suspend fun config(id: SourceId): SourceConfig? = null
        override suspend fun contentSource(id: SourceId): ContentSource? = null
        override suspend fun probe(config: SourceConfig): AccountInfo = error("unused")
    }

    private inner class FakeCatalog : CatalogRepository {
        override fun categories(sourceId: SourceId, kind: ContentKind, includeHidden: Boolean): Flow<List<Category>> = flowOf(emptyList())
        override fun channels(sourceId: SourceId, categoryId: RemoteId): PagingSource<Int, ChannelRow> = error("unused")
        override fun channelsAll(sourceId: SourceId, excludedCategories: Collection<String>): PagingSource<Int, ChannelRow> = error("unused")
        override suspend fun channelListAll(sourceId: SourceId, excludedCategories: Collection<String>): List<ChannelRow> = rows
        override suspend fun channelOrderAll(sourceId: SourceId, excludedCategories: Collection<String>): List<ContentKey> = emptyList()
        override suspend fun channelList(sourceId: SourceId, categoryId: RemoteId): List<ChannelRow> = rows
        override suspend fun channelNeighbours(key: ContentKey, categoryId: RemoteId): Pair<ChannelRow?, ChannelRow?> = null to null
        override suspend fun channel(key: ContentKey): ChannelRow? =
            rows.firstOrNull { it.key == key && it.key.remoteId.value !in gone }
        override suspend fun channelOrder(sourceId: SourceId, categoryId: RemoteId): List<ContentKey> = rows.map { it.key }
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

    private inner class SettingsOnly : UserDataRepository {
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
    }
}
