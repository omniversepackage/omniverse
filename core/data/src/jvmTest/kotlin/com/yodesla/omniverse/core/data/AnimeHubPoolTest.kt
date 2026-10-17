package com.yodesla.omniverse.core.data

import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.SourceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AnimeHubPoolTest {
    private fun c(id: String, cat: String?, genre: String?, group: String? = null, plex: Boolean = false, rating: Float? = 7f, src: String = "iptv") =
        AnimatedCandidate(
            PosterRow(ContentKey(SourceId(src), ContentKind.VOD, RemoteId(id)), id, null, 2000, rating, categoryId = RemoteId("c-$id")),
            cat, genre, group, plex, if (plex) 0 else 1, id.hashCode().toLong(),
        )

    @Test fun classifiesByWholeWords() {
        assertEquals(true, AnimeHubPool.classify("ANIME | SUB", null))
        assertEquals(true, AnimeHubPool.classify("Kids", "Animation, Anime"))
        assertEquals(false, AnimeHubPool.classify("Animation", null))
        assertEquals(false, AnimeHubPool.classify(null, "Family, Animated"))
        assertNull(AnimeHubPool.classify("Animal Planet", "Nature")) // the LIKE scan lets it in; the rule drops it
    }

    @Test fun plexCopyWinsTheGroupAndExclusionsApply() {
        val out = AnimeHubPool.build(
            listOf(
                c("a", "Anime", null, group = "g1"),
                c("b", null, "Anime", group = "g1", plex = true, src = "plex"),
                c("x", "Animation", null),
                c("locked", "Anime", null),
                c("drama", "Animals", "Drama"),
            ),
            excludedKeys = setOf("VOD|iptv|c-locked"),
        )
        assertEquals(listOf("b", "x"), out.map { it.row.key.remoteId.value }.sorted())
        assertEquals(true, out.first { it.row.key.remoteId.value == "b" }.anime)
        assertEquals(false, out.first { it.row.key.remoteId.value == "x" }.anime)
    }

    @Test fun bestRatedFirstUnratedLast() {
        val out = AnimeHubPool.build(listOf(c("lo", "Anime", null, rating = 5f), c("none", "Anime", null, rating = null), c("hi", "Anime", null, rating = 9f)), emptySet())
        assertEquals(listOf("hi", "lo", "none"), out.map { it.row.key.remoteId.value })
    }
}
