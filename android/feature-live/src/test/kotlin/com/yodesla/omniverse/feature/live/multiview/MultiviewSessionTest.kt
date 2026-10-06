package com.yodesla.omniverse.feature.live.multiview

import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.SourceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Task 67: the app-wide Multiview session is just the viewer's queued intent — it caps at the grid
 * size, survives navigation (nothing here clears it implicitly), and only [clear] empties it.
 */
class MultiviewSessionTest {
    private val s1 = SourceId("src1")
    private val s2 = SourceId("src2")
    private fun key(src: SourceId, id: String) = ContentKey(src, ContentKind.LIVE, RemoteId(id))
    private val a = key(s1, "1")
    private val b = key(s1, "2")
    private val c = key(s2, "3")
    private val d = key(s2, "4")
    private val e = key(s2, "5")

    @Test fun startsEmptyAndNotOpenable() {
        val s = MultiviewSession()
        assertEquals(0, s.size)
        assertFalse(s.openable)
    }

    @Test fun openableOnceTwoQueued() {
        val s = MultiviewSession()
        assertTrue(s.add(a))
        assertFalse(s.openable)
        assertTrue(s.add(b))
        assertTrue(s.openable)
    }

    @Test fun addIgnoresDuplicatesAndCapsAtMaxTiles() {
        val s = MultiviewSession()
        s.setAll(listOf(a, b, c, d))
        assertEquals(MultiviewLayout.MAX_TILES, s.size)
        assertFalse(s.add(a))
        assertFalse(s.add(e))
        assertEquals(MultiviewLayout.MAX_TILES, s.size)
    }

    @Test fun toggleAddsThenRemoves() {
        val s = MultiviewSession()
        assertTrue(s.toggle(a))
        assertTrue(s.contains(a))
        assertFalse(s.toggle(a))
        assertFalse(s.contains(a))
    }

    @Test fun replaceKeepsPositionAndIgnoresUnknownOrDuplicate() {
        val s = MultiviewSession()
        s.setAll(listOf(a, b, c))
        s.replace(b, e)
        assertEquals(listOf(a, e, c), s.channels.value)
        s.replace(b, a)
        assertEquals(listOf(a, e, c), s.channels.value)
        s.replace(e, e)
        assertEquals(listOf(a, e, c), s.channels.value)
    }

    @Test fun setAllDedupesAndCaps() {
        val s = MultiviewSession()
        s.setAll(listOf(a, a, b, c, d, e))
        assertEquals(listOf(a, b, c, d), s.channels.value)
    }

    @Test fun removeAndClear() {
        val s = MultiviewSession()
        s.setAll(listOf(a, b, c))
        s.remove(b)
        assertEquals(listOf(a, c), s.channels.value)
        s.remove(key(s1, "nope"))
        assertEquals(listOf(a, c), s.channels.value)
        s.clear()
        assertEquals(0, s.size)
        assertFalse(s.openable)
    }
}
