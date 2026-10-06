package com.yodesla.omniverse.feature.vod

import com.yodesla.omniverse.core.data.PosterRow
import com.yodesla.omniverse.core.data.Visibility
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.SourceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

private val src = SourceId("s1")
private fun vodKey(id: String) = ContentKey(src, ContentKind.VOD, RemoteId(id))
private fun seriesKey(id: String) = ContentKey(src, ContentKind.SERIES, RemoteId(id))
private fun poster(key: ContentKey, name: String, categoryId: RemoteId? = null): PosterRow =
    PosterRow(key, name, null, null, null, categoryId, null)

private val showAll: Visibility = { _, _, _ -> true }
private val noAdult: Visibility = { _, _, categoryId -> categoryId != "adult" }

class PlayNextOfferTest {
    @Test
    fun offersTheFirstQueuedTitleThatResolvesToAPoster() = runTest {
        val a = vodKey("a")
        val b = vodKey("b")
        val posters = mapOf(b to poster(b, "Second"))
        assertEquals(b to "Second", pickQueueOffer(listOf(a, b), emptySet(), posters::get, showAll))
    }

    @Test
    fun skipsTheFinishedMovieAndTheSeriesOfTheFinishedEpisode() = runTest {
        val movie = vodKey("m")
        val series = seriesKey("m")
        val next = vodKey("n")
        val posters = mapOf(
            movie to poster(movie, "Just watched"),
            series to poster(series, "Just watched"),
            next to poster(next, "Next up"),
        )
        val finished = setOf(movie, series)
        assertEquals(next to "Next up", pickQueueOffer(listOf(movie, series, next), finished, posters::get, showAll))
    }

    @Test
    fun dropsTitlesHiddenByTheProfileVisibility() = runTest {
        val adult = vodKey("adult")
        val kids = vodKey("kids")
        val posters = mapOf(
            adult to poster(adult, "Adult", RemoteId("adult")),
            kids to poster(kids, "Kids", RemoteId("kids")),
        )
        assertEquals(kids to "Kids", pickQueueOffer(listOf(adult, kids), emptySet(), posters::get, noAdult))
    }

    @Test
    fun noOfferWhenQueueEmptyOrEverythingHidden() = runTest {
        assertNull(pickQueueOffer(emptyList(), emptySet(), { null }, showAll))
        val adult = vodKey("adult")
        val posters = mapOf(adult to poster(adult, "Adult", RemoteId("adult")))
        assertNull(pickQueueOffer(listOf(adult), emptySet(), posters::get, noAdult))
    }

    @Test
    fun nullVisibilityTreatsEverythingAsVisible() = runTest {
        val k = vodKey("k")
        val posters = mapOf(k to poster(k, "Anything", RemoteId("adult")))
        assertEquals(k to "Anything", pickQueueOffer(listOf(k), emptySet(), posters::get, null))
    }
}
