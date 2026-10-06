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
    return listOf(
        "Playback  $delivery",
        "Video  ${if (width > 0 && height > 0) "${width}×$height" else "Unknown"}  ·  ${if (frameRate > 0) "${"%.2f".format(frameRate)} source fps" else "Source FPS unknown"}",
        "Aspect ratio  $ratio",
        "Codecs  ${videoCodec ?: "Unknown"} video  ·  ${audioCodec ?: "Unknown"} audio",
        "Encoded video bitrate  ${if (encodedBitrate > 0) "${"%.1f".format(encodedBitrate / 1_000_000.0)} Mb/s" else "Unknown"}",
        "Buffered  ${bufferedMs / 1000} s",
    )
}

private fun gcd(a: Int, b: Int): Int {
    var x = a
    var y = b
    while (y != 0) { val t = x % y; x = y; y = t }
    return x.coerceAtLeast(1)
}
