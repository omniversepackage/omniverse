package com.yodesla.omniverse.designsystem

import org.junit.Assert.assertEquals
import org.junit.Test

class ComponentsTest {
    @Test
    fun initialsSkipPrefixes() {
        assertEquals("CH", initials("US: CNN HD"))
        assertEquals("BO", initials("BBC One"))
        assertEquals("S", initials("[HD] Sky"))
        assertEquals("2K", initials("24 Kitchen"))
        assertEquals("?", initials(""))
        assertEquals(paletteIndex("BBC One"), paletteIndex("BBC One"))
    }

    @Test
    fun tierDecision() {
        assertEquals(VisualTier.LITE, decideTier(lowRam = true, memoryClassMb = 512, sdk = 34))
        assertEquals(VisualTier.LITE, decideTier(lowRam = false, memoryClassMb = 192, sdk = 34))
        assertEquals(VisualTier.LITE, decideTier(lowRam = false, memoryClassMb = 512, sdk = 26))
        assertEquals(VisualTier.CINEMATIC, decideTier(lowRam = false, memoryClassMb = 256, sdk = 30))
    }
}
