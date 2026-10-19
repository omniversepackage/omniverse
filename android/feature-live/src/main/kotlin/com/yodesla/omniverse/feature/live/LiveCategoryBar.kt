package com.yodesla.omniverse.feature.live

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Text
import com.yodesla.omniverse.designsystem.CategoryIdentity
import com.yodesla.omniverse.designsystem.CategorySelectionGlow
import com.yodesla.omniverse.designsystem.OmniSpacing
import com.yodesla.omniverse.designsystem.OmniTheme
import com.yodesla.omniverse.designsystem.touchClick

/**
 * Live TV categories along the top (Settings › Display › "Horizontal categories", the same
 * setting Movies & Shows use). Used above the channel list and inside the Guide header.
 * OK selects a category; hold OK opens its menu (Move / Hide); while moving, Left/Right move it
 * and OK or Back drops it.
 */
@Composable
fun LiveCategoryBar(
    categories: List<CategoryUi>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    selectedFocus: FocusRequester = remember { FocusRequester() },
    onSearch: (() -> Unit)? = null,
    onMultiview: (() -> Unit)? = null,
    onLongPress: ((String) -> Unit)? = null,
    movingId: String? = null,
    onMove: (String, Int) -> Unit = { _, _ -> },
    onDrop: () -> Unit = {},
    /** 0 inside a header that already has the screen gutter (the Guide). */
    sidePadding: androidx.compose.ui.unit.Dp = OmniSpacing.tvSide,
) {
    val list = rememberLazyListState()
    val lead = (if (onSearch != null) 1 else 0) + (if (onMultiview != null) 1 else 0)
    // Keep the selected tab composed and in view, so Up from the content always lands on it.
    LaunchedEffect(selectedId, categories) {
        val i = categories.indexOfFirst { it.id == selectedId }
        if (i >= 0 && list.layoutInfo.visibleItemsInfo.none { it.index == i + lead }) list.scrollToItem((i + lead - 1).coerceAtLeast(0))
    }
    var dropArmed by remember(movingId) { mutableStateOf(false) }
    LazyRow(
        state = list,
        contentPadding = PaddingValues(horizontal = sidePadding, vertical = OmniSpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(OmniSpacing.s),
        modifier = modifier.fillMaxWidth().onPreviewKeyEvent { e ->
            val grabbed = movingId ?: return@onPreviewKeyEvent false
            when (e.key) {
                Key.DirectionLeft, Key.DirectionRight -> { if (e.type == KeyEventType.KeyDown) onMove(grabbed, if (e.key == Key.DirectionLeft) -1 else 1); true }
                Key.DirectionCenter, Key.Enter -> {
                    if (e.type == KeyEventType.KeyUp) { if (dropArmed) onDrop() else dropArmed = true }
                    true
                }
                Key.Back -> { if (e.type == KeyEventType.KeyUp) onDrop(); true }
                else -> true
            }
        },
    ) {
        if (onSearch != null) item(key = "__search") {
            BarTab(onClick = onSearch, modifier = Modifier) {
                Text("🔍 Search", style = OmniTheme.type.body, color = OmniTheme.colors.textPrimary, maxLines = 1)
            }
        }
        if (onMultiview != null) item(key = "__multiview") {
            BarTab(onClick = onMultiview, modifier = Modifier) {
                Text("Multiview", style = OmniTheme.type.body, color = OmniTheme.colors.textPrimary, maxLines = 1)
            }
        }
        items(categories, key = { it.id }) { cat ->
            val selected = cat.id == selectedId
            BarTab(
                onClick = { onSelect(cat.id) },
                onLongClick = if (!cat.special && onLongPress != null) ({ onLongPress(cat.id) }) else null,
                modifier = if (selected) Modifier.focusRequester(selectedFocus) else Modifier,
                outlined = movingId == cat.id,
            ) {
                if (selected || movingId == cat.id) CategorySelectionGlow(cat.name, Modifier.fillMaxSize())
                if (cat.special) {
                    Text(cat.name, style = OmniTheme.type.body, color = if (selected) OmniTheme.colors.accent else OmniTheme.colors.textPrimary, maxLines = 1)
                } else {
                    CategoryIdentity(cat.name, selected)
                }
            }
        }
    }
}

/** Same compact pill as the Movies/Shows top bar. */
@Composable
private fun BarTab(onClick: () -> Unit, modifier: Modifier, onLongClick: (() -> Unit)? = null, outlined: Boolean = false, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    val shape = RoundedCornerShape(OmniSpacing.pill)
    val c = OmniTheme.colors
    Card(
        onClick,
        modifier = modifier.widthIn(min = 64.dp, max = 200.dp).width(IntrinsicSize.Max).height(34.dp).touchClick(onClick, onLongClick),
        onLongClick = onLongClick,
        shape = CardDefaults.shape(shape), scale = CardDefaults.scale(focusedScale = 1.02f),
        glow = CardDefaults.glow(focusedGlow = Glow.None),
        border = CardDefaults.border(
            border = if (outlined) Border(BorderStroke(2.dp, c.accent), shape = shape) else Border.None,
            focusedBorder = Border(BorderStroke(2.dp, c.focusBorder), shape = shape),
        ),
        colors = CardDefaults.colors(containerColor = c.surface.copy(alpha = 0.55f), focusedContainerColor = c.elevated),
    ) {
        Box(Modifier.fillMaxSize().padding(horizontal = OmniSpacing.m), contentAlignment = Alignment.CenterStart, content = content)
    }
}
