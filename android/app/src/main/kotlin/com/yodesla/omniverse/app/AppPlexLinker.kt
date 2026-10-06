package com.yodesla.omniverse.app

import com.yodesla.omniverse.core.data.UserDataRepository
import com.yodesla.omniverse.core.model.SourceId
import com.yodesla.omniverse.core.net.HttpClient
import com.yodesla.omniverse.core.source.SourceConfig
import com.yodesla.omniverse.core.source.plex.PlexLink
import com.yodesla.omniverse.feature.onboarding.PlexLinker
import com.yodesla.omniverse.feature.onboarding.PlexServerChoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import java.util.UUID

/**
 * Onboarding's plex.tv/link flow on top of core:source-plex. One X-Plex-Client-Identifier per
 * install (Plex lists it under "Authorized devices", so it must stay stable).
 */
class AppPlexLinker(
    private val http: HttpClient,
    private val userData: UserDataRepository,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    /** Seals the plex.tv account token so Settings can add/remove servers later without a new sign-in. */
    private val secrets: com.yodesla.omniverse.core.data.SecretBox? = null,
    /** Identity probes and Keystore work run here, never on the caller's (Main) dispatcher. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Hard cap on one /identity probe: headers AND the streaming body must answer within this. */
    private val probeTimeoutMs: Long = 3_000,
) : PlexLinker {
    private var link: PlexLink? = null
    private var pin: PlexLink.Pin? = null
    private var clientId: String? = null

    override suspend fun createCode(): String {
        val id = userData.setting(CLIENT_ID_KEY).first() ?: newId().also { userData.putSetting(CLIENT_ID_KEY, it) }
        clientId = id
        val l = PlexLink(http, id).also { link = it }
        return l.createPin().also { pin = it }.code
    }

    override suspend fun poll(): List<PlexServerChoice>? {
        val l = link ?: return null
        val p = pin ?: return null
        val userToken = l.pollToken(p) ?: return null
        val cid = clientId ?: return null
        // Keystore seal is synchronous hardware work: never on the caller's thread.
        secrets?.let { s -> withContext(io) { userData.putSetting(ACCOUNT_TOKEN_KEY, s.seal(userToken)) } }
        return resolve(l, userToken, cid)
    }

    /** True once a Plex sign-in has been remembered on this device. */
    suspend fun hasAccount(): Boolean = savedToken() != null

    /**
     * Every server the remembered Plex account can reach (owner + friends' shares), or null when no
     * sign-in is remembered (linked before this was kept, or the device key was lost).
     */
    suspend fun accountServers(): List<PlexServerChoice>? {
        val token = savedToken() ?: return null
        val cid = userData.setting(CLIENT_ID_KEY).first() ?: newId().also { userData.putSetting(CLIENT_ID_KEY, it) }
        return resolve(PlexLink(http, cid), token, cid)
    }

    /** Forget the remembered Plex sign-in (servers already added keep working). */
    suspend fun forgetAccount() = userData.putSetting(ACCOUNT_TOKEN_KEY, "")

    /** Keystore open is synchronous: run it off the caller's (Main) dispatcher. */
    private suspend fun savedToken(): String? {
        val sealed = userData.setting(ACCOUNT_TOKEN_KEY).first()?.takeIf { it.isNotBlank() } ?: return null
        return withContext(io) { secrets?.open(sealed) }
    }

    private suspend fun resolve(l: PlexLink, userToken: String, cid: String): List<PlexServerChoice> {
        // Each server: first connection whose /identity answers as THAT server within 3 s (LAN addresses first).
        return coroutineScope {
            l.servers(userToken).map { s ->
                async {
                    val uri = s.connections.firstOrNull { reachable(it, s.machineId, cid) } ?: return@async null
                    PlexServerChoice(s.name, SourceConfig.Plex(SourceId(newId()), s.name, uri.trimEnd('/'), s.token, s.machineId, cid))
                }
            }.mapNotNull { it.await() }
        }
    }

    // /identity needs no token, so the probe sends none, and it must report the expected machineIdentifier.
    // A plain-http LAN address that belongs to some other device then never receives the server's access
    // token, and a different Plex server on the same IP is never bound in its place.
    // Off the caller's dispatcher: a stalled body must never block the thread that opened Settings.
    // withTimeoutOrNull alone cannot interrupt a blocking read, so the response also gets a hard okio
    // deadline that aborts the socket; `use` closes the response on every exit path.
    private suspend fun reachable(uri: String, machineId: String, cid: String): Boolean = withContext(io) {
        withTimeoutOrNull(probeTimeoutMs) { probeIdentity(uri, machineId, cid) } == true
    }

    private suspend fun probeIdentity(uri: String, machineId: String, cid: String): Boolean =
        try {
            http.get("${uri.trimEnd('/')}/identity", mapOf("X-Plex-Client-Identifier" to cid, "Accept" to "application/json"))
                .use { r ->
                    r.body.timeout().deadline(probeTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                    r.status in 200..299 && identityOf(r.body.readUtf8()) == machineId
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false // unreachable, answered as another machine, or the body never finished in time
        }

    private companion object {
        const val CLIENT_ID_KEY = "plex_client_id"
        const val ACCOUNT_TOKEN_KEY = "plex_account_token_sealed"
        // JSON ("machineIdentifier":"…") or XML (machineIdentifier="…") — servers that ignore Accept send XML.
        private val MACHINE_ID = Regex("machineIdentifier\"?\\s*[:=]\\s*\"([^\"]+)\"")
        fun identityOf(body: String): String? = MACHINE_ID.find(body)?.groupValues?.get(1)
    }
}
