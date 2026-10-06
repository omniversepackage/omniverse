package com.yodesla.omniverse.feature.live.multiview

import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.SourceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Task 55: the Multiview grid, D-pad focus, audio and picker rules, without Compose. */
class MultiviewLayoutTest {
    private val src = SourceId("s")
    private fun key(id: String) = ContentKey(src, ContentKind.LIVE, RemoteId(id))

    @Test
    fun twoChannelsSitSideBySide() {
        assertEquals(MultiviewGrid(1, 2), MultiviewLayout.gridFor(2))
    }

    @Test
    fun threeAndFourUseAQuad() {
        assertEquals(MultiviewGrid(2, 2), MultiviewLayout.gridFor(3))
        assertEquals(MultiviewGrid(2, 2), MultiviewLayout.gridFor(4))
    }

    @Test
    fun everyDirectionMovesToTheNeighbouringTile() {
        assertEquals(1, MultiviewLayout.neighbor(0, 4, PadDirection.RIGHT))
        assertEquals(2, MultiviewLayout.neighbor(0, 4, PadDirection.DOWN))
        assertEquals(0, MultiviewLayout.neighbor(1, 4, PadDirection.LEFT))
        assertEquals(3, MultiviewLayout.neighbor(1, 4, PadDirection.DOWN))
        assertEquals(0, MultiviewLayout.neighbor(2, 4, PadDirection.UP))
        assertEquals(3, MultiviewLayout.neighbor(2, 4, PadDirection.RIGHT))
        assertEquals(1, MultiviewLayout.neighbor(3, 4, PadDirection.UP))
        assertEquals(2, MultiviewLayout.neighbor(3, 4, PadDirection.LEFT))
    }

    @Test
    fun sideBySideOnlyMovesHorizontally() {
        assertEquals(1, MultiviewLayout.neighbor(0, 2, PadDirection.RIGHT))
        assertEquals(0, MultiviewLayout.neighbor(1, 2, PadDirection.LEFT))
        assertNull(MultiviewLayout.neighbor(0, 2, PadDirection.DOWN))
        assertNull(MultiviewLayout.neighbor(1, 2, PadDirection.UP))
    }

    @Test
    fun edgesAndTheEmptyQuadCellHaveNoNeighbour() {
        assertNull(MultiviewLayout.neighbor(0, 4, PadDirection.UP))
        assertNull(MultiviewLayout.neighbor(1, 4, PadDirection.RIGHT))
        assertNull(MultiviewLayout.neighbor(2, 4, PadDirection.DOWN))
        assertNull(MultiviewLayout.neighbor(3, 4, PadDirection.DOWN))
        // 3-tile quad: the bottom-right cell is empty, so those presses hit a hole.
        assertNull(MultiviewLayout.neighbor(1, 3, PadDirection.DOWN))
        assertNull(MultiviewLayout.neighbor(2, 3, PadDirection.RIGHT))
        assertNull(MultiviewLayout.neighbor(4, 4, PadDirection.UP))
        assertNull(MultiviewLayout.neighbor(-1, 4, PadDirection.UP))
    }

    @Test
    fun onlyTheFocusedTileIsAudible() {
        assertEquals(2, MultiviewLayout.audibleIndex(2, 4))
        assertEquals(0, MultiviewLayout.audibleIndex(0, 2))
        assertNull(MultiviewLayout.audibleIndex(-1, 4))
        assertNull(MultiviewLayout.audibleIndex(3, 3))
    }

    @Test
    fun togglingKeepsPickOrderAndCapsAtFour() {
        val a = key("a")
        val b = key("b")
        val c = key("c")
        val d = key("d")
        val e = key("e")
        assertEquals(listOf(a), MultiviewLayout.toggled(emptyList(), a))
        var sel = emptyList<ContentKey>()
        for (k in listOf(a, b, c, d)) sel = MultiviewLayout.toggled(sel, k)
        assertEquals(listOf(a, b, c, d), sel)
        // A fifth pick is ignored; the badges stay 1-4 in pick order.
        assertEquals(listOf(a, b, c, d), MultiviewLayout.toggled(sel, e))
        // OK on a picked channel removes it; re-picking appends it last.
        assertEquals(listOf(a, c, d), MultiviewLayout.toggled(sel, b))
        assertEquals(listOf(a, c, d, b), MultiviewLayout.toggled(MultiviewLayout.toggled(sel, b), b))
    }
}
