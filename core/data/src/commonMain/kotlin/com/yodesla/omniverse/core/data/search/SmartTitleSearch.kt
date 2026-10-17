package com.yodesla.omniverse.core.data.search

/** Local title knowledge. Expansions retrieve candidates; literal matches always rank first. */
object SmartTitleSearch {
    private val families = listOf(
        listOf("batman", "dark knight", "batman begins"),
        listOf("superman", "man of steel"),
        listOf("harry potter", "fantastic beasts", "wizarding world"),
        listOf("lord of the rings", "hobbit", "middle earth"),
        listOf("star wars", "mandalorian", "andor", "ahsoka", "book of boba fett", "obi wan kenobi"),
        listOf("avengers", "iron man", "captain america", "thor", "hulk", "black widow", "hawkeye"),
        listOf("spider man", "spiderman", "spider verse"),
        listOf("x men", "xmen", "wolverine", "logan", "deadpool"),
        listOf("alien", "aliens", "prometheus", "alien covenant"),
        listOf("rocky", "creed"),
        listOf("james bond", "007", "casino royale", "skyfall", "spectre", "no time to die", "quantum of solace", "goldfinger"),
        listOf("fast and furious", "fast furious", "2 fast 2 furious", "fast five", "furious 7", "fate of the furious", "hobbs shaw", "fast x"),
        listOf("breaking bad", "better call saul", "el camino"),
        listOf("game of thrones", "house of the dragon"),
        listOf("walking dead", "daryl dixon", "dead city", "the ones who live"),
        listOf("visionquest", "vision quest", "wandavision"),
        listOf("attack on titan", "shingeki no kyojin"),
        listOf("demon slayer", "kimetsu no yaiba"),
    )
    fun normalize(value: String): String = value.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    fun expansions(query: String): List<String> {
        val q = normalize(query)
        if (q.length < 3) return emptyList()
        // A franchise name or its alias, not an arbitrary shared word, activates a relationship.
        return families.filter { family -> family.any { q == it || q.replace(" ", "") == it.replace(" ", "") } }
            .flatten().filter { it != q }.distinct().take(16)
    }
    fun rank(title: String, query: String): Int {
        val t = normalize(title); val q = normalize(query)
        return when {
            t == q -> 0
            t.startsWith(q) -> 1
            t.contains(" $q") -> 2
            t.contains(q) || t.replace(" ", "").contains(q.replace(" ", "")) -> 3
            else -> 4
        }
    }
}
