package com.yodesla.omniverse.core.source.m3u

/**
 * Guesses what kind of stream an entry is, purely from the entry (no network).
 *
 * Order matters: a path marker wins over a file extension (a movie may sit under `/series/`),
 * and a file extension only counts as a movie when the entry has no catch-up configured.
 */
fun classify(e: M3uEntry): M3uKind {
    val url = e.url
    if ("/movie/" in url) return M3uKind.MOVIE
    if ("/series/" in url) return M3uKind.SERIES
    if (!e.hasCatchup() && hasMediaExtension(url)) return M3uKind.MOVIE
    return M3uKind.LIVE
}

/** True when the entry has any usable catch-up configuration (entry level or inherited). */
fun M3uEntry.hasCatchup(): Boolean = !catchup.isNullOrEmpty() || !catchupSource.isNullOrEmpty()

private fun hasMediaExtension(url: String): Boolean {
    val path = url.substringBefore('?').substringBefore('#').lowercase()
    return path.endsWith(".mp4") || path.endsWith(".mkv") || path.endsWith(".avi")
}
