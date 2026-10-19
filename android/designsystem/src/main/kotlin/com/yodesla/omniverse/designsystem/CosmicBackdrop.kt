package com.yodesla.omniverse.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
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

/**
 * Static, low-contrast atmosphere behind TV browse surfaces; never animates or captures focus.
 *
 * Performance: the Shield renders the UI at 4K. The art used to be drawn every frame as a
 * translucent full-screen image plus a translucent full-screen gradient (two blended 4K layers
 * under every focus move). It is now composed ONCE, at half resolution, into an opaque bitmap and
 * drawn as a single scaled blit; the art is soft space imagery, so the lower resolution is invisible.
 */
@Composable
fun CosmicBackdrop(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val c = OmniTheme.colors
    val art = painterResource(R.drawable.cosmic_backdrop_v2)
    Box(modifier) {
        Spacer(
            Modifier.fillMaxSize().drawWithCache {
                val w = max(1, (size.width / 2f).toInt())
                val h = max(1, (size.height / 2f).toInt())
                val bmp = ImageBitmap(w, h)
                CanvasDrawScope().draw(this, LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(bmp), Size(w.toFloat(), h.toFloat())) {
                    drawRect(c.background)
                    drawCropped(art, Size(w.toFloat(), h.toFloat()), alpha = 0.72f)
                    drawRect(
                        Brush.verticalGradient(
                            0f to c.background.copy(alpha = 0.18f),
                            0.55f to c.background.copy(alpha = 0.28f),
                            1f to c.background.copy(alpha = 0.72f),
                        ),
                    )
                }
                onDrawBehind {
                    drawImage(bmp, srcOffset = IntOffset.Zero, srcSize = IntSize(w, h), dstSize = IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.Low)
                }
            },
        )
        content()
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
