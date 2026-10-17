package com.yodesla.omniverse.player

import android.content.Context
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

/**
 * Dolby Vision profile 7 (UHD Blu-ray dual layer) is rejected by Android DV decoders such as the
 * Shield's, so Media3 falls back to the HDR10 base layer and the TV never switches to DV. Relabelling
 * the stream as profile 8 lets the DV decoder take the base layer + RPU (the enhancement layer is
 * ignored), which is what Plex and Kodi do.
 */
internal fun Format.remapDolbyVisionProfile7(): Format {
    val codecs = codecs ?: return this
    if (sampleMimeType != MimeTypes.VIDEO_DOLBY_VISION) return this
    val match = Regex("""^(dvhe|dvh1)\.07(\..*)?$""").find(codecs) ?: return this
    val (prefix, rest) = match.destructured
    return buildUpon().setCodecs("$prefix.08$rest").build()
}

@OptIn(UnstableApi::class)
internal class DolbyVisionProfile7Renderer(
    context: Context,
    selector: MediaCodecSelector,
    allowedJoiningTimeMs: Long,
    enableDecoderFallback: Boolean,
    eventHandler: Handler?,
    eventListener: VideoRendererEventListener?,
    maxDroppedFramesToNotify: Int,
) : MediaCodecVideoRenderer(
    context,
    selector,
    allowedJoiningTimeMs,
    enableDecoderFallback,
    eventHandler,
    eventListener,
    maxDroppedFramesToNotify,
) {
    override fun supportsFormat(mediaCodecSelector: MediaCodecSelector, format: Format): Int =
        super.supportsFormat(mediaCodecSelector, format.remapDolbyVisionProfile7())

    override fun getDecoderInfos(
        mediaCodecSelector: MediaCodecSelector,
        format: Format,
        requiresSecureDecoder: Boolean,
    ): List<MediaCodecInfo> =
        super.getDecoderInfos(mediaCodecSelector, format.remapDolbyVisionProfile7(), requiresSecureDecoder)

    override fun onInputFormatChanged(formatHolder: FormatHolder) = run {
        formatHolder.format = formatHolder.format?.remapDolbyVisionProfile7()
        super.onInputFormatChanged(formatHolder)
    }
}
