package com.yodesla.omniverse.designsystem

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource

/** Static, low-contrast atmosphere behind TV browse surfaces; never animates or captures focus. */
@Composable
fun CosmicBackdrop(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val c = OmniTheme.colors
    Box(modifier.background(c.background)) {
            Image(
                painter = painterResource(R.drawable.cosmic_backdrop_v2),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = 0.72f,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to c.background.copy(alpha = 0.18f),
                        0.55f to c.background.copy(alpha = 0.28f),
                        1f to c.background.copy(alpha = 0.72f),
                    ),
                ),
            )
        content()
    }
}
