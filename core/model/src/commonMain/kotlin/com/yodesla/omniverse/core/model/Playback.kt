package com.yodesla.omniverse.core.model

sealed interface PlaybackRequest {
    val sourceId: SourceId

    data class Live(override val sourceId: SourceId, val channelId: RemoteId) : PlaybackRequest

    data class Vod(
        override val sourceId: SourceId,
        val vodId: RemoteId,
        val containerExt: String?,
        /** Stable source-specific playable-file identity, resolved again when playback starts. */
        val versionId: String? = null,
    ) : PlaybackRequest

    data class EpisodeItem(
        override val sourceId: SourceId,
        val episodeId: RemoteId,
        val containerExt: String?,
    ) : PlaybackRequest

    /** Catch-up of a past programme. startMs is UTC; the source converts to server-local time. */
    data class Catchup(
        override val sourceId: SourceId,
        val channelId: RemoteId,
        val startMs: Long,
        val durationMinutes: Int,
    ) : PlaybackRequest
}

enum class MimeHint { MPEG_TS, HLS, MP4, MKV, UNKNOWN }
enum class DeliveryMode { DIRECT_PLAY, TRANSCODE, UNKNOWN }

data class SkipMarker(val type: String, val startMs: Long, val endMs: Long)

/**
 * Everything a PlayerEngine needs to open a stream.
 * [url] may contain credentials: NEVER log it — log [redacted] instead.
 */
class PlaybackSpec(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val mimeHint: MimeHint,
    val isLive: Boolean,
    val seekable: Boolean,
    /** Same URL with username/password masked; safe for logs and error messages. */
    val redacted: String,
    val deliveryMode: DeliveryMode = DeliveryMode.UNKNOWN,
    /** Verified provider markers only; empty means no skip control. */
    val skipMarkers: List<SkipMarker> = emptyList(),
) {
    override fun toString(): String = "PlaybackSpec($redacted, $mimeHint, live=$isLive)"
}
