package com.yodesla.omniverse.feature.vod

import com.yodesla.omniverse.core.data.PosterRow
import com.yodesla.omniverse.core.model.SourceId

/** Which art a Netflix-style landscape surface uses. */
internal sealed interface NetflixArt {
    /** Anything that carries an image URL (every kind except [Blank]). */
    sealed interface WithUrl : NetflixArt {
        val url: String
    }

    /** Real wide art: no title overlay needed. */
    data class Backdrop(override val url: String) : WithUrl

    /**
     * Task 87b (Kory: "the art for the banner and the movie preview shouldn't be the same"): the
     * title has only one backdrop, so a card reuses the banner art and must carry the title
     * treatment — the TMDB logo, else the title scrim — to read differently from the billboard.
     */
    data class BannerReuse(override val url: String) : WithUrl

    /** No wide art (IPTV VOD): blurred poster fill with the poster centered over it. */
    data class PosterFill(override val url: String) : WithUrl

    data object Blank : NetflixArt
}

/**
 * Card art (task 87b): the second backdrop TMDB stored for this title; when there is none, the
 * banner art with a title treatment; when there is no wide art at all, the poster fill.
 */
internal fun netflixCardArt(row: PosterRow): NetflixArt = when {
    !row.cardBackdropUrl.isNullOrBlank() -> NetflixArt.Backdrop(row.cardBackdropUrl!!)
    !row.backdropUrl.isNullOrBlank() -> NetflixArt.BannerReuse(row.backdropUrl!!)
    !row.posterUrl.isNullOrBlank() -> NetflixArt.PosterFill(row.posterUrl!!)
    else -> NetflixArt.Blank
}

/** Cards that need a title treatment inside them: reused banner art, or art we fell back to. */
internal fun NetflixArt.needsTitleOverlay(): Boolean = this !is NetflixArt.Backdrop

/** Billboard art: wide art, else the poster scaled to fill and cropped from the top. */
internal fun netflixBillboardArt(row: PosterRow): NetflixArt = when {
    !row.backdropUrl.isNullOrBlank() -> NetflixArt.Backdrop(row.backdropUrl!!)
    !row.posterUrl.isNullOrBlank() -> NetflixArt.Backdrop(row.posterUrl!!)
    else -> NetflixArt.Blank
}

/** One row of the Netflix home, in the order the native app shows them. */
internal sealed interface NetflixRow {
    data class ContinueWatching(val title: String, val cards: List<ContinuePosterUi>) : NetflixRow
    data class Top10(val title: String, val rows: List<PosterRow>) : NetflixRow
    data class MyList(val title: String, val rows: List<PosterRow>) : NetflixRow
    data class Shelf(val key: String, val title: String, val rows: List<PosterRow>) : NetflixRow
}

/**
 * Row order (Kory, task 84c): Continue Watching for <profile> → Top 10 → My List → the plan's
 * curated rows → "Browse all" chunks. Empty rows are dropped entirely.
 */
internal fun netflixRows(
    continueWatching: List<ContinuePosterUi>,
    myList: List<PosterRow>,
    pool: List<PosterRow>,
    plan: BrandPlan,
    profileName: String?,
): List<NetflixRow> {
    val rows = mutableListOf<NetflixRow>()
    if (continueWatching.isNotEmpty()) {
        val title = profileName?.takeIf { it.isNotBlank() }?.let { "Continue Watching for $it" } ?: "Continue Watching"
        rows += NetflixRow.ContinueWatching(title, continueWatching)
    }
    val top = pool.take(10)
    if (top.isNotEmpty()) rows += NetflixRow.Top10(plan.top10, top)
    if (myList.isNotEmpty()) rows += NetflixRow.MyList("My List", myList)
    val rated = pool.filter { (it.rating ?: 0f) >= 7f }.sortedByDescending { it.rating }.take(20)
    if (rated.size >= 4) rows += NetflixRow.Shelf("rated", plan.rated, rated)
    val fresh = pool.filter { it.year != null }.sortedByDescending { it.year }.take(20)
    if (fresh.size >= 4) rows += NetflixRow.Shelf("fresh", plan.fresh, fresh)
    val vault = pool.filter { (it.year ?: 9999) < 2005 }.take(20)
    if (vault.size >= 4) rows += NetflixRow.Shelf("vault", plan.vault, vault)
    pool.drop(10).chunked(20).forEachIndexed { i, chunk ->
        rows += NetflixRow.Shelf("more-$i", if (i == 0) "Browse all" else "Browse all · ${i + 1}", chunk)
    }
    return rows
}

/** Cards shown for the current selection: "All" (null source) shows everything. */
internal fun inBrowseCategory(poster: PosterRow, selection: Pair<SourceId?, String?>?): Boolean {
    val (src, cat) = selection ?: return true
    if (src != null && poster.key.sourceId != src) return false
    if (cat != null && cat.isNotEmpty() && poster.categoryId?.value != cat) return false
    return true
}

/** The native app labels the billboard "FILM" / "SERIES" in red caps. */
internal fun netflixKindLabel(kindLabel: String): String =
    if (kindLabel.lowercase().startsWith("mov")) "FILM" else "SERIES"

/** Quality from the source's own name tags only; nothing is inferred. */
internal fun qualityTag(name: String): String? {
    val n = name.lowercase()
    return when {
        listOf("4k", "uhd", "2160p").any { n.contains(it) } -> "4K"
        listOf("fhd", "hd", "1080p", "720p").any { n.contains(it) } -> "HD"
        else -> null
    }
}

/** "1h 12m left" the way the native app counts it down; null when unknown or under a minute. */
internal fun timeLeftLabel(remainingMs: Long?): String? {
    if (remainingMs == null || remainingMs < 60_000L) return null
    val totalMin = remainingMs / 60_000L
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m left"
        h > 0 -> "${h}h left"
        else -> "${m}m left"
    }
}
