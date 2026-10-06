package com.yodesla.omniverse.core.data

import com.yodesla.omniverse.core.model.SourceId
import com.yodesla.omniverse.core.source.SourceException
import kotlinx.coroutines.flow.Flow

/*
 * PLAN.md §6.1 — the heart of "no freeze-ups".
 *
 * STATE MACHINE (per source, stages run in this order, each skippable by scope/staleness):
 *   ACCOUNT → LIVE_CATEGORIES → LIVE_CHANNELS → EPG → VOD_CATEGORIES → VOD → SERIES_CATEGORIES → SERIES → FINALIZE
 *
 * Rules:
 *  - gen = source.sync_gen + 1 at start; every upserted row gets sync_gen = gen.
 *  - Rows are written in transactions of SyncPolicy.batchSize; the UI reads committed batches
 *    progressively (live list usable before VOD finishes).
 *  - A stage that fails with a retryable SourceException is reported (SyncProgress.error) and the
 *    engine MOVES ON to the next stage; AuthFailed/Expired abort the whole sync.
 *  - Only a stage that COMPLETED marks its missing rows removed (removed_ms) — a failed/partial
 *    stage never deletes anything. User-data tables are never touched.
 *  - FINALIZE: rebuild search_index rows for the synced kinds, purge rows removed > purgeAfterMs,
 *    prune programmes outside the EPG window, write last_sync_*_ms and sync_gen.
 *  - EPG stage: channelKeys = distinct non-null channel.epg_channel_id of this source;
 *    window = [now - max(catchupDays, epgPastHours), now + epgFutureDays].
 *  - One sync per source at a time: a second call while running joins/returns the running flow's
 *    progress (no duplicate work).
 *  - Cancellation (app backgrounded, user leaves) stops cleanly between batches.
 */

enum class SyncStage { ACCOUNT, LIVE_CATEGORIES, LIVE_CHANNELS, EPG, VOD_CATEGORIES, VOD, SERIES_CATEGORIES, SERIES, FINALIZE }

enum class SyncScope { ALL, LIVE_AND_EPG, EPG_ONLY, VOD_AND_SERIES }

data class SyncProgress(
    val sourceId: SourceId,
    val stage: SyncStage,
    /** Items written so far in this stage. */
    val done: Int,
    /** Items skipped as unreadable so far in this stage. */
    val skipped: Int = 0,
    /** Non-null when this stage failed (the engine continues with the next stage unless fatal). */
    val error: SourceException? = null,
    /** True on the single last event of the whole sync. */
    val finished: Boolean = false,
)

data class SyncPolicy(
    val liveMaxAgeMs: Long = 12 * HOUR,
    val vodMaxAgeMs: Long = 24 * HOUR,
    val epgMaxAgeMs: Long = 12 * HOUR,
    val epgPastHours: Int = 6,
    val epgFutureDays: Int = 3,
    val batchSize: Int = 2_000,
    val purgeRemovedAfterMs: Long = 7 * 24 * HOUR,
    /** A stage that fails with a network error is re-run this many times... */
    val stageRetries: Int = 2,
    /** ...waiting attempt × this long in between. */
    val stageRetryDelayMs: Long = 1_000,
) {
    companion object { const val HOUR = 3_600_000L }
}

interface SyncEngine {
    /**
     * Syncs [sourceId]. Stages whose data is fresher than [SyncPolicy] are skipped unless [force].
     * Cold flow; collect it to run. Emits progress at least every batch.
     */
    fun sync(sourceId: SourceId, scope: SyncScope = SyncScope.ALL, force: Boolean = false): Flow<SyncProgress>

    /** True if any stage in [scope] is older than policy (cheap DB read). */
    suspend fun isStale(sourceId: SourceId, scope: SyncScope = SyncScope.ALL): Boolean
}

fun interface Clock {
    fun nowMs(): Long
}
