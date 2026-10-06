package com.yodesla.omniverse.feature.live

import kotlin.test.Test
import kotlin.test.assertEquals

/** Task 84g: the shared Live/Guide category order rule (same semantics as VOD's sortBySaved). */
class CategoryOrderTest {
    private val providers = listOf("a", "b", "c", "d").map { CategoryUi(it, it.uppercase()) }
    private val pinned = listOf(CategoryUi("__favorites", "Favorites", special = true), CategoryUi("__all", "All channels", special = true))

    @Test fun savedOrderFirstThenProviderOrder() {
        assertEquals(listOf("__favorites", "__all", "c", "a", "b", "d"), mergeCategoryOrder(pinned, providers, listOf("c", "a"), { it.id }).map { it.id })
    }

    @Test fun unknownSavedIdsAreIgnored() {
        assertEquals(listOf("__favorites", "__all", "b", "a", "c", "d"), mergeCategoryOrder(pinned, providers, listOf("gone", "b"), { it.id }).map { it.id })
    }

    @Test fun emptySavedKeepsProviderOrder() {
        assertEquals(pinned + providers, mergeCategoryOrder(pinned, providers, emptyList(), { it.id }))
    }

    @Test fun pinnedStayFirstEvenIfSavedMentionsThem() {
        assertEquals(listOf("__favorites", "__all", "d", "c", "b", "a"), mergeCategoryOrder(pinned, providers, listOf("__all", "d", "__favorites", "c", "b", "a"), { it.id }).map { it.id })
    }
}
