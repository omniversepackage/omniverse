package com.yodesla.omniverse.feature.live

import com.yodesla.omniverse.core.data.ChannelRow
import com.yodesla.omniverse.core.model.*
import kotlin.test.*

class UnifiedLiveCatalogTest {
    private fun row(source: String, name: String) = ChannelRow(ContentKey(SourceId(source), ContentKind.LIVE, RemoteId("1")), 1, name, null, "same-epg", 0, RemoteId("same-category"))

    @Test fun qualityCopiesGroupButDifferentFeedsRemainSeparate() {
        val rows = listOf(row("a", "ESPN HD"), row("b", "espn FHD"), row("a", "ESPN +1"), row("b", "UK: ESPN"), row("a", "ESPN+"))
        assertEquals(listOf("ESPN HD", "ESPN +1", "UK: ESPN", "ESPN+"), groupedLiveChannels(rows).map { it.name })
        assertEquals("espn", liveChannelIdentity(" ESPN HD HEVC "))
    }

    @Test fun sourceQualifiedIdsKeepCollidingCategoryAndEpgKeysSeparate() {
        val a = row("a:long", "ESPN")
        val b = row("b", "ESPN")
        assertNotEquals(liveEpgKey(a), liveEpgKey(b))
        val id = liveScopedId(a.key.sourceId, a.categoryId.value)
        assertEquals(a.key.sourceId to a.categoryId.value, liveScope(id, b.key.sourceId))
        assertEquals(b.key.sourceId to "legacy", liveScope("legacy", b.key.sourceId))
    }
}
