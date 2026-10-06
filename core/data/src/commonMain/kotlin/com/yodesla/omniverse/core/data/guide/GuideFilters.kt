package com.yodesla.omniverse.core.data.guide

import com.yodesla.omniverse.core.data.sports.SportsClassifier

/** Header filter chips of the TV guide (task 98); the stored form is [name]. */
enum class GuideFilter(val label: String) {
    ALL("All"),
    FAVORITES("Favorites"),
    SPORTS("Sports"),
    NEWS("News"),
    KIDS("Kids"),
    MOVIES("Movies"),
    ;

    companion object {
        /** Anything unknown (or absent) reads back as [ALL]. */
        fun fromName(value: String?): GuideFilter = entries.firstOrNull { it.name == value } ?: ALL
    }
}

/**
 * Channel-level rules behind the guide filter chips. Sports reuses [SportsClassifier]'s channel
 * test; News/Kids/Movies are deliberately narrow keyword lists — a channel only passes when its
 * own name or the name of its category says what it carries. Never fetches anything.
 */
object GuideFilterClassifier {
    private val newsWords = Regex(
        """\b(news|cnn|msnbc|cbs news|nbc news|fox news|bbc news|sky news|france ?24|al jazeera|euronews|""" +
            """headlines?|bulletin|journal|press ?tv|current affairs)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val kidsWords = Regex(
        """\b(kids?|children|cartoons?|disney|nickelodeon|nick ?(jr|toons)|pbs ?kids|cbeebies|junior|toddlers?|""" +
            """baby|nursery|toons|boomerang|jim henson|kindergarten|preschool|youth)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val movieWords = Regex(
        """\b(movies?|cinema|cinemax|films?|film ?4|hbo|tcm)\b""",
        RegexOption.IGNORE_CASE,
    )

    fun isNewsChannel(name: String, categoryName: String?): Boolean = hits(newsWords, name, categoryName)

    fun isKidsChannel(name: String, categoryName: String?): Boolean = hits(kidsWords, name, categoryName)

    fun isMoviesChannel(name: String, categoryName: String?): Boolean = hits(movieWords, name, categoryName)

    /** Whether a channel row passes [filter]. [favorite] = the row's channel is a favourite. */
    fun matches(filter: GuideFilter, channelName: String, categoryName: String?, favorite: Boolean): Boolean = when (filter) {
        GuideFilter.ALL -> true
        GuideFilter.FAVORITES -> favorite
        GuideFilter.SPORTS -> SportsClassifier.isSportsChannel(channelName, categoryName)
        GuideFilter.NEWS -> isNewsChannel(channelName, categoryName)
        GuideFilter.KIDS -> isKidsChannel(channelName, categoryName)
        GuideFilter.MOVIES -> isMoviesChannel(channelName, categoryName)
    }

    private fun hits(words: Regex, name: String, categoryName: String?): Boolean =
        words.containsMatchIn(name) || (categoryName != null && words.containsMatchIn(categoryName))
}
