package com.yodesla.omniverse.app

import com.yodesla.omniverse.core.data.UserDataRepository
import com.yodesla.omniverse.core.data.Progress
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.net.HttpClient
import com.yodesla.omniverse.core.net.HttpResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okio.Buffer
import okio.BufferedSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AppPlexLinkerTest {
    @Test
    fun keepsEveryReachableOwnerAndSharedFriendServerSelectable() = runBlocking {
        val http = PlexLinkHttpFixture()
        val ids = ArrayDeque(listOf("client-test", "plex-own", "plex-friend"))
        val linker = AppPlexLinker(http, PlexLinkUserData(), newId = { ids.removeFirst() })

        assertEquals("ABCD", linker.createCode())
        val choices = assertNotNull(linker.poll())

        assertEquals(listOf("Kory's Server", "Friend's Server"), choices.map { it.name })
        assertEquals(listOf("own-machine", "friend-machine"), choices.map { it.config.machineId })
        assertEquals(listOf("owner-server-token", "shared-server-token"), choices.map { it.config.token })
        // Own server: same network, plain LAN address. Friend's server: other network, so its LAN address is
        // skipped and the remote plex.direct uri is used.
        assertEquals(listOf("http://192.168.1.10:32400", "https://friend.plex.direct:32400"), choices.map { it.config.server })
        assertEquals(listOf("client-test", "client-test"), choices.map { it.config.clientId })
        // No identity probe ever carries a server token.
        assertEquals(emptyList(), http.identityProbes.mapNotNull { it.second })
    }

    @Test
    fun neverBindsADifferentServerThatAnswersOnTheSameAddress() = runBlocking {
        // The friend's server reports the viewer's own network (publicAddressMatches missing), and the
        // viewer has a different Plex server at that same LAN IP: it must not be bound in its place.
        val http = PlexLinkHttpFixture(friendSameNetwork = null, strangerAt = setOf("http://192.168.1.11:32400", "https://192-168-1-11.friend.plex.direct:32400"))
        val ids = ArrayDeque(listOf("client-test", "plex-own", "plex-friend"))
        val linker = AppPlexLinker(http, PlexLinkUserData(), newId = { ids.removeFirst() })
        linker.createCode()
        val choices = assertNotNull(linker.poll())
        assertEquals("https://friend.plex.direct:32400", choices.single { it.config.machineId == "friend-machine" }.config.server)
        assertEquals(emptyList(), http.identityProbes.mapNotNull { it.second })
    }
}

private class PlexLinkHttpFixture(
    /** publicAddressMatches for the friend's server; null = field absent. */
    private val friendSameNetwork: Boolean? = false,
    /** Hosts that answer /identity as some other Plex server (a different box on the viewer's LAN). */
    private val strangerAt: Set<String> = emptySet(),
) : HttpClient {
    /** (url, X-Plex-Token header) of every /identity request. */
    val identityProbes = mutableListOf<Pair<String, String?>>()

    private val machineAt = mapOf(
        "http://192.168.1.10:32400" to "own-machine", "https://own.plex.direct:32400" to "own-machine",
        "http://192.168.1.11:32400" to "friend-machine", "https://192-168-1-11.friend.plex.direct:32400" to "friend-machine",
        "https://friend.plex.direct:32400" to "friend-machine",
    )

    override suspend fun post(url: String, headers: Map<String, String>): HttpResponse =
        response(200, """{"id":123,"code":"ABCD"}""")

    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse = when {
        url.endsWith("/api/v2/pins/123") -> response(200, """{"authToken":"user-token"}""")
        url.contains("/api/v2/resources?") -> response(200, """
            [
              {"name":"Kory's Server","provides":"server","clientIdentifier":"own-machine","accessToken":"owner-server-token",
               "publicAddressMatches":true,
               "connections":[{"address":"192.168.1.10","port":32400,"uri":"https://own.plex.direct:32400","local":true}]},
              {"name":"Friend's Server","provides":"server","clientIdentifier":"friend-machine","accessToken":"shared-server-token",
               ${friendSameNetwork?.let { "\"publicAddressMatches\":$it," } ?: ""}
               "connections":[{"address":"192.168.1.11","port":32400,"uri":"https://192-168-1-11.friend.plex.direct:32400","local":true},
                              {"address":"203.0.113.9","port":32400,"uri":"https://friend.plex.direct:32400","local":false}]}
            ]
        """.trimIndent())
        url.endsWith("/identity") -> {
            val base = url.removeSuffix("/identity")
            identityProbes += url to headers["X-Plex-Token"]
            val machine = if (base in strangerAt) "some-other-server" else machineAt[base]
            if (machine != null) response(200, """{"MediaContainer":{"machineIdentifier":"$machine"}}""") else response(404, "{}")
        }
        else -> response(404, "{}")
    }

    private fun response(status: Int, body: String) = object : HttpResponse {
        override val status = status
        override val headers = emptyMap<String, String>()
        override val body: BufferedSource = Buffer().writeUtf8(body)
        override fun close() = Unit
    }
}

private class PlexLinkUserData : UserDataRepository {
    private val settings = mutableMapOf<String, String>()
    override fun favorites(kind: ContentKind?): Flow<List<ContentKey>> = flowOf(emptyList())
    override fun isFavorite(key: ContentKey): Flow<Boolean> = flowOf(false)
    override suspend fun setFavorite(key: ContentKey, favorite: Boolean) = Unit
    override suspend fun saveProgress(key: ContentKey, parentId: RemoteId?, positionMs: Long, durationMs: Long?) = Unit
    override fun continueWatching(limit: Int): Flow<List<Progress>> = flowOf(emptyList())
    override suspend fun progress(key: ContentKey): Progress? = null
    override suspend fun recordChannelWatched(key: ContentKey) = Unit
    override fun recentChannels(limit: Int): Flow<List<ContentKey>> = flowOf(emptyList())
    override suspend fun setHidden(key: ContentKey, hidden: Boolean) = Unit
    override fun setting(key: String): Flow<String?> = flowOf(settings[key])
    override suspend fun putSetting(key: String, value: String) { settings[key] = value }
}
