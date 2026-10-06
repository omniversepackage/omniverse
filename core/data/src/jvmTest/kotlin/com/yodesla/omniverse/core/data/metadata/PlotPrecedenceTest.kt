package com.yodesla.omniverse.core.data.metadata

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Task 84f: provider plot -> TMDB overview -> Wikidata/Wikipedia summary. */
class PlotPrecedenceTest {
    @Test fun sourcePlotAlwaysWins() {
        assertEquals("source", pickPlot("source", "tmdb", "wiki"))
        assertEquals("source", pickPlot("source", null, null))
    }

    @Test fun tmdbOverviewIsTheFallbackForAnAbsentSourcePlot() {
        assertEquals("tmdb", pickPlot(null, "tmdb", "wiki"))
        assertEquals("tmdb", pickPlot("", "tmdb", "wiki"))
        assertEquals("tmdb", pickPlot("   ", "tmdb", "wiki"))
        assertEquals("tmdb", pickPlot("\t\n ", "tmdb", "wiki"))
    }

    @Test fun wikidataSummaryIsTheLastResort() {
        assertEquals("wiki", pickPlot(null, null, "wiki"))
        assertEquals("wiki", pickPlot("", "  ", "wiki"))
    }

    /** IPTV providers send "" far more often than they send nothing; "" is never a plot. */
    @Test fun blankStringsEverywhereMeanNoPlot() {
        assertNull(pickPlot(null, null, null))
        assertNull(pickPlot("", "", ""))
        assertNull(pickPlot("   ", "\t", "\n "))
    }

    @Test fun theWikipediaTierIsOptional() {
        assertNull(pickPlot(null, null))
        assertNull(pickPlot("", null))
        assertEquals("tmdb", pickPlot(null, "tmdb"))
    }
}
