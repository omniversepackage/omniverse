package com.yodesla.omniverse.feature.home.phoneremote

import com.yodesla.omniverse.core.data.ChannelRow
import com.yodesla.omniverse.core.data.PosterRow
import com.yodesla.omniverse.core.data.ProgrammeHit
import com.yodesla.omniverse.core.data.SearchHit
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.SourceId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneRemoteSearchTest {
    private fun key(kind: ContentKind, src: String, rem: String) = ContentKey(SourceId(src), kind, RemoteId(rem))
    private fun channel(rem: String, cat: String) = ChannelRow(
        key(ContentKind.LIVE, "s", rem), number = 5, name = "Ch $rem", logoUrl = null,
        epgKey = null, catchupDays = 0, categoryId = RemoteId(cat),
    )
    private fun poster(kind: ContentKind, rem: String, cat: String?, tmdb: String?) = PosterRow(
        key(kind, "s", rem), name = "Title $rem", posterUrl = null, year = 2024, rating = null,
        categoryId = cat?.let { RemoteId(it) }, tmdbId = tmdb,
    )

    @Test fun hidesContentTheProfileCannotSee() {
        val vis: com.yodesla.omniverse.core.data.Visibility = { kind, _, cat -> !(kind == "VOD" && cat == "locked") }
        val out = runBlocking { PhoneRemoteSearch.buildResults(
            liveHits = listOf(SearchHit(key(ContentKind.LIVE, "s", "c1"), "Ch")),
            vodHits = listOf(SearchHit(key(ContentKind.VOD, "s", "ok"), "OK"), SearchHit(key(ContentKind.VOD, "s", "no"), "No")),
            seriesHits = emptyList(),
            programmes = emptyList(),
            channelOf = { channel("c1", "livecat") },
            posterOf = { k -> if (k.remoteId.value == "no") poster(ContentKind.VOD, "no", "locked", null) else poster(ContentKind.VOD, "ok", "open", null) },
            vis = vis,
        ) }
        assertEquals(listOf("c1", "ok"), out.map { it.remoteId })
        assertEquals(listOf("channel", "movie"), out.map { it.action })
    }

    @Test fun dedupesMoviesByTmdbId() {
        val vis: com.yodesla.omniverse.core.data.Visibility = { _, _, _ -> true }
        val out = runBlocking { PhoneRemoteSearch.buildResults(
            liveHits = emptyList(),
            vodHits = listOf(SearchHit(key(ContentKind.VOD, "s", "a"), "A"), SearchHit(key(ContentKind.VOD, "s", "b"), "B")),
            seriesHits = emptyList(),
            programmes = emptyList(),
            channelOf = { null },
            posterOf = { k -> poster(ContentKind.VOD, k.remoteId.value, "cat", "tmdb-1") },
            vis = vis,
        ) }
        assertEquals(1, out.size)
        assertEquals("a", out.single().remoteId)
    }

    @Test fun programmesRespectVisibility() {
        val vis: com.yodesla.omniverse.core.data.Visibility = { _, _, cat -> cat != "adult" }
        val out = runBlocking { PhoneRemoteSearch.buildResults(
            liveHits = emptyList(), vodHits = emptyList(), seriesHits = emptyList(),
            programmes = listOf(
                ProgrammeHit(key(ContentKind.LIVE, "s", "c1"), RemoteId("kids"), "Ch1", null, "Show", 0, 1),
                ProgrammeHit(key(ContentKind.LIVE, "s", "c2"), RemoteId("adult"), "Ch2", null, "Late", 0, 1),
            ),
            channelOf = { null }, posterOf = { null }, vis = vis,
        ) }
        assertEquals(listOf("c1"), out.map { it.remoteId })
    }
}
