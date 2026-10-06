package com.yodesla.omniverse.core.data.impl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MatchKeyTest {
    @Test fun plexAndIptvSpellingsMatch() {
        val plex = MatchKey.of("The Matrix", 1999)
        assertEquals("matrix|1999", plex)
        assertEquals(plex, MatchKey.of("EN - The Matrix (1999)", null))
        assertEquals(plex, MatchKey.of("|EN| The Matrix 4K", 1999))
        assertEquals(plex, MatchKey.of("[UK] The Matrix [1999] HD", null))
        assertEquals(plex, MatchKey.of("4K-EN - The Matrix", 1999))
    }

    @Test fun punctuationAndAmpersand() {
        assertEquals(MatchKey.of("Fast & Furious", 2009), MatchKey.of("EN: Fast and Furious", 2009))
        assertEquals(MatchKey.of("Spider-Man: No Way Home", 2021), MatchKey.of("Spider Man No Way Home (2021)", null))
    }

    @Test fun differentYearsDontMatch() {
        assertEquals(false, MatchKey.of("Dune", 1984) == MatchKey.of("Dune", 2021))
    }

    @Test fun noYearNoKey() {
        assertNull(MatchKey.of("The Matrix", null))
        assertNull(MatchKey.of("   ", 1999))
    }

    @Test fun titleThatLooksLikeAPrefixIsKept() {
        // "M3GAN" must not be eaten as a provider prefix.
        assertEquals("m3gan|2022", MatchKey.of("M3GAN", 2022))
    }
}
