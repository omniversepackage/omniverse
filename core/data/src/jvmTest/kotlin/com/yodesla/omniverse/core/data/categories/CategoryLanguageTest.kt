package com.yodesla.omniverse.core.data.categories

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Task 84h: the classifier table. Real-style panel names, including the traps: AR (Arabic) vs
 * ARG (Argentina), IN only as a pinned tag, UK vs UKRAINE, emoji suffixes, and untagged names.
 */
class CategoryLanguageTest {
    @Test
    fun panelNamesClassifyToTheirLanguageGroup() {
        val table = listOf(
            // English
            "US| SPORTS" to CategoryLanguage.ENGLISH,
            "UK: NEWS" to CategoryLanguage.ENGLISH,
            "GB Kids" to CategoryLanguage.ENGLISH,
            "USA Movies" to CategoryLanguage.ENGLISH,
            "EN" to CategoryLanguage.ENGLISH,
            "English" to CategoryLanguage.ENGLISH,
            "News·US" to CategoryLanguage.ENGLISH,
            "Canada TV" to CategoryLanguage.ENGLISH,
            "Australia" to CategoryLanguage.ENGLISH,
            // Arabic
            "AR - MBC" to CategoryLanguage.ARABIC,
            "AR" to CategoryLanguage.ARABIC,
            "ARABIC" to CategoryLanguage.ARABIC,
            "AR | Arabic" to CategoryLanguage.ARABIC,
            "SA | Saudi Arabia" to CategoryLanguage.ARABIC,
            "EG" to CategoryLanguage.ARABIC,
            "AE Drama" to CategoryLanguage.ARABIC,
            // French / German
            "|FR| CINEMA" to CategoryLanguage.FRENCH,
            "France 24" to CategoryLanguage.FRENCH,
            "DE ▎ DOKU" to CategoryLanguage.GERMAN,
            "Deutsch" to CategoryLanguage.GERMAN,
            "AT" to CategoryLanguage.GERMAN,
            "CH" to CategoryLanguage.GERMAN,
            // Spanish / Portuguese / Italian / Dutch
            "ES:DEPORTES" to CategoryLanguage.SPANISH,
            "LATINO" to CategoryLanguage.SPANISH,
            "LATAM" to CategoryLanguage.SPANISH,
            "MX Novelas" to CategoryLanguage.SPANISH,
            "ARG" to CategoryLanguage.SPANISH,
            "ARGENTINA" to CategoryLanguage.SPANISH,
            "Latinoamérica" to CategoryLanguage.SPANISH,
            "PT | Portuguese" to CategoryLanguage.PORTUGUESE,
            "BR | Brasil" to CategoryLanguage.PORTUGUESE,
            "IT | Italian" to CategoryLanguage.ITALIAN,
            "Italia" to CategoryLanguage.ITALIAN,
            "NL" to CategoryLanguage.DUTCH,
            "Holland" to CategoryLanguage.DUTCH,
            // Central/Eastern Europe + Asia
            "PL | Polish" to CategoryLanguage.POLISH,
            "Polska" to CategoryLanguage.POLISH,
            "TR | Türk" to CategoryLanguage.TURKISH,
            "RU | Russian" to CategoryLanguage.RUSSIAN,
            "GR | Greek" to CategoryLanguage.GREEK,
            "RO | Romanian" to CategoryLanguage.ROMANIAN,
            "AL | Albania" to CategoryLanguage.ALBANIAN,
            "IN | HINDI" to CategoryLanguage.HINDI,
            "PK | Urdu" to CategoryLanguage.URDU,
            "IR | Persian" to CategoryLanguage.PERSIAN,
            "KU | Kurdish" to CategoryLanguage.KURDISH,
            // Nordic / African / Ex-Yu
            "NORDIC" to CategoryLanguage.NORDIC,
            "SE | Sweden" to CategoryLanguage.NORDIC,
            "NO" to CategoryLanguage.NORDIC,
            "DK" to CategoryLanguage.NORDIC,
            "FI" to CategoryLanguage.NORDIC,
            "EX-YU" to CategoryLanguage.EXYU,
            "EX-YU | Balkan" to CategoryLanguage.EXYU,
            "Serbian" to CategoryLanguage.EXYU,
            "HR" to CategoryLanguage.EXYU,
            "AFRICA" to CategoryLanguage.AFRICAN,
            "ZA" to CategoryLanguage.AFRICAN,
            // Untagged panel names
            "4K / UHD Channels 🏆" to CategoryLanguage.UNKNOWN,
            "NFL Game Pass 🏈" to CategoryLanguage.UNKNOWN,
            "News 24" to CategoryLanguage.UNKNOWN,
            "Movies On Demand" to CategoryLanguage.UNKNOWN,
            "Kids Zone" to CategoryLanguage.UNKNOWN,
            "International TV" to CategoryLanguage.UNKNOWN,
            "Regional TV" to CategoryLanguage.UNKNOWN,
            "Action" to CategoryLanguage.UNKNOWN,
            "Netflix Movies" to CategoryLanguage.UNKNOWN,
            "UKRAINE" to CategoryLanguage.UNKNOWN,
            "Movies IN 4K" to CategoryLanguage.UNKNOWN,
            "Mini Series" to CategoryLanguage.UNKNOWN,
            "BE Movies" to CategoryLanguage.UNKNOWN,
            "Россия" to CategoryLanguage.UNKNOWN,
        )
        assertEquals(71, table.size)
        for ((name, want) in table) assertEquals(want, categoryLanguage(name), name)
    }

    @Test
    fun conflictingTagsStayUnknown() {
        assertEquals(CategoryLanguage.UNKNOWN, categoryLanguage("AR EN"))
        assertEquals(CategoryLanguage.UNKNOWN, categoryLanguage("US FR"))
    }

    @Test
    fun filterOffAllowsEverythingAndUnknownFollowsKeepUntagged() {
        assertTrue(categoryLanguageAllowed(CategoryLanguage.UNKNOWN, emptySet(), keepUntagged = false))
        assertTrue(categoryLanguageAllowed(CategoryLanguage.ENGLISH, emptySet(), keepUntagged = false))
        assertTrue(categoryLanguageAllowed(CategoryLanguage.UNKNOWN, setOf(CategoryLanguage.ENGLISH), keepUntagged = true))
        assertFalse(categoryLanguageAllowed(CategoryLanguage.UNKNOWN, setOf(CategoryLanguage.ENGLISH), keepUntagged = false))
    }

    @Test
    fun selectedGroupsPassAndOthersAreHidden() {
        val allowed = setOf(CategoryLanguage.ENGLISH, CategoryLanguage.ARABIC)
        assertTrue(categoryLanguageAllowed(CategoryLanguage.ENGLISH, allowed, keepUntagged = false))
        assertTrue(categoryLanguageAllowed(CategoryLanguage.ARABIC, allowed, keepUntagged = false))
        assertFalse(categoryLanguageAllowed(CategoryLanguage.SPANISH, allowed, keepUntagged = true))
        assertFalse(categoryLanguageAllowed(CategoryLanguage.NORDIC, allowed, keepUntagged = true))
    }

    @Test
    fun settingCodecRoundTripsAndGarbageReadsBackAsAll() {
        assertEquals(emptySet(), parseCategoryLanguageFilter(null))
        assertEquals(emptySet(), parseCategoryLanguageFilter("all"))
        assertEquals(emptySet(), parseCategoryLanguageFilter("nonsense"))
        assertEquals(setOf(CategoryLanguage.ENGLISH), parseCategoryLanguageFilter("sel:ENGLISH"))
        assertEquals(
            setOf(CategoryLanguage.ENGLISH, CategoryLanguage.ARABIC),
            parseCategoryLanguageFilter("sel:ENGLISH, ARABIC"),
        )
        assertEquals(
            "sel:ENGLISH,ARABIC",
            encodeCategoryLanguageFilter(setOf(CategoryLanguage.ARABIC, CategoryLanguage.ENGLISH)),
        )
        assertEquals("all", encodeCategoryLanguageFilter(emptySet()))
        assertEquals(
            setOf(CategoryLanguage.ENGLISH, CategoryLanguage.ARABIC),
            parseCategoryLanguageFilter(encodeCategoryLanguageFilter(setOf(CategoryLanguage.ENGLISH, CategoryLanguage.ARABIC))),
        )
    }
}
