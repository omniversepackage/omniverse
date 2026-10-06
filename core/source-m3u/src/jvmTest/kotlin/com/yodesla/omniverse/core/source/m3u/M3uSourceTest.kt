package com.yodesla.omniverse.core.source.m3u

import com.yodesla.omniverse.core.model.AccountStatus
import com.yodesla.omniverse.core.model.Capability
import com.yodesla.omniverse.core.model.MimeHint
import com.yodesla.omniverse.core.model.PlaybackRequest
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.SourceId
import com.yodesla.omniverse.core.model.TimeWindow
import com.yodesla.omniverse.core.net.HttpClient
import com.yodesla.omniverse.core.net.HttpResponse
import com.yodesla.omniverse.core.net.RetryPolicy
import com.yodesla.omniverse.core.source.SourceConfig
import com.yodesla.omniverse.core.source.SourceException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okio.Buffer
import okio.BufferedSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** M3uSource against in-memory playlist/EPG fixtures served by a counting fake HTTP client. */
class M3uSourceTest {

    private class FakeHttpClient : HttpClient {
        val requests = ArrayList<String>()
        private val bodies = LinkedHashMap<String, String>()
        private val statuses = HashMap<String, Int>()

        fun serve(url: String, body: String, status: Int = 200) {
            bodies[url] = body
            statuses[url] = status
        }

        override suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
            requests += url
            val body = bodies[url].orEmpty()
            val status = statuses[url] ?: 200
            return object : HttpResponse {
                override val status: Int get() = status
                override val headers: Map<String, String> get() = emptyMap()
                override val body: BufferedSource = Buffer().apply { writeUtf8(body) }
                override fun close() {}
            }
        }
    }

    private fun playlistBody() =
        javaClass.getResourceAsStream("/playlist.m3u")!!.readBytes().decodeToString()

    private fun guideBody() =
        javaClass.getResourceAsStream("/guide.xml")!!.readBytes().decodeToString()

    private fun newSource(clock: () -> Long = { 0L }): Pair<M3uSource, FakeHttpClient> {
        val fake = FakeHttpClient()
        fake.serve("http://playlist.test/live.m3u", playlistBody())
        fake.serve("http://epg.test/guide.xml", guideBody())
        val source = M3uSource(
            SourceConfig.M3u(SourceId("m"), "Test", "http://playlist.test/live.m3u"),
            fake,
            RetryPolicy(),
            Dispatchers.Default,
            clock,
        )
        return source to fake
    }

    // ---------------------------------------------------------------- catalog

    @Test
    fun catalogCountsAndCategories() = runBlocking {
        val (s, _) = newSource()
        assertEquals(
            listOf("News", "Sports", "Kids", "Uncategorized"),
            s.liveCategories().toList().map { it.name },
        )
        assertEquals(listOf("Action", "Drama"), s.vodCategories().toList().map { it.name })
        assertEquals(listOf("Series"), s.seriesCategories().toList().map { it.name })
        assertEquals(6, s.liveChannels().toList().size)
        assertEquals(3, s.vodItems().toList().size)
        assertEquals(1, s.series().toList().size)
    }

    @Test
    fun channelRecordFields() = runBlocking {
        val (s, _) = newSource()
        val channels = s.liveChannels().toList()
        val ch1 = channels.first { it.name == "Channel One" }
        assertEquals(101, ch1.number)
        assertEquals(RemoteId("ch1.test"), ch1.remoteId)
        assertEquals("ch1.test", ch1.epgChannelId)
        assertEquals("http://logo.test/1.png", ch1.logoUrl)
        assertEquals(0, ch1.catchupDays)
        val ch3 = channels.first { it.name == "Channel Three" }
        assertEquals(RemoteId("ch3.test"), ch3.remoteId)
        assertEquals(14, ch3.catchupDays)
        val ch4 = channels.first { it.name == "Channel Four" }
        assertEquals("Channel Four", ch4.epgChannelId)
    }

    @Test
    fun stableRemoteIdsAcrossParses() = runBlocking {
        val (a, _) = newSource()
        val (b, _) = newSource()
        assertEquals(a.liveChannels().toList().map { it.remoteId }, b.liveChannels().toList().map { it.remoteId })
        assertEquals(a.vodItems().toList().map { it.remoteId }, b.vodItems().toList().map { it.remoteId })
        val m1 = a.vodItems().toList().first { it.name == "Movie One" }
        assertEquals(RemoteId("m1.test"), m1.remoteId)
        val m2 = a.vodItems().toList().first { it.name == "Movie Two" }
        assertEquals(RemoteId(fnv1a64Hex("http://vod.test/movie/movie2.mkv")), m2.remoteId)
    }

    @Test
    fun seriesDetailGroupsByShow() {
        runBlocking {
        val (s, _) = newSource()
        val shows = s.series().toList()
        assertEquals("Show Name", shows.single().name)
        val detail = s.seriesDetail(shows.single().remoteId)
        assertEquals(2, detail.seasons.size)
        assertEquals(listOf(1, 2), detail.seasons.map { it.number })
        assertEquals(listOf(2, 2), detail.seasons.map { it.episodes.size })
        val s1 = detail.seasons.first()
        assertEquals(listOf("Show Name S01E01", "Show Name S01E02"), s1.episodes.map { it.title })
        assertEquals(listOf(RemoteId("e1.test"), RemoteId("e2.test")), s1.episodes.map { it.remoteId })
            assertFailsWith<SourceException.NotFound> { s.seriesDetail(RemoteId("nope")) }
        }
    }

    @Test
    fun vodDetailAndNotFound() {
        runBlocking {
        val (s, _) = newSource()
        val item = s.vodItems().toList().first { it.name == "Movie One" }
        val detail = s.vodDetail(item.remoteId)
        assertEquals("Movie One", detail.record.name)
        assertEquals("mp4", detail.record.containerExt)
            assertFailsWith<SourceException.NotFound> { s.vodDetail(RemoteId("nope")) }
        }
    }

    // ---------------------------------------------------------------- playback

    @Test
    fun playbackLiveUrlHeadersAndHints() {
        runBlocking {
        val (s, _) = newSource()
        s.liveChannels().toList() // warm the cache
        val ch1 = s.playback(PlaybackRequest.Live(SourceId("m"), RemoteId("ch1.test")))
        assertEquals("http://stream.test/live/ch1.m3u8", ch1.url)
        assertEquals(MimeHint.HLS, ch1.mimeHint)
        assertTrue(ch1.isLive)
        val ch3 = s.playback(PlaybackRequest.Live(SourceId("m"), RemoteId("ch3.test")))
        assertEquals(MimeHint.MPEG_TS, ch3.mimeHint)
        assertEquals("http://stream.test/", ch3.headers["Referer"])
        val movie = s.playback(PlaybackRequest.Vod(SourceId("m"), RemoteId("m1.test"), null))
        assertEquals("http://vod.test/movie/movie1.mp4", movie.url)
        assertEquals(MimeHint.MP4, movie.mimeHint)
        assertTrue(!movie.isLive && movie.seekable)
            assertFailsWith<SourceException.NotFound> {
                s.playback(PlaybackRequest.Live(SourceId("m"), RemoteId("nope")))
            }
        }
    }

    @Test
    fun playbackBeforeSyncLoadsThePlaylist() = runBlocking {
        val (s, _) = newSource() // fresh instance = app just restarted, nothing cached
        val ch1 = s.playback(PlaybackRequest.Live(SourceId("m"), RemoteId("ch1.test")))
        assertEquals("http://stream.test/live/ch1.m3u8", ch1.url)
    }

    @Test
    fun playbackCatchupUsesXtreamTemplate() = runBlocking {
        val start = 1_790_424_000_000L // 2026-09-26 12:00:00 UTC
        val (s, _) = newSource(clock = { start })
        s.liveChannels().toList()
        val spec = s.playback(PlaybackRequest.Catchup(SourceId("m"), RemoteId("ch3.test"), start, 60))
        assertEquals("http://stream.test/timeshift/user/pass/60/2026-09-26:12-00/3.ts", spec.url)
        assertEquals("http://stream.test/", spec.headers["Referer"])
    }

    @Test
    fun catchupCapabilityOnlyWhenConfigured() = runBlocking {
        val (s, _) = newSource()
        s.liveChannels().toList()
        assertTrue(Capability.CATCHUP in s.capabilities)
    }

    // ---------------------------------------------------------------- epg

    @Test
    fun epgFiltersByChannelKeys() = runBlocking {
        val (s, _) = newSource()
        val window = TimeWindow(1_790_420_400_000L, 1_790_434_800_000L) // 11:00..15:00 UTC
        val all = s.epg(window, null).toList()
        assertEquals(3, all.size)
        val ch1Only = s.epg(window, setOf("ch1.test")).toList()
        assertEquals(2, ch1Only.size)
        assertTrue(ch1Only.all { it.channelKey == "ch1.test" })
    }

    // ---------------------------------------------------------------- auth + cache

    @Test
    fun accountInfoActiveAfterSuccessfulFetch() = runBlocking {
        val (s, _) = newSource()
        assertEquals(AccountStatus.ACTIVE, s.accountInfo().status)
    }

    @Test
    fun forbiddenBecomesAuthFailed() {
        runBlocking {
            val (s, fake) = newSource()
            fake.serve("http://playlist.test/live.m3u", "", 403)
            assertFailsWith<SourceException.AuthFailed> { s.accountInfo() }
        }
    }

    @Test
    fun playlistIsCachedWithinTtl() = runBlocking {
        var now = 1_000L
        val (s, fake) = newSource(clock = { now })
        s.liveChannels().toList()
        s.vodItems().toList()
        s.series().toList()
        s.liveCategories().toList()
        assertEquals(1, fake.requests.count { it == "http://playlist.test/live.m3u" })
        now += 9 * 60_000L
        s.liveChannels().toList()
        assertEquals(1, fake.requests.count { it == "http://playlist.test/live.m3u" })
        now += 2 * 60_000L
        s.liveChannels().toList()
        assertEquals(2, fake.requests.count { it == "http://playlist.test/live.m3u" })
    }

    // ---------------------------------------------------------------- helpers

    @Test
    fun fnv1a64HexIsStableAndDistinct() {
        assertEquals(16, fnv1a64Hex("abc").length)
        assertEquals(fnv1a64Hex("abc"), fnv1a64Hex("abc"))
        assertTrue(fnv1a64Hex("abc") != fnv1a64Hex("abd"))
        assertTrue(fnv1a64Hex("abc").all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun factoryCreatesOnlyM3uConfigs() {
        val factory = M3uSourceFactory(FakeHttpClient())
        val m3u = factory.create(SourceConfig.M3u(SourceId("m"), "T", "http://p/x.m3u"))
        assertTrue(m3u != null)
        assertEquals(null, factory.create(SourceConfig.Xtream(SourceId("x"), "T", "http://h", "u", "p")))
    }
}
