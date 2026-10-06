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
}
