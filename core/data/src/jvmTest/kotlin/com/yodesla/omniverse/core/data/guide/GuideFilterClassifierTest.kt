package com.yodesla.omniverse.core.data.guide

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Task 98: the channel rules behind the guide filter chips. */
class GuideFilterClassifierTest {
    private fun passes(filter: GuideFilter, name: String, category: String? = null, favorite: Boolean = false) =
        GuideFilterClassifier.matches(filter, name, category, favorite)

    @Test
    fun allPassesEveryChannelAndFavoritesOnlyItsOwn() {
        assertTrue(passes(GuideFilter.ALL, "History"))
        assertTrue(passes(GuideFilter.FAVORITES, "ESPN 2", favorite = true))
        assertFalse(passes(GuideFilter.FAVORITES, "ESPN 2"))
    }

    @Test
    fun sportsReusesTheSportsChannelClassifier() {
        assertTrue(passes(GuideFilter.SPORTS, "ESPN 2"))
        assertTrue(passes(GuideFilter.SPORTS, "Channel 5", "Sports HD"))
        assertFalse(passes(GuideFilter.SPORTS, "CNN"))
        assertFalse(passes(GuideFilter.SPORTS, "Cartoon Network", "Kids"))
    }

    @Test
    fun newsMatchesNewsChannelsAndCategoriesOnly() {
        assertTrue(passes(GuideFilter.NEWS, "CNN"))
        assertTrue(passes(GuideFilter.NEWS, "BBC News"))
        assertTrue(passes(GuideFilter.NEWS, "ABC News Live"))
        assertTrue(passes(GuideFilter.NEWS, "Sky News"))
        assertTrue(passes(GuideFilter.NEWS, "France 24"))
        assertTrue(passes(GuideFilter.NEWS, "Channel 5", "News"))
        assertFalse(passes(GuideFilter.NEWS, "ESPN 2", "Sports"))
        assertFalse(passes(GuideFilter.NEWS, "Cartoon Network"))
        assertFalse(passes(GuideFilter.NEWS, "HBO", "Movies"))
    }

    @Test
    fun kidsMatchesKidsChannelsAndCategoriesOnly() {
        assertTrue(passes(GuideFilter.KIDS, "Cartoon Network"))
        assertTrue(passes(GuideFilter.KIDS, "Disney Junior"))
        assertTrue(passes(GuideFilter.KIDS, "Nickelodeon"))
        assertTrue(passes(GuideFilter.KIDS, "CBeebies"))
        assertTrue(passes(GuideFilter.KIDS, "PBS Kids"))
        assertTrue(passes(GuideFilter.KIDS, "Channel 5", "Kids"))
        assertFalse(passes(GuideFilter.KIDS, "Eurosport 1", "Sports"))
        assertFalse(passes(GuideFilter.KIDS, "HBO", "Movies"))
        assertFalse(passes(GuideFilter.KIDS, "CNN"))
    }

    @Test
    fun moviesMatchesMovieChannelsAndCategoriesOnly() {
        assertTrue(passes(GuideFilter.MOVIES, "HBO"))
        assertTrue(passes(GuideFilter.MOVIES, "TCM"))
        assertTrue(passes(GuideFilter.MOVIES, "Film4"))
        assertTrue(passes(GuideFilter.MOVIES, "Sony Movies"))
        assertTrue(passes(GuideFilter.MOVIES, "Cinemax"))
        assertTrue(passes(GuideFilter.MOVIES, "Channel 5", "Movies"))
        assertFalse(passes(GuideFilter.MOVIES, "History"))
        assertFalse(passes(GuideFilter.MOVIES, "ESPN 2"))
        assertFalse(passes(GuideFilter.MOVIES, "Cartoon Network"))
    }

    @Test
    fun storedNamesRoundTripAndUnknownReadsAsAll() {
        GuideFilter.entries.forEach { assertEquals(it, GuideFilter.fromName(it.name)) }
        assertEquals(GuideFilter.ALL, GuideFilter.fromName(null))
        assertEquals(GuideFilter.ALL, GuideFilter.fromName("bogus"))
    }
}
