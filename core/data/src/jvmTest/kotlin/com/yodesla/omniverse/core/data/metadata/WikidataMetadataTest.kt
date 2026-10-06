package com.yodesla.omniverse.core.data.metadata

import com.yodesla.omniverse.core.net.HttpClient
import com.yodesla.omniverse.core.net.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okio.Buffer
import okio.BufferedSource

private class Resp(override val status: Int, text: String) : HttpResponse {
    override val headers: Map<String, String> = emptyMap()
    override val body: BufferedSource = Buffer().apply { writeUtf8(text) }
    override fun close() {}
}

private class Fake(val routes: (String) -> HttpResponse) : HttpClient {
    val urls = mutableListOf<String>()
    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse { urls += url; return routes(url) }
}

private const val SPARQL = """{"results":{"bindings":[{
  "article":{"value":"https://en.wikipedia.org/wiki/Fight_Club"},
  "cast":{"value":"3|Extra A;;90|Brad Pitt;;80|Edward Norton;;70|Helena Bonham Carter;;2|B;;1|C;;4|D;;5|E;;6|F;;7|G"},
  "director":{"value":"David Fincher"},
  "genre":{"value":"drama film, thriller film, satire, cult film"}}]}}"""
private const val SUMMARY = """{"type":"standard","extract":"An insomniac office worker...","originalimage":{"source":"https://upload.wikimedia.org/poster.jpg"}}"""

class WikidataMetadataTest {
    private fun fake() = Fake { url -> if (url.contains("sparql")) Resp(200, SPARQL) else Resp(200, SUMMARY) }

    @Test fun fillsFactsAndPlotFromExactId() = runBlocking {
        val http = fake()
        val m = WikidataMetadata(http).lookup(WikidataMetadata.Kind.MOVIE, "550")!!
        assertEquals("David Fincher", m.director)
        assertEquals(8, m.cast!!.split(", ").size)
        assertTrue(m.cast!!.startsWith("Brad Pitt, Edward Norton, Helena Bonham Carter"))
        assertEquals(3, m.genre!!.split(", ").size)
        assertEquals("An insomniac office worker...", m.plot)
        assertNull(m.posterUrl, "posters are off unless explicitly allowed")
        assertEquals("Wikipedia contributors · Fight Club", m.attribution)
        assertEquals("https://en.wikipedia.org/wiki/Fight_Club", m.attributionUrl)
        assertEquals("https://creativecommons.org/licenses/by-sa/4.0/", m.attributionLicenseUrl)
        assertTrue(http.urls[0].contains("P4947") && http.urls[0].contains("%22550%22"))
        assertTrue(http.urls[1].endsWith("/Fight_Club"))
    }

    @Test fun postersOnlyWhenAllowed() = runBlocking {
        val m = WikidataMetadata(fake(), allowPosters = true).lookup(WikidataMetadata.Kind.SERIES, "1399")!!
        assertEquals("https://upload.wikimedia.org/poster.jpg", m.posterUrl)
    }

    @Test fun rejectsNonNumericIdsWithoutNetwork() = runBlocking {
        val http = fake()
        assertNull(WikidataMetadata(http).lookup(WikidataMetadata.Kind.MOVIE, "tt0137523"))
        assertNull(WikidataMetadata(http).lookup(WikidataMetadata.Kind.MOVIE, "550\" } DROP"))
        assertTrue(http.urls.isEmpty())
    }

    @Test fun failuresAndEmptyResultsReturnNull() = runBlocking {
        assertNull(WikidataMetadata(Fake { Resp(503, "") }).lookup(WikidataMetadata.Kind.MOVIE, "550"))
        assertNull(WikidataMetadata(Fake { Resp(200, """{"results":{"bindings":[]}}""") }).lookup(WikidataMetadata.Kind.MOVIE, "550"))
    }

    @Test fun sourceMetadataAlwaysWins() {
        val e = EnrichedMetadata("wiki plot", "wiki cast", "wiki dir", "wiki genre", null,
            "Wikipedia contributors · Title", "https://en.wikipedia.org/wiki/Title",
            "https://creativecommons.org/licenses/by-sa/4.0/")
        val merged = e.fillGaps(plot = "provider plot", cast = "", director = null, genre = "Drama", posterUrl = null)
        assertEquals("provider plot", merged.plot)
        assertNull(merged.attribution)
        assertEquals("wiki cast", merged.cast)
        assertEquals("wiki dir", merged.director)
        assertEquals("Drama", merged.genre)
        assertNull(merged.attributionUrl)
        assertNull(merged.attributionLicenseUrl)
    }
}
