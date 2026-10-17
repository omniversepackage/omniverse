package com.yodesla.omniverse.core.data

/**
 * Task 203: how Movies & Shows are laid out (Settings › Display, per profile, default Classic).
 * Classic is the sidebar the app has always used; Horizontal puts the categories in rows across the
 * screen. Pure parsing so the rule is testable without UI: only the literal [HORIZONTAL] selects
 * the new layout — an absent, empty or unknown value is [CLASSIC], the existing behaviour.
 */
object MoviesShowsView {
    /** The per-profile setting key. */
    const val KEY = "movies_shows_view"

    /** Category sidebar (the layout Movies & Shows have always had). */
    const val CLASSIC = "classic"

    /** Category rows across the screen. */
    const val HORIZONTAL = "horizontal"

    /** Setting parsing: default Classic — only the literal "horizontal" changes the layout. */
    fun parse(raw: String?): String = if (raw == HORIZONTAL) HORIZONTAL else CLASSIC
}
