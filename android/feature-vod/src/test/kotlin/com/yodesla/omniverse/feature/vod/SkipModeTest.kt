package com.yodesla.omniverse.feature.vod

import com.yodesla.omniverse.core.data.SkipSettings
import com.yodesla.omniverse.core.model.SkipMarker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkipModeTest {
    @Test fun episodeSixUsesPlexCreditsInsteadOfEarlierOnlineMarker() {
        val duration = 1_420_968L
        val source = SkipMarker("credits", 1_324_871, duration)
        val wrongOnline = SkipMarker("credits", 971_697, 1_415_042)
        val intro = SkipMarker("intro", 274_157, 363_244)
        val resolved = preferredSkipMarkers(emptyList(), listOf(source), listOf(intro, wrongOnline), duration, true)
        assertEquals(listOf(intro, source), resolved)
        assertFalse(resolved.any { 1_000_000L in it.startMs until it.endMs })
        assertEquals(duration, autoSkipTarget(source, SkipSettings.AUTO, 1_325_000, true, emptySet(), duration))
    }

    @Test fun sourcePriorityIsPerKindEvenBeforeSourceMarkerBecomesActive() {
        val source = SkipMarker("credits", 1_300_000, 1_420_000)
        val earlierOnline = SkipMarker("credits", 1_200_000, 1_420_000)
        assertEquals(listOf(source), preferredSkipMarkers(emptyList(), listOf(source), listOf(earlierOnline), 1_420_000, true))
        val local = SkipMarker("credits", 1_350_000, 1_420_000)
        assertEquals(listOf(local), preferredSkipMarkers(listOf(local), listOf(source), listOf(earlierOnline), 1_420_000, true))
    }

    @Test fun episodeFivePreservesScenesBetweenCreditsAndOnlyFinalCreditsAdvance() {
        val duration = 1_421_010L
        val first = SkipMarker("credits", 1_334_909, 1_370_909)
        val last = SkipMarker("credits", 1_388_909, duration)
        val resolved = preferredSkipMarkers(emptyList(), listOf(first, last), emptyList(), duration, true)
        assertEquals(listOf(first, last), resolved)
        assertFalse(creditsFinishItem(first, duration))
        assertEquals(first.endMs, autoSkipTarget(first, SkipSettings.AUTO, first.startMs + 100, true, emptySet(), duration))
        assertTrue(creditsFinishItem(last, duration))
        assertFalse(resolved.any { 1_380_000L in it.startMs until it.endMs })
    }

    @Test fun implausibleCommunityCreditsCannotSkipLastThirdWithoutSourceMarkers() {
        val bad = SkipMarker("credits", 971_727, 1_415_083)
        assertTrue(preferredSkipMarkers(emptyList(), emptyList(), listOf(bad), 1_421_010, true).isEmpty())
        assertFalse(plausibleCommunityCredits(bad, 0, true))
        assertTrue(plausibleCommunityCredits(SkipMarker("credits", 1_330_000, 1_420_000), 1_420_000, true))
        assertTrue(plausibleCommunityCredits(SkipMarker("credits", 6_900_000, 7_200_000), 7_200_000, false))
    }

    private val intro = SkipMarker("intro", 0, 75_000)
    private val credits = SkipMarker("credits", 2_500_000, 2_600_000)

    @Test fun autoSeeksConfirmedWindowOnce() {
        assertEquals(75_000L, autoSkipTarget(intro, SkipSettings.AUTO, 1_000, true, emptySet()))
        assertNull(autoSkipTarget(intro, SkipSettings.AUTO, 1_000, true, setOf(markerToken(intro))))
        assertEquals(2_600_000L, autoSkipTarget(credits, SkipSettings.AUTO, 2_500_500, true, emptySet()))
    }

    @Test fun buttonOffAndNonPlayingNeverAutoSeek() {
        assertNull(autoSkipTarget(intro, SkipSettings.BUTTON, 1_000, true, emptySet()))
        assertNull(autoSkipTarget(intro, SkipSettings.OFF, 1_000, true, emptySet()))
        assertNull(autoSkipTarget(intro, SkipSettings.AUTO, 1_000, false, emptySet()))
    }

    @Test fun outsideOrTooNearEndDoesNotSeek() {
        assertNull(autoSkipTarget(credits, SkipSettings.AUTO, 2_499_000, true, emptySet()))
        assertNull(autoSkipTarget(intro, SkipSettings.AUTO, 74_500, true, emptySet()))
        assertNull(autoSkipTarget(null, SkipSettings.AUTO, 1_000, true, emptySet()))
    }

    @Test fun invalidOrOutOfRuntimeMarkersCannotSeekPastEpisode() {
        val beyondRuntime = SkipMarker("credits", 2_500_000, 2_700_000)
        assertFalse(isUsableSkipMarker(SkipMarker("credits", 20, 10), 100))
        assertFalse(isUsableSkipMarker(SkipMarker("bookmark", 10, 20), 100))
        assertFalse(isUsableSkipMarker(beyondRuntime, 2_650_000))
        assertNull(autoSkipTarget(beyondRuntime, SkipSettings.AUTO, 2_500_500, true, emptySet(), 2_650_000))
    }

    @Test fun validCreditsMarkerMayEndExactlyAtEpisodeDuration() {
        val endCredits = SkipMarker("credits", 2_500_000, 2_600_000)
        assertTrue(isUsableSkipMarker(endCredits, 2_600_000))
        assertEquals(2_600_000L,
            autoSkipTarget(endCredits, SkipSettings.AUTO, 2_500_500, true, emptySet(), 2_600_000))
    }

    @Test fun seriesCreditsOffsetUsesKnownEpisodeRuntimeAndRejectsImpossibleOffsets() {
        assertEquals(2_460_000L, creditsStartFromRemaining(2_600_000L, 140_000L))
        assertNull(creditsStartFromRemaining(0L, 140_000L))
        assertNull(creditsStartFromRemaining(2_600_000L, 4_999L))
        assertNull(creditsStartFromRemaining(2_600_000L, 2_600_000L))
        assertNull(creditsStartFromRemaining(2_600_000L, 3_000_000L))
        assertNull(creditsStartFromRemaining(2_600_000L, 2_596_000L))
    }

    @Test fun onlineMarkersFollowTheSameManualAutoAndOffPreferenceAsLocalMarkers() {
        assertEquals(SkipSettings.BUTTON, skipModeForType("intro", SkipSettings.BUTTON, SkipSettings.AUTO))
        assertEquals(SkipSettings.AUTO, skipModeForType("credits", SkipSettings.BUTTON, SkipSettings.AUTO))
        assertEquals(SkipSettings.OFF, skipModeForType("intro", SkipSettings.OFF, SkipSettings.AUTO))
        assertEquals(SkipSettings.BUTTON, skipModeForType("credits", "invalid", null))
    }

    @Test fun creditsOverhangPastStreamEndIsClampedNotRejected() {
        val m = com.yodesla.omniverse.core.model.SkipMarker("credits", 1_380_000, 1_445_000)
        val fitted = fitToDuration(m, 1_440_000)
        kotlin.test.assertEquals(1_440_000, fitted.endMs)
        kotlin.test.assertTrue(isUsableSkipMarker(fitted, 1_440_000))
        // A marker far past the end (wrong cut) is left alone and still rejected.
        kotlin.test.assertFalse(isUsableSkipMarker(fitToDuration(m.copy(endMs = 1_600_000), 1_440_000), 1_440_000))
    }
}
