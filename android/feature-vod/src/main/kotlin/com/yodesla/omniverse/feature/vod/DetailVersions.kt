package com.yodesla.omniverse.feature.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.MediaVersion
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
) {
    val chipLabel: String get() = if (label.isNullOrBlank()) sourceName else "$label  ·  $sourceName"
}

/** Chips stop here; anything past it goes behind the "More…" chip and the existing dialog. */
internal const val MAX_DETAIL_CHIPS = 4

/**
 * Every way this title can be played: the files inside the item being viewed (when the source
 * exposes versions), the item itself (when it does not), and each parentally visible exact-ID
 * alternative. Ordered best quality first, then by source name, so the chip a viewer would
 * press is the left one.
 */
internal fun buildPlayOptions(
    currentKey: ContentKey,
    currentSourceName: String,
    currentEdition: String?,
    versions: List<MediaVersion>,
    related: List<RelatedMovie>,
    currentName: String? = null,
): List<PlayOption> {
    val options = mutableListOf<PlayOption>()
    if (versions.isEmpty()) {
        options += PlayOption("self", cleanLabel(currentEdition), currentSourceName, qualityRank(currentEdition), null, currentKey, currentName)
    } else {
        versions.forEach { v ->
            options += PlayOption("v:${v.id}", cleanLabel(v.label), currentSourceName, qualityRank(v.label), v.id, currentKey, v.label)
        }
    }
    related.filterNot { it.poster.key == currentKey }.forEach { r ->
        options += PlayOption("k:${r.poster.key.sourceId.value}:${r.poster.key.remoteId.value}",
            cleanLabel(r.editionLabel), r.sourceName, qualityRank(r.editionLabel), null, r.poster.key, r.poster.name)
    }
    return options.sortedWith(compareByDescending<PlayOption> { it.qualityRank }
        .thenBy { it.sourceName.lowercase() }
        .thenBy { it.label.orEmpty().lowercase() })
        .distinctBy { it.id }
}

private fun cleanLabel(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

/** 8K > 4K > 1440p > 1080p > 720p > anything else (including unlabelled editions). */
internal fun qualityRank(label: String?): Int {
    val l = label?.lowercase()?.trim() ?: return 0
    return when {
        l.contains("8k") || l.contains("4320") -> 5
        l.contains("4k") || l.contains("uhd") || l.contains("2160") -> 4
        l.contains("1440") || l.contains("qhd") || l.contains("2k") -> 3
        l.contains("1080") || l.contains("fhd") -> 2
        l.contains("720") || l.contains("hd") -> 1
        else -> 0
    }
}

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
 * One way to play but the file says what it is (a lone 4K or Extended version): the label shown as
 * plain text, so the viewer still learns what they're about to watch. Null when there's nothing to say.
 */
internal fun singlePlayOptionLabel(options: List<PlayOption>): String? =
    options.singleOrNull()?.label?.takeIf { it.isNotBlank() }

/** Compact chip row under the title; a single option is a plain label, not a chip. */
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
    LazyRow(modifier, horizontalArrangement = Arrangement.spacedBy(OmniSpacing.s)) {
        items(shown, key = { it.id }) { option ->
            DetailChip(option.chipLabel, option.id == selectedId) { onPick(option) }
        }
        if (hasMore) item(key = "more") { DetailChip(stringResource(R.string.detail_more_sources), false, onMore) }
    }
}

@Composable
internal fun DetailChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = OmniTheme.colors
    FocusCard(onClick = onClick, modifier = Modifier.height(40.dp)) {
        Box(
            Modifier.fillMaxSize()
                .background(if (selected) c.accent.copy(alpha = 0.24f) else Color.Transparent)
                .padding(horizontal = OmniSpacing.m),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                style = OmniTheme.type.body.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
                color = if (selected) c.textPrimary else c.textSecondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
