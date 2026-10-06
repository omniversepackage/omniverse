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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Task 84g: OK on a category opens the Guide (OpenGuide event with that category id) while dwell
 * selection stays silent, and hold-OK reorder keeps pinned entries first and saves the provider
 * order under `category_order_LIVE`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveViewModelCategoryTest {
    private val dispatcher = StandardTestDispatcher()
    private val src = SourceId("s")

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    // Same trick as LiveViewModelGroupTest: runCurrent() instead of advanceUntilIdle(), which would
    // deadlock chasing the now/next ticker's infinite delays.

    @Test
    fun okOnCategoryEmitsOpenGuideWithThatCategory() = runTest(dispatcher) {
        val vm = LiveViewModel(FakeSources(), FakeCatalog(), FakeEpg(), SettingsOnly(MutableStateFlow(null)), Clock { 0L })
        runCurrent()
        val seen = mutableListOf<LiveEvent>()
        val job = launch { vm.eventFlow.collect { seen += it } }
        runCurrent()
        vm.openGuideForCategory("news")
        runCurrent()
        assertEquals("news", vm.state.value.selectedCategoryId)
        assertEquals(listOf(LiveEvent.OpenGuide("news")), seen.filterIsInstance<LiveEvent.OpenGuide>())
        job.cancelAndJoin()
    }

    @Test
    fun dwellSelectingACategoryDoesNotOpenTheGuide() = runTest(dispatcher) {
        val vm = LiveViewModel(FakeSources(), FakeCatalog(), FakeEpg(), SettingsOnly(MutableStateFlow(null)), Clock { 0L })
        runCurrent()
        val seen = mutableListOf<LiveEvent>()
        val job = launch { vm.eventFlow.collect { seen += it } }
        runCurrent()
        vm.selectCategory("sports") // what the 300 ms dwell does
        runCurrent()
        assertEquals("sports", vm.state.value.selectedCategoryId)
        assertEquals(emptyList(), seen.filterIsInstance<LiveEvent.OpenGuide>())
        job.cancelAndJoin()
    }

    @Test
    fun holdOkMoveKeepsPinnedFirstAndSavesTheProviderOrder() = runTest(dispatcher) {
        val saved = MutableStateFlow<String?>(null)
        val vm = LiveViewModel(FakeSources(), FakeCatalog(), FakeEpg(), SettingsOnly(saved), Clock { 0L })
        runCurrent()
        assertEquals(listOf("__favorites", "__all", "sports", "news"), vm.state.value.categories.map { it.id })
        vm.moveCategory("news", -1)
        runCurrent()
        assertEquals(listOf("__favorites", "__all", "news", "sports"), vm.state.value.categories.map { it.id })
        vm.finishMove()
        runCurrent()
        assertEquals("news\nsports", saved.value)
        // A later provider refresh must not undo the saved order, and pinned stay at the top.
        vm.moveCategory("sports", -1)
        vm.finishMove()
        runCurrent()
        assertEquals("sports\nnews", saved.value)
        assertEquals(listOf("__favorites", "__all", "sports", "news"), vm.state.value.categories.map { it.id })
    }

    @Test
    fun holdOkOnAPinnedCategoryOpensNoMenu() = runTest(dispatcher) {
        val vm = LiveViewModel(FakeSources(), FakeCatalog(), FakeEpg(), SettingsOnly(MutableStateFlow(null)), Clock { 0L })
        runCurrent()
        vm.openCategoryMenu("__favorites")
        vm.openCategoryMenu("__all")
        runCurrent()
        assertEquals(null, vm.state.value.menuCategoryId)
    }

    @Test
    fun hideCategoryDropsItSelectsTheNextAndOffersUndo() = runTest(dispatcher) {
        val hidden = MutableStateFlow<Set<String>>(emptySet())
        val vm = LiveViewModel(FakeSources(), FakeCatalog(), FakeEpg(), SettingsOnly(MutableStateFlow(null), hidden), Clock { 0L })
        runCurrent()
        vm.openCategoryMenu("sports")
        runCurrent()
        assertEquals("sports", vm.state.value.menuCategoryId)
        vm.hideCategory("sports")
        runCurrent()
        assertEquals(listOf("__favorites", "__all", "news"), vm.state.value.categories.map { it.id })
        assertEquals("news", vm.state.value.selectedCategoryId)
        assertEquals(null, vm.state.value.menuCategoryId)
        assertEquals("sports", vm.state.value.hiddenNotice?.categoryId)
        assertEquals(setOf("LIVE|s|sports"), hidden.value)
        // Undo shows it again, in its old place, and clears the toast.
        vm.undoHideCategory()
        runCurrent()
        assertEquals(null, vm.state.value.hiddenNotice)
        assertEquals(emptySet(), hidden.value)
        assertEquals(listOf("__favorites", "__all", "sports", "news"), vm.state.value.categories.map { it.id })
    }

    @Test
    fun hidingTheLastCategoryMovesFocusToThePreviousOne() = runTest(dispatcher) {
        val hidden = MutableStateFlow<Set<String>>(emptySet())
        val vm = LiveViewModel(FakeSources(), FakeCatalog(), FakeEpg(), SettingsOnly(MutableStateFlow(null), hidden), Clock { 0L })
        runCurrent()
        vm.hideCategory("news")
        runCurrent()
        assertEquals(listOf("__favorites", "__all", "sports"), vm.state.value.categories.map { it.id })
        assertEquals("sports", vm.state.value.selectedCategoryId)
        assertEquals("news", vm.state.value.hiddenNotice?.categoryId)
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
        override fun categories(sourceId: SourceId, kind: ContentKind, includeHidden: Boolean): Flow<List<Category>> = flowOf(
            listOf(
                Category(src, ContentKind.LIVE, RemoteId("sports"), "Sports", null, 0),
                Category(src, ContentKind.LIVE, RemoteId("news"), "News", null, 1),
            ),
        )
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

    private class SettingsOnly(
        private val saved: MutableStateFlow<String?>,
        private val hiddenKeys: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet()),
    ) : UserDataRepository {
        override fun favorites(kind: ContentKind?): Flow<List<ContentKey>> = flowOf(emptyList())
        override fun isFavorite(key: ContentKey): Flow<Boolean> = flowOf(false)
        override suspend fun setFavorite(key: ContentKey, favorite: Boolean) = Unit
        override suspend fun saveProgress(key: ContentKey, parentId: RemoteId?, positionMs: Long, durationMs: Long?) = Unit
        override fun continueWatching(limit: Int): Flow<List<Progress>> = flowOf(emptyList())
        override suspend fun progress(key: ContentKey): Progress? = null
        override suspend fun recordChannelWatched(key: ContentKey) = Unit
        override fun recentChannels(limit: Int): Flow<List<ContentKey>> = flowOf(emptyList())
        override suspend fun setHidden(key: ContentKey, hidden: Boolean) = Unit
        override fun setting(key: String): Flow<String?> = if (key == LiveViewModel.ORDER_KEY) saved else flowOf(null)
        override suspend fun putSetting(key: String, value: String) { if (key == LiveViewModel.ORDER_KEY) saved.value = value }
        override fun hiddenCategoryKeys(): Flow<Set<String>> = hiddenKeys
        override suspend fun setCategoryHidden(sourceId: SourceId, kind: ContentKind, categoryId: String, hidden: Boolean) {
            val key = "${kind.name}|${sourceId.value}|$categoryId"
            hiddenKeys.update { if (hidden) it + key else it - key }
        }
    }
}
