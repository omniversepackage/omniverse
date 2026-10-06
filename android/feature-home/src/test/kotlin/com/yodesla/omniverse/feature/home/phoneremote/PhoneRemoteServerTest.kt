package com.yodesla.omniverse.feature.home.phoneremote

import com.yodesla.omniverse.core.model.NowPlaying
import com.yodesla.omniverse.core.model.NowPlayingTrack
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneRemoteServerTest {
    private val token = "0123456789ab"
    private val code = "123456"

    private fun newServer(
        actions: MutableList<PhoneRemoteAction> = mutableListOf(),
        results: List<RemoteResult> = emptyList(),
        nowPlaying: NowPlaying? = null,
    ): Pair<PhoneRemoteServer, CoroutineScope> {
        val scope = CoroutineScope(Job() + Dispatchers.IO)
        val s = PhoneRemoteServer(
            token = token, code = code,
            onAction = { actions.add(it) },
            onSearch = { results },
            onConnected = {},
            onNowPlaying = { nowPlaying },
            port = 0,
        )
        return s to scope
    }

    private fun call(port: Int, path: String, method: String = "GET", body: String? = null): Pair<Int, String> {
        val url = URL("http://127.0.0.1:$port$path")
        val c = url.openConnection() as HttpURLConnection
        c.requestMethod = method
        if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) } }
        val status = c.responseCode
        val text = (if (status in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        return status to text
    }

    @Test fun tokenRequiredOnEveryRequest() {
        val (s, scope) = newServer()
        val port = s.start(scope, "127.0.0.1")!!
        try {
            assertEquals(403, call(port, "/").first)
            assertEquals(403, call(port, "/search?k=bad&q=x&code=$code").first)
            assertEquals(200, call(port, "/?k=$token").first)
        } finally { s.stop(); scope.coroutineContext[Job]?.cancel() }
    }

    @Test fun codeGatesDataEndpoints() {
        val results = listOf(RemoteResult("VOD", "src1", "m1", "cat1", "Movie", "2024", null, "movie"))
        val (s, scope) = newServer(results = results)
        val port = s.start(scope, "127.0.0.1")!!
        try {
            assertEquals(403, call(port, "/search?k=$token&q=movie&code=000000").first)
            val (st, body) = call(port, "/search?k=$token&q=movie&code=$code")
            assertEquals(200, st)
            assertTrue(body.contains("\"title\":\"Movie\""))
            assertTrue(body.contains("\"action\":\"movie\""))
        } finally { s.stop(); scope.coroutineContext[Job]?.cancel() }
    }

    @Test fun fiveWrongCodesShutServerDown() {
        val (s, scope) = newServer()
        val port = s.start(scope, "127.0.0.1")!!
        try {
            repeat(5) { assertEquals(403, call(port, "/search?k=$token&q=x&code=000000").first) }
            // After the 5th strike the server is closed; the next request fails to connect.
            val closed = runCatching { call(port, "/search?k=$token&q=x&code=$code") }.exceptionOrNull()
            assertNotNull(closed)
        } finally { s.stop(); scope.coroutineContext[Job]?.cancel() }
    }

    @Test fun actionEndpointRoutesToTv() {
        val actions = mutableListOf<PhoneRemoteAction>()
        val (s, scope) = newServer(actions = actions)
        val port = s.start(scope, "127.0.0.1")!!
        try {
            val (st, _) = call(port, "/action?k=$token", "POST", "action=key:up&code=$code")
            assertEquals(200, st)
            assertEquals(PhoneRemoteAction.Key(PhoneRemoteKey.Up), actions.single())
        } finally { s.stop(); scope.coroutineContext[Job]?.cancel() }
    }

    @Test fun parseActionMapsEveryKind() {
        assertEquals(PhoneRemoteAction.Key(PhoneRemoteKey.PlayPause), PhoneRemoteServer.parseAction("key:playpause"))
        val ch = PhoneRemoteServer.parseAction("play:channel:src:rem:cat") as PhoneRemoteAction.PlayChannel
        assertEquals("rem", ch.key.remoteId.value); assertEquals("cat", ch.categoryId.value)
        val mv = PhoneRemoteServer.parseAction("play:movie:src:rem") as PhoneRemoteAction.PlayMovie
        assertEquals("src", mv.key.sourceId.value)
        val show = PhoneRemoteServer.parseAction("open:show:src:rem") as PhoneRemoteAction.OpenShow
        assertEquals("rem", show.key.remoteId.value)
        assertNull(PhoneRemoteServer.parseAction("play:movie:src"))
        assertNull(PhoneRemoteServer.parseAction("nonsense"))
    }

    @Test fun parseActionNowPlayingCommands() {
        assertEquals(PhoneRemoteAction.Seek(12345L), PhoneRemoteServer.parseAction("seek:12345"))
        assertEquals(PhoneRemoteAction.Seek(0L), PhoneRemoteServer.parseAction("seek:0"))
        assertEquals(PhoneRemoteAction.Skip(10000L), PhoneRemoteServer.parseAction("skip:+10000"))
        assertEquals(PhoneRemoteAction.Skip(-10000L), PhoneRemoteServer.parseAction("skip:-10000"))
        // Engine track ids contain ':' ("1:2") — the id is everything after the command.
        assertEquals(PhoneRemoteAction.SelectAudio("1:2"), PhoneRemoteServer.parseAction("audio:1:2"))
        assertEquals(PhoneRemoteAction.SelectSubtitle(null), PhoneRemoteServer.parseAction("subs:off"))
        assertEquals(PhoneRemoteAction.SelectSubtitle("2:1"), PhoneRemoteServer.parseAction("subs:2:1"))
        assertNull(PhoneRemoteServer.parseAction("seek:abc"))
        assertNull(PhoneRemoteServer.parseAction("seek:-5"))
        assertNull(PhoneRemoteServer.parseAction("seek:"))
        assertNull(PhoneRemoteServer.parseAction("skip:0"))
        assertNull(PhoneRemoteServer.parseAction("skip:abc"))
        assertNull(PhoneRemoteServer.parseAction("skip:99999999999999"))
        assertNull(PhoneRemoteServer.parseAction("audio:"))
        assertNull(PhoneRemoteServer.parseAction("subs:"))
    }

    @Test fun nowEndpointRequiresTokenAndCode() {
        val np = NowPlaying(
            title = "Movie", posterUrl = "http://p/m.jpg", kind = "VOD",
            positionMs = 61_000L, durationMs = 600_000L, isPlaying = true, isLive = false,
            channelName = null,
            audioTracks = listOf(NowPlayingTrack("1:0", "English")), selectedAudioId = "1:0",
            subtitleTracks = listOf(NowPlayingTrack("2:0", "English [forced]")), selectedSubtitleId = null,
        )
        val (s, scope) = newServer(nowPlaying = np)
        val port = s.start(scope, "127.0.0.1")!!
        try {
            assertEquals(403, call(port, "/now").first) // no token
            assertEquals(403, call(port, "/now?k=$token&code=000000").first) // wrong code
            val (st, body) = call(port, "/now?k=$token&code=$code")
            assertEquals(200, st)
            assertTrue(body.startsWith("{\"nowPlaying\":{"))
            assertTrue(body.contains("\"title\":\"Movie\""))
            assertTrue(body.contains("\"positionMs\":61000"))
            assertTrue(body.contains("\"durationMs\":600000"))
            assertTrue(body.contains("\"isLive\":false"))
            assertTrue(body.contains("\"audioSelected\":\"1:0\""))
            assertTrue(body.contains("\"subsSelected\":null"))
            assertTrue(body.contains("\"label\":\"English [forced]\""))
        } finally { s.stop(); scope.coroutineContext[Job]?.cancel() }
    }

    @Test fun nowJsonIsNullWhenNothingPlays() {
        assertEquals("{\"nowPlaying\":null}", PhoneRemoteServer.jsonNowPlaying(null))
        val (s, scope) = newServer()
        val port = s.start(scope, "127.0.0.1")!!
        try {
            val (st, body) = call(port, "/now?k=$token&code=$code")
            assertEquals(200, st)
            assertEquals("{\"nowPlaying\":null}", body)
        } finally { s.stop(); scope.coroutineContext[Job]?.cancel() }
    }

    @Test fun parseQueryDecodes() {
        val q = PhoneRemoteServer.parseQuery("k=abc&q=hello%20world&code=123456")
        assertEquals("abc", q["k"]); assertEquals("hello world", q["q"]); assertEquals("123456", q["code"])
    }

    @Test fun tokenAndCodeShapes() {
        assertTrue(PhoneRemoteServer.newToken().matches(Regex("^[0-9a-f]{12}$")))
        assertTrue(PhoneRemoteServer.newCode().matches(Regex("^\\d{6}$")))
    }
}
