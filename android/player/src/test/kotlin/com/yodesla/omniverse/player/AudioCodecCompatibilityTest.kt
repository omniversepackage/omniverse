package com.yodesla.omniverse.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioCodecCompatibilityTest {
    @Test fun silentAmlogicDtsComponentsAreExcludedWithoutExcludingOtherAudioOrShield() {
        assertTrue(unreliableDtsDecoder("audio/vnd.dts.hd", "c2.amlogic.audio.decoder.dtshd"))
        assertTrue(unreliableDtsDecoder("audio/vnd.dts", "OMX.amlogic.audio.decoder.dts"))
        assertFalse(unreliableDtsDecoder("audio/ac3", "c2.amlogic.audio.decoder.ac3"))
        assertFalse(unreliableDtsDecoder("audio/vnd.dts.hd", "OMX.Nvidia.dts.decoder"))
        assertFalse(unreliableDtsDecoder("audio/vnd.dts.hd", "c2.android.dts.decoder"))
    }
}
