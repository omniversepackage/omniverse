package com.yodesla.omniverse.core.model

/** Edition words stated by a filename or provider title, never inferred from size or bitrate. */
fun explicitEditionLabel(value: String?): String? {
    val text = value.orEmpty()
    return listOf(
        Regex("collector.?s[ ._-]+extended", RegexOption.IGNORE_CASE) to "Collector's Extended Cut",
        Regex("director.?s[ ._-]+cut", RegexOption.IGNORE_CASE) to "Director's Cut",
        Regex("special[ ._-]+edition", RegexOption.IGNORE_CASE) to "Special Edition",
        Regex("extended[ ._-]+(?:cut|edition)", RegexOption.IGNORE_CASE) to "Extended Cut",
        Regex("unrated(?:[ ._-]+cut)?", RegexOption.IGNORE_CASE) to "Unrated Cut",
        Regex("theatrical(?:[ ._-]+cut)?", RegexOption.IGNORE_CASE) to "Theatrical Cut",
        Regex("final[ ._-]+cut", RegexOption.IGNORE_CASE) to "Final Cut",
    ).firstOrNull { (pattern, _) -> pattern.containsMatchIn(text) }?.second
}

/** Narrow, verified runtime fallback for files whose provider does not name their cut. */
fun knownEditionLabel(tmdbId: String?, title: String?, year: Int?, durationSec: Int?): String? {
    if (tmdbId == "120" || (title?.contains("Fellowship of the Ring", ignoreCase = true) == true && year == 2001)) {
        return when (durationSec) {
            in 10560..10860 -> "Theatrical Cut"
            in 12360..12660, in 13560..13860 -> "Extended Edition"
            else -> null
        }
    }
    val isAvatar = tmdbId == "19995" || (title?.trim() == "Avatar" && year == 2009)
    if (!isAvatar) return null
    return when (durationSec) {
        in 9660..9840 -> "Theatrical Cut"
        in 10180..10300 -> "Special Edition"
        in 10650..10770 -> "Collector's Extended Cut"
        else -> null
    }
}

/** Compare an actual file length with the standard release length supplied by movie metadata.
 * Large differences reveal a distinct cut, but cannot establish its official edition name. */
fun relativeEditionLabel(durationSec: Int?, referenceSec: Int?): String? {
    if (durationSec == null || referenceSec == null || durationSec < 3600 || referenceSec < 3600) return null
    val differenceMin = (durationSec - referenceSec) / 60
    return when {
        differenceMin >= 10 -> "Longer cut (+${differenceMin}m)"
        differenceMin <= -10 -> "Shorter cut (${differenceMin}m)"
        else -> null
    }
}
