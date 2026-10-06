package com.yodesla.omniverse.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text

@Immutable
data class NavItem(val id: String, val label: String, val icon: ImageVector)

val TvNavRailWidth = 248.dp

/**
 * TV navigation is composed only while the viewer is using it. The page reserves this
 * width while open so category and channel controls never sit underneath the rail.
 */
@Composable
fun NavRail(
    items: List<NavItem>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    selectedRequester: FocusRequester = remember { FocusRequester() },
) {
    val c = OmniTheme.colors
    CosmicBackdrop(modifier.width(TvNavRailWidth).fillMaxHeight()) {
    Column(
        Modifier.fillMaxWidth().fillMaxHeight()
            .glassSurface(corner = 0.dp, shadow = false)
            // Entering the rail always lands on the CURRENT section (not the geometrically nearest item).
            .focusProperties { onEnter = { selectedRequester.requestFocus() } }
            .focusGroup()
            .padding(vertical = OmniSpacing.xl, horizontal = OmniSpacing.m),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("EXPLORE", style = OmniTheme.type.overline, color = c.accent,
            modifier = Modifier.padding(start = OmniSpacing.m, bottom = OmniSpacing.m))
        for (item in items) {
            val selected = item.id == selectedId
            var focused by remember { mutableStateOf(false) }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .then(if (selected) Modifier.focusRequester(selectedRequester) else Modifier)
                    .onFocusChanged { focused = it.isFocused }
                    .then(if (focused || selected) Modifier.glassSurface(corner = 26.dp, focused = focused, shadow = false) else Modifier)
                    .clickableTv { onSelect(item.id) },
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(Modifier.padding(horizontal = OmniSpacing.m), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        item.icon, contentDescription = item.label,
                        tint = when {
                            focused -> c.textPrimary
                            selected -> c.accent
                            else -> c.textTertiary
                        },
                        modifier = Modifier.size(26.dp),
                    )
                    Spacer(Modifier.width(OmniSpacing.m))
                    Text(item.label, style = if (selected || focused) OmniTheme.type.title else OmniTheme.type.body,
                        color = if (selected || focused) c.textPrimary else c.textSecondary, maxLines = 1)
                }
                if (selected && !focused) {
                    Box(Modifier.align(Alignment.CenterStart).width(3.dp).height(20.dp).clip(RoundedCornerShape(2.dp)).background(c.accent))
                }
            }
        }
    }
    }
}

/** Focusable + clickable for D-pad (OK / Enter) without a ripple. */
@Composable
private fun Modifier.clickableTv(onClick: () -> Unit): Modifier =
    this.clickable(
        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
        indication = null,
        onClick = onClick,
    )
