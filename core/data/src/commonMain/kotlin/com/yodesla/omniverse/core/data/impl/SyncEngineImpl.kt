package com.yodesla.omniverse.core.data.impl

import com.yodesla.omniverse.core.data.Clock
import com.yodesla.omniverse.core.data.SourceRepository
import com.yodesla.omniverse.core.data.SyncEngine
import com.yodesla.omniverse.core.data.SyncPolicy
import com.yodesla.omniverse.core.data.SyncProgress
import com.yodesla.omniverse.core.data.SyncScope
import com.yodesla.omniverse.core.data.SyncStage
import com.yodesla.omniverse.core.database.OmniverseDb
import com.yodesla.omniverse.core.model.AccountStatus
import com.yodesla.omniverse.core.model.Category
import com.yodesla.omniverse.core.model.ChannelRecord
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.ProgrammeRecord
import com.yodesla.omniverse.core.model.Redact
import com.yodesla.omniverse.core.model.SeriesRecord
import com.yodesla.omniverse.core.model.SourceId
import com.yodesla.omniverse.core.model.TimeWindow
import com.yodesla.omniverse.core.model.VodRecord
import com.yodesla.omniverse.core.source.SourceException
import com.yodesla.omniverse.core.source.SyncDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** PLAN.md §6.1 / Sync.kt header — the spec lives there; this follows it stage by stage. */
class SyncEngineImpl(
    private val db: OmniverseDb,
    private val sources: SourceRepository,
    private val clock: Clock,
    private val policy: SyncPolicy = SyncPolicy(),
    private val io: CoroutineDispatcher,
) : SyncEngine {

    private val registry = Mutex()
    private val locks = HashMap<SourceId, Mutex>()

    private suspend fun lockFor(id: SourceId) = registry.withLock { locks.getOrPut(id) { Mutex() } }

    override fun sync(sourceId: SourceId, scope: SyncScope, force: Boolean): Flow<SyncProgress> = channelFlow {
        val lock = lockFor(sourceId)
        if (!lock.tryLock()) {
            // Already syncing this source: don't duplicate work.
            send(SyncProgress(sourceId, SyncStage.FINALIZE, 0, finished = true))
            return@channelFlow
        }
        try {
            Run(this, sourceId, scope, force).execute()
        } finally {
            lock.unlock()
        }
    }.flowOn(io)

    override suspend fun isStale(sourceId: SourceId, scope: SyncScope): Boolean = withContext(io) {
        val row = db.storeQueries.sourceById(sourceId.value).executeAsOneOrNull() ?: return@withContext false
        val now = clock.nowMs()
        fun old(last: Long?, maxAge: Long) = last == null || now - last > maxAge
        val live = old(row.last_sync_live_ms, policy.liveMaxAgeMs)
        val vod = old(row.last_sync_vod_ms, policy.vodMaxAgeMs) || old(row.last_sync_series_ms, policy.vodMaxAgeMs)
        val epg = old(row.last_sync_epg_ms, policy.epgMaxAgeMs)
        when (scope) {
            SyncScope.ALL -> live || vod || epg
            SyncScope.LIVE_AND_EPG -> live || epg
            SyncScope.EPG_ONLY -> epg
            SyncScope.VOD_AND_SERIES -> vod
        }
    }

    private sealed interface Outcome {
        data object Ok : Outcome
        data object Failed : Outcome
        data object Fatal : Outcome
    }

    /** One sync run. Holds per-run state so the engine itself stays stateless. */
    private inner class Run(
        private val out: ProducerScope<SyncProgress>,
        private val sourceId: SourceId,
        private val scope: SyncScope,
        private val force: Boolean,
    ) {
        private val id = sourceId.value
        private val now = clock.nowMs()
        private val st = db.storeQueries
        private val cq = db.catalogQueries

        suspend fun execute() {
            val row = st.sourceById(id).executeAsOneOrNull()
            val source = sources.contentSource(sourceId)
            if (row == null || source == null) {
                out.send(SyncProgress(sourceId, SyncStage.ACCOUNT, 0, error = SourceException.BadResponse("Unknown source"), finished = true))
                return
            }

            // ACCOUNT — any failure here is fatal: nothing else can work without a valid login.
            out.send(SyncProgress(sourceId, SyncStage.ACCOUNT, 0))
            val fatal: SourceException? = try {
                val acc = source.accountInfo()
                when (acc.status) {
                    AccountStatus.AUTH_FAILED, AccountStatus.BANNED, AccountStatus.DISABLED -> SourceException.AuthFailed()
                    AccountStatus.EXPIRED -> SourceException.Expired()
                    else -> null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SourceException) {
                e
            } catch (e: Exception) {
                SourceException.BadResponse(Redact.text(e.message ?: e::class.simpleName.orEmpty()), e)
            }
            if (fatal != null) {
                out.send(SyncProgress(sourceId, SyncStage.ACCOUNT, 0, error = fatal, finished = true))
                return
            }
            out.send(SyncProgress(sourceId, SyncStage.ACCOUNT, 1))

            val gen = row.sync_gen + 1
            fun due(last: Long?, maxAge: Long) = force || last == null || now - last > maxAge
            val wantLive = scope == SyncScope.ALL || scope == SyncScope.LIVE_AND_EPG
            val wantEpg = scope != SyncScope.VOD_AND_SERIES
            val wantVod = scope == SyncScope.ALL || scope == SyncScope.VOD_AND_SERIES
            val doLive = wantLive && due(row.last_sync_live_ms, policy.liveMaxAgeMs)
            val doEpg = wantEpg && due(row.last_sync_epg_ms, policy.epgMaxAgeMs)
            val doVod = wantVod && due(row.last_sync_vod_ms, policy.vodMaxAgeMs)
            val doSeries = wantVod && due(row.last_sync_series_ms, policy.vodMaxAgeMs)

            var liveWritten = false; var vodWritten = false; var seriesWritten = false

            var liveDone = false
            var epgDone = false
            var vodDone = false
            var seriesDone = false

            if (doLive) {
                val cats = stage(SyncStage.LIVE_CATEGORIES, { source.liveCategories() }) { writeCategories(it, ContentKind.LIVE, gen) }
                if (cats == Outcome.Fatal) return finishFatal()
                if (cats == Outcome.Ok) st.markMissingCategoriesRemoved(now, id, ContentKind.LIVE.name, gen)
                val chans = stage(SyncStage.LIVE_CHANNELS, { source.liveChannels(it) }) { writeChannels(it, gen) }
                if (chans == Outcome.Fatal) return finishFatal()
                val chansTrusted = chans == Outcome.Ok && plausible(SyncStage.LIVE_CHANNELS, cq.countChannels(id).executeAsOne())
                if (chansTrusted) cq.markMissingChannelsRemoved(now = now, sourceId = id, gen = gen)
                liveDone = cats == Outcome.Ok && chansTrusted
                liveWritten = true // channels were stored (maybe partially): they must still be searchable
            }

            if (doEpg) {
                val keys = st.distinctEpgKeys(id).executeAsList().toSet()
                if (keys.isEmpty()) {
                    epgDone = true
                } else {
                    val catchupDays = st.maxCatchupDays(id).executeAsOneOrNull()?.max ?: 0L
                    val pastMs = maxOf(catchupDays * DAY, policy.epgPastHours * HOUR)
                    val window = TimeWindow(now - pastMs, now + policy.epgFutureDays * DAY)
                    val epg = stage(SyncStage.EPG, { source.epg(window, keys, it) }) { writeProgrammes(it) }
                    if (epg == Outcome.Fatal) return finishFatal()
                    if (epg == Outcome.Ok) st.pruneProgrammes(id, window.startMs, window.endMs)
                    epgDone = epg == Outcome.Ok
                }
            }

            if (doVod) {
                val cats = stage(SyncStage.VOD_CATEGORIES, { source.vodCategories() }) { writeCategories(it, ContentKind.VOD, gen) }
                if (cats == Outcome.Fatal) return finishFatal()
                if (cats == Outcome.Ok) st.markMissingCategoriesRemoved(now, id, ContentKind.VOD.name, gen)
                val items = stage(SyncStage.VOD, { source.vodItems(it) }) { writeVod(it, gen) }
                if (items == Outcome.Fatal) return finishFatal()
                val itemsTrusted = items == Outcome.Ok && plausible(SyncStage.VOD, db.readQueries.countVod(id, null).executeAsOne())
                if (itemsTrusted) st.markMissingVodRemoved(now = now, sourceId = id, gen = gen)
                vodDone = cats == Outcome.Ok && itemsTrusted
                vodWritten = true
            }

            if (doSeries) {
                val cats = stage(SyncStage.SERIES_CATEGORIES, { source.seriesCategories() }) { writeCategories(it, ContentKind.SERIES, gen) }
                if (cats == Outcome.Fatal) return finishFatal()
                if (cats == Outcome.Ok) st.markMissingCategoriesRemoved(now, id, ContentKind.SERIES.name, gen)
                val items = stage(SyncStage.SERIES, { source.series(it) }) { writeSeries(it, gen) }
                if (items == Outcome.Fatal) return finishFatal()
                val itemsTrusted = items == Outcome.Ok && plausible(SyncStage.SERIES, db.readQueries.countSeries(id, null).executeAsOne())
                if (itemsTrusted) st.markMissingSeriesRemoved(now = now, sourceId = id, gen = gen)
                seriesDone = cats == Outcome.Ok && itemsTrusted
                seriesWritten = true
            }

            // FINALIZE
            out.send(SyncProgress(sourceId, SyncStage.FINALIZE, 0))
            db.transaction {
                // Rebuild search for every kind whose rows were written this run, even when a stage was only
                // partially successful: a single bad record used to leave a provider's channels unsearchable.
                if (liveDone || liveWritten) { st.deleteSearchRows(id, ContentKind.LIVE.name); st.rebuildSearchLive(id) }
                if (vodDone || vodWritten) { st.deleteSearchRows(id, ContentKind.VOD.name); st.rebuildSearchVod(id) }
                if (seriesDone || seriesWritten) { st.deleteSearchRows(id, ContentKind.SERIES.name); st.rebuildSearchSeries(id) }
                st.purgeRemoved(id, now - policy.purgeRemovedAfterMs)
                st.updateSourceSync(
                    gen = gen,
                    liveMs = now.takeIf { liveDone },
                    vodMs = now.takeIf { vodDone },
                    seriesMs = now.takeIf { seriesDone },
                    epgMs = now.takeIf { epgDone },
                    id = id,
                )
            }
            out.send(SyncProgress(sourceId, SyncStage.FINALIZE, 1, finished = true))
        }

        /** Items the last stage wrote (set when a stage completes). */
        private var lastStageCount = 0

        /**
         * Safety valve before marking rows removed. A "successful" list that is empty (or collapses
         * by 90 %+) while we hold a real catalog is far more likely a provider hiccup (error page,
         * maintenance, expired session) than a real wipe: keep what we have, report it, retry later.
         * [liveCount] = rows currently not removed, INCLUDING the ones just upserted.
         */
        private suspend fun plausible(stage: SyncStage, liveCount: Long): Boolean {
            val fetched = lastStageCount.toLong()
            // Removals haven't run yet, so this still counts every row we held before this sync.
            val before = liveCount
            val suspicious = (fetched == 0L && before > 0L) || (before >= 200L && fetched * 10 < before)
            if (suspicious) {
                out.send(
                    SyncProgress(
                        sourceId, stage, lastStageCount,
                        error = SourceException.BadResponse("Provider returned $fetched items (had $before); kept the existing list"),
                    ),
                )
            }
            return !suspicious
        }

        private suspend fun finishFatal() {
            // The stage already reported the fatal error; close the run without touching timestamps.
            out.send(SyncProgress(sourceId, SyncStage.FINALIZE, 0, finished = true))
        }

        /** Collects [flowFactory] in batches; each batch is one transaction followed by a progress event. */
        private suspend fun <T> stage(
            stage: SyncStage,
            flowFactory: (SyncDiagnostics) -> Flow<T>,
            write: (List<T>) -> Unit,
        ): Outcome {
            var done = 0
            var skipped = 0
            val diag = object : SyncDiagnostics {
                override fun skipped(what: String, reason: String) { skipped++ }
            }
            val buffer = ArrayList<T>(policy.batchSize)
            suspend fun flush() {
                if (buffer.isEmpty()) return
                db.transaction { write(buffer) }
                done += buffer.size
                buffer.clear()
                out.send(SyncProgress(sourceId, stage, done, skipped))
                out.ensureActive()
            }
            out.send(SyncProgress(sourceId, stage, 0))
            lastStageCount = 0
            var attempt = 0
            while (true) {
                attempt++
                val error: Exception = try {
                    flowFactory(diag).collect { item ->
                        buffer += item
                        if (buffer.size >= policy.batchSize) flush()
                    }
                    flush()
                    lastStageCount = done
                    return Outcome.Ok
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e
                }
                // Flaky networks: re-run the whole stage (upserts are idempotent) before giving up.
                if (error is SourceException.Network && attempt <= policy.stageRetries) {
                    buffer.clear()
                    delay(policy.stageRetryDelayMs * attempt)
                    continue
                }
                // Keep rows received so far; they're valid. Nothing is marked removed for this stage.
                runCatching { flush() }
                val se = error as? SourceException
                    ?: SourceException.BadResponse(Redact.text(error.message ?: error::class.simpleName.orEmpty()), error)
                val isFatal = se is SourceException.AuthFailed || se is SourceException.Expired
                out.send(SyncProgress(sourceId, stage, done, skipped, error = se, finished = isFatal))
                return if (isFatal) Outcome.Fatal else Outcome.Failed
            }
        }

        private fun writeCategories(items: List<Category>, kind: ContentKind, gen: Long) {
            for (c in items) {
                st.upsertCategory(id, kind.name, c.remoteId.value, c.name, c.parentId?.value, c.sortIndex.toLong(), gen)
            }
        }

        private fun writeChannels(items: List<ChannelRecord>, gen: Long) {
            for (c in items) {
                val primary = c.categoryIds.firstOrNull()?.value ?: UNCATEGORIZED
                cq.upsertChannel(
                    id, c.remoteId.value, c.number?.toLong(), c.name, c.logoUrl, c.epgChannelId, primary,
                    c.catchupDays.toLong(), c.addedAtMs, c.sortIndex.toLong(), gen,
                )
                st.deleteChannelCategories(id, c.remoteId.value)
                if (c.categoryIds.isEmpty()) st.insertChannelCategory(id, c.remoteId.value, UNCATEGORIZED)
                for (cat in c.categoryIds) st.insertChannelCategory(id, c.remoteId.value, cat.value)
            }
        }

        private fun writeVod(items: List<VodRecord>, gen: Long) {
            for (v in items) {
                st.upsertVod(
                    id, v.remoteId.value, v.name, v.posterUrl, v.categoryIds.firstOrNull()?.value ?: UNCATEGORIZED,
                    v.rating?.toDouble(), v.year?.toLong(), v.addedAtMs, v.containerExt, v.tmdbId, v.sortIndex.toLong(), gen,
                    MatchKey.of(v.name, v.year),
                    v.genre,
                )
            }
        }

        private fun writeSeries(items: List<SeriesRecord>, gen: Long) {
            for (s in items) {
                st.upsertSeries(
                    id, s.remoteId.value, s.name, s.posterUrl, s.backdropUrls.firstOrNull(),
                    s.categoryIds.firstOrNull()?.value ?: UNCATEGORIZED, s.plot, s.genre, s.rating?.toDouble(),
                    s.year?.toLong(), s.lastModifiedMs, s.sortIndex.toLong(), gen,
                    MatchKey.of(s.name, s.year),
                    s.tmdbId,
                )
            }
        }

        private fun writeProgrammes(items: List<ProgrammeRecord>) {
            for (p in items) {
                st.deleteOverlappingProgrammes(id, p.channelKey, p.startMs, p.endMs)
                st.upsertProgramme(
                    id, p.channelKey, p.startMs, p.endMs, p.title, p.subtitle, p.description, p.category,
                    p.episodeNum, p.iconUrl, if (p.hasArchive) 1 else 0,
                )
            }
        }
    }

    private companion object {
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
        const val UNCATEGORIZED = "uncategorized"
    }
}
