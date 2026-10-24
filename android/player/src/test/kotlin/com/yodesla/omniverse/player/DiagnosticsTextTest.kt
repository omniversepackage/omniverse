package com.yodesla.omniverse.player

import com.yodesla.omniverse.core.model.DeliveryMode
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class DiagnosticsTextTest {
    @Test fun showsResolutionSourceFpsDisplayAspectAndEncodedBitrate() {
        val lines = PlaybackDiagnostics(
            width = 1440,
            height = 1080,
            frameRate = 23.976f,
            pixelAspectRatio = 4f / 3f,
            videoCodec = "video/avc",
            audioCodec = "audio/eac3",
            encodedBitrate = 8_000_000,
            deliveryMode = DeliveryMode.DIRECT_PLAY,
        ).lines()

        assertContains(lines[0], "Direct play")
        assertContains(lines[1], "1440×1080")
        assertContains(lines[1], "23.98 source fps")
        assertContains(lines[2], "16:9")
        assertContains(lines[3], "video/avc")
        assertContains(lines[4], "8.0 Mb/s")
    }

    @Test fun unknownMetricsStayUnknownInsteadOfBeingInvented() {
        val lines = PlaybackDiagnostics(deliveryMode = DeliveryMode.UNKNOWN).lines()
        assertContains(lines[0], "Unknown")
        assertContains(lines[1], "Unknown")
        assertContains(lines[1], "Source FPS unknown")
        assertContains(lines[2], "Unknown")
        assertContains(lines[4], "Unknown")
        assertTrue(lines.last().contains("Buffered"))
    }

    @Test fun transcodeModeIsShownWhenReportedByTheSource() {
        assertContains(PlaybackDiagnostics(deliveryMode = DeliveryMode.TRANSCODE).lines()[0], "Transcoding")
    }

    @Test fun averageBitrateNetworkSpeedAndServerAreShownWhenKnown() {
        val lines = PlaybackDiagnostics(averageBitrate = 24_500_000, encodedBitrate = 8_000_000,
            networkBitrate = 95_000_000, sourceHost = "plex.example.com").lines()
        assertContains(lines[4], "24.5 Mb/s (whole-file average)")
        assertTrue(lines.any { it == "Download speed now  95.0 Mb/s" })
        assertTrue(lines.any { it == "Server  plex.example.com" })
        assertTrue(lines.last().contains("Buffered"))
    }

    @Test fun totalFileBytesPrefersContentRangeTotal() {
        kotlin.test.assertEquals(5_000_000_000, totalFileBytes(mapOf("content-range" to listOf("bytes 0-1023/5000000000"), "Content-Length" to listOf("1024"))))
        kotlin.test.assertEquals(2048, totalFileBytes(mapOf("Content-Length" to listOf("2048"))))
        kotlin.test.assertEquals(null, totalFileBytes(mapOf("Content-Range" to listOf("bytes 0-1023/*"))))
    }
}
