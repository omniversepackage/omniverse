package com.yodesla.omniverse.core.data

import kotlin.test.Test
import kotlin.test.assertEquals

class ForYouLayoutTest {
    @Test
    fun onlyTheLiteralMagazineSelectsTheMagazine() {
        assertEquals(ForYouLayout.CINEMATIC, ForYouLayout.parse(null))
        assertEquals(ForYouLayout.CINEMATIC, ForYouLayout.parse(""))
        assertEquals(ForYouLayout.CINEMATIC, ForYouLayout.parse("Magazine"))
        assertEquals(ForYouLayout.CINEMATIC, ForYouLayout.parse(ForYouLayout.CINEMATIC))
        assertEquals(ForYouLayout.MAGAZINE, ForYouLayout.parse(ForYouLayout.MAGAZINE))
    }
}
