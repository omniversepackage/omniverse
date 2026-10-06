package com.yodesla.omniverse.core.source.plex

import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.SourceId
import com.yodesla.omniverse.core.net.OkHttpHttpClient
import com.yodesla.omniverse.core.net.RetryPolicy
import com.yodesla.omniverse.core.source.RemoteProgress
import com.yodesla.omniverse.core.source.SourceConfig
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer

/** PlexSource.remoteProgress() against a MockWebServer; onDeck fixture lives in resources/plex/. */
class PlexRemoteProgressTest {
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

    private fun fixture(name: String): String =
        javaClass.classLoader.getResource("plex/$name")!!.readText()

    private fun source() = PlexSource(
        SourceConfig.Plex(SourceId("p"), "Mock", base, token, "m-abc-123", "omni-client-1"),
        http,
        RetryPolicy(maxAttempts = 2, initialDelayMs = 1, maxDelayMs = 5),
    )

    private fun enqueue(name: String) {
        server.enqueue(MockResponse.Builder().code(200).setHeader("Content-Type", "application/json").body(fixture(name)).build())
    }

    @Test
    fun onDeckMapsMovieAndEpisodeAndSkipsNoViewOffset() = runBlocking {
        enqueue("on_deck.json")
        val items = source().remoteProgress()
        // The third fixture item has no viewOffset, so it is skipped.
        assertEquals(2, items.size)

        val movie = items[0]
        assertEquals(RemoteId("77"), movie.id)
        assertEquals(ContentKind.VOD, movie.kind)
        assertEquals(null, movie.parentId)
        assertEquals(1800000L, movie.positionMs)
        assertEquals(7200000L, movie.durationMs)
        assertEquals(1700000500000L, movie.lastViewedAtMs)

        val episode = items[1]
        assertEquals(RemoteId("e1"), episode.id)
        assertEquals(ContentKind.EPISODE, episode.kind)
        assertEquals(RemoteId("50"), episode.parentId)
        assertEquals(600000L, episode.positionMs)
        assertEquals(1800000L, episode.durationMs)
        assertEquals(1700000600000L, episode.lastViewedAtMs)

        val req = server.takeRequest()
        assertEquals("/library/onDeck", req.url.encodedPath)
        assertEquals(token, req.headers.get("X-Plex-Token"))
        assertEquals("application/json", req.headers.get("Accept"))
    }

    @Test
    fun remoteProgressIsEmptyOnHttpErrorAndNeverThrows() = runBlocking {
        server.enqueue(MockResponse.Builder().code(500).body("boom").build())
        val items = source().remoteProgress()
        assertEquals(emptyList<RemoteProgress>(), items)
    }

    @Test
    fun remoteProgressIsEmptyOnUnreadableBodyAndNeverThrows() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).setHeader("Content-Type", "application/json").body("not json at all").build())
        val items = source().remoteProgress()
        assertTrue(items.isEmpty())
    }

    @Test
    fun remoteProgressIsEmptyWhenOnDeckHasNoMetadata() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).setHeader("Content-Type", "application/json")
            .body("""{"MediaContainer":{"size":0}}""").build())
        assertEquals(emptyList<RemoteProgress>(), source().remoteProgress())
    }
}
