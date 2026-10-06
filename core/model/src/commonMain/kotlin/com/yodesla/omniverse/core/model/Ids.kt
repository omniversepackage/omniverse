package com.yodesla.omniverse.core.model

import kotlin.jvm.JvmInline

/** Stable local id of a configured source (UUID string, generated when the user adds it). */
@JvmInline
value class SourceId(val value: String)

/** The id a source uses for an item (Xtream stream_id / series_id / episode id, Jellyfin item id...). */
@JvmInline
value class RemoteId(val value: String)

enum class SourceKind { XTREAM, M3U, JELLYFIN, PLEX, EMBY }

enum class ContentKind { LIVE, VOD, SERIES, EPISODE }

/** What a source can do. UI hides features a source lacks. */
enum class Capability { LIVE, EPG, VOD, SERIES, CATCHUP, RECORDINGS, SERVER_SEARCH, SERVER_PROGRESS }

/** Globally unique key for any item. User data (favorites, progress, hidden...) is keyed by this. */
data class ContentKey(val sourceId: SourceId, val kind: ContentKind, val remoteId: RemoteId)

/** Local progress identity for a playable file within one provider movie. Never sent to the provider. */
fun mediaProgressKey(movie: ContentKey, versionId: String): ContentKey {
    require(movie.kind == ContentKind.VOD && versionId.isNotBlank())
    return movie.copy(remoteId = RemoteId("omni-media:${movie.remoteId.value.length}:${movie.remoteId.value}:$versionId"))
}

/** Resolve a progress row back to its browse poster without exposing synthetic media ids. */
fun progressPosterKey(key: ContentKey, parentId: RemoteId?): ContentKey? = when (key.kind) {
    ContentKind.EPISODE -> parentId?.let { ContentKey(key.sourceId, ContentKind.SERIES, it) }
    ContentKind.VOD -> if (parentId != null) ContentKey(key.sourceId, ContentKind.VOD, parentId) else key
    else -> null
}

/** Half-open time range [startMs, endMs) in UTC epoch milliseconds. */
data class TimeWindow(val startMs: Long, val endMs: Long) {
    init { require(endMs >= startMs) { "endMs < startMs" } }
    operator fun contains(ms: Long): Boolean = ms >= startMs && ms < endMs
}
