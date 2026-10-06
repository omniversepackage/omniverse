package com.yodesla.omniverse.feature.vod

import java.util.Locale

private val CodecTokens = listOf("EAC3", "AC3", "DTS-HD", "DTS", "TRUEHD", "AAC", "MP3", "OPUS", "FLAC", "VORBIS")
private val QualifierWords = setOf("SDH", "CC", "HI", "AD", "DTS", "AAC", "UND")
private val LanguageAliases = mapOf(
    "fre" to "fr",
    "fra" to "fr",
    "eng" to "en",
    "ger" to "de",
    "deu" to "de",
    "dut" to "nl",
    "nld" to "nl",
    "spa" to "es",
    "ita" to "it",
    "por" to "pt",
    "rus" to "ru",
    "jpn" to "ja",
    "chi" to "zh",
    "zho" to "zh",
    "kor" to "ko",
    "ara" to "ar",
    "tur" to "tr",
    "pol" to "pl",
    "swe" to "sv",
    "nor" to "no",
    "dan" to "da",
    "fin" to "fi",
    "ell" to "el",
    "gre" to "el",
    "heb" to "he",
    "ces" to "cs",
    "cze" to "cs",
    "slk" to "sk",
    "slo" to "sk",
    "slv" to "sl",
    "hrv" to "hr",
    "bul" to "bg",
    "ron" to "ro",
    "rum" to "ro",
    "hun" to "hu",
    "cat" to "ca",
    "glg" to "gl",
    "eus" to "eu",
    "ben" to "bn",
    "hin" to "hi",
    "tam" to "ta",
    "tel" to "te",
    "mar" to "mr",
    "urd" to "ur",
    "fas" to "fa",
    "per" to "fa",
    "msa" to "ms",
    "may" to "ms",
    "ind" to "id",
    "tha" to "th",
    "vie" to "vi",
    "ukr" to "uk",
    "srp" to "sr",
    "tgl" to "tl",
    "tlg" to "tl",
)

internal fun trackLabel(language: String?, rawLabel: String?, forcedOnly: Boolean, audio: Boolean): String {
    val raw = rawLabel.orEmpty().trim()
    val parts = linkedSetOf<String>()
    languageName(language, raw)?.takeIf { it.isNotBlank() }?.let { parts += it }
    if (forcedOnly || containsWord(raw, "Forced")) parts += "Forced"
    hearingQualifier(raw)?.let { parts += it }
    if (audio) {
        channelQualifier(raw)?.let { parts += it }
        codecQualifier(raw)?.let { parts += it }
    }
    return parts.joinToString(" · ").ifBlank { "Unknown" }
}

internal fun dedupeLabels(labels: List<String>): List<String> {
    val seen = HashMap<String, Int>()
    return labels.map { label ->
        val count = (seen[label] ?: 0) + 1
        seen[label] = count
        if (count == 1) label else "$label ($count)"
    }
}

private fun languageName(language: String?, raw: String): String? {
    val code = language?.trim()?.takeIf { it.isNotBlank() && !it.equals("und", ignoreCase = true) }
        ?: rawLanguageCode(raw)
    if (code != null) return displayLanguage(code)
    return fallbackLanguage(raw)
}

private fun rawLanguageCode(raw: String): String? {
    val cleaned = raw.replace(Regex("[(),·/|]+"), " ").trim()
    if (!Regex("^[A-Za-z]{2,3}$").matches(cleaned)) return null
    if (cleaned.uppercase(Locale.ROOT) in QualifierWords) return null
    return cleaned
}

private fun fallbackLanguage(raw: String): String? {
    if (raw.isBlank()) return null
    var cleaned = raw
    cleaned = Regex("(?i)\\b(SDH|CC|HI|AD|Forced|Closed Captions?|Subtitles? for Deaf|Deaf or Hard of Hearing)\\b").replace(cleaned, " ")
    cleaned = Regex("(?i)\\b(EAC3|AC3|DTS-HD|DTS|TrueHD|AAC|MP3|OPUS|FLAC|VORBIS)\\b").replace(cleaned, " ")
    cleaned = Regex("\\b\\d\\.\\d\\b").replace(cleaned, " ")
    cleaned = Regex("(?i)\\b(Stereo|Mono)\\b").replace(cleaned, " ")
    cleaned = cleaned.replace(Regex("[(),·/|]+"), " ").replace(Regex("\\s+"), " ").trim()
    if (cleaned.isBlank() || cleaned.equals("und", ignoreCase = true) || cleaned.startsWith("Track ", ignoreCase = true)) return null
    if (Regex("^[A-Za-z]{2,3}$").matches(cleaned)) return displayLanguage(cleaned)
    return cleaned
}

private fun displayLanguage(code: String): String {
    val normalized = LanguageAliases[code.lowercase(Locale.ROOT)] ?: code
    val display = Locale.forLanguageTag(normalized).getDisplayLanguage(Locale.ENGLISH).takeIf { it.isNotBlank() }
        ?: Locale(normalized).getDisplayLanguage(Locale.ENGLISH).takeIf { it.isNotBlank() }
    return display ?: normalized.uppercase(Locale.ROOT)
}

private fun hearingQualifier(raw: String): String? {
    val upper = raw.uppercase(Locale.ROOT)
    if (upper.contains("SDH") || upper.contains("HARD OF HEARING") || upper.contains("DEAF")) return "SDH"
    if (containsWord(upper, "CC") || upper.contains("CLOSED CAPTION")) return "CC"
    return null
}

private fun channelQualifier(raw: String): String? {
    Regex("\\b\\d\\.\\d\\b").find(raw)?.let { return it.value }
    val upper = raw.uppercase(Locale.ROOT)
    if (upper.contains("STEREO")) return "Stereo"
    if (upper.contains("MONO")) return "Mono"
    return null
}

private fun codecQualifier(raw: String): String? {
    val upper = raw.uppercase(Locale.ROOT)
    return CodecTokens.firstOrNull { upper.contains(it) }
}

private fun containsWord(text: String, word: String): Boolean =
    Regex("\\b${Regex.escape(word)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)
