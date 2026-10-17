package com.yodesla.omniverse.core.data.metadata

import com.yodesla.omniverse.core.data.AutoRefreshPolicy
import com.yodesla.omniverse.core.data.Clock
import com.yodesla.omniverse.core.data.UserDataRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Task 142: TMDB art for one live channel — a colour title/brand logo and (for show channels) key art. */
@Serializable
data class ChannelArt(val logoUrl: String? = null, val backdropUrl: String? = null, val show: Boolean = false)

@Serializable
private data class ChannelArtCache(val at: Long = 0, val art: Map<String, ChannelArt> = emptyMap())

/**
 * Task 142 (Kory: "REAL trending, not made up"): the Anime hub's public feeds.
 *
 * Trending = TMDB's own trending charts (`trending/{tv,movie}/{day,week}`), kept only where TMDB
 * itself tags the title Animation (genre 16), ranked in TMDB's order. Titles reach the page only by
 * exact TMDB id, never a name guess. Channel art = TMDB's colour logos: a show channel ("Naruto",
 * "One Piece") gets that show's title logo and backdrop when the channel's core name EXACTLY matches
 * a TMDB series name; a brand channel ("Crunchyroll", "HIDIVE") gets the company logo on an exact
 * company-name match. Anything else keeps the provider's own logo. Cached; failures keep old data.
 */
class AnimeHubFeeds(
    private val tmdb: Tmdb?,
    private val user: UserDataRepository,
    private val clock: Clock,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()

    fun trending(window: String, type: String): Flow<DiscoveryFeed> =
        user.setting(key(window, type)).map { raw -> runCatching { json.decodeFromString<DiscoveryFeed>(raw.orEmpty()) }.getOrDefault(DiscoveryFeed()) }

    private fun key(window: String, type: String) = "anime_trending_${window}_$type"

    /** Fetch the four trending feeds when missing or older than [maxAgeMs]; Auto-refresh "Off" only fills empty caches. */
    suspend fun ensureTrending(maxAgeMs: Long = 6 * 3_600_000L) = lock.withLock {
        val t = tmdb ?: return@withLock
        val off = AutoRefreshPolicy.parse(user.setting(AutoRefreshPolicy.DISCOVERY).first()).mode == "off"
        for (window in WINDOWS) for (type in TYPES) {
            val cached = trending(window, type).first()
            val stale = cached.at == 0L || (!off && clock.nowMs() - cached.at > maxAgeMs)
            if (!stale) continue
            try {
                val pages = if (window == "week") 10 else 5
                val titles = (1..pages).flatMap { page -> animated(t.discovery("trending/$type/$window", "&page=$page"), type) }
                    .distinctBy { it.id }
                    .mapIndexed { i, d -> d.copy(rank = i + 1) }
                if (titles.isNotEmpty()) user.putSetting(key(window, type), json.encodeToString(DiscoveryFeed(clock.nowMs(), titles)))
            } catch (e: CancellationException) { throw e } catch (_: Exception) { /* saved feed stays */ }
        }
    }

    // ---------------------------------------------------------------- channel art

    fun channelArtCache(): Flow<Map<String, ChannelArt>> = user.setting(ART_KEY).map { decodeArt(it).art }
    private fun decodeArt(raw: String?) = runCatching { json.decodeFromString<ChannelArtCache>(raw.orEmpty()) }.getOrDefault(ChannelArtCache())

    /** Resolve art for channel core names not yet cached (misses are cached too, so each name costs one lookup). */
    suspend fun ensureChannelArt(coreNames: Collection<String>, maxLookups: Int = 80) = lock.withLock {
        val t = tmdb ?: return@withLock
        val cache = decodeArt(user.setting(ART_KEY).first())
        val todo = coreNames.map { it.trim() }.filter { it.isNotEmpty() && it.lowercase() !in cache.art }.distinctBy { it.lowercase() }.take(maxLookups)
        if (todo.isEmpty()) return@withLock
        val found = HashMap(cache.art)
        for (name in todo) {
            try { found[name.lowercase()] = lookup(t, name) } catch (e: CancellationException) { throw e } catch (_: Exception) { /* retry next visit */ }
        }
        user.putSetting(ART_KEY, json.encodeToString(ChannelArtCache(clock.nowMs(), found)))
    }

    private suspend fun lookup(t: Tmdb, name: String): ChannelArt {
        val wanted = normalize(name)
        val brand = BRANDS.firstOrNull { wanted.contains(normalize(it)) }
        // "Anime", "Anime All Day", "It's Anime": nothing specific to look up — the provider logo stays.
        if (brand == null && wanted.split(' ').all { it in GENERIC_WORDS }) return ChannelArt()
        if (brand == null) {
            // A show channel: the most popular TMDB series whose name is exactly the channel's core name.
            val results = (t.discovery("search/tv", "&query=" + java.net.URLEncoder.encode(name, "UTF-8"))["results"] as? JsonArray).orEmpty()
            val hit = results.mapNotNull { it as? JsonObject }.firstOrNull { r ->
                listOf("name", "original_name").any { f -> normalize(r[f]?.jsonPrimitive?.contentOrNull.orEmpty()) == wanted }
            }
            val id = hit?.get("id")?.jsonPrimitive?.intOrNull
            if (id != null) {
                val meta = t.tv(id.toString())
                if (meta.logoPath != null || meta.backdropPath != null) return ChannelArt(Tmdb.logoUrl(meta.logoPath), Tmdb.backdropUrl(meta.backdropPath), show = true)
            }
        }
        val company = brand ?: name
        val results = (t.discovery("search/company", "&query=" + java.net.URLEncoder.encode(company, "UTF-8"))["results"] as? JsonArray).orEmpty()
        val logo = results.mapNotNull { it as? JsonObject }
            .firstOrNull { normalize(it["name"]?.jsonPrimitive?.contentOrNull.orEmpty()) == normalize(company) && it["logo_path"]?.jsonPrimitive?.contentOrNull != null }
            ?.get("logo_path")?.jsonPrimitive?.contentOrNull
        return ChannelArt(Tmdb.logoUrl(logo))
    }

    companion object {
        private val GENERIC_WORDS = setOf("anime", "animes", "all", "day", "it", "its", "s", "tv", "movies", "movie", "classic", "classics", "cartoon", "cartoons", "kids", "the", "network", "x", "24", "7", "247", "one", "")
        val WINDOWS = listOf("day", "week")
        val TYPES = listOf("tv", "movie")
        private const val ART_KEY = "anime_channel_art_v1"
        /** Animation is TMDB genre 16 on both movies and TV. */
        const val ANIMATION_GENRE = 16
        /** Brand channels resolved to their company logo (an exact TMDB company name each). */
        val BRANDS = listOf("Crunchyroll", "HIDIVE", "Funimation", "Toonami", "Adult Swim", "Cartoon Network", "Boomerang", "Nicktoons", "Animax", "RetroCrush", "Aniplex")

        internal fun animated(obj: JsonObject, type: String): List<DiscoveryTitle> = (obj["results"] as? JsonArray).orEmpty().mapNotNull { e ->
            val p = e as? JsonObject ?: return@mapNotNull null
            val genres = (p["genre_ids"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.intOrNull }
            if (ANIMATION_GENRE !in genres) return@mapNotNull null
            if (p["adult"]?.jsonPrimitive?.contentOrNull == "true") return@mapNotNull null
            val id = p["id"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 } ?: return@mapNotNull null
            DiscoveryTitle(id.toString(), type, p[if (type == "movie") "release_date" else "first_air_date"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                lang = p["original_language"]?.jsonPrimitive?.contentOrNull.orEmpty())
        }

        /** Case/accent/punctuation-insensitive comparison key ("Pokémon" == "pokemon"). */
        fun normalize(s: String): String = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

        /**
         * A channel's core name for art lookup: provider/service prefixes ("US:", "Amazon US:",
         * "Xumo:"), FAST-service and quality words and "24/7" are dropped — "Amazon US: Naruto" →
         * "Naruto", "US: Pluto TV Anime" → "Anime".
         */
        fun channelCoreName(name: String): String {
            var s = name
            while (true) {
                val m = Regex("^\\s*[^:|]{1,24}[:|]\\s*").find(s) ?: break
                if (m.value.length >= s.length) break
                s = s.substring(m.value.length)
            }
            return s.replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " ")
                .replace(Regex("(?i)\\b(pluto tv|pluto|xumo|samsung tv plus|amazon|plex|roku|tubi|24/7|24x7|fhd|uhd|hd|4k|channel)\\b"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        }
    }
}
