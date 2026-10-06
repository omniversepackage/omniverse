package com.yodesla.omniverse.core.model

/*
 * Catalog records as produced by a ContentSource during sync.
 * Conventions:
 *  - All times are UTC epoch milliseconds (Long). Rendering converts to local time.
 *  - Optional fields are null when the source didn't provide a usable value (never "" or 0 as "unknown").
 *  - URLs are absolute. Credentials never appear in these records except inside playback URLs,
 *    which are built on demand by ContentSource.playback() and never persisted.
 */

data class Category(
    val sourceId: SourceId,
    val kind: ContentKind,
    val remoteId: RemoteId,
    val name: String,
    val parentId: RemoteId? = null,
    /** Order as delivered by the source. */
    val sortIndex: Int,
)

data class ChannelRecord(
    val sourceId: SourceId,
    val remoteId: RemoteId,
    /** Provider channel number ("num"), if any. */
    val number: Int?,
    val name: String,
    val logoUrl: String?,
    /** Key used to join with EPG data (XMLTV channel id / tvg-id). */
    val epgChannelId: String?,
    /** First entry is the primary category. Never empty: sources use an "Uncategorized" id if needed. */
    val categoryIds: List<RemoteId>,
    /** Days of catch-up archive; 0 = no catch-up. */
    val catchupDays: Int,
    val addedAtMs: Long?,
    /** Order as delivered by the source. */
    val sortIndex: Int,
)

data class VodRecord(
    val sourceId: SourceId,
    val remoteId: RemoteId,
    val name: String,
    val posterUrl: String?,
    val categoryIds: List<RemoteId>,
    /** 0..10 scale. */
    val rating: Float?,
    val year: Int?,
    val addedAtMs: Long?,
    /** File extension used to build the playback URL, e.g. "mp4", "mkv". */
    val containerExt: String?,
    val tmdbId: String?,
    val sortIndex: Int,
    val genre: String? = null,
)

data class SeriesRecord(
    val sourceId: SourceId,
    val remoteId: RemoteId,
    val name: String,
    val posterUrl: String?,
    val backdropUrls: List<String>,
    val categoryIds: List<RemoteId>,
    val plot: String?,
    val genre: String?,
    /** 0..10 scale. */
    val rating: Float?,
    val year: Int?,
    val lastModifiedMs: Long?,
    val sortIndex: Int,
    /** Source-supplied TMDB show id; never inferred from title/year. */
    val tmdbId: String? = null,
)

data class VodDetail(
    val record: VodRecord,
    val plot: String?,
    val cast: String?,
    val director: String?,
    val genre: String?,
    val durationSec: Int?,
    val backdropUrls: List<String>,
    val releaseDate: String?,
    val trailerUrl: String?,
    /** Playable files within this source item; empty when the source does not expose choices. */
    val versions: List<MediaVersion> = emptyList(),
)

data class MediaVersion(val id: String, val index: Int, val label: String)

data class SeriesDetail(
    val record: SeriesRecord,
    val cast: String?,
    val director: String?,
    val seasons: List<Season>,
)

data class Season(
    val number: Int,
    val name: String?,
    val posterUrl: String?,
    val episodes: List<Episode>,
)

data class Episode(
    val sourceId: SourceId,
    val remoteId: RemoteId,
    val seriesId: RemoteId,
    val season: Int,
    val number: Int,
    val title: String,
    val plot: String?,
    val durationSec: Int?,
    val stillUrl: String?,
    val containerExt: String?,
    val rating: Float?,
)

data class ProgrammeRecord(
    val sourceId: SourceId,
    /** Matches ChannelRecord.epgChannelId. */
    val channelKey: String,
    val startMs: Long,
    val endMs: Long,
    val title: String,
    val subtitle: String? = null,
    val description: String? = null,
    val category: String? = null,
    val episodeNum: String? = null,
    val iconUrl: String? = null,
    val hasArchive: Boolean = false,
)
