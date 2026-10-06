package com.yodesla.omniverse.core.data.metadata

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RottenTomatoesDefaultTest {
    @Test fun privateBuildDefaultsOnWhenNeverSet() {
        assertTrue(RottenTomatoes.enabledFor(null, publicBuild = false))
    }

    @Test fun publicBuildDefaultsOffWhenNeverSet() {
        assertFalse(RottenTomatoes.enabledFor(null, publicBuild = true))
    }

    @Test fun explicitChoiceAlwaysWinsOverTheBuildDefault() {
        assertFalse(RottenTomatoes.enabledFor("false", publicBuild = false))
        assertFalse(RottenTomatoes.enabledFor("false", publicBuild = true))
        assertTrue(RottenTomatoes.enabledFor("true", publicBuild = true))
        assertTrue(RottenTomatoes.enabledFor("true", publicBuild = false))
    }
}
