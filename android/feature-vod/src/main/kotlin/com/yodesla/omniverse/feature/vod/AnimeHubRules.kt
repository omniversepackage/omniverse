package com.yodesla.omniverse.feature.vod

import com.yodesla.omniverse.core.data.AnimeRules
import com.yodesla.omniverse.core.data.ChannelRow
import com.yodesla.omniverse.core.data.PosterRow

/**
 * Task 142: the pure rules behind the Anime hub (Explore › Anime). Every classification reads only
 * names the provider gave — category names, provider genres, channel names — as whole words, never
 * a title substring, so "Animal Planet" or "Nickel Mines" never land on the page.
 */

/** Which channel row a live channel belongs on. */
enum class AnimeChannelKind { ANIME, CARTOON }

private val AnimeChannelWords = AnimeRules.AnimeTokens + setOf("animax", "toonami", "pokemon", "pokémon", "naruto")
private val AnimeChannelPhrases = listOf("dragon ball", "one piece", "sailor moon", "anime all day")
private val CartoonChannelWords = setOf(
    "cartoon", "cartoons", "cartoonito", "toon", "toons", "nicktoons", "boomerang",
    "animation", "animated", "teletoon", "toonavision",
)
private val CartoonChannelPhrases = listOf("adult swim", "disney xd", "disney junior", "nick jr", "baby tv", "babytv", "toon disney")

/**
 * A channel is ANIME when its name or category carries an anime word, CARTOON when it carries an
 * animation/cartoon outlet word, else null (not on the hub). Anime wins when both match.
 */
internal fun animeChannelKind(name: String, categoryName: String?): AnimeChannelKind? {
    val text = "$name ${categoryName.orEmpty()}".replace('.', ' ').replace('!', ' ')
    val words = AnimeRules.tokens(text).toSet()
    val flat = " " + AnimeRules.tokens(text).joinToString(" ") + " "
    return when {
        words.any { it in AnimeChannelWords } || AnimeChannelPhrases.any { " $it " in flat } -> AnimeChannelKind.ANIME
        words.any { it in CartoonChannelWords } || CartoonChannelPhrases.any { " $it " in flat } -> AnimeChannelKind.CARTOON
        else -> null
    }
}

/** Same channel offered twice (FHD/HD copies, a second provider): one card, the first copy wins. */
internal fun channelNameKey(name: String): String = name.lowercase()
    .replace(Regex("""^\s*[a-z]{2,3}\s*[|:▎\-]\s*"""), "")
    .replace(Regex("""\b(4k|uhd|fhd|hd|sd|hevc|h265|raw|backup|alt|\d{3,4}p)\b|[^\p{L}\p{N} ]"""), " ")
    .replace(Regex("""\s+"""), " ")
    .trim()

/** The hub's two channel rows, deduplicated by name and kept in provider order. */
internal fun animeChannelRows(candidates: List<Pair<ChannelRow, String?>>, visible: (ChannelRow) -> Boolean): Map<AnimeChannelKind, List<ChannelRow>> =
    candidates.asSequence()
        .filter { (c, _) -> visible(c) }
        .mapNotNull { (c, cat) -> animeChannelKind(c.name, cat)?.let { it to c } }
        .distinctBy { (_, c) -> channelNameKey(c.name) }
        .groupBy({ it.first }, { it.second })

private val AnimationWords = setOf("animation", "animated", "cartoon", "cartoons")

/** A title belongs on the hub: anime (AnimeRules) or plain animation, from its category or genre. */
internal fun isAnimatedTitle(categoryName: String?, genre: String?): Boolean =
    AnimeRules.isAnime(categoryName, genre) ||
        (AnimeRules.tokens(categoryName) + AnimeRules.tokens(genre)).any { it in AnimationWords }

/** One shelf of the hub's Home tab. non-empty [ranks] (row uid → TMDB chart rank, used for ordering) makes the shelf ranked: badges number its rows 1, 2, 3…; [caption] names the source. */
data class AnimeShelf(val key: String, val title: String, val rows: List<PosterRow>, val caption: String? = null, val ranks: Map<String, Int> = emptyMap())

/** A shelf shorter than this is dropped (the same floor Crunchyroll's page uses). */
internal const val AnimeMinShelf = CrunchyrollMinShelf

/**
 * The Home tab's shelves in display order. Empty or too-short shelves disappear, and a title shows
 * at most once across the whole page (first shelf wins), so the screens never repeat posters.
 */
internal fun animeHubShelves(candidates: List<AnimeShelf>, minShelf: Int = AnimeMinShelf): List<AnimeShelf> {
    val seen = HashSet<String>()
    return candidates.mapNotNull { shelf ->
        val rows = shelf.rows.distinctBy { it.titleIdentity() ?: it.key }.filter { seen.add(it.hubId()) }
        shelf.copy(rows = rows).takeIf { rows.size >= minShelf }
    }
}

/** Title identity for de-duplication: the exact TMDB id when known, else the exact row. */
internal fun PosterRow.hubId(): String = tmdbId?.let { "${key.kind}:tmdb:$it" } ?: "${key.kind}:${uid()}"

/** Rows with a provider rating only: a "Top rated" shelf must not be padded with unrated titles. */
/** Rated titles for the "Top" shelves; when too few have a real score yet, all titles (rated first). */
internal fun ratedOnly(rows: List<PosterRow>): List<PosterRow> {
    val rated = rows.filter { (it.rating ?: 0f) > 0f }
    return if (rated.size >= 12) rated else rated + rows.filter { (it.rating ?: 0f) <= 0f }
}

/**
 * Provider "ratings" that are placeholders: some IPTV sources give every anime the same score
 * (8.0). When one value covers 40%+ of the rated titles it says nothing, so it is dropped and only
 * real scores (TMDB, applied afterwards) are shown.
 */
internal fun dropPlaceholderRatings(rows: List<PosterRow>): List<PosterRow> {
    val rated = rows.mapNotNull { r -> r.rating?.takeIf { it > 0f }?.let { kotlin.math.round(it * 10f).toInt() } }
    if (rated.size < 20) return rows
    val (mode, count) = rated.groupingBy { it }.eachCount().maxBy { it.value }
    if (count < rated.size * 0.4) return rows
    return rows.map { r -> val v = r.rating; if (v != null && kotlin.math.round(v * 10f).toInt() == mode) r.copy(rating = null) else r }
}

/** Interleave two lists (series, movie, series …) so a mixed shelf is not all one kind up front. */
internal fun <T> interleave(a: List<T>, b: List<T>): List<T> {
    val out = ArrayList<T>(a.size + b.size)
    val n = maxOf(a.size, b.size)
    for (i in 0 until n) {
        a.getOrNull(i)?.let(out::add)
        b.getOrNull(i)?.let(out::add)
    }
    return out
}


/** How the Series / Movies grids are ordered. TRENDING shows only titles on TMDB's live chart, in chart order. */
enum class AnimeGridSort(val label: String) { TRENDING("Trending"), RATING("Top rated"), NEWEST("Newest"), TITLE("A–Z") }

/** Anime only, other animation only, or both. */
enum class AnimeTypeFilter(val label: String) { ALL("All"), ANIME("Anime"), CARTOONS("Cartoons & animation") }

/** Release-decade filter; [range] null = any year. */
enum class AnimeDecade(val label: String, val range: IntRange?) {
    ANY("Any year", null), D2020("2020s", 2020..2029), D2010("2010s", 2010..2019), D2000("2000s", 2000..2009),
    D1990("1990s", 1990..1999), CLASSIC("Before 1990", 0..1989),
}

/** One grid title with its classification (anime vs other animation). */
data class HubTitle(val row: PosterRow, val anime: Boolean)

/** The Series / Movies grid filter set. [genre] null = every genre. */
data class AnimeGridFilter(
    val sort: AnimeGridSort = AnimeGridSort.TRENDING,
    val type: AnimeTypeFilter = AnimeTypeFilter.ALL,
    val decade: AnimeDecade = AnimeDecade.ANY,
    val genre: String? = null,
)

private val GenreSplit = Regex("""\s*[,|/&]\s*""")
private val NotAGenre = setOf("animation", "anime", "animated", "cartoon", "cartoons", "kids & family")

/** A title's provider genres, one per entry ("Action, Adventure" → [Action, Adventure]). */
internal fun genresOf(row: PosterRow): List<String> =
    row.genre.orEmpty().split(GenreSplit).map { it.trim() }.filter { it.length in 2..24 && it.lowercase() !in NotAGenre }

/** The [max] most common provider genres in [titles] — the genre chips, most common first. */
internal fun topGenres(titles: List<HubTitle>, max: Int = 12): List<String> =
    titles.flatMap { t -> genresOf(t.row).map { it.lowercase().replaceFirstChar(Char::uppercase) }.distinct() }
        .groupingBy { it }.eachCount().entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .take(max).map { it.key }

/**
 * The grid: [titles] narrowed by type, decade and genre, then ordered. TRENDING keeps only titles
 * that hold a rank in [trendingRanks] (row [hubId] → TMDB chart rank) and orders them by that rank —
 * a library title that is not on the chart is never shown as trending.
 */
internal fun animeGrid(titles: List<HubTitle>, filter: AnimeGridFilter, trendingRanks: Map<String, Int>): List<PosterRow> {
    val picked = titles.asSequence()
        .filter { t -> when (filter.type) { AnimeTypeFilter.ALL -> true; AnimeTypeFilter.ANIME -> t.anime; AnimeTypeFilter.CARTOONS -> !t.anime } }
        .filter { t -> filter.decade.range?.let { r -> t.row.year?.let { it in r } == true } ?: true }
        .filter { t -> filter.genre?.let { g -> genresOf(t.row).any { it.equals(g, ignoreCase = true) } } ?: true }
        .map { it.row }
        .toList()
    return when (filter.sort) {
        AnimeGridSort.TRENDING -> picked.filter { it.hubId() in trendingRanks }.sortedBy { trendingRanks[it.hubId()] }
        AnimeGridSort.RATING -> picked.sortedWith(compareBy<PosterRow> { (it.rating ?: 0f) <= 0f }.thenByDescending { it.rating ?: 0f })
        AnimeGridSort.NEWEST -> picked.sortedWith(compareBy<PosterRow> { it.year == null }.thenByDescending { it.year ?: 0 })
        AnimeGridSort.TITLE -> picked.sortedBy { it.name.lowercase() }
    }
}

/** Live channels: which group to show. SHOWS = 24/7 channels of one series (TMDB matched the show). */
enum class ChannelKindFilter(val label: String) { ALL("All"), ANIME("Anime"), CARTOONS("Cartoons"), SHOWS("24/7 shows") }

/** Live channels ordering: provider order, airing-now first, or by name. */
enum class ChannelSort(val label: String) { FEATURED("Featured"), ON_NOW("On now"), AZ("A–Z") }

/** The provider/service group a channel name carries ("Amazon US: Naruto" → "Amazon US"); "Other" when none. */
internal fun channelService(name: String): String =
    Regex("""^\s*([^:|]{1,24})[:|]""").find(name)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() } ?: "Other"

/** One live channel tile on the hub, with its group, TMDB art and what is airing now. */
data class HubChannel(
    val row: ChannelRow,
    val kind: AnimeChannelKind,
    val art: com.yodesla.omniverse.core.data.metadata.ChannelArt? = null,
    val nowTitle: String? = null,
    val nowProgress: Float? = null,
)

internal fun channelGrid(channels: List<HubChannel>, kind: ChannelKindFilter, service: String?, sort: ChannelSort): List<HubChannel> {
    val picked = channels.filter { c ->
        when (kind) {
            ChannelKindFilter.ALL -> true
            ChannelKindFilter.ANIME -> c.kind == AnimeChannelKind.ANIME
            ChannelKindFilter.CARTOONS -> c.kind == AnimeChannelKind.CARTOON
            ChannelKindFilter.SHOWS -> c.art?.show == true
        } && (service == null || channelService(c.row.name) == service)
    }
    return when (sort) {
        ChannelSort.FEATURED -> picked
        ChannelSort.ON_NOW -> picked.sortedBy { it.nowTitle == null }
        ChannelSort.AZ -> picked.sortedBy { channelNameKey(it.row.name) }
    }
}

// ---------------------------------------------------------------- Anime-only search (Task 143)

/**
 * Kory: "a search within the anime section that only searches the anime section". The hub already holds
 * every anime / animated title and channel in memory, so its search is instant and can never return
 * anything that is not on the Anime page. Every query word must appear in the name; results rank exact,
 * starts-with, word-start, then mid-word, with the better-rated title first inside a tier.
 */
// The compact form also lets "yugioh" find "Yu-Gi-Oh!" and "dragonball" find "Dragon Ball".
private fun matches(normalizedName: String, parts: List<String>) = parts.isNotEmpty() &&
    (parts.all { normalizedName.contains(it) } || normalizedName.replace(" ", "").contains(parts.joinToString("")))

internal fun searchHubTitles(titles: List<HubTitle>, query: String, limit: Int = 80): List<PosterRow> {
    val needle = com.yodesla.omniverse.core.data.search.FastTitleSearch.normalize(query)
    val parts = needle.split(' ').filter { it.isNotEmpty() }
    if (parts.isEmpty()) return emptyList()
    return titles.asSequence()
        .filter { matches(com.yodesla.omniverse.core.data.search.FastTitleSearch.normalize(it.row.name), parts) }
        .sortedWith(compareBy<HubTitle>({ com.yodesla.omniverse.core.data.search.FastTitleSearch.tier(it.row.name, needle) },
            { (it.row.rating ?: 0f) <= 0f }, { -(it.row.rating ?: 0f) }, { it.row.name.length }, { it.row.name.lowercase() }))
        .map { it.row }.take(limit).toList()
}

/** Channels match on their name, their cleaned core name, or the programme airing now ("what is on"). */
internal fun searchHubChannels(channels: List<HubChannel>, query: String, limit: Int = 80): List<HubChannel> {
    val needle = com.yodesla.omniverse.core.data.search.FastTitleSearch.normalize(query)
    val parts = needle.split(' ').filter { it.isNotEmpty() }
    if (parts.isEmpty()) return emptyList()
    fun nameTier(c: HubChannel): Int? {
        val name = com.yodesla.omniverse.core.data.search.FastTitleSearch.normalize(c.row.name)
        val core = com.yodesla.omniverse.core.data.search.FastTitleSearch.normalize(com.yodesla.omniverse.core.data.metadata.AnimeHubFeeds.channelCoreName(c.row.name))
        val now = com.yodesla.omniverse.core.data.search.FastTitleSearch.normalize(c.nowTitle.orEmpty())
        return when {
            matches(core, parts) -> com.yodesla.omniverse.core.data.search.FastTitleSearch.tier(com.yodesla.omniverse.core.data.metadata.AnimeHubFeeds.channelCoreName(c.row.name), needle)
            matches(name, parts) -> 3
            matches(now, parts) -> 4
            else -> null
        }
    }
    return channels.mapNotNull { c -> nameTier(c)?.let { c to it } }
        .sortedWith(compareBy({ it.second }, { it.first.row.name.length }, { it.first.row.name.lowercase() }))
        .map { it.first }.take(limit)
}
