package com.yodesla.omniverse.feature.home

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * One public headline. [imageUrl] is the feed's own photo (its widest copy, Guardian-hosted only),
 * [section] the feed it came from ("Film", "TV", "Sport"), [publishedMs] the parsed pubDate.
 */
data class InterestStory(
    val title: String,
    val url: String,
    val summary: String,
    val published: String,
    val imageUrl: String? = null,
    val section: String = "",
    val publishedMs: Long? = null,
)

/** Generic public feeds; searches, favourites and viewing history never leave this device. */
object ForYouNews {
    private val FEEDS = listOf("us/film" to "Film", "us/tv-and-radio" to "TV", "us/sport" to "Sport")
    private var cached: List<InterestStory> = emptyList()
    private var fetched = 0L
    suspend fun fetch(): List<InterestStory> = withContext(Dispatchers.IO) {
        if (System.currentTimeMillis() - fetched < 3_600_000 && cached.isNotEmpty()) return@withContext cached
        // The three feeds load side by side: one slow section must not hold up the other two.
        val result = coroutineScope {
            FEEDS.map { (path, section) -> async { feed(path, section) } }.awaitAll()
        }.flatten().distinctBy { it.url }.sortedByDescending { it.publishedMs ?: 0L }.take(60)
        if (result.isNotEmpty()) { cached = result; fetched = System.currentTimeMillis() }
        result.ifEmpty { cached }
    }
    private fun feed(path: String, section: String): List<InterestStory> = runCatching {
        val connection = URL("https://www.theguardian.com/$path/rss").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5000; connection.readTimeout = 5000
            connection.setRequestProperty("User-Agent", "Omniverse/1.0")
            connection.instanceFollowRedirects = false
            if (connection.responseCode != 200) return@runCatching emptyList()
            val bytes = connection.inputStream.use { it.readBytesLimited(524_288) }
            parse(bytes.toString(Charsets.UTF_8), section).take(20)
        } finally { connection.disconnect() }
    }.getOrDefault(emptyList())
    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (out.size() < limit) { val n = read(buffer, 0, minOf(buffer.size, limit - out.size())); if (n < 0) break; out.write(buffer, 0, n) }
        return out.toByteArray()
    }
    internal fun parse(xml: String, section: String = ""): List<InterestStory> {
        val parser = Xml.newPullParser().apply { setInput(xml.reader()) }
        val items = mutableListOf<InterestStory>()
        var inside = false; var title = ""; var link = ""; var summary = ""; var date = ""
        var image: String? = null; var imageWidth = -1
        while (parser.eventType != XmlPullParser.END_DOCUMENT && items.size < 40) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                if (parser.name == "item") { inside = true; title = ""; link = ""; summary = ""; date = ""; image = null; imageWidth = -1 }
                else if (inside && isMediaContent(parser)) {
                    // The feed lists one photo at several widths; keep the widest.
                    val url = parser.getAttributeValue(null, "url")
                    val width = parser.getAttributeValue(null, "width")?.toIntOrNull() ?: 0
                    if (url != null && width > imageWidth && isFeedImage(url)) { image = url; imageWidth = width }
                }
                else if (inside && parser.name in setOf("title", "link", "description", "pubDate")) {
                    when (parser.name) {
                        "title" -> title = parser.nextText()
                        "link" -> link = parser.nextText()
                        "description" -> summary = cleanSummary(parser.nextText())
                        "pubDate" -> date = parser.nextText()
                    }
                }
            } else if (parser.eventType == XmlPullParser.END_TAG && parser.name == "item") {
                val uri = runCatching { java.net.URI(link) }.getOrNull()
                if (title.isNotBlank() && uri?.scheme == "https" && uri.host == "www.theguardian.com")
                    items += InterestStory(title, link, summary, date, image, section, publishedMs(date))
                inside = false
            }
            parser.next()
        }
        return items
    }
    private val ENTITIES = mapOf("&amp;" to "&", "&quot;" to "\"", "&#39;" to "'", "&#x27;" to "'", "&apos;" to "'",
        "&lt;" to "<", "&gt;" to ">", "&nbsp;" to " ", "&rsquo;" to "’", "&lsquo;" to "‘", "&ldquo;" to "“", "&rdquo;" to "”")

    /**
     * The feed's standfirst and opening paragraph as plain text: paragraphs end in punctuation (the
     * standfirst usually has none, which ran it into the next sentence), the "Continue reading…" link
     * is dropped, and anything over [max] chars ends on a whole sentence (or word, with an ellipsis).
     */
    internal fun cleanSummary(html: String, max: Int = 420): String {
        val text = html.split(Regex("(?i)</p>|<br\\s*/?>"))
            .map { p -> ENTITIES.entries.fold(p.replace(Regex("<[^>]+>"), " ")) { s, (k, v) -> s.replace(k, v) }.replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotEmpty() && !it.startsWith("Continue reading", ignoreCase = true) }
            .joinToString(" ") { if (it.last() in ".!?…:\"”’)") it else "$it." }
        if (text.length <= max) return text
        val cut = text.take(max)
        val sentence = cut.lastIndexOf(". ").takeIf { it >= max / 3 }
        return if (sentence != null) cut.substring(0, sentence + 1) else cut.substringBeforeLast(' ').trimEnd(',', ';', ' ') + "…"
    }

    /** Android's parser resolves namespaces, so `<media:content>` arrives as "content" in the Media RSS namespace. */
    private fun isMediaContent(p: XmlPullParser): Boolean =
        p.name == "media:content" || (p.name == "content" && (p.prefix == "media" || p.namespace == "http://search.yahoo.com/mrss/"))
    private fun isFeedImage(url: String): Boolean =
        runCatching { java.net.URI(url) }.getOrNull()?.let { it.scheme == "https" && it.host == "i.guim.co.uk" } == true

    /** RSS pubDate ("Tue, 28 Oct 2026 12:00:00 GMT") as epoch ms; null when it does not parse. */
    internal fun publishedMs(raw: String): Long? = runCatching {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(raw.trim())?.time
    }.getOrNull()

    /** "Just now", "25 min ago", "3h ago", "Yesterday", "4 days ago"; null when the time is unknown. */
    fun agoLabel(publishedMs: Long?, nowMs: Long): String? {
        val minutes = ((nowMs - (publishedMs ?: return null)) / 60_000).coerceAtLeast(0)
        return when {
            minutes < 2 -> "Just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 24 * 60 -> "${minutes / 60}h ago"
            minutes < 48 * 60 -> "Yesterday"
            else -> "${minutes / (24 * 60)} days ago"
        }
    }

    /**
     * 3 per interest named in the headline, 1 per interest only in the summary. Interests match as whole
     * phrases on word boundaries ("Game of Thrones", "Mushoku Tensei", "Arsenal"): single common words
     * from a long title ("when", "good") never count on their own.
     */
    fun relevance(story: InterestStory, interests: List<String>): Int {
        val phrases = interests.flatMap(::phrases).distinct()
        if (phrases.isEmpty()) return 0
        val title = normalized(story.title); val text = normalized(story.summary)
        return phrases.sumOf { if (title.contains(it)) 3 else if (text.contains(it)) 1 else 0 }
    }
    private val generic = setOf("movie", "movies", "film", "series", "season", "episode", "show", "news", "live", "sport", "sports", "theatre")
    private fun normalized(s: String) = " " + s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim() + " "
    /** The whole name, plus the part before a subtitle ("Mushoku Tensei: Jobless Reincarnation" → "Mushoku Tensei"). */
    private fun phrases(interest: String): List<String> =
        (listOf(interest) + interest.split(':', '–', '—', '(', '|').take(1))
            .map { normalized(it).trim() }.filter { it.length >= 4 && it !in generic }.distinct().map { " $it " }
}
