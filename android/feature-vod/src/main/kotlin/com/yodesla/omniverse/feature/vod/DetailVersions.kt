package com.yodesla.omniverse.feature.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.MediaVersion
import com.yodesla.omniverse.core.model.VideoInfo
import com.yodesla.omniverse.core.model.knownEditionLabel
import com.yodesla.omniverse.core.model.explicitEditionLabel
import com.yodesla.omniverse.core.model.relativeEditionLabel
import com.yodesla.omniverse.designsystem.FocusCard
import com.yodesla.omniverse.designsystem.OmniSpacing
import com.yodesla.omniverse.designsystem.OmniTheme

/** One playable alternative offered as a chip under the detail title. */
@Immutable
data class PlayOption(
    val id: String,
    /** Edition or quality label ("4K", "Extended"); null means the source alone names it. */
    val label: String?,
    val sourceName: String,
    /** Better quality plays first; 0 when the label says nothing about quality. */
    val qualityRank: Int,
    /** Set when Play stays on this item and picks a file inside it. */
    val versionId: String?,
    /** The item Play opens. */
    val key: ContentKey,
    /** Provider's own name for this copy (or the file's label); source of quality tags when the
     *  viewer never labelled it. Never shown raw — only parsed for a resolution tag. */
    val name: String? = null,
    /** What this copy is ("4K Dolby Vision"): the source's probe of the file, else its label or name. */
    val quality: String? = null,
    /** Dolby Vision 3, HDR10+ 2, HDR 1: orders copies of one resolution best first. */
    val rangeRank: Int = 0,
    val runtimeSec: Int? = null,
    /** Added only when two options would otherwise have the same visible name. */
    val disambiguator: String? = null,
) {
    /** The quality a chip leads with; read from the label when the copy was built without one. */
    val shownQuality: String? get() = quality ?: resolutionTag(label)
    /** "4K Dolby Vision  ·  gem-ruby.me": quality, then any edition words left in the label, then the source. */
    val chipLabel: String get() = listOfNotNull(shownQuality, editionWords(label), disambiguator, sourceName).distinct().joinToString("  ·  ")
}

/** Chips stop here; anything past it goes behind the "More…" chip and the existing dialog. */
internal const val MAX_DETAIL_CHIPS = 4

/**
 * Every way this title can be played: the files inside the item being viewed (when the source
 * exposes versions), the item itself (when it does not), and each parentally visible exact-ID
 * alternative. Each copy says what it is from the source's own probe of the file ([currentVideo],
 * [relatedVideo], each version's own) where there is one, else from its label or provider name.
 * Ordered best quality first (4K Dolby Vision, 4K HDR, 4K, 1080p…), then by source name, so the
 * chip a viewer would press is the left one.
 */
internal fun buildPlayOptions(
    currentKey: ContentKey,
    currentSourceName: String,
    currentEdition: String?,
    versions: List<MediaVersion>,
    related: List<RelatedMovie>,
    currentName: String? = null,
    currentVideo: VideoInfo? = null,
    relatedVideo: Map<ContentKey, VideoInfo> = emptyMap(),
    /** The category each copy is filed under; read last, after the label and name. */
    categoryNames: Map<ContentKey, String> = emptyMap(),
    currentRuntimeSec: Int? = null,
    relatedRuntimeSec: Map<ContentKey, Int> = emptyMap(),
    tmdbId: String? = null,
    versionLabels: Map<String, String> = emptyMap(),
    referenceRuntimeSec: Int? = null,
    year: Int? = null,
): List<PlayOption> {
    fun option(id: String, label: String?, source: String, versionId: String?, key: ContentKey, name: String?, video: VideoInfo?, runtimeSec: Int?): PlayOption {
        val quality = copyQuality(video, label, name, categoryNames[key])
        val placeholder = label?.matches(Regex("Version [0-9]+", RegexOption.IGNORE_CASE)) == true
        val cut = (explicitEditionLabel(name) ?: knownEditionLabel(tmdbId, currentName, year, runtimeSec)
            ?: relativeEditionLabel(runtimeSec, referenceRuntimeSec))
            ?.takeIf { editionWords(label) == null || placeholder }
        val shownLabel = listOfNotNull(cleanLabel(label).takeUnless { placeholder && cut != null }, cut)
            .joinToString(" · ").ifBlank { null }
        return PlayOption(id, shownLabel, source, quality.rank, versionId, key, name, quality.label, quality.rangeRank, runtimeSec)
    }
    val options = mutableListOf<PlayOption>()
    if (versions.isEmpty()) {
        options += option("self", currentEdition, currentSourceName, null, currentKey, currentName, currentVideo, currentRuntimeSec)
    } else {
        versions.forEach { v -> options += option("v:${v.id}", versionLabels[v.id] ?: v.label, currentSourceName, v.id, currentKey, v.label, v.video, v.durationSec ?: currentRuntimeSec) }
    }
    related.filterNot { it.poster.key == currentKey }.forEach { r ->
        options += option("k:${r.poster.key.sourceId.value}:${r.poster.key.remoteId.value}",
            r.editionLabel, r.sourceName, null, r.poster.key, r.poster.name, relatedVideo[r.poster.key], relatedRuntimeSec[r.poster.key])
    }
    // If TMDB has no runtime, the shortest actual copy is only a comparison baseline;
    // never call it theatrical without independent edition evidence.
    if (referenceRuntimeSec == null) {
        val shortest = options.mapNotNull { it.runtimeSec?.takeIf { seconds -> seconds >= 3600 } }.minOrNull()
        if (shortest != null && options.any { it.runtimeSec?.let { seconds -> seconds - shortest >= 600 } == true }) {
            options.replaceAll { option ->
                val inferred = relativeEditionLabel(option.runtimeSec, shortest)
                if (inferred != null && editionWords(option.label) == null)
                    option.copy(label = listOfNotNull(option.label, inferred).joinToString(" · "))
                else option
            }
        }
    }
    val hasDifferentCut = referenceRuntimeSec != null && options.any {
        it.runtimeSec?.let { runtime -> kotlin.math.abs(runtime - referenceRuntimeSec) >= 600 } == true
    }
    if (hasDifferentCut) {
        options.replaceAll { option ->
            if (option.runtimeSec != null && referenceRuntimeSec != null &&
                kotlin.math.abs(option.runtimeSec - referenceRuntimeSec) <= 180 &&
                editionWords(option.label) == null)
                option.copy(label = listOfNotNull(option.label, "Standard cut").joinToString(" · "))
            else option
        }
    }
    val sorted = options.sortedWith(compareByDescending<PlayOption> { it.qualityRank }
        .thenByDescending { it.rangeRank }
        .thenBy { it.sourceName.lowercase() }
        .thenBy { it.label.orEmpty().lowercase() })
        .distinctBy { it.id }
    val duplicates = sorted.groupBy { it.chipLabel }.filterValues { it.size > 1 }
    return sorted.map { option ->
        val group = duplicates[option.chipLabel] ?: return@map option
        val runtime = fmtRuntime(option.runtimeSec)
        val distinctRuntimes = group.map { fmtRuntime(it.runtimeSec) }.toSet()
        option.copy(disambiguator = if (runtime != null && distinctRuntimes.size == group.size) runtime
            else "Copy ${group.indexOf(option) + 1}")
    }
}

private fun cleanLabel(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

/** 8K > 4K > 1440p > 1080p > 720p > anything else (including unlabelled editions). */
internal fun qualityRank(label: String?): Int = textQuality(label).rank

/** The chip that matches what Play currently uses: the picked version, else this item itself. */
internal fun defaultPlayOptionId(options: List<PlayOption>, currentKey: ContentKey, selectedVersionId: String?): String? {
    val here = options.filter { it.key == currentKey }
    val picked = selectedVersionId?.let { id -> here.firstOrNull { it.versionId == id } }
    return (picked ?: here.firstOrNull { it.versionId == null } ?: here.firstOrNull() ?: options.firstOrNull())?.id
}

/** Chips to show (best first) and whether a "More…" chip is needed. The chosen one always stays visible. */
internal fun visiblePlayOptions(options: List<PlayOption>, selectedId: String?, max: Int = MAX_DETAIL_CHIPS): Pair<List<PlayOption>, Boolean> {
    if (options.size <= max) return options to false
    val shown = options.take(max).toMutableList()
    val selected = options.firstOrNull { it.id == selectedId }
    if (selected != null && shown.none { it.id == selected.id }) shown[max - 1] = selected
    return shown to true
}

/**
 * One way to play but the file says what it is (a lone 4K Dolby Vision or Extended copy): shown as
 * plain text, so the viewer still learns what they're about to watch. Null when there's nothing to say.
 */
internal fun singlePlayOptionLabel(options: List<PlayOption>): String? =
    options.singleOrNull()?.let { listOfNotNull(it.shownQuality, editionWords(it.label)).joinToString("  ·  ") }?.ifBlank { null }

/** Compact chip row under the title; a single option is a plain label, not a chip. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlayOptionChips(
    options: List<PlayOption>,
    selectedId: String?,
    onPick: (PlayOption) -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (options.size < 2) {
        val label = singlePlayOptionLabel(options) ?: return
        Text(label, style = OmniTheme.type.caption, color = OmniTheme.colors.textSecondary, modifier = modifier)
        return
    }
    val (shown, hasMore) = remember(options, selectedId) { visiblePlayOptions(options, selectedId) }
    FlowRow(modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        shown.forEach { option ->
            // Keyed: a copy's probe can land after the row is drawn and re-sort it; focus stays on its chip.
            key(option.id) { DetailChip(option.chipLabel, option.id == selectedId, emphasis = option.shownQuality) { onPick(option) } }
        }
        if (hasMore) { DetailChip(stringResource(R.string.detail_more_sources), false, onClick = onMore) }
    }
}

/** [emphasis] is the leading quality ("4K Dolby Vision"), lifted out of the dimmer source name. */
@Composable
internal fun DetailChip(label: String, selected: Boolean, emphasis: String? = null, onClick: () -> Unit) {
    val c = OmniTheme.colors
    FocusCard(onClick = onClick, modifier = Modifier.widthIn(max = 400.dp).width(IntrinsicSize.Max).height(28.dp)) {
        Box(
            Modifier.fillMaxSize()
                .background(if (selected) c.accent.copy(alpha = 0.24f) else Color.Transparent)
                .padding(horizontal = OmniSpacing.m),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                buildAnnotatedString {
                    append(label)
                    if (!selected && emphasis != null && label.startsWith(emphasis)) {
                        addStyle(SpanStyle(color = c.textPrimary, fontWeight = FontWeight.Medium), 0, emphasis.length)
                    }
                },
                style = OmniTheme.type.caption.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
                color = if (selected) c.textPrimary else c.textSecondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
