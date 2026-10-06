package com.yodesla.omniverse.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CategoryBrandTest {
    @Test
    fun brandCategories() {
        assertEquals(CategoryBrand.NETFLIX, categoryBrand("Netflix Shows"))
        assertEquals(CategoryBrand.DISNEY_PLUS, categoryBrand("Disney+ Movies"))
        assertEquals(CategoryBrand.PRIME_VIDEO, categoryBrand("Prime Video"))
        assertEquals(CategoryBrand.APPLE_TV_PLUS, categoryBrand("Apple TV+"))
        assertEquals(CategoryBrand.HULU, categoryBrand("Hulu"))
        assertEquals(CategoryBrand.MAX, categoryBrand("HBO Max"))
    }

    @Test
    fun caseAndPunctuationAreIgnored() {
        assertEquals(CategoryBrand.NETFLIX, categoryBrand("NETFLIX | Series"))
        assertEquals(CategoryBrand.NETFLIX, categoryBrand("netflix - 4k"))
        assertEquals(CategoryBrand.DISNEY_PLUS, categoryBrand("disney+ originals"))
        assertEquals(CategoryBrand.PRIME_VIDEO, categoryBrand("  prime-video  "))
        assertEquals(CategoryBrand.APPLE_TV_PLUS, categoryBrand("APPLE TV +"))
        assertEquals(CategoryBrand.HULU, categoryBrand("hulu | movies"))
        assertEquals(CategoryBrand.MAX, categoryBrand("max"))
    }

    @Test
    fun lookalikesAreNotBrands() {
        assertNull(categoryBrand("Maximum"))
        assertNull(categoryBrand("Prime Sports"))
        assertNull(categoryBrand("Apple News"))
        assertNull(categoryBrand("Disney Junior"))
        assertNull(categoryBrand("Huluween"))
        assertNull(categoryBrand("Netflixian"))
        assertNull(categoryBrand("All"))
        assertNull(categoryBrand(""))
    }

    @Test fun recognizesParamountPeacockAndCrunchyrollButNotLookalikes() {
        assertEquals(CategoryBrand.PARAMOUNT_PLUS, categoryBrand("Paramount+"))
        assertEquals(CategoryBrand.PARAMOUNT_PLUS, categoryBrand("EN | Paramount Plus Shows"))
        assertEquals(CategoryBrand.PARAMOUNT_PLUS, categoryBrand("Paramount"))
        assertEquals(CategoryBrand.PEACOCK, categoryBrand("Peacock Movies"))
        assertEquals(CategoryBrand.CRUNCHYROLL, categoryBrand("Crunchyroll Anime"))
        assertEquals(null, categoryBrand("Paramount Network"))
        assertEquals(null, categoryBrand("Paramount Pictures Classics"))
    }
}
