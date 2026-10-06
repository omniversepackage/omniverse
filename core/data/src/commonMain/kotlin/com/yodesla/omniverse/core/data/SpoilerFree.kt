package com.yodesla.omniverse.core.data

/**
 * Task 95: spoiler-free mode (Settings › Playback, per profile, default OFF). Pure rules so they
 * are testable without UI: which episodes must stay hidden, and what replaces the spoiler text
 * where an episode card, an "Up next" subtitle or the player's next-episode card would show one.
 */
object SpoilerFree {
    /** Replaces an episode card's plot line while hidden; OK on the card reveals it for the visit. */
    const val HIDDEN_DESCRIPTION = "Description hidden - press OK to reveal"

    /** Replaces the next episode's title on the player's "Up next" card while hidden. */
    const val HIDDEN_NEXT_TITLE = "Next episode"

    /** Setting parsing: default OFF — only the literal "true" turns either switch on. */
    fun enabled(value: String?): Boolean = value == "true"

    /** Sub-option "Also hide episode titles": default OFF, same parsing as the parent switch. */
    fun hideTitles(value: String?): Boolean = value == "true"

    /**
     * Episode ids (the same keys as [progress]) that must stay spoiler-hidden: every UNWATCHED
     * episode ordered AFTER the most recently touched one (watched or in progress — the episode
     * being watched is never hidden). Nothing watched yet = the whole series is hidden. Episodes
     * at or before the newest watch (skipped ones included) are not spoilers, and a watched
     * episode after it (an old row) stays open.
     */
    fun hiddenEpisodeIds(orderedEpisodeIds: List<String>, progress: Map<String, Progress>): Set<String> {
        val lastTouched = progress.values.maxByOrNull { it.updatedMs }
            ?.let { p -> orderedEpisodeIds.indexOf(p.key.remoteId.value) }
            ?: -1
        return orderedEpisodeIds.filterIndexed { index, id -> index > lastTouched && !isWatched(progress[id]) }.toSet()
    }

    /** Same watched rule as the episode cards: completed row, full position, or the position-1 sentinel. */
    private fun isWatched(p: Progress?): Boolean {
        if (p == null) return false
        if (p.completed) return true
        val dur = p.durationMs?.takeIf { it > 0 }
        return if (dur != null) p.positionMs >= dur else p.positionMs == 1L
    }

    /** The Continue Watching / Up next card subtitle; the "S2 · E4" part is the spoiler. */
    fun upNextSubtitle(season: Int, number: Int, spoilerFree: Boolean): String =
        if (spoilerFree) "Up next" else "Up next  ·  S$season · E$number"

    /** The player's next-episode card title; the title itself is the spoiler there. */
    fun nextEpisodeTitle(title: String, spoilerFree: Boolean): String =
        if (spoilerFree) HIDDEN_NEXT_TITLE else title
}
