package com.yodesla.omniverse.feature.vod

import androidx.compose.runtime.Immutable
import com.yodesla.omniverse.core.data.Progress
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.DynamicRange
import com.yodesla.omniverse.core.model.VideoInfo

/**
 * One visible copy of a merged title: another source's item sharing the exact TMDB id, or one
 * playable file inside the item being viewed. Built only from copies the caller has already
 * passed parental/library visibility, so nothing here can bring back a hidden one.
 */
@Immutable
data class EditionCopy(
    val id: String,
    val key: ContentKey,
    /** Set when this copy is a file inside the item being viewed. */
    val versionId: String?,
    val sourceName: String,
    /** The viewer's saved edition label; null when nobody labelled this copy. */
    val editionLabel: String?,
    /** Resolution/HDR tag read out of the copy's own label or provider name ("4K", "1080p DV"). */
    val resolution: String?,
    val runtimeSec: Int?,
    /** This copy's saved watch; null when it holds none. */
    val progress: Progress?,
    val isCurrent: Boolean,
    val disambiguator: String? = null,
)

/** Alternatives probed for runtime, picture and watch state when the page opens; keeps detail loads bounded. */
internal const val MAX_EDITION_ALTS = 8

/** Per-profile setting that remembers which copy this title was last played from. */
internal fun editionPickSettingKey(tmdbId: String?): String? =
    tmdbId?.trim()?.takeIf { it.isNotEmpty() }?.let { "edition_pick_$it" }

/** Persistent choices identify the source/item, independent of which detail page is open. */
internal fun editionItemPickId(key: ContentKey): String = "k:${key.sourceId.value}:${key.remoteId.value}"

internal fun editionVersionPickId(key: ContentKey, versionId: String): String =
    "${editionItemPickId(key)}:v:$versionId"

/** Per-profile label for one file inside an item; item-level labels use ItemOverride instead. */
internal fun editionVersionLabelSettingKey(key: ContentKey, versionId: String): String =
    "edition_label:${editionVersionPickId(key, versionId)}"

/** A source-chip navigation is authoritative even when the provider omits its catalog ID. */
internal fun detailSourcePick(key: ContentKey, savedPick: String?, explicitlySelected: Boolean): String? {
    if (!explicitlySelected) return savedPick
    val item = editionItemPickId(key)
    return savedPick?.takeIf { it == item || it.startsWith("$item:v:") } ?: item
}

internal fun EditionCopy.savedPickId(): String =
    versionId?.let { editionVersionPickId(key, it) } ?: editionItemPickId(key)

private fun EditionCopy.matchesPick(pick: String?): Boolean = pick != null &&
    (pick == savedPickId() || pick == editionItemPickId(key) || pick == id ||
        (versionId == null && pick.startsWith("${editionItemPickId(key)}:v:")))

internal fun EditionCopy.pickForNavigation(previousPick: String?): String =
    previousPick?.takeIf { matchesPick(it) } ?: savedPickId()

/**
 * What a copy is: its resolution ([rank] on the [qualityRank] scale, 0 when unknown) and dynamic
 * range. [resolution] is the display word ("4K", "1080p", "HD"); null when nothing said.
 */
@Immutable
internal data class CopyQuality(val resolution: String? = null, val rank: Int = 0, val range: DynamicRange? = null) {
    /** "4K Dolby Vision", "1080p HDR", "4K"; null when nothing is known. */
    val label: String? get() = listOfNotNull(resolution, rangeLabel(range)).joinToString(" ").ifEmpty { null }
    /** Orders copies of one resolution: Dolby Vision, then HDR10+, then HDR, then the rest. */
    val rangeRank: Int get() = when (range) {
        DynamicRange.DOLBY_VISION -> 3
        DynamicRange.HDR10_PLUS -> 2
        DynamicRange.HDR -> 1
        DynamicRange.SDR, null -> 0
    }
}

private fun rangeLabel(range: DynamicRange?): String? = when (range) {
    DynamicRange.DOLBY_VISION -> "DV"
    DynamicRange.HDR10_PLUS -> "HDR10+"
    DynamicRange.HDR -> "HDR"
    DynamicRange.SDR, null -> null
}

private val resolutionWords = mapOf(
    "8k" to ("8K" to 5), "4320p" to ("8K" to 5), "4320" to ("8K" to 5),
    "4k" to ("4K" to 4), "uhd" to ("4K" to 4), "2160p" to ("4K" to 4), "2160" to ("4K" to 4),
    "1440p" to ("1440p" to 3), "1440" to ("1440p" to 3), "qhd" to ("1440p" to 3), "2k" to ("1440p" to 3),
    "1080p" to ("1080p" to 2), "1080" to ("1080p" to 2), "fhd" to ("1080p" to 2),
    "720p" to ("720p" to 1), "720" to ("720p" to 1), "hd" to ("HD" to 1),
)
private val dolbyVisionWords = setOf("dv", "dovi", "dolbyvision")
private val hdr10PlusWords = setOf("hdr10+", "hdr10plus")
private val hdrWords = setOf("hdr", "hdr10", "hlg")
/** Words glued alongside quality tags ("2160pDVHEVC") that say nothing a viewer needs. */
private val fillerWords = setOf("sdr", "hevc", "x265", "x264", "h265", "h264", "10bit", "remux", "atmos", "imax")
private val containerWords = setOf("mkv", "mp4", "m4v", "avi", "ts", "m2ts", "mov", "webm", "wmv")
private val qualityVocabulary = (resolutionWords.keys + dolbyVisionWords + hdr10PlusWords + hdrWords + fillerWords)
    .sortedByDescending { it.length }
private val tagRun = Regex("""[A-Za-z0-9+]+""")
private val dolbyVisionPhrase = Regex("""(?i)dolby\s+vision""")

/**
 * A run of letters/digits split wholly into quality words, so tags glued together still read
 * ("4kDV" → 4k + dv, "2160pHDR10" → 2160p + hdr10); null when any part is not one, which keeps
 * ordinary words out ("4kids", "dvd", "hdrip").
 */
private fun qualityWordsIn(run: String): List<String>? {
    if (run.isEmpty()) return emptyList()
    if (run.length > 40) return null
    for (word in qualityVocabulary) {
        if (run.startsWith(word)) qualityWordsIn(run.substring(word.length))?.let { return listOf(word) + it }
    }
    return null
}

/**
 * Resolution and HDR/Dolby Vision read out of whatever text names a copy: the viewer's edition
 * label, the version label, or the provider's own item name. Highest resolution wins.
 */
internal fun textQuality(vararg values: String?): CopyQuality {
    val text = values.filterNotNull().joinToString(" ").replace(Regex("""(?i)\b([248])\s+k\b"""), "$1k")
    val words = tagRun.findAll(text).flatMap { qualityWordsIn(it.value.lowercase()).orEmpty() }.toList()
    val best = words.mapNotNull { resolutionWords[it] }.maxByOrNull { it.second }
    val range = when {
        dolbyVisionPhrase.containsMatchIn(text) || words.any { it in dolbyVisionWords } -> DynamicRange.DOLBY_VISION
        words.any { it in hdr10PlusWords } -> DynamicRange.HDR10_PLUS
        words.any { it in hdrWords } -> DynamicRange.HDR
        else -> null
    }
    return CopyQuality(best?.first, best?.second ?: 0, range)
}

/**
 * What the source's own probe of the file says. Width decides first, so a 3840×1600 scope film
 * is still 4K and a 1920×800 one 1080p.
 */
internal fun probeQuality(video: VideoInfo?): CopyQuality {
    if (video == null) return CopyQuality()
    val w = video.width ?: 0
    val h = video.height ?: 0
    val (resolution, rank) = when {
        w == 0 && h == 0 -> null to 0
        w >= 6000 || h >= 3500 -> "8K" to 5
        w >= 3000 || h >= 1900 -> "4K" to 4
        w >= 2200 || h >= 1300 -> "1440p" to 3
        w >= 1700 || h >= 900 -> "1080p" to 2
        w >= 1100 || h >= 600 -> "720p" to 1
        else -> "SD" to 0
    }
    return CopyQuality(resolution, rank, video.range)
}

/**
 * The probe's resolution wins over the copy's label or name. For range, a named Dolby Vision or
 * HDR10+ still counts over a probed HDR: many panels' ffprobe cannot see either and reports only
 * the HDR10 base underneath.
 */
internal fun copyQuality(video: VideoInfo?, vararg text: String?): CopyQuality {
    val probe = probeQuality(video)
    val named = textQuality(*text)
    val range = when {
        probe.range == DynamicRange.DOLBY_VISION || named.range == DynamicRange.DOLBY_VISION -> DynamicRange.DOLBY_VISION
        named.range == DynamicRange.HDR10_PLUS && probe.range != DynamicRange.SDR -> DynamicRange.HDR10_PLUS
        else -> probe.range ?: named.range
    }
    return (if (probe.resolution != null) probe else named).copy(range = range)
}

/** "4K Dolby Vision" read from a copy's label or name; null when it says nothing about quality. */
internal fun resolutionTag(vararg values: String?): String? = textQuality(*values).label

/** Rank of a parsed tag ("4K Dolby Vision" → 4); 0 when the copy says nothing about quality. */
internal fun resolutionRank(resolution: String?): Int = textQuality(resolution).rank

/**
 * A label with its quality words taken out, since the quality is shown on its own: "Extended 4K"
 * → "Extended", "4K · MKV" → null (the file type is noise beside the quality) or "MKV" when
 * [keepFileType]. Null when nothing is left.
 */
internal fun editionWords(label: String?, keepFileType: Boolean = false): String? {
    val parts = label?.replace(dolbyVisionPhrase, " ")?.split(Regex("""\s*(?:[·|/,]|\s[-–—]\s)\s*""")) ?: return null
    return parts.map { part ->
        tagRun.replace(part) { m ->
            val word = m.value.lowercase()
            if (qualityWordsIn(word) != null || (!keepFileType && word in containerWords)) "" else m.value
        }.replace(Regex("""[(\[{]\s*[)\]}]"""), " ").replace(Regex("""\s+"""), " ").trim(' ', '-', '–', '—', '_', '.', ':', ';')
    }.filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { null }
}

/** Plex is the more trustworthy copy when quality ties: it is the viewer's own library. */
internal fun sourceReliability(sourceName: String?): Int =
    if (sourceName.orEmpty().contains("plex", ignoreCase = true)) 2 else 1

/**
 * Every visible copy, described. [options] is already the parentally filtered play-option list, so
 * a switched-off library, a locked category or a kids profile simply has no entry here.
 */
internal fun buildEditionCopies(
    options: List<PlayOption>,
    currentKey: ContentKey,
    currentRuntimeSec: Int?,
    currentProgress: Progress?,
    versionProgress: Map<String, Progress>,
    copyProgress: Map<ContentKey, Progress>,
    copyRuntimeSec: Map<ContentKey, Int>,
    copyNames: Map<ContentKey, String>,
): List<EditionCopy> = options.map { option ->
    val progress = option.versionId?.let(versionProgress::get)
        ?: if (option.key == currentKey) currentProgress else copyProgress[option.key]
    EditionCopy(
        id = option.id,
        key = option.key,
        versionId = option.versionId,
        sourceName = option.sourceName,
        editionLabel = option.label,
        resolution = option.quality ?: resolutionTag(option.label, option.name, copyNames[option.key]),
        runtimeSec = if (option.key == currentKey) option.runtimeSec ?: currentRuntimeSec else option.runtimeSec ?: copyRuntimeSec[option.key],
        progress = progress,
        isCurrent = option.key == currentKey,
        disambiguator = option.disambiguator,
    )
}

/**
 * List order: this profile's explicit pick, then an unfinished watch (newest first),
 * then the best resolution, then the more trustworthy source when quality ties, then name.
 * A finished watch leads nothing — it is a reason to rewatch, not a reason to default.
 */
internal fun editionCopiesRanked(copies: List<EditionCopy>, lastPickId: String?): List<EditionCopy> =
    copies.sortedWith(
        compareByDescending<EditionCopy> { it.matchesPick(lastPickId) }
            .thenByDescending { it.progress != null && !isWatchedProgress(it.progress) }
            .thenByDescending { it.progress?.takeIf { p -> !isWatchedProgress(p) }?.updatedMs ?: 0L }
            .thenByDescending { resolutionRank(it.resolution) }
            .thenByDescending { sourceReliability(it.sourceName) }
            .thenBy { it.sourceName.lowercase() }
            .thenBy { it.editionLabel.orEmpty().lowercase() }
            .thenBy { it.id },
    )

/**
 * The copy Play will use: this profile's explicit pick, else an unfinished watch,
 * else the best copy standing — highest resolution, Plex before IPTV when quality ties.
 */
internal fun chosenEditionCopy(copies: List<EditionCopy>, lastPickId: String?): EditionCopy? {
    return editionCopiesRanked(copies, lastPickId).firstOrNull()
}

/** One copy needs no chooser at all: Play stays a single button press. */
internal fun editionChooserNeeded(copies: List<EditionCopy>): Boolean = copies.size >= 2

/** "4K Dolby Vision  ·  Director's Cut  ·  Plex" — what the Version chip reads. */
internal fun editionChipLabel(copy: EditionCopy?): String? = copy?.let {
    listOfNotNull(it.resolution, editionWords(it.editionLabel), it.disambiguator, it.sourceName).distinct().joinToString("  \u00b7  ").takeIf { text -> text.isNotEmpty() }
}

/** First line of a chooser row: what this copy is ("4K Dolby Vision  ·  Extended"), without naming the source twice. */
internal fun editionRowTitle(copy: EditionCopy): String? =
    listOfNotNull(copy.resolution, editionWords(copy.editionLabel, keepFileType = true), copy.disambiguator).distinct().joinToString("  \u00b7  ").takeIf { it.isNotEmpty() }

/** Second line of a chooser row: source, runtime when known, resume point when this copy holds one. */
internal fun editionRowMeta(copy: EditionCopy, resumeLabel: String?, currentTag: String?): String =
    listOfNotNull(copy.sourceName, fmtRuntime(copy.runtimeSec), resumeLabel, currentTag).joinToString("  \u00b7  ")

/** Whole minutes already watched on this copy; null when it holds no unfinished watch. */
internal fun editionResumeMinutes(copy: EditionCopy): Long? {
    val p = copy.progress ?: return null
    if (isWatchedProgress(p) || p.positionMs < 30_000L) return null
    return (p.positionMs / 60_000L).coerceAtLeast(1L)
}

/** The file Play should open inside the item being viewed when nothing watched or saved says otherwise. */
internal fun pickedEditionVersionId(versions: List<com.yodesla.omniverse.core.model.MediaVersion>, lastPickId: String?, key: ContentKey? = null): String? {
    val picked = lastPickId?.takeIf { it.startsWith("v:") }?.removePrefix("v:")?.takeIf { id -> versions.any { it.id == id } }
    return versions.firstOrNull { key != null && editionVersionPickId(key, it.id) == lastPickId }?.id ?: picked
}

internal fun defaultEditionVersionId(versions: List<com.yodesla.omniverse.core.model.MediaVersion>, lastPickId: String?, key: ContentKey? = null): String? =
    pickedEditionVersionId(versions, lastPickId, key) ?: versions.maxByOrNull { qualityRank(it.label) }?.id ?: versions.firstOrNull()?.id
