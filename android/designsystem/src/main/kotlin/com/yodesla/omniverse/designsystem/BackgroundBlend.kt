package com.yodesla.omniverse.designsystem

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.unit.dp

/** Dissolve the top edge of a nested destination into its parent's backdrop. No blur or animation. */
fun Modifier.blendDestinationTop(enabled: Boolean = true): Modifier = if (!enabled) this else
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithCache {
        val mask = Brush.verticalGradient(listOf(Color.Transparent, Color.Black), endY = 24.dp.toPx())
        onDrawWithContent { drawContent(); drawRect(mask, blendMode = BlendMode.DstIn) }
    }

/** Feather only a decorative background; foreground text and focus controls stay opaque. */
fun Modifier.featheredBackground(brush: Brush): Modifier = drawWithCache {
    val mask = Brush.verticalGradient(0f to Color.Black, 0.65f to Color.Black, 1f to Color.Transparent)
    onDrawBehind {
        drawContext.canvas.saveLayer(Rect(0f, 0f, size.width, size.height), Paint())
        drawRect(brush)
        drawRect(mask, blendMode = BlendMode.DstIn)
        drawContext.canvas.restore()
    }
}
