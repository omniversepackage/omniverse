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
import com.yodesla.omniverse.core.model.mediaProgressKey
import com.yodesla.omniverse.core.source.ContentSource
import com.yodesla.omniverse.core.source.SourceConfig
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
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

private val lvSrc = SourceId("s")
private val lvKey = ContentKey(lvSrc, ContentKind.VOD, RemoteId("m1"))
private val lvTwo = listOf(MediaVersion("v1", 0, "1080p"), MediaVersion("v2", 1, "4K"))
private val lvOne = listOf(MediaVersion("v1", 0, "1080p"))

private fun lvDetail(versions: List<MediaVersion>) = VodDetail(
    VodRecord(lvSrc, RemoteId("m1"), "Film", null, emptyList(), null, 2024, null, "mp4", null, 0),
    null, null, null, null, 120, emptyList(), null, null, versions,
)

/**
 * Audit L6: copying a legacy title-level watch into the first media version must not turn a
 * mid-watched film of unknown length into a finished one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DetailLegacyVersionProgressTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun newVm(user: LegacyFakeUserData, versions: List<MediaVersion>) =
        DetailViewModel(lvKey, LegacyFakeSourceRepository(LegacyFakeContentSource(versions)), user, null, ShowEverything)

    @Test
    fun aMidWatchedFilmOfUnknownLengthKeepsItsSharedResumeRow() = runTest(dispatcher) {
        val user = LegacyFakeUserData()
        user.rows["m1"] = Progress(lvKey, null, 30_000L, null, 1L)
        val vm = newVm(user, lvTwo)
        advanceUntilIdle()

        val copied = assertNotNull(user.progress(mediaProgressKey(lvKey, "v1")))
        assertEquals(30_000L, copied.positionMs)
        assertFalse(isWatchedProgress(copied))

        val shared = assertNotNull(user.progress(lvKey))
        assertEquals(30_000L, shared.positionMs)
        assertEquals(null, shared.durationMs)
        assertFalse(isWatchedProgress(shared))
        assertEquals(30_000L, assertNotNull(vm.state.value.progress).positionMs)
    }

    @Test
    fun aMidWatchedFilmOfKnownLengthStillRetiresTheSharedRow() = runTest(dispatcher) {
        val user = LegacyFakeUserData()
        user.rows["m1"] = Progress(lvKey, null, 30_000L, 120_000L, 1L)
        val vm = newVm(user, lvTwo)
        advanceUntilIdle()

        val copied = assertNotNull(user.progress(mediaProgressKey(lvKey, "v1")))
        assertEquals(30_000L, copied.positionMs)
        assertEquals(120_000L, copied.durationMs)
        assertFalse(isWatchedProgress(copied))

        val shared = assertNotNull(user.progress(lvKey))
        assertTrue(isWatchedProgress(shared))
        assertEquals(30_000L, assertNotNull(vm.state.value.progress).positionMs)
    }

    @Test
    fun aSingleVersionFilmLeavesTheSharedRowExactlyAsItWas() = runTest(dispatcher) {
        val user = LegacyFakeUserData()
        user.rows["m1"] = Progress(lvKey, null, 30_000L, null, 1L)
        val vm = newVm(user, lvOne)
        advanceUntilIdle()

        assertEquals(1, user.rows.size)
        val shared = assertNotNull(user.progress(lvKey))
        assertEquals(30_000L, shared.positionMs)
        assertFalse(isWatchedProgress(shared))
        assertEquals(30_000L, assertNotNull(vm.state.value.progress).positionMs)
    }
}

private class LegacyFakeUserData : UserDataRepository {
    val rows = mutableMapOf<String, Progress>()

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
    override fun setting(key: String): Flow<String?> = flowOf(null)
    override suspend fun putSetting(key: String, value: String) = Unit
}

private class LegacyFakeSourceRepository(private val source: ContentSource) : SourceRepository {
    override fun sources(): Flow<List<SourceSummary>> = flowOf(listOf(SourceSummary(lvSrc, SourceKind.XTREAM, "Mock", null, null, null)))
    override suspend fun add(config: SourceConfig): SourceId = TODO()
    override suspend fun update(config: SourceConfig) = TODO()
    override suspend fun remove(id: SourceId) = TODO()
    override suspend fun config(id: SourceId): SourceConfig? = TODO()
    override suspend fun contentSource(id: SourceId): ContentSource? = source
    override suspend fun probe(config: SourceConfig): AccountInfo = TODO()
}

private class LegacyFakeContentSource(private val versions: List<MediaVersion>) : ContentSource {
    override val id: SourceId = lvSrc
    override val kind: SourceKind = SourceKind.XTREAM
    override val capabilities: Set<Capability> = emptySet()
    override suspend fun accountInfo(): AccountInfo = TODO()
    override fun liveCategories(): Flow<Category> = emptyFlow()
    override fun liveChannels(diagnostics: SyncDiagnostics): Flow<ChannelRecord> = emptyFlow()
    override fun vodCategories(): Flow<Category> = emptyFlow()
    override fun vodItems(diagnostics: SyncDiagnostics): Flow<VodRecord> = emptyFlow()
    override fun seriesCategories(): Flow<Category> = emptyFlow()
    override fun series(diagnostics: SyncDiagnostics): Flow<SeriesRecord> = emptyFlow()
    override suspend fun vodDetail(id: RemoteId): VodDetail = lvDetail(versions)
    override suspend fun seriesDetail(id: RemoteId): com.yodesla.omniverse.core.model.SeriesDetail = TODO()
    override fun epg(window: TimeWindow, channelKeys: Set<String>?, diagnostics: SyncDiagnostics): Flow<ProgrammeRecord> = emptyFlow()
    override suspend fun shortEpg(channelId: RemoteId, limit: Int): List<ProgrammeRecord> = emptyList()
    override suspend fun playback(request: PlaybackRequest): PlaybackSpec = TODO()
    override suspend fun setWatched(id: RemoteId, watched: Boolean) = Unit
}
