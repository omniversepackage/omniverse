package com.yodesla.omniverse.player

/** Amlogic's DTS component can advertise support yet produce silent PCM (Onn 4K Pro).
 * Use bundled FFmpeg for these components; other codecs and hardware remain eligible.
 */
internal fun unreliableDtsDecoder(mimeType: String, decoderName: String): Boolean {
    val dts = mimeType in setOf("audio/vnd.dts", "audio/vnd.dts.hd", "audio/vnd.dts.hd;profile=lbr")
    val name = decoderName.lowercase()
    return dts && (name.startsWith("c2.amlogic.") || name.startsWith("omx.amlogic.")) && name.contains("dts")
}
