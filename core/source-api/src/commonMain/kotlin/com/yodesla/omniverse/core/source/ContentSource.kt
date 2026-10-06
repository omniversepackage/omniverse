package com.yodesla.omniverse.core.source

import com.yodesla.omniverse.core.model.AccountInfo
import com.yodesla.omniverse.core.model.Capability
import com.yodesla.omniverse.core.model.Category
import com.yodesla.omniverse.core.model.ChannelRecord
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.PlaybackRequest
import com.yodesla.omniverse.core.model.PlaybackSpec
import com.yodesla.omniverse.core.model.ProgrammeRecord
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.SeriesDetail
import com.yodesla.omniverse.core.model.SeriesRecord
import com.yodesla.omniverse.core.model.SourceId
import com.yodesla.omniverse.core.model.SourceKind
import com.yodesla.omniverse.core.model.TimeWindow
import com.yodesla.omniverse.core.model.VodDetail
import com.yodesla.omniverse.core.model.VodRecord
import kotlinx.coroutines.flow.Flow

/**
 * One configured content provider (an Xtream account, an M3U URL, later a Jellyfin/Plex/Emby server).
 *
 * CONTRACT (PLAN.md §5.1, §6.1) — implementations MUST:
 *  - Stream large lists as cold [Flow]s, item by item. Never materialize a whole catalog in memory.
 *  - Parse leniently: a malformed item is skipped (and counted via [SyncDiagnostics]), never fatal.
 *  - Throw [SourceException] (never raw IO/parse exceptions) for failures that stop the whole call.
 *  - Be main-safe: do their own dispatching (IO) internally.
 *  - Never log credentials (use Redact).
 *  - Open no media connections: [playback] only BUILDS a spec; the player opens it.
 */
interface ContentSource {
    val id: SourceId
    val kind: SourceKind
    val capabilities: Set<Capability>

    /** Validates credentials and returns account state. Throws [SourceException.AuthFailed] on bad login. */
    suspend fun accountInfo(): AccountInfo

    fun liveCategories(): Flow<Category>
    fun liveChannels(diagnostics: SyncDiagnostics = SyncDiagnostics.None): Flow<ChannelRecord>

    fun vodCategories(): Flow<Category>
    fun vodItems(diagnostics: SyncDiagnostics = SyncDiagnostics.None): Flow<VodRecord>

    fun seriesCategories(): Flow<Category>
    fun series(diagnostics: SyncDiagnostics = SyncDiagnostics.None): Flow<SeriesRecord>

    suspend fun vodDetail(id: RemoteId): VodDetail
    suspend fun seriesDetail(id: RemoteId): SeriesDetail

    /**
     * Guide data for [window]. [channelKeys] limits output to channels we actually have
     * (null = everything the source offers). Order is not guaranteed.
     */
    fun epg(
        window: TimeWindow,
        channelKeys: Set<String>?,
        diagnostics: SyncDiagnostics = SyncDiagnostics.None,
    ): Flow<ProgrammeRecord>

    /** Short "now/next" guide for one channel, for channels missing from bulk EPG. */
    suspend fun shortEpg(channelId: RemoteId, limit: Int = 4): List<ProgrammeRecord>

    /**
     * Builds (does not open) the stream URL. Fast for Xtream (pure); an M3U source may need to
     * (re)load its playlist first, e.g. right after an app restart.
     */
    suspend fun playback(request: PlaybackRequest): PlaybackSpec

    /**
     * Player heartbeat for sources that track sessions (Plex kills a direct-play session it
     * thinks is paused, then answers 503 forever). [state] is "playing", "paused" or "stopped".
     * Best effort: must never throw. Default: nothing.
     */
    suspend fun reportPlayback(request: PlaybackRequest, state: String, positionMs: Long, durationMs: Long?) {}

    /**
     * Playback progress the provider already holds (e.g. Plex onDeck), so a title started in
     * another of the provider's apps also shows in Continue Watching. Best effort: must never
     * throw. Default: empty.
     */
    suspend fun remoteProgress(): List<RemoteProgress> = emptyList()

    /**
     * Tell the provider that we watched (or un-watched) [id], so its own apps agree with ours.
     * Best effort: must never throw. Default: nothing (sources without a watch-state API).
     */
    suspend fun setWatched(id: RemoteId, watched: Boolean) {}
}

/**
 * One "in progress" item reported by the provider itself. [kind] is VOD for a movie or EPISODE
 * for an episode; for episodes [parentId] is the show's id so Continue Watching can group it.
 * Times are epoch milliseconds.
 */
data class RemoteProgress(
    val id: RemoteId,
    val kind: ContentKind,
    val parentId: RemoteId?,
    val positionMs: Long,
    val durationMs: Long?,
    val lastViewedAtMs: Long,
)

/** Receives counts of skipped/repaired rows so sync can report "3 channels couldn't be read". */
interface SyncDiagnostics {
    fun skipped(what: String, reason: String)

    object None : SyncDiagnostics {
        override fun skipped(what: String, reason: String) = Unit
    }
}
