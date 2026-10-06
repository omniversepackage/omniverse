package com.yodesla.omniverse.core.net

import com.yodesla.omniverse.core.source.SourceException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import okio.Buffer
import mockwebserver3.MockWebServer

class OkHttpHttpClientTest {
    private fun client(userAgent: String = "omniverse-test/1.0") =
        OkHttpHttpClient(OkHttpHttpClient.defaultOkHttp(), userAgent)

    @Test
    fun twoHundredBodyIsReadableAndHeadersExposed() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse.Builder().code(200).setHeader("X-Custom", "abc").body("hello").build(),
            )
            runBlocking {
                client().get(server.url("/x").toString()).use { r ->
                    assertEquals(200, r.status)
                    assertEquals("abc", r.headers["x-custom"])
                    assertEquals("hello", r.body.readUtf8())
                }
            }
            assertEquals("GET", server.takeRequest().method)
        } finally {
            server.close()
        }
    }

    @Test
    fun constructorUserAgentIsSentAndCallerCanOverrideIt() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse.Builder().code(200).build())
            runBlocking {
                client(userAgent = "base-ua/1").get(server.url("/1").toString()).use { }
            }
            assertEquals("base-ua/1", server.takeRequest().headers.get("User-Agent"))

            server.enqueue(MockResponse.Builder().code(200).build())
            runBlocking {
                client(userAgent = "base-ua/1")
                    .get(server.url("/2").toString(), mapOf("User-Agent" to "custom-ua/9")).use { }
            }
            assertEquals("custom-ua/9", server.takeRequest().headers.get("User-Agent"))
        } finally {
            server.close()
        }
    }

    @Test
    fun requireSuccessThrowsHttp500WithRedactedMessage() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse.Builder().code(500).body("boom").build())
            val url = server.url("/player_api.php?username=u&password=secret").toString()
            runBlocking {
                val r = client().get(url)
                assertEquals(500, r.status)
                val e = assertFailsWith<SourceException.Http> { r.requireSuccess(url) }
                assertEquals(500, e.status)
                assertFalse(e.message.orEmpty().contains("secret"), "message leaked secret: ${e.message}")
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun connectionFailureIsNetworkWithRedactedMessage() {
        val server = MockWebServer()
        server.start()
        server.close()
        val url = server.url("/player_api.php?username=u&password=secret").toString()
        val e = assertFailsWith<SourceException.Network> {
            runBlocking { client().get(url) }
        }
        assertFalse(e.message.orEmpty().contains("secret"), "message leaked secret: ${e.message}")
        val m = e.message.orEmpty()
        assertTrue(m.contains("Invalid URL: ") || m.contains("Network error for "), "unexpected message: $m")
    }

    @Test
    fun largeBodyCanBeReadInChunks() {
        val server = MockWebServer()
        server.start()
        try {
            val content = ByteArray(5 * 1024 * 1024) { (it % 251).toByte() }
            server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(content)).build())
            var read = 0L
            runBlocking {
                client().get(server.url("/big").toString()).use { r ->
                    val sink = ByteArray(64 * 1024)
                    while (true) {
                        val n = r.body.read(sink)
                        if (n == -1) break
                        read += n
                    }
                }
            }
            assertEquals(content.size.toLong(), read)
        } finally {
            server.close()
        }
    }
}
