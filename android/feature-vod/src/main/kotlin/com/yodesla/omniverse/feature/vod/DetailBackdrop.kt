package com.yodesla.omniverse.feature.vod

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage


/** True only where RenderEffect exists (API 31+); older boxes get the gradient treatment. */
internal fun supportsRuntimeBlur(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * Full-bleed artwork for a detail page. A real wide backdrop wins and keeps the existing
 * faint neutral shading. Without one, the poster becomes the background — blurred on API 31+,
 * or scaled up under a light neutral wash on older boxes — so the page never looks empty.
 */
@Composable
internal fun DetailBackdrop(backdropUrl: String?, posterUrl: String?, modifier: Modifier = Modifier) {
    val shade = Color.Black
    if (backdropUrl != null) {
        AsyncImage(model = backdropUrl, contentDescription = null, contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter, modifier = modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to shade.copy(alpha = 0.30f), 0.38f to shade.copy(alpha = 0.15f), 0.75f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.3f to Color.Transparent, 1f to shade.copy(alpha = 0.28f))))
        return
    }
    if (posterUrl != null) {
        val blur = supportsRuntimeBlur()
        AsyncImage(
            model = posterUrl, contentDescription = null, contentScale = ContentScale.Crop, alignment = Alignment.Center,
            modifier = modifier.fillMaxSize().scale(if (blur) 1.18f else 1.7f).then(if (blur) Modifier.blur(54.dp) else Modifier),
        )
        if (blur) {
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to shade.copy(alpha = 0.30f), 0.5f to shade.copy(alpha = 0.18f), 1f to shade.copy(alpha = 0.10f))))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to shade.copy(alpha = 0.15f), 0.55f to shade.copy(alpha = 0.22f), 1f to shade.copy(alpha = 0.30f))))
        } else {
            // No runtime blur below API 31: a blown-up poster under a light neutral wash reads as depth.
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to shade.copy(alpha = 0.35f), 0.55f to shade.copy(alpha = 0.24f), 1f to shade.copy(alpha = 0.12f))))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to shade.copy(alpha = 0.24f), 0.5f to shade.copy(alpha = 0.26f), 1f to shade.copy(alpha = 0.30f))))
        }
        return
    }
    Box(modifier.fillMaxSize().background(Brush.horizontalGradient(0f to shade.copy(alpha = 0.20f), 0.48f to shade.copy(alpha = 0.10f), 1f to Color.Transparent)))
}

