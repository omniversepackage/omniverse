package com.yodesla.omniverse.core.model

/**
 * Task 91: a read-only snapshot of whatever is playing right now, published by the active player
 * into the app-level holder (AppGraph) at most every 1 s; null there when nothing plays. The phone
 * remote serves it as JSON so a phone can show a Now Playing card. Pure data — no player types.
 */
data class NowPlaying(
    val title: String,
    val posterUrl: String?,
    /** [ContentKind] name: VOD / SERIES / EPISODE / LIVE. */
    val kind: String,
    /** Playback position; 0 for live TV (live has no position). */
    val positionMs: Long,
    /** Total length; 0 when unknown or live. */
    val durationMs: Long,
    val isPlaying: Boolean,
    val isLive: Boolean,
    /** Channel name for live TV; null for VOD. */
    val channelName: String?,
    val audioTracks: List<NowPlayingTrack>,
    val selectedAudioId: String?,
    val subtitleTracks: List<NowPlayingTrack>,
    /** null = subtitles off. */
    val selectedSubtitleId: String?,
)

/** One selectable audio/subtitle track, flattened for the phone page. [id] is the engine's track id. */
data class NowPlayingTrack(val id: String, val label: String)

/**
 * Control hooks the active player registers with the holder while it plays (null when nothing
 * plays, so a phone request can never act on a closed player). Live players no-op the seek hooks.
 */
interface NowPlayingControls {
    fun seekTo(positionMs: Long)
    fun skipBy(deltaMs: Long)
    fun selectAudio(trackId: String)
    /** null = subtitles off. */
    fun selectSubtitle(trackId: String?)
}
