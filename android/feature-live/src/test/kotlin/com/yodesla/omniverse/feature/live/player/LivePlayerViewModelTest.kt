package com.yodesla.omniverse.feature.live.player

import androidx.paging.PagingSource
import com.yodesla.omniverse.core.data.CatalogRepository
import com.yodesla.omniverse.core.data.ChannelRow
import com.yodesla.omniverse.core.data.EpgRepository
import com.yodesla.omniverse.core.data.NowNext
import com.yodesla.omniverse.core.data.PosterRow
import com.yodesla.omniverse.core.data.Progress
import com.yodesla.omniverse.core.data.SourceRepository
import com.yodesla.omniverse.core.data.SourceSummary
import com.yodesla.omniverse.core.data.UserDataRepository
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
import com.yodesla.omniverse.core.source.SyncDiagnostics
import com.yodesla.omniverse.player.FRAME_RATE_MATCH_ALWAYS
import com.yodesla.omniverse.player.FRAME_RATE_MATCH_SETTING
import com.yodesla.omniverse.player.FailureKind
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class LivePlayerViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val src = SourceId("s")
    private val src2 = SourceId("s2")
    private val cat = RemoteId("c")
    private val channels = (1..10).map {
        ChannelRow(ContentKey(src, ContentKind.LIVE, RemoteId("$it")), it, "Ch $it", null, "e$it", 0, cat)
    }
    private val altChannels = listOf(
        ChannelRow(ContentKey(src2, ContentKind.LIVE, RemoteId("alt1")), 1, "Ch 1", null, "e1", 0, cat),
    )
    private val opened = mutableListOf<String>()
    private val openedAt = mutableListOf<String>()
    private val hidden = mutableSetOf<String>()
    private var favs: List<ContentKey> = emptyList()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun vm(
        visibility: Flow<Visibility> = flowOf { _, _, _ -> true },
        preloadEnabled: Flow<Boolean> = flowOf(false),
    ) = LivePlayerViewModel(
        FakeSources(), FakeCatalog(), FakeEpg(), FakeUserData(),
        clock = { 0L }, visibility = visibility, preloadEnabled = preloadEnabled,
    )

    @Test
    fun rapidSurfingOpensOnlyTheFinalChannel() = runTest(dispatcher) {
        val vm = vm()
        vm.start(channels[0].key, cat)
        advanceUntilIdle()
        assertEquals(listOf("1"), opened)

        repeat(5) { vm.zapNext(); advanceTimeBy(100) }
        // Banner follows every press immediately...
        assertEquals("Ch 6", vm.state.value.banner?.name)
        // ...but no stream opened yet while surfing.
        assertEquals(listOf("1"), opened)

        advanceTimeBy(LivePlayerViewModel.SURF_SETTLE_MS + 10)
        assertEquals(listOf("1", "6"), opened)
    }

    @Test
    fun lastChannelReturnsToPrevious() = runTest(dispatcher) {
        val vm = vm()
        vm.start(channels[0].key, cat)
        advanceUntilIdle()
        vm.zapNext()
        advanceUntilIdle()
        vm.lastChannel()
        advanceUntilIdle()
        assertEquals(listOf("1", "2", "1"), opened)
    }

    // ---- Audit 2026-09-27 regressions ----

    @Test
    fun instantPressesEachMoveOneChannel() = runTest(dispatcher) {
        val vm = vm()
        vm.start(channels[0].key, cat)
        advanceUntilIdle()
        repeat(5) { vm.zapNext() } // no time in between: used to collapse onto "Ch 2"
        advanceUntilIdle()
        assertEquals("Ch 6", vm.state.value.banner?.name)
        assertEquals(listOf("1", "6"), opened)
    }

    @Test
    fun hiddenChannelsAreSkipped() = runTest(dispatcher) {
        hidden += "2"
        val vm = vm()
        vm.start(channels[0].key, cat)
        advanceUntilIdle()
        vm.zapNext()
        advanceUntilIdle()
        assertEquals(listOf("1", "3"), opened)
    }

    @Test
    fun favoritesZapWithinFavorites() = runTest(dispatcher) {
        favs = listOf(channels[4].key, channels[8].key)
        val vm = vm()
        vm.start(channels[4].key, RemoteId(com.yodesla.omniverse.feature.live.LiveViewModel.FAVORITES))
        advanceUntilIdle()
        vm.zapNext()
        advanceUntilIdle()
        assertEquals(listOf("5", "9"), opened)
    }

    // ---- Task 93: alternate-source live fallback ----

    @Test
    fun failedChannelOffersSameNameFromAnotherSource() = runTest(dispatcher) {
        val vm = vm()
        vm.start(channels[0].key, cat)
        advanceUntilIdle()
        vm.onStreamFailed()
        advanceUntilIdle()
        assertEquals("alt1", vm.state.value.switchTo?.key?.remoteId?.value)
        assertEquals("Src2", vm.state.value.switchTo?.sourceName)
    }

    @Test
    fun switchingToTheOfferedSourceOpensThatChannel() = runTest(dispatcher) {
        val vm = vm()
        vm.start(channels[0].key, cat)
        advanceUntilIdle()
        vm.onStreamFailed()
        advanceUntilIdle()
        vm.switchChannel(vm.state.value.switchTo!!.key)
        advanceUntilIdle()
        assertEquals(listOf("1", "alt1"), opened)
        assertEquals(listOf("s/1", "s2/alt1"), openedAt)
        assertNull(vm.state.value.switchTo)
    }

    @Test
    fun zappingAwayClearsTheOfferedSourceSwitch() = runTest(dispatcher) {
        val vm = vm()
        vm.start(channels[0].key, cat)
        advanceUntilIdle()
        vm.onStreamFailed()
        advanceUntilIdle()
        vm.zapNext()
        advanceUntilIdle()
        assertNull(vm.state.value.switchTo)
    }

    @Test
    fun retryingTheFailedChannelClearsTheOfferedSourceSwitch() = runTest(dispatcher) {
        val vm = vm()
        vm.start(channels[0].key, cat)
        advanceUntilIdle()
        vm.onStreamFailed()
        advanceUntilIdle()
        vm.retry()
        advanceUntilIdle()
        assertNull(vm.state.value.switchTo)
    }

    @Test
    fun parentalLocksHideTheOfferedSourceSwitch() = runTest(dispatcher) {
        val denySrc2: Visibility = { _, sourceId, categoryId -> !(sourceId == "s2" && categoryId == "c") }
        val vm = vm(visibility = flowOf(denySrc2))
        vm.start(channels[0].key, cat)
        advanceUntilIdle()
        vm.onStreamFailed()
        advanceUntilIdle()
        assertNull(vm.state.value.switchTo)
    }

    @Test
    fun channelNamesMatchAfterPunctuationAndCaseAreIgnored() {
        val vm = vm()
        assertTrue(vm.sameChannelName("Ch 1 HD", "ch1hd"))
        assertTrue(vm.sameChannelName("Ch 1", "Ch.1!"))
    }

    // ---- Task 94: next-channel pre-buffering ----

    @Test
    fun preloadsNextChannelAfterDwell() = runTest(dispatcher) {
        val vm = vm(preloadEnabled = flowOf(true))
        vm.start(channels[0].key, cat)
        advanceTimeBy(LivePlayerViewModel.PRELOAD_AFTER_MS - 1)
        assertNull(vm.state.value.preload) // nothing before the dwell elapses
        advanceTimeBy(1)
        advanceUntilIdle()
        assertTrue(vm.state.value.preload!!.url.endsWith("/2.ts"))
    }

    @Test
    fun zapOntoThePreloadedChannelReusesThatStream() = runTest(dispatcher) {
        val vm = vm(preloadEnabled = flowOf(true))
        vm.start(channels[0].key, cat)
        advanceUntilIdle()
        advanceTimeBy(LivePlayerViewModel.PRELOAD_AFTER_MS)
        advanceUntilIdle()
        val primed = vm.state.value.preload!!
        vm.zapNext()
        advanceUntilIdle()
        assertSame(primed, vm.state.value.spec) // swapped onto the primed object, not re-resolved
        assertEquals(1, opened.count { it == "2" }) // the spare's connection is reused, not doubled
    }

    @Test
    fun preloadFollowsTheDirectionLastZapped() = runTest(dispatcher) {
        val vm = vm(preloadEnabled = flowOf(true))
        vm.start(channels[0].key, cat)
        advanceUntilIdle()
        vm.zapPrevious() // wraps to channel 10, direction now backwards
        advanceUntilIdle()
        assertTrue(vm.state.value.preload!!.url.endsWith("/9.ts"))
    }

    @Test
    fun providerRefusalDisablesPreloadingForTheSession() = runTest(dispatcher) {
        val vm = vm(preloadEnabled = flowOf(true))
        vm.start(channels[0].key, cat)
        advanceUntilIdle()
        advanceTimeBy(LivePlayerViewModel.PRELOAD_AFTER_MS)
        advanceUntilIdle()
        vm.onStreamFailed(FailureKind.DENIED) // 403/509: a second connection would be refused too
        advanceUntilIdle()
        assertNull(vm.state.value.preload)
        vm.zapNext()
        advanceUntilIdle()
        assertNull(vm.state.value.preload) // stays off until the channel is re-entered
    }

    @Test
    fun leavingThePlayerScreenClearsTheResolvedPreload() = runTest(dispatcher) {
        // Task 111 L2: the VM survives on the Live back stack; popping the Player route must drop the
        // resolved (token-bearing) preload spec rather than retaining it.
        val vm = vm(preloadEnabled = flowOf(true))
        vm.start(channels[0].key, cat)
        advanceUntilIdle()
        advanceTimeBy(LivePlayerViewModel.PRELOAD_AFTER_MS)
        advanceUntilIdle()
        assertNotNull(vm.state.value.preload)
        vm.onScreenExit()
        assertNull(vm.state.value.preload)
    }

    @Test
    fun nextPreloadIndexWrapsInBothDirections() {
        assertEquals(1, LivePlayerViewModel.nextPreloadIndex(10, 0, true))
        assertEquals(9, LivePlayerViewModel.nextPreloadIndex(10, 0, false))
        assertEquals(0, LivePlayerViewModel.nextPreloadIndex(10, 9, true))
        assertNull(LivePlayerViewModel.nextPreloadIndex(1, 0, true))
    }

    @Test
    fun onlyARefusalDisablesPreloading() {
        assertTrue(PreloadGuard.shouldDisable(FailureKind.DENIED))
        assertFalse(PreloadGuard.shouldDisable(FailureKind.OTHER))
    }

    @Test
    fun matchFrameRateFollowsTheProfileSetting() = runTest(dispatcher) {
        val model = LivePlayerViewModel(
            FakeSources(), FakeCatalog(), FakeEpg(),
            FakeUserData(mapOf(FRAME_RATE_MATCH_SETTING to FRAME_RATE_MATCH_ALWAYS)),
            clock = { 0L },
        )
        assertEquals(FRAME_RATE_MATCH_ALWAYS, model.matchFrameRate.first())
    }

    @Test
    fun matchFrameRateIsAbsentWhenTheProfileNeverChoseOne() = runTest(dispatcher) {
        assertEquals(null, vm().matchFrameRate.first())
    }

    // ---------------- fakes ----------------

    private inner class FakeSource(override val id: SourceId) : ContentSource {
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
            opened += id
            openedAt += "${this.id.value}/$id"
            return PlaybackSpec("http://x/$id.ts", mimeHint = MimeHint.MPEG_TS, isLive = true, seekable = false, redacted = "x")
        }
    }

    private inner class FakeSources : SourceRepository {
        private val source = FakeSource(src)
        private val source2 = FakeSource(src2)
        override fun sources(): Flow<List<SourceSummary>> = flowOf(
            listOf(
                SourceSummary(src, SourceKind.XTREAM, "Src1", null, null, null),
                SourceSummary(src2, SourceKind.XTREAM, "Src2", null, null, null),
            ),
        )
        override suspend fun add(config: SourceConfig) = src
        override suspend fun update(config: SourceConfig) = Unit
        override suspend fun remove(id: SourceId) = Unit
        override suspend fun config(id: SourceId): SourceConfig? = null
        override suspend fun contentSource(id: SourceId): ContentSource? = when (id) {
            src -> source
            src2 -> source2
            else -> null
        }
        override suspend fun probe(config: SourceConfig) = source.accountInfo()
    }

    private inner class FakeCatalog : CatalogRepository {
        override fun categories(sourceId: SourceId, kind: ContentKind, includeHidden: Boolean): Flow<List<Category>> = flowOf(emptyList())
        override fun channels(sourceId: SourceId, categoryId: RemoteId): PagingSource<Int, ChannelRow> = error("unused")
        override fun channelsAll(sourceId: SourceId, excludedCategories: Collection<String>): androidx.paging.PagingSource<Int, com.yodesla.omniverse.core.data.ChannelRow> = TODO()
        override suspend fun channelListAll(sourceId: SourceId, excludedCategories: Collection<String>): List<com.yodesla.omniverse.core.data.ChannelRow> = emptyList()
        override suspend fun channelOrderAll(sourceId: SourceId, excludedCategories: Collection<String>): List<com.yodesla.omniverse.core.model.ContentKey> = emptyList()
        override suspend fun channelList(sourceId: SourceId, categoryId: RemoteId): List<ChannelRow> = channels
        override suspend fun channelNeighbours(key: ContentKey, categoryId: RemoteId): Pair<ChannelRow?, ChannelRow?> {
            val i = channels.indexOfFirst { it.key == key }
            return channels[(i - 1 + channels.size) % channels.size] to channels[(i + 1) % channels.size]
        }
        override suspend fun channel(key: ContentKey): ChannelRow? =
            channels.firstOrNull { it.key == key } ?: altChannels.firstOrNull { it.key == key }
        override suspend fun channelOrder(sourceId: SourceId, categoryId: RemoteId): List<ContentKey> =
            channels.filter { it.key.remoteId.value !in hidden }.map { it.key }
        override fun vod(sourceId: SourceId, categoryId: RemoteId?): PagingSource<Int, PosterRow> = error("unused")
        override fun series(sourceId: SourceId, categoryId: RemoteId?): PagingSource<Int, PosterRow> = error("unused")
        override fun vodAll(excludedCategoryKeys: Collection<String>): PagingSource<Int, PosterRow> = TODO()
        override fun seriesAll(excludedCategoryKeys: Collection<String>): PagingSource<Int, PosterRow> = TODO()
        override fun recentlyAdded(kind: ContentKind, limit: Int): Flow<List<PosterRow>> = flowOf(emptyList())
        override suspend fun poster(key: ContentKey): PosterRow? = null
        override fun counts(sourceId: SourceId): Flow<Map<ContentKind, Long>> = flowOf(emptyMap())
    }

    private inner class FakeEpg : EpgRepository {
        override suspend fun nowNext(sourceId: SourceId, epgKeys: Collection<String>, atMs: Long): Map<String, NowNext> = emptyMap()
        override suspend fun programmes(sourceId: SourceId, epgKey: String, window: TimeWindow): List<ProgrammeRecord> = emptyList()
        override suspend fun channelsNamed(term: String, limit: Int): List<ChannelRow> =
            altChannels.filter { it.name.contains(term, ignoreCase = true) }.take(limit)
    }

    private inner class FakeUserData(private val settings: Map<String, String> = emptyMap()) : UserDataRepository {
        override fun favorites(kind: ContentKind?): Flow<List<ContentKey>> = flowOf(favs)
        override fun isFavorite(key: ContentKey): Flow<Boolean> = flowOf(false)
        override suspend fun setFavorite(key: ContentKey, favorite: Boolean) = Unit
        override suspend fun saveProgress(key: ContentKey, parentId: RemoteId?, positionMs: Long, durationMs: Long?) = Unit
        override fun continueWatching(limit: Int): Flow<List<Progress>> = flowOf(emptyList())
        override suspend fun progress(key: ContentKey): Progress? = null
        override suspend fun recordChannelWatched(key: ContentKey) = Unit
        override fun recentChannels(limit: Int): Flow<List<ContentKey>> = flowOf(emptyList())
        override suspend fun setHidden(key: ContentKey, hidden: Boolean) = Unit
        override fun setting(key: String): Flow<String?> = flowOf(settings[key])
        override suspend fun putSetting(key: String, value: String) = Unit
    }
}
