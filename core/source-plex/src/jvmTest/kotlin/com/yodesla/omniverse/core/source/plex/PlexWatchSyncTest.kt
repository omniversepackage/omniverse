package com.yodesla.omniverse.core.source.plex

import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.SourceId
import com.yodesla.omniverse.core.net.HttpClient
import com.yodesla.omniverse.core.net.HttpResponse
import com.yodesla.omniverse.core.net.RetryPolicy
import com.yodesla.omniverse.core.source.SourceConfig
import com.yodesla.omniverse.core.source.SourceException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import okio.BufferedSource

/**
 * Audit M10: a watched change that Plex did not accept must be reported, retried only within the
 * policy, and remote intent for one ratingKey must stay serialized and coalesced (last intent wins).
 */
class PlexWatchSyncStatusTest {
    private val http = com.yodesla.omniverse.core.net.OkHttpHttpClient(
        com.yodesla.omniverse.core.net.OkHttpHttpClient.defaultOkHttp(), "Omniverse-test",
    )
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
        SourceConfig.Plex(SourceId("p"), "Mock", base, "tok-very-secret-123", "m-abc-123", "omni-client-1"),
        http,
        RetryPolicy(maxAttempts = 2, initialDelayMs = 1, maxDelayMs = 5),
    )

    private fun enqueue(code: Int, body: String) {
        server.enqueue(MockResponse.Builder().code(code).setHeader("Content-Type", "application/json").body(body).build())
    }

    @Test
    fun acceptedWatchedChangeSendsExactlyOneRequest() = runBlocking {
        enqueue(200, """{"MediaContainer":{"size":0}}""")
        source().setWatched(RemoteId("e1"), true)
        assertEquals(1, server.requestCount)
        assertEquals("/:/scrobble", server.takeRequest().url.encodedPath)
    }

    @Test
    fun expiredTokenFailsAtOnceWithoutPointlessRetries() = runBlocking {
        enqueue(401, "{}")
        assertFailsWith<SourceException.AuthFailed> { source().setWatched(RemoteId("e1"), true) }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun serviceUnavailableIsRetriedThenReported() = runBlocking {
        enqueue(503, "later")
        enqueue(503, "later")
        val e = assertFailsWith<SourceException.Http> { source().setWatched(RemoteId("e1"), true) }
        assertEquals(503, e.status)
        assertEquals(2, server.requestCount)
    }
}

/** Serialized / coalesced remote intent per ratingKey, against a fake transport we can time. */
class PlexWatchSyncOrderingTest {
    private fun source(http: HttpClient) = PlexSource(
        SourceConfig.Plex(SourceId("p"), "Mock", "http://127.0.0.1:1", "tok-very-secret-123", "m-abc-123", "omni-client-1"),
        http,
        RetryPolicy(maxAttempts = 1, initialDelayMs = 1, maxDelayMs = 1),
    )

    @Test
    fun rapidInvertedIntentsReachPlexInTheOrderTheyWereAsked() = runBlocking {
        val http = TimedHttp(stallMs = 60)
        val src = source(http)
        val first = launch { src.setWatched(RemoteId("e1"), true) }
        delay(10) // the watched request is still in flight
        val second = launch { src.setWatched(RemoteId("e1"), false) }
        first.join()
        second.join()
        assertEquals(listOf("/:/scrobble", "/:/unscrobble"), http.paths)
    }

    @Test
    fun supersededIntentsCollapseSoTheLastIntentWins() = runBlocking {
        val http = TimedHttp(stallMs = 80)
        val src = source(http)
        val first = launch { src.setWatched(RemoteId("e1"), true) }
        delay(10)
        launch { src.setWatched(RemoteId("e1"), false) }
        delay(10)
        launch { src.setWatched(RemoteId("e1"), true) }
        first.join()
        // The abandoned "false" was replaced before it was ever sent: watched -> watched.
        assertEquals(listOf("/:/scrobble", "/:/scrobble"), http.paths)
    }

    @Test
    fun intentsForOneTitleNeverOverlapOnTheWire() = runBlocking {
        val http = TimedHttp(stallMs = 40)
        val src = source(http)
        val a = launch { src.setWatched(RemoteId("e1"), true) }
        delay(5)
        val b = launch { src.setWatched(RemoteId("e1"), false) }
        a.join()
        b.join()
        assertEquals(2, http.windows.size)
        assertEquals(true, http.windows[0].second <= http.windows[1].first)
    }

    @Test
    fun cancellationOfAWatchSyncPropagatesInsteadOfBeingSwallowed() = runBlocking {
        val http = TimedHttp(stallMs = 300)
        val src = source(http)
        val job = launch { src.setWatched(RemoteId("e1"), true) }
        delay(30)
        job.cancel()
        job.join()
        assertTrue(job.isCancelled, "the cancelled watch sync swallowed the cancellation")
        // A cancelled attempt is never counted as delivered.
        assertEquals(0, http.delivered)

        val healthy = TimedHttp(stallMs = 0)
        source(healthy).setWatched(RemoteId("e2"), false)
        assertEquals(listOf("/:/unscrobble"), healthy.paths)
    }
}

/** Records the path and the in-flight window of every request; the body arrives after [stallMs]. */
private class TimedHttp(private val stallMs: Long) : HttpClient {
    val paths = mutableListOf<String>()
    val windows = mutableListOf<Pair<Long, Long>>()
    /** Requests that actually completed and were closed. */
    var delivered = 0

    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
        val path = url.substringAfter("http://127.0.0.1:1").substringBefore('?')
        paths += path
        val start = System.nanoTime()
        if (stallMs > 0) delay(stallMs)
        return object : HttpResponse {
            override val status = 200
            override val headers = emptyMap<String, String>()
            override val body: BufferedSource = Buffer().writeUtf8("""{"MediaContainer":{"size":0}}""")
            override fun close() {
                windows += start to System.nanoTime()
                delivered++
            }
        }
    }
}
