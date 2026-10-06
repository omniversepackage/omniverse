package com.yodesla.omniverse.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Glow

/**
 * The signature focus treatment (PLAN.md §8.2): scale 1.06, accent-tinted glow, no border.
 * Every focusable tile in the app is built on this so the feel is identical everywhere.
 */
@Composable
fun FocusCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    glass: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(OmniSpacing.cardCorner)
    var focused by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.onFocusChanged { focused = it.hasFocus }.touchClick(onClick, onLongClick),
        shape = CardDefaults.shape(shape = shape),
        scale = CardDefaults.scale(focusedScale = OmniMotion.FOCUS_SCALE),
        colors = CardDefaults.colors(
            containerColor = if (glass) Color.Transparent else OmniTheme.colors.surface.copy(alpha = 0.78f),
            focusedContainerColor = if (glass) Color.Transparent else OmniTheme.colors.elevated.copy(alpha = 0.96f),
        ),
        // Depth comes from light, never borders (PLAN.md §8.1).
        border = CardDefaults.border(border = Border.None, focusedBorder = Border.None, pressedBorder = Border.None),
        glow = CardDefaults.glow(
            focusedGlow = Glow(elevationColor = OmniTheme.colors.accent.copy(alpha = 0.55f), elevation = 18.dp),
        ),
    ) {
        Box(modifier = if (glass) Modifier.glassSurface(focused = focused, shadow = false) else Modifier, content = content)
    }
}
