package com.yodesla.omniverse.core.source.m3u

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CatchupTemplatesTest {

    // 2026-09-26T12:00:00Z
    private val startMs = 1_790_424_000_000L
    // +1h
    private val endMs = startMs + 3_600_000L
    // 2026-09-26T14:03:20Z
    private val nowMs = 1_790_431_400_000L

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

    private fun build(e: M3uEntry): String? = buildCatchupUrl(e, startMs, endMs, nowMs)

    @Test
    fun defaultAbsoluteTemplate() {
        val e = entry("http://h.test/live/c.m3u8", "default", "http://h.test/catch/{utc}-{utcend}.ts")
        assertEquals("http://h.test/catch/1790424000-1790427600.ts", build(e))
    }

    @Test
    fun defaultRelativeTemplateIsAppendedToStreamUrl() {
        val e = entry("http://h.test/live/c.m3u8", "default", "?start={utc}&end={utcend}")
        assertEquals("http://h.test/live/c.m3u8?start=1790424000&end=1790427600", build(e))
        val amp = entry("http://h.test/live/c.m3u8", "default", "&t={utc}")
        assertEquals("http://h.test/live/c.m3u8&t=1790424000", build(amp))
    }

    @Test
    fun defaultAllPlaceholders() {
        val e = entry(
            "http://h.test/live/c.m3u8",
            "default",
            "http://h.test/v/{Y}/{m}/{d}/{H}/{M}/{S}/{duration}/{duration:60}/{offset}/{lutc}.m3u8",
        )
        assertEquals("http://h.test/v/2026/09/26/12/00/00/3600/60/7400/1790431400.m3u8", build(e))
    }

    @Test
    fun defaultDollarSyntaxPlaceholders() {
        val e = entry(
            "http://h.test/live/c.m3u8",
            "default",
            "http://h.test/w/\${start}-\${end}-\${now}",
        )
        assertEquals("http://h.test/w/1790424000-1790427600-1790431400", build(e))
    }

    @Test
    fun appendModeConcatenatesSubstitutedSource() {
        val e = entry("http://s.test/live/c.m3u8", "append", "t/{utc}/{utcend}")
        assertEquals("http://s.test/live/c.m3u8t/1790424000/1790427600", build(e))
    }

    @Test
    fun shiftModeAppendsUtcAndLutc() {
        assertEquals(
            "http://s.test/live/c.m3u8?utc=1790424000&lutc=1790431400",
            build(entry("http://s.test/live/c.m3u8", "shift")),
        )
        assertEquals(
            "http://s.test/live/c.m3u8?x=1&utc=1790424000&lutc=1790431400",
            build(entry("http://s.test/live/c.m3u8?x=1", "shift")),
        )
    }

    @Test
    fun flussonicM3u8() {
        assertEquals(
            "http://f.test/ch/index-1790424000-3600.m3u8",
            build(entry("http://f.test/ch/index.m3u8", "flussonic")),
        )
        // "fs" alias
        assertEquals(
            "http://f.test/ch/index-1790424000-3600.m3u8",
            build(entry("http://f.test/ch/index.m3u8", "fs")),
        )
    }

    @Test
    fun flussonicMpegts() {
        assertEquals(
            "http://f.test/ch/timeshift_abs-1790424000.ts",
            build(entry("http://f.test/ch/mpegts", "flussonic")),
        )
    }

    @Test
    fun xtreamWithAndWithoutLiveSegment() {
        assertEquals(
            "http://x.test:8080/timeshift/u/p/60/2026-09-26:12-00/1234.ts",
            build(entry("http://x.test:8080/live/u/p/1234.ts", "xc")),
        )
        assertEquals(
            "http://x.test/timeshift/u/p/60/2026-09-26:12-00/1234.ts",
            build(entry("http://x.test/u/p/1234.ts", "xc")),
        )
    }

    @Test
    fun nullWhenNoCatchupOrTemplateUnusable() {
        assertNull(build(entry("http://h.test/live/c.m3u8")))
        assertNull(build(entry("http://h.test/live/c.m3u8", "default")))
        assertNull(build(entry("http://h.test/live/c.m3u8", "append")))
        assertNull(build(entry("http://h.test/live/c.m3u8", "weird", "http://h.test/t/{utc}")))
    }

    @Test
    fun catchupSourceAloneActsAsMode() {
        // iptvsimple allows the mode itself to live in catchup-source
        assertEquals(
            "http://f.test/ch/index-1790424000-3600.m3u8",
            build(entry("http://f.test/ch/index.m3u8", null, "flussonic")),
        )
    }
}
