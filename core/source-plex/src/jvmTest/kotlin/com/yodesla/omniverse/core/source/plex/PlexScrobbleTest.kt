package com.yodesla.omniverse.core.source.plex

import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.SourceId
import com.yodesla.omniverse.core.net.OkHttpHttpClient
import com.yodesla.omniverse.core.net.RetryPolicy
import com.yodesla.omniverse.core.source.SourceConfig
import com.yodesla.omniverse.core.source.SourceException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer

/**
 * PlexSource.setWatched(): the scrobble / unscrobble request must match what the official Plex
 * apps send, and a server that did not accept it must be reported (audit M10) rather than swallowed.
 */
class PlexScrobbleTest {
    private val http = OkHttpHttpClient(OkHttpHttpClient.defaultOkHttp(), "Omniverse-test")
    private val token = "tok-very-secret-123"
    private lateinit var server: MockWebServer
    private lateinit var base: String

    @BeforeTest
    fun up() {
        server = MockWebServer()
        server.start()
        base = server.url("/").toString().trimEnd('/')
    }

    @AfterTest
    fun down() {
        server.close()
    }

    private fun source() = PlexSource(
        SourceConfig.Plex(SourceId("p"), "Mock", base, token, "m-abc-123", "omni-client-1"),
        http,
        RetryPolicy(maxAttempts = 2, initialDelayMs = 1, maxDelayMs = 5),
    )

    private fun enqueue(code: Int, body: String) {
        server.enqueue(MockResponse.Builder().code(code).setHeader("Content-Type", "application/json").body(body).build())
    }

    @Test
    fun watchedSendsScrobbleWithLibraryIdentifier() = runBlocking {
        enqueue(200, """{"MediaContainer":{"size":0}}""")
        source().setWatched(RemoteId("e1"), true)
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/:/scrobble", req.url.encodedPath)
        assertEquals("key=e1&identifier=com.plexapp.plugins.library", req.url.encodedQuery)
        assertEquals(token, req.headers.get("X-Plex-Token"))
        assertEquals("application/json", req.headers.get("Accept"))
        assertEquals("omni-client-1", req.headers.get("X-Plex-Client-Identifier"))
        assertEquals("Omniverse", req.headers.get("X-Plex-Product"))
    }

    @Test
    fun unwatchedSendsUnscrobbleWithLibraryIdentifier() = runBlocking {
        enqueue(200, """{"MediaContainer":{"size":0}}""")
        source().setWatched(RemoteId("e1"), false)
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/:/unscrobble", req.url.encodedPath)
        assertEquals("key=e1&identifier=com.plexapp.plugins.library", req.url.encodedQuery)
        assertEquals(token, req.headers.get("X-Plex-Token"))
    }

    @Test
    fun ratingKeyIsPercentEncodedInQuery() = runBlocking {
        enqueue(200, """{"MediaContainer":{"size":0}}""")
        source().setWatched(RemoteId("9/1 x&y"), true)
        val req = server.takeRequest()
        assertEquals("/:/scrobble", req.url.encodedPath)
        assertEquals("key=9%2F1%20x%26y&identifier=com.plexapp.plugins.library", req.url.encodedQuery)
    }

    @Test
    fun serverErrorIsRetriedWithinThePolicyAndThenReported() = runBlocking {
        enqueue(500, "boom")
        enqueue(500, "boom")
        val e = assertFailsWith<SourceException.Http> { source().setWatched(RemoteId("e1"), true) }
        assertEquals(500, e.status)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun offlineIsReportedAsANetworkFailure() = runBlocking {
        server.close()
        assertFailsWith<SourceException.Network> { source().setWatched(RemoteId("e1"), false) }
    }
}
