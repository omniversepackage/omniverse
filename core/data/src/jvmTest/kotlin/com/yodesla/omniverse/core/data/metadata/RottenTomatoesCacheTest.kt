package com.yodesla.omniverse.core.data.metadata

import com.yodesla.omniverse.core.net.HttpClient
import com.yodesla.omniverse.core.net.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import okio.Buffer
import okio.BufferedSource
import okio.IOException

class RottenTomatoesCacheTest {
    private class FakeHttp(var failNext: Int = 0, val errorCall: Int = 0, val errorStatus: Int = 503) : HttpClient {
        var calls = 0
        override suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
            calls++
            if (failNext > 0) { failNext--; throw IOException("network down") }
            val body = if (url.contains("sparql")) """{"results":{"bindings":[{"rt":{"value":"m/fight_club"}}]}}"""
                else """"criticsScore":{"score":"79"} "audienceScore":{"score":"96"}"""
            val responseStatus = if (calls == errorCall) errorStatus else 200
            return object : HttpResponse {
                override val status = responseStatus
                override val headers = emptyMap<String, String>()
                override val body: BufferedSource = Buffer().writeUtf8(body)
                override fun close() = Unit
            }
        }
    }

    @Test fun networkFailureIsNotCachedAsAMiss() = runBlocking {
        val http = FakeHttp(failNext = 1)
        val rt = RottenTomatoes(http)
        assertNull(rt.scores(WikidataMetadata.Kind.MOVIE, "550"))
        assertEquals(RtScores(79, 96), rt.scores(WikidataMetadata.Kind.MOVIE, "550"))
    }

    @Test fun realAnswersAreCached() = runBlocking {
        val http = FakeHttp()
        val rt = RottenTomatoes(http)
        repeat(3) { assertEquals(RtScores(79, 96), rt.scores(WikidataMetadata.Kind.MOVIE, "550")) }
        assertEquals(2, http.calls)   // one SPARQL + one page, then cache
    }

    @Test fun httpErrorsAtEitherEndpointAreRetried() = runBlocking {
        for (endpointCall in listOf(1, 2)) {
            for (status in listOf(429, 503)) {
                val http = FakeHttp(errorCall = endpointCall, errorStatus = status)
                val rt = RottenTomatoes(http)
                assertNull(rt.scores(WikidataMetadata.Kind.MOVIE, "550"))
                assertEquals(RtScores(79, 96), rt.scores(WikidataMetadata.Kind.MOVIE, "550"))
                assertEquals(endpointCall + 2, http.calls)
            }
        }
    }

    @Test fun offMakesNoRequestAtAll() = runBlocking {
        val http = FakeHttp()
        val rt = RottenTomatoes(http, enabled = { false })
        assertNull(rt.scores(WikidataMetadata.Kind.MOVIE, "550"))
        assertEquals(0, http.calls)
    }
}
