package com.yodesla.omniverse.core.source.plex

import com.yodesla.omniverse.core.net.OkHttpHttpClient
import com.yodesla.omniverse.core.net.RetryPolicy
import com.yodesla.omniverse.core.source.SourceException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer

/** PlexLink (plex.tv PIN flow) against a MockWebServer via the base-URL constructor parameter. */
class PlexLinkTest {
    private val http = OkHttpHttpClient(OkHttpHttpClient.defaultOkHttp(), "Omniverse-test")
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

    private fun link() = PlexLink(http, "omni-client-1", base, RetryPolicy(maxAttempts = 2, initialDelayMs = 1, maxDelayMs = 5))

    private fun enqueue(name: String) {
        server.enqueue(MockResponse.Builder().code(200).setHeader("Content-Type", "application/json").body(fixture(name)).build())
    }

    @Test
    fun createPinReturnsIdAndCode() = runBlocking {
        enqueue("pin.json")
        val pin = link().createPin()
        assertEquals(PlexLink.Pin(1234, "1234"), pin)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/v2/pins", req.url.encodedPath)
        assertEquals("strong=false", req.url.encodedQuery)
        assertEquals("omni-client-1", req.headers.get("X-Plex-Client-Identifier"))
        assertEquals("Omniverse", req.headers.get("X-Plex-Product"))
        assertEquals("application/json", req.headers.get("Accept"))
    }

    @Test
    fun pollTokenIsNullUntilTheCodeWasEntered() = runBlocking {
        val pin = PlexLink.Pin(1234, "1234")
        enqueue("pin_pending.json")
        assertNull(link().pollToken(pin))
        assertEquals("/api/v2/pins/1234", server.takeRequest().url.encodedPath)
        enqueue("pin_done.json")
        assertEquals("tok-user-1", link().pollToken(pin))
    }

    @Test
    fun pollToken401IsAuthFailed() = runBlocking {
        server.enqueue(MockResponse.Builder().code(401).build())
        assertFailsWith<SourceException.AuthFailed> { link().pollToken(PlexLink.Pin(1, "1")) }
        Unit
    }

    @Test
    fun serversKeepsOnlyServersAndPutsLocalConnectionsFirst() = runBlocking {
        enqueue("resources.json")
        val servers = link().servers("tok-user-1")
        assertEquals(2, servers.size)

        assertEquals("Remote Box", servers[0].name)
        assertEquals("ci-remote", servers[0].machineId)
        assertEquals("tok-remote", servers[0].token)
        assertEquals(listOf("https://10-0-0-5.abc.plex.direct:32400"), servers[0].connections)

        assertEquals("Home Server", servers[1].name)
        // Local first, plain LAN http before its plex.direct name; remote last.
        assertEquals(
            listOf("http://192.168.1.10:32400", "https://192-168-1-10.def.plex.direct:32400", "https://10-0-0-9.def.plex.direct:32400"),
            servers[1].connections,
        )

        val req = server.takeRequest()
        assertEquals("/api/v2/resources", req.url.encodedPath)
        assertEquals("includeHttps=1&includeRelay=0", req.url.encodedQuery)
        assertEquals("tok-user-1", req.headers.get("X-Plex-Token"))
    }

    @Test
    fun serversOnAnotherNetworkDropTheirLanAddresses() = runBlocking {
        // A friend's shared server: its LAN IPs are on the friend's network, not the viewer's.
        enqueue("resources-other-network.json")
        val servers = link().servers("tok-user-1")
        assertEquals(listOf("https://203-0-113-7.fff.plex.direct:32400"), servers.single().connections)
    }
}
