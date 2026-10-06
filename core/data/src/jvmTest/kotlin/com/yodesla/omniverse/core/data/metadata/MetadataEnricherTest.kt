package com.yodesla.omniverse.core.data.metadata

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

class MetadataEnricherTest {
    private val wiki = EnrichedMetadata("wiki plot", "wiki cast", "dir", "genre", null, "Plot from Wikipedia (CC BY-SA)")

    @Test fun offByDefaultMakesNoRequest() = runBlocking {
        var calls = 0
        val e = MetadataEnricher({ _, _ -> calls++; wiki }, enabled = { false })
        assertNull(e.enrich(WikidataMetadata.Kind.MOVIE, "550", null, null, null, null, null))
        assertEquals(0, calls)
    }

    @Test fun skipsWithoutIdOrWhenNothingIsMissing() = runBlocking {
        var calls = 0
        val e = MetadataEnricher({ _, _ -> calls++; wiki }, enabled = { true })
        assertNull(e.enrich(WikidataMetadata.Kind.MOVIE, null, null, null, null, null, null))
        assertNull(e.enrich(WikidataMetadata.Kind.MOVIE, "550", "p", "c", "d", "g", "u"))
        assertEquals(0, calls)
    }

    @Test fun cachesHitsAndMisses() = runBlocking {
        var calls = 0
        val e = MetadataEnricher({ _, id -> calls++; if (id == "550") wiki else null }, enabled = { true })
        repeat(3) { assertEquals("wiki plot", e.enrich(WikidataMetadata.Kind.MOVIE, "550", null, "c", "d", "g", "u")?.plot) }
        repeat(3) { assertNull(e.enrich(WikidataMetadata.Kind.MOVIE, "1", null, null, null, null, null)) }
        assertEquals(2, calls)
    }
}
