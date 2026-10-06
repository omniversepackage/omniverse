package com.yodesla.omniverse.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/*
 * Task 84e: the app's toggle switch. The ROW is the focus target (OK flips it); this is the
 * visual control the row renders on its right - never focusable or clickable on its own.
 */

/** Pure state helper (tested): thumb position 0 = off, 1 = on. */
fun switchThumbFraction(checked: Boolean): Float = if (checked) 1f else 0f

/** Pure state helper (tested): what OK does to the setting. */
fun nextSwitchState(checked: Boolean): Boolean = !checked

private val SwitchTrackWidth = 52.dp
private val SwitchTrackHeight = 30.dp
private val SwitchThumbSize = 24.dp
private val SwitchThumbTravel = 22.dp // 52 - 24 - 3 dp padding each side
private const val SWITCH_ANIM_MS = 150

@Composable
fun OmniSwitch(checked: Boolean, modifier: Modifier = Modifier) {
    val c = OmniTheme.colors
    val fraction by animateFloatAsState(switchThumbFraction(checked), tween(SWITCH_ANIM_MS), label = "switch-thumb")
    val shape = RoundedCornerShape(15.dp)
    Box(
        modifier
            .size(SwitchTrackWidth, SwitchTrackHeight)
            .clip(shape)
            .background(if (checked) c.accent else c.elevated)
            .border(1.dp, if (checked) c.accent else c.glassEdge, shape),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .size(SwitchThumbSize)
                .offset { IntOffset((SwitchThumbTravel * fraction).roundToPx(), 0) }
                .clip(CircleShape)
                .background(c.textPrimary),
        )
    }
}
