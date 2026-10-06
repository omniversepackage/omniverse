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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/** Task 85: jumps go through the VM's clamping and never rebuild the grid (which would reset "now"). */
@OptIn(ExperimentalCoroutinesApi::class)
class GuideJumpTest {
    private val dispatcher = StandardTestDispatcher()
    private val src = SourceId("s")
    private val cat = RemoteId("c")
    private val day0 = dayStartMs(1_700_000_000_000L)
    private val day1 = nextDayStartMs(day0)
    private val day2 = nextDayStartMs(day1)
    private val day3 = nextDayStartMs(day2)
    private val now = day0 + 12 * HOUR_MS
    /** Guide data ends midday three days on: the strip shows four days, forward jumps stop there. */
    private val guideEnd = day3 + 12 * HOUR_MS
    private val channel = ChannelRow(ContentKey(src, ContentKind.LIVE, RemoteId("1")), 1, "Channel", null, "epg1", 2, cat)

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun start(row: ChannelRow = channel): GuideViewModel =
        GuideViewModel(FakeSources(), FakeCatalog(row), FakeEpg(guideEnd), Clock { now }, ShowEverything)

    @Test
    fun jumpsMoveTheGridWithoutRebuildingIt() = runTest(dispatcher) {
        val vm = start()
        advanceUntilIdle()
        val grid = assertNotNull(vm.state.value.grid)
        assertEquals(listOf(day0, day1, day2, day3), vm.state.value.dayStarts)
        assertEquals(true, vm.state.value.atNow)
        assertNull(vm.state.value.jump)

        assertEquals(now + GUIDE_JUMP_MS, vm.jumpBy(GUIDE_JUMP_MS, now))
        val state = vm.state.value
        // Same grid instance: the row selection and the per-block caches survive the jump.
        assertSame(grid, state.grid)
        assertEquals(now + GUIDE_JUMP_MS, state.jump?.timeMs)
        assertEquals(now + GUIDE_JUMP_MS, state.viewMs)
        assertEquals(false, state.atNow)
    }

    @Test
    fun jumpsStopAtTheCatchupFloorAndAtTheLastProgramme() = runTest(dispatcher) {
        val vm = start()
        advanceUntilIdle()
        val floor = now - 2 * DAY_MS // the channel's catch-up window
        assertEquals(floor, vm.jumpBy(-GUIDE_JUMP_MS, floor + HOUR_MS))
        assertNull(vm.jumpBy(-GUIDE_JUMP_MS, floor))
        assertEquals(guideEnd, vm.jumpBy(GUIDE_JUMP_MS, guideEnd - HOUR_MS))
        assertNull(vm.jumpBy(GUIDE_JUMP_MS, guideEnd))
    }

    @Test
    fun withoutCatchupDataTheGuideNeverGoesBeforeNow() = runTest(dispatcher) {
        val vm = start(channel.copy(catchupDays = 0))
        advanceUntilIdle()
        assertNull(vm.jumpBy(-GUIDE_JUMP_MS, now))
        assertEquals(now, vm.state.value.viewMs)
    }

    @Test
    fun dayStripJumpsToPrimeTimeOnTheSameRow() = runTest(dispatcher) {
        val vm = start()
        advanceUntilIdle()
        val grid = assertNotNull(vm.state.value.grid)
        val target = assertNotNull(vm.jumpToDay(2))
        assertEquals(day2 + 18 * HOUR_MS, target)
        assertSame(grid, vm.state.value.grid)
        vm.onViewTime(target) // the grid reports where it moved
        assertEquals(2, dayIndexOf(vm.state.value.viewMs, vm.state.value.dayStarts))
        assertNull(vm.jumpToDay(9)) // no data that far out
    }

    @Test
    fun nowReturnsToTheLiveEdge() = runTest(dispatcher) {
        val vm = start()
        advanceUntilIdle()
        assertNull(vm.jumpToNow()) // already there
        vm.jumpBy(GUIDE_JUMP_MS, now)
        assertEquals(now, vm.jumpToNow())
        assertEquals(true, vm.state.value.atNow)
    }

    private class FakeEpg(private val endMs: Long) : EpgRepository {
        override suspend fun nowNext(sourceId: SourceId, epgKeys: Collection<String>, atMs: Long): Map<String, NowNext> = emptyMap()
        override suspend fun programmes(sourceId: SourceId, epgKey: String, window: TimeWindow): List<ProgrammeRecord> = emptyList()
        override suspend fun maxProgrammeEnd(sourceId: SourceId): Long = endMs
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

    private inner class FakeCatalog(private val row: ChannelRow) : CatalogRepository {
        override fun categories(sourceId: SourceId, kind: ContentKind, includeHidden: Boolean): Flow<List<Category>> =
            flowOf(listOf(Category(src, ContentKind.LIVE, cat, "Category", null, 0)))
        override fun channels(sourceId: SourceId, categoryId: RemoteId): PagingSource<Int, ChannelRow> = error("unused")
        override fun channelsAll(sourceId: SourceId, excludedCategories: Collection<String>): PagingSource<Int, ChannelRow> = error("unused")
        override suspend fun channelListAll(sourceId: SourceId, excludedCategories: Collection<String>): List<ChannelRow> = listOf(row)
        override suspend fun channelOrderAll(sourceId: SourceId, excludedCategories: Collection<String>): List<ContentKey> = listOf(row.key)
        override suspend fun channelList(sourceId: SourceId, categoryId: RemoteId): List<ChannelRow> = listOf(row)
        override suspend fun channelNeighbours(key: ContentKey, categoryId: RemoteId): Pair<ChannelRow?, ChannelRow?> = null to null
        override suspend fun channelOrder(sourceId: SourceId, categoryId: RemoteId): List<ContentKey> = listOf(row.key)
        override suspend fun channel(key: ContentKey): ChannelRow? = row.takeIf { it.key == key }
        override fun vod(sourceId: SourceId, categoryId: RemoteId?): PagingSource<Int, PosterRow> = error("unused")
        override fun series(sourceId: SourceId, categoryId: RemoteId?): PagingSource<Int, PosterRow> = error("unused")
        override fun vodAll(excludedCategoryKeys: Collection<String>): PagingSource<Int, PosterRow> = error("unused")
        override fun seriesAll(excludedCategoryKeys: Collection<String>): PagingSource<Int, PosterRow> = error("unused")
        override fun recentlyAdded(kind: ContentKind, limit: Int): Flow<List<PosterRow>> = error("unused")
        override suspend fun poster(key: ContentKey): PosterRow? = error("unused")
        override fun counts(sourceId: SourceId): Flow<Map<ContentKind, Long>> = error("unused")
    }
}
