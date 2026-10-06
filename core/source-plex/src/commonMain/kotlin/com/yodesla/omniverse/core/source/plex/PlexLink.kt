package com.yodesla.omniverse.core.source.plex

import com.yodesla.omniverse.core.model.Redact
import com.yodesla.omniverse.core.net.HttpClient
import com.yodesla.omniverse.core.net.RetryPolicy
import com.yodesla.omniverse.core.net.requireSuccess
import com.yodesla.omniverse.core.net.withRetry
import com.yodesla.omniverse.core.source.SourceException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.IOException

/**
 * plex.tv PIN-link flow (used by onboarding): create a PIN, show the code, poll until the
 * user enters it, then fetch their servers. We never see the user's plex.tv password.
 *
 * PIN creation uses POST /api/v2/pins?strong=false so the code works at plex.tv/link.
 */
class PlexLink(
    private val http: HttpClient,
    private val clientId: String,
    private val base: String = "https://plex.tv",
    private val retry: RetryPolicy = RetryPolicy(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    data class Pin(val id: Long, val code: String)

    data class Server(
        val name: String,
        val machineId: String,
        val token: String,
        /** Connection uris; local (LAN) ones first. */
        val connections: List<String>,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val headers = mapOf(
        "Accept" to "application/json",
        "X-Plex-Product" to "Omniverse",
        "X-Plex-Client-Identifier" to clientId,
    )

    suspend fun createPin(): Pin {
        // POST creates the PIN; strong=false = the short code plex.tv/link accepts.
        val root = getJson("/api/v2/pins?strong=false", post = true)
        val id = root.long("id") ?: throw SourceException.BadResponse("plex.tv returned no PIN id")
        val code = root.str("code") ?: throw SourceException.BadResponse("plex.tv returned no PIN code")
        return Pin(id, code)
    }

    /** null until the user has entered [Pin.code] in the Plex app. */
    suspend fun pollToken(pin: Pin): String? =
        getJson("/api/v2/pins/${pin.id}").let { it.str("authToken") ?: it.str("authentication_token") }

    suspend fun servers(userToken: String): List<Server> {
        // v2 answers with a bare JSON array; older shapes wrap it in MediaContainer.Resource.
        val body = getElement("/api/v2/resources?includeHttps=1&includeRelay=0", "X-Plex-Token" to userToken)
        val resources = body as? JsonArray
            ?: ((body as? JsonObject)?.get("MediaContainer") as? JsonObject)?.get("Resource") as? JsonArray
            ?: throw SourceException.BadResponse("plex.tv returned no resources")
        val out = mutableListOf<Server>()
        for (r in resources.filterIsInstance<JsonObject>()) {
            val name = r.str("name") ?: continue
            val machineId = r.str("clientIdentifier") ?: continue
            val token = r.str("accessToken") ?: continue
            val provides = when (val p = r["provides"]) {
                is JsonArray -> p.mapNotNull { (it as? JsonPrimitive)?.content }
                is JsonPrimitive -> p.content.split(',')
                else -> emptyList()
            }
            if (provides.none { it.trim().equals("server", ignoreCase = true) }) continue
            val all = (r["connections"] as? JsonArray)?.filterIsInstance<JsonObject>()
                ?: emptyList()
            // A server's LAN addresses only mean something on its own network (Plex's clients gate them
            // on publicAddressMatches). Elsewhere, e.g. a friend's shared server, they point at whatever
            // device happens to own that IP on the viewer's network.
            val conns = if (r.bool("publicAddressMatches") == false) all.filter { it.bool("local") != true } else all
            val localFirst = conns.filter { it.bool("local") == true } + conns.filter { it.bool("local") != true }
            // Plain http://LAN-IP:port first for local connections: *.plex.direct names can fail to
            // resolve behind DNS-rebinding protection (Pi-hole, many routers).
            val uris = localFirst.flatMap { c ->
                val plain = if (c.bool("local") == true) {
                    val a = c.str("address"); val port = c.long("port")
                    if (a != null && port != null) "http://$a:$port" else null
                } else null
                listOfNotNull(plain, c.str("uri"))
            }.distinct()
            out.add(Server(name, machineId, token, uris))
        }
        return out
    }

    private suspend fun getJson(path: String, vararg extra: Pair<String, String>, post: Boolean = false): JsonObject =
        getElement(path, *extra, post = post) as? JsonObject
            ?: throw SourceException.BadResponse("plex.tv answered $path with a non-object body")

    private suspend fun getElement(path: String, vararg extra: Pair<String, String>, post: Boolean = false): kotlinx.serialization.json.JsonElement = withContext(io) {
        val url = "$base$path"
        withRetry(retry) {
            val r = if (post) http.post(url, headers + extra.toMap()) else http.get(url, headers + extra.toMap())
            r.use {
                if (it.status == 401) throw SourceException.AuthFailed("plex.tv rejected the token")
                it.requireSuccess(url)
                val text = try {
                    it.body.readUtf8()
                } catch (e: IOException) {
                    throw SourceException.Network("Connection lost while reading ${Redact.text(url)}", e)
                }
                try {
                    json.parseToJsonElement(text)
                } catch (e: SerializationException) {
                    throw SourceException.BadResponse("plex.tv sent unreadable JSON for $path", e)
                }
            }
        }
    }
}
