package com.yodesla.omniverse.feature.vod

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CollectionInputTest {
    @Test fun invalidFiltersAreNotSilentlyDropped() {
        assertNotNull(collectionInputError("", "", "", ""))
        assertNotNull(collectionInputError("Saved", "twenty", "", ""))
        assertNotNull(collectionInputError("Saved", "2026", "2000", ""))
        assertNotNull(collectionInputError("Saved", "", "", "NaN"))
        assertNotNull(collectionInputError("Saved", "", "", "11"))
        assertNull(collectionInputError("Saved", "2000", "2026", "7.5"))
        assertNull(collectionInputError("Saved", "", "", ""))
    }
}
