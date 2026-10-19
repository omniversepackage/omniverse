package com.yodesla.omniverse.core.data

import com.yodesla.omniverse.core.data.search.FastTitleSearch
import com.yodesla.omniverse.core.data.search.TitleHit
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.SourceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FastTitleSearchTest {
    private fun hit(id: String, name: String, rating: Float? = 7f, group: String? = null, plex: Int = 1, sub: Boolean = false, cat: String = "c", src: String = "s") =
        TitleHit(PosterRow(ContentKey(SourceId(src), ContentKind.VOD, RemoteId(id)), name, null, 2000, rating, categoryId = RemoteId(cat)), group, plex, 0, id.hashCode().toLong(), sub)

    @Test fun tiersExactStartsWordMidword() {
        assertEquals(0, FastTitleSearch.tier("Naruto", "naruto"))
        assertEquals(1, FastTitleSearch.tier("Naruto Shippuden", "naruto"))
        assertEquals(2, FastTitleSearch.tier("Boruto: Naruto Next Generations", "naruto"))
        assertEquals(3, FastTitleSearch.tier("Dragonball Z", "ball"))
        assertEquals("pokemon", FastTitleSearch.normalize("Pokémon"))
    }

    @Test fun ordersByTierThenRatingAndCollapsesPlexFirst() {
        val hits = listOf(
            hit("a", "Dragon Ball Z", 8f),
            hit("b", "Dragon", 6f),
            hit("c", "Dragonball", 9f, sub = true),
            hit("d", "Dragon", 6f, group = "g", plex = 1, src = "iptv"),
            hit("e", "Dragon", 6f, group = "g", plex = 0, src = "plex"),
        )
        val (rows, more) = FastTitleSearch.rank(hits, "dragon", emptySet(), "VOD", 10)
        assertEquals(false, more)
        // "Dragon" copies collapse to the Plex one; exact ("Dragon") before starts-with ("Dragon Ball Z", "Dragonball").
        assertEquals(4, rows.size)
        assertTrue(rows.first().name == "Dragon")
        assertEquals(1, rows.count { it.key.sourceId.value == "plex" })
        assertEquals(0, rows.count { it.key.sourceId.value == "iptv" && it.name == "Dragon" && it.key.remoteId.value == "d" })
    }

    @Test fun excludedCategoriesNeverAppearAndLimitReportsMore() {
        val hits = (1..5).map { hit("$it", "Show $it", cat = if (it == 1) "locked" else "ok") }
        val (rows, more) = FastTitleSearch.rank(hits, "show", setOf("VOD|s|locked"), "VOD", 3)
        assertEquals(3, rows.size)
        assertTrue(more)
        assertTrue(rows.none { it.categoryId?.value == "locked" })
    }
}
