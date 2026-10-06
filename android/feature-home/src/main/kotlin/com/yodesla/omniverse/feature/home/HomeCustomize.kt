package com.yodesla.omniverse.feature.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.yodesla.omniverse.designsystem.FocusCard
import com.yodesla.omniverse.designsystem.FocusPivot
import com.yodesla.omniverse.designsystem.OmniSpacing
import com.yodesla.omniverse.designsystem.OmniTheme
import com.yodesla.omniverse.designsystem.requestFocusWhenReady

/**
 * Viewer-customizable Home layout (task 65): which rows show, in what order, and poster density.
 * Persisted as two settings — `home_layout` (order + hidden) and `home_density` (comfortable/compact).
 *
 * Row TYPES, not per-seed rows: the fixed rows keep their ids ("continue", "newEpisodes",
 * "playnext", "mylist", "fav", "movies", "shows"); every "Because you watched" row collapses to "byw"; each
 * pinned collection is its own type (its row id, "collection-<id>"). Unknown/new row types keep
 * their default position (they simply never appear in a saved order).
 */
@Immutable
data class HomeLayout(val order: List<String> = emptyList(), val hidden: Set<String> = emptySet())

/** One row in the Customize Home panel: a row type, its label, and whether it currently shows. */
@Immutable
data class HomePanelEntry(val type: String, val title: String, val hideable: Boolean, val visible: Boolean)

/** The panel key for a row id: fixed ids map to themselves, "byw-…" groups to "byw", else the id. */
internal fun homeRowType(id: String): String = when {
    id == "continue" || id == "newEpisodes" || id == "playnext" || id == "mylist" || id == "fav" || id == "movies" || id == "shows" || id == "myTeams" -> id
    id.startsWith("byw-") -> "byw"
    else -> id // "collection-<id>" and any future row: its own entry
}

/** "order=a,b,c\nhidden=d,e" → layout. Blank/unknown → default (empty order, nothing hidden). */
internal fun parseHomeLayout(raw: String?): HomeLayout {
    if (raw.isNullOrBlank()) return HomeLayout()
    var order = emptyList<String>()
    var hidden = emptySet<String>()
    raw.lineSequence().forEach { line ->
        val eq = line.indexOf('=')
        if (eq < 0) return@forEach
        val parts = line.substring(eq + 1).split(',').map { it.trim() }.filter { it.isNotEmpty() }
        when (line.substring(0, eq).trim()) {
            "order" -> order = parts
            "hidden" -> hidden = parts.toSet()
        }
    }
    return HomeLayout(order, hidden)
}

internal fun encodeHomeLayout(order: List<String>, hidden: Set<String>): String =
    "order=${order.joinToString(",")}\nhidden=${hidden.joinToString(",")}"

/**
 * Apply a saved layout to freshly built rows: rows sort by their type's saved rank (types absent
 * from the order keep their default relative position, after the ordered ones — the same rule as
 * category reorder), then hidden types drop. "Continue watching" can never be hidden.
 */
internal fun applyHomeLayout(rows: List<HomeRow>, layout: HomeLayout): List<HomeRow> {
    val rank = layout.order.withIndex().associate { (i, id) -> id to i }
    val ordered = rows.withIndex()
        .sortedWith(compareBy({ rank[homeRowType(it.value.id)] ?: Int.MAX_VALUE }, { it.index }))
        .map { it.value }
    return ordered.filter { homeRowType(it.id) !in layout.hidden || homeRowType(it.id) == "continue" }
}

/** Panel entries from default-built rows: one per type (first title wins), ordered by the layout. */
internal fun computeHomePanel(natural: List<HomeRow>, layout: HomeLayout): List<HomePanelEntry> {
    val firstByType = LinkedHashMap<String, HomePanelEntry>()
    for (row in natural) {
        val type = homeRowType(row.id)
        if (!firstByType.containsKey(type)) {
            firstByType[type] = HomePanelEntry(type, panelTitle(type, row.title), hideable = type != "continue", visible = true)
        }
    }
    val withVisibility = firstByType.values.map {
        if (it.type == "continue") it else it.copy(visible = it.type !in layout.hidden)
    }
    val rank = layout.order.withIndex().associate { (i, id) -> id to i }
    return withVisibility.withIndex()
        .sortedWith(compareBy({ rank[it.value.type] ?: Int.MAX_VALUE }, { it.index }))
        .map { it.value }
}

private fun panelTitle(type: String, rowTitle: String): String =
    if (type == "byw") stringBywGroup() else rowTitle

// The grouped "Because you watched" label is a fixed string, not a per-seed row title.
private fun stringBywGroup(): String = "Because you watched"

/**
 * The Customize Home panel: every row type with an On/Off toggle and a hold-OK reorder handle
 * (same grab pattern as the Movies category reorder), plus poster density and a reset.
 */
@Composable
fun HomeCustomizePanel(
    viewModel: HomeViewModel,
    entries: List<HomePanelEntry>,
    compact: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = OmniTheme.colors
    val first = remember { FocusRequester() }
    var movingType by remember { mutableStateOf<String?>(null) }
    // The release of the hold that picked a row up must not toggle it.
    var dropArmed by remember { mutableStateOf(false) }
    BackHandler(enabled = true) { onDismiss() }

    Box(modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.92f))) {        FocusPivot(parentFraction = 0.35f, leading = 0.dp) {
            LazyColumn(
                Modifier.fillMaxHeight().widthIn(max = 820.dp).onPreviewKeyEvent { e ->
                    val grabbed = movingType
                    if (grabbed != null) {
                        when (e.key) {
                            Key.DirectionUp -> { if (e.type == KeyEventType.KeyDown) viewModel.moveHomeRow(grabbed, -1); true }
                            Key.DirectionDown -> { if (e.type == KeyEventType.KeyDown) viewModel.moveHomeRow(grabbed, 1); true }
                            Key.DirectionCenter, Key.Enter -> {
                                if (e.type == KeyEventType.KeyUp) {
                                    if (dropArmed) { movingType = null; viewModel.finishHomeMove() } else dropArmed = true
                                }
                                true
                            }
                            Key.Back -> { if (e.type == KeyEventType.KeyUp) { movingType = null; viewModel.finishHomeMove() }; true }
                            else -> true
                        }
                    } else false
                },
                contentPadding = PaddingValues(start = OmniSpacing.tvSide, end = OmniSpacing.tvSide, top = OmniSpacing.xxl, bottom = 200.dp),
                verticalArrangement = Arrangement.spacedBy(OmniSpacing.s),
            ) {
                item { Text(stringResource(R.string.customize_home_title), style = OmniTheme.type.browseHeading, color = c.textPrimary) }
                item { Text(stringResource(R.string.customize_home_hint), style = OmniTheme.type.caption, color = c.textSecondary) }
                item { Section(stringResource(R.string.home_rows_section)) }
                items(entries, key = { it.type }) { entry ->
                    val grabbed = movingType != null && movingType == entry.type
                    FocusCard(
                        onClick = { if (entry.hideable) viewModel.setHomeRowVisible(entry.type, !entry.visible) },
                        onLongClick = { movingType = entry.type; dropArmed = false },
                        modifier = Modifier.fillMaxWidth().height(60.dp)
                            .then(if (entry == entries.firstOrNull()) Modifier.focusRequester(first) else Modifier)
                            .then(if (grabbed) Modifier.border(2.dp, c.accent, RoundedCornerShape(12.dp)) else Modifier),
                    ) {
                        Row(Modifier.fillMaxSize().padding(horizontal = OmniSpacing.l), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(entry.title, style = OmniTheme.type.body, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (!entry.hideable) Text(stringResource(R.string.home_row_always_on), style = OmniTheme.type.caption, color = c.textTertiary, maxLines = 1)
                            }
                            if (grabbed) {
                                Text("↕", style = OmniTheme.type.title, color = c.accent)
                                Spacer(Modifier.width(OmniSpacing.m))
                            }
                            Text(
                                stringResource(if (entry.visible) R.string.home_row_on else R.string.home_row_off),
                                style = OmniTheme.type.body,
                                color = when { !entry.hideable -> c.textTertiary; entry.visible -> c.accent; else -> c.textSecondary },
                            )
                        }
                    }
                }
                item { Section(stringResource(R.string.home_poster_size_section)) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m)) {
                        SizePill(stringResource(R.string.home_poster_comfortable), selected = !compact) { viewModel.setHomeCompact(false) }
                        SizePill(stringResource(R.string.home_poster_compact), selected = compact) { viewModel.setHomeCompact(true) }
                    }
                }
                item { Section("") }
                item {
                    SizePill(stringResource(R.string.home_reset), accent = true) { viewModel.resetHomeLayout() }
                }
            }
        }
    }
    LaunchedEffect(Unit) { first.requestFocusWhenReady() }
}

@Composable
private fun Section(title: String) {
    if (title.isEmpty()) Spacer(Modifier.height(OmniSpacing.s))
    else Text(title, style = OmniTheme.type.title, color = OmniTheme.colors.accent, modifier = Modifier.padding(top = OmniSpacing.l, bottom = OmniSpacing.xs))
}

@Composable
private fun SizePill(label: String, modifier: Modifier = Modifier, accent: Boolean = false, selected: Boolean = false, onClick: () -> Unit) {
    val c = OmniTheme.colors
    FocusCard(onClick = onClick, modifier = modifier.height(48.dp)) {
        Box(Modifier.fillMaxHeight().padding(horizontal = OmniSpacing.l), contentAlignment = Alignment.CenterStart) {
            Text(label, style = OmniTheme.type.body, color = when { accent -> c.live; selected -> c.accent; else -> c.textPrimary })
        }
    }
}
