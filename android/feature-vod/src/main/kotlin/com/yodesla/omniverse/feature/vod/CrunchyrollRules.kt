package com.yodesla.omniverse.feature.vod

import com.yodesla.omniverse.core.data.PosterRow
import com.yodesla.omniverse.core.data.metadata.RtScores
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.SourceId

/**
 * The pure rules behind [CrunchyrollLayout] (task 86b): what the hero features, which rows appear
 * in which order, and the "Sub | Dub" line. No Compose, no repositories — everything here is
 * testable on the JVM.
 */

/** How many titles the hero carousel rotates through. */
internal const val CrunchyrollFeaturedCount = 5

/** A shelf shorter than this is noise; Crunchyroll does not show it. */
internal const val CrunchyrollMinShelf = 4

/**
 * Hero carousel step (task 84l, Kory: "Left/Right on the billboard should change the title"):
 * the next/previous featured index, wrapping in both directions. Empty carousel stays at 0.
 */
internal fun crunchyrollHeroStep(current: Int, count: Int, delta: Int): Int =
    if (count <= 0) 0 else ((current + delta) % count + count) % count

/** Poster focus drives the backdrop; hero focus shows the manually selected featured title. */
internal fun crunchyrollBackdropRow(hero: PosterRow?, focusedPoster: PosterRow?, heroFocused: Boolean): PosterRow? =
    if (heroFocused) hero else focusedPoster ?: hero

/**
 * Task 121: the hero's Rotten Tomatoes badge gate. A score is tagged with the exact title it was
 * looked up for and paints only while that title is still the hero — paging the billboard to the
 * next title must never show the previous title's badges, and a title with no exact TMDB id or no
 * RT entry gets none (null in → null out; the app never guesses a score).
 */
internal fun crunchyrollHeroScores(shownKey: ContentKey?, lookedUpKey: ContentKey?, scores: RtScores?): RtScores? =
    scores?.takeIf { lookedUpKey != null && lookedUpKey == shownKey }

/**
 * The Continue Watching cards that belong on a brand category page (task 84l, Kory: "CW shows
 * titles that are never on this service"). A card stays when its own poster sits in the selected
 * source+category, or when the same title is in that category's pool — exact key or exact TMDB
 * id only, never a name guess. Watching a Crunchyroll title through Plex still belongs on the
 * Crunchyroll page; a movie from another provider with no copy here never shows.
 */
internal fun cwForBrowseCategory(
    continueWatching: List<ContinuePosterUi>,
    pool: List<PosterRow>,
    selection: Pair<SourceId?, String?>?,
): List<ContinuePosterUi> {
    val (src, cat) = selection ?: return continueWatching
    val keys = pool.mapTo(mutableSetOf()) { it.key }
    val tmdbIds = pool.mapNotNull { it.tmdbId }.toSet()
    return continueWatching.filter { cw ->
        val p = cw.poster
        (src == null || p.key.sourceId == src) && (cat.isNullOrEmpty() || p.categoryId?.value == cat) ||
            p.key in keys ||
            (p.tmdbId != null && p.tmdbId in tmdbIds)
    }
}

/**
 * Crunchyroll's audio line, read only from names the provider actually gave (the title itself,
 * its category, and every copy of the merged title). "Sub"/"Dub" are matched as whole words so
 * "Substitute" or "Dublin" never produce a label. Unknown → null: the app never guesses.
 */
private val DubPattern = Regex("""\bdubs?\b|\bdubbed\b|\benglish dub\b|\bdubbing\b""", RegexOption.IGNORE_CASE)
private val SubPattern = Regex("""\bsub\b|\bsubbed\b|\bsubtitle[ds]?\b|\bsubtitled\b|\bvostfr\b|\bjapanese\b""", RegexOption.IGNORE_CASE)

internal fun subDubLabel(names: List<String>): String? {
    var sub = false
    var dub = false
    for (name in names) {
        if (!dub && DubPattern.containsMatchIn(name)) dub = true
        if (!sub && SubPattern.containsMatchIn(name)) sub = true
        if (sub && dub) break
    }
    return when {
        sub && dub -> "Sub | Dub"
        dub -> "Dubbed"
        sub -> "Subtitled"
        else -> null
    }
}

/**
 * Age rating, only when a name literally carries one (providers put "TV-MA" in the category name).
 * Case-sensitive: rating tokens are always upper-case, so a lower-case word in a title is not one.
 */
private val AgeRatingPattern = Regex("""TV-Y7-FV|TV-Y7|TV-Y|TV-G|TV-PG|TV-MA|NC-17|PG-13|PG""")

internal fun ageRatingLabel(vararg names: String?): String? =
    names.filterNotNull().asSequence().mapNotNull { name -> AgeRatingPattern.find(name)?.value }.firstOrNull()

/** Provider genres, at most [max], in the provider's own order. */
internal fun crunchyrollGenres(genre: String?, max: Int = 3): List<String> =
    genre.orEmpty().split('|', ',', '/').map(String::trim).filter(String::isNotEmpty).distinct().take(max)

/** Hero key art: the show's wide backdrop when the source has one, else the poster. */
internal fun crunchyrollKeyArt(row: PosterRow): String? =
    row.backdropUrl?.takeIf(String::isNotBlank) ?: row.posterUrl?.takeIf(String::isNotBlank)

/** Which art a Crunchyroll landscape card uses (task 87b). */
internal sealed interface CrunchArt {
    /** Anything that carries an image URL (every kind except [None]). */
    sealed interface WithUrl : CrunchArt {
        val url: String
    }

    /** The second backdrop TMDB stored: different art from the hero, nothing overlaid. */
    data class Card(override val url: String) : WithUrl

    /** Only the hero's own backdrop exists: the card must carry a title treatment to differ. */
    data class Banner(override val url: String) : WithUrl

    /** No wide art at all: the poster. */
    data class Poster(override val url: String) : WithUrl

    data object None : CrunchArt
}

/** Card art (task 87b, Kory: banner art and preview art must not be the same picture). */
internal fun crunchyrollCardArt(row: PosterRow): CrunchArt = when {
    !row.cardBackdropUrl.isNullOrBlank() -> CrunchArt.Card(row.cardBackdropUrl!!)
    !row.backdropUrl.isNullOrBlank() -> CrunchArt.Banner(row.backdropUrl!!)
    !row.posterUrl.isNullOrBlank() -> CrunchArt.Poster(row.posterUrl!!)
    else -> CrunchArt.None
}

/** A card showing the hero's own picture must carry a title treatment so the two read differently. */
internal fun CrunchArt.needsTitleOverlay(): Boolean = this is CrunchArt.Banner

/**
 * The hero carousel's five titles: visible titles only (parental locks and viewer-hidden
 * categories never reach the billboard), art required, best-rated first, one card per title.
 * A library with fewer than [limit] art-carrying titles simply rotates through fewer.
 */
internal fun crunchyrollFeatured(
    pool: List<PosterRow>,
    visible: (PosterRow) -> Boolean = { true },
    limit: Int = CrunchyrollFeaturedCount,
): List<PosterRow> = pool
    .filter { visible(it) && !crunchyrollKeyArt(it).isNullOrBlank() }
    .distinctBy { it.uid() }
    .sortedWith(compareByDescending<PosterRow> { it.rating ?: -1f }.thenBy { it.name.lowercase() })
    .take(limit)

/** One row on the Crunchyroll home: [landscape] cards are the Continue Watching strip. */
internal data class CrunchyrollRowSpec(
    val key: String,
    val title: String,
    val items: List<PosterRow>,
    val landscape: Boolean = false,
)

/**
 * Row order (Kory): Continue Watching first when there is any, then the service's own plan rows,
 * then "Browse all" chunks. A plan row needs at least [CrunchyrollMinShelf] titles to appear.
 */
internal fun crunchyrollRows(
    pool: List<PosterRow>,
    continueWatching: List<ContinuePosterUi>,
    plan: BrandPlan,
    continueWatchingTitle: String = "Continue Watching",
    minShelf: Int = CrunchyrollMinShelf,
): List<CrunchyrollRowSpec> {
    val rows = mutableListOf<CrunchyrollRowSpec>()
    if (continueWatching.isNotEmpty()) rows += CrunchyrollRowSpec("cw", continueWatchingTitle, continueWatching.map { it.poster }, landscape = true)
    val top = pool.take(10)
    if (top.isNotEmpty()) rows += CrunchyrollRowSpec("top", plan.top10, top)
    val rated = pool.filter { (it.rating ?: 0f) >= 7f }.sortedByDescending { it.rating }.take(20)
    if (rated.size >= minShelf) rows += CrunchyrollRowSpec("rated", plan.rated, rated)
    val fresh = pool.filter { it.year != null }.sortedByDescending { it.year }.take(20)
    if (fresh.size >= minShelf) rows += CrunchyrollRowSpec("fresh", plan.fresh, fresh)
    val vault = pool.filter { (it.year ?: 9999) < 2005 }.take(20)
    if (vault.size >= minShelf) rows += CrunchyrollRowSpec("vault", plan.vault, vault)
    pool.drop(10).chunked(20).forEachIndexed { i, chunk ->
        rows += CrunchyrollRowSpec("more-$i", if (i == 0) plan.more else "${plan.more} · ${i + 1}", chunk)
    }
    return rows
}

/** "18m left" under a Continue Watching card; null when the runtime is unknown or it is over. */
internal fun crunchyrollMinutesLeft(positionMs: Long, durationMs: Long?): String? {
    val left = durationMs?.minus(positionMs) ?: return null
    if (left <= 0) return null
    return "${((left + 59_999L) / 60_000L)}m left"
}

/** "S1 E5" from the provider's own season/episode numbers; null when either is unknown. */
internal fun crunchyrollEpisodeLabel(season: Int?, number: Int?): String? =
    if (season != null && number != null) "S$season E$number" else null
