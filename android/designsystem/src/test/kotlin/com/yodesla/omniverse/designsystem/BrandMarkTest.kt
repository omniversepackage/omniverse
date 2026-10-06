package com.yodesla.omniverse.designsystem

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrandMarkTest {
    @Test
    fun privateBuildUsesTheBundledLogo() {
        assertTrue(brandMark(CategoryBrand.NETFLIX, publicBuild = false) is BrandMark.Logo)
        assertTrue(brandMark(CategoryBrand.HULU, publicBuild = false) is BrandMark.Logo)
    }

    @Test
    fun publicBuildUsesATextMarkInTheBrandColor() {
        val mark = brandMark(CategoryBrand.NETFLIX, publicBuild = true)
        assertTrue(mark is BrandMark.Text)
        mark as BrandMark.Text
        assertEquals("Netflix", mark.label)
        assertEquals(Color(0xFFE50914), mark.color)
    }

    @Test
    fun publicBuildNamesEveryService() {
        val expected = mapOf(
            CategoryBrand.NETFLIX to "Netflix",
            CategoryBrand.DISNEY_PLUS to "Disney+",
            CategoryBrand.PRIME_VIDEO to "Prime Video",
            CategoryBrand.APPLE_TV_PLUS to "Apple TV+",
            CategoryBrand.HULU to "Hulu",
            CategoryBrand.MAX to "Max",
            CategoryBrand.PARAMOUNT_PLUS to "Paramount+",
            CategoryBrand.PEACOCK to "Peacock",
            CategoryBrand.CRUNCHYROLL to "Crunchyroll",
        )
        for ((brand, name) in expected) {
            val mark = brandMark(brand, publicBuild = true)
            assertTrue("$brand should render a text mark", mark is BrandMark.Text)
            assertEquals(name, (mark as BrandMark.Text).label)
        }
    }
}
