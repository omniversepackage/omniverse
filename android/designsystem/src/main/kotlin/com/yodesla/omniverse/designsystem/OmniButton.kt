package com.yodesla.omniverse.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text

/**
 * The app's only button. A quiet surface pill at rest; on focus it fills (accent for [primary],
 * near-white otherwise), flips its text dark and lifts slightly. Manrope, not the tv-material
 * default font. Use for actions; content uses FocusCard.
 *
 * Task 90: labels are always solid text — the tv-material default text style is overridden with an
 * explicit shadow-free [OmniTheme.type.body] so no dark halo/outline is drawn around the label.
 * Contrast comes from colour only. [destructive] keeps the normal dark pill and reads in muted red.
 */
@Composable
fun OmniButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    destructive: Boolean = false,
) {
    val c = OmniTheme.colors
    val labelStyle = OmniTheme.type.body.copy(shadow = null)
    Button(
        onClick = onClick,
        modifier = modifier.height(52.dp).touchClick(onClick),
        shape = ButtonDefaults.shape(RoundedCornerShape(26.dp)),
        colors = ButtonDefaults.colors(
            containerColor = if (primary) c.accent.copy(alpha = 0.18f) else c.elevated,
            contentColor = if (destructive) c.live else c.textPrimary,
            focusedContainerColor = when {
                destructive -> c.elevated
                primary -> c.accent
                else -> c.textPrimary
            },
            focusedContentColor = if (destructive) c.live else c.background,
            pressedContainerColor = when {
                destructive -> c.elevated
                primary -> c.accent
                else -> c.textPrimary
            },
            pressedContentColor = if (destructive) c.live else c.background,
        ),
        scale = ButtonDefaults.scale(focusedScale = 1.05f),
        contentPadding = PaddingValues(horizontal = 28.dp),
    ) {
        // The button's content row doesn't fill the fixed height; centre the label explicitly.
        Box(Modifier.fillMaxHeight(), contentAlignment = Alignment.Center) {
            Text(label, style = labelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
