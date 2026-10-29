package com.yodesla.omniverse.feature.vod

import com.yodesla.omniverse.core.model.Episode
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.PlaybackRequest
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.Season
import com.yodesla.omniverse.core.model.SeriesDetail
import com.yodesla.omniverse.core.model.SeriesRecord
import com.yodesla.omniverse.core.model.SourceId
import com.yodesla.omniverse.core.model.VideoInfo
import com.yodesla.omniverse.core.model.DynamicRange
import kotlin.test.Test
import kotlin.test.assertEquals

class UnifiedSeriesEpisodesTest {
    @Test fun episodeSourceChoiceShowsVerifiedQualityAndRuntime() {
        val probed = episode("plex", "p", 1, 1, "p1").copy(video = VideoInfo(3840, 2160, DynamicRange.DOLBY_VISION))
        assertEquals("Korys Theatre  ·  4K DV  ·  20m", episodeSourceLabel(EpisodeSource(probed, "Korys Theatre")))
        val unknown = probed.copy(video = null, durationSec = null)
        assertEquals("Gem Ruby", episodeSourceLabel(EpisodeSource(unknown, "Gem Ruby")))
    }

    @Test fun homeAndDetailShareNextEpisodeAndStartOverSelection() {
        val first = episode("plex", "p", 1, 1, "p1")
        val next = episode("plex", "p", 2, 1, "p2")
        val progress = com.yodesla.omniverse.core.data.Progress(
            ContentKey(first.sourceId, ContentKind.EPISODE, first.remoteId), first.seriesId,
            1_200_000L, 1_200_000L, 100L, completed = true,
        )
        val state = DetailState(series = detail("plex", "p", first, next), episodeProgress = mapOf("p1" to progress))
        assertEquals(next, state.primarySeriesEpisode())
        assertEquals(first, state.primarySeriesEpisode(fromStart = true))
        assertEquals(first, state.copy(focusEpisodeId = first.remoteId).primarySeriesEpisode())
        assertEquals(first, state.copy(episodeProgress = mapOf("p1" to progress.copy(completed = false))).primarySeriesEpisode())
    }

    @Test fun continueUsesTheExplicitSourceEvenWhenAnotherCopyHoldsTheWatch() {
        val watched = episode("iptv", "i", 1, 4, "i4")
        val selected = episode("plex", "p", 1, 4, "p4")
        val options = listOf(EpisodeSource(watched, "IPTV"), EpisodeSource(selected, "Korys Theatre"))
        val pick = editionItemPickId(ContentKey(selected.sourceId, ContentKind.SERIES, selected.seriesId))
        assertEquals(selected, episodeFromPickedSource(watched, options, pick))
        assertEquals(watched, episodeFromPickedSource(watched, options, null))
        assertEquals(watched, episodeFromPickedSource(watched, listOf(options.first()), pick))
    }
    private fun episode(source: String, series: String, season: Int, number: Int, id: String) = Episode(
        SourceId(source), RemoteId(id), RemoteId(series), season, number, "Episode $number", null, 1200,
        null, "mkv", null,
    )

    private fun detail(source: String, series: String, vararg episodes: Episode) = SeriesDetail(
        SeriesRecord(SourceId(source), RemoteId(series), "Attack on Titan", null, emptyList(), emptyList(),
            null, null, null, 2013, null, 0, null),
        null, null, episodes.groupBy { it.season }.map { (season, rows) -> Season(season, null, null, rows) },
    )

    @Test fun combinesCoverageFromAnyNumberOfSourcesAndOffersEachPlayableCopy() {
        val plex = detail("plex", "p", episode("plex", "p", 0, 1, "ova"), episode("plex", "p", 1, 1, "p1"))
        val iptv = detail("iptv", "i", episode("iptv", "i", 1, 1, "i1"), episode("iptv", "i", 1, 2, "i2"))
        val emby = detail("emby", "e", episode("emby", "e", 1, 1, "e1"), episode("emby", "e", 2, 1, "e2"))

        val merged = mergeSeriesEpisodes(plex, "Plex", listOf(iptv to "IPTV", emby to "Emby"))

        assertEquals(listOf(0, 1, 2), merged.detail.seasons.map { it.number })
        assertEquals(listOf(1, 2), merged.detail.seasons.first { it.number == 1 }.episodes.map { it.number })
        assertEquals(listOf("Plex", "IPTV", "Emby"), merged.sourcesByEpisode[1 to 1]?.map { it.sourceName })
        assertEquals(listOf("Plex"), merged.sourcesByEpisode[0 to 1]?.map { it.sourceName })
        assertEquals(listOf("IPTV"), merged.sourcesByEpisode[1 to 2]?.map { it.sourceName })
        assertEquals("emby", merged.detail.seasons.first { it.number == 2 }.episodes.single().sourceId.value)
    }

    @Test fun anIptvEpisodeChoicePlaysAndContinuesWithinItsOwnSeries() {
        val first = episode("iptv", "i", 1, 1, "i1")
        val second = episode("iptv", "i", 1, 2, "i2")
        val play = buildEpisodeQueue(first, listOf(first, second),
            ContentKey(first.sourceId, ContentKind.SERIES, first.seriesId), "Attack on Titan", emptyMap())
        assertEquals(PlaybackRequest.EpisodeItem(first.sourceId, first.remoteId, "mkv"), play.request)
        assertEquals(first.seriesId, play.parentId)
        assertEquals(second.sourceId, (play.next?.request as PlaybackRequest.EpisodeItem).sourceId)
        assertEquals(second.seriesId, play.next?.parentId)
    }
}
