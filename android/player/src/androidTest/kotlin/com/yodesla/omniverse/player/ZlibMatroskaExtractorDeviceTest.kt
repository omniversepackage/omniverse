package com.yodesla.omniverse.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.DataReader
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.util.zip.DeflaterOutputStream
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ZlibMatroskaExtractorDeviceTest {
    private class Track : TrackOutput {
        var mime = ""
        var samples = 0
        val bytes = ByteArrayOutputStream()
        override fun format(format: Format) { mime = format.sampleMimeType.orEmpty() }
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, part: Int): Int {
            val buffer = ByteArray(minOf(length, 8192))
            val count = input.read(buffer, 0, buffer.size)
            if (count == -1 && !allowEndOfInput) throw EOFException()
            if (count > 0) bytes.write(buffer, 0, count)
            return count
        }
        override fun sampleData(data: ParsableByteArray, length: Int, part: Int) {
            val buffer = ByteArray(length)
            data.readBytes(buffer, 0, length)
            bytes.write(buffer)
        }
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, crypto: TrackOutput.CryptoData?) {
            samples++
        }
    }

    private class Output : ExtractorOutput {
        val tracks = mutableMapOf<Int, Track>()
        override fun track(id: Int, type: Int): TrackOutput = tracks.getOrPut(id) { Track() }
        override fun endTracks() {}
        override fun seekMap(seekMap: SeekMap) {}
    }

    private fun parse(bytes: ByteArray, extractor: Extractor, prefix: Boolean = false): Output {
        var offset = 0
        val input = DefaultExtractorInput(DataReader { target, start, length ->
            val count = minOf(length, bytes.size - offset)
            if (count == 0) -1 else {
                bytes.copyInto(target, start, offset, offset + count)
                offset += count
                count
            }
        }, 0, bytes.size.toLong())
        val output = Output()
        extractor.init(output)
        try {
            val position = PositionHolder()
            while (true) {
                val result = extractor.read(input, position)
                if (result == Extractor.RESULT_END_OF_INPUT) break
                assertNotEquals("Fixture should not seek outside its prefix", Extractor.RESULT_SEEK, result)
            }
        } catch (error: EOFException) {
            if (!prefix) throw error
        } finally { extractor.release() }
        return output
    }

    private fun extractor(raw: Boolean = true) = ZlibMatroskaExtractor(DefaultSubtitleParserFactory(),
        MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES or
            (if (raw) MatroskaExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA else 0))

    private fun element(id: String, data: ByteArray): ByteArray {
        val key = id.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val size = if (data.size < 127) byteArrayOf((0x80 or data.size).toByte()) else
            byteArrayOf((0x40 or (data.size shr 8)).toByte(), data.size.toByte())
        return key + size + data
    }
    private fun number(id: String, value: Int) = element(id, byteArrayOf(value.toByte()))
    private fun zip(data: ByteArray): ByteArray = ByteArrayOutputStream().also {
        out -> DeflaterOutputStream(out).use { it.write(data) }
    }.toByteArray()

    private fun fixture(compression: Int?, explicit: Boolean = true): ByteArray {
        val ebml = element("1a45dfa3", number("42f7", 1) + element("4282", "matroska".toByteArray()) + number("4285", 2))
        val encoding = if (compression == null) byteArrayOf() else element("6d80", element("6240",
            element("5034", (if (explicit) number("4254", compression) else byteArrayOf()) +
                (if (compression == 3) element("4255", "Pika".toByteArray()) else byteArrayOf()))))
        val track = element("1654ae6b", element("ae", number("d7", 1) + number("83", 17) +
            element("86", "S_TEXT/UTF8".toByteArray()) + encoding))
        val text = "Pikachu!".toByteArray()
        val payload = when (compression) {
            0 -> zip(text)
            3 -> "chu!".toByteArray()
            else -> text
        }
        val block = element("a0", element("a1", byteArrayOf(0x81.toByte(), 0, 0, 0) + payload) +
            element("9b", byteArrayOf(3, 0xe8.toByte())))
        val cluster = element("1f43b675", number("e7", 0) + block)
        val info = element("1549a966", element("2ad7b1", byteArrayOf(0x0f, 0x42, 0x40)))
        return ebml + element("18538067", info + track + cluster)
    }

    @Test(timeout = 60_000) fun readsZlibAndDefaultAlgorithmAndPreservesOrdinaryMkv() {
        val ordinary = parse(fixture(null), extractor()).tracks.getValue(1)
        val stripped = parse(fixture(3), extractor()).tracks.getValue(1)
        val stockStripped = parse(fixture(3), MatroskaExtractor(DefaultSubtitleParserFactory(),
            MatroskaExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA)).tracks.getValue(1)
        assertArrayEquals(stockStripped.bytes.toByteArray(), stripped.bytes.toByteArray())
        for (explicit in listOf(true, false)) {
            val compressed = parse(fixture(0, explicit), extractor()).tracks.getValue(1)
            assertEquals(1, compressed.samples)
            assertArrayEquals(ordinary.bytes.toByteArray(), compressed.bytes.toByteArray())
            assertEquals(ordinary.mime, compressed.mime)
        }
        // Also exercise the production subtitle transcoding path.
        val transcoded = parse(fixture(0), extractor(raw = false)).tracks.getValue(1)
        assertEquals(MimeTypes.APPLICATION_MEDIA3_CUES, transcoded.mime)
        assertEquals(1, transcoded.samples)
    }

    @Test(timeout = 60_000) fun stockRejectsSameZlibFixtureAndOtherAlgorithmsRemainRejected() {
        try {
            parse(fixture(0), MatroskaExtractor(DefaultSubtitleParserFactory()))
            fail("Stock Media3 should reproduce ContentCompAlgo 0")
        } catch (expected: ParserException) { assertTrue(expected.message.orEmpty().contains("ContentCompAlgo 0")) }
        try {
            parse(fixture(1), extractor())
            fail("Unsupported compression must not silently pass")
        } catch (expected: ParserException) { assertTrue(expected.message.orEmpty().contains("ContentCompAlgo 1")) }
    }

    @Test(timeout = 60_000) fun actualPokemonPrefixIncludesVideoAudioAndExpandedPgs() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        assumeTrue("Place private prefix in src/androidTest/assets/pokemon-prefix.mkv", assets.list("")!!.contains("pokemon-prefix.mkv"))
        val bytes = assets.open("pokemon-prefix.mkv").use { it.readBytes() }
        val output = parse(bytes, extractor(), prefix = true)
        assertTrue(output.tracks.values.any { it.mime == MimeTypes.VIDEO_H265 && it.samples > 100 })
        assertTrue(output.tracks.values.any { it.mime == MimeTypes.AUDIO_AAC && it.samples > 100 })
        val pgs = output.tracks.values.single { it.mime == MimeTypes.APPLICATION_PGS }
        assertTrue("Original zlib PGS subtitles must survive", pgs.samples > 0)
        assertTrue(pgs.bytes.size() > 0)
        val production = parse(bytes, extractor(raw = false), prefix = true)
        assertTrue(production.tracks.values.any { it.mime == MimeTypes.APPLICATION_MEDIA3_CUES && it.samples > 0 })
        // The same actual bytes must reproduce the original failure in stock Media3.
        try {
            parse(bytes, MatroskaExtractor(DefaultSubtitleParserFactory(), MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES), true)
            fail("Expected original error")
        } catch (expected: ParserException) { assertTrue(expected.message.orEmpty().contains("ContentCompAlgo 0")) }
    }
}
