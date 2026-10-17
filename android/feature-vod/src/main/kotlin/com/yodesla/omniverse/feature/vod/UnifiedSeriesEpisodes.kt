package com.yodesla.omniverse.feature.vod

import com.yodesla.omniverse.core.model.Episode
import com.yodesla.omniverse.core.model.Season
import com.yodesla.omniverse.core.model.SeriesDetail

/** One playable copy of an episode. The source and parent series are carried by [episode]. */
data class EpisodeSource(val episode: Episode, val sourceName: String)

data class UnifiedSeriesEpisodes(
    val detail: SeriesDetail,
    val sourcesByEpisode: Map<Pair<Int, Int>, List<EpisodeSource>>,
)

/** Keep every source's episode coverage while showing a single row for each season/episode number. */
fun mergeSeriesEpisodes(
    primary: SeriesDetail,
    primarySourceName: String,
    alternatives: List<Pair<SeriesDetail, String>>,
): UnifiedSeriesEpisodes {
    val copies = listOf(primary to primarySourceName) + alternatives
    val grouped = linkedMapOf<Pair<Int, Int>, MutableList<EpisodeSource>>()
    copies.forEach { (detail, sourceName) ->
        detail.seasons.forEach { season ->
            season.episodes.forEach { episode ->
                val slot = episode.season to episode.number
                val options = grouped.getOrPut(slot) { mutableListOf() }
                if (options.none { it.episode.sourceId == episode.sourceId && it.episode.remoteId == episode.remoteId }) {
                    options += EpisodeSource(episode, sourceName)
                }
            }
        }
    }
    val seasons = grouped.entries.groupBy { it.key.first }.toSortedMap().map { (number, entries) ->
        val metadata = copies.asSequence().flatMap { it.first.seasons.asSequence() }
            .firstOrNull { it.number == number }
        Season(
            number = number,
            name = metadata?.name,
            posterUrl = metadata?.posterUrl,
            episodes = entries.sortedBy { it.key.second }.map { it.value.first().episode },
        )
    }
    return UnifiedSeriesEpisodes(primary.copy(seasons = seasons), grouped.mapValues { it.value.toList() })
}

internal fun episodeSlot(episode: Episode): Pair<Int, Int> = episode.season to episode.number

internal fun episodeProgressId(episode: Episode): String =
    "${episode.sourceId.value}|${episode.remoteId.value}"
