package com.yodesla.omniverse.core.source.xtream

import com.yodesla.omniverse.core.model.AccountStatus
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.SeriesRecord
import com.yodesla.omniverse.core.model.SourceId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MappersTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val sourceId = SourceId("test-source")

    private fun fixture(name: String): String =
        javaClass.getResource("/fixtures/$name")!!.readText()

    private fun parseArray(text: String): JsonArray =
        json.parseToJsonElement(text) as? JsonArray ?: error("expected array")

    private fun parseObj(text: String): JsonObject =
        json.parseToJsonElement(text) as? JsonObject ?: error("expected object")

    @Test
    fun cleanLiveStreamsMapWithCorrectCountsAndFields() {
        val arr = parseArray(fixture("live_streams.json"))
        assertEquals(200, arr.size)
        val mapped = arr.mapIndexed { i, el -> XtreamMappers.channel(el as? JsonObject ?: return@mapIndexed null, sourceId, i) }
        assertEquals(200, mapped.count { it != null })

        val first = mapped[0]!!
        assertEquals(RemoteId("1001"), first.remoteId)
        assertEquals(1, first.number)
        assertEquals("FR: Lifestyle Hub HD", first.name)
        assertEquals("http://127.0.0.1:8799/logos/1001.png", first.logoUrl)
        assertEquals("ch1001.mock", first.epgChannelId)
        assertEquals(listOf(RemoteId("6")), first.categoryIds)
        assertEquals(7, first.catchupDays)
        assertEquals(1787854526000L, first.addedAtMs)
        assertEquals(0, first.sortIndex)
        assertEquals(1, mapped[1]!!.sortIndex)
    }

    @Test
    fun cleanVodStreamsMapWithCorrectCountsAndFields() {
        val arr = parseArray(fixture("vod_streams.json"))
        assertEquals(500, arr.size)
        val mapped = arr.mapIndexed { i, el -> XtreamMappers.vod(el as? JsonObject ?: return@mapIndexed null, sourceId, i) }
        assertEquals(500, mapped.count { it != null })

        val first = mapped[0]!!
        assertEquals(RemoteId("1001"), first.remoteId)
        assertEquals("Movie Title 1 (1998)", first.name)
        assertEquals(1998, first.year)
        assertEquals("mp4", first.containerExt)
        assertEquals("91118", first.tmdbId)
        assertEquals(listOf(RemoteId("3")), first.categoryIds)
        assertNotNull(first.rating)
        assertTrue(first.rating!! in 0f..10f)
    }

    @Test
    fun cleanCategoriesMap() {
        val arr = parseArray(fixture("live_categories.json"))
        val mapped = arr.mapIndexed { i, el -> XtreamMappers.category(el as? JsonObject ?: return@mapIndexed null, ContentKind.LIVE, sourceId, i) }
        assertTrue(mapped.all { it != null })
        val first = mapped[0]!!
        assertEquals(RemoteId("1"), first.remoteId)
        assertNull(first.parentId)
        assertEquals(0, first.sortIndex)
    }

    @Test
    fun cleanSeriesInfoGivesSortedSeasonsAndEpisodes() {
        val seriesArr = parseArray(fixture("series.json"))
        val fallback = XtreamMappers.series(seriesArr[0] as JsonObject, sourceId, 0)!!
        val root = parseObj(fixture("series_info.json"))
        val detail = XtreamMappers.seriesDetail(root, fallback)

        assertEquals(RemoteId("1001"), detail.record.remoteId)
        assertEquals("Silent Protocol 1 (2021)", detail.record.name)
        assertTrue(detail.seasons.isNotEmpty())
        val numbers = detail.seasons.map { it.number }
        assertEquals(numbers.sorted(), numbers)
        for (season in detail.seasons) {
            val eps = season.episodes.map { it.number }
            assertEquals(eps.sorted(), eps)
            assertTrue(season.episodes.all { it.seriesId == fallback.remoteId })
        }
        val firstEp = detail.seasons.first().episodes.first()
        assertNotNull(firstEp.stillUrl)
        assertTrue(firstEp.stillUrl!!.startsWith("http"))
        assertNotNull(firstEp.durationSec)
    }

    @Test
    fun vodDetailMapsInfoAndMovieData() {
        val arr = parseArray(fixture("vod_streams.json"))
        val fallback = XtreamMappers.vod(arr[0] as JsonObject, sourceId, 0)!!
        val root = parseObj(fixture("vod_info.json"))
        val detail = XtreamMappers.vodDetail(root, fallback)

        assertEquals("Movie Title 1 (1998)", detail.record.name)
        assertEquals(8845, detail.durationSec)
        assertEquals("1998-01-01", detail.releaseDate)
        assertEquals(1998, detail.record.year)
        assertEquals("91118", detail.record.tmdbId)
        assertEquals(2, detail.backdropUrls.size)
        assertNull(detail.trailerUrl)
        assertNotNull(detail.plot)
        assertNotNull(detail.cast)
    }

    @Test
    fun shortEpgTitlesAreBase64Decoded() {
        val listings = parseArray(fixture("short_epg.json"))
        val root = parseObj("""{"epg_listings": ${listings}}""")
        val programmes = XtreamMappers.shortEpg(root, sourceId, "ch1001.mock")

        assertEquals(4, programmes.size)
        assertEquals("Show 692", programmes[0].title)
        assertEquals("ch1001.mock", programmes[0].channelKey)
        assertTrue(programmes.all { it.startMs < it.endMs })
        assertTrue(programmes[0].description!!.contains("Daily Roundup"))
        assertEquals(1790445600000L, programmes[0].startMs)
    }

    @Test
    fun accountFixtures() {
        val ok = XtreamMappers.accountInfo(parseObj(fixture("account.json")))
        assertEquals(AccountStatus.ACTIVE, ok.status)
        assertEquals(2, ok.maxConnections)
        assertEquals(0, ok.activeConnections)
        assertEquals(false, ok.isTrial)
        assertEquals(listOf("m3u8", "ts"), ok.allowedOutputFormats)
        assertEquals("UTC", ok.serverTimeZone)
        assertNotNull(ok.expiresAtMs)
        assertNotNull(ok.serverNowMs)

        val fail = XtreamMappers.accountInfo(parseObj(fixture("auth_fail.json")))
        assertEquals(AccountStatus.AUTH_FAILED, fail.status)
    }

    @Test
    fun chaosLiveNeverThrowsAndMostItemsMap() {
        val arr = parseArray(fixture("chaos_live_streams.json"))
        val mapped = arr.mapIndexed { i, el -> XtreamMappers.channel(el as? JsonObject ?: return@mapIndexed null, sourceId, i) }
        val ok = mapped.count { it != null }
        assertTrue(ok >= arr.size * 0.9, "only $ok of ${arr.size} chaos channels mapped")
    }

    @Test
    fun chaosVodNeverThrowsAndMostItemsMap() {
        val arr = parseArray(fixture("chaos_vod_streams.json"))
        val mapped = arr.mapIndexed { i, el -> XtreamMappers.vod(el as? JsonObject ?: return@mapIndexed null, sourceId, i) }
        val ok = mapped.count { it != null }
        assertTrue(ok >= arr.size * 0.9, "only $ok of ${arr.size} chaos vod items mapped")
    }

    @Test
    fun chaosSeriesInfoNeverThrows() {
        val root = parseObj(fixture("chaos_series_info.json"))
        val fallback = SeriesRecord(
            sourceId = sourceId,
            remoteId = RemoteId("1001"),
            name = "Fallback",
            posterUrl = null,
            backdropUrls = emptyList(),
            categoryIds = emptyList(),
            plot = null,
            genre = null,
            rating = null,
            year = null,
            lastModifiedMs = null,
            sortIndex = 0,
        )
        val detail = XtreamMappers.seriesDetail(root, fallback)
        assertTrue(detail.seasons.all { it.episodes.size >= 0 })
    }

    @Test
    fun quirksLiveExactlySixOfEightMap() {
        val arr = parseArray(fixture("quirks_live.json"))
        assertEquals(8, arr.size)
        val mapped = arr.mapIndexed { i, el -> XtreamMappers.channel(el as? JsonObject ?: return@mapIndexed null, sourceId, i) }
        val nonNull = mapped.filterNotNull()
        assertEquals(6, nonNull.size)
        assertNull(mapped[6])
        assertNull(mapped[7])

        val byId = nonNull.associateBy { it.remoteId.value }
        assertTrue(byId.keys.containsAll(listOf("101", "102", "103", "104", "105", "106")))
        assertEquals(7, byId["101"]!!.catchupDays)
        assertEquals(listOf(RemoteId("7")), byId["101"]!!.categoryIds)

        assertNull(byId["102"]!!.number)
        assertNull(byId["102"]!!.logoUrl)
        assertEquals(listOf(RemoteId("2"), RemoteId("3")), byId["102"]!!.categoryIds)
        assertEquals(0, byId["102"]!!.catchupDays)

        assertEquals(5, byId["103"]!!.catchupDays)
        assertEquals(listOf(RemoteId("4")), byId["103"]!!.categoryIds)

        assertEquals("& News", byId["104"]!!.name)
        assertNull(byId["104"]!!.logoUrl)
        assertEquals(listOf(RemoteId("6")), byId["104"]!!.categoryIds)

        assertEquals(listOf(RemoteId("8")), byId["105"]!!.categoryIds)
        assertEquals(3, byId["105"]!!.catchupDays)

        assertEquals("Zeta Six", byId["106"]!!.name)
        assertEquals(listOf(RemoteId("9")), byId["106"]!!.categoryIds)
    }

    @Test
    fun seriesDetailHandlesArrayOfArraysEpisodes() {
        val root = parseObj(
            """
            {
              "info": {"series_id": "77", "name": "Array Show", "cover": "http://mock.local/c.png"},
              "episodes": [
                [
                  {"id": "7702", "episode_num": 2, "title": "E2", "season": 1, "info": {"duration_secs": 100}},
                  {"id": "7701", "episode_num": 1, "title": "E1", "season": 1}
                ],
                [
                  {"id": "7711", "episode_num": 1, "title": "S2E1", "season": 2, "info": {"movie_image": "http://mock.local/s.png"}}
                ]
              ]
            }
            """.trimIndent()
        )
        val fallback = SeriesRecord(
            sourceId = sourceId,
            remoteId = RemoteId("77"),
            name = "Array Show",
            posterUrl = null,
            backdropUrls = emptyList(),
            categoryIds = emptyList(),
            plot = null,
            genre = null,
            rating = null,
            year = null,
            lastModifiedMs = null,
            sortIndex = 0,
        )
        val detail = XtreamMappers.seriesDetail(root, fallback)

        assertEquals(2, detail.seasons.size)
        assertEquals(listOf(1, 2), detail.seasons.map { it.number })
        assertEquals(listOf(1, 2), detail.seasons[0].episodes.map { it.number })
        assertEquals("E1", detail.seasons[0].episodes[0].title)
        assertEquals(100, detail.seasons[0].episodes[1].durationSec)
        assertEquals("http://mock.local/s.png", detail.seasons[1].episodes[0].stillUrl)
        assertEquals("Season 1", detail.seasons[0].name)
    }

    @Test
    fun mappersNeverThrowOnGarbageRoots() {
        val garbage = parseObj("""{"nope": 1}""")
        assertNull(XtreamMappers.channel(garbage, sourceId, 0))
        assertNull(XtreamMappers.vod(garbage, sourceId, 0))
        assertNull(XtreamMappers.series(garbage, sourceId, 0))
        assertNull(XtreamMappers.category(garbage, ContentKind.LIVE, sourceId, 0))
        assertEquals(emptyList(), XtreamMappers.shortEpg(garbage, sourceId, "k"))
        val vodArr = parseArray(fixture("vod_streams.json"))
        val vod = XtreamMappers.vod(vodArr[0] as JsonObject, sourceId, 0)!!
        assertEquals(vod.name, XtreamMappers.vodDetail(garbage, vod).record.name)
        val seriesArr = parseArray(fixture("series.json"))
        val series = XtreamMappers.series(seriesArr[0] as JsonObject, sourceId, 0)!!
        assertEquals(series.name, XtreamMappers.seriesDetail(garbage, series).record.name)
    }
}
