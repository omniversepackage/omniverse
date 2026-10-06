package com.yodesla.omniverse.core.data.metadata

import kotlin.test.Test
import kotlin.test.assertEquals

class RottenTomatoesParseTest {
    @Test fun readsBothScores() {
        val html = """x"audienceScore":{"certifiedFresh":"none","averageRating":"4.4","score":"91","scoreType":"ALL"} y "criticsScore":{"averageRating":"8.40","certified":true,"score":"86"}"""
        assertEquals(RtScores(86, 91), RottenTomatoes.parseScores(html))
    }

    @Test fun missingScoreIsNull() {
        val html = """"criticsScore":{"averageRating":null,"score":null} "audienceScore":{"score":"70"}"""
        assertEquals(RtScores(null, 70), RottenTomatoes.parseScores(html))
    }
}
