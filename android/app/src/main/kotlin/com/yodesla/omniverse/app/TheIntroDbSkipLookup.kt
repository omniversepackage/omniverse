package com.yodesla.omniverse.app

import com.yodesla.omniverse.core.model.SkipMarker
import com.yodesla.omniverse.core.net.HttpClient
import com.yodesla.omniverse.feature.vod.CommunitySkipLookup
import com.yodesla.omniverse.feature.vod.CommunitySkipQuery
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.net.URLEncoder

/** Read-only community timestamp lookup. Requests contain catalog metadata only, never stream URLs. */
internal class TheIntroDbSkipLookup(private val http: HttpClient) : CommunitySkipLookup {
    override suspend fun lookup(query: CommunitySkipQuery): List<SkipMarker> {
        val season = query.season
        val episode = query.episode
        if ((season == null) != (episode == null)) return emptyList()
        if (season != null && (season < 0 || episode == null || episode < 1)) return emptyList()
        val tmdbId = query.tmdbId.toLongOrNull()?.takeIf { it > 0 }
        // The provider may have no catalog ID. Only the user's opt-in online lookup invokes
        // this path; accept a unique exact series-title/year match, never a fuzzy first hit.
        val imdbId = if (tmdbId == null && season != null) resolveSeriesImdbId(query.seriesTitle, query.seriesYear) else null
        if (tmdbId == null && imdbId == null) return emptyList()
        val runtime = query.durationMs?.takeIf { it in 60_000L..(8 * 60 * 60 * 1000L) }
        val url = if (tmdbId != null) buildLookupUrl(tmdbId, season, episode, runtime)
            else buildImdbLookupUrl(checkNotNull(imdbId), season, episode, runtime)

        val response = try {
            http.get(url)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return emptyList()
        }
        try {
            if (response.status !in 200..299) return emptyList()
            val body = response.body.readUtf8()
            val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
            val media = root["data"]?.let { (it as? JsonObject) } ?: root
            if (tmdbId != null && media.long("tmdb_id") != tmdbId) return emptyList()
            if (tmdbId == null && (media.long("tmdb_id") ?: 0L) <= 0L) return emptyList()
            val expectedType = if (season == null) "movie" else "tv"
            if (media.string("type") != expectedType) return emptyList()
            if (season != null && (media.int("season") != season || media.int("episode") != episode)) {
                return emptyList()
            }
            return buildList {
                addSegments(media, "intro", runtime).forEach(::add)
                addSegments(media, "credits", runtime).forEach(::add)
            }
        } finally {
            response.close()
        }
    }

    private suspend fun resolveSeriesImdbId(rawTitle: String?, year: Int?): String? {
        val title = rawTitle?.trim()?.let(::stripYearSuffix)?.takeIf { it.length in 2..120 } ?: return null
        val match = searchTvmaze(title, year)
        if (match != NoMatch) return (match as? String)
        // IPTV panels often prefix names with a language tag ("EN - Show", "|EN| Show"). Retry
        // without it only when the raw title found nothing, so real titles like "FBI: ..." win.
        val unprefixed = stripLanguagePrefix(title).takeIf { it != title && it.length >= 2 } ?: return null
        return searchTvmaze(unprefixed, year) as? String
    }

    private object NoMatch

    /** The unique matching IMDb ID, null when ambiguous/unusable, or [NoMatch] when nothing matched. */
    private suspend fun searchTvmaze(title: String, year: Int?): Any? {
        val normalized = normalizeSeriesTitle(title)
        if (normalized.length < 2) return null
        val url = "https://api.tvmaze.com/search/shows?q=" + URLEncoder.encode(title, "UTF-8")
        val response = try {
            http.get(url)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        try {
            if (response.status !in 200..299) return null
            val results = runCatching { Json.parseToJsonElement(response.body.readUtf8()) as? JsonArray }
                .getOrNull() ?: return null
            val matches = results.mapNotNull { (it as? JsonObject)?.get("show") as? JsonObject }
                .filter { show ->
                    normalizeSeriesTitle(show.string("name").orEmpty()) == normalized &&
                        (year == null || show.string("premiered")?.take(4)?.toIntOrNull() == year)
                }
            if (matches.isEmpty()) return NoMatch
            if (matches.size != 1) return null
            val imdb = (matches.single()["externals"] as? JsonObject)?.string("imdb")
            return imdb?.takeIf { it.matches(Regex("tt[0-9]{7,10}")) }
        } finally {
            response.close()
        }
    }

    private fun stripLanguagePrefix(title: String): String = title
        .replace(Regex("^(?:\\|[A-Z]{2,3}\\||\\[[A-Z]{2,3}]|[A-Z]{2,3}\\s*[-:|])\\s*"), "")
        .trim()

    private fun stripYearSuffix(title: String): String = title
        .replace(Regex("\\s*\\((?:19|20)[0-9]{2}\\)\\s*$"), "")
        .replace(Regex("\\s*\\[(?:19|20)[0-9]{2}]\\s*$"), "")
        .trim()

    private fun normalizeSeriesTitle(title: String): String = stripYearSuffix(title)
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    private fun addSegments(media: JsonObject, type: String, runtime: Long?): List<SkipMarker> {
        val raw = media[type]
        val segments = when (raw) {
            is JsonArray -> raw.mapNotNull { it as? JsonObject }
            is JsonObject -> listOf(raw)
            else -> emptyList()
        }
        return segments.mapNotNull { segment ->
            val start = segment.long("start_ms") ?: if (type == "intro" || type == "recap") 0L else null
            val end = segment.long("end_ms") ?: if (type == "credits" || type == "preview") runtime else null
            if (start == null || end == null || end <= start) return@mapNotNull null
            if (runtime != null && (start >= runtime || end > runtime + 90_000L)) return@mapNotNull null
            val maxLength = if (type == "intro") 5 * 60_000L else 12 * 60_000L
            if (end - start > maxLength) return@mapNotNull null
            if (type == "credits" && runtime != null && start < runtime / 2) return@mapNotNull null
            SkipMarker(type, start, end.coerceAtMost(runtime ?: end))
        }
    }

    private fun JsonObject.string(key: String): String? = this[key]?.let { it as? kotlinx.serialization.json.JsonPrimitive }
        ?.contentOrNull

    private fun JsonObject.long(key: String): Long? = this[key].asLong()
    private fun JsonObject.int(key: String): Int? = long(key)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

    private fun JsonElement?.asLong(): Long? = when (this) {
        null, JsonNull -> null
        else -> (this as? kotlinx.serialization.json.JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }
    }

    internal fun buildLookupUrl(tmdbId: Long, season: Int?, episode: Int?, runtimeMs: Long?): String = buildString {
        append("https://").append(HOST).append("/v3/media?tmdb_id=").append(tmdbId)
        season?.let { append("&season=").append(it) }
        episode?.let { append("&episode=").append(it) }
        runtimeMs?.let { append("&duration_ms=").append(it) }
    }

    internal fun buildImdbLookupUrl(imdbId: String, season: Int?, episode: Int?, runtimeMs: Long?): String = buildString {
        append("https://").append(HOST).append("/v3/media?imdb_id=").append(imdbId)
        season?.let { append("&season=").append(it) }
        episode?.let { append("&episode=").append(it) }
        runtimeMs?.let { append("&duration_ms=").append(it) }
    }

    private companion object {
        const val HOST = "api.theintrodb.org"
    }
}
