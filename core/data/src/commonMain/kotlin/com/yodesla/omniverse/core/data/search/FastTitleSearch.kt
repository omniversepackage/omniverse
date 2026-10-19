package com.yodesla.omniverse.core.data.search

import com.yodesla.omniverse.core.data.PosterRow

/** One FTS / substring hit before ranking and de-duplication. */
data class TitleHit(
    val row: PosterRow,
    val groupKey: String?,
    val plexRank: Int,
    val sourceSort: Long,
    val itemSort: Long,
    /** True when the hit came only from the mid-word substring pass. */
    val substringOnly: Boolean = false,
)

/**
 * Task 143: the pure ranking behind plain-text title search. Tiers (lower is better): exact title, title
 * starts with the query, every query word starts a title word, query inside a word. Inside a tier the
 * better-rated and shorter title wins. Copies sharing a group_key collapse Plex-first (then source order,
 * then provider order), exactly like browse.
 */
object FastTitleSearch {
    private val nonWord = Regex("[^\\p{L}\\p{N}]+")

    fun normalize(s: String): String = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .split(nonWord).filter { it.isNotEmpty() }.joinToString(" ")

    /** No spaces, punctuation or accents: "Yu-Gi-Oh!" → "yugioh", "Pokémon" → "pokemon", "&" → "and". */
    fun compact(s: String): String = normalize(s.replace("&", " and ")).replace(" ", "")

    /** 0 exact, 1 starts with, 2 all words start a title word, 3 anything else that matched. */
    fun tier(title: String, needle: String): Int {
        val t = normalize(title)
        if (t == needle) return 0
        if (t.startsWith(needle)) return 1
        val ct = compact(title); val cn = needle.replace(" ", "")
        if (cn.isNotEmpty() && ct == cn) return 0
        if (cn.isNotEmpty() && ct.startsWith(cn)) return 1
        val words = t.split(' ')
        val parts = needle.split(' ').filter { it.isNotEmpty() }
        if (parts.isNotEmpty() && parts.all { p -> words.any { it.startsWith(p) } }) return 2
        return 3
    }

    fun rank(hits: List<TitleHit>, query: String, excludedKeys: Set<String>, kindName: String, limit: Int): Pair<List<PosterRow>, Boolean> {
        val needle = normalize(query)
        val visible = hits.filter { h -> h.row.categoryId?.let { "$kindName|${h.row.key.sourceId.value}|${it.value}" !in excludedKeys } ?: true }
        val winners = visible.groupBy { it.groupKey ?: "u:${it.row.key.sourceId.value}:${it.row.key.remoteId.value}" }
            .values.map { copies -> copies.minWith(compareBy({ it.plexRank }, { it.sourceSort }, { it.itemSort })) }
        val ordered = winners.sortedWith(
            compareBy<TitleHit>({ tier(it.row.name, needle) }, { if (it.substringOnly) 1 else 0 },
                { (it.row.rating ?: 0f) <= 0f }, { -(it.row.rating ?: 0f) }, { it.row.name.length }, { it.row.name.lowercase() }),
        )
        return ordered.take(limit).map { it.row } to (ordered.size > limit)
    }
}
