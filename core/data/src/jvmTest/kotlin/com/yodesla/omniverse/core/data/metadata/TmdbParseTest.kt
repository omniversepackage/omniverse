package com.yodesla.omniverse.core.data.metadata

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

class TmdbParseTest {
    private fun obj(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

    private val movieJson = obj(
        """{"id":550,"backdrop_path":"/fallback.jpg","poster_path":"/p.jpg","runtime":139,
           "tagline":"Trash talk","overview":"A fight-club synopsis.","genres":[{"name":"Drama"},{"name":"Crime"}],
           "images":{"backdrops":[
             {"iso_639_1":null,"file_path":"/textless.jpg","vote_average":5.0,"aspect_ratio":1.777,"width":1920},
             {"iso_639_1":"en","file_path":"/english.jpg","vote_average":7.0,"aspect_ratio":1.777,"width":1920},
             {"iso_639_1":null,"file_path":"/tall.jpg","vote_average":9.0,"aspect_ratio":1.0,"width":1000}],
             "logos":[
             {"iso_639_1":"en","file_path":"/logo.svg","vote_average":6.0,"width":800},
             {"iso_639_1":"en","file_path":"/logo.png","vote_average":3.0,"width":600},
             {"iso_639_1":"de","file_path":"/de.png","vote_average":8.0,"width":700}],
             "posters":[{"iso_639_1":"en","file_path":"/poster.jpg","vote_average":4.0,"width":500}]},
           "release_dates":{"results":[
             {"iso_3166_1":"GB","type":3,"certification":"15"},
             {"iso_3166_1":"US","type":4,"certification":"R"},
             {"iso_3166_1":"US","type":3,"certification":"PG-13"}]}}""",
    )

    private fun images(key: String): JsonArray = ((movieJson["images"] as JsonObject)[key] as JsonArray)

    @Test fun textlessBackdropWinsEvenWithLowerVotes() {
        assertEquals("/textless.jpg", Tmdb.pickBackdrop(images("backdrops")))
    }

    // Task 87b: cards get a second backdrop, never the banner's own.
    @Test fun cardBackdropIsNeverTheBannerAndTakesTheEnglishTitleTreatment() {
        val banner = Tmdb.pickBackdrop(images("backdrops"))
        assertEquals("/textless.jpg", banner)
        assertEquals("/english.jpg", Tmdb.pickCardBackdrop(images("backdrops"), banner))
    }

    @Test fun cardBackdropIsAbsentWhenTheTitleHasNoOtherWideBackdrop() {
        val only = Json.parseToJsonElement(
            """[{"iso_639_1":null,"file_path":"/textless.jpg","vote_average":5.0,"aspect_ratio":1.777,"width":1920},
               {"iso_639_1":null,"file_path":"/tall.jpg","vote_average":9.0,"aspect_ratio":1.0,"width":1000}]""",
        ).jsonArray
        assertEquals("/textless.jpg", Tmdb.pickBackdrop(only))
        assertNull(Tmdb.pickCardBackdrop(only, "/textless.jpg"))
    }

    @Test fun cardBackdropFallsBackToTheNextBestTextlessImage() {
        val set = Json.parseToJsonElement(
            """[{"iso_639_1":null,"file_path":"/a.jpg","vote_average":7.0,"aspect_ratio":1.777,"width":1920},
               {"iso_639_1":null,"file_path":"/b.jpg","vote_average":5.0,"aspect_ratio":1.777,"width":1920}]""",
        ).jsonArray
        assertEquals("/b.jpg", Tmdb.pickCardBackdrop(set, "/a.jpg"))
    }

    @Test fun metaStoresBannerAndCardArtAsDifferentPaths() {
        val m = Tmdb.parseMeta(movieJson, Tmdb.usCertification(movieJson["release_dates"] as JsonObject))
        assertEquals("/textless.jpg", m.backdropPath)
        assertEquals("/english.jpg", m.cardBackdropPath)
    }

    @Test fun englishLogoOnlyAndPngPreferredOverBetterRatedSvg() {
        assertEquals("/logo.png", Tmdb.pickLogo(images("logos")))
    }

    @Test fun usCertificationPrefersTheatricalType3() {
        assertEquals("PG-13", Tmdb.usCertification(movieJson["release_dates"] as JsonObject))
    }

    @Test fun metaPicksArtRuntimeGenresTaglineAndOverview() {
        val m = Tmdb.parseMeta(movieJson, Tmdb.usCertification(movieJson["release_dates"] as JsonObject))
        assertEquals("/textless.jpg", m.backdropPath)
        assertEquals("/logo.png", m.logoPath)
        assertEquals("/poster.jpg", m.posterPath)
        assertEquals("PG-13", m.certification)
        assertEquals(139, m.runtimeMin)
        assertEquals("Drama, Crime", m.genres)
        assertEquals("Trash talk", m.tagline)
        assertEquals("A fight-club synopsis.", m.overview)
    }

    /** Task 84f: TMDB sends "" instead of nothing for a title with no synopsis; that is not a plot. */
    @Test fun blankAndMissingOverviewParseToNull() {
        val blank = obj("""{"id":1,"overview":"   "}""")
        assertNull(Tmdb.parseMeta(blank, null).overview)
        assertNull(Tmdb.parseMeta(blank, null).tagline)
        val missing = obj("""{"id":1,"tagline":"only a tagline"}""")
        assertNull(Tmdb.parseMeta(missing, null).overview)
        assertEquals("only a tagline", Tmdb.parseMeta(missing, null).tagline)
    }

    @Test fun tvCertificationTakesMostRestrictiveUsRating() {
        val ratings = obj("""{"results":[
            {"iso_3166_1":"US","certification":"TV-14"},{"iso_3166_1":"US","certification":"TV-MA"},
            {"iso_3166_1":"GB","certification":"18"}]}""")
        assertEquals("TV-MA", Tmdb.usContentCertification(ratings))
    }

    @Test fun seasonStillsParse() {
        val season = obj("""{"episodes":[
            {"episode_number":1,"name":"Pilot","overview":"p","still_path":"/s1.jpg"},
            {"episode_number":2,"name":"Caveat","still_path":null}]}""")
        val eps = Tmdb.parseSeason(season)
        assertEquals(2, eps.size)
        assertEquals(1, eps[0].episode)
        assertEquals("/s1.jpg", eps[0].stillPath)
        assertEquals("Caveat", eps[1].name)
        assertNull(eps[1].stillPath)
    }

    @Test fun imageUrlsUseTaskSizes() {
        assertEquals("https://image.tmdb.org/t/p/w1280/b.jpg", Tmdb.backdropUrl("/b.jpg"))
        assertEquals("https://image.tmdb.org/t/p/w500/l.png", Tmdb.logoUrl("/l.png"))
        assertEquals("https://image.tmdb.org/t/p/w342/p.jpg", Tmdb.posterUrl("/p.jpg"))
        assertEquals("https://image.tmdb.org/t/p/w300/s.jpg", Tmdb.stillUrl("/s.jpg"))
        assertNull(Tmdb.backdropUrl(null))
        assertNull(Tmdb.backdropUrl(""))
    }
}
