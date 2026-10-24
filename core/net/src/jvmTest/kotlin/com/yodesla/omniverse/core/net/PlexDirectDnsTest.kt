package com.yodesla.omniverse.core.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlexDirectDnsTest {
    @Test fun resolvesOnlyEncodedPlexAddressesWithoutRouterDns() {
        val host = "192-168-1-137.abc123.plex.direct"
        val resolved = plexDirectAddress(host)!!
        assertEquals("192.168.1.137", resolved.hostAddress)
        assertEquals(host, resolved.hostName)
        assertEquals("76.213.178.60", plexDirectAddress("76-213-178-60.abc123.plex.direct")!!.hostAddress)
        assertEquals("192.168.1.137", OkHttpHttpClient.defaultOkHttp().dns.lookup(host).single().hostAddress)
    }

    @Test fun rejectsUnrelatedAndMalformedHostnames() {
        for (host in listOf("example.com", "192-168-1-137.abc.plex.direct.attacker.com",
            "999-168-1-137.abc.plex.direct", "192-168-1.abc.plex.direct", "foo.plex.direct")) {
            assertNull(plexDirectAddress(host))
        }
    }
}
