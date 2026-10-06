package com.yodesla.omniverse.feature.vod

import com.yodesla.omniverse.core.data.Progress
import com.yodesla.omniverse.core.data.ShowEverything
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
import com.yodesla.omniverse.core.source.SourceException
import com.yodesla.omniverse.core.source.SyncDiagnostics
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

private val wSrc = SourceId("s")
private val wSeriesKey = ContentKey(wSrc, ContentKind.SERIES, RemoteId("s1"))
private val wEp1 = Episode(wSrc, RemoteId("e1"), RemoteId("s1"), 1, 1, "Pilot", null, 60, null, "mp4", null)
private val wEp2 = Episode(wSrc, RemoteId("e2"), RemoteId("s1"), 1, 2, "Second", null, 60, null, "mp4", null)
private val wEp1Key = ContentKey(wSrc, ContentKind.EPISODE, RemoteId("e1"))
class WatchedProgressCompletionTest {
    @Test fun completedNearTheEndOffersMarkUnwatched() {
        assertTrue(isWatchedProgress(Progress(wEp1Key, RemoteId("s1"), 93_000L, 100_000L, 1L, completed = true)))
        assertFalse(isWatchedProgress(Progress(wEp1Key, RemoteId("s1"), 50_000L, 100_000L, 1L, completed = false)))
    }
}

private val wDetail = SeriesDetail(
    SeriesRecord(wSrc, RemoteId("s1"), "Show S", null, emptyList(), emptyList(), null, null, null, null, null, 0, null),
    null, null, listOf(Season(1, "Season 1", null, listOf(wEp1, wEp2))),
)

/** DetailViewModel mark-watched / mark-unwatched: writes go to the repository and state refreshes immediately. */
@OptIn(ExperimentalCoroutinesApi::class)
class DetailWatchedTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun newVm(user: WatchedFakeUserData) =
        DetailViewModel(wSeriesKey, WatchedFakeSourceRepository(WatchedFakeContentSource()), user, null, ShowEverything)

    @Test
    fun loadMarksAnAlreadyCompletedEpisodeAsWatched() = runTest(dispatcher) {
        val user = WatchedFakeUserData()
        user.rows["e1"] = Progress(wEp1Key, wSeriesKey.remoteId, 60_000, 60_000, 5L)
        val vm = newVm(user)
        advanceUntilIdle()
        assertTrue(isWatchedProgress(vm.state.value.episodeProgress["e1"]))
        assertFalse(isWatchedProgress(vm.state.value.episodeProgress["e2"]))
    }

    @Test
    fun markWatchedWritesACompletedRowAndUpdatesState() = runTest(dispatcher) {
        val user = WatchedFakeUserData()
        val vm = newVm(user)
        advanceUntilIdle()
        vm.markWatched(wEp1)
        advanceUntilIdle()
        val call = user.watchedCalls.single()
        assertEquals(wEp1Key, call.first)
        assertTrue(call.second)
        assertEquals(60_000L, call.third)
        val p = vm.state.value.episodeProgress["e1"]
        assertNotNull(p)
        assertEquals(60_000L, p.positionMs)
        assertEquals(60_000L, p.durationMs)
        assertTrue(isWatchedProgress(p))
    }

    @Test
    fun markUnwatchedDeletesTheRowFromRepositoryAndState() = runTest(dispatcher) {
        val user = WatchedFakeUserData()
        val vm = newVm(user)
        advanceUntilIdle()
        vm.markWatched(wEp1)
        advanceUntilIdle()
        assertNotNull(vm.state.value.episodeProgress["e1"])
        vm.markUnwatched(wEp1)
        advanceUntilIdle()
        assertNull(vm.state.value.episodeProgress["e1"])
        assertNull(user.progress(wEp1Key))
    }

    @Test
    fun markSeasonWatchedMarksEveryEpisodeOfTheSeason() = runTest(dispatcher) {
        val user = WatchedFakeUserData()
        val vm = newVm(user)
        advanceUntilIdle()
        vm.markSeason(1, watched = true)
        advanceUntilIdle()
        assertEquals(2, user.watchedCalls.count { it.second })
        assertTrue(isWatchedProgress(vm.state.value.episodeProgress["e1"]))
        assertTrue(isWatchedProgress(vm.state.value.episodeProgress["e2"]))
    }

    @Test
    fun markSeasonUnwatchedClearsEveryEpisodeOfTheSeason() = runTest(dispatcher) {
        val user = WatchedFakeUserData()
        val vm = newVm(user)
        advanceUntilIdle()
        vm.markSeason(1, watched = true)
        advanceUntilIdle()
        vm.markSeason(1, watched = false)
        advanceUntilIdle()
        assertEquals(2, user.watchedCalls.count { !it.second })
        assertNull(vm.state.value.episodeProgress["e1"])
        assertNull(vm.state.value.episodeProgress["e2"])
    }

    @Test
    fun markWatchedAndUnwatchedAlsoTellTheSource() = runTest(dispatcher) {
        val user = WatchedFakeUserData()
        val src = WatchedFakeContentSource()
        val vm = DetailViewModel(wSeriesKey, WatchedFakeSourceRepository(src), user, null, ShowEverything)
        advanceUntilIdle()
        vm.markWatched(wEp1)
        advanceUntilIdle()
        vm.markUnwatched(wEp1)
        advanceUntilIdle()
        assertEquals(listOf(RemoteId("e1") to true, RemoteId("e1") to false), src.watchedSync)
    }

    @Test
    fun markSeasonTellsTheSourceAboutEveryEpisode() = runTest(dispatcher) {
        val user = WatchedFakeUserData()
        val src = WatchedFakeContentSource()
        val vm = DetailViewModel(wSeriesKey, WatchedFakeSourceRepository(src), user, null, ShowEverything)
        advanceUntilIdle()
        vm.markSeason(1, watched = true)
        advanceUntilIdle()
        assertEquals(listOf(RemoteId("e1") to true, RemoteId("e2") to true), src.watchedSync)
    }

    @Test
    fun aSourceThatRefusesWatchSyncStillMarksLocally() = runTest(dispatcher) {
        val user = WatchedFakeUserData()
        val vm = DetailViewModel(wSeriesKey, WatchedFakeSourceRepository(WatchedFakeContentSource(failSync = true)), user, null, ShowEverything)
        advanceUntilIdle()
        vm.markWatched(wEp1)
        advanceUntilIdle()
        assertEquals(1, user.watchedCalls.size)
        assertTrue(isWatchedProgress(vm.state.value.episodeProgress["e1"]))
    }

    // ---- audit M10: the provider half of a watched change is never silent

    @Test
    fun aRefusedWatchSyncKeepsTheLocalMarkAndSaysWhichEpisodeDidNotLand() = runTest(dispatcher) {
        val user = WatchedFakeUserData()
        val src = WatchedFakeContentSource(failSync = true)
        val vm = DetailViewModel(wSeriesKey, WatchedFakeSourceRepository(src), user, null, ShowEverything)
        advanceUntilIdle()
        vm.markWatched(wEp1)
        advanceUntilIdle()
        assertTrue(isWatchedProgress(vm.state.value.episodeProgress["e1"]))
        assertTrue(src.watchedSync.isEmpty())
        val msg = assertNotNull(vm.state.value.watchSyncError)
        assertTrue(msg.contains("S1E1"), msg)
    }

    @Test
    fun aConfirmedWatchSyncLeavesNoMessage() = runTest(dispatcher) {
        val user = WatchedFakeUserData()
        val src = WatchedFakeContentSource()
        val vm = DetailViewModel(wSeriesKey, WatchedFakeSourceRepository(src), user, null, ShowEverything)
        advanceUntilIdle()
        vm.markWatched(wEp1)
        advanceUntilIdle()
        vm.markUnwatched(wEp1)
        advanceUntilIdle()
        assertNull(vm.state.value.watchSyncError)
        assertEquals(listOf(RemoteId("e1") to true, RemoteId("e1") to false), src.watchedSync)
    }

    @Test
    fun retryWatchSyncLandsTheChangeAndClearsTheMessage() = runTest(dispatcher) {
        val user = WatchedFakeUserData()
        val src = WatchedFakeContentSource(failSync = true)
        val vm = DetailViewModel(wSeriesKey, WatchedFakeSourceRepository(src), user, null, ShowEverything)
        advanceUntilIdle()
        vm.markWatched(wEp1)
        advanceUntilIdle()
        assertNotNull(vm.state.value.watchSyncError)

        src.failSync = false
        vm.retryWatchSync()
        advanceUntilIdle()
        assertEquals(listOf(RemoteId("e1") to true), src.watchedSync)
        assertNull(vm.state.value.watchSyncError)
        assertTrue(isWatchedProgress(vm.state.value.episodeProgress["e1"]))
    }

    @Test
    fun aWholeSeasonThatFailedRetriesEveryEpisodeOfIt() = runTest(dispatcher) {
        val user = WatchedFakeUserData()
        val src = WatchedFakeContentSource(failSync = true)
        val vm = DetailViewModel(wSeriesKey, WatchedFakeSourceRepository(src), user, null, ShowEverything)
        advanceUntilIdle()
        vm.markSeason(1, watched = true)
        advanceUntilIdle()
        assertTrue(vm.state.value.episodeProgress.values.all { isWatchedProgress(it) })
        assertTrue(assertNotNull(vm.state.value.watchSyncError).contains("2"))

        src.failSync = false
        vm.retryWatchSync()
        advanceUntilIdle()
        assertEquals(listOf(RemoteId("e1") to true, RemoteId("e2") to true), src.watchedSync)
        assertNull(vm.state.value.watchSyncError)
    }

    @Test
    fun aRefusedUnwatchIsReportedAndTheLocalDeletionStands() = runTest(dispatcher) {
        val user = WatchedFakeUserData()
        val src = WatchedFakeContentSource()
        val vm = DetailViewModel(wSeriesKey, WatchedFakeSourceRepository(src), user, null, ShowEverything)
        advanceUntilIdle()
        vm.markWatched(wEp1)
        advanceUntilIdle()
        src.failSync = true
        vm.markUnwatched(wEp1)
        advanceUntilIdle()
        assertNull(vm.state.value.episodeProgress["e1"])
        assertNull(user.progress(wEp1Key))
        assertTrue(assertNotNull(vm.state.value.watchSyncError).contains("S1E1"))
    }
}

private class WatchedFakeUserData : UserDataRepository {
    val rows = mutableMapOf<String, Progress>()
    val watchedCalls = mutableListOf<Triple<ContentKey, Boolean, Long?>>()

    override fun favorites(kind: ContentKind?): Flow<List<ContentKey>> = flowOf(emptyList())
    override fun isFavorite(key: ContentKey): Flow<Boolean> = flowOf(false)
    override suspend fun setFavorite(key: ContentKey, favorite: Boolean) = Unit
    override suspend fun saveProgress(key: ContentKey, parentId: RemoteId?, positionMs: Long, durationMs: Long?) {
        rows[key.remoteId.value] = Progress(key, parentId, positionMs, durationMs, 1L)
    }
    override fun continueWatching(limit: Int): Flow<List<Progress>> =
        flowOf(rows.values.filter { it.durationMs == null || it.positionMs < it.durationMs!! }.sortedByDescending { it.updatedMs })
    override suspend fun progress(key: ContentKey): Progress? = rows[key.remoteId.value]
    override suspend fun setWatched(key: ContentKey, parentId: RemoteId?, watched: Boolean, durationMs: Long?) {
        watchedCalls += Triple(key, watched, durationMs)
        if (watched) {
            val dur = durationMs?.takeIf { it > 0 }
            rows[key.remoteId.value] = Progress(key, parentId, dur ?: 1L, dur, 2L)
        } else {
            rows.remove(key.remoteId.value)
        }
    }
    override suspend fun recordChannelWatched(key: ContentKey) = Unit
    override fun recentChannels(limit: Int): Flow<List<ContentKey>> = flowOf(emptyList())
    override suspend fun setHidden(key: ContentKey, hidden: Boolean) = Unit
    override fun setting(key: String): Flow<String?> = flowOf(null)
    override suspend fun putSetting(key: String, value: String) = Unit
}

private class WatchedFakeSourceRepository(private val source: ContentSource) : SourceRepository {
    override fun sources(): Flow<List<SourceSummary>> = flowOf(listOf(SourceSummary(wSrc, SourceKind.XTREAM, "Mock", null, null, null)))
    override suspend fun add(config: SourceConfig): SourceId = TODO()
    override suspend fun update(config: SourceConfig) = TODO()
    override suspend fun remove(id: SourceId) = TODO()
    override suspend fun config(id: SourceId): SourceConfig? = TODO()
    override suspend fun contentSource(id: SourceId): ContentSource? = source
    override suspend fun probe(config: SourceConfig): AccountInfo = TODO()
}

private class WatchedFakeContentSource(var failSync: Boolean = false) : ContentSource {
    val watchedSync = mutableListOf<Pair<RemoteId, Boolean>>()
    override val id: SourceId = wSrc
    override val kind: SourceKind = SourceKind.XTREAM
    override val capabilities: Set<Capability> = emptySet()
    override suspend fun accountInfo(): AccountInfo = TODO()
    override fun liveCategories(): Flow<Category> = emptyFlow()
    override fun liveChannels(diagnostics: SyncDiagnostics): Flow<ChannelRecord> = emptyFlow()
    override fun vodCategories(): Flow<Category> = emptyFlow()
    override fun vodItems(diagnostics: SyncDiagnostics): Flow<VodRecord> = emptyFlow()
    override fun seriesCategories(): Flow<Category> = emptyFlow()
    override fun series(diagnostics: SyncDiagnostics): Flow<SeriesRecord> = emptyFlow()
    override suspend fun vodDetail(id: RemoteId): VodDetail = TODO()
    override suspend fun seriesDetail(id: RemoteId): SeriesDetail = wDetail
    override fun epg(window: TimeWindow, channelKeys: Set<String>?, diagnostics: SyncDiagnostics): Flow<ProgrammeRecord> = emptyFlow()
    override suspend fun shortEpg(channelId: RemoteId, limit: Int): List<ProgrammeRecord> = emptyList()
    override suspend fun playback(request: PlaybackRequest): PlaybackSpec = TODO()
    override suspend fun setWatched(id: RemoteId, watched: Boolean) {
        if (failSync) throw SourceException.Http(503, "provider unavailable")
        watchedSync += id to watched
    }
}
