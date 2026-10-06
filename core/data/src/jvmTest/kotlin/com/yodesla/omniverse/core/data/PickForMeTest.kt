package com.yodesla.omniverse.core.data

import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.SourceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Task 96: "Surprise me" weighting, exclusion and visibility rules. */
class PickForMeTest {
    private val src = SourceId("s1")

    private fun vod(id: String) = ContentKey(src, ContentKind.VOD, RemoteId(id))
    private fun series(id: String) = ContentKey(src, ContentKind.SERIES, RemoteId(id))

    private fun poster(
        key: ContentKey,
        name: String,
        genre: String? = null,
        rating: Float? = null,
        tmdbId: String? = null,
        categoryId: RemoteId? = null,
    ): PosterRow = PosterRow(key, name, null, 2019, rating, categoryId, tmdbId, genre = genre)

    private val visible: Visibility = { _, _, _ -> true }

    @Test
    fun genresSplitsProviderSeparatorsAndNormalizesCase() {
        val p = poster(vod("a"), "X", genre = " Action , sci-fi|COMEDY ")
        assertEquals(listOf("action", "sci-fi", "comedy"), PickForMe.genres(p))
    }

    @Test
    fun genreWeightsNormalizesByTheMostWatchedGenre() {
        val watched = listOf(
            poster(vod("w1"), "W1", genre = "Action"),
            poster(vod("w2"), "W2", genre = "Action|Thriller"),
            poster(vod("w3"), "W3", genre = "Action"),
            poster(vod("w4"), "W4", genre = "Comedy"),
        )
        val weights = PickForMe.genreWeights(watched)
        assertEquals(1f, weights.getValue("action"))
        assertEquals(0.33333334f, weights.getValue("comedy"), absoluteTolerance = 1e-6f)
        assertEquals(0.33333334f, weights.getValue("thriller"), absoluteTolerance = 1e-6f)
    }

    @Test
    fun pickFollowsTheMostWatchedGenreEvenAgainstAHigherRatedOtherGenre() {
        val watched = listOf(
            poster(vod("w1"), "W1", genre = "Action"),
            poster(vod("w2"), "W2", genre = "Action"),
            poster(vod("w3"), "W3", genre = "Action"),
            poster(vod("w4"), "W4", genre = "Comedy"),
        )
        val candidates = listOf(
            poster(vod("c1"), "Action Caper", genre = "Action", rating = 6.0f),
            poster(vod("c2"), "Comedy Star", genre = "Comedy", rating = 9.4f),
        )
        assertEquals("Action Caper", PickForMe.pick(candidates, watched, visible)?.name)
    }

    @Test
    fun pickPrefersWellRatedAmongEqualGenreMatches() {
        val watched = listOf(poster(vod("w1"), "W1", genre = "Action"))
        val candidates = listOf(
            poster(vod("c1"), "Mediocre Match", genre = "Action", rating = 6.0f),
            poster(vod("c2"), "Great Match", genre = "Action", rating = 7.8f),
        )
        assertEquals("Great Match", PickForMe.pick(candidates, watched, visible)?.name)
    }

    @Test
    fun pickStillReturnsTheBestMatchWhenNothingIsWellRated() {
        val watched = listOf(poster(vod("w1"), "W1", genre = "Action"))
        val candidates = listOf(
            poster(vod("c1"), "So-so Match", genre = "Action", rating = 5.0f),
            poster(vod("c2"), "Weak Other", genre = "Comedy", rating = 6.4f),
        )
        assertEquals("So-so Match", PickForMe.pick(candidates, watched, visible)?.name)
    }

    @Test
    fun pickExcludesWatchedTitlesAndEveryCopyOfThem() {
        val watched = listOf(poster(vod("w1"), "Watched (plex)", genre = "Action", tmdbId = "tt1"))
        val candidates = listOf(
            poster(vod("c1"), "Watched (iptv)", genre = "Action", tmdbId = "tt1"),
            poster(vod("c2"), "Fresh Pick", genre = "Action", tmdbId = "tt2"),
        )
        assertEquals("Fresh Pick", PickForMe.pick(candidates, watched, visible)?.name)
    }

    @Test
    fun pickExcludesTheExactWatchedKeyEvenWithoutTmdb() {
        val watched = listOf(poster(vod("c1"), "Watched", genre = "Action"))
        val candidates = listOf(
            poster(vod("c1"), "Watched", genre = "Action"),
            poster(vod("c2"), "Fresh Pick", genre = "Action"),
        )
        assertEquals("Fresh Pick", PickForMe.pick(candidates, watched, visible)?.name)
    }

    @Test
    fun pickNeverOffersHiddenCategories() {
        val watched = listOf(poster(vod("w1"), "W1", genre = "Action"))
        val candidates = listOf(
            poster(vod("c1"), "Locked Action", genre = "Action", rating = 9f, categoryId = RemoteId("adult")),
            poster(vod("c2"), "Open Action", genre = "Action", rating = 7f, categoryId = RemoteId("kids")),
        )
        val onlyKids: Visibility = { _, _, category -> category == "kids" }
        assertEquals("Open Action", PickForMe.pick(candidates, watched, onlyKids)?.name)
    }

    @Test
    fun pickSkipsTitlesAlreadyOfferedThisSession() {
        val watched = listOf(poster(vod("w1"), "W1", genre = "Action"))
        val candidates = listOf(
            poster(vod("c1"), "First Pick", genre = "Action", rating = 9f),
            poster(vod("c2"), "Second Pick", genre = "Action", rating = 8f),
        )
        val first = PickForMe.pick(candidates, watched, visible)
        assertEquals("First Pick", first?.name)
        val second = PickForMe.pick(candidates, watched, visible, alreadyPicked = setOf(PickForMe.titleGroup(first!!)))
        assertEquals("Second Pick", second?.name)
    }

    @Test
    fun pickReturnsNullWhenEverythingIsWatchedOrHidden() {
        val watched = listOf(poster(vod("c1"), "Watched", genre = "Action", tmdbId = "tt1"))
        val candidates = listOf(
            poster(vod("c1"), "Watched", genre = "Action", tmdbId = "tt1"),
            poster(vod("c2"), "Hidden", genre = "Action", categoryId = RemoteId("adult")),
        )
        val noAdult: Visibility = { _, _, category -> category != "adult" }
        assertNull(PickForMe.pick(candidates, watched, noAdult))
    }

    @Test
    fun pickGroupsMoviesAndShowsSeparatelyForTheSameTmdbId() {
        val watched = listOf(poster(series("w1"), "Shared Title", genre = "Drama", tmdbId = "tt9"))
        val candidates = listOf(poster(vod("c1"), "Shared Title", genre = "Drama", rating = 8f, tmdbId = "tt9"))
        assertTrue(PickForMe.pick(candidates, watched, visible)?.name == "Shared Title")
    }
}
