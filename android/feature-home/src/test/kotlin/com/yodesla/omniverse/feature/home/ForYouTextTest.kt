package com.yodesla.omniverse.feature.home

import com.yodesla.omniverse.core.data.PosterRow
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.SourceId
import java.util.Calendar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** For You's words: the hero's metadata line, headline ages, feed dates and the time-of-day greeting. */
class ForYouTextTest {
    private fun poster(kind: ContentKind, year: Int? = null, rating: Float? = null, genre: String? = null, cert: String? = null, runtime: Int? = null) =
        PosterRow(ContentKey(SourceId("s"), kind, RemoteId("1")), "Title", null, year, rating, genre = genre, certification = cert, runtimeMin = runtime)

    @Test fun metaLineShowsOnlyWhatTheCatalogKnows() {
        assertEquals("Movie  ·  2003  ·  Drama  ·  R  ·  2h 34m  ·  7.7/10",
            titleMeta(poster(ContentKind.VOD, 2003, 7.7f, "Drama, War", "R", 154)))
        assertEquals("Series  ·  2024", titleMeta(poster(ContentKind.SERIES, 2024, runtime = 55)))
        assertEquals("Movie  ·  48 min", titleMeta(poster(ContentKind.VOD, runtime = 48, rating = 0f)))
    }

    @Test fun headlineAgesReadNaturally() {
        val now = 1_790_000_000_000L
        assertNull(ForYouNews.agoLabel(null, now))
        assertEquals("Just now", ForYouNews.agoLabel(now - 30_000, now))
        assertEquals("25 min ago", ForYouNews.agoLabel(now - 25 * 60_000, now))
        assertEquals("3h ago", ForYouNews.agoLabel(now - 3 * 3_600_000, now))
        assertEquals("Yesterday", ForYouNews.agoLabel(now - 30 * 3_600_000L, now))
        assertEquals("4 days ago", ForYouNews.agoLabel(now - 4 * 86_400_000L, now))
    }

    @Test fun feedDatesParseAndJunkDoesNot() {
        assertEquals(1_793_361_600_000L, ForYouNews.publishedMs("Fri, 30 Oct 2026 12:00:00 GMT"))
        assertNull(ForYouNews.publishedMs("yesterday-ish"))
    }

    @Test fun greetingFollowsTheLocalHour() {
        fun at(hour: Int) = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 28, hour, 15) }.timeInMillis
        assertEquals("Good morning", greeting(at(8)))
        assertEquals("Good afternoon", greeting(at(13)))
        assertEquals("Good evening", greeting(at(19)))
        assertEquals("Up late", greeting(at(2)))
        assertEquals("Tonight's pick", pickLabel(at(20)))
        assertEquals("Today's pick", pickLabel(at(10)))
    }
}
