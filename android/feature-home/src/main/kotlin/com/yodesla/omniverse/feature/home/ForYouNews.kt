package com.yodesla.omniverse.feature.home

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL

data class InterestStory(val title: String, val url: String, val summary: String, val published: String)

/** Generic public feeds; searches, favourites and viewing history never leave this device. */
object ForYouNews {
    private var cached: List<InterestStory> = emptyList()
    private var fetched = 0L
    suspend fun fetch(): List<InterestStory> = withContext(Dispatchers.IO) {
        if (System.currentTimeMillis() - fetched < 3_600_000 && cached.isNotEmpty()) return@withContext cached
        val result = listOf("us/film", "us/tv-and-radio", "us/sport").flatMap { section ->
            runCatching {
                val connection = URL("https://www.theguardian.com/$section/rss").openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 5000; connection.readTimeout = 5000
                    connection.setRequestProperty("User-Agent", "Omniverse/1.0")
                    connection.instanceFollowRedirects = false
                    if (connection.responseCode != 200) return@runCatching emptyList()
                    val bytes = connection.inputStream.use { it.readBytesLimited(524_288) }
                    parse(bytes.toString(Charsets.UTF_8)).take(20)
                } finally { connection.disconnect() }
            }.getOrDefault(emptyList())
        }.distinctBy { it.url }.take(60)
        if (result.isNotEmpty()) { cached = result; fetched = System.currentTimeMillis() }
        result.ifEmpty { cached }
    }
    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (out.size() < limit) { val n = read(buffer, 0, minOf(buffer.size, limit - out.size())); if (n < 0) break; out.write(buffer, 0, n) }
        return out.toByteArray()
    }
    internal fun parse(xml: String): List<InterestStory> {
        val parser = Xml.newPullParser().apply { setInput(xml.reader()) }
        val items = mutableListOf<InterestStory>()
        var inside = false; var title = ""; var link = ""; var summary = ""; var date = ""
        while (parser.eventType != XmlPullParser.END_DOCUMENT && items.size < 40) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                if (parser.name == "item") { inside = true; title = ""; link = ""; summary = ""; date = "" }
                else if (inside && parser.name in setOf("title", "link", "description", "pubDate")) {
                    when (parser.name) {
                        "title" -> title = parser.nextText()
                        "link" -> link = parser.nextText()
                        "description" -> summary = parser.nextText().replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").take(260)
                        "pubDate" -> date = parser.nextText()
                    }
                }
            } else if (parser.eventType == XmlPullParser.END_TAG && parser.name == "item") {
                val uri = runCatching { java.net.URI(link) }.getOrNull()
                if (title.isNotBlank() && uri?.scheme == "https" && uri.host == "www.theguardian.com") items += InterestStory(title, link, summary, date)
                inside = false
            }
            parser.next()
        }
        return items
    }
    fun relevance(story: InterestStory, interests: List<String>): Int {
        val words = interests.flatMap { it.lowercase().split(Regex("[^\\p{L}\\p{N}]+")) }
            .filter { it.length >= 4 && it !in setOf("movie", "movies", "series", "season", "episode", "theatre", "with", "from", "this", "that") }.distinct()
        val title = story.title.lowercase(); val text = "$title ${story.summary.lowercase()}"
        return words.sumOf { if (title.contains(it)) 3 else if (text.contains(it)) 1 else 0 }
    }
}
