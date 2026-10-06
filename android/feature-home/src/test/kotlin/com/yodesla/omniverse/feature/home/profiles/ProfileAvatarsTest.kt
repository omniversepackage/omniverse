package com.yodesla.omniverse.feature.home.profiles

import com.yodesla.omniverse.core.data.ProfileRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Task 84b: the pixel-art avatar set is validated before it ever reaches the Canvas. */
class ProfileAvatarsTest {
    @Test
    fun pixelSetCompletesTheAvatarRange() {
        assertEquals(ProfileRepository.AVATAR_COUNT - 8, PixelAvatars.size)
    }

    @Test
    fun everyGridIs16By16AndOnlyUsesItsPalette() {
        for (def in PixelAvatars) {
            assertEquals(16, def.grid.size, "${def.name}: row count")
            def.grid.forEachIndexed { r, row ->
                assertEquals(16, row.length, "${def.name}: row $r length")
                assertTrue(row.all { it in def.palette }, "${def.name}: row $r uses a character outside its palette")
            }
        }
    }

    @Test
    fun everyGridPaintsSomething() {
        for (def in PixelAvatars) {
            assertTrue(def.grid.any { row -> row.any { it != '.' } }, "${def.name}: grid is empty")
        }
    }

    @Test
    fun avatarNamesAreUnique() {
        assertEquals(PixelAvatars.map { it.name }.distinct().size, PixelAvatars.size)
    }
}
