package com.yodesla.omniverse.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage

/** Decorative preview art dissolves into the actual backdrop, including its top and right edges. */
@Composable
fun HeroArtwork(
    backdropUrl: String?,
    posterUrl: String?,
    modifier: Modifier = Modifier,
) {
    val wide = backdropUrl?.takeIf { it.isNotBlank() }
    val art = wide ?: posterUrl?.takeIf { it.isNotBlank() } ?: return
    Box(modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithCache {
            // Alpha masks reveal the galaxy itself; painting a dark gradient leaves a visible box.
            val horizontal = Brush.horizontalGradient(
                0f to Color.Transparent, 0.18f to Color.Black.copy(alpha = 0.65f),
                0.36f to Color.Black, 0.93f to Color.Black, 1f to Color.Transparent,
            )
            val vertical = Brush.verticalGradient(
                0f to Color.Transparent, 0.06f to Color.Black,
                0.84f to Color.Black, 1f to Color.Transparent,
            )
            onDrawWithContent {
                drawContent()
                drawRect(horizontal, blendMode = BlendMode.DstIn)
                drawRect(vertical, blendMode = BlendMode.DstIn)
            }
        }) {
        AsyncImage(
            model = art, contentDescription = null,
            contentScale = ContentScale.FillBounds,
            alignment = Alignment.Center,
            colorFilter = VibrantArt, modifier = Modifier.fillMaxSize(),
        )
    }
}






