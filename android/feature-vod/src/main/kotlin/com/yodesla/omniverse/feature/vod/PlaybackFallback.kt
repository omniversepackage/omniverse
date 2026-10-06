package com.yodesla.omniverse.feature.vod

import androidx.compose.runtime.Immutable
import com.yodesla.omniverse.core.data.CatalogRepository
import com.yodesla.omniverse.core.data.SourceRepository
import com.yodesla.omniverse.core.data.UserDataRepository
import com.yodesla.omniverse.core.data.Visibility
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.Episode
import com.yodesla.omniverse.core.model.PlaybackRequest
import com.yodesla.omniverse.core.model.SeriesDetail
import com.yodesla.omniverse.core.model.mediaProgressKey
import kotlinx.coroutines.flow.first
import kotlin.math.abs

/**
 * Task 93: when a VOD stream dies and the same-URL refresh retries are spent, the player offers
 * the other copies of the same title — the exact list the Detail version chips build. Everything
 * here is source-shaped data only; the player owns the UI and the engine.
 */

/** One other copy the player can switch to: another file in the same item, or another source's copy. */
@Immutable
data class FallbackChoice(
    /** Chip id ("v:…" / "k:source:remote") — the same identity the version chips use. */
    val id: String,
    /** What the row reads: edition/quality label plus source name. */
    val label: String,
    /** Source display name alone; the countdown reads "Switching to <source>…". */
    val sourceName: String,
    val key: ContentKey,
    /** Set when this copy is another file inside the item being played. */
    val versionId: String?,
)

/** Seconds the auto-switch countdown runs when exactly one alternative exists. */
internal const val FALLBACK_COUNTDOWN_S = 5

/**
 * The alternatives for the copy being played, in chip order (best first): the other files inside
 * the item and every parentally visible exact-ID copy of the title. The copy being played is
 * excluded; the list is capped like the detail chips so the panel stays scannable.
 */
internal fun fallbackChoices(
    options: List<PlayOption>,
    currentKey: ContentKey,
    currentVersionId: String?,
): List<FallbackChoice> =
    options.filterNot { it.key == currentKey && it.versionId == currentVersionId }
        .filterNot { currentVersionId != null && it.key == currentKey && it.versionId == null }
        .take(MAX_DETAIL_CHIPS)
        .map { FallbackChoice(it.id, it.chipLabel, it.sourceName, it.key, it.versionId) }

/**
 * Where the alternative copy resumes. Same absolute position when the two runtimes are within two
 * minutes of each other (different encodes of one master drift by seconds, not minutes); the same
 * percentage of the film when they differ more. An unknown runtime on either side keeps the raw
 * position — there is nothing to convert against.
 */
internal fun resumeAfterCopySwitch(positionMs: Long, fromDurationMs: Long?, toDurationMs: Long?): Long {
    val pos = positionMs.coerceAtLeast(0L)
    val from = fromDurationMs?.takeIf { it > 0 } ?: return pos
    val to = toDurationMs?.takeIf { it > 0 } ?: return pos
    if (abs(from - to) <= 120_000L) return pos.coerceAtMost(to)
    return (pos * to / from).coerceAtMost(to)
}

/**
 * The alternatives for the item the player is showing, built the way Detail builds its version
 * chips: the files inside the item (movies only) plus every parentally visible exact-ID copy of
 * the title. An episode switches to the same season/episode of another source's copy of its show.
 */
internal suspend fun loadFallbackChoices(
    item: PlayItem,
    sources: SourceRepository,
    userData: UserDataRepository,
    catalog: CatalogRepository?,
    allowed: Visibility,
): List<FallbackChoice> {
    if (catalog == null) return emptyList()
    val titleKey = when (item.key.kind) {
        ContentKind.VOD -> item.key
        ContentKind.EPISODE -> item.parentId?.let { ContentKey(item.key.sourceId, ContentKind.SERIES, it) }
        else -> null
    } ?: return emptyList()
    val sourceNames = runCatching { sources.sources().first().associate { it.id to it.name } }.getOrDefault(emptyMap())
    val versions = if (item.key.kind == ContentKind.VOD) {
        runCatching { sources.contentSource(item.key.sourceId)?.vodDetail(item.key.remoteId)?.versions }.getOrNull().orEmpty()
    } else {
        emptyList()
    }
    val related = alternativeCopies(titleKey, catalog, userData, sourceNames, allowed)
    val edition = userData.itemOverride(item.key)?.editionLabel
    val options = buildPlayOptions(item.key, sourceNames[item.key.sourceId] ?: "Source", edition, versions, related)
    return fallbackChoices(options, item.key, (item.request as? PlaybackRequest.Vod)?.versionId)
}

/**
 * The PlayItem that opens [choice] at the position the failed copy was at. Resolves the copy's own
 * detail (container extension, runtime, and for episodes the matching S/E on the other source) and
 * converts the position when the runtimes differ by more than two minutes. Null when the copy can
 * no longer be resolved — the player then keeps the error and the remaining offers.
 */
internal suspend fun resolveFallbackItem(
    choice: FallbackChoice,
    failed: PlayItem,
    positionMs: Long,
    fromDurationMs: Long?,
    sources: SourceRepository,
    userData: UserDataRepository,
): PlayItem? {
    val source = sources.contentSource(choice.key.sourceId) ?: return null
    if (choice.key == failed.key && choice.versionId != null) {
        // Another file inside the same provider item: progress is per-file, so a watch on this
        // exact file wins over converting the failed file's position.
        val progressKey = mediaProgressKey(choice.key, choice.versionId)
        val resume = userData.progress(progressKey)?.positionMs?.takeIf { it > 0 }
            ?: resumeAfterCopySwitch(positionMs, fromDurationMs, null)
        return PlayItem(
            PlaybackRequest.Vod(choice.key.sourceId, choice.key.remoteId,
                (failed.request as? PlaybackRequest.Vod)?.containerExt, choice.versionId),
            progressKey, failed.parentId, failed.title, resume,
            tmdbId = failed.tmdbId, durationMs = failed.durationMs,
        )
    }
    if (choice.key.kind == ContentKind.VOD) {
        val d = runCatching { source.vodDetail(choice.key.remoteId) }.getOrNull() ?: return null
        val durationMs = d.durationSec?.takeIf { it > 0 }?.times(1000L)
        return PlayItem(
            PlaybackRequest.Vod(choice.key.sourceId, choice.key.remoteId, d.record.containerExt, choice.versionId),
            choice.key, null, failed.title,
            resumeAfterCopySwitch(positionMs, fromDurationMs, durationMs),
            tmdbId = d.record.tmdbId ?: failed.tmdbId, durationMs = durationMs,
        )
    }
    if (choice.key.kind == ContentKind.SERIES && failed.key.kind == ContentKind.EPISODE &&
        failed.season != null && failed.episode != null) {
        val d = runCatching { source.seriesDetail(choice.key.remoteId) }.getOrNull() ?: return null
        val ep = matchingEpisode(d, failed.season, failed.episode) ?: return null
        val all = d.seasons.sortedBy { it.number }.flatMap { s -> s.episodes.sortedBy { it.number } }
        val progress = all.mapNotNull { e ->
            userData.progress(ContentKey(choice.key.sourceId, ContentKind.EPISODE, e.remoteId))
                ?.let { e.remoteId.value to it }
        }.toMap()
        val queue = buildEpisodeQueue(ep, all, choice.key, d.record.name, progress,
            d.record.tmdbId ?: failed.tmdbId, d.record.name, d.record.year)
        // The failed copy's live position is fresher than anything saved on the alternative copy.
        return queue.copy(resumeMs = resumeAfterCopySwitch(positionMs, fromDurationMs,
            ep.durationSec?.takeIf { it > 0 }?.times(1000L)))
    }
    return null
}

/** The same season/episode number on another source's copy of the show. */
internal fun matchingEpisode(detail: SeriesDetail, season: Int, episode: Int): Episode? =
    detail.seasons.sortedBy { it.number }.flatMap { it.episodes.sortedBy { e -> e.number } }
        .firstOrNull { it.season == season && it.number == episode }

/**
 * Task 93: the parentally visible exact-ID copies of a title (the same related list the Detail
 * version chips build), named by source. [key] is the movie or show item, never an episode key.
 */
internal suspend fun alternativeCopies(
    key: ContentKey,
    catalog: CatalogRepository?,
    userData: UserDataRepository,
    sourceNames: Map<com.yodesla.omniverse.core.model.SourceId, String>,
    allowed: Visibility,
): List<RelatedMovie> {
    val matches = when (key.kind) {
        ContentKind.VOD -> catalog?.exactMovieMatches(key).orEmpty()
        ContentKind.SERIES -> catalog?.exactSeriesMatches(key).orEmpty()
        else -> return emptyList()
    }
    return matches.filter { row ->
        row.key != key &&
            (row.categoryId?.let { allowed(row.key.kind.name, row.key.sourceId.value, it.value) } ?: true)
    }.distinctBy { it.key }.take(MAX_EDITION_ALTS)
        .map { RelatedMovie(it, sourceNames[it.key.sourceId] ?: "Source", userData.itemOverride(it.key)?.editionLabel) }
}

