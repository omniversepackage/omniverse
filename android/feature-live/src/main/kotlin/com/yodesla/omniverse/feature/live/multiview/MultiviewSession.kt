package com.yodesla.omniverse.feature.live.multiview

import com.yodesla.omniverse.core.model.ContentKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The app-wide Multiview SESSION (Task 67 redesign): the 1-4 channels the viewer has queued, held by
 * [com.yodesla.omniverse.app.AppGraph] so it survives navigation. Adding a channel from the Guide or
 * the Live list appends here; the Guide/Live header offers "Multiview · N" once [openable] (>= 2).
 *
 * Leaving Multiview (Back) does NOT touch the session — re-entering resumes the same channels. Only
 * [clear] (the tile menu's "End Multiview") empties it. Parental revalidation still happens in the
 * view model at play time; the session is just the user's intent, so a channel locked while away is
 * re-checked (and dropped from the grid) the next time Multiview opens.
 */
class MultiviewSession {
    private val _channels = MutableStateFlow<List<ContentKey>>(emptyList())
    val channels: StateFlow<List<ContentKey>> = _channels

    val size: Int get() = _channels.value.size

    /** Enough queued to be worth a Multiview grid (the header button's threshold). */
    val openable: Boolean get() = _channels.value.size >= MultiviewLayout.MIN_TILES

    fun contains(key: ContentKey): Boolean = key in _channels.value

    /** Appends [key] if it isn't already queued and the grid isn't full. Returns true when added. */
    fun add(key: ContentKey): Boolean {
        val cur = _channels.value
        if (key in cur || cur.size >= MultiviewLayout.MAX_TILES) return false
        _channels.value = cur + key
        return true
    }

    fun remove(key: ContentKey) {
        val cur = _channels.value
        if (key in cur) _channels.value = cur.filterNot { it == key }
    }

    /** Toggle a channel's membership. Returns true when it is now in the session. */
    fun toggle(key: ContentKey): Boolean = if (key in _channels.value) { remove(key); false } else add(key)

    /** Replace one queued channel with another, keeping its position (a tile swap). */
    fun replace(oldKey: ContentKey, newKey: ContentKey) {
        val cur = _channels.value
        if (oldKey !in cur || newKey in cur) return
        _channels.value = cur.map { if (it == oldKey) newKey else it }
    }

    /** Replace the whole session (the picker's confirm). */
    fun setAll(keys: List<ContentKey>) {
        _channels.value = keys.distinct().take(MultiviewLayout.MAX_TILES)
    }

    /** "End Multiview": clears the session entirely. */
    fun clear() {
        _channels.value = emptyList()
    }
}
