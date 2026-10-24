package com.yodesla.omniverse.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.decoder.ffmpeg.FfmpegAudioDecoder
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import android.content.Context
import android.os.Handler
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Exercises the real bundled JNI decoder, independent of the device's hardware codecs. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class FfmpegAudioDeviceTest {
    @Test(timeout = 30_000) fun realDtsHdMaPlaysThroughSoftwareOnlyAndReachesTheAudioSink() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ended = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        val decoderName = AtomicReference<String?>()
        val advancing = CountDownLatch(1)
        var player: ExoPlayer? = null
        instrumentation.runOnMainSync {
            val context = instrumentation.context
            val factory = object : DefaultRenderersFactory(context) {
                override fun buildAudioRenderers(context: Context, extensionRendererMode: Int,
                    selector: MediaCodecSelector, enableDecoderFallback: Boolean, audioSink: AudioSink,
                    eventHandler: Handler, eventListener: AudioRendererEventListener, out: ArrayList<Renderer>) {
                    out.add(FfmpegAudioRenderer(eventHandler, object : AudioRendererEventListener {
                        override fun onAudioDecoderInitialized(name: String, timestamp: Long, duration: Long) {
                            decoderName.set(name)
                            eventListener.onAudioDecoderInitialized(name, timestamp, duration)
                        }
                        override fun onAudioPositionAdvancing(time: Long) {
                            advancing.countDown()
                            eventListener.onAudioPositionAdvancing(time)
                        }
                    }, audioSink))
                }
            }
            player = ExoPlayer.Builder(context, factory).build().apply {
                trackSelectionParameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
                addListener(object : Player.Listener {
                    override fun onPlayerError(failure: PlaybackException) { error.set(failure); ended.countDown() }
                    override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) ended.countDown() }
                })
                setMediaItem(MediaItem.fromUri("asset:///audio/dts-hd-ma.mkv"))
                prepare()
                play()
            }
        }
        try {
            assertTrue("Software-only DTS-HD playback did not end", ended.await(20, TimeUnit.SECONDS))
            error.get()?.let { throw AssertionError("DTS-HD software playback failed", it) }
            assertTrue("FFmpeg decoder was not used: ${decoderName.get()}", decoderName.get().orEmpty().startsWith("ffmpeg"))
            assertEquals("Audio never reached playback", 0L, advancing.count)
        } finally { instrumentation.runOnMainSync { player?.release() } }
    }

    @Test fun bundledDecoderSupportsDtsHdAndOtherCommonSurroundFormats() {
        assertTrue("Native decoder must be packaged for this device ABI", FfmpegLibrary.isAvailable())
        for (mime in listOf(MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_AC3,
            MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_TRUEHD, MimeTypes.AUDIO_AAC)) {
            assertTrue("Missing decoder for $mime", FfmpegLibrary.supportsFormat(mime))
        }
    }

    @Test(timeout = 15_000) fun dtsSurroundProducesNonSilentPcmWithoutAHardwareDecoder() {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("audio/tone.dts").use { it.readBytes() }
        for (mime in listOf(MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD)) {
            val decoder = FfmpegAudioDecoder(Format.Builder().setSampleMimeType(mime)
                .setSampleRate(48_000).setChannelCount(6).build(), 4, 4, 4096, false)
            try {
                // Fixture contains two independent 1884-byte DTS frames generated from a sine tone.
                for (frame in 0..1) {
                    val input = requireNotNull(decoder.dequeueInputBuffer())
                    input.ensureSpaceForWrite(1884)
                    input.data!!.put(bytes, frame * 1884, 1884)
                    input.timeUs = frame * 10_666L
                    input.flip()
                    decoder.queueInputBuffer(input)
                }
                var output = decoder.dequeueOutputBuffer()
                val deadline = android.os.SystemClock.elapsedRealtime() + 5_000
                while (output == null && android.os.SystemClock.elapsedRealtime() < deadline) {
                    Thread.sleep(5)
                    output = decoder.dequeueOutputBuffer()
                }
                assertNotNull("DTS decoder produced no PCM", output)
                val pcm = output!!.data!!
                assertTrue("Empty PCM", pcm.remaining() > 0)
                var audible = false
                while (pcm.hasRemaining()) if (pcm.get().toInt() != 0) audible = true
                assertTrue("PCM was entirely silent", audible)
                assertEquals(48_000, decoder.sampleRate)
                assertEquals(6, decoder.channelCount)
                output.release()
            } finally { decoder.release() }
        }
    }
}
