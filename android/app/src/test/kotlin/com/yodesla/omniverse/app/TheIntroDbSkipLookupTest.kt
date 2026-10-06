package com.yodesla.omniverse.app

import com.yodesla.omniverse.core.net.HttpClient
import com.yodesla.omniverse.core.net.HttpResponse
import com.yodesla.omniverse.feature.vod.CommunitySkipQuery
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import okio.Buffer
import okio.BufferedSource

private class FakeHttpResponse(override val status: Int, json: String) : HttpResponse {
    override val headers: Map<String, String> = emptyMap()
    override val body: BufferedSource = Buffer().writeUtf8(json)
    var closed = false
    override fun close() { closed = true }
}

private class FakeHttpClient(private val response: FakeHttpResponse) : HttpClient {
    val urls = mutableListOf<String>()
    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
        urls += url
        return response
    }
}

private class RoutedHttpClient(private val responseFor: (String) -> FakeHttpResponse) : HttpClient {
    val urls = mutableListOf<String>()
    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
        urls += url
        return responseFor(url)
    }
}

class TheIntroDbSkipLookupTest {
    @Test fun iptvSeriesWithoutCatalogIdUsesUniqueExactTvmazeMatchThenImdbLookup() = runBlocking {
        val http = RoutedHttpClient { url ->
            if (url.contains("tvmaze.com")) FakeHttpResponse(200, """[
                {"show":{"name":"Attack on Titan","premiered":"2013-04-07","externals":{"imdb":"tt2560140"}}},
                {"show":{"name":"Attack on Titan: Smoke Signal of Fight Back","premiered":"2015-08-15","externals":{"imdb":"tt4573582"}}}
            ]""") else FakeHttpResponse(200, """{"tmdb_id":1429,"type":"tv","season":1,"episode":12,
                "intro":[{"start_ms":116606,"end_ms":208939}]}""")
        }
        val markers = TheIntroDbSkipLookup(http)
            .lookup(CommunitySkipQuery("", 1, 12, 1_440_000, "Attack on Titan (2013)", 2013))

        assertEquals(2, http.urls.size)
        assertEquals("https://api.tvmaze.com/search/shows?q=Attack+on+Titan", http.urls[0])
        assertEquals("https://api.theintrodb.org/v3/media?imdb_id=tt2560140&season=1&episode=12&duration_ms=1440000", http.urls[1])
        assertEquals(listOf("intro"), markers.map { it.type })
        assertEquals(208_939L, markers.single().endMs)
    }

    @Test fun titleFallbackRejectsAmbiguousOrWrongYearMatchesBeforeSkipRequest() = runBlocking {
        val http = RoutedHttpClient { FakeHttpResponse(200, """[
            {"show":{"name":"The Show","premiered":"2013-04-07","externals":{"imdb":"tt2560140"}}},
            {"show":{"name":"The Show","premiered":"2013-09-01","externals":{"imdb":"tt1234567"}}}
        ]""") }
        val lookup = TheIntroDbSkipLookup(http)

        assertTrue(lookup.lookup(CommunitySkipQuery("", 1, 12, null, "The Show", 2013)).isEmpty())
        assertTrue(lookup.lookup(CommunitySkipQuery("", 1, 12, null, "The Show", 2020)).isEmpty())
        assertEquals(2, http.urls.size)
        assertTrue(http.urls.all { it.contains("tvmaze.com") })
    }

    @Test fun attackOnTitanSeasonOneEpisodeTwelveHasUsableIntroAndCredits() = runBlocking {
        val response = FakeHttpResponse(200, """{"tmdb_id":1429,"type":"tv","season":1,"episode":12,
            "intro":[{"start_ms":116606,"end_ms":208939}],
            "recap":[{"start_ms":0,"end_ms":115000}],
            "credits":[{"start_ms":1339000,"end_ms":1432000}]}""")
        val markers = TheIntroDbSkipLookup(FakeHttpClient(response))
            .lookup(CommunitySkipQuery("1429", 1, 12, 1_440_000))

        assertEquals(listOf("intro", "credits"), markers.map { it.type })
        assertEquals(116_606L, markers.first().startMs)
        assertEquals(208_939L, markers.first().endMs)
        assertEquals(1_339_000L, markers.last().startMs)
        assertEquals(1_432_000L, markers.last().endMs)
    }

    @Test fun sendsOnlyValidatedPublicCatalogIdentifiersAndParsesTvSegments() = runBlocking {
        val response = FakeHttpResponse(200, """
            {"data":{"tmdb_id":1396,"type":"tv","season":1,"episode":1,
              "intro":[{"start_ms":null,"end_ms":90000}],
              "credits":[{"start_ms":3431000,"end_ms":null}],
              "recap":[{"start_ms":100,"end_ms":150000}]}}
        """.trimIndent())
        val http = FakeHttpClient(response)
        val result = TheIntroDbSkipLookup(http).lookup(CommunitySkipQuery("1396", 1, 1, 3_600_000))

        assertEquals("https://api.theintrodb.org/v3/media?tmdb_id=1396&season=1&episode=1&duration_ms=3600000", http.urls.single())
        assertEquals(listOf("intro" to 90_000L, "credits" to 3_600_000L), result.map { it.type to it.endMs })
        assertEquals(0L, result.first().startMs)
        assertTrue(response.closed)
    }

    @Test fun movieQueryOmitsEpisodeFieldsAndUsesRuntimeForNullCreditEnd() = runBlocking {
        val response = FakeHttpResponse(200, """{"tmdb_id":550,"type":"movie","credits":{"start_ms":6900000,"end_ms":null}}""")
        val http = FakeHttpClient(response)
        val result = TheIntroDbSkipLookup(http).lookup(CommunitySkipQuery("550", null, null, 7_200_000))

        assertEquals("https://api.theintrodb.org/v3/media?tmdb_id=550&duration_ms=7200000", http.urls.single())
        assertEquals(1, result.size)
        assertEquals(7_200_000L, result.single().endMs)
    }

    @Test fun rejectsPartialEpisodeIdentityAndMismatchedResponse() = runBlocking {
        val response = FakeHttpResponse(200, """{"tmdb_id":1396,"type":"tv","season":1,"episode":2,"intro":{"end_ms":90000}}""")
        val http = FakeHttpClient(response)
        val lookup = TheIntroDbSkipLookup(http)

        assertTrue(lookup.lookup(CommunitySkipQuery("1396", 1, null, 3_600_000)).isEmpty())
        assertTrue(http.urls.isEmpty())
        assertTrue(lookup.lookup(CommunitySkipQuery("1396", 1, 1, 3_600_000)).isEmpty())
        assertEquals(1, http.urls.size)
    }

    @Test fun rejectsMalformedOrImplausibleSegmentsAndNonSuccessResponses() = runBlocking {
        val response = FakeHttpResponse(200, """
            {"tmdb_id":1396,"type":"tv","season":1,"episode":1,
             "intro":[{"end_ms":500000},{"end_ms":90000}],
             "credits":[{"start_ms":100000,"end_ms":150000},{"start_ms":3500000,"end_ms":3800000},{"start_ms":2900000,"end_ms":null}]}
        """.trimIndent())
        val lookup = TheIntroDbSkipLookup(FakeHttpClient(response))
        val markers = lookup.lookup(CommunitySkipQuery("1396", 1, 1, 3_600_000))

        assertEquals(listOf("intro", "credits"), markers.map { it.type })
        val unavailable = FakeHttpResponse(503, "")
        assertTrue(TheIntroDbSkipLookup(FakeHttpClient(unavailable))
            .lookup(CommunitySkipQuery("1396", 1, 1, 3_600_000)).isEmpty())
        assertTrue(unavailable.closed)
    }

    @Test fun iptvLanguagePrefixIsRetriedOnlyAfterRawTitleFindsNothing() = runBlocking {
        val http = RoutedHttpClient { url ->
            when {
                url.contains("q=EN+-+Attack+on+Titan") -> FakeHttpResponse(200, "[]")
                url.contains("tvmaze.com") -> FakeHttpResponse(200, """[
                    {"show":{"name":"Attack on Titan","premiered":"2013-04-07","externals":{"imdb":"tt2560140"}}}]""")
                else -> FakeHttpResponse(200, """{"tmdb_id":1429,"type":"tv","season":1,"episode":12,
                    "intro":[{"start_ms":116606,"end_ms":208939}]}""")
            }
        }
        val markers = TheIntroDbSkipLookup(http)
            .lookup(CommunitySkipQuery("", 1, 12, 1_440_000, "EN - Attack on Titan", 2013))

        assertEquals(3, http.urls.size)
        assertEquals("https://api.tvmaze.com/search/shows?q=Attack+on+Titan", http.urls[1])
        assertEquals(listOf("intro"), markers.map { it.type })
    }

    @Test fun titleThatLooksPrefixedButMatchesExactlyIsNotStripped() = runBlocking {
        val http = RoutedHttpClient { url ->
            if (url.contains("tvmaze.com")) FakeHttpResponse(200, """[
                {"show":{"name":"FBI: Most Wanted","premiered":"2020-01-07","externals":{"imdb":"tt9742936"}}}]""")
            else FakeHttpResponse(200, """{"tmdb_id":93533,"type":"tv","season":1,"episode":1,"intro":[{"end_ms":60000}]}""")
        }
        TheIntroDbSkipLookup(http).lookup(CommunitySkipQuery("", 1, 1, 2_600_000, "FBI: Most Wanted", 2020))

        assertEquals(2, http.urls.size)
        assertTrue(http.urls[1].contains("imdb_id=tt9742936"))
    }
}
