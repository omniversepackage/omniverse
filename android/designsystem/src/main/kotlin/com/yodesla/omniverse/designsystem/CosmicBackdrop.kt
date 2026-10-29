package com.yodesla.omniverse.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.drawscope.translate
import kotlin.math.max

private val LocalCosmicBackdrop = staticCompositionLocalOf { false }

/**
 * Luminous atmosphere behind browse surfaces; never animates or captures focus.
 *
 * Performance: the Shield renders the UI at 4K. The art used to be drawn every frame as a
 * translucent full-screen image plus a translucent full-screen gradient (two blended 4K layers
 * under every focus move). Compose once at the actual viewport size to preserve fine stars,
 * then draw opaquely. Nested destinations share the same backdrop.
 */
@Composable
fun CosmicBackdrop(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    if (LocalCosmicBackdrop.current) {
        Box(modifier, content = content)
        return
    }
    val c = OmniTheme.colors
    val art = painterResource(R.drawable.cosmic_backdrop_v3)
    Box(modifier) {
        Spacer(
            Modifier.fillMaxSize().drawWithCache {
                val w = max(1, size.width.toInt())
                val h = max(1, size.height.toInt())
                val bmp = ImageBitmap(w, h)
                CanvasDrawScope().draw(this, LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(bmp), Size(w.toFloat(), h.toFloat())) {
                    drawRect(c.background)
                    drawCropped(art, Size(w.toFloat(), h.toFloat()), alpha = 1f)
                    drawRect(
                        Brush.verticalGradient(
                            0f to c.background.copy(alpha = 0.08f),
                            0.55f to c.background.copy(alpha = 0.12f),
                            1f to c.background.copy(alpha = 0.30f),
                        ),
                    )
                }
                onDrawBehind {
                    drawImage(bmp)
                }
            },
        )
        CompositionLocalProvider(LocalCosmicBackdrop provides true) { content() }
    }
}

/** ContentScale.Crop for a painter inside a DrawScope. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCropped(p: Painter, dst: Size, alpha: Float) {
    val src = p.intrinsicSize
    if (src == Size.Unspecified || src.width <= 0f || src.height <= 0f) { with(p) { draw(dst, alpha) }; return }
    val k = max(dst.width / src.width, dst.height / src.height)
    val drawn = Size(src.width * k, src.height * k)
    translate((dst.width - drawn.width) / 2f, (dst.height - drawn.height) / 2f) { with(p) { draw(drawn, alpha) } }
}
