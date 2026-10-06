package com.yodesla.omniverse.core.data.sports

import com.yodesla.omniverse.core.net.HttpClient
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class GameState { PRE, LIVE, FINAL }

data class GameTeam(
    val name: String,
    val shortName: String,
    val abbreviation: String,
    /** ARGB, from the feed (falls back to the local Teams table). */
    val color: Long?,
    val logoUrl: String?,
    val score: String?,
    val record: String?,
)

/** One scheduled game from the public schedule feed. */
data class ScheduledGame(
    val id: String,
    val league: String,
    val sport: Sport,
    val startMs: Long,
    val state: GameState,
    /** "8:15 PM", "Q3 5:12", "Final" — the feed's own short status. */
    val detail: String,
    val home: GameTeam,
    val away: GameTeam,
    /** TV networks/streamers carrying it ("Prime Video", "CBS", "ESPN+"). */
    val networks: List<String>,
    val venue: String?,
    /** Matchup graphic when the feed has one (may 404; image loaders fall back). */
    val imageUrl: String?,
    val headline: String?,
)

data class HighlightClip(
    val id: String,
    val gameId: String?,
    val teams: List<String>,
    val headline: String,
    val thumbnailUrl: String?,
    val videoUrl: String,
    val durationSec: Int?,
    val league: String,
    /** When the clip was published (the feed lists newest first; used to mix leagues). */
    val publishedMs: Long? = null,
)

/**
 * Read-only client for ESPN's public scoreboard + video feeds (no key, no account). Requests carry
 * only league/date parameters — nothing about the viewer or their providers. Results are cached
 * for [ttlMs] so browsing doesn't refetch. Any failure yields an empty list (the page falls back
 * to the viewer's own guide).
 */
class EspnSportsFeed(
    private val http: HttpClient,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val ttlMs: Long = 10 * 60_000L,
) {
    private data class League(val path: String, val label: String, val sport: Sport, val videoSport: String?, val videoLeague: String?, val weekly: Boolean = false)

    private val leagues = listOf(
        League("football/nfl", "NFL", Sport.FOOTBALL, "football", "nfl", weekly = true),
        League("football/college-football", "College Football", Sport.FOOTBALL, "football", "college-football", weekly = true),
        League("basketball/nba", "NBA", Sport.BASKETBALL, "basketball", "nba"),
        League("basketball/wnba", "WNBA", Sport.BASKETBALL, null, null),
        League("baseball/mlb", "MLB", Sport.BASEBALL, "baseball", "mlb"),
        League("hockey/nhl", "NHL", Sport.HOCKEY, "hockey", "nhl"),
        League("soccer/eng.1", "Premier League", Sport.SOCCER, null, null),
        League("soccer/usa.1", "MLS", Sport.SOCCER, null, null),
        League("soccer/uefa.champions", "Champions League", Sport.SOCCER, null, null),
        League("mma/ufc", "UFC", Sport.FIGHTING, null, null),
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private var gamesCache: Pair<Long, List<ScheduledGame>>? = null
    private val clipsCache = HashMap<String, Pair<Long, List<HighlightClip>>>()

    /**
     * [onPartial] receives the games gathered so far each time another league answers, so the page
     * can show the first league in a second or two instead of waiting for every request.
     */
    suspend fun games(days: Int = 7, onPartial: (List<ScheduledGame>) -> Unit = {}): List<ScheduledGame> = lock.withLock {
        gamesCache?.takeIf { clock() - it.first < ttlMs }?.let { return it.second }
        val now = clock()
        val today = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        // The feed rejects date ranges; the default view is the current week for NFL/college
        // football and today for daily leagues, so daily leagues also ask for the next few dates.
        val requests = leagues.flatMap { lg ->
            listOf(lg to null) + if (lg.weekly) emptyList() else (1..minOf(days, 3)).map { d -> lg to today.plusDays(d.toLong()).toString().replace("-", "") }
        }
        val gathered = java.util.concurrent.ConcurrentHashMap<String, ScheduledGame>()
        fun visible() = gathered.values.filter { it.state != GameState.FINAL || now - it.startMs < 5 * 3_600_000L }.sortedBy { it.startMs }
        // NFL first: it is what most people open this page for.
        val list = coroutineScope {
            requests.sortedBy { (lg, d) -> (if (lg.path == "football/nfl") 0 else 1) * 10 + (if (d == null) 0 else 1) }.map { (lg, date) ->
                async {
                    val got = runCatching { fetchLeague(lg, date) }.getOrDefault(emptyList())
                    if (got.isNotEmpty()) { got.forEach { gathered[it.id] = it }; onPartial(visible()) }
                    got
                }
            }.awaitAll().flatten()
        }.distinctBy { it.id }.filter { it.state != GameState.FINAL || now - it.startMs < 5 * 3_600_000L }.sortedBy { it.startMs }
        if (list.isNotEmpty()) gamesCache = now to list
        list
    }

    /**
     * Highlight clips. [league] (a label like "NBA") asks only that league's video feed; null mixes
     * every league that has one, newest first. Cached per league for [ttlMs]. Leagues ESPN has no
     * video feed for (soccer, UFC…) simply return nothing.
     */
    suspend fun clips(league: String? = null): List<HighlightClip> = lock.withLock {
        val key = league ?: "all"
        clipsCache[key]?.takeIf { clock() - it.first < ttlMs }?.let { return it.second }
        val list = coroutineScope {
            leagues.filter { it.videoSport != null && (league == null || it.label.equals(league, ignoreCase = true)) }
                .map { lg -> async { runCatching { fetchClips(lg) }.getOrDefault(emptyList()) } }.awaitAll().flatten()
        }.distinctBy { it.id }
        val ordered = if (league == null) list.sortedByDescending { it.publishedMs ?: Long.MIN_VALUE } else list
        if (ordered.isNotEmpty()) clipsCache[key] = clock() to ordered
        ordered
    }

    private var photosCache: Pair<Long, Map<Sport, List<String>>>? = null

    /**
     * Recent news photos per sport (16:9 where available), so every card can show a relevant
     * picture even when the guide has none and no clip mentions its teams. Refreshed hourly.
     */
    suspend fun photos(): Map<Sport, List<String>> = lock.withLock {
        photosCache?.takeIf { clock() - it.first < 3_600_000L }?.let { return it.second }
        val paths = listOf("football/nfl" to Sport.FOOTBALL, "football/college-football" to Sport.FOOTBALL, "basketball/nba" to Sport.BASKETBALL,
            "basketball/wnba" to Sport.BASKETBALL, "baseball/mlb" to Sport.BASEBALL, "hockey/nhl" to Sport.HOCKEY, "soccer/eng.1" to Sport.SOCCER,
            "soccer/uefa.champions" to Sport.SOCCER, "mma/ufc" to Sport.FIGHTING, "tennis/atp" to Sport.TENNIS, "racing/f1" to Sport.MOTORSPORT,
            "golf/pga" to Sport.GOLF)
        val got = coroutineScope {
            paths.map { (path, sport) ->
                async {
                    sport to runCatching {
                        val arts = get("https://site.api.espn.com/apis/site/v2/sports/$path/news?limit=20")?.jsonObject?.get("articles") as? JsonArray
                        arts.orEmpty().mapNotNull { a -> (a.jsonObject["images"] as? JsonArray)?.firstOrNull()?.jsonObject?.str("url") }
                            .filter { it.startsWith("https://") && "/stitcher/" !in it }
                    }.getOrDefault(emptyList())
                }
            }.awaitAll()
        }
        val map = got.groupBy({ it.first }, { it.second }).mapValues { (_, v) -> v.flatten().distinct() }.filterValues { it.isNotEmpty() }
        if (map.isNotEmpty()) photosCache = clock() to map
        map
    }

    private var teamsCache: Pair<Long, TeamDirectory>? = null

    /** Every team (name, colour, logo) for the major leagues; refreshed daily. */
    suspend fun teams(): TeamDirectory = lock.withLock {
        teamsCache?.takeIf { clock() - it.first < 24 * 3_600_000L }?.let { return it.second }
        val paths = listOf("football/nfl" to "NFL", "basketball/nba" to "NBA", "baseball/mlb" to "MLB", "hockey/nhl" to "NHL",
            "basketball/wnba" to "WNBA", "soccer/eng.1" to "Premier League", "soccer/usa.1" to "MLS", "soccer/esp.1" to "La Liga")
        val all = coroutineScope {
            paths.map { (path, label) ->
                async {
                    runCatching {
                        val root = get("https://site.api.espn.com/apis/site/v2/sports/$path/teams?limit=200")?.jsonObject
                        val teams = root?.get("sports")?.jsonArray?.firstOrNull()?.jsonObject?.get("leagues")?.jsonArray?.firstOrNull()?.jsonObject?.get("teams") as? JsonArray
                        teams.orEmpty().mapNotNull { el ->
                            val t = el.jsonObject.obj("team") ?: return@mapNotNull null
                            val name = t.str("displayName") ?: return@mapNotNull null
                            DirectoryTeam(label, GameTeam(name, t.str("shortDisplayName") ?: name, t.str("abbreviation") ?: "",
                                t.str("color")?.let(::hex), (t["logos"] as? JsonArray)?.firstOrNull()?.jsonObject?.str("href"), null, null),
                                listOfNotNull(name, t.str("shortDisplayName"), t.str("name"), t.str("nickname")).map { it.lowercase() }.distinct())
                        }
                    }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten()
        }
        val dir = TeamDirectory(all)
        if (all.isNotEmpty()) teamsCache = clock() to dir
        dir
    }

    private suspend fun get(url: String): JsonElement? = http.get(url, mapOf("Accept" to "application/json")).use { r ->
        if (r.status !in 200..299) return null
        json.parseToJsonElement(r.body.readUtf8())
    }

    private suspend fun fetchLeague(lg: League, date: String?): List<ScheduledGame> {
        val q = if (date == null) "limit=300" else "dates=$date&limit=300"
        val root = get("https://site.api.espn.com/apis/site/v2/sports/${lg.path}/scoreboard?$q")?.jsonObject ?: return emptyList()
        val events = root["events"] as? JsonArray ?: return emptyList()
        return events.mapNotNull { runCatching { parseEvent(it.jsonObject, lg) }.getOrNull() }
    }

    private fun parseEvent(e: JsonObject, lg: League): ScheduledGame? {
        val id = e.str("id") ?: return null
        val comp = (e["competitions"] as? JsonArray)?.firstOrNull()?.jsonObject ?: return null
        val start = (comp.str("date") ?: e.str("date"))?.let(::parseTime) ?: return null
        val type = comp.obj("status")?.obj("type")
        val state = when (type?.str("state")) { "in" -> GameState.LIVE; "post" -> GameState.FINAL; else -> GameState.PRE }
        val teams = (comp["competitors"] as? JsonArray)?.map { it.jsonObject } ?: return null
        fun team(side: String): GameTeam? {
            val c = teams.firstOrNull { it.str("homeAway") == side } ?: return null
            val t = c.obj("team") ?: c.obj("athlete") ?: return null
            val name = t.str("displayName") ?: return null
            return GameTeam(
                name = name, shortName = t.str("shortDisplayName") ?: t.str("name") ?: name,
                abbreviation = t.str("abbreviation") ?: name.take(3).uppercase(),
                color = t.str("color")?.let(::hex) ?: Teams.find(name)?.color,
                logoUrl = t.str("logo") ?: (t["logos"] as? JsonArray)?.firstOrNull()?.jsonObject?.str("href") ?: t.obj("flag")?.str("href"),
                score = c.str("score")?.takeIf { state != GameState.PRE },
                record = (c["records"] as? JsonArray)?.firstOrNull()?.jsonObject?.str("summary")?.takeIf { r -> r.any { it in '1'..'9' } }, // "0-0-0" preseason is noise
            )
        }
        val home = team("home") ?: return null
        val away = team("away") ?: return null
        val networks = buildList {
            (comp["broadcasts"] as? JsonArray)?.forEach { b -> (b.jsonObject["names"] as? JsonArray)?.forEach { n -> n.jsonPrimitive.contentOrNull?.let(::add) } }
            if (isEmpty()) (comp["geoBroadcasts"] as? JsonArray)?.forEach { g -> g.jsonObject.obj("media")?.str("shortName")?.let(::add) }
        }.distinct()
        val headline = (comp["headlines"] as? JsonArray)?.firstOrNull()?.jsonObject?.let { it.str("shortLinkText") ?: it.str("description") }
            ?: (comp["notes"] as? JsonArray)?.firstOrNull()?.jsonObject?.str("headline")
        return ScheduledGame(
            id = id, league = lg.label, sport = lg.sport, startMs = start, state = state,
            detail = type?.str("shortDetail") ?: "", home = home, away = away, networks = networks,
            venue = comp.obj("venue")?.str("fullName"),
            imageUrl = "https://s.espncdn.com/stitcher/sports/${lg.path.substringBefore('/')}/${lg.path.substringAfter('/')}/events/$id.png",
            headline = headline,
        )
    }

    private suspend fun fetchClips(lg: League): List<HighlightClip> {
        val root = get("https://now.core.api.espn.com/v1/sports/news?sport=${lg.videoSport}&league=${lg.videoLeague}&limit=40&type=video")?.jsonObject ?: return emptyList()
        val items = root["headlines"] as? JsonArray ?: return emptyList()
        return items.mapNotNull { el ->
            val x = el.jsonObject
            val v = (x["video"] as? JsonArray)?.firstOrNull()?.jsonObject ?: return@mapNotNull null
            val src = v.obj("links")?.obj("source")
            val url = src?.obj("HD")?.str("href") ?: src?.str("href") ?: src?.obj("full")?.str("href") ?: return@mapNotNull null
            if (!url.startsWith("https://") || !url.endsWith(".mp4")) return@mapNotNull null
            HighlightClip(
                id = x.str("id") ?: url, gameId = x.str("gameId"),
                teams = (x["categories"] as? JsonArray)?.mapNotNull { c -> c.jsonObject.takeIf { it.str("type") == "team" }?.str("description") }.orEmpty(),
                headline = x.str("headline") ?: v.str("headline") ?: return@mapNotNull null,
                thumbnailUrl = v.str("thumbnail") ?: (x["images"] as? JsonArray)?.firstOrNull()?.jsonObject?.str("url"),
                videoUrl = url, durationSec = v.str("duration")?.toIntOrNull(), league = lg.label,
                publishedMs = x.str("published")?.let { parseTime(it) },
            )
        }
    }

    companion object {
        internal fun parseTime(s: String): Long? = runCatching { java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { java.time.Instant.parse(s).toEpochMilli() }.getOrNull()
            ?: runCatching { java.time.OffsetDateTime.parse(s.replace("Z", ":00Z")).toInstant().toEpochMilli() }.getOrNull()

        private fun hex(s: String): Long? = s.removePrefix("#").takeIf { it.length == 6 }?.toLongOrNull(16)?.let { 0xFF000000L or it }

        private fun JsonObject.str(k: String): String? = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        private fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject
    }
}

/**
 * Finds the viewer's channels that carry a network ("Prime Video" → "US: Prime Video Sports 1").
 * US-labelled channels win over other regions' feeds of the same name.
 */
object NetworkMatcher {
    private val aliases: Map<String, List<String>> = mapOf(
        "prime video" to listOf("prime video", "amazon prime", "thursday night football", "tnf"),
        "nfl net" to listOf("nfl network", "nfl net"), "nfl network" to listOf("nfl network"),
        "espn2" to listOf("espn2", "espn 2"), "espn+" to listOf("espn+", "espn plus"), "espnu" to listOf("espnu"),
        "fs1" to listOf("fs1", "fox sports 1"), "fs2" to listOf("fs2", "fox sports 2"),
        "usa net" to listOf("usa network"), "nbcsn" to listOf("nbcsn", "nbc sports"),
        "peacock" to listOf("peacock"), "paramount+" to listOf("paramount"), "apple tv" to listOf("apple tv", "mls season pass"),
        "nba tv" to listOf("nba tv"), "mlb net" to listOf("mlb network"), "nhl net" to listOf("nhl network"),
        "tnt" to listOf("tnt"), "tbs" to listOf("tbs"), "trutv" to listOf("trutv", "tru tv"), "netflix" to listOf("netflix"),
    )

    /** Search terms for a network name, most specific first. */
    fun termsFor(network: String): List<String> {
        val n = network.trim().lowercase()
        return aliases[n] ?: listOf(n)
    }

    /** Higher is better; 0 means "doesn't actually carry it" (e.g. "Fox News" for "FOX"). */
    fun score(channelName: String, network: String): Int {
        val name = channelName.lowercase()
        val term = termsFor(network).firstOrNull { Regex("""(^|[^a-z0-9])${Regex.escape(it)}([^a-z0-9]|$)""").containsMatchIn(name) } ?: return 0
        if (network.equals("fox", true) && Regex("""fox (news|business|weather|soul|life)""").containsMatchIn(name)) return 0
        var s = 10
        if (Regex("""^(us|usa)\b|\b(us|usa)[:| ]""").containsMatchIn(name)) s += 6
        if (Regex("""^(es|mx|ar|br|pt|fr|de|it|uk|ca|au|nl|in|ar|latino|spanish|espanol|español)\b|\b(spanish|español|latino|deportes)\b""").containsMatchIn(name)) s -= 7
        if (name.startsWith(term)) s += 2
        if (Regex("""\b(4k|uhd|fhd|hd)\b""").containsMatchIn(name)) s += 1
        return s.coerceAtLeast(1)
    }
}


data class DirectoryTeam(val league: String, val team: GameTeam, val names: List<String>)

/**
 * Looks up a team from free text ("Cleveland Browns", "Browns", "Man United"?) — full names first,
 * then a unique short name/nickname. Ambiguous nicknames resolve to [leagueHint] when given.
 */
class TeamDirectory(private val teams: List<DirectoryTeam>) {
    private val full = teams.associateBy { it.team.name.lowercase() }

    fun find(text: String, leagueHint: String? = null): DirectoryTeam? {
        val q = text.lowercase().replace(Regex("""\(.*?\)|\[.*?]"""), " ").replace(Regex("""\s+"""), " ").trim()
        if (q.length < 2) return null
        full[q]?.let { return it }
        // Bare abbreviations ("MEM", "PIT") only when exactly one team (in the hinted league) uses it.
        if (q.length in 2..4 && !q.contains(' ')) {
            val ab = teams.filter { it.team.abbreviation.equals(q, ignoreCase = true) }
            (ab.firstOrNull { leagueHint != null && it.league.equals(leagueHint, true) } ?: ab.singleOrNull())?.let { return it }
        }
        val hits = teams.filter { t -> t.names.any { n -> n.length >= 3 && (q == n || Regex("""(^|\s)${Regex.escape(n)}($|\s)""").containsMatchIn(q)) } }
        if (hits.isEmpty()) return null
        return hits.firstOrNull { leagueHint != null && it.league.equals(leagueHint, true) } ?: hits.maxByOrNull { t -> t.names.maxOf { n -> if (n in q) n.length else 0 } }
    }

    val isEmpty get() = teams.isEmpty()
}
