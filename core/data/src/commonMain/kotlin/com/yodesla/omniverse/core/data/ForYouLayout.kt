package com.yodesla.omniverse.core.data

/**
 * How the For You page is laid out (its own switch and Settings › Display, per profile, default
 * Cinematic). Cinematic puts full-screen art of the focused pick over personal rows; Magazine lays
 * the page out like an editorial front page. Pure parsing so the rule is testable without UI: only
 * the literal [MAGAZINE] selects the magazine — an absent, empty or unknown value is [CINEMATIC].
 */
object ForYouLayout {
    /** The per-profile setting key. */
    const val KEY = "for_you_layout"

    /** Full-screen art of whatever is focused, over personal rows. */
    const val CINEMATIC = "cinematic"

    /** Editorial front page: feature spread, curated sections, a headlines briefing. */
    const val MAGAZINE = "magazine"

    fun parse(raw: String?): String = if (raw == MAGAZINE) MAGAZINE else CINEMATIC
}
