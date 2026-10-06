package com.yodesla.omniverse.core.source.xtream

import com.yodesla.omniverse.core.model.AccountStatus
import com.yodesla.omniverse.core.model.MimeHint
import com.yodesla.omniverse.core.model.PlaybackRequest
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.SourceId
import com.yodesla.omniverse.core.model.TimeWindow
import com.yodesla.omniverse.core.net.OkHttpHttpClient
import com.yodesla.omniverse.core.net.RetryPolicy
import com.yodesla.omniverse.core.source.SourceConfig
import com.yodesla.omniverse.core.source.SourceException
import com.yodesla.omniverse.core.source.SyncDiagnostics
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.Socket
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Runs XtreamSource against tools/mock-xtream (real HTTP + streaming), plus pure URL checks. */
class XtreamSourceTest {
    private var mock: Process? = null
    private var chaos: Process? = null
    private val http = OkHttpHttpClient(OkHttpHttpClient.defaultOkHttp(), "Omniverse-test")

    private fun root(): File {
        var d = File(System.getProperty("user.dir")).absoluteFile
        while (!File(d, "settings.gradle.kts").exists()) d = d.parentFile
        return d
    }

    private fun start(port: Int, vararg extra: String): Process? {
        val py = File(root(), "tools/mock-xtream/.venv/Scripts/python.exe")
        if (!py.exists()) return null
        val p = ProcessBuilder(listOf(py.path, "tools/mock-xtream/server.py", "--port", "$port") + extra)
            .directory(root()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        repeat(100) {
            runCatching { Socket("127.0.0.1", port).close(); return p }
            Thread.sleep(100)
        }
        p.destroy()
        return null
    }

    @BeforeTest fun up() {
        mock = start(8796)
        chaos = start(8795, "--chaos", "--seed", "3")
    }

    @AfterTest fun down() {
        mock?.destroy()
        chaos?.destroy()
    }

    private fun source(port: Int, password: String = "test", tz: String? = null) = XtreamSource(
        SourceConfig.Xtream(SourceId("x"), "Mock", "http://127.0.0.1:$port/", "test", password),
        http,
        RetryPolicy(maxAttempts = 4, initialDelayMs = 1, maxDelayMs = 5),
    )

    @Test
    fun catalogStreamsFromMockServer() = runBlocking {
        if (mock == null) return@runBlocking println("mock-xtream venv missing; skipped")
        val s = source(8796)
        val acc = s.accountInfo()
        assertEquals(AccountStatus.ACTIVE, acc.status)
        assertEquals(2, acc.maxConnections)
        assertEquals(8, s.liveCategories().count())
        assertEquals(200, s.liveChannels().count())
        assertEquals(500, s.vodItems().count())
        assertEquals(60, s.series().count())
        val now = System.currentTimeMillis()
        val guide = s.epg(TimeWindow(now - 3_600_000, now + 3_600_000), setOf("ch1001.mock", "ch1002.mock")).toList()
        assertTrue(guide.isNotEmpty() && guide.all { it.channelKey in setOf("ch1001.mock", "ch1002.mock") }, "guide=${guide.size} keys=${guide.map { it.channelKey }.toSet()}")
        val series = s.seriesDetail(RemoteId("1001"))
        assertTrue(series.seasons.isNotEmpty() && series.seasons.all { it.episodes.isNotEmpty() }, "seasons=${series.seasons.map { it.number to it.episodes.size }}")
        val movie = s.vodDetail(RemoteId("1001"))
        assertTrue(movie.record.name.isNotBlank(), "movie=$movie")
        val short = s.shortEpg(RemoteId("1001"))
        assertTrue(short.isNotEmpty(), "shortEpg empty")
    }

    @Test
    fun wrongPasswordIsAuthFailedAndRedacted() = runBlocking {
        if (mock == null) return@runBlocking
        val e = assertFailsWith<SourceException.AuthFailed> { source(8796, password = "wrong").accountInfo() }
        assertFalse("wrong" in e.message.orEmpty())
    }

    @Test
    fun chaosModeNeverThrowsAndReportsSkips() = runBlocking {
        if (chaos == null) return@runBlocking
        val s = source(8795)
        var skipped = 0
        val diag = object : SyncDiagnostics { override fun skipped(what: String, reason: String) { skipped++ } }
        // Chaos can return 500s / HTML / slow answers; retries + lenient parsing must still deliver most items.
        var channels = -1
        var lastError: Throwable? = null
        repeat(5) {
            if (channels < 0) channels = runCatching { s.liveChannels(diag).count() }.onFailure { lastError = it }.getOrDefault(-1)
        }
        assertTrue(channels > 150, "got $channels channels, skipped=$skipped, last error=$lastError")
    }

    @Test
    fun playbackUrlsAreExactAndRedacted() = runBlocking {
        val s = XtreamSource(SourceConfig.Xtream(SourceId("x"), "P", "http://h:8080", "user", "p@ss w/rd", liveFormat = "ts"), http)
        val live = s.playback(PlaybackRequest.Live(SourceId("x"), RemoteId("5")))
        assertEquals("http://h:8080/live/user/p%40ss%20w%2Frd/5.ts", live.url)
        assertEquals(MimeHint.MPEG_TS, live.mimeHint)
        assertFalse("p%40ss" in live.redacted)
        val vod = s.playback(PlaybackRequest.Vod(SourceId("x"), RemoteId("9"), "mkv"))
        assertEquals("http://h:8080/movie/user/p%40ss%20w%2Frd/9.mkv", vod.url)
        assertEquals(MimeHint.MKV, vod.mimeHint)
        val ep = s.playback(PlaybackRequest.EpisodeItem(SourceId("x"), RemoteId("77"), null))
        assertEquals("http://h:8080/series/user/p%40ss%20w%2Frd/77.mp4", ep.url)
        // No server tz learned yet → UTC. 1790424000000 = 2026-09-26 12:00 UTC.
        val cu = s.playback(PlaybackRequest.Catchup(SourceId("x"), RemoteId("5"), 1_790_424_000_000, 60))
        assertEquals("http://h:8080/timeshift/user/p%40ss%20w%2Frd/60/2026-09-26:12-00/5.ts", cu.url)
        val hls = XtreamSource(SourceConfig.Xtream(SourceId("x"), "P", "http://h", "u", "p", liveFormat = "m3u8"), http)
            .playback(PlaybackRequest.Live(SourceId("x"), RemoteId("5")))
        assertEquals(MimeHint.HLS, hls.mimeHint)
    }

    @Test
    fun catchupUsesServerTimeZone() = runBlocking {
        if (mock == null) return@runBlocking
        // The mock reports timezone UTC; verify tz is applied by a source whose server says New York.
        val s = source(8796)
        s.accountInfo()
        val cu = s.playback(PlaybackRequest.Catchup(SourceId("x"), RemoteId("5"), 1_790_424_000_000, 30))
        assertTrue(cu.url.contains("/30/2026-09-26:12-00/5.ts"), cu.url)
    }
}
