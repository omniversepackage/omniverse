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
import com.yodesla.omniverse.core.model.MediaVersion
import com.yodesla.omniverse.core.model.PlaybackRequest
import com.yodesla.omniverse.core.model.PlaybackSpec
import com.yodesla.omniverse.core.model.ProgrammeRecord
import com.yodesla.omniverse.core.model.RemoteId
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

private val epSrc = SourceId("s")
private val epKey = ContentKey(epSrc, ContentKind.VOD, RemoteId("m1"))

private fun epDetail(versions: List<MediaVersion>, tmdb: String?) = VodDetail(
    VodRecord(epSrc, RemoteId("m1"), "Film", null, emptyList(), null, 2024, null, "mp4", tmdb, 0),
    null, null, null, null, 120, emptyList(), null, null, versions,
)

/**
 * Task 86: the version chooser's memory. The choice belongs to the profile and to the title's
 * exact catalog id, so it survives a restart and never leaks into another profile's copy.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditionPickTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun newVm(user: EpFakeUserData, versions: List<MediaVersion> = emptyList(), tmdb: String? = "603") =
        DetailViewModel(epKey, EpFakeSourceRepository(EpFakeContentSource(epDetail(versions, tmdb))), user, null, ShowEverything)

    @Test fun theCopyThisProfileLastChoseLoadsWithTheTitle() = runTest(dispatcher) {
        val user = EpFakeUserData()
        user.settings["edition_pick_603"] = "k:iptv:m2"
        val vm = newVm(user)
        advanceUntilIdle()

        assertEquals("603", vm.state.value.tmdbId)
        assertEquals("k:iptv:m2", vm.state.value.lastEditionPick)
    }

    @Test fun choosingACopyRemembersItForThisProfileOnly() = runTest(dispatcher) {
        val user = EpFakeUserData()
        val vm = newVm(user)
        advanceUntilIdle()

        vm.rememberEditionPick("k:iptv:m2")
        advanceUntilIdle()

        assertEquals("k:iptv:m2", vm.state.value.lastEditionPick)
        assertEquals(mapOf("edition_pick_603" to "k:iptv:m2"), user.settings)
    }

    @Test fun choosingAFileInsideThisItemRemembersThatFile() = runTest(dispatcher) {
        val user = EpFakeUserData()
        val versions = listOf(MediaVersion("v1", 0, "1080p"), MediaVersion("v2", 1, "4K"))
        val vm = newVm(user, versions)
        advanceUntilIdle()

        vm.selectVersion("v2")
        advanceUntilIdle()

        assertEquals("v:v2", vm.state.value.lastEditionPick)
        assertEquals("v:v2", user.settings["edition_pick_603"])
    }

    @Test fun aTitleWithNoCatalogIdRemembersNothing() = runTest(dispatcher) {
        val user = EpFakeUserData()
        val vm = newVm(user, tmdb = null)
        advanceUntilIdle()

        vm.rememberEditionPick("k:iptv:m2")
        advanceUntilIdle()

        assertEquals(null, vm.state.value.tmdbId)
        assertTrue(user.settings.isEmpty())
    }

    @Test fun aWatchOnAnotherCopyIsLoadedSoPlayCanFollowIt() = runTest(dispatcher) {
        val user = EpFakeUserData()
        user.rows["m1"] = Progress(epKey, null, 30_000L, 120_000L, 1L)
        val vm = newVm(user)
        advanceUntilIdle()

        assertEquals(30_000L, vm.state.value.progress?.positionMs)
        assertTrue(vm.state.value.editionCopyProgress.isEmpty())
    }
}

private class EpFakeUserData : UserDataRepository {
    val rows = mutableMapOf<String, Progress>()
    val settings = mutableMapOf<String, String>()

    override fun favorites(kind: ContentKind?): Flow<List<ContentKey>> = flowOf(emptyList())
    override fun isFavorite(key: ContentKey): Flow<Boolean> = flowOf(false)
    override suspend fun setFavorite(key: ContentKey, favorite: Boolean) = Unit
    override suspend fun saveProgress(key: ContentKey, parentId: RemoteId?, positionMs: Long, durationMs: Long?) {
        val completed = durationMs != null && durationMs > 0 && positionMs >= durationMs * 0.92
        rows[key.remoteId.value] = Progress(key, parentId, positionMs, durationMs, 2L, completed)
    }
    override fun continueWatching(limit: Int): Flow<List<Progress>> =
        flowOf(rows.values.filter { !it.completed }.sortedByDescending { it.updatedMs })
    override suspend fun progress(key: ContentKey): Progress? = rows[key.remoteId.value]
    override suspend fun setWatched(key: ContentKey, parentId: RemoteId?, watched: Boolean, durationMs: Long?) = Unit
    override suspend fun recordChannelWatched(key: ContentKey) = Unit
    override fun recentChannels(limit: Int): Flow<List<ContentKey>> = flowOf(emptyList())
    override suspend fun setHidden(key: ContentKey, hidden: Boolean) = Unit
    override fun setting(key: String): Flow<String?> = flowOf(settings[key])
    override suspend fun putSetting(key: String, value: String) { settings[key] = value }
}

private class EpFakeSourceRepository(private val source: ContentSource) : SourceRepository {
    override fun sources(): Flow<List<SourceSummary>> = flowOf(listOf(SourceSummary(epSrc, SourceKind.XTREAM, "Mock", null, null, null)))
    override suspend fun add(config: SourceConfig): SourceId = TODO()
    override suspend fun update(config: SourceConfig) = TODO()
    override suspend fun remove(id: SourceId) = TODO()
    override suspend fun config(id: SourceId): SourceConfig? = TODO()
    override suspend fun contentSource(id: SourceId): ContentSource? = source
    override suspend fun probe(config: SourceConfig): AccountInfo = TODO()
}

private class EpFakeContentSource(private val detail: VodDetail) : ContentSource {
    override val id: SourceId = epSrc
    override val kind: SourceKind = SourceKind.XTREAM
    override val capabilities: Set<Capability> = emptySet()
    override suspend fun accountInfo(): AccountInfo = TODO()
    override fun liveCategories(): Flow<Category> = emptyFlow()
    override fun liveChannels(diagnostics: SyncDiagnostics): Flow<ChannelRecord> = emptyFlow()
    override fun vodCategories(): Flow<Category> = emptyFlow()
    override fun vodItems(diagnostics: SyncDiagnostics): Flow<VodRecord> = emptyFlow()
    override fun seriesCategories(): Flow<Category> = emptyFlow()
    override fun series(diagnostics: SyncDiagnostics): Flow<SeriesRecord> = emptyFlow()
    override suspend fun vodDetail(id: RemoteId): VodDetail = detail
    override suspend fun seriesDetail(id: RemoteId): com.yodesla.omniverse.core.model.SeriesDetail = TODO()
    override fun epg(window: TimeWindow, channelKeys: Set<String>?, diagnostics: SyncDiagnostics): Flow<ProgrammeRecord> = emptyFlow()
    override suspend fun shortEpg(channelId: RemoteId, limit: Int): List<ProgrammeRecord> = emptyList()
    override suspend fun playback(request: PlaybackRequest): PlaybackSpec = TODO()
    override suspend fun setWatched(id: RemoteId, watched: Boolean) = Unit
}
