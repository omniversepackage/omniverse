package com.yodesla.omniverse.player

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory

/** Keeps Media3's format ordering and configuration; extends only Matroska extraction. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class OmniverseExtractorsFactory : ExtractorsFactory {
    private val delegate = DefaultExtractorsFactory().setTsExtractorFlags(
        DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
            DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS,
    )
    private var subtitleParserFactory: SubtitleParser.Factory = DefaultSubtitleParserFactory()
    private var transcodeSubtitles = true
    private var parseHagc = true

    override fun setSubtitleParserFactory(factory: SubtitleParser.Factory): ExtractorsFactory = apply {
        subtitleParserFactory = factory
        delegate.setSubtitleParserFactory(factory)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun experimentalSetTextTrackTranscodingEnabled(enabled: Boolean): ExtractorsFactory = apply {
        transcodeSubtitles = enabled
        delegate.experimentalSetTextTrackTranscodingEnabled(enabled)
    }

    override fun experimentalSetCodecsToParseWithinGopSampleDependencies(codecs: Int): ExtractorsFactory = apply {
        delegate.experimentalSetCodecsToParseWithinGopSampleDependencies(codecs)
    }

    override fun setParseHagcMetadata(enabled: Boolean): ExtractorsFactory = apply {
        parseHagc = enabled
        delegate.setParseHagcMetadata(enabled)
    }

    override fun createExtractors(): Array<Extractor> = createExtractors(Uri.EMPTY, emptyMap())

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        delegate.createExtractors(uri, responseHeaders).map { extractor ->
            if (extractor is MatroskaExtractor) {
                extractor.release()
                ZlibMatroskaExtractor(
                    subtitleParserFactory,
                    (if (transcodeSubtitles) 0 else MatroskaExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA) or
                        (if (parseHagc) 0 else MatroskaExtractor.FLAG_DISABLE_HAGC_METADATA),
                )
            } else extractor
        }.toTypedArray()
}
