package com.yodesla.omniverse.designsystem

private val trailingYear = Regex("""\s*[(\[](\d{4})[)\]]\s*$""")
private val trailingPictureTag = Regex(
    """(?i)\s*(?:[-·|:]\s*)?(?:[(\[]\s*)?(?:2160p|1080p|720p|4k|8k|uhd)(?:\s*[-· ]\s*(?:dv|dolby\s*vision|hdr10\+?|hdr))?\s*(?:[)\]])?\s*$"""
)

/** "Movie Title (2019)" + year 2019 → "Movie Title" (the year is shown separately). Keeps other years. */
fun displayTitle(name: String, year: Int?): String {
    var title = name.trim()
    // Provider titles sometimes carry the quality of one file. That is misleading when the
    // viewer selects another copy; quality belongs on the copy label, never the movie title.
    repeat(2) {
        trailingYear.find(title)?.takeIf { year == null || it.groupValues[1].toInt() == year }
            ?.let { title = title.removeRange(it.range).trim() }
        trailingPictureTag.find(title)?.let { title = title.removeRange(it.range).trimEnd(' ', '-', '·', '|', ':') }
    }
    return title.ifBlank { name }
}
