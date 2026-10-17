package com.yodesla.omniverse.core.data.metadata

import com.yodesla.omniverse.core.net.HttpClient
import kotlinx.serialization.json.*
import java.net.URLEncoder
import java.time.LocalDate

/** Netflix's own country weekly chart. Stream only the newest US section, not the 30MB archive. */
class NetflixCharts(private val http: HttpClient, private val tmdb: Tmdb) {
    suspend fun load(): List<DiscoveryTitle> {
        val entries = http.get(URL).use { response ->
            check(response.status in 200..299)
            val lines = sequence {
                var bytes = 0
                repeat(30_000) {
                    val line = response.body.readUtf8Line() ?: return@sequence
                    bytes += line.length
                    check(bytes < 8_000_000) { "Netflix chart exceeded bounded scan" }
                    yield(line)
                }
            }
            parse(lines, LocalDate.now().toString())
        }
        return entries.mapNotNull { entry ->
            val result = tmdb.discovery("search/${entry.kind}", "&query=${URLEncoder.encode(entry.title, "UTF-8")}&include_adult=false")["results"] as? JsonArray ?: return@mapNotNull null
            val exact = result.mapNotNull { it as? JsonObject }.filter { obj ->
                listOf("title", "original_title", "name", "original_name").any { field -> normalize(obj[field]?.jsonPrimitive?.contentOrNull.orEmpty()) == normalize(entry.title) }
            }.mapNotNull { it["id"]?.jsonPrimitive?.intOrNull }.distinct()
            // An ambiguous remake or same-name series is omitted, never guessed by first result.
            exact.singleOrNull()?.let { DiscoveryTitle(it.toString(), entry.kind, entry.week, entry.rank) }
        }.distinctBy { it.kind to it.id }
    }
    data class Entry(val title: String, val kind: String, val week: String, val rank: Int)
    companion object {
        const val URL = "https://www.netflix.com/tudum/top10/data/all-weeks-countries.tsv"
        private fun normalize(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        internal fun parse(lines: Sequence<String>, today: String): List<Entry> {
            val it = lines.iterator()
            check(it.hasNext())
            val header = it.next().removePrefix("\uFEFF").split('\t')
            fun index(name: String) = header.indexOf(name).also { check(it >= 0) { "Netflix schema changed" } }
            val country = index("country_iso2"); val week = index("week"); val category = index("category")
            val rank = index("weekly_rank"); val title = index("show_title")
            val out = ArrayList<Entry>()
            var newest: String? = null
            while (it.hasNext()) {
                val p = it.next().split('\t')
                if (p.size < header.size || p[country] != "US" || p[week] > today) continue
                if (newest == null) newest = p[week]
                if (p[week] != newest) break
                val k = when (p[category]) { "Films" -> "movie"; "TV" -> "tv"; else -> continue }
                val r = p[rank].toIntOrNull()?.takeIf { n -> n in 1..10 } ?: continue
                out += Entry(p[title], k, p[week], r)
                if (out.size >= 20) break
            }
            check(out.isNotEmpty()) { "Netflix US chart not available" }
            return out.sortedWith(compareBy({ it.kind }, { it.rank }))
        }
    }
}
