package com.yodesla.omniverse.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt

/** Bounded, in-memory derived features only. Raw PCM is not retained after feature extraction. */
internal class LocalAudioFeatureBuffer(
    private val maxFrames: Int = 48_000,
) {
    private val enabled = AtomicBoolean(false)
    private val lock = Any()
    private val frames = ArrayDeque<AudioFeatureFrame>()
    private val _revision = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val revision: kotlinx.coroutines.flow.StateFlow<Long> = _revision

    fun setEnabled(value: Boolean) {
        enabled.set(value)
        if (!value) clear()
    }

    fun isEnabled(): Boolean = enabled.get()

    fun add(frame: AudioFeatureFrame) {
        if (!enabled.get()) return
        synchronized(lock) {
            frames.addLast(frame)
            while (frames.size > maxFrames) frames.removeFirst()
            _revision.value = _revision.value + 1
        }
    }

    fun snapshot(): List<AudioFeatureFrame> = synchronized(lock) { frames.toList() }

    fun latestPositionMs(): Long? = synchronized(lock) { frames.peekLast()?.positionMs }

    fun clear() = synchronized(lock) {
        frames.clear()
        _revision.value = _revision.value + 1
    }
}

/**
 * Identity PCM processor with an opt-in local feature tap. It uses a one-second mono scratch
 * buffer only while extracting features, clears that buffer after each frame, and copies every
 * input byte unchanged to the sink. Media3 does not run processors for encoded passthrough/offload.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class LocalPcmFeatureProcessor(
    private val featureBuffer: LocalAudioFeatureBuffer,
) : BaseAudioProcessor() {
    private var streamPositionUs = C.TIME_UNSET
    private var processedFrames = 0L
    private var blockStartFrame = 0L
    private var blockSampleCount = 0
    private var samples = FloatArray(0)

    @Synchronized
    fun clearScratch() {
        samples.fill(0f)
        blockSampleCount = 0
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.sampleRate <= 0 || inputAudioFormat.channelCount <= 0 ||
            inputAudioFormat.encoding !in PCM_ENCODINGS
        ) return AudioProcessor.AudioFormat.NOT_SET
        samples = FloatArray(0)
        return inputAudioFormat
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        streamPositionUs = streamMetadata.positionOffsetUs
        processedFrames = 0L
        blockStartFrame = 0L
        blockSampleCount = 0
        samples.fill(0f)
        featureBuffer.clear()
    }

    @Synchronized
    override fun queueInput(inputBuffer: ByteBuffer) {
        // An empty input can be the shared EMPTY_BUFFER, which is also this processor's initial
        // output buffer: replaceOutputBuffer(0) would return it and put(self) throws, killing
        // playback (Bleach S1E1 on Plex and IPTV). Media3's own processors skip empty input too.
        if (!inputBuffer.hasRemaining()) return
        val input = inputBuffer.duplicate().order(inputBuffer.order())
        val format = inputAudioFormat
        val bytesPerSample = encodingBytes(format.encoding)
        val channels = format.channelCount
        val bytesPerFrame = bytesPerSample * channels
        val completeFrames = input.remaining() / bytesPerFrame
        val mayAnalyze = featureBuffer.isEnabled() && streamPositionUs != C.TIME_UNSET &&
            format.sampleRate <= MAX_SAMPLE_RATE

        if (mayAnalyze) {
            repeat(completeFrames) {
                var mono = 0f
                repeat(channels) { mono += readSample(input, format.encoding) }
                mono /= channels
                appendSample(mono, format.sampleRate)
                processedFrames++
            }
        } else {
            // Do not bridge a period where analysis was off (or timestamps were unavailable).
            samples.fill(0f)
            blockSampleCount = 0
            processedFrames += completeFrames
        }

        val output = replaceOutputBuffer(inputBuffer.remaining())
        output.put(inputBuffer)
        output.flip()
    }

    private fun appendSample(sample: Float, sampleRate: Int) {
        if (samples.size != sampleRate) samples = FloatArray(sampleRate)
        if (blockSampleCount == 0) blockStartFrame = processedFrames
        samples[blockSampleCount++] = sample
        if (blockSampleCount == sampleRate) {
            val startMs = streamPositionUs / 1_000L + blockStartFrame * 1_000L / sampleRate
            featureBuffer.add(extractFeature(samples, blockSampleCount, sampleRate, startMs))
            blockSampleCount = 0
            samples.fill(0f)
        }
    }

    private fun readSample(buffer: ByteBuffer, encoding: Int): Float = when (encoding) {
        C.ENCODING_PCM_8BIT -> ((buffer.get().toInt() and 0xff) - 128) / 128f
        C.ENCODING_PCM_16BIT -> buffer.short / 32768f
        C.ENCODING_PCM_24BIT -> {
            val b0 = buffer.get().toInt() and 0xff
            val b1 = buffer.get().toInt() and 0xff
            val b2 = buffer.get().toInt()
            val value = (b2 shl 16) or (b1 shl 8) or b0
            (if (value and 0x800000 != 0) value or -0x1000000 else value) / 8_388_608f
        }
        C.ENCODING_PCM_32BIT -> buffer.int / 2_147_483_648f
        C.ENCODING_PCM_FLOAT -> buffer.float.coerceIn(-1f, 1f)
        else -> 0f
    }

    override fun onReset() {
        samples.fill(0f)
        samples = FloatArray(0)
        streamPositionUs = C.TIME_UNSET
        processedFrames = 0L
        blockStartFrame = 0L
        blockSampleCount = 0
        featureBuffer.clear()
    }

    private companion object {
        const val MAX_SAMPLE_RATE = 96_000
        val PCM_ENCODINGS = setOf(
            C.ENCODING_PCM_8BIT,
            C.ENCODING_PCM_16BIT,
            C.ENCODING_PCM_24BIT,
            C.ENCODING_PCM_32BIT,
            C.ENCODING_PCM_FLOAT,
        )
        const val SPECTRUM_SAMPLES = 8_192
        const val HF_CUTOFF_HZ = 2_000.0

        fun encodingBytes(encoding: Int): Int = when (encoding) {
            C.ENCODING_PCM_8BIT -> 1
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
            else -> 1
        }

        fun extractFeature(input: FloatArray, count: Int, sampleRate: Int, positionMs: Long): AudioFeatureFrame {
            var squareSum = 0.0
            for (i in 0 until count) squareSum += input[i] * input[i]
            val rms = sqrt(squareSum / count).toFloat()
            val rmsDb = if (rms <= 1e-6f) -120f else (20.0 * ln(rms.toDouble()) / ln(10.0)).toFloat()

            val n = SPECTRUM_SAMPLES
            val real = DoubleArray(n)
            val imaginary = DoubleArray(n)
            for (i in 0 until n) {
                val source = ((i.toLong() * count) / n).toInt().coerceAtMost(count - 1)
                val hann = 0.5 - 0.5 * cos(2.0 * PI * i / (n - 1))
                real[i] = input[source] * hann
            }
            fft(real, imaginary)

            var magnitudeSum = 0.0
            var weightedHz = 0.0
            var totalPower = 0.0
            var highPower = 0.0
            val effectiveRate = n.toDouble()
            for (bin in 1 until n / 2) {
                val power = real[bin] * real[bin] + imaginary[bin] * imaginary[bin]
                val magnitude = sqrt(power)
                val hz = bin * effectiveRate / n
                magnitudeSum += magnitude
                weightedHz += hz * magnitude
                totalPower += power
                if (hz >= HF_CUTOFF_HZ) highPower += power
            }
            val centroid = if (magnitudeSum <= 1e-12) 0f else (weightedHz / magnitudeSum).toFloat()
            val hfRatio = if (totalPower <= 1e-18) 0f else (highPower / totalPower).toFloat()
            return AudioFeatureFrame(positionMs, rmsDb, centroid, hfRatio)
        }

        private fun fft(real: DoubleArray, imaginary: DoubleArray) {
            val n = real.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
                j = j xor bit
                if (i < j) {
                    val r = real[i]; real[i] = real[j]; real[j] = r
                    val im = imaginary[i]; imaginary[i] = imaginary[j]; imaginary[j] = im
                }
            }
            var length = 2
            while (length <= n) {
                val angle = -2.0 * PI / length
                val wLenR = cos(angle)
                val wLenI = kotlin.math.sin(angle)
                var start = 0
                while (start < n) {
                    var wR = 1.0
                    var wI = 0.0
                    for (offset in 0 until length / 2) {
                        val even = start + offset
                        val odd = even + length / 2
                        val oddR = real[odd] * wR - imaginary[odd] * wI
                        val oddI = real[odd] * wI + imaginary[odd] * wR
                        real[odd] = real[even] - oddR
                        imaginary[odd] = imaginary[even] - oddI
                        real[even] += oddR
                        imaginary[even] += oddI
                        val nextWR = wR * wLenR - wI * wLenI
                        wI = wR * wLenI + wI * wLenR
                        wR = nextWR
                    }
                    start += length
                }
                length = length shl 1
            }
        }
    }
}
