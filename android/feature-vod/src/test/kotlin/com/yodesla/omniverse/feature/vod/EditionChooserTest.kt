package com.yodesla.omniverse.feature.vod

import com.yodesla.omniverse.core.data.PosterRow
import com.yodesla.omniverse.core.data.Progress
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

private val ecSrc = SourceId("plex")
private val ecKey = ContentKey(ecSrc, ContentKind.VOD, RemoteId("m1"))
private val ecIptv = ContentKey(SourceId("iptv"), ContentKind.VOD, RemoteId("m2"))
private val ecPlex2 = ContentKey(SourceId("plex"), ContentKind.VOD, RemoteId("m3"))
private val ecApple = ContentKey(SourceId("apple"), ContentKind.VOD, RemoteId("m4"))

private fun ecAlt(key: ContentKey, source: String, edition: String?, name: String = "Film") =
    RelatedMovie(PosterRow(key, name, null, 2024, null), source, edition)

private fun ecCopies(
    options: List<PlayOption>,
    currentProgress: Progress? = null,
    versionProgress: Map<String, Progress> = emptyMap(),
    copyProgress: Map<ContentKey, Progress> = emptyMap(),
    copyRuntimeSec: Map<ContentKey, Int> = emptyMap(),
    copyNames: Map<ContentKey, String> = emptyMap(),
    currentRuntimeSec: Int? = 6060,
) = buildEditionCopies(options, ecKey, currentRuntimeSec, currentProgress, versionProgress,
    copyProgress, copyRuntimeSec, copyNames)

private val ecTwoCopies = ecCopies(
    buildPlayOptions(ecKey, "Plex", null, emptyList(), listOf(ecAlt(ecIptv, "IPTV", "1080p")), "Film"),
)

/**
 * Task 86: the pure ranking/label half of the detail version chooser. Nothing here touches
 * Compose, a source or a database, so the ordering Play depends on is testable directly.
 */
class EditionChooserTest {
    @Test fun resolutionTagsComeFromTheSavedLabelOrTheProvidersOwnName() {
        assertEquals("4K", resolutionTag("4K", null, null))
        assertEquals("4K HDR", resolutionTag(null, null, "Blade Runner (2160p HDR)"))
        assertEquals("1080p", resolutionTag("Director's Cut", null, "Film 1080p"))
        assertEquals("DV", resolutionTag("Dolby Vision", null, null))
        assertNull(resolutionTag("Director's Cut", null, null))
        assertEquals("4K", resolutionTag("4K", "Extended", "Film UHD"))
        assertNull(resolutionTag("Director's Cut", null, "Film"))
        assertNull(resolutionTag(null, null, null))
    }

    @Test fun parsedResolutionTagsRankLikeLabels() {
        assertEquals(4, resolutionRank("4K DV"))
        assertEquals(1, resolutionRank("720p"))
        assertEquals(0, resolutionRank("Director's Cut"))
        assertEquals(0, resolutionRank(null))
    }

    @Test fun theViewersOwnLibraryIsTheMoreTrustworthyCopy() {
        assertEquals(2, sourceReliability("Plex"))
        assertEquals(2, sourceReliability("My Plex Server"))
        assertEquals(1, sourceReliability("IPTV"))
        assertEquals(1, sourceReliability(null))
    }

    @Test fun everyCopyIsDescribedFromItsOwnSourceData() {
        assertEquals(2, ecTwoCopies.size)
        val here = ecTwoCopies.first { it.isCurrent }
        val there = ecTwoCopies.first { !it.isCurrent }
        assertEquals("self", here.id)
        assertEquals(6060, here.runtimeSec)
        assertNull(here.progress)
        assertEquals("k:iptv:m2", there.id)
        assertEquals("1080p", there.resolution)
        assertEquals("IPTV", there.sourceName)
        assertNull(there.runtimeSec)
    }

    @Test fun progressAndRuntimeAreReadPerCopy() {
        val copies = ecCopies(
            buildPlayOptions(ecKey, "Plex", null, emptyList(), listOf(ecAlt(ecIptv, "IPTV", "1080p")), "Film"),
            copyProgress = mapOf(ecIptv to Progress(ecIptv, null, 120_000L, 540_000L, 9L)),
            copyRuntimeSec = mapOf(ecIptv to 5400),
        )
        val there = copies.first { !it.isCurrent }
        assertEquals(5400, there.runtimeSec)
        assertEquals(9L, there.progress?.updatedMs)
        assertEquals("k:iptv:m2", editionCopiesRanked(copies, null).first().id)
    }

    @Test fun theCopyHoldingWatchProgressLeadsTheChooser() {
        val copies = ecCopies(
            buildPlayOptions(ecKey, "Plex", null, emptyList(),
                listOf(ecAlt(ecIptv, "IPTV", "4K"), ecAlt(ecPlex2, "Plex", "4K"))),
            copyProgress = mapOf(ecIptv to Progress(ecIptv, null, 120_000L, 540_000L, 9L)),
        )
        assertEquals(listOf("k:iptv:m2", "k:plex:m3", "self"), editionCopiesRanked(copies, null).map { it.id })
    }

    @Test fun theSavedPickBeatsBetterQualityWhenNothingIsWatched() {
        val copies = ecCopies(
            buildPlayOptions(ecKey, "Plex", null, emptyList(),
                listOf(ecAlt(ecIptv, "IPTV", "1080p"), ecAlt(ecPlex2, "Plex", "4K"))),
        )
        assertEquals(listOf("k:iptv:m2", "k:plex:m3", "self"), editionCopiesRanked(copies, "k:iptv:m2").map { it.id })
    }

    @Test fun qualityThenSourceThenNameOrderTheRest() {
        val copies = ecCopies(
            buildPlayOptions(ecKey, "Plex", null, emptyList(),
                listOf(ecAlt(ecIptv, "IPTV", "1080p"), ecAlt(ecPlex2, "Plex", "1080p"), ecAlt(ecApple, "Apple TV", "4K"))),
        )
        assertEquals(listOf("k:apple:m4", "k:plex:m3", "k:iptv:m2", "self"), editionCopiesRanked(copies, null).map { it.id })
    }

    @Test fun playFollowsWatchProgressThenSavedPickThenTheBestCopy() {
        val watchedElsewhere = ecCopies(
            buildPlayOptions(ecKey, "Plex", null, emptyList(), listOf(ecAlt(ecIptv, "IPTV", "1080p"))),
            copyProgress = mapOf(ecIptv to Progress(ecIptv, null, 120_000L, 540_000L, 9L)),
        )
        assertEquals("k:iptv:m2", chosenEditionCopy(watchedElsewhere, "self")?.id)
        assertEquals("k:iptv:m2", chosenEditionCopy(watchedElsewhere, null)?.id)

        val pickedElsewhere = ecCopies(
            buildPlayOptions(ecKey, "Plex", null, emptyList(), listOf(ecAlt(ecIptv, "IPTV", "1080p"))),
        )
        assertEquals("k:iptv:m2", chosenEditionCopy(pickedElsewhere, "k:iptv:m2")?.id)
        assertEquals("k:iptv:m2", chosenEditionCopy(pickedElsewhere, null)?.id)

        val nothingWatched = ecCopies(
            buildPlayOptions(ecKey, "Plex", null, emptyList(), listOf(ecAlt(ecIptv, "IPTV", null))),
        )
        assertEquals("self", chosenEditionCopy(nothingWatched, null)?.id)
        assertEquals("self", chosenEditionCopy(ecCopies(
            buildPlayOptions(ecKey, "Plex", null, emptyList(), listOf(ecAlt(ecIptv, "IPTV", "1080p"))),
            currentProgress = Progress(ecKey, null, 120_000L, 540_000L, 9L)), null)?.id)
        assertNull(chosenEditionCopy(emptyList(), null))
    }

    @Test fun aFinishedWatchLeadsNeitherPlayNorTheChooser() {
        val copies = ecCopies(
            buildPlayOptions(ecKey, "Plex", null, emptyList(), listOf(ecAlt(ecIptv, "IPTV", null))),
            copyProgress = mapOf(ecIptv to Progress(ecIptv, null, 540_000L, 540_000L, 9L, completed = true)),
        )
        assertEquals("self", chosenEditionCopy(copies, null)?.id)
        assertEquals(listOf("self", "k:iptv:m2"), editionCopiesRanked(copies, null).map { it.id })
    }

    @Test fun oneCopyNeedsNoChooserAndNoChip() {
        val alone = ecCopies(buildPlayOptions(ecKey, "Plex", null, emptyList(), emptyList()))
        assertFalse(editionChooserNeeded(alone))
        assertTrue(editionChooserNeeded(ecTwoCopies))
        assertTrue(editionChooserNeeded(ecCopies(
            buildPlayOptions(ecKey, "Plex", null, listOf(MediaVersion("v1", 0, "1080p"), MediaVersion("v2", 1, "4K")), emptyList()))))
    }

    @Test fun chipAndRowLabelsNameTheCopyWithoutRepeatingTheSource() {
        val copies = ecCopies(
            buildPlayOptions(ecKey, "Plex", "Director's Cut", emptyList(), listOf(ecAlt(ecIptv, "IPTV", "1080p")), "Film"),
            copyRuntimeSec = mapOf(ecIptv to 5400),
        )
        val here = copies.first { it.isCurrent }
        val there = copies.first { !it.isCurrent }
        assertEquals("Director's Cut  \u00b7  Plex", editionChipLabel(here))
        assertEquals("1080p  \u00b7  IPTV", editionChipLabel(there))
        assertNull(editionChipLabel(null))
        assertEquals("Director's Cut", editionRowTitle(here))
        assertEquals("1080p", editionRowTitle(there))
        assertEquals("Plex  \u00b7  1h 41m  \u00b7  Current source", editionRowMeta(here, null, "Current source"))
        assertEquals("IPTV  \u00b7  1h 30m  \u00b7  Resume 2 min", editionRowMeta(there, "Resume 2 min", null))
    }

    @Test fun resumeMinutesShowOnlyOnTheCopyHoldingAnUnfinishedWatch() {
        val copies = ecCopies(
            buildPlayOptions(ecKey, "Plex", null, emptyList(), listOf(ecAlt(ecIptv, "IPTV", "1080p"))),
            currentProgress = Progress(ecKey, null, 1_380_000L, 6_060_000L, 5L),
            copyProgress = mapOf(ecIptv to Progress(ecIptv, null, 120_000L, 540_000L, 9L)),
        )
        assertEquals(23L, editionResumeMinutes(copies.first { it.isCurrent }))
        assertEquals(2L, editionResumeMinutes(copies.first { !it.isCurrent }))
        assertNull(editionResumeMinutes(copies.first { !it.isCurrent }.copy(progress = null)))
        assertNull(editionResumeMinutes(copies.first { !it.isCurrent }.copy(
            progress = Progress(ecIptv, null, 120_000L, 120_000L, 9L, completed = true))))
        assertNull(editionResumeMinutes(copies.first { !it.isCurrent }.copy(
            progress = Progress(ecIptv, null, 12_000L, 540_000L, 9L))))
    }

    @Test fun thePickIsRememberedOnlyForATitleWithACatalogId() {
        assertEquals("edition_pick_603", editionPickSettingKey("603"))
        assertEquals("edition_pick_603", editionPickSettingKey(" 603 "))
        assertNull(editionPickSettingKey(null))
        assertNull(editionPickSettingKey("   "))
    }

    @Test fun theFilePlayOpensInsideThisItemFollowsThePickThenQuality() {
        val versions = listOf(MediaVersion("v1", 0, "1080p"), MediaVersion("v2", 1, "4K"))
        assertEquals("v2", defaultEditionVersionId(versions, null))
        assertEquals("v1", defaultEditionVersionId(versions, "v:v1"))
        assertEquals("v2", defaultEditionVersionId(versions, "v:gone"))
        assertEquals("v2", defaultEditionVersionId(versions, "k:iptv:m2"))
        assertEquals("v1", defaultEditionVersionId(listOf(MediaVersion("v1", 0, "1080p")), null))
        assertNull(defaultEditionVersionId(emptyList(), "v1"))
    }
}
