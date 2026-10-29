package com.yodesla.omniverse.core.data.metadata

import com.yodesla.omniverse.core.data.*
import com.yodesla.omniverse.core.model.ContentKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.time.LocalDate

@Serializable
/** [lang] = TMDB original_language when the feed records it (task 142: "ja" marks anime). */
data class DiscoveryTitle(val id: String, val kind: String, val date: String = "", val rank: Int = 0, val lang: String = "")
@Serializable
data class DiscoveryFeed(val at: Long = 0, val titles: List<DiscoveryTitle> = emptyList())
data class DiscoveryShelf(val id: String, val title: String, val posters: List<PosterRow>, val caption: String = "")

/** Public TMDB charts resolved ONLY by exact identity to playable, visible catalog copies. */
class DiscoveryRepository(
    private val tmdb: Tmdb?, private val user: UserDataRepository,
    private val catalog: CatalogRepository, private val clock: Clock,
    private val netflix: NetflixCharts? = null,
    private val sourceHealth: Flow<List<SourceHealthSnapshot>> = flowOf(emptyList()),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    fun feed(key: String): Flow<DiscoveryFeed> = user.setting("discovery_cache_$key").map { decode(it) }
    private fun decode(raw: String?) = runCatching { json.decodeFromString<DiscoveryFeed>(raw.orEmpty()) }.getOrDefault(DiscoveryFeed())
    private suspend fun cached(key: String) = feed(key).first()
    private suspend fun save(key: String, titles: List<DiscoveryTitle>) {
        // Empty/error responses never wipe a working cache.
        if (titles.isNotEmpty()) user.putSetting("discovery_cache_$key", json.encodeToString(DiscoveryFeed(clock.nowMs(), titles)))
    }
    suspend fun refresh(): Boolean = lock.withLock {
        if (tmdb == null) { user.putSetting("discovery_status", "Unavailable: this build has no TMDB key"); return@withLock false }
        var failed = false
        for (key in listOf("day", "week", "recent")) {
            try { save(key, load(key)) } catch (e: CancellationException) { throw e } catch (_: Exception) { failed = true }
        }
        if (netflix != null) try {
            val titles = netflix.load()
            save("netflix_official_movie", titles.filter { it.kind == "movie" })
            save("netflix_official_tv", titles.filter { it.kind == "tv" })
        } catch (e: CancellationException) { throw e } catch (_: Exception) { failed = true }
        // Refresh only service charts that have actually been visited.
        for (service in SERVICES.keys) for (kind in listOf("movie", "tv")) {
            val key = "service_${service}_$kind"
            if (cached(key).at == 0L) continue
            try { save(key, loadService(service, kind)) } catch (e: CancellationException) { throw e } catch (_: Exception) { failed = true }
        }
        user.putSetting("discovery_status", if (failed) "Some feeds unavailable; saved results kept" else "Updated · TMDB daily/weekly interest · US service availability")
        return@withLock !failed
    }
    private suspend fun load(key: String): List<DiscoveryTitle> {
        val out = ArrayList<DiscoveryTitle>()
        if (key == "recent") {
            val today = LocalDate.now()
            for (kind in listOf("movie", "tv")) for (page in 1..5) {
                val field = if (kind == "movie") "primary_release_date" else "first_air_date"
                val obj = tmdb!!.discovery("discover/$kind", "&page=$page&sort_by=$field.desc&$field.gte=${today.minusDays(90)}&$field.lte=$today&vote_count.gte=5&include_adult=false")
                out += parse(obj, kind)
            }
            return out.filter { it.date.isNotBlank() && it.date <= today.toString() }.sortedByDescending { it.date }.distinctBy { it.kind to it.id }
        }
        for (page in 1..5) out += parse(tmdb!!.discovery("trending/all/$key", "&page=$page"))
        return out.distinctBy { it.kind to it.id }
    }
    private suspend fun loadService(service: String, kind: String): List<DiscoveryTitle> {
        val wanted = SERVICES[service] ?: return emptyList()
        // Resolve current IDs from TMDB instead of baking changing provider IDs into the APK.
        val providers = tmdb!!.discovery("watch/providers/$kind", "&watch_region=US")["results"] as? JsonArray ?: return emptyList()
        val ids = providers.mapNotNull { e ->
            val p = e.jsonObject
            val name = p["provider_name"]?.jsonPrimitive?.content.orEmpty()
            p["provider_id"]?.jsonPrimitive?.content?.takeIf { wanted.any { n -> name.equals(n, true) } }
        }
        if (ids.isEmpty()) return emptyList()
        return (1..3).flatMap { page -> parse(tmdb.discovery("discover/$kind", "&page=$page&sort_by=popularity.desc&watch_region=US&with_watch_providers=${ids.joinToString("|")}&with_watch_monetization_types=flatrate&include_adult=false"), kind) }.distinctBy { it.kind to it.id }
    }
    suspend fun ensureService(service: String, kind: ContentKind) = lock.withLock {
        if (tmdb == null || AutoRefreshPolicy.parse(user.setting(AutoRefreshPolicy.DISCOVERY).first()).mode == "off") return@withLock
        val type = if (kind == ContentKind.VOD) "movie" else "tv"
        val key = "service_${service}_$type"
        if (cached(key).at != 0L) return@withLock
        val now = clock.nowMs()
        val last = user.setting("discovery_attempt_$key").first()?.toLongOrNull() ?: 0L
        if (last > 0 && now - last in 0 until 1_800_000L) return@withLock
        user.putSetting("discovery_attempt_$key", now.toString())
        try { save(key, loadService(service, type)) } catch (e: CancellationException) { throw e } catch (_: Exception) { /* next scheduled refresh can retry */ }
    }
    suspend fun resolve(feed: DiscoveryFeed, kind: ContentKind?, visible: (PosterRow) -> Boolean, limit: Int = 60): List<PosterRow> {
        val out = ArrayList<PosterRow>()
        for (title in feed.titles.distinctBy { it.kind to it.id }) {
            if (title.kind !in listOf("movie", "tv") || title.id.toIntOrNull()?.let { it > 0 } != true) continue
            val k = if (title.kind == "movie") ContentKind.VOD else ContentKind.SERIES
            if (kind != null && kind != k) continue
            val p = catalog.titleCandidates(TitleIdentity(k, "tmdb", title.id)).firstOrNull(visible) ?: continue
            out += p
            if (out.size >= limit) break
        }
        return out
    }
    private fun homeVisibility(visibility: Flow<Visibility>): Flow<Visibility> = combine(
        visibility, user.hiddenCategoryKeys(),
        catalog.recentlyAdded(ContentKind.VOD, 1), catalog.recentlyAdded(ContentKind.SERIES, 1)
    ) { vis, hidden, _, _ ->
        { kind: String, source: String, category: String -> "$kind|$source|$category" !in hidden && vis(kind, source, category) }
    }
    fun home(kind: ContentKind?, visibility: Flow<Visibility>): Flow<List<DiscoveryShelf>> = combine(feed("day"), feed("week"), feed("recent"), homeVisibility(visibility), sourceHealth) { day, week, recent, vis, health ->
        // A chart title may have several catalog copies. Skip sources that the recent health pass
        // confirmed cannot answer, then let exact-ID resolution pick a working alternative.
        val unavailable = health.filter { snapshot ->
            clock.nowMs() - snapshot.checkedAtMs in 0..SourceHealth.DAY &&
                (snapshot.lastErrorKind == SourceErrorKind.AUTH.name || snapshot.lastErrorKind == SourceErrorKind.NETWORK.name)
        }.mapTo(HashSet()) { it.sourceId }
        val visible: (PosterRow) -> Boolean = { p ->
            p.key.sourceId.value !in unavailable &&
                (p.categoryId?.let { vis(p.key.kind.name, p.key.sourceId.value, it.value) } ?: true)
        }
        val used = HashSet<TitleIdentity>()
        suspend fun row(id: String, title: String, f: DiscoveryFeed): DiscoveryShelf {
            // Daily wins, then weekly, then releases; fill each row AFTER excluding earlier rows.
            val candidates = resolve(f, kind, visible, 100)
            val picks = candidates.filter { it.titleIdentity() !in used }.take(20)
            used += picks.mapNotNull { it.titleIdentity() }
            val updated = java.time.Instant.ofEpochMilli(f.at).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            val basis = when (id) { "released" -> "Release dates · last 90 days"; "popularToday" -> "Daily audience interest"; else -> "Weekly audience interest" }
            return DiscoveryShelf(id, title, picks, "TMDB · $basis · Updated $updated")
        }
        val daily = row("popularToday", "Most Popular Today", day)
        val weekly = row("trendingWeek", "Trending This Week", week)
        val released = row("released", "Recently Released", recent)
        listOf(released, weekly, daily).filter { it.posters.isNotEmpty() }
    }
    companion object {
        val SERVICES = mapOf(
            "NETFLIX" to listOf("Netflix"), "DISNEY_PLUS" to listOf("Disney Plus"),
            "PRIME_VIDEO" to listOf("Amazon Prime Video"), "APPLE_TV_PLUS" to listOf("Apple TV Plus", "Apple TV"),
            "HULU" to listOf("Hulu"), "MAX" to listOf("HBO Max", "Max"),
            "PARAMOUNT_PLUS" to listOf("Paramount Plus", "Paramount Plus Premium", "Paramount Plus Essential"), "PEACOCK" to listOf("Peacock Premium", "Peacock Premium Plus"),
            "CRUNCHYROLL" to listOf("Crunchyroll"))
        internal fun parse(obj: JsonObject, forced: String? = null): List<DiscoveryTitle> = (obj["results"] as? JsonArray).orEmpty().mapNotNull { e ->
            val p = e as? JsonObject ?: return@mapNotNull null
            val type = forced ?: p["media_type"]?.jsonPrimitive?.content
            if (type !in listOf("movie", "tv") || p["adult"]?.jsonPrimitive?.booleanOrNull == true) return@mapNotNull null
            val id = p["id"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 } ?: return@mapNotNull null
            DiscoveryTitle(id.toString(), type!!, p[if (type == "movie") "release_date" else "first_air_date"]?.jsonPrimitive?.contentOrNull.orEmpty())
        }
    }
}
