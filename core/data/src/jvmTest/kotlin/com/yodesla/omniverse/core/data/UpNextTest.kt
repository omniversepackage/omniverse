package com.yodesla.omniverse.core.data

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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

/** UpNextResolver.nextAfter: the episode after a finished one (task 57 "Up next"). */
private val src = SourceId("plex")
private val seriesKey = ContentKey(src, ContentKind.SERIES, RemoteId("s1"))

class UpNextTest {

    private fun ep(season: Int, number: Int) =
        Episode(src, RemoteId("e$season-$number"), RemoteId("s1"), season, number, "E$number", null, 60, null, "mp4", null)

    private fun detail(vararg seasons: Pair<Int, List<Episode>>) = SeriesDetail(
        SeriesRecord(src, RemoteId("s1"), "Show", null, emptyList(), emptyList(), null, null, null, null, null, 0, null),
        null, null, seasons.map { (n, eps) -> Season(n, "Season $n", null, eps) },
    )

    private class FakeSource(
        var detail: SeriesDetail,
        var fail: Boolean = false,
        var failWith: Throwable? = null,
        private val onCall: (RemoteId) -> Unit = {},
    ) : ContentSource {
        var calls = 0
        override val id = src
        override val kind = SourceKind.XTREAM
        override val capabilities: Set<Capability> = emptySet()
        override suspend fun accountInfo(): AccountInfo = TODO()
        override fun liveCategories(): Flow<Category> = emptyFlow()
        override fun liveChannels(diagnostics: SyncDiagnostics): Flow<ChannelRecord> = emptyFlow()
        override fun vodCategories(): Flow<Category> = emptyFlow()
        override fun vodItems(diagnostics: SyncDiagnostics): Flow<VodRecord> = emptyFlow()
        override fun seriesCategories(): Flow<Category> = emptyFlow()
        override fun series(diagnostics: SyncDiagnostics): Flow<SeriesRecord> = emptyFlow()
        override suspend fun vodDetail(id: RemoteId): VodDetail = TODO()
        override suspend fun seriesDetail(id: RemoteId): SeriesDetail {
            calls++
            onCall(id)
            failWith?.let { throw it }
            if (fail) error("provider down")
            return detail
        }
        override fun epg(window: TimeWindow, channelKeys: Set<String>?, diagnostics: SyncDiagnostics): Flow<ProgrammeRecord> = emptyFlow()
        override suspend fun shortEpg(channelId: RemoteId, limit: Int): List<ProgrammeRecord> = emptyList()
        override suspend fun playback(request: PlaybackRequest): PlaybackSpec = TODO()
    }

    private class FakeSources(private val source: ContentSource?) : SourceRepository {
        override fun sources(): Flow<List<SourceSummary>> = flowOf(emptyList())
        override suspend fun add(config: SourceConfig): SourceId = TODO()
        override suspend fun update(config: SourceConfig) = TODO()
        override suspend fun remove(id: SourceId) = TODO()
        override suspend fun config(id: SourceId): SourceConfig? = null
        override suspend fun contentSource(id: SourceId): ContentSource? = source
        override suspend fun probe(config: SourceConfig): AccountInfo = TODO()
    }

    private fun resolver(detail: SeriesDetail, fail: Boolean = false) = UpNextResolver(FakeSources(FakeSource(detail, fail)))

    @Test
    fun midSeasonFinishedEpisodeRollsToTheNextNumber() = runTest {
        val r = resolver(detail(1 to listOf(ep(1, 1), ep(1, 2), ep(1, 3))))
        assertEquals(ep(1, 2), r.nextAfter(seriesKey, RemoteId("e1-1")))
        assertEquals(ep(1, 3), r.nextAfter(seriesKey, RemoteId("e1-2")))
    }

    @Test
    fun seasonBoundaryRollsToEpisodeOneOfTheNextSeason() = runTest {
        val r = resolver(detail(1 to listOf(ep(1, 1), ep(1, 2)), 2 to listOf(ep(2, 1), ep(2, 2))))
        val next = r.nextAfter(seriesKey, RemoteId("e1-2"))
        assertEquals(2, next?.season)
        assertEquals(1, next?.number)
    }

    @Test
    fun providerOrderDoesNotMatter() = runTest {
        // Seasons and episodes arrive out of order; the resolver orders them.
        val r = resolver(detail(2 to listOf(ep(2, 2), ep(2, 1)), 1 to listOf(ep(1, 2), ep(1, 1))))
        val next = r.nextAfter(seriesKey, RemoteId("e1-1"))
        assertEquals(1, next?.season)
        assertEquals(2, next?.number)
        val boundary = r.nextAfter(seriesKey, RemoteId("e1-2"))
        assertEquals(2, boundary?.season)
        assertEquals(1, boundary?.number)
    }

    @Test
    fun lastEpisodeOfTheShowHasNoUpNext() = runTest {
        val r = resolver(detail(1 to listOf(ep(1, 1)), 2 to listOf(ep(2, 1))))
        assertNull(r.nextAfter(seriesKey, RemoteId("e2-1")))
    }

    @Test
    fun unknownEpisodeIdResolvesToNothing() = runTest {
        val r = resolver(detail(1 to listOf(ep(1, 1))))
        assertNull(r.nextAfter(seriesKey, RemoteId("gone")))
    }

    @Test
    fun failedLookupResolvesToNothingAndIsRetriedOnTheNextCall() = runTest {
        val source = FakeSource(detail(1 to listOf(ep(1, 1), ep(1, 2))), fail = true)
        val r = UpNextResolver(FakeSources(source))
        assertNull(r.nextAfter(seriesKey, RemoteId("e1-1")))
        assertNull(r.nextAfter(seriesKey, RemoteId("e1-1")))
        assertEquals(2, source.calls, "a failure is not cached: the next refresh retries it")
        source.fail = false
        assertEquals(ep(1, 2), r.nextAfter(seriesKey, RemoteId("e1-1")), "reconnect recovers within the same resolver")
    }

    @Test
    fun successIsCachedUntilTtlExpiresThenRefetchedSoNewEpisodesSurface() = runTest {
        var nowMs = 1_000L
        val source = FakeSource(detail(1 to listOf(ep(1, 1), ep(1, 2))))
        val r = UpNextResolver(FakeSources(source), Clock { nowMs })
        assertEquals(ep(1, 2), r.nextAfter(seriesKey, RemoteId("e1-1")))
        assertEquals(ep(1, 2), r.nextAfter(seriesKey, RemoteId("e1-1")))
        assertEquals(1, source.calls, "a fresh success is served from the cache, not refetched per refresh")
        source.detail = detail(1 to listOf(ep(1, 1), ep(1, 2), ep(1, 3))) // upstream adds an episode
        assertEquals(ep(1, 2), r.nextAfter(seriesKey, RemoteId("e1-1")), "inside the TTL the cache still answers")
        nowMs += UpNextResolver.SUCCESS_TTL_MS
        assertEquals(ep(1, 3), r.nextAfter(seriesKey, RemoteId("e1-2")), "after the TTL the show is refetched")
        assertEquals(2, source.calls)
    }

    @Test
    fun cancellationIsRethrownNotSwallowed() = runTest {
        val source = FakeSource(detail(1 to listOf(ep(1, 1), ep(1, 2))), failWith = CancellationException("scope closed"))
        val r = UpNextResolver(FakeSources(source))
        val outcome = runCatching { r.nextAfter(seriesKey, RemoteId("e1-1")) }
        assertTrue(outcome.exceptionOrNull() is CancellationException, "cancellation must propagate, not become a null")
    }

    @Test
    fun cacheIsBoundedToTheMostRecentShows() = runTest {
        val calls = mutableMapOf<String, Int>()
        val source = FakeSource(detail(1 to listOf(ep(1, 1), ep(1, 2)))) { id ->
            calls[id.value] = (calls[id.value] ?: 0) + 1
        }
        val r = UpNextResolver(FakeSources(source))
        val keys = (0 until UpNextResolver.MAX_ENTRIES + 6).map { ContentKey(src, ContentKind.SERIES, RemoteId("show$it")) }
        keys.forEach { key -> assertEquals(ep(1, 2), r.nextAfter(key, RemoteId("e1-1"))) }
        assertEquals(ep(1, 2), r.nextAfter(keys.first(), RemoteId("e1-1")))
        assertEquals(2, calls["show0"], "the oldest show was evicted to bound the cache, so it refetches")
        assertEquals(ep(1, 2), r.nextAfter(keys.last(), RemoteId("e1-1")))
        assertEquals(1, calls["show${keys.last().remoteId.value.removePrefix("show")}"], "the newest show is still cached")
    }

    @Test
    fun removedSourceResolvesToNothing() = runTest {
        val r = UpNextResolver(FakeSources(null))
        assertNull(r.nextAfter(seriesKey, RemoteId("e1-1")))
    }
}
