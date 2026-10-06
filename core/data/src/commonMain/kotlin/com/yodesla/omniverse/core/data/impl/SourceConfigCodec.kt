package com.yodesla.omniverse.core.data.impl

import com.yodesla.omniverse.core.model.SourceId
import com.yodesla.omniverse.core.source.SourceConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Persists SourceConfig as JSON in source.config_json. Contains credentials: this string must never
 * be logged or exported unencrypted (PLAN.md §10).
 */
internal object SourceConfigCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator = "type" }

    @Serializable
    private sealed interface Dto

    @Serializable @SerialName("xtream")
    private data class XtreamDto(
        val id: String, val name: String, val server: String, val username: String, val password: String,
        val liveFormat: String = "ts", val epgUrlOverride: String? = null,
    ) : Dto

    @Serializable @SerialName("m3u")
    private data class M3uDto(
        val id: String, val name: String, val playlistUrl: String, val epgUrl: String? = null, val userAgent: String? = null,
    ) : Dto

    @Serializable @SerialName("plex")
    private data class PlexDto(
        val id: String, val name: String, val server: String, val token: String, val machineId: String, val clientId: String,
    ) : Dto

    fun encode(config: SourceConfig): String = json.encodeToString(
        Dto.serializer(),
        when (config) {
            is SourceConfig.Xtream -> XtreamDto(
                config.id.value, config.displayName, config.server, config.username, config.password,
                config.liveFormat, config.epgUrlOverride,
            )
            is SourceConfig.M3u -> M3uDto(config.id.value, config.displayName, config.playlistUrl, config.epgUrl, config.userAgent)
            is SourceConfig.Plex -> PlexDto(config.id.value, config.displayName, config.server, config.token, config.machineId, config.clientId)
        },
    )

    fun decode(text: String): SourceConfig? = try {
        when (val d = json.decodeFromString(Dto.serializer(), text)) {
            is XtreamDto -> SourceConfig.Xtream(SourceId(d.id), d.name, d.server, d.username, d.password, d.liveFormat, d.epgUrlOverride)
            is M3uDto -> SourceConfig.M3u(SourceId(d.id), d.name, d.playlistUrl, d.epgUrl, d.userAgent)
            is PlexDto -> SourceConfig.Plex(SourceId(d.id), d.name, d.server, d.token, d.machineId, d.clientId)
        }
    } catch (e: Exception) {
        null
    }

    fun kindOf(config: SourceConfig): String = when (config) {
        is SourceConfig.Xtream -> "XTREAM"
        is SourceConfig.M3u -> "M3U"
        is SourceConfig.Plex -> "PLEX"
    }

    /** Same config with a different id (used when the caller left the id blank). */
    fun withId(config: SourceConfig, id: SourceId): SourceConfig = when (config) {
        is SourceConfig.Xtream -> SourceConfig.Xtream(id, config.displayName, config.server, config.username, config.password, config.liveFormat, config.epgUrlOverride)
        is SourceConfig.M3u -> SourceConfig.M3u(id, config.displayName, config.playlistUrl, config.epgUrl, config.userAgent)
        is SourceConfig.Plex -> SourceConfig.Plex(id, config.displayName, config.server, config.token, config.machineId, config.clientId)
    }
}
