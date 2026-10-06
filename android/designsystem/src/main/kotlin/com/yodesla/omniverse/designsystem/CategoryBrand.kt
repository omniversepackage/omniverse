package com.yodesla.omniverse.designsystem

enum class CategoryBrand { NETFLIX, DISNEY_PLUS, PRIME_VIDEO, APPLE_TV_PLUS, HULU, MAX, PARAMOUNT_PLUS, PEACOCK, CRUNCHYROLL }

private val tokenPattern = Regex("""[a-z0-9]+|\+""")

/**
 * Maps a provider category name to its streaming brand, or null when no distinct brand
 * token is present. Case-insensitive; matches whole tokens only, so "Netflixian",
 * "Maximum", "Huluween", "Disney Junior", "Prime Sports", "Apple News" and "Paramount Network"
 * all return null.
 */
fun categoryBrand(name: String): CategoryBrand? {
    val tokens = tokenPattern.findAll(name.lowercase()).map { it.value }.toList()
    fun hasSequence(vararg seq: String): Boolean = tokens.indices.any { i ->
        seq.withIndex().all { (j, s) -> i + j < tokens.size && tokens[i + j] == s }
    }
    return when {
        hasSequence("netflix") -> CategoryBrand.NETFLIX
        hasSequence("disney", "+") -> CategoryBrand.DISNEY_PLUS
        hasSequence("prime", "video") -> CategoryBrand.PRIME_VIDEO
        hasSequence("apple", "tv", "+") -> CategoryBrand.APPLE_TV_PLUS
        hasSequence("hulu") -> CategoryBrand.HULU
        hasSequence("max") -> CategoryBrand.MAX
        // Providers often label the service plain "Paramount"; the studio/cable brands stay unbranded.
        "paramountplus" in tokens || (hasSequence("paramount") &&
            tokens.none { it in setOf("network", "pictures", "channel", "comedy", "studios") }) -> CategoryBrand.PARAMOUNT_PLUS
        hasSequence("peacock") -> CategoryBrand.PEACOCK
        hasSequence("crunchyroll") -> CategoryBrand.CRUNCHYROLL
        else -> null
    }
}
