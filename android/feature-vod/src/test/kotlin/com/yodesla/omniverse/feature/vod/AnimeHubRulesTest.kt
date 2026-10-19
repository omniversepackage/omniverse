package com.yodesla.omniverse.feature.vod

import com.yodesla.omniverse.core.data.ChannelRow
import com.yodesla.omniverse.core.data.PosterRow
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.SourceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnimeHubRulesTest {
    private fun ch(id: String, name: String, cat: String = "c") =
        ChannelRow(ContentKey(SourceId("s"), ContentKind.LIVE, RemoteId(id)), null, name, null, null, 0, RemoteId(cat))

    private fun poster(id: String, tmdb: String? = null, kind: ContentKind = ContentKind.SERIES) =
        PosterRow(ContentKey(SourceId("s"), kind, RemoteId(id)), "T$id", null, 2020, 8f, tmdbId = tmdb)

    @Test fun animeChannelsByNameOrCategory() {
        assertEquals(AnimeChannelKind.ANIME, animeChannelKind("US| Animax HD", null))
        assertEquals(AnimeChannelKind.ANIME, animeChannelKind("Pluto TV Anime All Day", null))
        assertEquals(AnimeChannelKind.ANIME, animeChannelKind("Toonami Aftermath", "US Entertainment"))
        assertEquals(AnimeChannelKind.ANIME, animeChannelKind("Channel 12", "ANIME | 24/7"))
        assertEquals(AnimeChannelKind.ANIME, animeChannelKind("Dragon Ball Z 24/7", null))
    }

    @Test fun cartoonChannels() {
        assertEquals(AnimeChannelKind.CARTOON, animeChannelKind("US| Cartoon Network FHD", null))
        assertEquals(AnimeChannelKind.CARTOON, animeChannelKind("Adult Swim", null))
        assertEquals(AnimeChannelKind.CARTOON, animeChannelKind("Nick Jr.", null))
        assertEquals(AnimeChannelKind.CARTOON, animeChannelKind("Nicktoons", null))
        assertEquals(AnimeChannelKind.CARTOON, animeChannelKind("Boomerang", "Kids"))
    }

    @Test fun lookalikesAreNotOnTheHub() {
        assertNull(animeChannelKind("Animal Planet", "Documentary"))
        assertNull(animeChannelKind("Nickelback Live", "Music"))
        assertNull(animeChannelKind("Disney Channel", "Kids"))
        assertNull(animeChannelKind("Kids Movies 24/7", "Kids"))
    }

    @Test fun duplicateChannelCopiesCollapse() {
        val rows = animeChannelRows(listOf(ch("1", "US| Animax HD") to null, ch("2", "Animax FHD") to null, ch("3", "Cartoon Network") to null)) { true }
        assertEquals(listOf("1"), rows[AnimeChannelKind.ANIME]!!.map { it.key.remoteId.value })
        assertEquals(listOf("3"), rows[AnimeChannelKind.CARTOON]!!.map { it.key.remoteId.value })
    }

    @Test fun hiddenChannelsStayHidden() {
        val rows = animeChannelRows(listOf(ch("1", "Animax", cat = "locked") to null)) { it.categoryId.value != "locked" }
        assertTrue(rows.isEmpty())
    }

    @Test fun animatedTitles() {
        assertTrue(isAnimatedTitle("ANIME | SUB", null))
        assertTrue(isAnimatedTitle("Kids", "Animation, Family"))
        assertTrue(isAnimatedTitle("Cartoons", null))
        assertFalse(isAnimatedTitle("Documentary", "Nature"))
    }

    @Test fun shelvesNeverRepeatATitleAndDropShortOnes() {
        val a = (1..6).map { poster("$it", tmdb = "t$it") }
        val b = (4..12).map { poster("b$it", tmdb = "t$it") }
        val short = listOf(poster("x"), poster("y"))
        val out = animeHubShelves(listOf(AnimeShelf("a", "A", a), AnimeShelf("b", "B", b), AnimeShelf("s", "S", short)))
        assertEquals(listOf("a", "b"), out.map { it.key })
        assertEquals((7..12).map { "b$it" }, out[1].rows.map { it.key.remoteId.value })
    }

    @Test fun movieAndShowWithSameTmdbNumberStayDistinct() {
        val show = poster("1", tmdb = "100", kind = ContentKind.SERIES)
        val movie = poster("2", tmdb = "100", kind = ContentKind.VOD)
        val out = animeHubShelves(listOf(AnimeShelf("a", "A", listOf(show) + (3..6).map { poster("$it") }), AnimeShelf("b", "B", listOf(movie) + (7..10).map { poster("$it") })))
        assertEquals(2, out.size)
        assertEquals("2", out[1].rows.first().key.remoteId.value)
    }

    @Test fun interleaveAlternates() {
        assertEquals(listOf(1, 10, 2, 20, 3), interleave(listOf(1, 2, 3), listOf(10, 20)))
    }

    private fun g(id: String, year: Int?, rating: Float?, genre: String?, tmdb: String? = null, kind: ContentKind = ContentKind.SERIES) =
        PosterRow(ContentKey(SourceId("s"), kind, RemoteId(id)), "T$id", null, year, rating, tmdbId = tmdb, genre = genre)

    @Test fun trendingSortShowsOnlyChartedTitlesInChartOrder() {
        val titles = listOf(HubTitle(g("a", 2020, 9f, null, "1"), true), HubTitle(g("b", 2021, 8f, null, "2"), true), HubTitle(g("c", 2022, 7f, null, null), false))
        val ranks = mapOf(g("b", null, null, null, "2").hubId() to 3, g("a", null, null, null, "1").hubId() to 7)
        assertEquals(listOf("b", "a"), animeGrid(titles, AnimeGridFilter(sort = AnimeGridSort.TRENDING), ranks).map { it.key.remoteId.value })
    }

    @Test fun gridFiltersCombine() {
        val titles = listOf(
            HubTitle(g("a", 2021, 9f, "Action, Fantasy"), true),
            HubTitle(g("b", 1995, 8f, "Action"), true),
            HubTitle(g("c", 2022, 7f, "Comedy, Animation"), false),
        )
        assertEquals(listOf("a"), animeGrid(titles, AnimeGridFilter(AnimeGridSort.RATING, AnimeTypeFilter.ANIME, AnimeDecade.D2020), emptyMap()).map { it.key.remoteId.value })
        assertEquals(listOf("a", "b"), animeGrid(titles, AnimeGridFilter(AnimeGridSort.RATING, genre = "action"), emptyMap()).map { it.key.remoteId.value })
        assertEquals(listOf("c"), animeGrid(titles, AnimeGridFilter(AnimeGridSort.TITLE, AnimeTypeFilter.CARTOONS), emptyMap()).map { it.key.remoteId.value })
        assertEquals(listOf("Action", "Comedy", "Fantasy"), topGenres(titles)) // "Animation" is never a genre chip
    }

    @Test fun channelServicesAndSorts() {
        assertEquals("Amazon US", channelService("Amazon US: Naruto"))
        assertEquals("Other", channelService("Anime All day"))
        val a = HubChannel(ch("1", "Xumo: Crunchyroll"), AnimeChannelKind.ANIME)
        val b = HubChannel(ch("2", "US: Naruto"), AnimeChannelKind.ANIME, nowTitle = "Ep 4")
        val t = HubChannel(ch("3", "Cartoon Network"), AnimeChannelKind.CARTOON)
        assertEquals(listOf("2", "1", "3"), channelGrid(listOf(a, b, t), ChannelKindFilter.ALL, null, ChannelSort.ON_NOW).map { it.row.key.remoteId.value })
        assertEquals(listOf("3"), channelGrid(listOf(a, b, t), ChannelKindFilter.CARTOONS, null, ChannelSort.FEATURED).map { it.row.key.remoteId.value })
        assertEquals(listOf("1"), channelGrid(listOf(a, b, t), ChannelKindFilter.ALL, "Xumo", ChannelSort.FEATURED).map { it.row.key.remoteId.value })
    }

    @Test fun animeSearchOnlyReturnsHubTitlesRankedByMatchQuality() {
        val titles = listOf(
            HubTitle(g("a", 2002, 8f, null).copy(name = "Naruto Shippuden"), true),
            HubTitle(g("b", 2002, 7f, null).copy(name = "Naruto"), true),
            HubTitle(g("c", 2017, 9f, null).copy(name = "Boruto: Naruto Next Generations"), true),
            HubTitle(g("d", 1999, 9f, null).copy(name = "One Piece"), true),
        )
        assertEquals(listOf("b", "a", "c"), searchHubTitles(titles, "naruto").map { it.key.remoteId.value })
        assertEquals(listOf("c"), searchHubTitles(titles, "naruto next").map { it.key.remoteId.value })
        assertEquals(emptyList(), searchHubTitles(titles, "bleach"))
        assertEquals(emptyList(), searchHubTitles(titles, "  "))
    }

    @Test fun animeSearchChannelsMatchNameCoreNameOrWhatIsOnNow() {
        val a = HubChannel(ch("1", "US: Naruto"), AnimeChannelKind.ANIME)
        val b = HubChannel(ch("2", "Pluto TV Anime"), AnimeChannelKind.ANIME, nowTitle = "Naruto Shippuden")
        val c = HubChannel(ch("3", "Cartoon Network"), AnimeChannelKind.CARTOON)
        assertEquals(listOf("1", "2"), searchHubChannels(listOf(c, b, a), "naruto").map { it.row.key.remoteId.value })
        assertEquals(listOf("3"), searchHubChannels(listOf(a, b, c), "cartoon").map { it.row.key.remoteId.value })
    }
}
