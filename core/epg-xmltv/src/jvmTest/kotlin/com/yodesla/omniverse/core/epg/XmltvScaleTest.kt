package com.yodesla.omniverse.core.epg

import com.yodesla.omniverse.core.model.SourceId
import com.yodesla.omniverse.core.model.TimeWindow
import okio.Buffer
import okio.GzipSink
import okio.buffer
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class XmltvScaleTest {

    private val sourceId = SourceId("scale")
    private var seed = 42L

    private fun rnd(): Long {
        seed = seed * 6364136223846793005L + 1442695040888963407L
        return seed ushr 32
    }

    private fun stamp(hoursFrom2026Sep26: Int): String {
        val t = LocalDateTime.of(2026, 9, 26, 0, 0).plusHours(hoursFrom2026Sep26.toLong())
        return String.format("%04d%02d%02d%02d%02d%02d", t.year, t.monthValue, t.dayOfMonth, t.hour, t.minute, t.second)
    }

    private val window = TimeWindow(
        LocalDateTime.of(2026, 9, 25, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli(),
        LocalDateTime.of(2026, 10, 3, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli(),
    )

    /** Builds XMLTV of [channels] channels with hourly programmes, directly into a [Buffer]. */
    private fun generate(channels: Int, programmesPerChannel: Int): Buffer {
        seed = 42L
        val b = Buffer()
        b.writeUtf8("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<tv>\n")
        for (c in 0 until channels) {
            b.writeUtf8("<channel id=\"ch$c\"><display-name>Channel $c</display-name></channel>\n")
        }
        for (c in 0 until channels) {
            for (h in 0 until programmesPerChannel) {
                val sentences = 2 + (rnd() % 5).toInt()
                val desc = StringBuilder()
                repeat(sentences) { i ->
                    desc.append("Filler text $i for channel $c hour $h to make the description realistically long. ")
                }
                b.writeUtf8("<programme start=\"${stamp(h)}\" stop=\"${stamp(h + 1)}\" channel=\"ch$c\">")
                b.writeUtf8("<title>Channel $c programme $h</title>")
                b.writeUtf8("<desc>")
                b.writeUtf8(desc.toString())
                b.writeUtf8("</desc>")
                b.writeUtf8("<category>Variety</category>")
                b.writeUtf8("</programme>\n")
            }
        }
        b.writeUtf8("</tv>")
        return b
    }

    @Test
    fun scaleParseFilteredStaysFast() {
        val xml = generate(500, 72)
        val sizeMb = xml.size / (1024.0 * 1024.0)
        assertTrue(sizeMb in 10.0..120.0, "generated file is ${"%.1f".format(sizeMb)} MB")

        val keys = (0 until 500).map { "ch$it" }.toSet()
        val t0 = System.nanoTime()
        val records = XmltvParser().parse(xml.buffer(), sourceId, window, keys).toList()
        val secs = (System.nanoTime() - t0) / 1e9

        assertEquals(500 * 72, records.size)
        assertTrue(secs < 10.0, "parse of ${"%.1f".format(sizeMb)} MB took ${"%.2f".format(secs)} s")
    }

    @Test
    fun gzipYieldsSameCountAsPlain() {
        val plain = generate(200, 72)
        val gz = Buffer()
        val sink = GzipSink(gz).buffer()
        // writeAll() would drain `plain`; compress a copy.
        sink.writeAll(plain.copy())
        sink.close()
        assertTrue(gz.size < plain.size)

        val keys = (0 until 100).map { "ch$it" }.toSet()
        val plainCount = XmltvParser().parse(plain.copy().buffer(), sourceId, window, keys).toList().size
        val gzCount = XmltvParser().parse(maybeGunzip(gz.buffer()), sourceId, window, keys).toList().size
        val passthroughCount = XmltvParser().parse(maybeGunzip(plain.copy().buffer()), sourceId, window, keys).toList().size

        assertEquals(100 * 72, plainCount)
        assertEquals(plainCount, gzCount)
        assertEquals(plainCount, passthroughCount)
        // Empty and 1-byte bodies pass through without throwing.
        assertEquals(0, XmltvParser().parse(maybeGunzip(Buffer()), sourceId, window, keys).count())
        assertEquals(0, XmltvParser().parse(maybeGunzip(Buffer().writeUtf8("<")), sourceId, window, keys).count())
    }
}
