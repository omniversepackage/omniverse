package com.yodesla.omniverse.designsystem

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Where a focused item settles when a lazy list scrolls to it (the leanback "window alignment").
 *
 * Default Compose TV behaviour centres the focused item, so the first cards of a row slide half
 * off-screen. Premium apps anchor focus at a fixed "pivot": rows keep the focused card at the
 * left safe margin; vertical pages keep the focused row ~30% down the screen.
 *
 * target = parentFraction * container + leading - childFraction * child
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FocusPivot(
    parentFraction: Float = 0f,
    childFraction: Float = 0f,
    leading: Dp = OmniSpacing.tvSide,
    focusedScale: Float = OmniMotion.FOCUS_SCALE,
    halo: Dp = OmniSpacing.focusHalo,
    onlyWhenClipped: Boolean = false,
    /** Space after the focusable image for its non-focusable title and metadata. */
    trailing: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    val leadingPx = with(LocalDensity.current) { leading.toPx() }
    val haloPx = with(LocalDensity.current) { halo.toPx() }
    val trailingPx = with(LocalDensity.current) { trailing.toPx() }
    val spec = remember(parentFraction, childFraction, leadingPx, haloPx, focusedScale, onlyWhenClipped, trailingPx) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                val target = parentFraction * containerSize + leadingPx - childFraction * size
                return focusSafeScrollDistance(offset, size, (containerSize - trailingPx).coerceAtLeast(1f), target, haloPx, focusedScale, onlyWhenClipped)
            }
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

/** Keep the transformed card inside the viewport without changing its focus scale or pivot policy. */
internal fun focusSafeScrollDistance(
    offset: Float, size: Float, containerSize: Float, target: Float, halo: Float,
    focusedScale: Float = OmniMotion.FOCUS_SCALE, onlyWhenClipped: Boolean = false,
): Float {
    val outset = size * (focusedScale - 1f) / 2f + halo
    val lastSafeStart = containerSize - size - outset
    if (onlyWhenClipped && lastSafeStart >= outset) {
        return when {
            offset < outset -> offset - outset
            offset > lastSafeStart -> offset - lastSafeStart
            else -> 0f
        }
    }
    val safeTarget = if (lastSafeStart >= outset) target.coerceIn(outset, lastSafeStart)
        else (containerSize - size) / 2f
    return offset - safeTarget
}
