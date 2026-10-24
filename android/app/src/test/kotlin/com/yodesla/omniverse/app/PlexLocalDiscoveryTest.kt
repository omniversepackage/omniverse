package com.yodesla.omniverse.app

import kotlin.test.Test
import kotlin.test.assertEquals

class PlexLocalDiscoveryTest {
    private val response = "HTTP/1.0 200 OK\r\nHost: abc123.plex.direct\r\nPort: 32400\r\nResource-Identifier: own-machine\r\n\r\n"

    @Test fun buildsCurrentTlsCandidateForMatchingServer() {
        assertEquals(listOf("https://192-168-1-137.abc123.plex.direct:32400"),
            plexGdmConnections(response, "192.168.1.137", "own-machine"))
    }

    @Test fun rejectsOtherServersAndInvalidEndpoints() {
        assertEquals(emptyList(), plexGdmConnections(response, "192.168.1.137", "other-machine"))
        assertEquals(emptyList(), plexGdmConnections(response.replace("abc123.plex.direct", "attacker.example"), "192.168.1.137", "own-machine"))
        assertEquals(emptyList(), plexGdmConnections(response.replace("32400", "99999"), "192.168.1.137", "own-machine"))
    }
}
