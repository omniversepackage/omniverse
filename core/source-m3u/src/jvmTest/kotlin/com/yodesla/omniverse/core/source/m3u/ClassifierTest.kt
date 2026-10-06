package com.yodesla.omniverse.core.source.m3u

import kotlin.test.Test
import kotlin.test.assertEquals

class ClassifierTest {

    private fun entry(
        url: String,
        catchup: String? = null,
        catchupSource: String? = null,
    ): M3uEntry = M3uEntry(
        name = "n",
        url = url,
        durationSec = -1,
        attrs = emptyMap(),
        group = null,
        tvgId = null,
        tvgName = null,
        logo = null,
        chno = null,
        catchup = catchup,
        catchupDays = null,
        catchupSource = catchupSource,
        userAgent = null,
        referrer = null,
        index = 0,
    )

    @Test
    fun plainM3u8StreamIsLive() {
        assertEquals(M3uKind.LIVE, classify(entry("http://h.test/live/c.m3u8")))
    }

    @Test
    fun moviePathMarkerWins() {
        assertEquals(M3uKind.MOVIE, classify(entry("http://h.test/movie/anything.m3u8")))
    }

    @Test
    fun seriesPathMarkerWins() {
        assertEquals(M3uKind.SERIES, classify(entry("http://h.test/series/s1/ep1.m3u8")))
    }

    @Test
    fun seriesPathBeatsMovieExtension() {
        assertEquals(M3uKind.SERIES, classify(entry("http://h.test/series/x.mp4")))
    }

    @Test
    fun mediaExtensionWithoutCatchupIsMovie() {
        assertEquals(M3uKind.MOVIE, classify(entry("http://h.test/vod/a.mp4")))
        assertEquals(M3uKind.MOVIE, classify(entry("http://h.test/vod/b.mkv")))
        assertEquals(M3uKind.MOVIE, classify(entry("http://h.test/vod/c.avi")))
    }

    @Test
    fun mediaExtensionWithCatchupStaysLive() {
        assertEquals(M3uKind.LIVE, classify(entry("http://h.test/vod/a.mp4", catchup = "default")))
        assertEquals(M3uKind.LIVE, classify(entry("http://h.test/vod/a.mp4", catchupSource = "flussonic")))
    }

    @Test
    fun queryDoesNotDefeatExtension() {
        assertEquals(M3uKind.MOVIE, classify(entry("http://h.test/x.mp4?token=1")))
    }

    @Test
    fun m3u8ExtensionIsLive() {
        assertEquals(M3uKind.LIVE, classify(entry("http://h.test/vod/a.m3u8")))
    }
}
