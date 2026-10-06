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
