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

/** Standard poster variants that can be matched to the actual output pixel width. */
private val TMDB_LARGE_SIZE_SEGMENTS = listOf("original", "w600_and_h900_bestv2", "w185", "w342", "w500", "w780")

/** TMDB image hosts (the only ones whose path layout we may rewrite). */
private val TMDB_IMAGE_HOSTS = setOf("image.tmdb.org", "image.themoviedb.org")

/**
 * Match TMDB poster resolution to the card's physical pixels, including focus growth.
 * Leave other hosts, unrecognized paths, query strings and hero-sized requests unchanged.
 */
fun tmdbPosterUrlForCard(url: String?, cardWidthDp: Int, density: Float = 2f): String? {
    if (url == null || cardWidthDp > TMDB_CARD_MAX_WIDTH_DP) return url
    val schemeEnd = url.indexOf("://")
    if (schemeEnd < 0) return url
    val host = url.substring(schemeEnd + 3).takeWhile { it != '/' && it != '?' && it != '#' }.lowercase()
    if (host !in TMDB_IMAGE_HOSTS) return url
    // Include focus growth. A 4K TV must not upscale the 1080p card variant.
    val pixels = cardWidthDp * density * OmniMotion.FOCUS_SCALE
    val variant = when {
        pixels <= 342f -> "w342"
        pixels <= 500f -> "w500"
        pixels <= 780f -> "w780"
        else -> "original"
    }
    for (segment in TMDB_LARGE_SIZE_SEGMENTS) {
        val needle = "/$segment/"
        val idx = url.indexOf(needle, ignoreCase = true)
        if (idx >= 0) return url.replaceRange(idx + 1, idx + needle.length - 1, variant)
    }
    return url
}
