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
import com.yodesla.omniverse.designsystem.OmniTheme

/** True only where RenderEffect exists (API 31+); older boxes get the gradient treatment. */
internal fun supportsRuntimeBlur(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * Full-bleed artwork for a detail page. A real wide backdrop wins and keeps the existing
 * Midnight Cinema washes. Without one, the poster becomes the background — blurred on API 31+,
 * or scaled up under a heavy dark wash on older boxes — so the page never looks empty.
 */
@Composable
internal fun DetailBackdrop(backdropUrl: String?, posterUrl: String?, modifier: Modifier = Modifier) {
    val c = OmniTheme.colors
    if (backdropUrl != null) {
        AsyncImage(model = backdropUrl, contentDescription = null, contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter, modifier = modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to c.background, 0.38f to c.background.copy(alpha = 0.78f), 0.75f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.3f to Color.Transparent, 1f to c.background)))
        return
    }
    if (posterUrl != null) {
        val blur = supportsRuntimeBlur()
        AsyncImage(
            model = posterUrl, contentDescription = null, contentScale = ContentScale.Crop, alignment = Alignment.Center,
            modifier = modifier.fillMaxSize().scale(if (blur) 1.18f else 1.7f).then(if (blur) Modifier.blur(54.dp) else Modifier),
        )
        if (blur) {
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to c.background.copy(alpha = 0.92f), 0.5f to c.background.copy(alpha = 0.62f), 1f to c.background.copy(alpha = 0.34f))))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to c.background.copy(alpha = 0.5f), 0.55f to c.background.copy(alpha = 0.74f), 1f to c.background)))
        } else {
            // No runtime blur below API 31: a blown-up poster under a heavy dark wash reads as depth.
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to c.background.copy(alpha = 0.95f), 0.55f to c.background.copy(alpha = 0.82f), 1f to c.background.copy(alpha = 0.58f))))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to c.background.copy(alpha = 0.82f), 0.5f to c.background.copy(alpha = 0.88f), 1f to c.background)))
        }
        return
    }
    Box(modifier.fillMaxSize().background(Brush.horizontalGradient(0f to c.background.copy(alpha = 0.9f), 0.48f to c.background.copy(alpha = 0.48f), 1f to Color.Transparent)))
}
