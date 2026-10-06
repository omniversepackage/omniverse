package com.yodesla.omniverse.feature.live

/**
 * Task 84g: the Live TV / Guide category order, shared by both view models so the Guide's list
 * always matches the order saved from the Live list (setting `category_order_LIVE`, the same
 * newline format as VOD's `category_order_VOD`).
 *
 * Same rule as VodBrowse's sortBySaved: saved ids come first in their saved order; provider
 * categories not in the list keep provider order after them; saved ids whose category is gone
 * are dropped. [pinned] (Favorites, All channels, Recently watched) always stay at the top in
 * the given order and can never be moved.
 */
internal fun <T> mergeCategoryOrder(pinned: List<T>, providers: List<T>, saved: List<String>, idOf: (T) -> String): List<T> {
    if (saved.isEmpty()) return pinned + providers
    val rank = saved.withIndex().associate { (i, id) -> id to i }
    val ordered = providers.withIndex().sortedWith(compareBy({ rank[idOf(it.value)] ?: Int.MAX_VALUE }, { it.index })).map { it.value }
    return pinned + ordered
}
