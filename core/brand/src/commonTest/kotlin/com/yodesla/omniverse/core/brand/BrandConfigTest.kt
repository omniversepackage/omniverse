package com.yodesla.omniverse.core.brand

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrandConfigTest {
    @Test
    fun parsesMinimalBrandWithDefaults() {
        val b = BrandConfig.parse(
            """{"id":"x","appName":"X","applicationId":"com.x","accentColor":"#112233","disclaimer":"d","extra":1}""",
        )
        assertEquals("X", b.appName)
        assertEquals(ExperienceMode.SIMPLE, b.defaultExperienceMode)
        assertTrue(b.features.allowAddSource)
        assertNull(b.lockedPortal)
    }

    @Test
    fun publicBuildDefaultsFalse() {
        val b = BrandConfig.parse(
            """{"id":"x","appName":"X","applicationId":"com.x","accentColor":"#112233","disclaimer":"d"}""",
        )
        assertFalse(b.publicBuild)
    }

    @Test
    fun parsesPublicBuildTrue() {
        val b = BrandConfig.parse(
            """{"id":"p","appName":"P","applicationId":"com.p","accentColor":"#000000","disclaimer":"d","publicBuild":true}""",
        )
        assertTrue(b.publicBuild)
    }

    @Test
    fun parsesLockedPortal() {
        val b = BrandConfig.parse(
            """{"id":"p","appName":"P","applicationId":"com.p","accentColor":"#000000","disclaimer":"d",
               "defaultExperienceMode":"full",
               "lockedPortal":{"displayName":"P TV","servers":["http://a:80","http://b:80"]}}""",
        )
        assertEquals(ExperienceMode.FULL, b.defaultExperienceMode)
        assertEquals(2, b.lockedPortal!!.servers.size)
    }
}
