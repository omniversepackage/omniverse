package com.yodesla.omniverse.core.source.plex

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlexCutLabelsTest {
    @Test fun avatarRuntimesIdentifyTheVerifiedCuts() {
        assertEquals("Special Edition", plexCutLabel("Avatar", 2009, 10253, "Avatar (2009) - version1.mkv"))
        assertEquals("Collector's Extended Cut", plexCutLabel("Avatar", 2009, 10708, "Avatar (2009) - version2.mkv"))
        assertEquals("Collector's Extended Cut", plexCutLabel("Avatar", 2009, 10708, "Avatar (2009) - version3.mkv"))
        assertNull(plexCutLabel("Avatar", 2009, 10_000, "Avatar (2009) - version4.mkv"))
        assertNull(plexCutLabel("Other movie", 2009, 10708, "version2.mkv"))
    }

    @Test fun explicitFilenameEditionWins() {
        assertEquals("Director's Cut", plexCutLabel("Other movie", 2020, 5000, "Other.Movie.Directors.Cut.mkv"))
    }
}
