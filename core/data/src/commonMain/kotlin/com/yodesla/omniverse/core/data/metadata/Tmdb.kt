package com.yodesla.omniverse.core.data.metadata

import com.yodesla.omniverse.core.net.HttpClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Cached TMDB details for one title. Every field is optional; paths, not URLs. */
data class TmdbMeta(
    val backdropPath: String?,
    val logoPath: String?,
    val posterPath: String?,
    val certification: String?,
    val runtimeMin: Int?,
    val genres: String?,
    val tagline: String?,
    /** TMDB plot summary (task 84f); the synopsis fallback when the provider supplies none. */
    val overview: String?,
    /** Task 87b: a DIFFERENT backdrop for landscape cards; null when the title has only one. */
    val cardBackdropPath: String? = null,
) {
    val isEmpty: Boolean
        get() = backdropPath == null && logoPath == null && posterPath == null &&
            certification == null && runtimeMin == null && genres == null && tagline == null &&
            overview == null && cardBackdropPath == null
}

/** One episode's TMDB still (used only when the source has no episode thumbnail). */
data class TmdbEpisodeStill(
    val episode: Int,
    val stillPath: String?,
    val name: String?,
    val overview: String?,
)

/**
 * Read-only TMDB client (task 84d). Sends ONLY a public TMDB id — no titles, provider URLs, or
 * accounts. Rate-limited to TMDB's published ceiling: at most 4 requests in flight and 20 per
 * second, on a background dispatcher. Any non-2xx answer throws [TmdbException]; the enricher
 * turns that into a 1-day cache backoff so a screen never hammers a failing endpoint.
 */
class Tmdb(
    private val http: HttpClient,
    private val apiKey: String,
    private val apiBase: String = "https://api.themoviedb.org/3",
    private val io: CoroutineDispatcher = Dispatchers.Default,
) {
    private val inFlight = Semaphore(MAX_IN_FLIGHT)
    private val rateLock = Mutex()
    private var windowStartMs = 0L
    private var windowCount = 0

    suspend fun movie(id: String): TmdbMeta = withContext(io) {
        val json = fetch("$apiBase/movie/$id?api_key=$apiKey&append_to_response=images,release_dates&include_image_language=en,null")
        parseMeta(json, usCertification(json["release_dates"] as? JsonObject))
    }

    suspend fun tv(id: String): TmdbMeta = withContext(io) {
        val json = fetch("$apiBase/tv/$id?api_key=$apiKey&append_to_response=images,content_ratings&include_image_language=en,null")
        parseMeta(json, usContentCertification(json["content_ratings"] as? JsonObject))
    }

    suspend fun season(tvId: String, season: Int): List<TmdbEpisodeStill> = withContext(io) {
        val json = fetch("$apiBase/tv/$tvId/season/$season?api_key=$apiKey")
        parseSeason(json)
    }

    private suspend fun fetch(url: String): JsonObject = inFlight.withPermit {
        acquireRateSlot()
        http.get(url, HEADERS).use { r ->
            if (r.status !in 200..299) throw TmdbException(r.status)
            Json.parseToJsonElement(r.body.readUtf8()).jsonObject
        }
    }

    /** Sliding one-second window: never more than 20 requests start inside any second. */
    private suspend fun acquireRateSlot() = rateLock.withLock {
        var now = System.currentTimeMillis()
        if (now - windowStartMs >= 1000L) { windowStartMs = now; windowCount = 0 }
        if (windowCount >= MAX_PER_SECOND) {
            delay(1000L - (now - windowStartMs))
            windowStartMs = System.currentTimeMillis()
            windowCount = 0
        }
        windowCount++
    }

    class TmdbException(val status: Int) : Exception("TMDB returned HTTP $status")

    companion object {
        internal const val MAX_IN_FLIGHT = 4
        internal const val MAX_PER_SECOND = 20
        private val HEADERS = mapOf("Accept" to "application/json")

        /** TMDB image URL from a stored path; sizes follow the task's per-kind choices. */
        fun imageUrl(path: String?, size: String): String? =
            path?.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/$size$it" }

        fun backdropUrl(path: String?): String? = imageUrl(path, "w1280")
        fun logoUrl(path: String?): String? = imageUrl(path, "w500")
        fun posterUrl(path: String?): String? = imageUrl(path, "w342")
        fun stillUrl(path: String?): String? = imageUrl(path, "w300")

        /**
         * Best backdrop: textless entries (iso_639_1 == null) first, English ones only if there
         * are none; 16:9 only; highest vote_average wins (ties break toward the bigger file).
         */
        internal fun pickBackdrop(images: JsonArray?): String? =
            bestOf(images, langs = listOf(null, "en"), requireWide = true, preferPng = false)

        /**
         * Card backdrop (task 87b, Kory: "the art for the banner and the movie preview shouldn't be
         * the same"): a second backdrop for landscape cards, NEVER the banner's own path. English
         * entries — which carry the title treatment — come first, then the next-best textless one.
         * Null when the title has no other wide backdrop; the card then reuses the banner art with
         * the title logo overlaid.
         */
        internal fun pickCardBackdrop(images: JsonArray?, bannerPath: String?): String? {
            val others = images?.mapNotNull { it as? JsonObject }
                ?.filter { it.stringOrNull("file_path") != null && it.stringOrNull("file_path") != bannerPath }
                ?: return null
            return bestOf(JsonArray(others), langs = listOf("en", null), requireWide = true, preferPng = false)
        }

        /** Best title logo: English only, PNG/SVG only, PNG preferred; highest vote_average. */
        internal fun pickLogo(images: JsonArray?): String? =
            bestOf(images, langs = listOf("en"), requireWide = false, preferPng = true)

        /** Best poster (used only when the title has none at all). */
        internal fun pickPoster(images: JsonArray?): String? =
            bestOf(images, langs = listOf(null, "en"), requireWide = false, preferPng = false)

        private fun bestOf(images: JsonArray?, langs: List<String?>, requireWide: Boolean, preferPng: Boolean): String? {
            val all = images?.mapNotNull { it as? JsonObject } ?: return null
            // Language preference applies among the usable (wide) entries only, so a tall textless
            // image cannot block a wide English one.
            val byLang = langs.firstNotNullOfOrNull { lang ->
                val subset = all.filter { it.stringOrNull("iso_639_1") == lang }
                (if (requireWide) subsetWide(subset) else subset).ifEmpty { null }
            } ?: return null
            val ranked = if (preferPng) {
                val pngs = byLang.filter { it.stringOrNull("file_path")?.endsWith(".png") == true }
                pngs.ifEmpty { byLang.filter { it.stringOrNull("file_path")?.endsWith(".svg") == true } }.ifEmpty { byLang }
            } else byLang
            return ranked.maxWithOrNull(
                compareBy<JsonObject> { it.doubleOrNull2("vote_average") ?: 0.0 }.thenBy { it.longOrNull2("width") ?: 0L },
            )?.stringOrNull("file_path")
        }

        private fun subsetWide(items: List<JsonObject>): List<JsonObject> =
            items.filter { val ar = it.doubleOrNull2("aspect_ratio"); ar != null && ar >= 1.5 }

        /** US certification from movie release_dates: theatrical (type 3) then digital (type 4). */
        internal fun usCertification(releaseDates: JsonObject?): String? {
            val results = (releaseDates?.get("results") as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return null
            val us = results.filter { it.stringOrNull("iso_3166_1") == "US" && !it.stringOrNull("certification").isNullOrBlank() }
            return (us.firstOrNull { it.intOrNull2("type") == 3 } ?: us.firstOrNull { it.intOrNull2("type") == 4 } ?: us.firstOrNull())
                ?.stringOrNull("certification")
        }

        /** US certification from TV content_ratings: the most restrictive listed US rating. */
        internal fun usContentCertification(contentRatings: JsonObject?): String? {
            val results = (contentRatings?.get("results") as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return null
            return results.filter { it.stringOrNull("iso_3166_1") == "US" && !it.stringOrNull("certification").isNullOrBlank() }
                .maxByOrNull { CERT_ORDER.indexOf(it.stringOrNull("certification")).let { i -> if (i < 0) -1 else i } }
                ?.stringOrNull("certification")
        }

        private val CERT_ORDER = listOf("G", "TV-Y", "TV-Y7", "TV-G", "TV-PG", "PG", "TV-14", "PG-13", "R", "TV-MA", "NC-17")

        internal fun parseMeta(json: JsonObject, certification: String?): TmdbMeta {
            val backdrops = json["images"]?.let { it as? JsonObject }?.get("backdrops") as? JsonArray
            val banner = pickBackdrop(backdrops) ?: json.stringOrNull("backdrop_path")
            return TmdbMeta(
                backdropPath = banner,
                cardBackdropPath = pickCardBackdrop(backdrops, banner),
                logoPath = pickLogo(json["images"]?.let { it as? JsonObject }?.get("logos") as? JsonArray)
                    ?: json.stringOrNull("logo_path"),
                posterPath = pickPoster(json["images"]?.let { it as? JsonObject }?.get("posters") as? JsonArray)
                    ?: json.stringOrNull("poster_path"),
                certification = certification,
                runtimeMin = json["runtime"]?.jsonPrimitive?.intOrNull,
                genres = (json["genres"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.stringOrNull("name") }
                    ?.takeIf { it.isNotEmpty() }?.joinToString(", "),
                tagline = json.stringOrNull("tagline")?.takeIf { it.isNotBlank() },
                // Task 84f: the plot summary is already in the movie/tv detail response - no extra call.
                overview = json.stringOrNull("overview"),
            )
        }

        internal fun parseSeason(json: JsonObject): List<TmdbEpisodeStill> =
            (json["episodes"] as? JsonArray)?.mapNotNull { ep ->
                ep as? JsonObject ?: return@mapNotNull null
                val number = ep["episode_number"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                TmdbEpisodeStill(
                    episode = number,
                    stillPath = pickPoster(ep["still_images"] as? JsonArray) ?: ep.stringOrNull("still_path"),
                    name = ep.stringOrNull("name")?.takeIf { it.isNotBlank() },
                    overview = ep.stringOrNull("overview")?.takeIf { it.isNotBlank() },
                )
            }.orEmpty()

        private fun JsonObject.stringOrNull(key: String): String? =
            (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

        private fun JsonObject.doubleOrNull2(key: String): Double? =
            (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.doubleOrNull

        private fun JsonObject.longOrNull2(key: String): Long? =
            (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull

        private fun JsonObject.intOrNull2(key: String): Int? =
            (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull
    }
}
