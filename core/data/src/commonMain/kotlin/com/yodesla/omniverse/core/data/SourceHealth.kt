package com.yodesla.omniverse.core.data

import com.yodesla.omniverse.core.model.AccountInfo
import com.yodesla.omniverse.core.model.AccountStatus
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.SourceId
import com.yodesla.omniverse.core.source.ContentSource
import com.yodesla.omniverse.core.source.SourceException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/*
 * Task 105 — weekly source health check.
 *
 * One background pass per source asks the provider for its own account state (expiry, active or
 * expired, how many screens it allows), records when that source last synced successfully, and
 * compares today's item counts with the counts from the previous pass. Everything it learns lands
 * in one settings row (`src_health_all`), so Settings, the Home banner and the worker all read the
 * same recorded facts without a schema change.
 *
 * Trust rules: the snapshot carries counts, coarse failure KINDS and times only. No URL, host,
 * username, token or provider message is ever stored here or shown from here — the wording is
 * produced by the pure rules below, never by the provider.
 */

/** Tuning knobs for the health rules, in one place. */
object SourceHealth {
    const val DAY = 86_400_000L
    /** The weekly cadence: a check is due once the last one is this old. */
    const val CHECK_INTERVAL_MS = 7 * DAY
    /** "Expires in N days" starts counting at this many days left. */
    const val EXPIRY_WARN_DAYS = 7
    /** A source whose last successful sync is older than this has a sync problem. */
    const val STALE_SYNC_MS = 14 * DAY
    /** Smaller libraries never raise a drop warning (providers shuffle a handful of titles all the time). */
    const val DROP_MIN_PREV = 20L
    /** Percent of a library that has to vanish before it is called a drop. */
    const val DROP_MIN_PERCENT = 25
    /** Failed checks in a row before the source is described as "keeps failing". */
    const val REPEATED_FAILURES = 3
}

/** Settings keys. `snapshots` and `lastCheck` are device facts; `dismissed` follows the profile. */
object SourceHealthKeys {
    const val snapshots = "src_health_all"
    const val lastCheck = "src_health_last_check"
    const val dismissed = "src_alert_dismissed"
}

/** What one health pass learned about one source. Times are epoch milliseconds. */
@Serializable
data class SourceHealthSnapshot(
    val sourceId: String,
    /** The name the viewer gave the source — the only provider label ever displayed. */
    val sourceName: String,
    val checkedAtMs: Long,
    /** [com.yodesla.omniverse.core.model.AccountStatus] name, null when the provider never answered. */
    val accountStatus: String? = null,
    val expiresAtMs: Long? = null,
    val maxConnections: Int? = null,
    val activeConnections: Int? = null,
    /** Newest committed stage sync (last_sync_live/vod/epg_ms), null when it never synced. */
    val lastSuccessSyncMs: Long? = null,
    val movies: Long = 0,
    val shows: Long = 0,
    val channels: Long = 0,
    /** Counts from the previous pass, the baseline a drop is measured against. */
    val prevMovies: Long = 0,
    val prevShows: Long = 0,
    val prevChannels: Long = 0,
    /** False when this pass could not read counts: a drop is never measured against an unknown. */
    val countsKnown: Boolean = true,
    /** Failed checks in a row (reset by a clean one). */
    val failCount: Int = 0,
    /** [SourceErrorKind] name of the newest failure. */
    val lastErrorKind: String? = null,
)

/** One pass' raw input, before the rules turn it into warnings. */
data class SourceHealthObservation(
    val account: AccountInfo? = null,
    val counts: Map<ContentKind, Long> = emptyMap(),
    val lastSuccessSyncMs: Long? = null,
    val error: SourceErrorKind? = null,
)

enum class SourceWarningKind { EXPIRING, EXPIRED, LOGIN_FAILED, COUNT_DROP, NO_RECENT_SYNC, REPEATED_FAILURE }

enum class SourceWarningSeverity { WARNING, SEVERE }

/** One chip: what the viewer should be told about one source. */
data class SourceWarning(
    val sourceId: String,
    val sourceName: String,
    val kind: SourceWarningKind,
    val text: String,
    val severity: SourceWarningSeverity,
    /** Stable while the problem is the same problem; changes when it reappears or changes shape. */
    val fingerprint: String,
)

/** The one Home banner: the most serious source problem worth interrupting the viewer for. */
data class SourceAlert(
    val sourceId: String,
    val sourceName: String,
    val text: String,
    val fingerprint: String,
)

fun classifySourceError(e: Throwable): SourceErrorKind = when (e) {
    is SourceException.AuthFailed, is SourceException.Expired -> SourceErrorKind.AUTH
    is SourceException.Network -> SourceErrorKind.NETWORK
    else -> SourceErrorKind.OTHER
}

/** Whole days from [nowMs] to [atMs] (rounded up), negative once [atMs] has passed. */
fun daysUntil(nowMs: Long, atMs: Long): Long {
    val delta = atMs - nowMs
    return if (delta >= 0) (delta + SourceHealth.DAY - 1) / SourceHealth.DAY else -((-delta) / SourceHealth.DAY)
}

/**
 * The warning rules. Pure: same snapshot and clock always give the same chips, so every threshold
 * below is unit-tested. Order is severity first, then kind.
 */
fun sourceWarnings(s: SourceHealthSnapshot, nowMs: Long): List<SourceWarning> {
    val out = mutableListOf<SourceWarning>()
    fun add(kind: SourceWarningKind, text: String, severity: SourceWarningSeverity, bucket: Long) {
        out += SourceWarning(s.sourceId, s.sourceName, kind, text, severity, "${s.sourceId}|${kind.name}|$bucket")
    }

    val expiry = s.expiresAtMs
    if (expiry != null) {
        val days = daysUntil(nowMs, expiry)
        when {
            days <= 0 -> add(SourceWarningKind.EXPIRED, "Expired", SourceWarningSeverity.SEVERE, expiry / SourceHealth.DAY)
            days <= SourceHealth.EXPIRY_WARN_DAYS ->
                add(SourceWarningKind.EXPIRING, "Expires in $days day${if (days == 1L) "" else "s"}", SourceWarningSeverity.WARNING, expiry / SourceHealth.DAY)
        }
    }
    if (s.accountStatus == AccountStatus.EXPIRED.name || s.accountStatus == AccountStatus.BANNED.name ||
        s.accountStatus == AccountStatus.DISABLED.name
    ) {
        add(SourceWarningKind.EXPIRED, "Disabled by provider", SourceWarningSeverity.SEVERE, s.checkedAtMs / SourceHealth.DAY)
    }
    if (s.lastErrorKind == SourceErrorKind.AUTH.name) {
        add(SourceWarningKind.LOGIN_FAILED, "Login failed", SourceWarningSeverity.SEVERE, s.checkedAtMs / SourceHealth.DAY)
    }
    if (s.countsKnown) {
        dropWarning(s, "Movies", s.prevMovies, s.movies)?.let { (text, pct) ->
            add(SourceWarningKind.COUNT_DROP, text, if (pct >= 50) SourceWarningSeverity.SEVERE else SourceWarningSeverity.WARNING, s.checkedAtMs / SourceHealth.DAY)
        }
        dropWarning(s, "Shows", s.prevShows, s.shows)?.let { (text, pct) ->
            add(SourceWarningKind.COUNT_DROP, text, if (pct >= 50) SourceWarningSeverity.SEVERE else SourceWarningSeverity.WARNING, s.checkedAtMs / SourceHealth.DAY)
        }
        dropWarning(s, "Channels", s.prevChannels, s.channels)?.let { (text, pct) ->
            add(SourceWarningKind.COUNT_DROP, text, if (pct >= 50) SourceWarningSeverity.SEVERE else SourceWarningSeverity.WARNING, s.checkedAtMs / SourceHealth.DAY)
        }
    }
    val lastSync = s.lastSuccessSyncMs
    if (lastSync == null) {
        // A source that has never synced and is not failing is still loading, not unhealthy.
        if (s.failCount > 0 || s.movies + s.shows + s.channels > 0) {
            add(SourceWarningKind.NO_RECENT_SYNC, "Never synced successfully", SourceWarningSeverity.SEVERE, s.checkedAtMs / SourceHealth.DAY)
        }
    } else {
        val staleDays = (nowMs - lastSync) / SourceHealth.DAY
        if (nowMs - lastSync > SourceHealth.STALE_SYNC_MS) {
            add(SourceWarningKind.NO_RECENT_SYNC, "No successful sync in $staleDays days", SourceWarningSeverity.SEVERE, lastSync / SourceHealth.DAY)
        }
    }
    if (s.failCount >= SourceHealth.REPEATED_FAILURES) {
        add(
            SourceWarningKind.REPEATED_FAILURE,
            "Keeps failing (${s.failCount} checks in a row)",
            SourceWarningSeverity.SEVERE,
            s.checkedAtMs / SourceHealth.DAY,
        )
    }
    return out.sortedWith(compareByDescending<SourceWarning> { it.severity.ordinal }.thenBy { it.kind.ordinal })
}

private fun dropWarning(s: SourceHealthSnapshot, label: String, prev: Long, now: Long): Pair<String, Int>? {
    if (prev < SourceHealth.DROP_MIN_PREV) return null
    val dropped = prev - now
    if (dropped <= 0) return null
    val pct = ((dropped * 100 + prev / 2) / prev).toInt()
    if (pct < SourceHealth.DROP_MIN_PERCENT) return null
    return "$label dropped $pct%" to pct
}

/**
 * The banner rule: only an expiring/expired account or a source that keeps failing is worth a
 * one-time Home banner. A count drop stays a Settings chip.
 */
fun homeAlert(warnings: List<SourceWarning>): SourceAlert? {
    val bannerWorthy = warnings.filter {
        it.kind == SourceWarningKind.EXPIRING || it.kind == SourceWarningKind.EXPIRED ||
            it.kind == SourceWarningKind.LOGIN_FAILED || it.kind == SourceWarningKind.NO_RECENT_SYNC ||
            it.kind == SourceWarningKind.REPEATED_FAILURE
    }
    val top = bannerWorthy.maxByOrNull { it.severity.ordinal } ?: return null
    return SourceAlert(top.sourceId, top.sourceName, top.text, top.fingerprint)
}

/** A check is due when there is no recorded one yet or the recorded one is older than the interval. */
fun healthCheckDue(nowMs: Long, lastCheckMs: Long?, intervalMs: Long = SourceHealth.CHECK_INTERVAL_MS): Boolean =
    lastCheckMs == null || nowMs - lastCheckMs >= intervalMs

/**
 * The status store: snapshots in one settings row, the last check time in another, the banner
 * dismissal in a per-profile row (so dismissing on one profile does not silence another).
 */
class SourceHealthStore(private val userData: UserDataRepository, private val clock: Clock) {
    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(SourceHealthSnapshot.serializer())

    fun snapshots(): Flow<List<SourceHealthSnapshot>> = userData.setting(SourceHealthKeys.snapshots).map { decodeSnapshots(it) }

    fun warnings(): Flow<List<SourceWarning>> = snapshots().map { list -> list.flatMap { sourceWarnings(it, clock.nowMs()) } }

    fun dismissedFingerprint(): Flow<String?> = userData.setting(SourceHealthKeys.dismissed)

    /** The banner to show now: null when nothing is banner-worthy or this profile already dismissed it. */
    fun alert(): Flow<SourceAlert?> = combine(warnings(), dismissedFingerprint()) { warnings, dismissed ->
        homeAlert(warnings)?.takeIf { it.fingerprint != dismissed }
    }

    fun lastCheckedMs(): Flow<Long?> = userData.setting(SourceHealthKeys.lastCheck).map { it?.toLongOrNull() }

    suspend fun lastCheckedMsNow(): Long? = lastCheckedMs().first()

    suspend fun isDue(intervalMs: Long = SourceHealth.CHECK_INTERVAL_MS): Boolean =
        healthCheckDue(clock.nowMs(), lastCheckedMsNow(), intervalMs)

    /** Merge one pass into the stored snapshots (previous counts become the drop baseline). */
    suspend fun record(sourceId: SourceId, sourceName: String, observation: SourceHealthObservation) {
        val current = decodeSnapshots(userData.setting(SourceHealthKeys.snapshots).first())
        val old = current.firstOrNull { it.sourceId == sourceId.value }
        val countsKnown = observation.counts.isNotEmpty()
        val baselineKnown = old != null && old.countsKnown
        val next = SourceHealthSnapshot(
            sourceId = sourceId.value,
            sourceName = sourceName,
            checkedAtMs = clock.nowMs(),
            accountStatus = observation.account?.status?.name ?: old?.accountStatus,
            expiresAtMs = observation.account?.expiresAtMs ?: old?.expiresAtMs,
            maxConnections = observation.account?.maxConnections ?: old?.maxConnections,
            activeConnections = observation.account?.activeConnections ?: old?.activeConnections,
            lastSuccessSyncMs = observation.lastSuccessSyncMs ?: old?.lastSuccessSyncMs,
            movies = if (countsKnown) observation.counts[ContentKind.VOD] ?: 0L else old?.movies ?: 0L,
            shows = if (countsKnown) observation.counts[ContentKind.SERIES] ?: 0L else old?.shows ?: 0L,
            channels = if (countsKnown) observation.counts[ContentKind.LIVE] ?: 0L else old?.channels ?: 0L,
            prevMovies = if (countsKnown && baselineKnown) old?.movies ?: 0L else old?.prevMovies ?: 0L,
            prevShows = if (countsKnown && baselineKnown) old?.shows ?: 0L else old?.prevShows ?: 0L,
            prevChannels = if (countsKnown && baselineKnown) old?.channels ?: 0L else old?.prevChannels ?: 0L,
            countsKnown = countsKnown,
            failCount = if (observation.error == null) 0 else (old?.failCount ?: 0) + 1,
            lastErrorKind = observation.error?.name ?: old?.lastErrorKind,
        )
        writeSnapshots(current.filter { it.sourceId != sourceId.value } + next)
    }

    suspend fun markChecked(atMs: Long = clock.nowMs()) = userData.putSetting(SourceHealthKeys.lastCheck, atMs.toString())

    /** Snapshots of sources that no longer exist must not keep warning about nothing. */
    suspend fun pruneTo(ids: Collection<SourceId>) {
        val keep = ids.map { it.value }.toSet()
        val current = decodeSnapshots(userData.setting(SourceHealthKeys.snapshots).first())
        val kept = current.filter { it.sourceId in keep }
        if (kept.size != current.size) writeSnapshots(kept)
    }

    suspend fun dismissAlert(fingerprint: String) = userData.putSetting(SourceHealthKeys.dismissed, fingerprint)

    private suspend fun writeSnapshots(list: List<SourceHealthSnapshot>) {
        userData.putSetting(SourceHealthKeys.snapshots, json.encodeToString(listSerializer, list))
    }

    private fun decodeSnapshots(value: String?): List<SourceHealthSnapshot> =
        if (value.isNullOrEmpty()) emptyList() else runCatching { json.decodeFromString(listSerializer, value) }.getOrDefault(emptyList())
}

/**
 * Runs one health pass over every source. Network calls happen only through [contentSource], so the
 * whole pass is testable with a fake source; a source that fails to answer is recorded as a failure
 * (and counts toward "keeps failing") instead of aborting the pass.
 */
class SourceHealthProbe(
    private val store: SourceHealthStore,
    private val clock: Clock,
    private val sources: suspend () -> List<Pair<SourceId, String>>,
    private val contentSource: suspend (SourceId) -> ContentSource?,
    private val counts: suspend (SourceId) -> Map<ContentKind, Long>,
    private val lastSuccessSyncMs: suspend (SourceId) -> Long?,
) {
    /** Returns the warnings the pass produced. Never throws for provider failures. */
    suspend fun checkAll(): List<SourceWarning> {
        val ids = sources()
        for ((id, name) in ids) {
            val observation = try {
                val source = contentSource(id)
                SourceHealthObservation(
                    account = source?.accountInfo(),
                    counts = counts(id),
                    lastSuccessSyncMs = lastSuccessSyncMs(id),
                    error = if (source == null) SourceErrorKind.OTHER else null,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SourceHealthObservation(
                    counts = runCatching { counts(id) }.getOrDefault(emptyMap()),
                    lastSuccessSyncMs = runCatching { lastSuccessSyncMs(id) }.getOrNull(),
                    error = classifySourceError(e),
                )
            }
            store.record(id, name, observation)
        }
        store.pruneTo(ids.map { it.first })
        store.markChecked(clock.nowMs())
        return store.warnings().first()
    }
}
