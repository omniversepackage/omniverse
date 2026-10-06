package com.yodesla.omniverse.feature.vod

import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.MediaVersion
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.SeriesDetail
import com.yodesla.omniverse.core.model.SeriesRecord
import com.yodesla.omniverse.core.model.SourceId
import com.yodesla.omniverse.core.model.VodDetail
import com.yodesla.omniverse.core.model.VodRecord
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DetailMoreMenuTest {
    private val always = { _: String, _: String, _: String -> true }

    private fun movieState(versions: List<MediaVersion>) = DetailState(
        loading = false,
        movie = VodDetail(
            record = VodRecord(SourceId("s1"), RemoteId("m1"), "Movie", null, emptyList(), null, null, null, null, null, 0),
            plot = null, cast = null, director = null, genre = null, durationSec = null,
            backdropUrls = emptyList(), releaseDate = null, trailerUrl = null, versions = versions,
        ),
    )

    @Test
    fun movedActionsAlwaysInMore() {
        val key = ContentKey(SourceId("s1"), ContentKind.VOD, RemoteId("m1"))
        val labels = moreMenuLabelsFor(key, movieState(listOf(MediaVersion("v1", 0, "4K"), MediaVersion("v2", 1, "1080p"))), always)
        assertTrue("Edit details" in labels)
        assertTrue("Collections" in labels)
        assertTrue(labels.any { it.startsWith("Version:") })
    }

    @Test
    fun singleCopyHasNoVersionAction() {
        val key = ContentKey(SourceId("s1"), ContentKind.VOD, RemoteId("m1"))
        val labels = moreMenuLabelsFor(key, movieState(listOf(MediaVersion("v1", 0, "4K"))), always)
        assertTrue("Edit details" in labels)
        assertTrue("Collections" in labels)
        assertFalse(labels.any { it.startsWith("Version:") })
    }

    @Test
    fun seriesMovesManagementIntoMoreWithoutVersion() {
        val key = ContentKey(SourceId("s1"), ContentKind.SERIES, RemoteId("se1"))
        val state = DetailState(
            loading = false,
            series = SeriesDetail(
                record = SeriesRecord(SourceId("s1"), RemoteId("se1"), "Show", null, emptyList(), emptyList(), null, null, null, null, null, 0),
                cast = null, director = null, seasons = emptyList(),
            ),
        )
        val labels = moreMenuLabelsFor(key, state, always)
        assertTrue("Edit details" in labels)
        assertTrue("Collections" in labels)
        assertFalse(labels.any { it.startsWith("Version:") })
    }
}
