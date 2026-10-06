package com.yodesla.omniverse.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush

/**
 * Static, translucent destination glow tinted by the selected category's brand accent.
 * Place it as the first child of a [CosmicBackdrop] box so it sits behind the browse
 * content. Two fixed linear gradients only — no blur, shader state, or animation — so it
 * costs nothing while scrolling and fades into the cosmic backdrop. Renders nothing for
 * unrecognized or special categories, preserving their existing appearance.
 */
@Composable
fun CategoryAtmosphere(name: String, modifier: Modifier = Modifier) {
    val tint = categoryAccent(name) ?: return
    Box(modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to tint.copy(alpha = 0.16f),
                    0.35f to tint.copy(alpha = 0.07f),
                    0.7f to tint.copy(alpha = 0f),
                    1f to tint.copy(alpha = 0f),
                ),
            ),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to tint.copy(alpha = 0.10f),
                    0.4f to tint.copy(alpha = 0f),
                    1f to tint.copy(alpha = 0f),
                ),
            ),
        )
    }
}
