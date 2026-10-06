package com.yodesla.omniverse.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class LocalPcmFeatureProcessorTest {
    @Test
    fun enabledProcessorEmitsDerivedFrameAndForwardsIdenticalPcm() {
        val features = LocalAudioFeatureBuffer()
        features.setEnabled(true)
        val processor = LocalPcmFeatureProcessor(features)
        val format = AudioProcessor.AudioFormat(1_000, 1, C.ENCODING_PCM_16BIT)
        assertEquals(format, processor.configure(format))
        processor.flush(AudioProcessor.StreamMetadata.Builder().setPositionOffsetUs(0L).build())

        val input = ByteBuffer.allocateDirect(2_000).order(ByteOrder.nativeOrder())
        repeat(1_000) { input.putShort(if (it % 2 == 0) 12_000 else -12_000) }
        input.flip()
        val expected = ByteArray(input.remaining())
        input.duplicate().get(expected)

        processor.queueInput(input)

        val output = processor.output
        val actual = ByteArray(output.remaining())
        output.get(actual)
        assertContentEquals(expected, actual)
        assertEquals(1, features.snapshot().size)
        assertEquals(0L, features.snapshot().single().positionMs)
    }

    @Test
    fun disabledProcessorDoesNotRetainFrames() {
        val features = LocalAudioFeatureBuffer(maxFrames = 2)
        features.add(AudioFeatureFrame(0, -10f, 2_500f, 0.4f))
        assertEquals(emptyList(), features.snapshot())

        features.setEnabled(true)
        features.add(AudioFeatureFrame(0, -10f, 2_500f, 0.4f))
        features.add(AudioFeatureFrame(1_000, -10f, 2_500f, 0.4f))
        features.add(AudioFeatureFrame(2_000, -10f, 2_500f, 0.4f))
        assertEquals(listOf(1_000L, 2_000L), features.snapshot().map { it.positionMs })

        features.setEnabled(false)
        assertEquals(emptyList(), features.snapshot())
    }

    @Test
    fun emptyInputBeforeAnyAudioDoesNotThrow() {
        val processor = LocalPcmFeatureProcessor(LocalAudioFeatureBuffer())
        val format = AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT)
        processor.configure(format)
        processor.flush(AudioProcessor.StreamMetadata.Builder().setPositionOffsetUs(0L).build())

        processor.queueInput(AudioProcessor.EMPTY_BUFFER)

        assertEquals(0, processor.output.remaining())
    }
}
