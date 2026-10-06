package com.yodesla.omniverse.core.data.metadata

import com.yodesla.omniverse.core.net.HttpClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Gap-filling metadata found for one exact TMDB id. Every field is optional. */
data class EnrichedMetadata(
    val plot: String?,
    val cast: String?,
    val director: String?,
    val genre: String?,
    /** Wikipedia lead image; may be a non-free poster, so private builds only (see [allowPosters]). */
    val posterUrl: String?,
    /** Attribution required by CC BY-SA when [plot] is shown, e.g. "Plot from Wikipedia". */
    val attribution: String?,
    /** Canonical article URL, used to provide attribution and license context to users. */
    val attributionUrl: String? = null,
    /** Explicit link to the article text's license; separate from the source article URL. */
    val attributionLicenseUrl: String? = null,
)

/**
 * Opt-in, read-only metadata lookup. Sends ONLY a source-supplied TMDB id to Wikidata
 * (CC0 facts: cast, director, genre) and, when an English article exists, fetches its
 * summary from Wikipedia (CC BY-SA text). No title guessing, provider URLs, or accounts.
 */
class WikidataMetadata(
    private val http: HttpClient,
    private val allowPosters: Boolean = false,
    private val sparqlUrl: String = "https://query.wikidata.org/sparql",
    private val summaryUrl: String = "https://en.wikipedia.org/api/rest_v1/page/summary/",
) {
    enum class Kind(internal val property: String) { MOVIE("P4947"), SERIES("P4983") }

    suspend fun lookup(kind: Kind, tmdbId: String): EnrichedMetadata? {
        val id = tmdbId.trim().takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: return null
        val row = runCatching { queryWikidata(kind, id) }.getOrNull() ?: return null
        val summary = row.article?.let { runCatching { wikipediaSummary(it) }.getOrNull() }
        val plot = summary?.first
        return EnrichedMetadata(
            plot = plot,
            cast = row.cast,
            director = row.director,
            genre = row.genre,
            posterUrl = summary?.second?.takeIf { allowPosters },
            attribution = if (plot != null) wikipediaAttribution(row.article) else null,
            attributionUrl = if (plot != null) row.article.takeIf { it.startsWith(WIKIPEDIA_ARTICLE_PREFIX) && !it.any(Char::isWhitespace) } else null,
            attributionLicenseUrl = if (plot != null) CC_BY_SA_4_URL else null,
        ).takeIf { listOf(it.plot, it.cast, it.director, it.genre, it.posterUrl).any { v -> v != null } }
    }

    private data class Row(val article: String?, val cast: String?, val director: String?, val genre: String?)

    private fun wikipediaAttribution(articleUrl: String): String {
        val title = articleUrl.substringAfter(WIKIPEDIA_ARTICLE_PREFIX, "Wikipedia article")
            .replace('_', ' ').replace("%20", " ")
        return "Wikipedia contributors · $title"
    }

    private suspend fun queryWikidata(kind: Kind, id: String): Row? {
        val query = """
            SELECT ?article
              (GROUP_CONCAT(DISTINCT CONCAT(STR(?fame), "|", ?castName); separator=";;") AS ?cast)
              (GROUP_CONCAT(DISTINCT ?directorName; separator=", ") AS ?director)
              (GROUP_CONCAT(DISTINCT ?genreName; separator=", ") AS ?genre)
            WHERE {
              ?item wdt:${kind.property} "$id" .
              OPTIONAL { ?article schema:about ?item ; schema:isPartOf <https://en.wikipedia.org/> . }
              OPTIONAL { ?item wdt:P161 ?c . ?c rdfs:label ?castName ; wikibase:sitelinks ?fame . FILTER(LANG(?castName) = "en") }
              OPTIONAL { ?item wdt:P57 ?d . ?d rdfs:label ?directorName . FILTER(LANG(?directorName) = "en") }
              OPTIONAL { ?item wdt:P136 ?g . ?g rdfs:label ?genreName . FILTER(LANG(?genreName) = "en") }
            } GROUP BY ?article LIMIT 1
        """.trimIndent()
        val body = http.get("$sparqlUrl?format=json&query=${encode(query)}", HEADERS).use { r ->
            if (r.status !in 200..299) return null
            r.body.readUtf8()
        }
        val binding = json.parseToJsonElement(body).jsonObject["results"]?.jsonObject
            ?.get("bindings")?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        fun field(name: String) = binding[name]?.jsonObject?.get("value")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        return Row(
            article = field("article"),
            cast = field("cast")?.let(::rankCast),
            director = field("director"),
            genre = field("genre")?.let { limitList(it, MAX_GENRES) },
        )
    }

    /** Returns (extract, lead image) for an English Wikipedia article URL. */
    private suspend fun wikipediaSummary(articleUrl: String): Pair<String?, String?>? {
        val title = articleUrl.substringAfter("/wiki/", "").takeIf { it.isNotEmpty() } ?: return null
        val body = http.get(summaryUrl + title, HEADERS).use { r ->
            if (r.status !in 200..299) return null
            r.body.readUtf8()
        }
        val obj: JsonObject = json.parseToJsonElement(body).jsonObject
        if (obj["type"]?.jsonPrimitive?.contentOrNull == "disambiguation") return null
        val extract = obj["extract"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val image = (obj["originalimage"] ?: obj["thumbnail"])?.jsonObject?.get("source")?.jsonPrimitive?.contentOrNull
        return extract to image
    }

    /** "fame|name;;fame|name" → best-known names first; Wikidata returns cast unordered. */
    private fun rankCast(raw: String): String? = raw.split(";;").mapNotNull { e ->
        val name = e.substringAfter('|', "").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        (e.substringBefore('|').toIntOrNull() ?: 0) to name
    }.sortedByDescending { it.first }.take(MAX_CAST).joinToString(", ") { it.second }.ifEmpty { null }

    private fun limitList(csv: String, max: Int) = csv.split(", ").take(max).joinToString(", ")

    private companion object {
        const val MAX_CAST = 8
        const val MAX_GENRES = 3
        const val WIKIPEDIA_ARTICLE_PREFIX = "https://en.wikipedia.org/wiki/"
        const val CC_BY_SA_4_URL = "https://creativecommons.org/licenses/by-sa/4.0/"
        // Wikimedia asks every client to identify itself; no personal contact data is sent.
        val HEADERS = mapOf("User-Agent" to "Omniverse-TV/1.0 (personal media player)", "Accept" to "application/json")
        val json = Json { ignoreUnknownKeys = true }

        fun encode(s: String): String = buildString {
            for (b in s.encodeToByteArray()) {
                val c = b.toInt() and 0xFF
                if (c.toChar().isLetterOrDigit() && c < 128 || c.toChar() in "-_.~") append(c.toChar())
                else append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
            }
        }
    }
}

/** Fills only blank fields; source/provider metadata always wins. */
fun EnrichedMetadata.fillGaps(plot: String?, cast: String?, director: String?, genre: String?, posterUrl: String?) = EnrichedMetadata(
    plot = plot?.takeIf { it.isNotBlank() } ?: this.plot,
    cast = cast?.takeIf { it.isNotBlank() } ?: this.cast,
    director = director?.takeIf { it.isNotBlank() } ?: this.director,
    genre = genre?.takeIf { it.isNotBlank() } ?: this.genre,
    posterUrl = posterUrl?.takeIf { it.isNotBlank() } ?: this.posterUrl,
    attribution = if (plot.isNullOrBlank() && this.plot != null) attribution else null,
    attributionUrl = if (plot.isNullOrBlank() && this.plot != null) attributionUrl else null,
    attributionLicenseUrl = if (plot.isNullOrBlank() && this.plot != null) attributionLicenseUrl else null,
)
