package com.yodesla.omniverse.player

import com.yodesla.omniverse.core.model.DeliveryMode
import kotlin.math.roundToInt

fun PlaybackDiagnostics.lines(): List<String> {
    val displayWidth = (width * pixelAspectRatio).roundToInt()
    val ratio = if (displayWidth > 0 && height > 0) {
        val divisor = gcd(displayWidth, height)
        "${displayWidth / divisor}:${height / divisor}"
    } else "Unknown"
    val delivery = when (deliveryMode) {
        DeliveryMode.DIRECT_PLAY -> "Direct play"
        DeliveryMode.TRANSCODE -> "Transcoding"
        DeliveryMode.UNKNOWN -> "Unknown"
    }
    // A whole-file average (size ÷ duration) beats the codec field, which MKV/MP4 usually leave blank.
    val bitrate = when {
        averageBitrate > 0 -> "${mbps(averageBitrate)} (whole-file average)"
        encodedBitrate > 0 -> "${mbps(encodedBitrate.toLong())} video"
        else -> "Unknown"
    }
    return listOfNotNull(
        "Playback  $delivery",
        "Video  ${if (width > 0 && height > 0) "${width}×$height" else "Unknown"}  ·  ${if (frameRate > 0) "${"%.2f".format(frameRate)} source fps" else "Source FPS unknown"}",
        "Aspect ratio  $ratio",
        "Codecs  ${videoCodec ?: "Unknown"} video  ·  ${audioCodec ?: "Unknown"} audio",
        "Bitrate  $bitrate",
        if (networkBitrate > 0) "Download speed now  ${mbps(networkBitrate)}" else null,
        sourceHost?.let { "Server  $it" },
        "Buffered  ${bufferedMs / 1000} s",
    )
}

private fun mbps(bps: Long) = "${"%.1f".format(bps / 1_000_000.0)} Mb/s"

private fun gcd(a: Int, b: Int): Int {
    var x = a
    var y = b
    while (y != 0) { val t = x % y; x = y; y = t }
    return x.coerceAtLeast(1)
}
