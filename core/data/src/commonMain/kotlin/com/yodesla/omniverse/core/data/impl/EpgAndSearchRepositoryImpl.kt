package com.yodesla.omniverse.core.data.impl

import com.yodesla.omniverse.core.data.EpgRepository
import com.yodesla.omniverse.core.data.ChannelRow
import com.yodesla.omniverse.core.data.sports.SportsAiring
import com.yodesla.omniverse.core.data.sports.SportsClassifier
import com.yodesla.omniverse.core.data.sports.SportsKind
import com.yodesla.omniverse.core.data.NowNext
import com.yodesla.omniverse.core.data.PosterRow
import com.yodesla.omniverse.core.data.SearchHit
import com.yodesla.omniverse.core.data.SearchFilteredPage
import com.yodesla.omniverse.core.data.SearchFilters
import com.yodesla.omniverse.core.data.ProgrammeHit
import com.yodesla.omniverse.core.data.SearchRepository
import com.yodesla.omniverse.core.database.OmniverseDb
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.ProgrammeRecord
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.SourceId
import com.yodesla.omniverse.core.model.TimeWindow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

class EpgRepositoryImpl(
    private val db: OmniverseDb,
    private val io: CoroutineDispatcher,
) : EpgRepository {

    override suspend fun nowNext(sourceId: SourceId, epgKeys: Collection<String>, atMs: Long): Map<String, NowNext> = withContext(io) {
        val keys = epgKeys.toSet()
        if (keys.isEmpty()) return@withContext emptyMap()
        val byKey = HashMap<String, MutableList<ProgrammeRecord>>(keys.size)
        // SQLite caps bound variables; 500 per query keeps well under every platform's limit.
        for (chunk in keys.chunked(500)) {
            db.readQueries.programmesForKeysBetween(sourceId.value, chunk, atMs, atMs + LOOKAHEAD_MS).executeAsList().forEach {
                byKey.getOrPut(it.channel_key) { ArrayList(4) }.add(it.toRecord())
            }
        }
        keys.associateWith { key ->
            val list = byKey[key].orEmpty() // ordered by start_ms
            val now = list.firstOrNull { it.startMs <= atMs && atMs < it.endMs }
            val next = list.firstOrNull { it.startMs >= (now?.endMs ?: atMs) && it !== now }
            NowNext(now, next)
        }
    }

    override suspend fun programmes(sourceId: SourceId, epgKey: String, window: TimeWindow): List<ProgrammeRecord> = withContext(io) {
        db.catalogQueries.programmesForChannel(sourceId.value, epgKey, window.startMs, window.endMs).executeAsList().map { it.toRecord() }
    }

    override suspend fun maxProgrammeEnd(sourceId: SourceId): Long = withContext(io) {
        db.readQueries.maxProgrammeEnd(sourceId.value).executeAsOne()
    }

    override suspend fun sportsSchedule(nowMs: Long, windowMs: Long, limit: Int): List<SportsAiring> = withContext(io) {
        // Fast path: find the sports channels first (small), then read only THEIR programmes through
        // the (channel_key, start_ms) primary key. Scanning every programme for sports words took
        // tens of seconds on big IPTV guides. The online schedule covers games on general channels.
        val horizon = nowMs + minOf(windowMs, 24 * 3_600_000L)
        val fromGuide = ArrayList<SportsAiring>()
        val chans = db.readQueries.sportsChannels(400).executeAsList().filter { !it.epg_channel_id.isNullOrBlank() }
        for ((src, rows) in chans.groupBy { it.source_id }) {
            val byEpg = rows.groupBy { it.epg_channel_id!! }
            for (chunk in byEpg.keys.chunked(400)) {
                for (p in db.readQueries.programmesForKeysBetween(src, chunk, nowMs, horizon).executeAsList()) {
                    for (r in byEpg[p.channel_key].orEmpty()) {
                        if (SportsClassifier.isIdleChannel(r.name)) continue
                        val c = SportsClassifier.classify(p.title, p.description, p.category, r.name, r.category_name) ?: continue
                        fromGuide += SportsAiring(
                            channel = ContentKey(SourceId(r.source_id), ContentKind.LIVE, RemoteId(r.remote_id)),
                            categoryId = RemoteId(r.primary_category_id), channelName = r.name, logoUrl = r.logo_url,
                            title = p.title, description = p.description, startMs = p.start_ms, endMs = p.end_ms,
                            sport = c.sport, kind = c.kind, teams = c.teams, league = c.league,
                            imageUrl = p.icon_url?.takeIf { it.startsWith("http", ignoreCase = true) },
                        )
                    }
                }
            }
            if (fromGuide.size >= limit) break
        }
        // Event channels whose guide didn't already yield a game: the channel name is the listing.
        val covered = fromGuide.filter { it.kind == SportsKind.EVENT }.mapTo(HashSet()) { it.channel }
        val fromNames = db.readQueries.sportsEventChannels(200).executeAsList().mapNotNull { r ->
            val key = ContentKey(SourceId(r.source_id), ContentKind.LIVE, RemoteId(r.remote_id))
            if (key in covered || SportsClassifier.isIdleChannel(r.name)) return@mapNotNull null
            val c = SportsClassifier.classifyEventChannel(r.name, r.category_name) ?: return@mapNotNull null
            val start = SportsClassifier.clockMinutes(r.name)?.let { min -> todayAt(nowMs, min) } ?: nowMs
            val end = start + EVENT_LENGTH_MS
            if (end <= nowMs || start > nowMs + windowMs) return@mapNotNull null
            SportsAiring(
                channel = key, categoryId = RemoteId(r.primary_category_id), channelName = r.name, logoUrl = r.logo_url,
                title = "${c.teams!!.first} vs ${c.teams.second}", description = null, startMs = start, endMs = end,
                sport = c.sport, kind = SportsKind.EVENT, teams = c.teams, league = c.league,
            )
        }
        (fromGuide + fromNames).sortedBy { it.startMs }
    }

    override suspend fun channelsNamed(term: String, limit: Int): List<ChannelRow> = withContext(io) {
        val t = term.trim().replace("%", "").replace("_", "").takeIf { it.length >= 2 } ?: return@withContext emptyList()
        db.readQueries.channelsNameLike("%$t%", limit.toLong()).executeAsList().map { r ->
            ChannelRow(ContentKey(SourceId(r.source_id), ContentKind.LIVE, RemoteId(r.remote_id)), r.number?.toInt(), r.name, r.logo_url, r.epg_channel_id, r.catchup_days.toInt(), RemoteId(r.primary_category_id))
        }
    }

    /** [minutes] after local midnight of [nowMs]'s day. */
    private fun todayAt(nowMs: Long, minutes: Int): Long {
        val tz = java.util.TimeZone.getDefault()
        val local = nowMs + tz.getOffset(nowMs)
        val midnight = local - Math.floorMod(local, 86_400_000L) - tz.getOffset(nowMs)
        return midnight + minutes * 60_000L
    }

    override suspend fun sportsChannels(limit: Int): List<ChannelRow> = withContext(io) {
        db.readQueries.sportsChannels(limit.toLong()).executeAsList().map { r ->
            ChannelRow(ContentKey(SourceId(r.source_id), ContentKind.LIVE, RemoteId(r.remote_id)), null, r.name, r.logo_url, r.epg_channel_id, 0, RemoteId(r.primary_category_id))
        }
    }

    private companion object {
        const val LOOKAHEAD_MS = 12 * 60 * 60_000L
        /** Typical game length when only a start time is known (event channels). */
        const val EVENT_LENGTH_MS = 3 * 60 * 60_000L + 30 * 60_000L
    }
}

class SearchRepositoryImpl(
    private val db: OmniverseDb,
    private val io: CoroutineDispatcher,
) : SearchRepository {

    override suspend fun search(query: String, limitPerKind: Int): Map<ContentKind, List<SearchHit>> = withContext(io) {
        val match = toMatchExpression(query) ?: return@withContext emptyMap()
        val out = LinkedHashMap<ContentKind, List<SearchHit>>()
        val needle = normalize(query)
        for (kind in listOf(ContentKind.LIVE, ContentKind.VOD, ContentKind.SERIES)) {
            // FTS4 has no relevance ranking: over-fetch, then rank in Kotlin.
            val fetch = (limitPerKind * 8L).coerceAtMost(400L)
            val hits = db.readQueries.searchByKind(match, kind.name, fetch).executeAsList().mapNotNull { r ->
                val sid = r.source_id ?: return@mapNotNull null
                val rid = r.remote_id ?: return@mapNotNull null
                SearchHit(ContentKey(SourceId(sid), kind, RemoteId(rid)), r.title.orEmpty())
            }
                .sortedWith(compareBy<SearchHit>({ rank(normalize(it.title), needle) }, { it.title.length }, { it.title }))
                .take(limitPerKind)
            if (hits.isNotEmpty()) out[kind] = hits
        }
        out
    }

    override suspend fun searchFiltered(
        query: String,
        filters: SearchFilters,
        excludedCategoryKeys: Collection<String>,
        limitPerKind: Int,
    ): Map<ContentKind, SearchFilteredPage> = withContext(io) {
        if (!filters.active) return@withContext emptyMap()
        val text = query.trim().takeIf { it.isNotEmpty() }
        val match = text?.let { toMatchExpression(it) }
        val needle = text?.let { normalize(it) }
        val genresCsv = filters.genres.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("|").takeIf { it.isNotEmpty() }
        // Blank query: fetch exactly limit+1 so `more` is exact. Text: over-fetch for Kotlin ranking
        // (FTS4 has no relevance), same as plain search; `more` then means "more in the window".
        val fetch = if (text == null) limitPerKind + 1L else (limitPerKind * 8L).coerceAtMost(400L)
        val out = LinkedHashMap<ContentKind, SearchFilteredPage>()
        for (kind in filters.kinds) {
            val rows: List<PosterRow> = when (kind) {
                ContentKind.VOD -> db.readQueries.searchVodFiltered(
                    genresCsv = genresCsv, matchQuery = match, excludedKeys = excludedCategoryKeys,
                    animeOnly = if (filters.anime) 1L else null,
                    yearFrom = filters.yearFrom?.toLong(), yearTo = filters.yearTo?.toLong(),
                    minRating = filters.ratingAtLeast?.toDouble(), limit = fetch,
                ).executeAsList().map { r ->
                    PosterRow(
                        key = ContentKey(SourceId(r.source_id), kind, RemoteId(r.remote_id)),
                        name = r.name,
                        posterUrl = r.poster_url,
                        year = r.year?.toInt(),
                        rating = r.rating?.toFloat(),
                        categoryId = RemoteId(r.primary_category_id),
                        tmdbId = r.tmdb_id,
                    )
                }
                else -> db.readQueries.searchSeriesFiltered(
                    genresCsv = genresCsv, matchQuery = match, excludedKeys = excludedCategoryKeys,
                    animeOnly = if (filters.anime) 1L else null,
                    yearFrom = filters.yearFrom?.toLong(), yearTo = filters.yearTo?.toLong(),
                    minRating = filters.ratingAtLeast?.toDouble(), limit = fetch,
                ).executeAsList().map { r ->
                    PosterRow(
                        key = ContentKey(SourceId(r.source_id), kind, RemoteId(r.remote_id)),
                        name = r.name,
                        posterUrl = r.poster_url,
                        year = r.year?.toInt(),
                        rating = r.rating?.toFloat(),
                        categoryId = RemoteId(r.primary_category_id),
                        tmdbId = r.tmdb_id,
                    )
                }
            }
            val ranked = if (needle != null) {
                rows.sortedWith(compareBy({ rank(normalize(it.name), needle) }, { it.name.length }, { it.name }))
            } else rows
            if (ranked.isNotEmpty()) out[kind] = SearchFilteredPage(ranked.take(limitPerKind), ranked.size > limitPerKind)
        }
        out
    }

    override suspend fun decadeOptions(kinds: Collection<ContentKind>, excludedCategoryKeys: Collection<String>): List<Int> = withContext(io) {
        kinds.flatMap { kind ->
            db.readQueries.searchDecades(kind = kind.name, excludedKeys = excludedCategoryKeys).executeAsList()
        }.mapNotNull { it.decade?.toInt() }.distinct().sortedDescending()
    }

    override suspend fun searchProgrammes(query: String, nowMs: Long, windowMs: Long, limit: Int): List<ProgrammeHit> = withContext(io) {
        // LIKE wildcards in user text are dropped rather than escaped (no ESCAPE clause needed).
        val needle = query.trim().replace("%", "").replace("_", "").takeIf { it.length >= 2 } ?: return@withContext emptyList()
        db.readQueries.searchProgrammes(nowMs, nowMs + windowMs, "%$needle%", limit.toLong()).executeAsList().map { r ->
            ProgrammeHit(
                channel = ContentKey(SourceId(r.source_id), ContentKind.LIVE, RemoteId(r.remote_id)),
                categoryId = RemoteId(r.primary_category_id), channelName = r.name, logoUrl = r.logo_url,
                title = r.title, startMs = r.start_ms, endMs = r.end_ms,
            )
        }
    }

    override suspend fun recentTitles(limit: Int): List<PosterRow> = withContext(io) {
        db.readQueries.recentTitles(limit.toLong()).executeAsList().map { r ->
            val kind = if (r.kind == ContentKind.SERIES.name) ContentKind.SERIES else ContentKind.VOD
            PosterRow(
                key = ContentKey(SourceId(r.source_id), kind, RemoteId(r.remote_id)),
                name = r.name,
                posterUrl = r.poster_url,
                year = r.year?.toInt(),
                rating = r.rating?.toFloat(),
                categoryId = RemoteId(r.primary_category_id),
                tmdbId = r.tmdb_id,
            )
        }
    }

    companion object {
        private val nonWord = Regex("[^\\p{L}\\p{N}]+")

        private fun normalize(s: String) = s.lowercase().split(nonWord).filter { it.isNotEmpty() }.joinToString(" ")

        /** 0 = exact title, 1 = title starts with the query, 2 = a word starts with it, 3 = other match. */
        private fun rank(title: String, needle: String): Int = when {
            title == needle -> 0
            title.startsWith(needle) -> 1
            title.contains(" $needle") -> 2
            else -> 3
        }

        /**
         * User text → safe FTS4 expression: only letter/digit tokens, each as a prefix term, AND-ed.
         * Raw user text never reaches MATCH (no operators, quotes, NEAR, column filters).
         */
        fun toMatchExpression(query: String): String? {
            val tokens = query.lowercase()
                .split(Regex("[^\\p{L}\\p{N}]+"))
                .filter { it.isNotEmpty() }
                .take(6)
            if (tokens.isEmpty()) return null
            return tokens.joinToString(" ") { "$it*" }
        }
    }
}
