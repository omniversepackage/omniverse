package com.yodesla.omniverse.core.source.plex

import com.yodesla.omniverse.core.model.AccountStatus
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.MimeHint
import com.yodesla.omniverse.core.model.PlaybackRequest
import com.yodesla.omniverse.core.model.Redact
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.SourceId
import com.yodesla.omniverse.core.model.TimeWindow
import com.yodesla.omniverse.core.net.OkHttpHttpClient
import com.yodesla.omniverse.core.net.RetryPolicy
import com.yodesla.omniverse.core.source.SourceConfig
import com.yodesla.omniverse.core.source.SourceException
import com.yodesla.omniverse.core.source.SyncDiagnostics
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer

/** PlexSource against a MockWebServer; JSON fixtures live in src/jvmTest/resources/plex/. */
class PlexSourceTest {
    private val http = OkHttpHttpClient(OkHttpHttpClient.defaultOkHttp(), "Omniverse-test")
    private val token = "tok-very-secret-123"
    private lateinit var server: MockWebServer
    private lateinit var base: String

    @BeforeTest
    fun up() {
        server = MockWebServer()
        server.start()
        base = server.url("/").toString().trimEnd('/')
    }

    @AfterTest
    fun down() {
        server.close()
    }

    private fun fixture(name: String): String =
        javaClass.classLoader.getResource("plex/$name")!!.readText()

    private fun source() = PlexSource(
        SourceConfig.Plex(SourceId("p"), "Mock", base, token, "m-abc-123", "omni-client-1"),
        http,
        RetryPolicy(maxAttempts = 2, initialDelayMs = 1, maxDelayMs = 5),
    )

    private fun enqueue(name: String) {
        server.enqueue(MockResponse.Builder().code(200).setHeader("Content-Type", "application/json").body(fixture(name)).build())
    }

    private class Diagnostics : SyncDiagnostics {
        val skips = mutableListOf<Pair<String, String>>()
        override fun skipped(what: String, reason: String) {
            skips += what to reason
        }
    }

    @Test
    fun accountInfoIsActiveAndSendsPlexHeaders() = runBlocking {
        enqueue("identity.json")
        val acc = source().accountInfo()
        assertEquals(AccountStatus.ACTIVE, acc.status)
        assertEquals(null, acc.expiresAtMs)
        val req = server.takeRequest()
        assertEquals("/identity", req.url.encodedPath)
        assertEquals(token, req.headers.get("X-Plex-Token"))
        assertEquals("omni-client-1", req.headers.get("X-Plex-Client-Identifier"))
        assertEquals("Omniverse", req.headers.get("X-Plex-Product"))
        assertEquals("application/json", req.headers.get("Accept"))
    }

    @Test
    fun accountInfoRejectsAReachableButDifferentServer() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body(
            fixture("identity.json").replace("m-abc-123", "another-machine")
        ).build())
        assertFailsWith<SourceException.BadResponse> { source().accountInfo() }
    }

    @Test
    fun sectionsBecomeCategoriesByType() = runBlocking {
        enqueue("sections.json")
        val vod = source().vodCategories().toList()
        assertEquals(1, vod.size)
        assertEquals("Movies", vod[0].name)
        assertEquals("1", vod[0].remoteId.value)
        assertEquals(ContentKind.VOD, vod[0].kind)

        enqueue("sections.json")
        val series = source().seriesCategories().toList()
        assertEquals(1, series.size)
        assertEquals("Shows", series[0].name)
        assertEquals("2", series[0].remoteId.value)
        assertEquals(ContentKind.SERIES, series[0].kind)
    }

    @Test
    fun multipleLibrariesOnOneServerAreAllImported() = runBlocking {
        val libraries = """{"MediaContainer":{"Directory":[
            {"key":"1","title":"Movies","type":"movie"},
            {"key":"2","title":"Family Movies","type":"movie"},
            {"key":"3","title":"Shows","type":"show"},
            {"key":"4","title":"Documentaries","type":"show"}
        ]}}"""
        fun enqueueLibraries() = server.enqueue(MockResponse.Builder().code(200).body(libraries).build())
        enqueueLibraries()
        assertEquals(listOf("Movies", "Family Movies"), source().vodCategories().toList().map { it.name })
        enqueueLibraries()
        assertEquals(listOf("Shows", "Documentaries"), source().seriesCategories().toList().map { it.name })

        enqueueLibraries()
        server.enqueue(MockResponse.Builder().code(200).body("""{"MediaContainer":{"size":1,"totalSize":1,"Metadata":[{"ratingKey":"11","title":"Movie One"}]}}""").build())
        server.enqueue(MockResponse.Builder().code(200).body("""{"MediaContainer":{"size":1,"totalSize":1,"Metadata":[{"ratingKey":"22","title":"Movie Two"}]}}""").build())
        assertEquals(listOf("Movie One", "Movie Two"), source().vodItems().toList().map { it.name })
        assertEquals("/library/sections", server.takeRequest().url.encodedPath)
        assertEquals("/library/sections", server.takeRequest().url.encodedPath)
        assertEquals("/library/sections", server.takeRequest().url.encodedPath)
        assertEquals("/library/sections/1/all", server.takeRequest().url.encodedPath)
        assertEquals("/library/sections/2/all", server.takeRequest().url.encodedPath)
    }

    @Test
    fun vodPagingStopsOnPartialPage() = runBlocking {
        enqueue("sections.json")
        val page1 = buildString {
            append("""{"MediaContainer":{"size":200,"Metadata":[""")
            append((0 until 200).joinToString(",") { i ->
                """{"ratingKey":"m-${i.toString().padStart(3, '0')}","title":"Movie ${i}","year":2000}"""
            })
            append("]}}")
        }
        server.enqueue(MockResponse.Builder().code(200).body(page1).build())
        enqueue("movies_page2.json")

        val items = source().vodItems().toList()
        assertEquals(202, items.size)
        assertEquals("m-000", items[0].remoteId.value)
        assertEquals("Movie 199", items[199].name)
        assertEquals("Beta Movie", items[200].name)
        assertEquals("Gamma", items[201].name)
        assertEquals(9f, items[201].rating)
        assertEquals(1700000000000L, items[200].addedAtMs)
        assertTrue(items[200].posterUrl!!.startsWith("$base/library/sections/1/items/201/thumb.jpg?"))

        assertEquals("/library/sections", server.takeRequest().url.encodedPath)
        val r1 = server.takeRequest()
        assertEquals("/library/sections/1/all", r1.url.encodedPath)
        assertEquals("type=1&X-Plex-Container-Start=0&X-Plex-Container-Size=200", r1.url.encodedQuery)
        val r2 = server.takeRequest()
        assertEquals("/library/sections/1/all", r2.url.encodedPath)
        assertEquals("type=1&X-Plex-Container-Start=200&X-Plex-Container-Size=200", r2.url.encodedQuery)
    }

    @Test
    fun malformedItemIsSkippedAndCounted() = runBlocking {
        enqueue("sections.json")
        server.enqueue(MockResponse.Builder().code(200).body(
            fixture("movies_page2.json").replace("\"totalSize\": 203", "\"totalSize\": 3").replace("\"offset\": 200", "\"offset\": 0")
        ).build()) // 3 items, one without a title; < 200 so paging stops
        val diag = Diagnostics()
        val items = source().vodItems(diag).toList()
        assertEquals(2, items.size)
        assertEquals(listOf("Beta Movie", "Gamma"), items.map { it.name })
        assertEquals(1, diag.skips.size)
        assertEquals("vod", diag.skips[0].first)
    }

    @Test
    fun malformedPageFailsInsteadOfRemovingExistingMovies() = runBlocking {
        enqueue("sections.json")
        server.enqueue(MockResponse.Builder().code(200).body("""{"MediaContainer":{"size":2,"error":"temporary"}}""").build())
        assertFailsWith<SourceException.BadResponse> { source().vodItems().toList() }
    }

    @Test
    fun shortPageWithMoreResultsContinuesAtActualOffset() = runBlocking {
        enqueue("sections.json")
        server.enqueue(MockResponse.Builder().code(200).body("""{"MediaContainer":{"size":1,"totalSize":2,"Metadata":[{"ratingKey":"1","title":"First"}]}}""").build())
        server.enqueue(MockResponse.Builder().code(200).body("""{"MediaContainer":{"size":1,"totalSize":2,"Metadata":[{"ratingKey":"2","title":"Second"}]}}""").build())
        assertEquals(listOf("First", "Second"), source().vodItems().toList().map { it.name })
        server.takeRequest() // sections
        server.takeRequest() // first page
        assertEquals("type=1&X-Plex-Container-Start=1&X-Plex-Container-Size=200", server.takeRequest().url.encodedQuery)
    }

    @Test
    fun unauthorizedIsAuthFailedWithoutTokenInMessage() = runBlocking {
        server.enqueue(MockResponse.Builder().code(401).body("unauthorized").build())
        val e = assertFailsWith<SourceException.AuthFailed> { source().accountInfo() }
        assertFalse(token in (e.message ?: ""), "token leaked: ${e.message}")
    }

    @Test
    fun httpErrorsAreRedactedSourceExceptions() = runBlocking {
        server.enqueue(MockResponse.Builder().code(404).body("""{"error":"$token"}""").build())
        val e = assertFailsWith<SourceException.Http> { source().vodDetail(RemoteId("77")) }
        assertEquals(404, e.status)
        assertFalse(token in (e.message ?: ""), "token leaked: ${e.message}")
    }

    @Test
    fun playbackUrlComesFromPartKey() = runBlocking {
        enqueue("movie_metadata.json")
        val spec = source().playback(PlaybackRequest.Vod(SourceId("p"), RemoteId("77"), "mkv"))
        assertEquals("$base/library/parts/5550/film.mkv?X-Plex-Token=$token", spec.url)
        assertEquals(MimeHint.MKV, spec.mimeHint)
        assertFalse(spec.isLive)
        assertTrue(spec.seekable)
        assertTrue(token in spec.url)
        assertFalse(token in spec.redacted, "redacted leaked token: ${spec.redacted}")
        assertEquals(Redact.text(spec.url), spec.redacted)

        enqueue("movie_metadata.json")
        val ep = source().playback(PlaybackRequest.EpisodeItem(SourceId("p"), RemoteId("77"), null))
        assertEquals(spec.url, ep.url)

        assertFailsWith<SourceException.Unsupported> {
            source().playback(PlaybackRequest.Live(SourceId("p"), RemoteId("77")))
        }
        Unit
    }

    @Test
    fun playbackSkipsMediaVersionsWithoutPlayableParts() = runBlocking {
        val body = fixture("movie_metadata.json").replace(
            "\"Media\": [\n          {",
            "\"Media\": [{\"Part\":[]}, {\"Part\":[{}]}, {",
        )
        server.enqueue(MockResponse.Builder().code(200).body(body).build())
        val spec = source().playback(PlaybackRequest.Vod(SourceId("p"), RemoteId("77"), "mkv"))
        assertEquals("$base/library/parts/5550/film.mkv?X-Plex-Token=$token", spec.url)
    }

    @Test
    fun plexMovieListsAndPlaysSelectedVersion() = runBlocking {
        val body = fixture("movie_metadata.json").replace(
            "\"Media\": [\n          {",
            "\"Media\": [{\"videoResolution\":\"1080\",\"Part\":[{\"key\":\"/library/parts/1080/film.mkv\",\"container\":\"mkv\"}]}, {",
        )
        server.enqueue(MockResponse.Builder().code(200).body(body).build())
        val detail = source().vodDetail(RemoteId("77"))
        assertEquals(2, detail.versions.size)
        assertEquals(0, detail.versions[0].index)
        assertTrue("1080p" in detail.versions[0].label)
        assertEquals(1, detail.versions[1].index)

        server.enqueue(MockResponse.Builder().code(200).body(body).build())
        val chosen = source().playback(PlaybackRequest.Vod(SourceId("p"), RemoteId("77"), "mkv", versionId = detail.versions[1].id))
        assertEquals("$base/library/parts/5550/film.mkv?X-Plex-Token=$token", chosen.url)

        server.enqueue(MockResponse.Builder().code(200).body(body).build())
        assertFailsWith<SourceException.NotFound> {
            source().playback(PlaybackRequest.Vod(SourceId("p"), RemoteId("77"), "mkv", versionId = "missing"))
        }
    }

    @Test
    fun plexPartsWithinOneMediaAreNotSeparateVersions() = runBlocking {
        val body = fixture("movie_metadata.json").replace(
            "\"Part\": [\n              {",
            "\"Part\": [{\"key\":\"/library/parts/first.mkv\"}, {",
        )
        server.enqueue(MockResponse.Builder().code(200).body(body).build())
        assertEquals(1, source().vodDetail(RemoteId("77")).versions.size)
    }

    @Test
    fun playbackOnlyExposesValidIntroAndCreditMarkers() = runBlocking {
        val body = fixture("movie_metadata.json").replace(
            "\"Media\": [",
            "\"Marker\":[{" +
                "\"type\":\"intro\",\"startTimeOffset\":10000,\"endTimeOffset\":45000}," +
                "{\"type\":\"credits\",\"startTimeOffset\":7000000,\"endTimeOffset\":7100000}," +
                "{\"type\":\"intro\",\"startTimeOffset\":90000,\"endTimeOffset\":80000}," +
                "{\"type\":\"bookmark\",\"startTimeOffset\":100,\"endTimeOffset\":200}],\"Media\": [",
        )
        server.enqueue(MockResponse.Builder().code(200).body(body).build())
        val spec = source().playback(PlaybackRequest.Vod(SourceId("p"), RemoteId("77"), "mkv"))
        assertEquals(listOf("intro", "credits"), spec.skipMarkers.map { it.type })
        assertEquals(45000, spec.skipMarkers.first().endMs)
    }

    @Test
    fun playbackPreservesPartQueryAndEncodesToken() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body(
            fixture("movie_metadata.json").replace("/library/parts/5550/film.mkv", "/library/parts/5550/film.mkv?download=1")
        ).build())
        val specialToken = "tok+/="
        val plex = PlexSource(
            SourceConfig.Plex(SourceId("p"), "Mock", base, specialToken, "m-abc-123", "omni-client-1"), http,
            RetryPolicy(maxAttempts = 2, initialDelayMs = 1, maxDelayMs = 5),
        )
        val spec = plex.playback(PlaybackRequest.Vod(SourceId("p"), RemoteId("77"), "mkv"))
        assertEquals("$base/library/parts/5550/film.mkv?download=1&X-Plex-Token=tok%2B%2F%3D", spec.url)
        assertEquals(MimeHint.MKV, spec.mimeHint)
        assertFalse("tok%2B%2F%3D" in spec.redacted)
    }

    @Test
    fun vodDetailMapsCastGenresAndRatings() = runBlocking {
        enqueue("movie_metadata.json")
        val d = source().vodDetail(RemoteId("77"))
        assertEquals("Detail Movie", d.record.name)
        assertEquals("550088", d.record.tmdbId)
        assertEquals("7.2f".toFloat(), d.record.rating)
        assertEquals("Jane Doe, John Roe", d.cast)
        assertEquals("Jane Director", d.director)
        assertEquals("Drama, Mystery", d.genre)
        assertEquals(7200, d.durationSec)
        assertEquals("2021-05-07", d.releaseDate)
        assertEquals(listOf("$base/library/sections/1/items/77/art.jpg?X-Plex-Token=$token"), d.backdropUrls)
    }

    @Test
    fun seriesDetailListsSeasonsAndEpisodes() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body(
            fixture("show_metadata.json").replace("\"Genre\":", "\"Role\":[{\"tag\":\"Actor One\"}],\"Director\":[{\"tag\":\"Director One\"}],\"Genre\":")
        ).build())
        enqueue("series_children.json")
        enqueue("season_children.json")
        val d = source().seriesDetail(RemoteId("50"))
        assertEquals("My Show", d.record.name)
        assertEquals("Actor One", d.cast)
        assertEquals("Director One", d.director)
        assertEquals(1, d.seasons.size)
        val season = d.seasons[0]
        assertEquals(1, season.number)
        assertEquals("Season 1", season.name)
        assertEquals(2, season.episodes.size)
        val e1 = season.episodes[0]
        assertEquals("e1", e1.remoteId.value)
        assertEquals("50", e1.seriesId.value)
        assertEquals("Pilot", e1.title)
        assertEquals("It begins.", e1.plot)
        assertEquals(3000, e1.durationSec)
        assertEquals("$base/library/sections/2/items/e1/thumb.jpg?X-Plex-Token=$token", e1.stillUrl)
        assertEquals(2, season.episodes[1].number)
        assertEquals(null, season.episodes[1].durationSec)
    }

    @Test
    fun seriesListUsesShowSectionsWithPaging() = runBlocking {
        enqueue("sections.json")
        enqueue("show_metadata.json")
        val list = source().series().toList()
        assertEquals(1, list.size)
        assertEquals("My Show", list[0].name)
        assertEquals(1700000200000L, list[0].lastModifiedMs)
        assertEquals("Comedy", list[0].genre)
    }

    @Test
    fun liveEpgAreEmpty() = runBlocking {
        val s = source()
        assertEquals(0, s.liveCategories().toList().size)
        assertEquals(0, s.liveChannels().toList().size)
        assertEquals(0, s.epg(TimeWindow(0, 1000), null).toList().size)
        assertEquals(emptyList(), s.shortEpg(RemoteId("x")))
        // no HTTP traffic at all for the empty ones
        assertEquals(0, server.requestCount)
    }
}

