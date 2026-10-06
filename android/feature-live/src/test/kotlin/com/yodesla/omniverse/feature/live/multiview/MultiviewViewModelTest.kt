package com.yodesla.omniverse.feature.live.multiview

import androidx.paging.PagingSource
import com.yodesla.omniverse.core.data.CatalogRepository
import com.yodesla.omniverse.core.data.ChannelRow
import com.yodesla.omniverse.core.data.EpgRepository
import com.yodesla.omniverse.core.data.NowNext
import com.yodesla.omniverse.core.data.PosterRow
import com.yodesla.omniverse.core.data.SourceRepository
import com.yodesla.omniverse.core.data.SourceSummary
import com.yodesla.omniverse.core.data.Visibility
import com.yodesla.omniverse.core.model.AccountInfo
import com.yodesla.omniverse.core.model.Capability
import com.yodesla.omniverse.core.model.Category
import com.yodesla.omniverse.core.model.ChannelRecord
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.MimeHint
import com.yodesla.omniverse.core.model.PlaybackRequest
import com.yodesla.omniverse.core.model.PlaybackSpec
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
import com.yodesla.omniverse.core.source.SourceException
import com.yodesla.omniverse.core.source.SyncDiagnostics
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Task 61 / audit M1+M2: every Multiview tile is judged by the SAME live policy the Player route is
 * gated by — before its stream is resolved, and again whenever the policy changes (PIN session
 * expiry, re-lock). Denied/missing tiles are removed; the route closes when none are left.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MultiviewViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val src = SourceId("s")
    private val general = RemoteId("general")
    private val adult = RemoteId("adult")

    private val opened = mutableListOf<String>()
    private val broken = mutableSetOf<String>()
    private val gone = mutableSetOf<String>()
    private val visibility = MutableStateFlow<Visibility>({ _, _, _ -> true })

    private fun key(id: String) = ContentKey(src, ContentKind.LIVE, RemoteId(id))
    private val a = key("1")
    private val b = key("2")
    private val c = key("3")
    private val d = key("4")
    private val e = key("5")
    private val f = key("6")
    private val rows = listOf(
        ChannelRow(a, 1, "Ch 1", null, "e1", 0, general),
        ChannelRow(b, 2, "Ch 2", null, "e2", 0, general),
        ChannelRow(c, 3, "Ch 3", null, "e3", 0, adult),
        ChannelRow(d, 4, "Ch 4", null, "e4", 0, general),
        ChannelRow(e, 5, "Ch 5", null, "e5", 0, general),
        ChannelRow(f, 6, "Ch 6", null, "e6", 0, adult),
    )

    @BeforeTest fun setUp() {
        Dispatchers.setMain(dispatcher)
        opened.clear(); broken.clear(); gone.clear()
        visibility.value = { _, _, _ -> true }
    }

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun vm() = MultiviewViewModel(FakeSources(), FakeCatalog(), FakeEpg(), clock = { 0L }, visibility = visibility)

    private val denyAdult: Visibility = { kind, _, cat -> !(kind == "LIVE" && cat == "adult") }

    // ---- M1: policy before playback ----

    @Test
    fun deniedChannelNeverOpensItsStream() = runTest(dispatcher) {
        visibility.value = denyAdult
        val vm = vm()
        vm.start(listOf(a, b, c, d))
        advanceUntilIdle()
        assertEquals(listOf("1", "2", "4"), opened)
        assertEquals(listOf(a, b, d), vm.state.value.tiles.map { it.key })
        assertFalse(vm.closed.value)
    }

    @Test
    fun removedChannelGetsNoTileAndNoStream() = runTest(dispatcher) {
        gone += "3"
        val vm = vm()
        vm.start(listOf(a, b, c, d))
        advanceUntilIdle()
        assertEquals(listOf("1", "2", "4"), opened)
        assertEquals(listOf(a, b, d), vm.state.value.tiles.map { it.key })
        assertFalse(vm.closed.value)
    }

    @Test
    fun allChannelsDeniedAtStartClosesTheRoute() = runTest(dispatcher) {
        visibility.value = denyAdult
        val vm = vm()
        vm.start(listOf(c))
        advanceUntilIdle()
        assertEquals(emptyList(), opened)
        assertEquals(emptyList(), vm.state.value.tiles)
        assertTrue(vm.closed.value)
    }

    // ---- M1: policy changes while the route is open ----

    @Test
    fun sessionExpiryRemovesDeniedTilesAndKeepsTheRest() = runTest(dispatcher) {
        val vm = vm()
        vm.start(listOf(a, b, c, d))
        advanceUntilIdle()
        assertEquals(listOf("1", "2", "3", "4"), opened)

        visibility.value = denyAdult // MainActivity locks again / PIN session expired
        advanceUntilIdle()
        assertEquals(listOf(a, b, d), vm.state.value.tiles.map { it.key })
        assertFalse(vm.closed.value)
    }

    @Test
    fun lastAllowedTileDeniedClosesTheRoute() = runTest(dispatcher) {
        val vm = vm()
        vm.start(listOf(a, c))
        advanceUntilIdle()

        visibility.value = denyAdult
        advanceUntilIdle()
        assertEquals(listOf(a), vm.state.value.tiles.map { it.key })
        assertFalse(vm.closed.value)

        visibility.value = { _, _, _ -> false }
        advanceUntilIdle()
        assertEquals(emptyList(), vm.state.value.tiles)
        assertTrue(vm.closed.value)
    }

    @Test
    fun closedResetsWhenTheRouteIsReopened() = runTest(dispatcher) {
        val vm = vm()
        vm.start(listOf(c))
        advanceUntilIdle()
        visibility.value = denyAdult
        advanceUntilIdle()
        assertTrue(vm.closed.value)

        vm.start(listOf(a, b))
        advanceUntilIdle()
        assertFalse(vm.closed.value)
        assertEquals(listOf(a, b), vm.state.value.tiles.map { it.key })
        assertEquals(listOf("3", "1", "2"), opened)
    }

    @Test
    fun kidsHardFilterPolicyDropsLockedTilesEvenDuringAnUnlockedSession() = runTest(dispatcher) {
        // Audit 73 M1+M2: Multiview is fed graph.vodVisibility — parental AND the Kids hard filter.
        // This is that policy's shape on a Kids profile: locked categories are denied with no
        // unlock, so locked tiles never open a stream and the tiles match the route gate exactly.
        visibility.value = { kind, _, cat -> !(kind == "LIVE" && cat == "adult") }
        val vm = vm()
        vm.start(listOf(a, c, f))
        advanceUntilIdle()
        assertEquals(listOf(a), vm.state.value.tiles.map { it.key })
        assertEquals(listOf("1"), opened)
        assertFalse(vm.closed.value)
    }

    // ---- M2: the foreground-resume gate ----

    @Test
    fun resumeReopensOnlyTilesTheCurrentPolicyAllows() = runTest(dispatcher) {
        val vm = vm()
        vm.start(listOf(a, b, c))
        advanceUntilIdle()
        assertEquals(listOf(a, b, c), vm.tilesToResume().map { it.first })
        assertTrue(vm.allows(c))

        visibility.value = denyAdult
        advanceUntilIdle()
        assertEquals(listOf(a, b), vm.tilesToResume().map { it.first })
        assertFalse(vm.allows(c))
    }

    // ---- a dead stream is a dark tile, not a closed route ----

    @Test
    fun streamFailureKeepsTheTileAndDoesNotCloseTheRoute() = runTest(dispatcher) {
        broken += "2"
        val vm = vm()
        vm.start(listOf(a, b))
        advanceUntilIdle()
        assertEquals(listOf(a, b), vm.state.value.tiles.map { it.key })
        assertEquals("This channel can't be played right now.", vm.state.value.tiles[1].error)
        assertFalse(vm.closed.value)
    }

    // ---- Task 67: swap one slot without disturbing the others ----

    @Test
    fun swapReplacesOnlyThatSlotAndKeepsTheOthers() = runTest(dispatcher) {
        val vm = vm()
        vm.start(listOf(a, b, c, d))
        advanceUntilIdle()
        val before = vm.state.value.tiles

        vm.swapChannel(1, e) // swap "Ch 2" for "Ch 5"
        advanceUntilIdle()

        val after = vm.state.value.tiles
        assertEquals(listOf(a, e, c, d), after.map { it.key })
        // The untouched tiles keep the exact tile objects (same spec, same resolved name) they had.
        assertEquals(before[0], after[0])
        assertEquals(before[2], after[2])
        assertEquals(before[3], after[3])
        assertNotNull(after[1].spec)
        assertTrue("5" in opened)
    }

    @Test
    fun swapToAGoneChannelDropsOnlyThatSlot() = runTest(dispatcher) {
        val vm = vm()
        vm.start(listOf(a, b, c, d))
        advanceUntilIdle()

        vm.swapChannel(1, key("9")) // no such channel in the catalog
        advanceUntilIdle()

        assertEquals(listOf(a, c, d), vm.state.value.tiles.map { it.key })
        assertFalse("9" in opened)
        assertFalse(vm.closed.value)
    }

    @Test
    fun swapToADeniedChannelIsJudgedByTheCurrentPolicy() = runTest(dispatcher) {
        val vm = vm()
        vm.start(listOf(a, b, c, d))
        advanceUntilIdle()
        visibility.value = denyAdult

        vm.swapChannel(1, f) // f is an adult channel: it must not open a stream
        advanceUntilIdle()

        // c was already removed when the policy changed; b's slot is dropped because f is denied.
        assertEquals(listOf(a, d), vm.state.value.tiles.map { it.key })
        assertFalse("6" in opened)
    }

    @Test
    fun swapCandidatesSkipChannelsAlreadyOnATileAndDeniedByPolicy() = runTest(dispatcher) {
        val vm = vm()
        vm.start(listOf(a, b, c, d))
        advanceUntilIdle()
        visibility.value = denyAdult
        advanceUntilIdle() // let the policy emission land (and drop the adult tile) before listing candidates
        // e (general, not on a tile) survives; f (adult) is denied; a-d are already on tiles.
        assertEquals(listOf(e), vm.swapCandidates().map { it.key })
    }

    // Task 84m: a category hidden (hold-OK › Hide category / Settings › Hidden categories) is gone
    // from Multiview too — its tiles drop and its channels leave the swap picker.
    @Test
    fun hiddenCategoryDropsItsTilesAndStaysOutOfTheSwapPicker() = runTest(dispatcher) {
        val hidden = MutableStateFlow<Set<String>>(emptySet())
        val vm = MultiviewViewModel(FakeSources(), FakeCatalog(), FakeEpg(), clock = { 0L }, visibility = visibility, hiddenCategoryKeys = hidden)
        vm.start(listOf(a, b, c))
        advanceUntilIdle()
        assertEquals(listOf(a, b, c), vm.state.value.tiles.map { it.key })

        hidden.value = setOf("LIVE|s|adult")
        advanceUntilIdle()
        assertEquals(listOf(a, b), vm.state.value.tiles.map { it.key })
        // d/e are general and not on a tile; c/f belong to the hidden category; a/b are taken.
        assertEquals(listOf(d, e), vm.swapCandidates().map { it.key })
    }

    @Test
    fun retryReResolvesOnlyThatTile() = runTest(dispatcher) {
        val vm = vm()
        vm.start(listOf(a, b))
        advanceUntilIdle()
        assertEquals(listOf("1", "2"), opened)

        vm.retry(0)
        advanceUntilIdle()

        assertEquals(listOf("1", "2", "1"), opened)
        assertEquals(1, vm.state.value.tiles[0].attempt)
        assertEquals(0, vm.state.value.tiles[1].attempt)
    }

    // ---------------- fakes ----------------

    private inner class FakeSource : ContentSource {
        override val id = src
        override val kind = SourceKind.XTREAM
        override val capabilities = setOf(Capability.LIVE)
        override suspend fun accountInfo(): AccountInfo = error("unused")
        override fun liveCategories(): Flow<Category> = emptyFlow()
        override fun liveChannels(diagnostics: SyncDiagnostics): Flow<ChannelRecord> = emptyFlow()
        override fun vodCategories(): Flow<Category> = emptyFlow()
        override fun vodItems(diagnostics: SyncDiagnostics): Flow<VodRecord> = emptyFlow()
        override fun seriesCategories(): Flow<Category> = emptyFlow()
        override fun series(diagnostics: SyncDiagnostics): Flow<SeriesRecord> = emptyFlow()
        override suspend fun vodDetail(id: RemoteId): VodDetail = error("unused")
        override suspend fun seriesDetail(id: RemoteId): SeriesDetail = error("unused")
        override fun epg(window: TimeWindow, channelKeys: Set<String>?, diagnostics: SyncDiagnostics): Flow<ProgrammeRecord> = emptyFlow()
        override suspend fun shortEpg(channelId: RemoteId, limit: Int): List<ProgrammeRecord> = emptyList()
        override suspend fun playback(request: PlaybackRequest): PlaybackSpec {
            val id = (request as PlaybackRequest.Live).channelId.value
            if (id in broken) throw SourceException.NotFound("gone")
            opened += id
            return PlaybackSpec("http://x/$id.ts", mimeHint = MimeHint.MPEG_TS, isLive = true, seekable = false, redacted = "x")
        }
    }

    private inner class FakeSources : SourceRepository {
        private val source = FakeSource()
        override fun sources(): Flow<List<SourceSummary>> = flowOf(emptyList())
        override suspend fun add(config: SourceConfig) = src
        override suspend fun update(config: SourceConfig) = Unit
        override suspend fun remove(id: SourceId) = Unit
        override suspend fun config(id: SourceId): SourceConfig? = null
        override suspend fun contentSource(id: SourceId): ContentSource = source
        override suspend fun probe(config: SourceConfig) = source.accountInfo()
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
}
