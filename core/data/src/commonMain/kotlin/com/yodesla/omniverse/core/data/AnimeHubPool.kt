package com.yodesla.omniverse.core.data

/** Task 142: one Anime-hub title — [anime] true for anime, false for other animation/cartoons. */
data class AnimatedTitle(val row: PosterRow, val anime: Boolean)

/** One candidate row from the hub's cheap LIKE scan, before classification and dedup. */
data class AnimatedCandidate(
    val row: PosterRow,
    val categoryName: String?,
    val genre: String?,
    val groupKey: String?,
    val plex: Boolean,
    val sourceSort: Long,
    val itemSort: Long,
)

/**
 * Task 142: the Anime hub's classification — the same whole-word tokens as the Search "Anime &
 * Animation" chip (task 130), over the category name and provider genre. Anime tokens win; plain
 * animation/cartoon words make a title "other animation"; anything else (an "Animal" category the
 * LIKE scan let through) is dropped. Excluded categories never appear, and copies sharing a
 * group_key collapse Plex-first, then source order, then provider order — the browse rule.
 */
object AnimeHubPool {
    private val AnimationTokens = setOf("animation", "animated", "cartoon", "cartoons")

    fun classify(categoryName: String?, genre: String?): Boolean? {
        val words = AnimeRules.tokens(categoryName) + AnimeRules.tokens(genre)
        return when {
            words.any { it in AnimeRules.AnimeTokens } -> true
            words.any { it in AnimationTokens } -> false
            else -> null
        }
    }

    fun build(candidates: List<AnimatedCandidate>, excludedKeys: Set<String>): List<AnimatedTitle> =
        candidates.asSequence()
            .filter { c -> c.row.categoryId?.let { "${c.row.key.kind.name}|${c.row.key.sourceId.value}|${it.value}" !in excludedKeys } ?: true }
            .mapNotNull { c -> classify(c.categoryName, c.genre)?.let { c to it } }
            .groupBy { (c, _) -> c.groupKey ?: "u:${c.row.key.sourceId.value}:${c.row.key.remoteId.value}" }
            .values
            .map { copies -> copies.minWith(compareBy({ if (it.first.plex) 0 else 1 }, { it.first.sourceSort }, { it.first.itemSort })) }
            .map { (c, anime) -> AnimatedTitle(c.row.copy(genre = c.genre), anime) }
            .sortedWith(compareBy<AnimatedTitle> { it.row.rating == null }.thenByDescending { it.row.rating ?: 0f }.thenBy { it.row.name.lowercase() })
}
