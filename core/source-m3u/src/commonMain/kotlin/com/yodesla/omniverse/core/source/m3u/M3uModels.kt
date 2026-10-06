package com.yodesla.omniverse.core.source.m3u

/**
 * Playlist-level options from the `#EXTM3U` line. Entry-level catch-up attributes
 * fall back to these when the entry does not set its own.
 */
data class M3uHeader(
    val epgUrls: List<String>,
    val catchup: String?,
    val catchupSource: String?,
    val catchupDays: Int?,
) {
    companion object {
        /**
         * Parses the attribute part of a `#EXTM3U` line. `url-tvg` and `x-tvg-url` may each hold
         * a comma-separated list of EPG URLs; both are collected, in order.
         */
        fun parseAttrs(body: String): M3uHeader {
            val attrs = scanM3uAttrs(body, commaTerminates = false).values
            val urls = ArrayList<String>()
            for (key in listOf("url-tvg", "x-tvg-url")) {
                attrs[key]?.split(',')?.forEach { if (it.isNotBlank()) urls += it.trim() }
            }
            return M3uHeader(
                epgUrls = urls,
                catchup = attrs["catchup"],
                catchupSource = attrs["catchup-source"],
                catchupDays = attrs["catchup-days"]?.toIntOrNull(),
            )
        }
    }
}

/**
 * One stream from a playlist.
 *
 * [name] is the display text after the first comma that is outside quotes on the `#EXTINF`
 * line; for bare URL lines it is the URL's last path segment.
 */
data class M3uEntry(
    val name: String,
    val url: String,
    val durationSec: Int,
    val attrs: Map<String, String>,
    val group: String?,
    val tvgId: String?,
    val tvgName: String?,
    val logo: String?,
    val chno: Int?,
    val catchup: String?,
    val catchupDays: Int?,
    val catchupSource: String?,
    val userAgent: String?,
    val referrer: String?,
    val index: Int,
)

enum class M3uKind { LIVE, MOVIE, SERIES }
