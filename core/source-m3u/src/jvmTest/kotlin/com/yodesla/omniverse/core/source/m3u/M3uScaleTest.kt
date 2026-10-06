package com.yodesla.omniverse.core.source.m3u

import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class M3uScaleTest {

    private fun generate(entries: Int): Buffer {
        val b = Buffer()
        b.writeUtf8("#EXTM3U url-tvg=\"http://epg.test/a.xml\" catchup-days=7\n")
        for (i in 0 until entries) {
            b.writeUtf8("#EXTINF:-1 tvg-id=\"ch$i\" group-title=\"Group ${i % 10}\",Channel $i\n")
            b.writeUtf8("http://stream.test/live/channel-$i.m3u8\n")
        }
        return b
    }

    @Test
    fun parses100kEntriesInUnderFiveSeconds() {
        val playlist = generate(100_000)
        val sizeMb = playlist.size / (1024.0 * 1024.0)
        assertTrue(sizeMb > 1.0, "generated playlist is only ${"%.1f".format(sizeMb)} MB")

        val t0 = System.nanoTime()
        val count = M3uParser().parse(playlist.copy().buffer()).count()
        val secs = (System.nanoTime() - t0) / 1e9

        assertEquals(100_000, count)
        assertTrue(secs < 5.0, "parse of ${"%.1f".format(sizeMb)} MB took ${"%.2f".format(secs)} s")
    }

    @Test
    fun sequenceIsLazyAndReplayable() {
        val playlist = generate(1_000)
        val first = M3uParser().parse(playlist.copy().buffer()).take(2).toList()
        assertEquals(listOf("Channel 0", "Channel 1"), first.map { it.name })
        val all = M3uParser().parse(playlist.copy().buffer()).toList()
        assertEquals(1_000, all.size)
    }
}
