package com.yodesla.omniverse.app

import com.yodesla.omniverse.core.data.PosterRow
import com.yodesla.omniverse.core.data.Visibility
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.SourceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task 80: the pure mapping behind SearchSuggestProvider. What the system search may show is
 * exactly what the current profile may browse — hidden titles never leak, merged titles collapse
 * to one row, at most 10 rows, and every deep link is one the omniverse://open handler accepts.
 */
class SearchSuggestProviderTest {
    private val src = SourceId("src1")
    private val other = SourceId("src2")
    private val ok = RemoteId("cat-ok")
    private val adult = RemoteId("cat-adult")

    private val allowAll: Visibility = { _, _, _ -> true }
    private val denyAdult: Visibility = { _, _, cat -> cat != "cat-adult" }
    private val denyAll: Visibility = { _, _, _ -> false }

    private fun poster(
        source: SourceId = src,
        kind: ContentKind = ContentKind.VOD,
        remote: String = "t1",
        name: String = "Title $remote",
        year: Int? = 2011,
        category: RemoteId? = ok,
        tmdb: String? = null,
        url: String? = "https://img/$remote.jpg",
    ) = PosterRow(
        key = ContentKey(source, kind, RemoteId(remote)),
        name = name,
        posterUrl = url,
        year = year,
        rating = null,
        categoryId = category,
        tmdbId = tmdb,
    )

    @Test
    fun titlesHiddenFromTheCurrentProfileNeverLeak() {
        val rows = suggestRows(
            listOf(poster(remote = "a"), poster(remote = "b", category = adult), poster(remote = "c")),
            denyAdult,
        )
        assertEquals(listOf("Title a", "Title c"), rows.map { it.title })
    }

    @Test
    fun aFullyLockedProfileGetsNoRows() {
        assertTrue(suggestRows(listOf(poster(), poster(remote = "b")), denyAll).isEmpty())
    }

    @Test
    fun aTitleWithoutCategoryStaysVisible() {
        // Same rule as the in-app Search: no category to check means nothing hides it.
        val rows = suggestRows(listOf(poster(remote = "a", category = null)), denyAll)
        assertEquals(listOf("Title a"), rows.map { it.title })
    }

    @Test
    fun mergedTitlesCollapseToOneRowKeepingTheBestRanked() {
        // Two sources, same exact TMDB id (the Search screen's merge rule): one row, first hit wins.
        val rows = suggestRows(
            listOf(
                poster(source = src, remote = "plex1", name = "Dune", tmdb = "693"),
                poster(source = other, remote = "xt1", name = "Dune (extended)", tmdb = "693"),
                poster(source = other, remote = "xt2", name = "Dune 2", tmdb = "694"),
            ),
            allowAll,
        )
        assertEquals(listOf("Dune", "Dune 2"), rows.map { it.title })
        assertEquals("omniverse://open?s=src1&k=VOD&r=plex1", rows.first().intentData)
    }

    @Test
    fun rowsAreCappedAtTenInRankOrder() {
        val many = (1..15).map { poster(remote = "t$it") }
        val rows = suggestRows(many, allowAll)
        assertEquals(10, rows.size)
        assertEquals((1..10).map { "Title t$it" }, rows.map { it.title })
        assertEquals((1L..10L).toList(), rows.map { it.id })
    }

    @Test
    fun deepLinksAreExactlyWhatTheOpenHandlerParses() {
        val rows = suggestRows(
            listOf(poster(remote = "m7", kind = ContentKind.VOD), poster(remote = "s9", kind = ContentKind.SERIES)),
            allowAll,
        )
        for ((row, key) in rows.zip(listOf(ContentKind.VOD to "m7", ContentKind.SERIES to "s9"))) {
            val parsed = parseOpenLink(row.intentData)
            assertEquals("omniverse", parsed["scheme"])
            assertEquals("open", parsed["host"])
            assertEquals("src1", parsed["s"])
            assertEquals(key.first.name, parsed["k"]) // only VOD/SERIES parse at all
            assertEquals(key.second, parsed["r"])
        }
    }

    @Test
    fun idsWithSpecialCharactersSurviveTheLink() {
        val row = suggestRows(listOf(poster(remote = "a/b c")), allowAll).single()
        assertEquals("omniverse://open?s=src1&k=VOD&r=a%2Fb%20c", row.intentData)
        assertEquals("a/b c", parseOpenLink(row.intentData)["r"])
    }

    @Test
    fun subtitleYearAndContentTypeMatchTheKind() {
        val movie = suggestRows(listOf(poster(kind = ContentKind.VOD, year = 2011)), allowAll).single()
        assertEquals("2011 · Movie", movie.subtitle)
        assertEquals("movie", movie.contentType)
        assertEquals(2011, movie.year)
        assertEquals("https://img/t1.jpg", movie.imageUrl)

        val show = suggestRows(listOf(poster(kind = ContentKind.SERIES, remote = "s", year = 1999)), allowAll).single()
        assertEquals("1999 · Show", show.subtitle)
        assertEquals("tvShow", show.contentType)

        val noYear = suggestRows(listOf(poster(remote = "n", year = null)), allowAll).single()
        assertEquals("Movie", noYear.subtitle)
        assertNull(noYear.year)

        val noPoster = suggestRows(listOf(poster(remote = "p", url = null)), allowAll).single()
        assertNull(noPoster.imageUrl)
    }

    /** Mirrors DeepLinks.parse (scheme/authority + s/k/r query params) without android.net.Uri. */
    private fun parseOpenLink(link: String): Map<String, String> {
        val scheme = link.substringBefore("://")
        val rest = link.substringAfter("://")
        val authority = rest.substringBefore("/").substringBefore("?")
        val query = rest.substringAfter("?", "")
        val params = query.split("&").filter { it.isNotEmpty() }
            .associate { it.substringBefore("=") to decode(it.substringAfter("=")) }
        return mapOf("scheme" to scheme, "host" to authority) + params
    }

    private fun decode(v: String): String {
        val bytes = ArrayList<ByteArray>()
        var i = 0
        val cur = ArrayList<Byte>()
        while (i < v.length) {
            if (v[i] == '%' && i + 2 < v.length) {
                cur.add(v.substring(i + 1, i + 3).toInt(16).toByte())
                i += 3
            } else {
                cur.add(v[i].code.toByte())
                i++
            }
        }
        bytes.add(cur.toByteArray())
        return bytes.single().toString(Charsets.UTF_8)
    }
}
