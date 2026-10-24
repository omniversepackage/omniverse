package com.yodesla.omniverse.feature.vod

import com.yodesla.omniverse.core.model.SkipMarker

data class CommunitySkipQuery(
    val tmdbId: String,
    val season: Int?,
    val episode: Int?,
    val durationMs: Long?,
    /** Original provider series name, only used for the opted-in ID fallback. */
    val seriesTitle: String? = null,
    val seriesYear: Int? = null,
)

fun interface CommunitySkipLookup {
    suspend fun lookup(query: CommunitySkipQuery): List<SkipMarker>
}

/** Unverified episode credits must fit in the final fifth, capped at five minutes. */
fun plausibleCommunityCredits(marker: SkipMarker, durationMs: Long, episode: Boolean): Boolean =
    marker.type != "credits" || !episode ||
        (durationMs > 0 && marker.startMs >= durationMs - minOf(durationMs / 5, 300_000L))
