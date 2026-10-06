package com.yodesla.omniverse.feature.vod

import kotlin.test.Test
import kotlin.test.assertEquals

class TrackLabelsTest {
    @Test fun twoLetterIsoCodeBecomesDisplayLanguage() {
        assertEquals("French", trackLabel("fr", null, false, false))
    }

    @Test fun threeLetterCodesResolveToDisplayLanguage() {
        assertEquals("French", trackLabel("fre", null, false, false))
        assertEquals("French", trackLabel("fra", null, false, false))
        assertEquals("English", trackLabel("eng", null, false, false))
    }

    @Test fun undeterminedAndMissingLanguagesUseUnknown() {
        assertEquals("Unknown", trackLabel("und", null, false, false))
        assertEquals("Unknown", trackLabel(null, null, false, false))
    }

    @Test fun forcedFlagAddsQualifier() {
        assertEquals("French · Forced", trackLabel("fr", null, true, false))
    }

    @Test fun sdhNeverStandsAlone() {
        assertEquals("English · SDH", trackLabel("eng", "SDH", false, false))
        assertEquals("English · CC", trackLabel("eng", "CC", false, false))
    }

    @Test fun duplicateSdhLabelsAreNumbered() {
        assertEquals(
            listOf("English · SDH", "English · SDH (2)", "English · SDH (3)"),
            dedupeLabels(listOf("English · SDH", "English · SDH", "English · SDH")),
        )
    }

    @Test fun audioCodecAndChannelAreKept() {
        assertEquals("English · 5.1 · EAC3", trackLabel("eng", "5.1 EAC3", false, true))
    }

    @Test fun subtitleCodecIsNotAdded() {
        assertEquals("English", trackLabel("eng", "EAC3", false, false))
    }

    @Test fun rawLanguageNameIsUsedWhenNoCode() {
        assertEquals("English", trackLabel(null, "English", false, false))
    }

    @Test fun rawIsoCodeIsUsedWhenNoLanguage() {
        assertEquals("French", trackLabel(null, "fr", false, false))
    }
}
