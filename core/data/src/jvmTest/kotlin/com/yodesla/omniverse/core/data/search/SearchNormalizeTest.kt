package com.yodesla.omniverse.core.data.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SearchNormalizeTest {
    @Test fun foldsAccentsToAsciiBase() {
        assertEquals("cafe", normalize("Café"))
        assertEquals("zoe", normalize("Zoë"))
        assertEquals("naive facade", normalize("Naïve Façade"))
        assertEquals("german strasse", normalize("German Straße"))
    }

    @Test fun foldsRomanNumeralsToArabic() {
        assertEquals("matrix 2", normalize("The Matrix II"))
        assertEquals("ocean 11", normalize("Ocean XI"))
        assertEquals("part 3", normalize("Part III"))
        assertEquals("lion king 2", normalize("The Lion King II"))
        // Not a valid roman numeral: left as-is.
        assertEquals("xxo", normalize("XXO"))
    }

    @Test fun foldsAmpersandToAnd() {
        assertEquals("law and order", normalize("Law & Order"))
        assertEquals("r and d", normalize("R&D"))
        assertEquals("rock and roll", normalize("Rock and Roll"))
    }

    @Test fun dropsLeadingArticlesAndCollapsesSeparators() {
        assertEquals("apple", normalize("An  Apple!"))
        assertEquals("night at the opera", normalize("A Night at the Opera"))
        assertEquals("multiple spaces", normalize("  multiple   spaces  "))
        assertEquals("", normalize("The"))
    }

    @Test fun emptyInputNormalizesToEmpty() {
        assertEquals("", normalize(""))
        assertEquals("", normalize("   "))
        assertEquals("", normalize("!!!"))
    }

    @Test fun levenshteinCountsEdits() {
        assertEquals(0, levenshtein("abc", "abc"))
        assertEquals(3, levenshtein("kitten", "sitting"))
        assertEquals(3, levenshtein("", "abc"))
        assertEquals(3, levenshtein("abc", ""))
    }

    @Test fun closestMatchesEquivalentSpellings() {
        assertEquals("The Matrix II", closest("matrix 2", listOf("The Matrix II")))
        assertEquals("Law & Order", closest("law and order", listOf("Law & Order")))
        assertEquals("Café", closest("cafe", listOf("Café")))
    }

    @Test fun closestRespectsDistanceCap() {
        assertEquals("cot", closest("cat", listOf("cot")))
        assertEquals("cats", closest("cat", listOf("cats")))
        assertNull(closest("cat", listOf("dog"))) // distance 3 > 2
        assertNull(closest("abcd", listOf("wxyz"))) // distance 4 > 2
        assertEquals("wxyz", closest("abcd", listOf("wxyz"), maxDistance = 4))
        // Picks the nearest candidate within the cap.
        assertEquals("matrix 2", closest("matrix", listOf("matrix 2", "matrix 22")))
    }

    @Test fun closestHandlesEmptyInput() {
        assertNull(closest("", listOf("anything")))
        assertNull(closest("query", emptyList()))
        assertNull(closest("!!!", listOf("!!!")))
    }
}
