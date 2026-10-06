package com.yodesla.omniverse.core.source

import com.yodesla.omniverse.core.model.SourceId

/** User-entered configuration of a source. toString() never reveals secrets. */
sealed interface SourceConfig {
    val id: SourceId
    val displayName: String

    class Xtream(
        override val id: SourceId,
        override val displayName: String,
        /** Exactly as typed, including scheme and port, e.g. "http://example.com:8080". No trailing slash. */
        val server: String,
        val username: String,
        val password: String,
        /** "ts" (default) or "m3u8". */
        val liveFormat: String = "ts",
        /** Optional extra/alternative XMLTV URL. null = the provider's xmltv.php. */
        val epgUrlOverride: String? = null,
    ) : SourceConfig {
        override fun toString() = "Xtream($displayName, $server, user=***)"
    }

    class M3u(
        override val id: SourceId,
        override val displayName: String,
        val playlistUrl: String,
        val epgUrl: String? = null,
        val userAgent: String? = null,
    ) : SourceConfig {
        override fun toString() = "M3u($displayName)"
    }

    /**
     * A Plex Media Server the user linked via plex.tv/link (PIN flow; we never see their password).
     * [token] is the server access token from plex.tv resources, stored like any other secret.
     */
    class Plex(
        override val id: SourceId,
        override val displayName: String,
        /** Best reachable connection, e.g. "http://192.168.1.10:32400". No trailing slash. */
        val server: String,
        val token: String,
        /** Plex server machineIdentifier (stable across address changes). */
        val machineId: String,
        /** Our X-Plex-Client-Identifier (one per install). */
        val clientId: String,
    ) : SourceConfig {
        override fun toString() = "Plex($displayName, $server, token=***)"
    }
}

/** Creates sources from configs. Each source module contributes one via DI. */
fun interface SourceFactory {
    /** Returns null if this factory doesn't handle [config]'s type. */
    fun create(config: SourceConfig): ContentSource?
}
