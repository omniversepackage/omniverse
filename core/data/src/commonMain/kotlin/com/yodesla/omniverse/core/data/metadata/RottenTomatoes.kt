package com.yodesla.omniverse.core.data.metadata

import com.yodesla.omniverse.core.net.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Rotten Tomatoes scores in percent; either may be missing (too few reviews/ratings). */
data class RtScores(val critics: Int?, val audience: Int?) {
    val isEmpty get() = critics == null && audience == null
}

/**
 * Rotten Tomatoes critics (Tomatometer) and audience (Popcornmeter) scores for an exact TMDB id.
 * Wikidata maps the TMDB id to the Rotten Tomatoes page id (P1258) and the page's own score
 * data is read from there. Results, including misses, are cached in memory per id.
 */
class RottenTomatoes(
    private val http: HttpClient,
    private val sparqlUrl: String = "https://query.wikidata.org/sparql",
    private val siteUrl: String = "https://www.rottentomatoes.com/",
    private val capacity: Int = 512,
    /** Settings switch (default on); checked before any request so Off sends nothing. */
    private val enabled: suspend () -> Boolean = { true },
) {
    private val mutex = Mutex()
    private val cache = object : LinkedHashMap<String, RtScores?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RtScores?>) = size > capacity
    }

    suspend fun scores(kind: WikidataMetadata.Kind, tmdbId: String?): RtScores? {
        val id = tmdbId?.trim()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: return null
        if (!enabled()) return null
        val key = "${kind.name}:$id"
        mutex.withLock { if (cache.containsKey(key)) return cache[key] }
        // A network failure or a cancelled lookup (page left mid-request) is not a miss: cache neither, so
        // the next visit tries again. Only real answers (scores, or no RT page / no scores) are cached.
        val found = try {
            withContext(Dispatchers.IO) {
                rtPath(kind, id)?.let { page(it) }?.takeUnless { it.isEmpty }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        mutex.withLock { cache[key] = found }
        return found
    }

    private suspend fun rtPath(kind: WikidataMetadata.Kind, id: String): String? {
        val query = "SELECT ?rt WHERE { ?item wdt:${kind.property} \"$id\" ; wdt:P1258 ?rt . } LIMIT 1"
        val body = http.get("$sparqlUrl?format=json&query=${java.net.URLEncoder.encode(query, "UTF-8")}", SPARQL_HEADERS).use { r ->
            check(r.status in 200..299) { "Score lookup HTTP ${r.status}" }
            r.body.readUtf8()
        }
        return kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject["results"]?.jsonObject?.get("bindings")?.jsonArray
            ?.firstOrNull()?.jsonObject?.get("rt")?.jsonObject?.get("value")?.jsonPrimitive?.contentOrNull
            ?.takeIf { PATH.matches(it) }
    }

    private suspend fun page(path: String): RtScores? {
        val html = http.get(siteUrl + path, PAGE_HEADERS).use { r ->
            check(r.status in 200..299) { "Score page HTTP ${r.status}" }
            r.body.readUtf8()
        }
        return parseScores(html)
    }

    companion object {
        const val SETTING_KEY = "rt_scores"   // "false" = off; absent = build default; anything else = on

        /**
         * Resolve the stored setting against the build's default. An explicit choice always wins;
         * when the user has never touched the switch (null), public builds ship Rotten Tomatoes
         * off (no third-party score requests by default) and private builds keep it on.
         */
        fun enabledFor(stored: String?, publicBuild: Boolean): Boolean =
            if (stored == null) !publicBuild else stored != "false"

        private val SPARQL_HEADERS = mapOf("User-Agent" to "Omniverse-TV/1.0 (personal media player)", "Accept" to "application/json")
        private val PATH = Regex("^(m|tv)/[A-Za-z0-9_\\-]+$")
        private val PAGE_HEADERS = mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 11) Omniverse-TV/1.0", "Accept" to "text/html")

        /** Reads the first `"criticsScore":{…"score":"NN"` and `"audienceScore":{…}` blocks of a title page. */
        fun parseScores(html: String): RtScores {
            fun score(name: String): Int? {
                val start = html.indexOf("\"$name\":{").takeIf { it >= 0 } ?: return null
                val block = html.substring(start, minOf(html.length, html.indexOf('}', start).takeIf { it > 0 } ?: html.length))
                return Regex("\"score\":\"?(\\d{1,3})").find(block)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 0..100 }
            }
            return RtScores(score("criticsScore"), score("audienceScore"))
        }
    }
}
