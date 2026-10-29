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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    override suspend fun animeChannelCandidates(limit: Int): List<Pair<ChannelRow, String?>> = withContext(io) {
        db.readQueries.animeChannels(limit.toLong()).executeAsList().map { r ->
            ChannelRow(ContentKey(SourceId(r.source_id), ContentKind.LIVE, RemoteId(r.remote_id)), r.number?.toInt(), r.name, r.logo_url, r.epg_channel_id, r.catchup_days.toInt(), RemoteId(r.primary_category_id)) to r.category_name
        }
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

    override suspend fun searchLive(query: String, limit: Int): List<SearchHit> = searchLiveVisible(query, emptyList(), limit)

    override suspend fun searchLiveVisible(query: String, excludedCategoryKeys: Collection<String>, limit: Int): List<SearchHit> = withContext(io) {
        val match = toMatchExpression(query) ?: return@withContext emptyList()
        val needle = normalize(query)
        val fetch = (limit * 8L).coerceAtMost(400L)
        val hits = db.readQueries.searchLiveEligible(match, excludedCategoryKeys, fetch).executeAsList().mapNotNullTo(ArrayList()) { r ->
            val sid = r.source_id ?: return@mapNotNullTo null
            val rid = r.remote_id ?: return@mapNotNullTo null
            SearchHit(ContentKey(SourceId(sid), ContentKind.LIVE, RemoteId(rid)), r.title.orEmpty())
        }
        // Punctuation/accent-blind fallback ("yugioh" → "24/7: Yu-Gi-Oh!"), as for titles.
        val compact = com.yodesla.omniverse.core.data.search.FastTitleSearch.compact(query)
        if (hits.size < limit && compact.length >= 3) {
            val seen = hits.mapTo(HashSet()) { it.key }
            db.readQueries.searchLiveSubstringEligible(compact.take(2), compact, excludedCategoryKeys, fetch).executeAsList().forEach { r ->
                val key = ContentKey(SourceId(r.source_id), ContentKind.LIVE, RemoteId(r.remote_id))
                if (seen.add(key)) hits += SearchHit(key, r.name)
            }
        }
        hits.sortedWith(compareBy<SearchHit>({ rank(normalize(it.title), needle).let { r -> if (r > 0 && com.yodesla.omniverse.core.data.search.FastTitleSearch.compact(it.title).startsWith(compact)) 1 else r } }, { it.title.length }, { it.title }))
            .take(limit)
    }

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
        sort: com.yodesla.omniverse.core.data.SearchSort,
    ): Map<ContentKind, SearchFilteredPage> = withContext(io) {
        if (!filters.active && query.isBlank()) return@withContext emptyMap()
        val text = query.trim().takeIf { it.isNotEmpty() }
        val match = text?.let { value ->
            (listOf(value) + com.yodesla.omniverse.core.data.search.SmartTitleSearch.expansions(value))
                .mapNotNull(::toMatchExpression).distinct().joinToString(" OR ") { "($it)" }.takeIf { it.isNotBlank() }
        }
        val needle = text?.let { normalize(it) }
        val genresCsv = filters.genres.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("|").takeIf { it.isNotEmpty() }
        val selectedDecades = filters.decades.map { it.toLong() }
        // Blank query: fetch exactly limit+1 so `more` is exact. Text: over-fetch for Kotlin ranking
        // (FTS4 has no relevance), same as plain search; `more` then means "more in the window".
        val fetch = if (text == null || sort != com.yodesla.omniverse.core.data.SearchSort.RELEVANCE) limitPerKind + 1L else (limitPerKind * 8L).coerceAtMost(400L)
        val out = LinkedHashMap<ContentKind, SearchFilteredPage>()
        for (kind in filters.kinds) {
            val rows: List<PosterRow> = when (kind) {
                ContentKind.VOD -> db.readQueries.searchVodFiltered(
                    genresCsv = genresCsv, matchQuery = match, titleNeedle = text?.lowercase()?.takeIf { it.length >= 3 }, excludedKeys = excludedCategoryKeys,
                    animeOnly = if (filters.anime) 1L else null,
                    yearFrom = filters.yearFrom?.toLong(), yearTo = filters.yearTo?.toLong(),
                    decadesOff = if (selectedDecades.isEmpty()) 1L else 0L, selectedDecades = selectedDecades,
                    minRating = filters.ratingAtLeast?.toDouble(), limit = fetch, sortMode = sort.name,
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
                    genresCsv = genresCsv, matchQuery = match, titleNeedle = text?.lowercase()?.takeIf { it.length >= 3 }, excludedKeys = excludedCategoryKeys,
                    animeOnly = if (filters.anime) 1L else null,
                    yearFrom = filters.yearFrom?.toLong(), yearTo = filters.yearTo?.toLong(),
                    decadesOff = if (selectedDecades.isEmpty()) 1L else 0L, selectedDecades = selectedDecades,
                    minRating = filters.ratingAtLeast?.toDouble(), limit = fetch, sortMode = sort.name,
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
            val ranked = if (needle != null && sort == com.yodesla.omniverse.core.data.SearchSort.RELEVANCE) {
                rows.sortedWith(compareBy({ com.yodesla.omniverse.core.data.search.SmartTitleSearch.rank(it.name, needle) }, { it.name.length }, { it.name }))
            } else rows
            if (ranked.isNotEmpty()) out[kind] = SearchFilteredPage(ranked.take(limitPerKind), ranked.size > limitPerKind)
        }
        out
    }

    override suspend fun searchTitles(
        query: String,
        kinds: Collection<ContentKind>,
        excludedCategoryKeys: Collection<String>,
        limitPerKind: Int,
    ): Map<ContentKind, SearchFilteredPage> = titleSearch(query, kinds, excludedCategoryKeys, limitPerKind, true)

    override suspend fun searchTitlePrefixes(query: String, kinds: Collection<ContentKind>, excludedCategoryKeys: Collection<String>, limitPerKind: Int): Map<ContentKind, SearchFilteredPage> =
        titleSearch(query, kinds, excludedCategoryKeys, limitPerKind, false)

    private suspend fun titleSearch(query: String, kinds: Collection<ContentKind>, excludedCategoryKeys: Collection<String>, limitPerKind: Int, includeSubstring: Boolean): Map<ContentKind, SearchFilteredPage> = withContext(io) {
        val text = query.trim().takeIf { it.isNotEmpty() } ?: return@withContext emptyMap()
        val match = (listOf(text) + com.yodesla.omniverse.core.data.search.SmartTitleSearch.expansions(text))
            .mapNotNull(::toMatchExpression).distinct().joinToString(" OR ") { "($it)" }.takeIf { it.isNotBlank() }
            ?: return@withContext emptyMap()
        val needle = com.yodesla.omniverse.core.data.search.FastTitleSearch.normalize(text)
        val compact = com.yodesla.omniverse.core.data.search.FastTitleSearch.compact(text)
        val excluded = excludedCategoryKeys.toSet()
        val fetch = (limitPerKind * 12L).coerceIn(120L, 600L)
        val out = LinkedHashMap<ContentKind, SearchFilteredPage>()
        for (kind in kinds) {
            val isVod = kind == ContentKind.VOD
            fun hit(substring: Boolean, src: String, remote: String, name: String, poster: String?, cat: String, rating: Double?, year: Long?, tmdb: String?, group: String?, plex: Long, srcSort: Long, itemSort: Long) =
                com.yodesla.omniverse.core.data.search.TitleHit(
                    PosterRow(ContentKey(SourceId(src), kind, RemoteId(remote)), name, poster, year?.toInt(), rating?.toFloat(), categoryId = RemoteId(cat), tmdbId = tmdb),
                    group, plex.toInt(), srcSort, itemSort, substring,
                )
            val hits = ArrayList<com.yodesla.omniverse.core.data.search.TitleHit>()
            if (isVod) db.readQueries.searchVodFast(match, fetch).executeAsList().forEach { r -> hits += hit(false, r.source_id, r.remote_id, r.name, r.poster_url, r.primary_category_id, r.rating, r.year, r.tmdb_id, r.group_key, r.plex_rank, r.src_sort, r.item_sort) }
            else db.readQueries.searchSeriesFast(match, fetch).executeAsList().forEach { r -> hits += hit(false, r.source_id, r.remote_id, r.name, r.poster_url, r.primary_category_id, r.rating, r.year, r.tmdb_id, r.group_key, r.plex_rank, r.src_sort, r.item_sort) }
            // Mid-word / punctuation- and accent-blind matches ("ball" in "Dragonball", "yugioh" in
            // "Yu-Gi-Oh!", "pokemon" in "Pokémon"): only when the word-start pass came up short (a full
            // compacted-name scan costs ~3x the FTS pass, so it never runs on every keystroke).
            if (includeSubstring && hits.size < limitPerKind && compact.length >= 3) {
                val seen = hits.mapTo(HashSet()) { it.row.key.sourceId.value + ":" + it.row.key.remoteId.value }
                if (isVod) db.readQueries.searchVodSubstring(compact.take(2), compact, fetch).executeAsList().forEach { r -> if (seen.add(r.source_id + ":" + r.remote_id)) hits += hit(true, r.source_id, r.remote_id, r.name, r.poster_url, r.primary_category_id, r.rating, r.year, r.tmdb_id, r.group_key, r.plex_rank, r.src_sort, r.item_sort) }
                else db.readQueries.searchSeriesSubstring(compact.take(2), compact, fetch).executeAsList().forEach { r -> if (seen.add(r.source_id + ":" + r.remote_id)) hits += hit(true, r.source_id, r.remote_id, r.name, r.poster_url, r.primary_category_id, r.rating, r.year, r.tmdb_id, r.group_key, r.plex_rank, r.src_sort, r.item_sort) }
            }
            val (rows, more) = com.yodesla.omniverse.core.data.search.FastTitleSearch.rank(hits, text, excluded, kind.name, limitPerKind)
            if (rows.isNotEmpty()) out[kind] = SearchFilteredPage(rows, more)
        }
        out
    }

    override suspend fun decadeOptions(kinds: Collection<ContentKind>, excludedCategoryKeys: Collection<String>): List<Int> = withContext(io) {
        kinds.flatMap { kind ->
            db.readQueries.searchDecades(kind = kind.name, excludedKeys = excludedCategoryKeys).executeAsList()
        }.mapNotNull { it.decade?.toInt() }.distinct().sortedDescending()
    }

    override suspend fun searchProgrammes(query: String, nowMs: Long, windowMs: Long, limit: Int): List<ProgrammeHit> = searchProgrammesVisible(query, nowMs, emptyList(), windowMs, limit)

    override suspend fun searchProgrammesVisible(query: String, nowMs: Long, excludedCategoryKeys: Collection<String>, windowMs: Long, limit: Int): List<ProgrammeHit> = withContext(io) {
        // LIKE wildcards in user text are dropped rather than escaped (no ESCAPE clause needed).
        val needle = query.trim().replace("%", "").replace("_", "").takeIf { it.length >= 2 } ?: return@withContext emptyList()
        // Punctuation/accent-blind like titles and channels ("yugioh" → "Yu-Gi-Oh!").
        val compact = com.yodesla.omniverse.core.data.search.FastTitleSearch.compact(needle).takeIf { it.length >= 2 } ?: return@withContext emptyList()
        // Programmes that began up to 4 h ago and are still on count as "now"; longer ones are missed.
        val from = nowMs - GUIDE_LOOKBACK_MS
        val titles = guideTitles?.takeIf { nowMs - it.builtAt in 0 until GUIDE_TTL_MS && it.untilMs >= nowMs + windowMs }
        val rows = if (titles != null) {
            // Match in memory first: no matching title → no guide walk at all.
            val hit = titles.entries.filter { it.second.contains(compact) }.map { it.first }.take(GUIDE_MAX_TITLES)
            if (hit.isEmpty()) return@withContext emptyList()
            db.readQueries.searchProgrammesByTitlesEligible(from, nowMs + windowMs, nowMs, hit, excludedCategoryKeys, limit.toLong()).executeAsList()
                .map { r -> ProgrammeRow(r.title, r.start_ms, r.end_ms, r.source_id, r.remote_id, r.name, r.logo_url, r.primary_category_id) }
        } else {
            db.readQueries.searchProgrammesEligible(from, nowMs + windowMs, nowMs, compact.take(2), compact, excludedCategoryKeys, limit.toLong()).executeAsList()
                .map { r -> ProgrammeRow(r.title, r.start_ms, r.end_ms, r.source_id, r.remote_id, r.name, r.logo_url, r.primary_category_id) }
        }
        rows.map { r ->
            ProgrammeHit(
                channel = ContentKey(SourceId(r.source_id), ContentKind.LIVE, RemoteId(r.remote_id)),
                categoryId = RemoteId(r.primary_category_id), channelName = r.name, logoUrl = r.logo_url,
                title = r.title, startMs = r.start_ms, endMs = r.end_ms,
            )
        }
    }

    private class ProgrammeRow(val title: String, val start_ms: Long, val end_ms: Long, val source_id: String, val remote_id: String, val name: String, val logo_url: String?, val primary_category_id: String)
    /** Distinct guide titles for the search window with their compact forms. */
    private class GuideTitles(val builtAt: Long, val untilMs: Long, val entries: List<Pair<String, String>>)
    @Volatile private var guideTitles: GuideTitles? = null
    private val guideLock = Mutex()

    override suspend fun warmGuideSearch(nowMs: Long) = withContext(io) {
        guideLock.withLock {
            val g = guideTitles
            if (g != null && nowMs - g.builtAt in 0 until GUIDE_TTL_MS) return@withLock
            val until = nowMs + GUIDE_WINDOW_MS + GUIDE_TTL_MS
            val list = db.readQueries.guideTitlesWindow(nowMs - GUIDE_LOOKBACK_MS, until, nowMs).executeAsList()
            guideTitles = GuideTitles(nowMs, until, list.map { it to com.yodesla.omniverse.core.data.search.FastTitleSearch.compact(it) })
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
        private const val GUIDE_WINDOW_MS = 86_400_000L
        private const val GUIDE_TTL_MS = 15 * 60_000L
        private const val GUIDE_LOOKBACK_MS = 4 * 3_600_000L
        /** Bound on the SQL IN list (SQLite variable limit); broad two-letter queries use the first ones. */
        private const val GUIDE_MAX_TITLES = 400
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
