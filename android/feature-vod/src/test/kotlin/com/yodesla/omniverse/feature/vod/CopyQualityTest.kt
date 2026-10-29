package com.yodesla.omniverse.feature.vod

import com.yodesla.omniverse.core.data.PosterRow
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.DynamicRange
import com.yodesla.omniverse.core.model.MediaVersion
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.SourceId
import com.yodesla.omniverse.core.model.VideoInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private val cqPlex = ContentKey(SourceId("plex"), ContentKind.VOD, RemoteId("p1"))
private fun cqGem(id: String) = ContentKey(SourceId("gem"), ContentKind.VOD, RemoteId(id))
private fun cqCopy(key: ContentKey, name: String) = RelatedMovie(PosterRow(key, name, null, 2001, null), "gem-ruby.me", null)

/** What each copy of a title says it is, before the viewer opens it. */
class CopyQualityTest {
    @Test fun tagsGluedTogetherStillRead() {
        assertEquals("4K DV", resolutionTag("The Lord of the Rings: The Fellowship of the Ring 4kDV"))
        assertEquals("4K HDR", resolutionTag("Fellowship (4KHDR)"))
        assertEquals("4K HDR10+", resolutionTag("Film 2160pHDR10+"))
        assertEquals("4K DV", resolutionTag("Film [4K-DV]"))
        assertEquals("4K DV", resolutionTag("Film UHD DoVi HEVC"))
        assertEquals("1080p", resolutionTag("Film 1080pHEVC"))
        assertEquals("DV", resolutionTag("Dolby Vision"))
    }

    @Test fun ordinaryWordsAreNotQualityTags() {
        assertNull(resolutionTag("4kids special"))
        assertNull(resolutionTag("Film DVDRip"))
        assertNull(resolutionTag("Film HDRip"))
        assertNull(resolutionTag("Dvorak"))
        assertEquals("HDR10+", resolutionTag("Film HDR10+"), "HDR10+ is not Dolby Vision")
    }

    @Test fun theProbeSaysWhatTheFileIs() {
        assertEquals("4K DV", probeQuality(VideoInfo(3840, 1600, DynamicRange.DOLBY_VISION)).label)
        assertEquals(4, probeQuality(VideoInfo(3840, 1600, DynamicRange.DOLBY_VISION)).rank)
        assertEquals("1080p", probeQuality(VideoInfo(1920, 800, DynamicRange.SDR)).label)
        assertEquals("720p", probeQuality(VideoInfo(1280, 534)).label)
        assertEquals("SD", probeQuality(VideoInfo(720, 404, DynamicRange.SDR)).label)
        assertNull(probeQuality(null).label)
    }

    @Test fun theProbeOutranksTheNameExceptWhereItCannotSeeDolbyVision() {
        assertEquals("1080p", copyQuality(VideoInfo(1920, 1080, DynamicRange.SDR), "Film 4K").label, "a mislabelled 4K")
        assertEquals("4K DV", copyQuality(VideoInfo(3840, 2160, DynamicRange.HDR), "Film 4kDV").label)
        assertEquals("4K DV", copyQuality(null, "Film 4kDV").label)
        assertEquals("4K HDR", copyQuality(VideoInfo(3840, 2160), "Film HDR").label)
        assertEquals("4K", copyQuality(VideoInfo(3840, 2160, DynamicRange.SDR), "Film HDR").label)
        assertNull(copyQuality(null, "Film", null).label)
    }

    @Test fun editionWordsLeaveOutWhatTheQualityAlreadySays() {
        assertNull(editionWords("4K · MKV"))
        assertEquals("MKV", editionWords("4K · MKV", keepFileType = true))
        assertEquals("Extended", editionWords("Extended 4K"))
        assertEquals("Extended", editionWords("(4K) Extended"))
        assertEquals("Director's Cut", editionWords("Director's Cut"))
        assertNull(editionWords("4K-DV"))
        assertNull(editionWords(null))
    }

    @Test fun threeCopiesFromOneProviderEachSayWhatTheyAre() {
        val related = listOf(
            cqCopy(cqGem("1"), "The Lord of the Rings: The Fellowship of the Ring"),
            cqCopy(cqGem("2"), "The Lord of the Rings: The Fellowship of the Ring 4kDV"),
            cqCopy(cqGem("3"), "The Lord of the Rings: The Fellowship of the Ring"),
        )
        val probed = mapOf(cqGem("1") to VideoInfo(3840, 2160, DynamicRange.HDR), cqGem("3") to VideoInfo(1920, 800, DynamicRange.SDR))
        val options = buildPlayOptions(cqPlex, "Korys Theatre", null, listOf(MediaVersion("m", 0, "4K · MKV")), related,
            relatedVideo = probed)
        assertEquals(listOf(
            "4K DV  ·  gem-ruby.me",
            "4K HDR  ·  gem-ruby.me",
            "4K  ·  Korys Theatre",
            "1080p  ·  gem-ruby.me",
        ), options.map { it.chipLabel })
        assertEquals("4K DV", options.first().shownQuality)
    }

    @Test fun aPlainlyNamedCopyIsReadFromItsCategory() {
        val related = listOf(cqCopy(cqGem("1"), "Fellowship (2001)"), cqCopy(cqGem("2"), "Fellowship (2001)"))
        val options = buildPlayOptions(cqPlex, "Plex", null, emptyList(), related,
            categoryNames = mapOf(cqGem("1") to "4K Movies Dolby Vision", cqGem("2") to "Action"))
        assertEquals("4K DV  ·  gem-ruby.me", options.first().chipLabel)
        assertEquals("gem-ruby.me", options.first { it.key == cqGem("2") }.chipLabel)
    }

    @Test fun aLoneCopyStillSaysWhatItIs() {
        val options = buildPlayOptions(cqPlex, "IPTV", null, emptyList(), emptyList(), "Film",
            currentVideo = VideoInfo(3840, 2160, DynamicRange.DOLBY_VISION))
        assertEquals("4K DV", singlePlayOptionLabel(options))
        val plex = buildPlayOptions(cqPlex, "Plex", null,
            listOf(MediaVersion("m", 0, "4K · MKV", VideoInfo(3840, 1608, DynamicRange.HDR))), emptyList())
        assertEquals("4K HDR", singlePlayOptionLabel(plex))
    }

    @Test fun chooserRowsNameTheQualityOnce() {
        val copies = buildEditionCopies(
            buildPlayOptions(cqPlex, "Korys Theatre", null, listOf(MediaVersion("m", 0, "4K · MKV")), emptyList()),
            cqPlex, 13_680, null, emptyMap(), emptyMap(), emptyMap(), emptyMap())
        assertEquals("4K  ·  MKV", editionRowTitle(copies.single()))
        assertEquals("4K  ·  Korys Theatre", editionChipLabel(copies.single()))
    }

    @Test fun plexVersionsShowTheirCutsAndOwnRuntimes() {
        val versions = listOf(
            MediaVersion("special", 0, "4K · Special Edition · MKV", VideoInfo(3840, 2160, DynamicRange.DOLBY_VISION), 10253),
            MediaVersion("extended", 1, "4K · Collector's Extended Cut · MKV", VideoInfo(3840, 2160, DynamicRange.DOLBY_VISION), 10708),
            MediaVersion("hd", 2, "1080p · Collector's Extended Cut · MKV", VideoInfo(1920, 1080), 10708),
        )
        val options = buildPlayOptions(cqPlex, "Korys Theatre", null, versions, emptyList(), currentRuntimeSec = 10253)
        assertEquals(3, options.map { it.chipLabel }.distinct().size)
        assertEquals("4K DV  ·  Special Edition  ·  Korys Theatre", options.first { it.versionId == "special" }.chipLabel)
        val copies = buildEditionCopies(options, cqPlex, 10253, null, emptyMap(), emptyMap(), emptyMap(), emptyMap())
        assertEquals(10708, copies.first { it.versionId == "extended" }.runtimeSec)
        assertEquals("1080p  ·  Collector's Extended Cut · MKV", editionRowTitle(copies.first { it.versionId == "hd" }))
    }

    @Test fun indistinguishableProviderCopiesGetHonestDifferentiators() {
        val related = listOf(cqCopy(cqGem("1"), "Avatar (2009)"), cqCopy(cqGem("2"), "Avatar (2009)"))
        val options = buildPlayOptions(cqPlex, "Korys Theatre", null, emptyList(), related,
            relatedVideo = mapOf(cqGem("1") to VideoInfo(3840, 2160, DynamicRange.DOLBY_VISION),
                cqGem("2") to VideoInfo(3840, 2160, DynamicRange.DOLBY_VISION)),
            relatedRuntimeSec = mapOf(cqGem("1") to 10253, cqGem("2") to 10708))
        assertEquals(setOf("2h 50m", "2h 58m"), options.filter { it.sourceName == "gem-ruby.me" }.mapNotNull { it.disambiguator }.toSet())
        val unknown = buildPlayOptions(cqPlex, "Korys Theatre", null, emptyList(), related)
        assertEquals(setOf("Copy 1", "Copy 2"), unknown.filter { it.sourceName == "gem-ruby.me" }.mapNotNull { it.disambiguator }.toSet())
    }

    @Test fun avatarProviderRuntimeRevealsTheatricalCut() {
        val related = listOf(cqCopy(cqGem("1"), "Avatar (2009)"))
        val options = buildPlayOptions(cqPlex, "Korys Theatre", null, emptyList(), related,
            relatedVideo = mapOf(cqGem("1") to VideoInfo(3840, 2160, DynamicRange.DOLBY_VISION)),
            relatedRuntimeSec = mapOf(cqGem("1") to 9720), tmdbId = "19995")
        assertEquals("4K DV  ·  Theatrical Cut  ·  gem-ruby.me", options.first { it.key == cqGem("1") }.chipLabel)
    }

    @Test fun providerTitleSuppliesCutAndSavedFileLabelOverridesIt() {
        val related = listOf(cqCopy(cqGem("1"), "Film - Director's Cut 4K"))
        val options = buildPlayOptions(cqPlex, "Plex", null, emptyList(), related)
        assertEquals("4K  ·  Director's Cut  ·  gem-ruby.me", options.first { it.key == cqGem("1") }.chipLabel)

        val versions = listOf(MediaVersion("file", 0, "4K · MKV", VideoInfo(3840, 2160)))
        val labelled = buildPlayOptions(cqPlex, "Plex", null, versions, emptyList(),
            versionLabels = mapOf("file" to "Final Cut"))
        assertEquals("4K  ·  Final Cut  ·  Plex", labelled.single().chipLabel)
    }

    @Test fun fellowshipRuntimesGetVerifiedEditionNamesAcrossSources() {
        val related = listOf(cqCopy(cqGem("fellowship"), "The Lord of the Rings: The Fellowship of the Ring"))
        val options = buildPlayOptions(cqPlex, "Korys Theatre", null,
            listOf(MediaVersion("extended", 0, "4K · MKV", durationSec = 13680)), related,
            currentName = "The Lord of the Rings: The Fellowship of the Ring", currentRuntimeSec = 13680,
            relatedRuntimeSec = mapOf(cqGem("fellowship") to 10740), tmdbId = "120", referenceRuntimeSec = 10740)
        assertEquals("Extended Edition", editionWords(options.first { it.versionId == "extended" }.label))
        assertEquals("Theatrical Cut", editionWords(options.first { it.key == cqGem("fellowship") }.label))
    }

    @Test fun unrelatedMoviesUseHonestRuntimeDifferenceLabels() {
        val related = listOf(cqCopy(cqGem("other"), "Some Movie"))
        val options = buildPlayOptions(cqPlex, "Plex", null, emptyList(), related,
            currentName = "Some Movie", currentRuntimeSec = 7200,
            relatedRuntimeSec = mapOf(cqGem("other") to 8400), referenceRuntimeSec = 7200)
        assertEquals("Standard cut", editionWords(options.first { it.key == cqPlex }.label))
        assertEquals("Longer cut (+20m)", editionWords(options.first { it.key == cqGem("other") }.label))
    }

    @Test fun missingReferenceStillIdentifiesLongerCopyWithoutNamingItsEdition() {
        val related = listOf(cqCopy(cqGem("long"), "Unknown Film"))
        val options = buildPlayOptions(cqPlex, "Plex", null, emptyList(), related,
            currentRuntimeSec = 6900, relatedRuntimeSec = mapOf(cqGem("long") to 8100))
        assertEquals("Longer cut (+20m)", editionWords(options.first { it.key == cqGem("long") }.label))
        assertNull(editionWords(options.first { it.key == cqPlex }.label))
    }
}
