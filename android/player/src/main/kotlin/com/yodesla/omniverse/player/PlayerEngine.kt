package com.yodesla.omniverse.player

import com.yodesla.omniverse.core.model.PlaybackSpec
import kotlinx.coroutines.flow.StateFlow

/**
 * PLAN.md §5.1 / §7. One long-lived engine per playback screen.
 * Connection discipline (rule 7): opening a new spec ALWAYS releases the previous upstream request
 * first, and the engine never opens hidden extra connections (no neighbour pre-buffering).
 */
interface PlayerEngine {
    val state: StateFlow<PlayerState>
    val tracks: StateFlow<TrackInfo>
    val diagnostics: StateFlow<PlaybackDiagnostics>

    /** Opens [spec]. For VOD, [startPositionMs] resumes. */
    fun play(spec: PlaybackSpec, startPositionMs: Long = 0)

    /** Live fast path: reuses player + surface, no teardown. Same connection discipline as [play]. */
    fun zap(spec: PlaybackSpec)

    fun pause()
    fun resume()
    fun seekTo(positionMs: Long)
    fun seekBy(deltaMs: Long)
    /** Stops and closes the upstream connection; the engine stays reusable. */
    fun stop()
    /** Final teardown (screen destroyed). */
    fun release()

    fun selectAudio(trackId: String?)
    fun selectSubtitle(trackId: String?)

    val positionMs: Long
    val durationMs: Long?
}

sealed interface PlayerState {
    data object Idle : PlayerState
    /** Waiting for first frame. [attempt] > 1 means the watchdog is reconnecting. */
    data class Loading(val spec: PlaybackSpec, val attempt: Int = 1) : PlayerState
    data class Playing(val spec: PlaybackSpec) : PlayerState
    data class Paused(val spec: PlaybackSpec) : PlayerState
    data class Buffering(val spec: PlaybackSpec) : PlayerState
    data class Ended(val spec: PlaybackSpec) : PlayerState
    data class Failed(val spec: PlaybackSpec?, val kind: FailureKind, val detail: String) : PlayerState
}

enum class FailureKind {
    /** 401/403 — wrong login, or the account hit max connections. */
    DENIED,
    /** 404/410 — stream gone. */
    NOT_FOUND,
    /** Network down / timeouts after retries. */
    NETWORK,
    /** Container/codec this engine can't play (Phase 4: fall back to libmpv). */
    UNSUPPORTED,
    OTHER,
}

data class TrackInfo(
    val audio: List<Track> = emptyList(),
    val subtitles: List<Track> = emptyList(),
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val videoFrameRate: Float = 0f,
)

/**
 * A selectable audio/subtitle track. [language] is the ISO 639 code the container carries (null when
 * the track has none); [forcedOnly] is true for a subtitle track that holds only forced (foreign-part)
 * lines. Both are what a remembered choice matches on — never the fragile group:track index.
 */
data class Track(
    val id: String,
    val label: String,
    val language: String?,
    val selected: Boolean,
    val forcedOnly: Boolean = false,
)

/** The language + forced-only role of the track currently selected for a type, read from the engine. */
data class SelectedTrackRole(val language: String?, val forcedOnly: Boolean)

data class PlaybackDiagnostics(
    val width: Int = 0,
    val height: Int = 0,
    val frameRate: Float = 0f,
    val pixelAspectRatio: Float = 1f,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val encodedBitrate: Int = 0,
    val bufferedMs: Long = 0,
    val deliveryMode: com.yodesla.omniverse.core.model.DeliveryMode = com.yodesla.omniverse.core.model.DeliveryMode.UNKNOWN,
)
