package com.yodesla.omniverse.app

import com.yodesla.omniverse.core.data.PosterRow
import com.yodesla.omniverse.core.data.Progress
import com.yodesla.omniverse.core.data.Visibility
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.progressPosterKey
import com.yodesla.omniverse.designsystem.displayTitle

/** One artwork slot of the screensaver rotation. */
data class ScreensaverItem(
    val key: ContentKey,
    val title: String,
    val image: String?,
)

/**
 * Task 70 — which artwork the screensaver rotates through, and in what order.
 * Pure (no Android imports) so the rules are unit-testable:
 * - Continue Watching first (most recent watch first), resolved to its browse poster.
 * - Then recently added movies and shows, interleaved so a rotation shows both kinds early.
 * - Parental/library visibility exactly as Home applies it: a poster with no category passes.
 * - One slot per title: the same TMDB title from several sources appears once, first seen wins.
 * [posterOf] resolves a progress key to its poster (null when the catalog no longer has it).
 */
suspend fun pickScreensaverArt(
    continueWatching: List<Progress>,
    recentlyAddedMovies: List<PosterRow>,
    recentlyAddedShows: List<PosterRow>,
    visibility: Visibility,
    posterOf: suspend (ContentKey) -> PosterRow?,
): List<ScreensaverItem> {
    val visible = fun(p: PosterRow): Boolean =
        p.categoryId?.let { visibility(p.key.kind.name, p.key.sourceId.value, it.value) } ?: true
    val group = fun(p: PosterRow): String =
        p.tmdbId?.takeIf { it.isNotBlank() }?.let { "${p.key.kind}:tmdb:$it" }
            ?: "${p.key.kind}:key:${p.key.sourceId.value}:${p.key.remoteId.value}"

    val out = ArrayList<ScreensaverItem>()
    val seen = HashSet<String>()
    fun add(p: PosterRow) {
        if (!visible(p)) return
        if (!seen.add(group(p))) return
        out += ScreensaverItem(p.key, displayTitle(p.name, p.year), p.posterUrl)
    }
    // Continue Watching: newest watch first (sorted here so the order never depends on the
    // caller's list); episodes resolve to their series poster.
    continueWatching.sortedByDescending { it.updatedMs }.forEach { p ->
        val posterKey = progressPosterKey(p.key, p.parentId) ?: return@forEach
        posterOf(posterKey)?.let(::add)
    }
    // Recently added: alternate movies and shows so both kinds appear in the first rotation.
    val n = maxOf(recentlyAddedMovies.size, recentlyAddedShows.size)
    for (i in 0 until n) {
        recentlyAddedMovies.getOrNull(i)?.let(::add)
        recentlyAddedShows.getOrNull(i)?.let(::add)
    }
    return out
}
