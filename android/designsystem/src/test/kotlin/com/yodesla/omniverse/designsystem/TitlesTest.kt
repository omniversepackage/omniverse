package com.yodesla.omniverse.designsystem

import org.junit.Assert.assertEquals
import org.junit.Test

class TitlesTest {
    @Test fun sourcePictureTagDoesNotBecomeTheMovieTitle() {
        assertEquals("Dune", displayTitle("Dune 1080p", 2021))
        assertEquals("Dune", displayTitle("Dune (2021) 1080p", 2021))
        assertEquals("Dune", displayTitle("Dune 4K DV (2021)", 2021))
        assertEquals("Dune", displayTitle("Dune [1080p]", 2021))
    }

    @Test fun genuineTitleAndDifferentYearAreRetained() {
        assertEquals("The 4K Restoration", displayTitle("The 4K Restoration", null))
        assertEquals("Dune (1984)", displayTitle("Dune (1984)", 2021))
        assertEquals("Dune", displayTitle("Dune (2021)", 2021))
    }
}
