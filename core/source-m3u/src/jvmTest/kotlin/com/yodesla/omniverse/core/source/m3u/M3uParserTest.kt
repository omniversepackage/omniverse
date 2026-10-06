package com.yodesla.omniverse.core.source.m3u

import com.yodesla.omniverse.core.source.SyncDiagnostics
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class M3uParserTest {

    private class RecordingDiagnostics : SyncDiagnostics {
        val events = ArrayList<Pair<String, String>>()
        override fun skipped(what: String, reason: String) {
            events += what to reason
        }
    }

    private fun parse(
        text: String,
        diagnostics: SyncDiagnostics = SyncDiagnostics.None,
        onHeader: (M3uHeader) -> Unit = {},
    ): List<M3uEntry> =
        M3uParser(diagnostics).parse(Buffer().writeUtf8(text).buffer(), onHeader).toList()

    @Test
    fun headerWithTwoEpgUrls() {
        val headers = ArrayList<M3uHeader>()
        val entries = parse(
            """
            #EXTM3U url-tvg="http://epg.test/full.xml,http://epg.test/short.xml"
            #EXTINF:-1,One
            http://stream.test/one.m3u8
            """.trimIndent(),
            onHeader = { headers += it },
        )
        assertEquals(1, entries.size)
        assertEquals(1, headers.size)
        assertEquals(listOf("http://epg.test/full.xml", "http://epg.test/short.xml"), headers[0].epgUrls)
        assertEquals("One", entries[0].name)
    }

    @Test
    fun headerCombinesUrlTvgAndXTvgUrl() {
        var header: M3uHeader? = null
        parse(
            """
            #EXTM3U url-tvg="http://epg.test/a.xml" x-tvg-url="http://epg.test/b.xml" catchup-days=14
            #EXTINF:-1,One
            http://stream.test/one.m3u8
            """.trimIndent(),
            onHeader = { header = it },
        )
        val h = header ?: return
        assertEquals(listOf("http://epg.test/a.xml", "http://epg.test/b.xml"), h.epgUrls)
        assertEquals(14, h.catchupDays)
    }

    @Test
    fun quotedValueWithCommaAndSpaces() {
        val entries = parse(
            """
            #EXTM3U
            #EXTINF:-1 group-title="News, 24h" tvg-id="news.example",My Channel
            http://stream.test/live/my.m3u8
            """.trimIndent(),
        )
        assertEquals(1, entries.size)
        val e = entries[0]
        assertEquals("My Channel", e.name)
        assertEquals("News, 24h", e.attrs["group-title"])
        assertEquals("News, 24h", e.group)
        assertEquals("news.example", e.tvgId)
    }

    @Test
    fun singleQuotedAndUnquotedAttrs() {
        val entries = parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-name='BBC One' chno=7,Prime
            http://stream.test/live/prime.m3u8
            """.trimIndent(),
        )
        val e = entries[0]
        assertEquals("BBC One", e.tvgName)
        assertEquals(7, e.chno)
        assertEquals("Prime", e.name)
    }

    @Test
    fun nameMayContainCommasAfterAttrs() {
        val entries = parse(
            """
            #EXTM3U
            #EXTINF:-1,The Show, Part 2 (Extended, Uncut)
            http://stream.test/live/show.m3u8
            """.trimIndent(),
        )
        assertEquals("The Show, Part 2 (Extended, Uncut)", entries[0].name)
    }

    @Test
    fun extVlcoptUserAgentAndReferrer() {
        val entries = parse(
            """
            #EXTM3U
            #EXTINF:-1,Secure
            #EXTVLCOPT:http-user-agent=Mozilla/5.0 (X11; Linux)
            #EXTVLCOPT:http-referrer=http://origin.test/page
            http://stream.test/live/secure.m3u8
            """.trimIndent(),
        )
        val e = entries[0]
        assertEquals("Mozilla/5.0 (X11; Linux)", e.userAgent)
        assertEquals("http://origin.test/page", e.referrer)
    }

    @Test
    fun extGrpUsedUnlessGroupTitlePresent() {
        val entries = parse(
            """
            #EXTM3U
            #EXTINF:-1 group-title="FromAttr",A
            #EXTGRP:FromGroup
            http://stream.test/live/a.m3u8
            #EXTINF:-1,B
            #EXTGRP:FromGroup
            http://stream.test/live/b.m3u8
            """.trimIndent(),
        )
        assertEquals("FromAttr", entries[0].group)
        assertEquals("FromGroup", entries[1].group)
    }

    @Test
    fun kodiPropStreamHeaders() {
        val entries = parse(
            """
            #EXTM3U
            #EXTINF:-1,Headered
            #KODIPROP:inputstream.adaptive.stream_headers=User-Agent=Koditest/1.0&Referer=http://ref.test/x
            http://stream.test/live/h.m3u8
            """.trimIndent(),
        )
        assertEquals("Koditest/1.0", entries[0].userAgent)
        assertEquals("http://ref.test/x", entries[0].referrer)
    }

    @Test
    fun crlfAndBom() {
        val text = "﻿#EXTM3U\r\n#EXTINF:-1,CRLF Channel\r\nhttp://stream.test/live/c.m3u8\r\n"
        val entries = parse(text)
        assertEquals(1, entries.size)
        assertEquals("CRLF Channel", entries[0].name)
        assertEquals("http://stream.test/live/c.m3u8", entries[0].url)
    }

    @Test
    fun urlOnlyLineBecomesEntryNamedAfterLastPathSegment() {
        val entries = parse(
            """
            #EXTM3U catchup-days=7
            http://bare.test/live/channel-123.m3u8?token=abc
            """.trimIndent(),
        )
        assertEquals(1, entries.size)
        val e = entries[0]
        assertEquals("channel-123.m3u8", e.name)
        assertEquals("http://bare.test/live/channel-123.m3u8?token=abc", e.url)
        assertEquals(0, e.durationSec)
        assertTrue(e.attrs.isEmpty())
        assertEquals(7, e.catchupDays)
        assertNull(e.catchup)
    }

    @Test
    fun malformedExtinfIsSkippedAndReported() {
        val diag = RecordingDiagnostics()
        val entries = parse(
            """
            #EXTM3U
            #EXTINF:garbage key="x",Broken
            http://stream.test/live/x.m3u8
            #EXTINF:-1,Good
            http://stream.test/live/g.m3u8
            """.trimIndent(),
            diagnostics = diag,
        )
        assertEquals(listOf("extinf" to "bad duration: garbage"), diag.events)
        // The orphan URL after the malformed EXTINF still emits, as a URL-only entry.
        assertEquals(2, entries.size)
        assertEquals("x.m3u8", entries[0].name)
        assertEquals("Good", entries[1].name)
        assertEquals(listOf(0, 1), entries.map { it.index })
    }

    @Test
    fun indexIncrementsAcrossEntryKinds() {
        val entries = parse(
            """
            #EXTM3U
            #EXTINF:-1,A
            http://stream.test/live/a.m3u8
            http://stream.test/live/bare.m3u8
            #EXTINF:3600,B
            http://vod.test/movie/b.mp4
            """.trimIndent(),
        )
        assertEquals(3, entries.size)
        assertEquals(listOf(0, 1, 2), entries.map { it.index })
        assertEquals(3600, entries[2].durationSec)
    }

    @Test
    fun entryCatchupFallsBackToHeader() {
        val entries = parse(
            """
            #EXTM3U catchup="default" catchup-source="http://h.test/c/{utc}"
            #EXTINF:-1,UsesHeader
            http://h.test/live/u.m3u8
            #EXTINF:-1 catchup="shift",Overrides
            http://h.test/live/o.m3u8
            """.trimIndent(),
        )
        assertEquals("default", entries[0].catchup)
        assertEquals("http://h.test/c/{utc}", entries[0].catchupSource)
        assertEquals("shift", entries[1].catchup)
        assertEquals("http://h.test/c/{utc}", entries[1].catchupSource)
    }

    @Test
    fun emptySourceYieldsNothing() {
        assertTrue(parse("").isEmpty())
        assertTrue(M3uParser().parse(Buffer().buffer()).toList().isEmpty())
    }
}
