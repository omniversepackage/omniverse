package com.yodesla.omniverse.designsystem

/**
 * Poster URL sizing helpers (task 87). Providers hand us TMDB artwork at full resolution
 * (`/original/`, sometimes `/w600_and_h900_bestv2/`); a 124.dp Home card does not need a
 * 1000×1500 JPEG. Rewriting the size segment server-side cuts cold-start bytes by ~10x.
 * Pure functions only — no Compose, no Coil — so the rewrite is unit-testable.
 */

/** Cards at or below this width get the small TMDB variant; hero/backdrops keep the large one. */
const val TMDB_CARD_MAX_WIDTH_DP = 220

/** TMDB's poster variant a card-width request should use (≈ 2x a 124.dp card at TV density). */
const val TMDB_CARD_SIZE = "w342"

/** Large TMDB variants worth downgrading for card-sized requests. */
private val TMDB_LARGE_SIZE_SEGMENTS = listOf("original", "w600_and_h900_bestv2")

/** TMDB image hosts (the only ones whose path layout we may rewrite). */
private val TMDB_IMAGE_HOSTS = setOf("image.tmdb.org", "image.themoviedb.org")

/**
 * Returns [url] rewritten to TMDB's [TMDB_CARD_SIZE] poster variant when it is a TMDB image at a
 * large size segment and [cardWidthDp] is within card range; otherwise returns [url] unchanged.
 * Never touches non-TMDB hosts, already-small variants, query strings, or hero-sized requests.
 */
fun tmdbPosterUrlForCard(url: String?, cardWidthDp: Int): String? {
    if (url == null || cardWidthDp > TMDB_CARD_MAX_WIDTH_DP) return url
    val schemeEnd = url.indexOf("://")
    if (schemeEnd < 0) return url
    val host = url.substring(schemeEnd + 3).takeWhile { it != '/' && it != '?' && it != '#' }.lowercase()
    if (host !in TMDB_IMAGE_HOSTS) return url
    for (segment in TMDB_LARGE_SIZE_SEGMENTS) {
        val needle = "/$segment/"
        val idx = url.indexOf(needle, ignoreCase = true)
        if (idx >= 0) return url.replaceRange(idx + 1, idx + needle.length - 1, TMDB_CARD_SIZE)
    }
    return url
}
