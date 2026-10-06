package com.yodesla.omniverse.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class TrackPreferenceTest {
    @Test fun emptyRoundTripsToEmpty() {
        assertEquals(TrackPreference.EMPTY, TrackPreference.decode(TrackPreference.EMPTY.encode()))
        assertTrue(TrackPreference.EMPTY.isEmpty)
        assertTrue(TrackPreference.decode(null).isEmpty)
        assertTrue(TrackPreference.decode("").isEmpty)
        assertTrue(TrackPreference.decode("   ").isEmpty)
    }

    @Test fun audioOnlyRoundTrips() {
        val p = TrackPreference("eng", null, null)
        assertEquals(p, TrackPreference.decode(p.encode()))
        assertFalse(p.isEmpty)
    }

    @Test fun subtitleModesRoundTrip() {
        for (p in listOf(
            TrackPreference(null, SubtitleMode.OFF, null),
            TrackPreference("eng", SubtitleMode.OFF, null),
            TrackPreference("eng", SubtitleMode.FORCED, "spa"),
            TrackPreference("eng", SubtitleMode.FULL, "por"),
        )) assertEquals(p, TrackPreference.decode(p.encode()))
    }

    @Test fun garbageDecodesToEmpty() {
        assertEquals(TrackPreference.EMPTY, TrackPreference.decode("nonsense"))
        assertEquals(TrackPreference.EMPTY, TrackPreference.decode("a=;s="))
    }

    @Test fun matchesLanguageCaseInsensitivelyIgnoringRole() {
        val subs = listOf(Track("0:0", "English", "eng", false), Track("0:1", "Spanish", "spa", false))
        assertEquals("0:1", matchingTrackId(subs, "SPA", null))
        assertEquals("0:0", matchingTrackId(subs, " eng ", null))
    }

    @Test fun matchesForcedOnlyRoleWhenAsked() {
        val subs = listOf(
            Track("0:0", "English full", "eng", false, forcedOnly = false),
            Track("0:1", "English forced", "eng", false, forcedOnly = true),
        )
        assertEquals("0:1", matchingTrackId(subs, "eng", true))
        assertEquals("0:0", matchingTrackId(subs, "eng", false))
    }

    @Test fun neverMatchesByIndex() {
        val audio = listOf(Track("0:0", "Default", "jpn", true), Track("0:1", "Dub", "eng", false))
        assertEquals("0:1", matchingTrackId(audio, "eng", null))
    }

    @Test fun absentLanguageOrRoleFallsBackToNull() {
        val subs = listOf(Track("0:0", "English", "eng", false, forcedOnly = false))
        assertNull(matchingTrackId(subs, "deu", null))
        assertNull(matchingTrackId(subs, "eng", true))
        assertNull(matchingTrackId(subs, null, null))
        assertNull(matchingTrackId(subs, "  ", null))
        assertNull(matchingTrackId(emptyList(), "eng", null))
    }
}
