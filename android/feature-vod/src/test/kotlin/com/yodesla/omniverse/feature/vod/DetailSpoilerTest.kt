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
import com.yodesla.omniverse.core.source.SyncDiagnostics
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

private val spSrc = SourceId("s")
private val spSeriesKey = ContentKey(spSrc, ContentKind.SERIES, RemoteId("s1"))
private val spEp1 = Episode(spSrc, RemoteId("e1"), RemoteId("s1"), 1, 1, "Pilot", null, 60, null, "mp4", null)
private val spEp2 = Episode(spSrc, RemoteId("e2"), RemoteId("s1"), 1, 2, "Second", null, 60, null, "mp4", null)
private val spEp1Key = ContentKey(spSrc, ContentKind.EPISODE, RemoteId("e1"))

private val spDetail = SeriesDetail(
    SeriesRecord(spSrc, RemoteId("s1"), "Show S", null, emptyList(), emptyList(), null, null, null, null, null, 0, null),
    null, null, listOf(Season(1, "Season 1", null, listOf(spEp1, spEp2))),
)

/** Task 95: DetailViewModel spoiler switches come from the profile settings; OK reveals for the visit. */
@OptIn(ExperimentalCoroutinesApi::class)
class DetailSpoilerTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun newVm(user: SpoilerFakeUserData) =
        DetailViewModel(spSeriesKey, SpoilerFakeSourceRepository(SpoilerFakeContentSource()), user, null, ShowEverything)

    @Test
    fun spoilerProtectionDefaultsOff() = runTest(dispatcher) {
        val vm = newVm(SpoilerFakeUserData())
        advanceUntilIdle()
        assertFalse(vm.state.value.spoilerFree)
        assertFalse(vm.state.value.spoilerHideTitles)
    }

    @Test
    fun profileSettingsTurnOnProtectionAndTheTitlesSubOption() = runTest(dispatcher) {
        val vm = newVm(SpoilerFakeUserData(settings = mapOf("spoiler_free" to "true", "spoiler_free_hide_titles" to "true")))
        advanceUntilIdle()
        assertTrue(vm.state.value.spoilerFree)
        assertTrue(vm.state.value.spoilerHideTitles)
    }

    @Test
    fun pressingOkOnAHiddenEpisodeRevealsItForTheVisit() = runTest(dispatcher) {
        val vm = newVm(SpoilerFakeUserData(settings = mapOf("spoiler_free" to "true")))
        advanceUntilIdle()
        assertTrue(vm.state.value.revealedSpoilerIds.isEmpty())
        vm.revealEpisode(spEp2)
        assertEquals(setOf("e2"), vm.state.value.revealedSpoilerIds)
        vm.revealEpisode(spEp2)
        assertEquals(setOf("e2"), vm.state.value.revealedSpoilerIds)
    }

    @Test
    fun watchedEpisodeProgressLoadsIntoStateForTheHiddenSet() = runTest(dispatcher) {
        val user = SpoilerFakeUserData(settings = mapOf("spoiler_free" to "true"))
        user.rows["e1"] = Progress(spEp1Key, spSeriesKey.remoteId, 60_000, 60_000, 5L)
        val vm = newVm(user)
        advanceUntilIdle()
        assertTrue(vm.state.value.spoilerFree)
        assertTrue(isWatchedProgress(vm.state.value.episodeProgress["e1"]))
    }
}

private class SpoilerFakeUserData(settings: Map<String, String> = emptyMap()) : UserDataRepository {
    val rows = mutableMapOf<String, Progress>()
    private val settingsFlow = MutableStateFlow(settings)

    override fun favorites(kind: ContentKind?): Flow<List<ContentKey>> = flowOf(emptyList())
    override fun isFavorite(key: ContentKey): Flow<Boolean> = flowOf(false)
    override suspend fun setFavorite(key: ContentKey, favorite: Boolean) = Unit
    override suspend fun saveProgress(key: ContentKey, parentId: RemoteId?, positionMs: Long, durationMs: Long?) {
        rows[key.remoteId.value] = Progress(key, parentId, positionMs, durationMs, 1L)
    }
    override fun continueWatching(limit: Int): Flow<List<Progress>> =
        flowOf(rows.values.filter { it.durationMs == null || it.positionMs < it.durationMs!! }.sortedByDescending { it.updatedMs })
    override suspend fun progress(key: ContentKey): Progress? = rows[key.remoteId.value]
    override suspend fun setWatched(key: ContentKey, parentId: RemoteId?, watched: Boolean, durationMs: Long?) = Unit
    override suspend fun recordChannelWatched(key: ContentKey) = Unit
    override fun recentChannels(limit: Int): Flow<List<ContentKey>> = flowOf(emptyList())
    override suspend fun setHidden(key: ContentKey, hidden: Boolean) = Unit
    override fun setting(key: String): Flow<String?> = settingsFlow.map { it[key] }
    override suspend fun putSetting(key: String, value: String) { settingsFlow.update { it + (key to value) } }
}

private class SpoilerFakeSourceRepository(private val source: ContentSource) : SourceRepository {
    override fun sources(): Flow<List<SourceSummary>> = flowOf(listOf(SourceSummary(spSrc, SourceKind.XTREAM, "Mock", null, null, null)))
    override suspend fun add(config: SourceConfig): SourceId = TODO()
    override suspend fun update(config: SourceConfig) = TODO()
    override suspend fun remove(id: SourceId) = TODO()
    override suspend fun config(id: SourceId): SourceConfig? = TODO()
    override suspend fun contentSource(id: SourceId): ContentSource? = source
    override suspend fun probe(config: SourceConfig): AccountInfo = TODO()
}

private class SpoilerFakeContentSource : ContentSource {
    override val id: SourceId = spSrc
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
    override suspend fun seriesDetail(id: RemoteId): SeriesDetail = spDetail
    override fun epg(window: TimeWindow, channelKeys: Set<String>?, diagnostics: SyncDiagnostics): Flow<ProgrammeRecord> = emptyFlow()
    override suspend fun shortEpg(channelId: RemoteId, limit: Int): List<ProgrammeRecord> = emptyList()
    override suspend fun playback(request: PlaybackRequest): PlaybackSpec = TODO()
    override suspend fun setWatched(id: RemoteId, watched: Boolean) = Unit
}
