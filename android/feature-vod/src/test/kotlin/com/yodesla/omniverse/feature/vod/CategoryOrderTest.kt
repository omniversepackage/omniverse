package com.yodesla.omniverse.feature.vod

import kotlin.test.Test
import kotlin.test.assertEquals

class CategoryOrderTest {
    private val cats = listOf("a", "b", "c", "d").map { VodCategoryUi(it, it.uppercase()) }

    @Test fun savedOrderFirstThenProviderOrder() {
        assertEquals(listOf("c", "a", "b", "d"), sortBySaved(cats, listOf("c", "a")).map { it.id })
    }

    @Test fun unknownSavedIdsAreIgnored() {
        assertEquals(listOf("b", "a", "c", "d"), sortBySaved(cats, listOf("gone", "b")).map { it.id })
    }

    @Test fun emptySavedKeepsProviderOrder() {
        assertEquals(cats, sortBySaved(cats, emptyList()))
    }
}
