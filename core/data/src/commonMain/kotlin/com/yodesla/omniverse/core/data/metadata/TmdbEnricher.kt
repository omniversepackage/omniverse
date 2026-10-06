package com.yodesla.omniverse.core.data.metadata

import com.yodesla.omniverse.core.data.Clock
import com.yodesla.omniverse.core.data.PosterRow
import com.yodesla.omniverse.core.database.OmniverseDb
import com.yodesla.omniverse.core.model.ContentKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * TMDB artwork/details enrichment with a local cache (task 84d).
 *
 * Cache policy (tmdb_meta.fetched_ms): a row with any content column set is fresh for 30 days;
 * an all-null row (miss or failed attempt) is retried after 1 day, so a failing endpoint is never
 * hammered by repeated screen opens. Season stills are permanent once stored; an empty/failed
 * season keeps a 1-day in-process backoff (the table has no timestamp column for it).
 *
 * Disabled entirely when the build has no API key ([tmdb] == null) or the Settings › Metadata
 * switch is off ([enabled]) — in both cases not a single request goes out. Only the public TMDB
 * id is ever sent.
 */
class TmdbEnricher(
    private val db: OmniverseDb,
    private val clock: Clock,
    private val tmdb: Tmdb?,
    private val enabled: suspend () -> Boolean = { true },
    private val io: CoroutineDispatcher = Dispatchers.Default,
) {
    companion object {
        const val FRESH_MS = 30L * 86_400_000L
        const val RETRY_MS = 86_400_000L

        /** Task 87b: shortest gap between two [artUpdated] signals, whatever the batch size. */
        const val ART_SIGNAL_DEBOUNCE_MS = 1_500L
    }

    /** "tmdbId:season" → last attempt ms; keeps empty/failed seasons quiet for a day. */
    private val seasonAttempts = mutableMapOf<String, Long>()

    /**
     * Task 87b: bumped whenever a lookup stored new art, at most once per [ART_SIGNAL_DEBOUNCE_MS].
     * Browse/brand pages re-map their visible rows on this, so art cached by the prefetch that ran
     * under the first paint appears within a second or two — without leaving the page and without
     * restarting paging (no scroll or focus reset).
     */
    private val _artUpdated = MutableStateFlow(0L)
    val artUpdated: StateFlow<Long> = _artUpdated.asStateFlow()
    private var lastArtSignalMs: Long? = null

    private fun signalArtUpdated(nowMs: Long) {
        val last = lastArtSignalMs
        if (last != null && nowMs - last < ART_SIGNAL_DEBOUNCE_MS) return
        lastArtSignalMs = nowMs
        _artUpdated.update { it + 1 }
    }

    /** Cached-first details for one exact TMDB id; null when unknown, off, or the lookup failed. */
    suspend fun meta(kind: WikidataMetadata.Kind, tmdbId: String?): TmdbMeta? {
        val id = tmdbId?.trim()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: return null
        val client = tmdb ?: return null
        if (!enabled()) return null
        val now = clock.nowMs()
        val cached = withContext(io) { db.tmdbQueries.tmdbMeta(id, kind.name).executeAsOneOrNull() }
        if (cached != null && isFresh(cached.fetched_ms, cached.hasContent(), now)) return cached.toMeta().takeIf { !it.isEmpty }
        val found = runCatching { if (kind == WikidataMetadata.Kind.MOVIE) client.movie(id) else client.tv(id) }.getOrNull()
        withContext(io) {
            if (found != null) {
                db.tmdbQueries.upsertTmdbMeta(
                    tmdbId = id, kind = kind.name, backdropPath = found.backdropPath, logoPath = found.logoPath,
                    posterPath = found.posterPath, certification = found.certification, runtimeMin = found.runtimeMin?.toLong(),
                    genres = found.genres, tagline = found.tagline, fetchedMs = now, overview = found.overview,
                    cardBackdropPath = found.cardBackdropPath,
                )
            } else {
                db.tmdbQueries.insertTmdbMetaMiss(tmdbId = id, kind = kind.name, fetchedMs = now)
                db.tmdbQueries.touchTmdbMeta(tmdbId = id, kind = kind.name, fetchedMs = now)
            }
        }
        if (found != null && !found.isEmpty) signalArtUpdated(now)
        return found
    }

    /** Cached-first episode stills for one season; empty when unknown, off, or the lookup failed. */
    suspend fun seasonStills(tmdbId: String?, season: Int): List<TmdbEpisodeStill> {
        val id = tmdbId?.trim()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: return emptyList()
        val client = tmdb ?: return emptyList()
        if (!enabled()) return emptyList()
        val stored = withContext(io) { db.tmdbQueries.tmdbEpisodeStills(id, season.toLong()).executeAsList() }
        if (stored.isNotEmpty()) return stored.map { TmdbEpisodeStill(it.episode.toInt(), it.still_path, it.name, it.overview) }
        val key = "$id:$season"
        val now = clock.nowMs()
        val last = seasonAttempts[key]
        if (last != null && last > now - RETRY_MS) return emptyList()
        seasonAttempts[key] = now
        val found = runCatching { client.season(id, season) }.getOrNull() ?: return emptyList()
        if (found.isEmpty()) return emptyList()
        withContext(io) {
            db.tmdbQueries.transaction {
                db.tmdbQueries.deleteTmdbEpisodes(id, season.toLong())
                found.forEach {
                    db.tmdbQueries.upsertTmdbEpisode(id, season.toLong(), it.episode.toLong(), it.stillPath, it.name, it.overview)
                }
            }
        }
        return found
    }

    /** Warm the cache for on-screen titles. The client's own limiter caps the network at 4 in flight. */
    suspend fun prefetch(kind: WikidataMetadata.Kind, ids: Collection<String>) {
        if (tmdb == null || !enabled()) return
        coroutineScope { ids.map { id -> launch { meta(kind, id) } } }
    }

    private fun isFresh(fetchedMs: Long, hasContent: Boolean, now: Long): Boolean =
        fetchedMs >= now - if (hasContent) FRESH_MS else RETRY_MS

    private fun com.yodesla.omniverse.core.database.Tmdb_meta.hasContent(): Boolean =
        backdrop_path != null || logo_path != null || poster_path != null ||
            certification != null || runtime_min != null || genres != null || tagline != null ||
            overview != null || card_backdrop_path != null

    private fun com.yodesla.omniverse.core.database.Tmdb_meta.toMeta() = TmdbMeta(
        backdropPath = backdrop_path, logoPath = logo_path, posterPath = poster_path,
        certification = certification, runtimeMin = runtime_min?.toInt(), genres = genres, tagline = tagline,
        overview = overview,
        cardBackdropPath = card_backdrop_path,
    )
}

/**
 * Plot precedence, used everywhere a synopsis is shown (task 84f): the provider's own plot first,
 * then the cached TMDB overview, then the opt-in Wikidata/Wikipedia summary (whose attribution
 * caption is only owed when that summary is what actually appears). A blank string counts as
 * absent, because IPTV providers routinely send `""` instead of nothing.
 */
fun pickPlot(source: String?, tmdbOverview: String?, wikidataSummary: String? = null): String? =
    source?.takeIf { it.isNotBlank() }
        ?: tmdbOverview?.takeIf { it.isNotBlank() }
        ?: wikidataSummary?.takeIf { it.isNotBlank() }

/**
 * Read-only TMDB art and synopsis for catalog rows (tasks 84d, 84f): fills gaps in source art from
 * the local cache only — it never makes a request, so browse screens stay fast and the Metadata
 * switch (which gates network) never leaves already-cached screens blank.
 *
 * Precedence: source/Plex art first; TMDB fills gaps. The title logo is the one thing only TMDB
 * supplies, so it is shown whenever cached. The plot follows [pickPlot]: the provider's own plot
 * wins, the cached TMDB overview is the fallback (most IPTV movies carry no plot at all).
 */
class TmdbArt(private val db: OmniverseDb) {
    fun fill(row: PosterRow): PosterRow {
        val kind = row.tmdbKind() ?: return row
        val cached = db.tmdbQueries.tmdbMeta(row.tmdbId!!, kind).executeAsOneOrNull() ?: return row
        return row.withArt(
            cached.backdrop_path?.let(Tmdb::backdropUrl),
            cached.logo_path?.let(Tmdb::logoUrl),
            cached.certification,
            cached.runtime_min?.toInt(),
            cached.overview,
            cached.card_backdrop_path?.let(Tmdb::backdropUrl),
        )
    }

    /** Batch variant for whole shelves: one query for the page's TMDB ids. */
    fun fillAll(rows: List<PosterRow>): List<PosterRow> {
        val movies = rows.filter { it.tmdbKind() == "MOVIE" }.mapNotNull { it.tmdbId }.distinct()
        val series = rows.filter { it.tmdbKind() == "SERIES" }.mapNotNull { it.tmdbId }.distinct()
        if (movies.isEmpty() && series.isEmpty()) return rows
        val byId = HashMap<String, TmdbArtRow>()
        if (movies.isNotEmpty()) db.readQueries.tmdbMetaForIds("MOVIE", movies).executeAsList().forEach { byId["MOVIE:${it.tmdb_id}"] = TmdbArtRow(it.backdrop_path, it.logo_path, it.certification, it.runtime_min?.toInt(), it.overview, it.card_backdrop_path) }
        if (series.isNotEmpty()) db.readQueries.tmdbMetaForIds("SERIES", series).executeAsList().forEach { byId["SERIES:${it.tmdb_id}"] = TmdbArtRow(it.backdrop_path, it.logo_path, it.certification, it.runtime_min?.toInt(), it.overview, it.card_backdrop_path) }
        return rows.map { row ->
            val kind = row.tmdbKind() ?: return@map row
            val art = byId["$kind:${row.tmdbId}"] ?: return@map row
            row.withArt(art.backdrop?.let(Tmdb::backdropUrl), art.logo?.let(Tmdb::logoUrl), art.certification, art.runtimeMin, art.overview, art.cardBackdrop?.let(Tmdb::backdropUrl))
        }
    }

    private data class TmdbArtRow(
        val backdrop: String?, val logo: String?, val certification: String?, val runtimeMin: Int?, val overview: String?,
        val cardBackdrop: String?,
    )
}

private fun PosterRow.tmdbKind(): String? {
    val kind = when (key.kind) {
        ContentKind.VOD -> "MOVIE"
        ContentKind.SERIES -> "SERIES"
        else -> return null
    }
    return if (tmdbId.isNullOrBlank()) null else kind
}

internal fun PosterRow.withArt(
    backdrop: String?, logo: String?, certification: String?, runtimeMin: Int?, overview: String?, cardBackdrop: String?,
): PosterRow {
    val wide = backdropUrl ?: backdrop
    // Task 87b: card art is a different picture from the banner art - a duplicate is dropped so the
    // card falls back to the banner with the title logo overlaid instead.
    return copy(
        backdropUrl = wide,
        cardBackdropUrl = cardBackdropUrl ?: cardBackdrop?.takeIf { it != wide },
        logoUrl = logoUrl ?: logo,
        certification = this.certification ?: certification,
        runtimeMin = this.runtimeMin ?: runtimeMin,
        plot = pickPlot(plot, overview),
    )
}
