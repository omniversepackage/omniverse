package com.yodesla.omniverse.feature.vod

import androidx.compose.runtime.Immutable
import com.yodesla.omniverse.core.data.Progress
import com.yodesla.omniverse.core.model.ContentKey

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
)

/** Alternatives probed for runtime/watch state when the chooser opens; keeps detail loads bounded. */
internal const val MAX_EDITION_ALTS = 4

/** Per-profile setting that remembers which copy this title was last played from. */
internal fun editionPickSettingKey(tmdbId: String?): String? =
    tmdbId?.trim()?.takeIf { it.isNotEmpty() }?.let { "edition_pick_$it" }

private val resolutionToken = Regex("""(?i)\b(8k|8 k|4320p?|4k|4 k|uhd|2160p?|1440p?|qhd|2k|2 k|1080p?|fhd|720p?|hd)\b""")
private val dolbyVisionToken = Regex("""(?i)dolby\s*vision|hdr10\+|\bdv\b""")
private val hdrToken = Regex("""(?i)\bhdr\b|\bhdr10\b""")

/**
 * Resolution (and HDR/Dolby Vision) read out of whatever text names a copy: the viewer's edition
 * label, the version label, or the provider's own item name. Highest tag wins; null when the
 * copy says nothing about quality.
 */
internal fun resolutionTag(vararg values: String?): String? {
    val text = values.filterNotNull().joinToString(" ").trim()
    if (text.isEmpty()) return null
    val best = resolutionToken.findAll(text)
        .map { it.value.lowercase().replace(" ", "") }
        .maxByOrNull { qualityRank(it) }
        ?.let { tag ->
            when (qualityRank(tag)) {
                5 -> "8K"
                4 -> "4K"
                3 -> "1440p"
                2 -> "1080p"
                1 -> "720p"
                else -> null
            }
        }
    val range = when {
        dolbyVisionToken.containsMatchIn(text) -> "DV"
        hdrToken.containsMatchIn(text) -> "HDR"
        else -> null
    }
    return listOfNotNull(best, range).joinToString(" ").takeIf { it.isNotEmpty() }
}

/** Rank of a parsed tag ("4K DV" → 4); 0 when the copy says nothing about quality. */
internal fun resolutionRank(resolution: String?): Int =
    resolution?.split(" ")?.maxOfOrNull { qualityRank(it) } ?: 0

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
        resolution = resolutionTag(option.label, option.name, copyNames[option.key]),
        runtimeSec = if (option.key == currentKey) currentRuntimeSec else copyRuntimeSec[option.key],
        progress = progress,
        isCurrent = option.key == currentKey,
    )
}

/**
 * List order: the copy holding an unfinished watch (newest first), then this profile's saved pick,
 * then the best resolution, then the more trustworthy source when quality ties, then name.
 * A finished watch leads nothing — it is a reason to rewatch, not a reason to default.
 */
internal fun editionCopiesRanked(copies: List<EditionCopy>, lastPickId: String?): List<EditionCopy> =
    copies.sortedWith(
        compareByDescending<EditionCopy> { it.progress != null && !isWatchedProgress(it.progress) }
            .thenByDescending { it.progress?.takeIf { p -> !isWatchedProgress(p) }?.updatedMs ?: 0L }
            .thenByDescending { if (it.id == lastPickId) 1 else 0 }
            .thenByDescending { resolutionRank(it.resolution) }
            .thenByDescending { sourceReliability(it.sourceName) }
            .thenBy { it.sourceName.lowercase() }
            .thenBy { it.editionLabel.orEmpty().lowercase() }
            .thenBy { it.id },
    )

/**
 * The copy Play will use: the one holding an unfinished watch, else this profile's saved pick,
 * else the best copy standing — highest resolution, Plex before IPTV when quality ties.
 */
internal fun chosenEditionCopy(copies: List<EditionCopy>, lastPickId: String?): EditionCopy? {
    val watched = copies.filter { it.progress != null && !isWatchedProgress(it.progress) }.maxByOrNull { it.progress!!.updatedMs }
    return watched ?: copies.firstOrNull { it.id == lastPickId } ?: editionCopiesRanked(copies, lastPickId).firstOrNull()
}

/** One copy needs no chooser at all: Play stays a single button press. */
internal fun editionChooserNeeded(copies: List<EditionCopy>): Boolean = copies.size >= 2

/** "4K  ·  Director's Cut  ·  Plex" — what the Version chip reads. */
internal fun editionChipLabel(copy: EditionCopy?): String? = copy?.let {
    listOfNotNull(it.resolution, it.editionLabel, it.sourceName).distinct().joinToString("  \u00b7  ").takeIf { text -> text.isNotEmpty() }
}

/** First line of a chooser row: what this copy is, without naming the source twice. */
internal fun editionRowTitle(copy: EditionCopy): String? =
    listOfNotNull(copy.resolution, copy.editionLabel).distinct().joinToString("  \u00b7  ").takeIf { it.isNotEmpty() }

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
internal fun defaultEditionVersionId(versions: List<com.yodesla.omniverse.core.model.MediaVersion>, lastPickId: String?): String? {
    val picked = lastPickId?.takeIf { it.startsWith("v:") }?.removePrefix("v:")?.takeIf { id -> versions.any { it.id == id } }
    return picked ?: versions.maxByOrNull { qualityRank(it.label) }?.id ?: versions.firstOrNull()?.id
}
