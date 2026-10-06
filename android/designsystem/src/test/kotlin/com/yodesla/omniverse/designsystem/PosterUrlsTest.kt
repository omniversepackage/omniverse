package com.yodesla.omniverse.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PosterUrlsTest {
    @Test
    fun originalRewrittenForCardWidths() {
        assertEquals(
            "https://image.tmdb.org/t/p/w342/abc123.jpg",
            tmdbPosterUrlForCard("https://image.tmdb.org/t/p/original/abc123.jpg", 124),
        )
        assertEquals(
            "https://image.tmdb.org/t/p/w342/abc123.jpg",
            tmdbPosterUrlForCard("https://image.tmdb.org/t/p/original/abc123.jpg", 220),
        )
    }

    @Test
    fun bestv2RewrittenForCardWidths() {
        assertEquals(
            "https://image.tmdb.org/t/p/w342/abc123.jpg",
            tmdbPosterUrlForCard("https://image.tmdb.org/t/p/w600_and_h900_bestv2/abc123.jpg", 105),
        )
    }

    @Test
    fun heroWidthsKeepTheLargeVariant() {
        val url = "https://image.tmdb.org/t/p/original/abc123.jpg"
        assertEquals(url, tmdbPosterUrlForCard(url, 221))
        assertEquals(url, tmdbPosterUrlForCard(url, 640))
    }

    @Test
    fun alreadySmallVariantsUntouched() {
        assertEquals(
            "https://image.tmdb.org/t/p/w185/abc123.jpg",
            tmdbPosterUrlForCard("https://image.tmdb.org/t/p/w185/abc123.jpg", 124),
        )
        assertEquals(
            "https://image.tmdb.org/t/p/w342/abc123.jpg",
            tmdbPosterUrlForCard("https://image.tmdb.org/t/p/w342/abc123.jpg", 124),
        )
    }

    @Test
    fun nonTmdbHostsUntouched() {
        val url = "https://provider.example.com/t/p/original/abc123.jpg"
        assertEquals(url, tmdbPosterUrlForCard(url, 124))
        val plex = "https://metadata.provider.tv/art/original/abc.jpg"
        assertEquals(plex, tmdbPosterUrlForCard(plex, 124))
    }

    @Test
    fun schemeAndQueryPreserved() {
        assertEquals(
            "http://image.tmdb.org/t/p/w342/abc123.jpg?token=7",
            tmdbPosterUrlForCard("http://image.tmdb.org/t/p/original/abc123.jpg?token=7", 124),
        )
        assertEquals(
            "https://image.themoviedb.org/t/p/w342/abc123.jpg",
            tmdbPosterUrlForCard("https://image.themoviedb.org/t/p/original/abc123.jpg", 124),
        )
    }

    @Test
    fun nullAndBlankPassThrough() {
        assertNull(tmdbPosterUrlForCard(null, 124))
        assertEquals("", tmdbPosterUrlForCard("", 124))
    }

    @Test
    fun malformedUrlsPassThrough() {
        val odd = "image.tmdb.org/t/p/original/abc.jpg"
        assertEquals(odd, tmdbPosterUrlForCard(odd, 124))
        val noSize = "https://image.tmdb.org/t/p/abc.jpg"
        assertEquals(noSize, tmdbPosterUrlForCard(noSize, 124))
    }
}
