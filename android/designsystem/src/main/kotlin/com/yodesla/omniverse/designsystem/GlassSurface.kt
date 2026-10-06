package com.yodesla.omniverse.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A cheap TV-friendly approximation of the site's layered glass: translucent navy,
 * shallow edge reflection and ambient depth. No per-card backdrop blur or shader work.
 * The hairline belongs to the glass surface; D-pad focus still uses the app's glow.
 */
@Composable
fun Modifier.glassSurface(
    corner: Dp = OmniSpacing.cardCorner,
    focused: Boolean = false,
    tint: Color? = null,
    shadow: Boolean = true,
): Modifier {
    val c = OmniTheme.colors
    val shape = RoundedCornerShape(corner)
    return this
        .then(if (shadow) Modifier.shadow(8.dp, shape, clip = false) else Modifier)
        .clip(shape)
        .drawWithCache {
            val radius = corner.toPx()
            val top = if (focused) c.glassFocusTop else c.glassTop
            val bottom = if (focused) c.glassFocusBottom else c.glassBottom
            val tintTop = tint?.let { lerp(top, it.copy(alpha = top.alpha), 0.10f) } ?: top
            val fill = Brush.verticalGradient(listOf(tintTop, bottom))
            val focusSheen = Brush.horizontalGradient(listOf(c.glassBlueSheen, Color.Transparent, c.glassWineSheen))
            val topGlint = Brush.horizontalGradient(listOf(Color.Transparent, c.glassGlint, Color.Transparent))
            val edgeWidth = 1.dp.toPx()
            onDrawWithContent {
                drawRoundRect(fill, cornerRadius = CornerRadius(radius))
                if (focused) drawRoundRect(focusSheen, cornerRadius = CornerRadius(radius))
                drawContent()
                drawRoundRect(
                    color = c.glassEdge,
                    topLeft = Offset(edgeWidth / 2f, edgeWidth / 2f),
                    size = Size(size.width - edgeWidth, size.height - edgeWidth),
                    cornerRadius = CornerRadius(radius),
                    style = Stroke(width = edgeWidth),
                )
                if (size.width > radius * 2f) {
                    drawLine(
                        brush = topGlint,
                        start = Offset(radius, edgeWidth),
                        end = Offset(size.width - radius, edgeWidth),
                        strokeWidth = edgeWidth,
                    )
                }
            }
        }
}
