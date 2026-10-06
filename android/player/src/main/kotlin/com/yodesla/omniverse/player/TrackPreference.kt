package com.yodesla.omniverse.player

/** What the viewer chose for one show's subtitles, remembered so the next episode starts the same way. */
enum class SubtitleMode { OFF, FORCED, FULL }

/**
 * A viewer's remembered audio/subtitle choice for one show (its episodes share it) or movie. Languages
 * are the ISO 639 codes exactly as the container tags them; null means "no memory for that part", so the
 * viewer's global default applies. The whole choice lives in one settings row via [encode].
 */
data class TrackPreference(
    val audioLanguage: String?,
    val subtitleMode: SubtitleMode?,
    val subtitleLanguage: String?,
) {
    /** Nothing remembered for this show: the viewer's global defaults apply unchanged. */
    val isEmpty: Boolean get() = audioLanguage == null && subtitleMode == null

    fun encode(): String = buildString {
        append("a="); append(audioLanguage.orEmpty())
        append(";s=")
        when (subtitleMode) {
            null -> Unit
            SubtitleMode.OFF -> append("off")
            SubtitleMode.FORCED -> append("forced:").append(subtitleLanguage.orEmpty())
            SubtitleMode.FULL -> append("full:").append(subtitleLanguage.orEmpty())
        }
    }

    companion object {
        val EMPTY = TrackPreference(null, null, null)

        /** Tolerant of an absent/blank row and of a value a different build wrote in another shape. */
        fun decode(value: String?): TrackPreference {
            if (value.isNullOrBlank()) return EMPTY
            val parts = value.split(';')
            val audio = parts.firstOrNull { it.startsWith("a=") }
                ?.removePrefix("a=")?.takeIf { it.isNotBlank() }
            val sub = parts.firstOrNull { it.startsWith("s=") }?.removePrefix("s=")
            if (sub.isNullOrBlank()) return TrackPreference(audio, null, null)
            if (sub == "off") return TrackPreference(audio, SubtitleMode.OFF, null)
            val mode = when (sub.substringBefore(':', "")) {
                "forced" -> SubtitleMode.FORCED
                "full" -> SubtitleMode.FULL
                else -> null
            }
            val language = sub.substringAfter(':', "").takeIf { it.isNotBlank() }
            return if (mode == null) TrackPreference(audio, null, null) else TrackPreference(audio, mode, language)
        }
    }
}

/**
 * The id of the track matching [language] (case-insensitive) and, when [forcedOnly] is not null, that
 * forced-only role — never by position, so a provider reordering its tracks cannot change the choice.
 * Null when nothing matches, so the caller falls back to the viewer's global default. A blank language
 * never matches: there is nothing trustworthy to match on.
 */
fun matchingTrackId(tracks: List<Track>, language: String?, forcedOnly: Boolean?): String? {
    val want = language?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    return tracks.firstOrNull { t ->
        t.language?.trim()?.lowercase() == want && (forcedOnly == null || t.forcedOnly == forcedOnly)
    }?.id
}
