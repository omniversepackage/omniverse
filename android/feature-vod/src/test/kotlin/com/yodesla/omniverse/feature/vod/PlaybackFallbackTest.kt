package com.yodesla.omniverse.feature.vod

import androidx.paging.PagingSource
import com.yodesla.omniverse.core.data.CatalogRepository
import com.yodesla.omniverse.core.data.ChannelRow
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
import com.yodesla.omniverse.core.model.Episode
import com.yodesla.omniverse.core.model.MediaVersion
import com.yodesla.omniverse.core.model.PlaybackRequest
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
import com.yodesla.omniverse.core.model.mediaProgressKey
import com.yodesla.omniverse.core.source.ContentSource
import com.yodesla.omniverse.core.source.SourceConfig
import com.yodesla.omniverse.core.source.SyncDiagnostics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

private val srcA = SourceId("a")
private val srcB = SourceId("b")
private val cat = RemoteId("c")
private val keyMovieA = ContentKey(srcA, ContentKind.VOD, RemoteId("m1"))
private val keyMovieB = ContentKey(srcB, ContentKind.VOD, RemoteId("m2"))
private val keySeriesA = ContentKey(srcA, ContentKind.SERIES, RemoteId("s1"))
private val keySeriesB = ContentKey(srcB, ContentKind.SERIES, RemoteId("s2"))
private val keyEpisodeA = ContentKey(srcA, ContentKind.EPISODE, RemoteId("e1"))
private val keyEpisodeB = ContentKey(srcB, ContentKind.EPISODE, RemoteId("e2"))
private val showEverything: Visibility = { _, _, _ -> true }

class PlaybackFallbackTest {
    @Test
    fun fallbackChoicesExcludeTheCopyBeingPlayed() {
        val options = listOf(
            PlayOption(id = "v:1", label = "4K", sourceName = "A", qualityRank = 4, versionId = "v1", key = keyMovieA),
            PlayOption(id = "v:2", label = "1080p", sourceName = "A", qualityRank = 2, versionId = "v2", key = keyMovieA),
            PlayOption(id = "k:b:m2", label = null, sourceName = "B", qualityRank = 0, versionId = null, key = keyMovieB),
        )
        val choices = fallbackChoices(options, keyMovieA, "v1")
        assertEquals(listOf("v:2", "k:b:m2"), choices.map { it.id })
        assertEquals("1080p  ·  A", choices.first().label)
        assertEquals("B", choices.last().sourceName)
    }

    @Test
    fun resumeKeepsPositionWhenRuntimesAreCloseAndConvertsWhenTheyAreNot() {
        assertEquals(60_000L, resumeAfterCopySwitch(60_000L, 100_000L, 110_000L))
        assertEquals(110_000L, resumeAfterCopySwitch(120_000L, 100_000L, 110_000L))
        assertEquals(50_000L, resumeAfterCopySwitch(50_000L, 100_000L, 200_000L))
        assertEquals(150_000L, resumeAfterCopySwitch(50_000L, 100_000L, 300_000L))
        assertEquals(60_000L, resumeAfterCopySwitch(60_000L, null, 200_000L))
    }

    @Test
    fun loadFallbackChoicesUsesExactIdAlternatives() = runTest {
        val item = PlayItem(
            request = PlaybackRequest.Vod(srcA, RemoteId("m1"), "mp4"),
            key = keyMovieA,
            parentId = null,
            title = "Movie",
            resumeMs = 60_000L,
            durationMs = 100_000L,
        )
        val choices = loadFallbackChoices(
            item,
            FakeSources(
                FakeContentSource(srcA, vod = mapOf(RemoteId("m1") to vodDetail(srcA, "m1", 100, "mp4"))),
                extraNames = mapOf(srcB to "B"),
            ),
            FakeUserData(),
            FakeCatalog(movies = listOf(PosterRow(key = keyMovieB, name = "Movie", posterUrl = null, year = null, rating = null, categoryId = cat))),
            showEverything,
        )
        assertEquals(1, choices.size)
        assertEquals(keyMovieB, choices.single().key)
        assertEquals("B", choices.single().sourceName)
    }

    @Test
    fun loadFallbackChoicesHidesParentallyBlockedAlternatives() = runTest {
        val item = PlayItem(
            request = PlaybackRequest.Vod(srcA, RemoteId("m1"), "mp4"),
            key = keyMovieA,
            parentId = null,
            title = "Movie",
            resumeMs = 60_000L,
        )
        val denyB: Visibility = { _, sourceId, _ -> sourceId != "b" }
        val choices = loadFallbackChoices(
            item,
            FakeSources(FakeContentSource(srcA, vod = mapOf(RemoteId("m1") to vodDetail(srcA, "m1", 100, "mp4")))),
            FakeUserData(),
            FakeCatalog(movies = listOf(PosterRow(key = keyMovieB, name = "Movie", posterUrl = null, year = null, rating = null, categoryId = cat))),
            denyB,
        )
        assertEquals(emptyList<FallbackChoice>(), choices)
    }

    @Test
    fun loadFallbackChoicesOffersOtherVersionsInsideTheSameItem() = runTest {
        val item = PlayItem(
            request = PlaybackRequest.Vod(srcA, RemoteId("m1"), "mp4", "v1"),
            key = keyMovieA,
            parentId = null,
            title = "Movie",
            resumeMs = 60_000L,
        )
        val source = FakeContentSource(
            srcA,
            vod = mapOf(
                RemoteId("m1") to vodDetail(srcA, "m1", 100, "mp4").copy(
                    versions = listOf(MediaVersion("v1", 0, "4K"), MediaVersion("v2", 1, "1080p")),
                ),
            ),
        )
        val choices = loadFallbackChoices(item, FakeSources(source), FakeUserData(), FakeCatalog(), showEverything)
        assertEquals(listOf("v:v2"), choices.map { it.id })
        assertEquals("v2", choices.single().versionId)
    }

    @Test
    fun resolveFallbackMovieUsesTheAlternativeRuntimeAndContainer() = runTest {
        val failed = PlayItem(
            request = PlaybackRequest.Vod(srcA, RemoteId("m1"), "mp4"),
            key = keyMovieA,
            parentId = null,
            title = "Movie",
            resumeMs = 50_000L,
            durationMs = 100_000L,
        )
        val choice = FallbackChoice("k:b:m2", "B", "B", keyMovieB, null)
        val source = FakeContentSource(srcB, vod = mapOf(RemoteId("m2") to vodDetail(srcB, "m2", 300, "mkv")))
        val next = resolveFallbackItem(choice, failed, 50_000L, 100_000L, FakeSources(source), FakeUserData())
        assertEquals(srcB, next?.key?.sourceId)
        assertEquals("mkv", (next?.request as? PlaybackRequest.Vod)?.containerExt)
        assertEquals(150_000L, next?.resumeMs)
        assertEquals(300_000L, next?.durationMs)
    }

    @Test
    fun resolveFallbackVersionInsideSameItemPrefersThatFilesOwnProgress() = runTest {
        val failed = PlayItem(
            request = PlaybackRequest.Vod(srcA, RemoteId("m1"), "mp4", "v1"),
            key = keyMovieA,
            parentId = null,
            title = "Movie",
            resumeMs = 50_000L,
            durationMs = 100_000L,
        )
        val choice = FallbackChoice("v:v2", "1080p  ·  A", "A", keyMovieA, "v2")
        val progressKey = mediaProgressKey(keyMovieA, "v2")
        val userData = FakeUserData(progress = mapOf(progressKey to Progress(progressKey, null, 70_000L, 100_000L, 1L)))
        val next = resolveFallbackItem(choice, failed, 50_000L, 100_000L, FakeSources(FakeContentSource(srcA)), userData)
        assertEquals(progressKey, next?.key)
        assertEquals("v2", (next?.request as? PlaybackRequest.Vod)?.versionId)
        assertEquals(70_000L, next?.resumeMs)
    }

    @Test
    fun resolveFallbackEpisodeMatchesSeasonAndEpisodeOnTheOtherSource() = runTest {
        val failed = PlayItem(
            request = PlaybackRequest.EpisodeItem(srcA, RemoteId("e1"), "mp4"),
            key = keyEpisodeA,
            parentId = RemoteId("s1"),
            title = "Show · S1E2",
            resumeMs = 50_000L,
            season = 1,
            episode = 2,
            durationMs = 100_000L,
        )
        val choice = FallbackChoice("k:b:s2", "B", "B", keySeriesB, null)
        val episode = Episode(
            sourceId = srcB,
            remoteId = RemoteId("e2"),
            seriesId = RemoteId("s2"),
            season = 1,
            number = 2,
            title = "Ep",
            plot = null,
            durationSec = 300,
            stillUrl = null,
            containerExt = "mkv",
            rating = null,
        )
        val detail = SeriesDetail(
            record = SeriesRecord(
                sourceId = srcB,
                remoteId = RemoteId("s2"),
                name = "Show",
                posterUrl = null,
                backdropUrls = emptyList(),
                categoryIds = listOf(cat),
                plot = null,
                genre = null,
                rating = null,
                year = 2020,
                lastModifiedMs = null,
                sortIndex = 0,
                tmdbId = "t",
            ),
            cast = null,
            director = null,
            seasons = listOf(Season(1, null, null, listOf(episode))),
        )
        val source = FakeContentSource(srcB, series = mapOf(RemoteId("s2") to detail))
        val next = resolveFallbackItem(choice, failed, 50_000L, 100_000L, FakeSources(source), FakeUserData())
        assertEquals(keyEpisodeB, next?.key)
        assertEquals(RemoteId("s2"), next?.parentId)
        assertEquals(1, next?.season)
        assertEquals(2, next?.episode)
        assertEquals(150_000L, next?.resumeMs)
        assertEquals(300_000L, next?.durationMs)
        assertEquals("t", next?.tmdbId)
    }

    @Test
    fun resolveFallbackReturnsNullWhenTheAlternativeCannotBeResolved() = runTest {
        val failed = PlayItem(
            request = PlaybackRequest.Vod(srcA, RemoteId("m1"), "mp4"),
            key = keyMovieA,
            parentId = null,
            title = "Movie",
            resumeMs = 50_000L,
        )
        val choice = FallbackChoice("k:b:m2", "B", "B", keyMovieB, null)
        assertNull(resolveFallbackItem(choice, failed, 50_000L, 100_000L, FakeSources(FakeContentSource(srcB)), FakeUserData()))
    }

    private fun vodDetail(sourceId: SourceId, remoteId: String, durationSec: Int, containerExt: String) = VodDetail(
        record = VodRecord(
            sourceId = sourceId,
            remoteId = RemoteId(remoteId),
            name = "Movie",
            posterUrl = null,
            categoryIds = listOf(cat),
            rating = null,
            year = null,
            addedAtMs = null,
            containerExt = containerExt,
            tmdbId = "1",
            sortIndex = 0,
        ),
        plot = null,
        cast = null,
        director = null,
        genre = null,
        durationSec = durationSec,
        backdropUrls = emptyList(),
        releaseDate = null,
        trailerUrl = null,
    )

    private class FakeContentSource(
        override val id: SourceId,
        private val vod: Map<RemoteId, VodDetail> = emptyMap(),
        private val series: Map<RemoteId, SeriesDetail> = emptyMap(),
    ) : ContentSource {
        override val kind = SourceKind.XTREAM
        override val capabilities = emptySet<Capability>()
        override suspend fun accountInfo(): AccountInfo = error("unused")
        override fun liveCategories(): Flow<Category> = emptyFlow()
        override fun liveChannels(diagnostics: SyncDiagnostics): Flow<ChannelRecord> = emptyFlow()
        override fun vodCategories(): Flow<Category> = emptyFlow()
        override fun vodItems(diagnostics: SyncDiagnostics): Flow<VodRecord> = emptyFlow()
        override fun seriesCategories(): Flow<Category> = emptyFlow()
        override fun series(diagnostics: SyncDiagnostics): Flow<SeriesRecord> = emptyFlow()
        override suspend fun vodDetail(id: RemoteId): VodDetail = vod[id] ?: error("unused")
        override suspend fun seriesDetail(id: RemoteId): SeriesDetail = series[id] ?: error("unused")
        override fun epg(window: TimeWindow, channelKeys: Set<String>?, diagnostics: SyncDiagnostics): Flow<ProgrammeRecord> = emptyFlow()
        override suspend fun shortEpg(channelId: RemoteId, limit: Int): List<ProgrammeRecord> = emptyList()
        override suspend fun playback(request: PlaybackRequest): com.yodesla.omniverse.core.model.PlaybackSpec = error("unused")
    }

    private class FakeSources(
        private val source: ContentSource,
        private val extraNames: Map<SourceId, String> = emptyMap(),
    ) : SourceRepository {
        override fun sources(): Flow<List<SourceSummary>> = flowOf(
            listOf(SourceSummary(source.id, source.kind, source.id.value.uppercase(), null, null, null)) +
                extraNames.map { SourceSummary(it.key, SourceKind.XTREAM, it.value, null, null, null) },
        )
        override suspend fun add(config: SourceConfig): SourceId = error("unused")
        override suspend fun update(config: SourceConfig) = Unit
        override suspend fun remove(id: SourceId) = Unit
        override suspend fun config(id: SourceId): SourceConfig? = null
        override suspend fun contentSource(id: SourceId): ContentSource? = if (id == source.id) source else null
        override suspend fun probe(config: SourceConfig): AccountInfo = error("unused")
    }

    private class FakeCatalog(
        private val movies: List<PosterRow> = emptyList(),
        private val shows: List<PosterRow> = emptyList(),
    ) : CatalogRepository {
        override fun categories(sourceId: SourceId, kind: ContentKind, includeHidden: Boolean): Flow<List<Category>> = flowOf(emptyList())
        override fun channels(sourceId: SourceId, categoryId: RemoteId): PagingSource<Int, ChannelRow> = error("unused")
        override fun channelsAll(sourceId: SourceId, excludedCategories: Collection<String>): PagingSource<Int, ChannelRow> = error("unused")
        override suspend fun channelListAll(sourceId: SourceId, excludedCategories: Collection<String>): List<ChannelRow> = emptyList()
        override suspend fun channelOrderAll(sourceId: SourceId, excludedCategories: Collection<String>): List<ContentKey> = emptyList()
        override suspend fun channelList(sourceId: SourceId, categoryId: RemoteId): List<ChannelRow> = emptyList()
        override suspend fun channelNeighbours(key: ContentKey, categoryId: RemoteId): Pair<ChannelRow?, ChannelRow?> = null to null
        override suspend fun channelOrder(sourceId: SourceId, categoryId: RemoteId): List<ContentKey> = emptyList()
        override suspend fun channel(key: ContentKey): ChannelRow? = null
        override fun vod(sourceId: SourceId, categoryId: RemoteId?): PagingSource<Int, PosterRow> = error("unused")
        override fun series(sourceId: SourceId, categoryId: RemoteId?): PagingSource<Int, PosterRow> = error("unused")
        override fun vodAll(excludedCategoryKeys: Collection<String>): PagingSource<Int, PosterRow> = error("unused")
        override fun seriesAll(excludedCategoryKeys: Collection<String>): PagingSource<Int, PosterRow> = error("unused")
        override fun recentlyAdded(kind: ContentKind, limit: Int): Flow<List<PosterRow>> = flowOf(emptyList())
        override suspend fun poster(key: ContentKey): PosterRow? = null
        override suspend fun exactMovieMatches(key: ContentKey): List<PosterRow> = movies
        override suspend fun exactSeriesMatches(key: ContentKey): List<PosterRow> = shows
        override fun counts(sourceId: SourceId): Flow<Map<ContentKind, Long>> = flowOf(emptyMap())
    }

    private class FakeUserData(private val progress: Map<ContentKey, Progress> = emptyMap()) : UserDataRepository {
        override fun favorites(kind: ContentKind?): Flow<List<ContentKey>> = flowOf(emptyList())
        override fun isFavorite(key: ContentKey): Flow<Boolean> = flowOf(false)
        override suspend fun setFavorite(key: ContentKey, favorite: Boolean) = Unit
        override suspend fun saveProgress(key: ContentKey, parentId: RemoteId?, positionMs: Long, durationMs: Long?) = Unit
        override fun continueWatching(limit: Int): Flow<List<Progress>> = flowOf(emptyList())
        override suspend fun progress(key: ContentKey): Progress? = progress[key]
        override suspend fun recordChannelWatched(key: ContentKey) = Unit
        override fun recentChannels(limit: Int): Flow<List<ContentKey>> = flowOf(emptyList())
        override suspend fun setHidden(key: ContentKey, hidden: Boolean) = Unit
        override fun setting(key: String): Flow<String?> = flowOf(null)
        override suspend fun putSetting(key: String, value: String) = Unit
    }
}
