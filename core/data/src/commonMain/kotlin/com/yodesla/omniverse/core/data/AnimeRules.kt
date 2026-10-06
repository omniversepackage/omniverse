package com.yodesla.omniverse.core.data

/**
 * Task 92: the pure classifier behind the Crunchyroll "Anime library". Deterministic, no network:
 * a title is anime when its primary category name or its provider genre carries an anime token as
 * a whole word ("ANIME | SUB", "Animes", "Anime Dubbed", "Crunchyroll (dub)", genre "Anime").
 * Plain "Animation", "Kids" and "Cartoons" are NOT anime. Plex's rule — genre Animation AND
 * country/original language Japan or Japanese — only fires when the caller actually has that data
 * (the catalog schema carries none, so SQL never applies it).
 */
object AnimeRules {
    /** Whole-word tokens that mark a category or genre as anime, any provider language. */
    val AnimeTokens = setOf("anime", "animes", "crunchyroll", "funimation", "hidive", "manga")

    private val JapanTokens = setOf("japan", "japanese", "jp")

    /** Lowercase whole words; every separator (| ( ) [ ] - : / , \ quote, whitespace) splits. */
    fun tokens(text: String?): List<String> = text
        ?.lowercase()
        .orEmpty()
        .split(*Separators)
        .filter { it.isNotEmpty() }

    fun isAnime(categoryName: String?, genre: String?, countryOrLanguage: String? = null): Boolean {
        val seen = tokens(categoryName) + tokens(genre)
        if (seen.any { it in AnimeTokens }) return true
        return countryOrLanguage != null &&
            seen.any { it == "animation" } &&
            tokens(countryOrLanguage).any { it in JapanTokens }
    }

    private val Separators = charArrayOf(
        '|', '(', ')', '[', ']', '-', ':', '/', ',', '\\', '\'', '’', '"',
        ' ', '\t', '\n', '\r',
    )
}
