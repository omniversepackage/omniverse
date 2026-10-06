package com.yodesla.omniverse.core.epg

import com.yodesla.omniverse.core.model.ProgrammeRecord
import com.yodesla.omniverse.core.model.SourceId
import com.yodesla.omniverse.core.model.TimeWindow
import com.yodesla.omniverse.core.source.SyncDiagnostics
import okio.Buffer
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class XmltvParserTest {

    private val sourceId = SourceId("test-source")
    private val wideWindow = TimeWindow(0L, Long.MAX_VALUE)

    private class RecordingDiagnostics : SyncDiagnostics {
        val events = ArrayList<Pair<String, String>>()
        override fun skipped(what: String, reason: String) {
            events += what to reason
        }
    }

    private fun parse(
        xml: String,
        window: TimeWindow = wideWindow,
        channelKeys: Set<String>? = null,
        diagnostics: SyncDiagnostics = SyncDiagnostics.None,
        onChannel: (XmltvChannel) -> Unit = {},
    ): List<ProgrammeRecord> =
        XmltvParser(diagnostics)
            .parse(Buffer().writeUtf8(xml).buffer(), sourceId, window, channelKeys, onChannel)
            .toList()

    private fun utc(y: Int, m: Int, d: Int, h: Int, min: Int = 0): Long =
        LocalDateTime.of(y, m, d, h, min).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test
    fun channelCallbackFiresWithDisplayNameAndIcon() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE tv SYSTEM "xmltv.dtd">
            <!-- some comment -->
            <tv>
              <channel id="c1">
                <display-name>One &amp; Only</display-name>
                <icon src="http://example.test/c1.png"/>
              </channel>
              <channel id="c2"/>
              <programme start="20260926120000 +0000" stop="20260926130000 +0000" channel="c1"><title>X</title></programme>
            </tv>
        """.trimIndent()
        val channels = ArrayList<XmltvChannel>()
        parse(xml) { channels += it }
        assertEquals(2, channels.size)
        assertEquals(XmltvChannel("c1", "One & Only", "http://example.test/c1.png"), channels[0])
        assertEquals(XmltvChannel("c2", null, null), channels[1])
    }

    @Test
    fun programmeFieldsCdataEntitiesEpisodeOnscreenFirstCategory() {
        val xml = """
            <tv>
              <programme start="20260926120000 +0000" stop="20260926130000 +0000" channel="c1">
                <title lang="en">A &amp; B: part &quot;two&quot; &#8212; it&apos;s <gt/> test</title>
                <sub-title><![CDATA[Sub <b>bold</b> & raw]]></sub-title>
                <desc>  Long   desc   with  \t tabs and
                    newlines  </desc>
                <category lang="en">First</category>
                <category>Second</category>
                <episode-num system="dd">1.2</episode-num>
                <episode-num system="onscreen">S01E02</episode-num>
                <icon src="http://example.test/p.png"/>
              </programme>
            </tv>
        """.trimIndent()
        val records = parse(xml)
        assertEquals(1, records.size)
        val r = records[0]
        assertEquals(sourceId, r.sourceId)
        assertEquals("c1", r.channelKey)
        assertEquals(utc(2026, 9, 26, 12), r.startMs)
        assertEquals(utc(2026, 9, 26, 13), r.endMs)
        assertEquals("A & B: part \"two\" \u2014 it's test", r.title)
        assertEquals("Sub <b>bold</b> & raw", r.subtitle)
        assertEquals("Long desc with \\t tabs and newlines", r.description)
        assertEquals("First", r.category)
        assertEquals("S01E02", r.episodeNum)
        assertEquals("http://example.test/p.png", r.iconUrl)
        assertEquals(false, r.hasArchive)
    }

    @Test
    fun emptyTitleBecomesNoTitle() {
        val xml = """
            <tv>
              <programme start="20260926120000" stop="20260926130000" channel="c1"><title>   </title></programme>
            </tv>
        """.trimIndent()
        assertEquals("(No title)", parse(xml).single().title)
    }

    @Test
    fun channelKeysFilterAndWindowFilter() {
        val windowStart = utc(2026, 9, 26, 12)
        val windowEnd = utc(2026, 9, 26, 13)
        val window = TimeWindow(windowStart, windowEnd)
        val xml = """
            <tv>
              <programme start="20260926110000" stop="20260926123000" channel="c1"><title>A overlap-start</title></programme>
              <programme start="20260926100000" stop="20260926120000" channel="c1"><title>B ends-at-start</title></programme>
              <programme start="20260926124500" stop="20260926131500" channel="c1"><title>C overlap-end</title></programme>
              <programme start="20260926130000" stop="20260926140000" channel="c1"><title>D starts-at-end</title></programme>
              <programme start="20260926110000" stop="20260926150000" channel="c2"><title>E other-channel</title></programme>
            </tv>
        """.trimIndent()

        val all = parse(xml, window)
        // No channel filter: E (other channel, 11:00-15:00) overlaps the window, so it is kept.
        assertEquals(listOf("A overlap-start", "C overlap-end", "E other-channel"), all.map { it.title })

        val filtered = parse(xml, window, channelKeys = setOf("c1"))
        assertEquals(listOf("A overlap-start", "C overlap-end"), filtered.map { it.title })
        val onlyC2 = parse(xml, window, channelKeys = setOf("c2"))
        assertEquals(listOf("E other-channel"), onlyC2.map { it.title })
        val none = parse(xml, window, channelKeys = setOf("c3"))
        assertEquals(emptyList<ProgrammeRecord>(), none)
    }

    @Test
    fun unknownElementsAreIgnored() {
        val xml = """
            <tv>
              <programme start="20260926120000" stop="20260926130000" channel="c1">
                <title>T</title>
                <credits><actor>Someone</actor><director>Other</director></credits>
                <rating><value>18</value></rating>
                <url>http://example.test/p</url>
                <video-rating>12</video-rating>
              </programme>
            </tv>
        """.trimIndent()
        val records = parse(xml)
        assertEquals(1, records.size)
        assertEquals("T", records[0].title)
        assertNull(records[0].description)
    }

    @Test
    fun malformedProgrammeIsReportedAndParsingContinues() {
        val diagnostics = RecordingDiagnostics()
        val xml = """
            <tv>
              <programme start="garbage" stop="20260926130000" channel="c1"><title>Bad</title></programme>
              <programme start="20260926130000" channel="c1"><title>No stop</title></programme>
              <programme start="20260926140000" stop="20260926150000"><title>No channel</title></programme>
              <programme start="20260926150000" stop="20260926160000" channel="c1"><title>Good</title></programme>
            </tv>
        """.trimIndent()
        val records = parse(xml, diagnostics = diagnostics)
        assertEquals(listOf("Good"), records.map { it.title })
        assertEquals(3, diagnostics.events.size)
        assertTrue(diagnostics.events[0].first == "programme" && "start" in diagnostics.events[0].second)
        assertTrue("stop" in diagnostics.events[1].second)
        assertTrue("channel" in diagnostics.events[2].second)
    }

    @Test
    fun truncatedInputReturnsCompleteProgrammes() {
        val full = """
            <tv>
              <programme start="20260926120000" stop="20260926130000" channel="c1"><title>First</title></programme>
              <programme start="20260926130000" stop="20260926140000" channel="c1"><title>Se
        """.trimIndent()
        assertEquals(listOf("First"), parse(full).map { it.title })
        // Cut inside a tag attribute, and at the very first bytes too.
        assertEquals(emptyList<ProgrammeRecord>(), parse("<tv><programme start="))
    }

    @Test
    fun multibyteTitleSurvivesChunkBoundary() {
        val title = "Ñandú 🎬 Überraschung"
        val head = """<?xml version="1.0"?><tv><programme start="20260926120000" stop="20260926130000" channel="c1"><title>"""
        val tail = "</title></programme></tv>"
        val headBytes = head.toByteArray(Charsets.UTF_8).size
        // Pad so the first 3 UTF-8 bytes of the title ("Ña") sit before byte 65536
        // and the rest after it.
        val padLen = 65536 - 3 - headBytes
        val xml = head + "x".repeat(padLen) + title + tail
        val xmlBytes = xml.toByteArray(Charsets.UTF_8)
        val titleStart = headBytes + padLen
        assertTrue(titleStart < 65536)
        assertTrue(titleStart + 3 <= 65536 && titleStart + title.toByteArray(Charsets.UTF_8).size > 65536)
        assertTrue(xmlBytes.size > 65536)
        val records = parse(xml)
        // The padding sits inside <title>, so the parsed title is padding + title; the multi-byte
        // tail straddling byte 65536 must survive intact.
        assertEquals("x".repeat(padLen) + title, records.single().title)
    }

    @Test
    fun entitiesSplitAcrossChunksAreDecoded() {
        val head = """<tv><programme start="20260926120000" stop="20260926130000" channel="c1"><desc>"""
        val tail = "</desc></programme></tv>"
        val headBytes = head.toByteArray(Charsets.UTF_8).size
        // "R&B" written as R&amp;B: put "&am" in chunk 1 and "p;B" in chunk 2.
        val padLen = 65536 - 3 - headBytes - 1
        val xml = head + "R" + "x".repeat(padLen) + "&amp;B" + tail
        val records = parse(xml)
        assertEquals("R" + "x".repeat(padLen) + "&B", records.single().description)
    }
}
