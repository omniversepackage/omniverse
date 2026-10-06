package com.yodesla.omniverse.feature.vod

import com.yodesla.omniverse.core.data.PosterRow
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.MediaVersion
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.SourceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val poSrc = SourceId("plex")
private val poKey = ContentKey(poSrc, ContentKind.VOD, RemoteId("m1"))

private fun poAlt(source: String, remote: String, edition: String?): RelatedMovie =
    RelatedMovie(PosterRow(ContentKey(SourceId(source.lowercase()), ContentKind.VOD, RemoteId(remote)), "Title", null, 2024, null), source, edition)

private val poNone = emptyList<RelatedMovie>()

/** Chip list building from the item's own versions plus exact-ID alternatives. */
class PlayOptionChipsTest {
    @Test fun aTitleWithNoAlternativesHasOneOptionAndNoChips() {
        val options = buildPlayOptions(poKey, "Plex", null, emptyList(), poNone)
        assertEquals(1, options.size)
        assertEquals("self", options.single().id)
        assertEquals("Plex", options.single().chipLabel)
        val (shown, more) = visiblePlayOptions(options, defaultPlayOptionId(options, poKey, null))
        assertEquals(1, shown.size)
        assertFalse(more)
    }

    @Test fun oneAlternativeMakesTwoChipsWithTheBestQualityFirst() {
        val options = buildPlayOptions(poKey, "Plex", null, emptyList(), listOf(poAlt("IPTV", "m2", "1080p")))
        assertEquals(listOf("k:iptv:m2", "self"), options.map { it.id })
        assertEquals("1080p  ·  IPTV", options.first().chipLabel)
        val (shown, more) = visiblePlayOptions(options, defaultPlayOptionId(options, poKey, null))
        assertEquals(2, shown.size)
        assertFalse(more)
    }

    @Test fun manyAlternativesOrderByQualityThenSource() {
        val related = listOf(
            poAlt("IPTV", "m2", "1080p"),
            poAlt("Plex", "m3", "4K"),
            poAlt("Apple TV", "m4", "Extended Cut"),
            poAlt("IPTV", "m5", "720p"),
        )
        val options = buildPlayOptions(poKey, "Plex", null, emptyList(), related)
        assertEquals(listOf("k:plex:m3", "k:iptv:m2", "k:iptv:m5", "k:apple tv:m4", "self"), options.map { it.id })
        assertEquals(listOf(4, 2, 1, 0, 0), options.map { it.qualityRank })
    }

    @Test fun filesInsideThisItemSortAlongsideOtherSources() {
        val versions = listOf(MediaVersion("v1", 0, "1080p"), MediaVersion("v2", 1, "4K"))
        val options = buildPlayOptions(poKey, "Plex", null, versions, listOf(poAlt("IPTV", "m2", "4K")))
        assertEquals(listOf("k:iptv:m2", "v:v2", "v:v1"), options.map { it.id })
        assertTrue(options.all { it.qualityRank >= 2 })
    }

    @Test fun anEditionLabelOnThisItemNamesItsChip() {
        val options = buildPlayOptions(poKey, "Plex", "Extended", emptyList(), poNone)
        assertEquals("Extended  ·  Plex", options.single().chipLabel)
        assertEquals(0, options.single().qualityRank)
    }

    @Test fun defaultSelectionFollowsTheVersionPlayUses() {
        val versions = listOf(MediaVersion("v1", 0, "1080p"), MediaVersion("v2", 1, "4K"))
        val options = buildPlayOptions(poKey, "Plex", null, versions, poNone)
        assertEquals("v:v1", defaultPlayOptionId(options, poKey, "v1"))
        assertEquals("v:v2", defaultPlayOptionId(options, poKey, "v2"))
    }

    @Test fun defaultSelectionFallsBackToTheBestOptionOnThisItem() {
        val versions = listOf(MediaVersion("v1", 0, "1080p"), MediaVersion("v2", 1, "4K"))
        val options = buildPlayOptions(poKey, "Plex", null, versions, listOf(poAlt("IPTV", "m2", "4K")))
        assertEquals("v:v2", defaultPlayOptionId(options, poKey, null))
        assertEquals("v:v2", defaultPlayOptionId(options, poKey, "gone"))
        val single = buildPlayOptions(poKey, "Plex", null, emptyList(), listOf(poAlt("IPTV", "m2", "4K")))
        assertEquals("self", defaultPlayOptionId(single, poKey, null))
    }

    @Test fun moreChipAppearsOnlyPastFour() {
        val related = (2..6).map { poAlt("IPTV$it", "m$it", "1080p") }
        val options = buildPlayOptions(poKey, "Plex", null, emptyList(), related)
        assertEquals(6, options.size)
        val (shown, more) = visiblePlayOptions(options, "self")
        assertEquals(4, shown.size)
        assertTrue(more)
        val (few, noMore) = visiblePlayOptions(options.take(4), "self")
        assertEquals(4, few.size)
        assertFalse(noMore)
    }

    @Test fun theChosenChipIsNeverHiddenBehindMore() {
        val related = (2..6).map { poAlt("IPTV$it", "m$it", "1080p") }
        val options = buildPlayOptions(poKey, "Plex", null, emptyList(), related)
        val chosen = options.last().id
        val (shown, more) = visiblePlayOptions(options, chosen)
        assertTrue(more)
        assertEquals(4, shown.size)
        assertEquals(chosen, shown.last().id)
    }

    @Test fun aLoneLabelledVersionStillNamesItself() {
        val options = buildPlayOptions(poKey, "Plex", null, listOf(MediaVersion("v1", 0, "4K")), poNone)
        assertEquals(1, options.size)
        assertEquals("4K", singlePlayOptionLabel(options))
    }

    @Test fun aLoneUnlabelledOptionHasNoLabelToShow() {
        assertNull(singlePlayOptionLabel(buildPlayOptions(poKey, "Plex", null, emptyList(), poNone)))
        assertNull(singlePlayOptionLabel(buildPlayOptions(poKey, "Plex", "   ", emptyList(), poNone)))
        assertNull(singlePlayOptionLabel(buildPlayOptions(poKey, "Plex", null, listOf(MediaVersion("v1", 0, "1080p"), MediaVersion("v2", 1, "4K")), poNone)))
        assertNull(singlePlayOptionLabel(emptyList()))
    }

    @Test fun qualityRanksGoFrom8kDownToUnlabelled() {
        assertEquals(listOf(5, 4, 4, 3, 2, 2, 1, 0), listOf("8K", "4K HDR", "UHD", "1440p", "1080p", "FHD", "HD", "Director's Cut").map(::qualityRank))
        assertEquals(0, qualityRank(null))
    }

    @Test fun runtimeReadsAsHoursAndMinutes() {
        assertEquals("1h 41m", fmtRuntime(6060))
        assertEquals("41m", fmtRuntime(2460))
        assertEquals("2h 0m", fmtRuntime(7200))
        assertNull(fmtRuntime(null))
        assertNull(fmtRuntime(30))
    }
}
