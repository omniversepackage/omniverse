package com.yodesla.omniverse.feature.vod

import com.yodesla.omniverse.designsystem.displayTitle
import kotlin.test.Test
import kotlin.test.assertEquals

class DisplayTitleTest {
    @Test
    fun stripsTrailingYearOnlyWhenItMatches() {
        assertEquals("Movie Title 1", displayTitle("Movie Title 1 (1998)", 1998))
        assertEquals("Dune [2021]", displayTitle("Dune [2021]", 2020))
        assertEquals("Blade Runner 2049", displayTitle("Blade Runner 2049", 2017))
        assertEquals("1917", displayTitle("1917 (2019)", 2019))
        assertEquals("(2019)", displayTitle("(2019)", 2019))
        assertEquals("No Year", displayTitle("No Year", null))
    }

    @Test
    fun cleansEpisodeTitles() {
        assertEquals("Episode 1", episodeTitle("Silent Protocol 1 (2021) S01E01", "Silent Protocol 1 (2021)", 1))
        assertEquals("The Heist", episodeTitle("Silent Protocol - S01E02 - The Heist", "Silent Protocol (2021)", 2))
        assertEquals("Pilot", episodeTitle("Pilot", "Lost", 1))
        assertEquals("Episode 3", episodeTitle("S01E03", "Lost", 3))
    }

    @Test
    fun formatsTimes() {
        assertEquals("0:05", fmt(5_000))
        assertEquals("1:02:03", fmt(3_723_000))
    }
}
