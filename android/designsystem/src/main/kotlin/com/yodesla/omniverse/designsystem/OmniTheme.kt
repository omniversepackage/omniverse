package com.yodesla.omniverse.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

private val LocalOmniColors = staticCompositionLocalOf { OmniColors() }
private val LocalOmniType = staticCompositionLocalOf { OmniType() }
private val LocalPublicBuild = staticCompositionLocalOf { false }

/** The active text scale (1.0 / 1.15 / 1.3). Components that clip text in fixed-height boxes read it
 *  to cap their own growth (e.g. the guide grid stays at 1.15 so rows stay readable). */
val LocalTextScale = staticCompositionLocalOf { 1f }

object OmniTheme {
    val colors: OmniColors
        @Composable @ReadOnlyComposable get() = LocalOmniColors.current
    val type: OmniType
        @Composable @ReadOnlyComposable get() = LocalOmniType.current
    /** Public/distribution build: service marks render as text, never bundled logo vectors. */
    val publicBuild: Boolean
        @Composable @ReadOnlyComposable get() = LocalPublicBuild.current
}

/** Task 84: per-profile "Text size" setting key and its three stored values. */
const val TEXT_SIZE_KEY = "text_size"
const val TEXT_SIZE_NORMAL = "normal"
const val TEXT_SIZE_LARGE = "large"
const val TEXT_SIZE_XLARGE = "xlarge"

/** Pure mapping from the stored setting to a scale. null / unknown → Normal (1.0). */
fun textScaleFor(setting: String?): Float = when (setting) {
    TEXT_SIZE_LARGE -> 1.15f
    TEXT_SIZE_XLARGE -> 1.3f
    else -> 1.0f
}

/** Parses "#RRGGBB" from BrandConfig; falls back to the default accent on bad input. */
fun parseAccent(hex: String?): Color = runCatching {
    val v = hex!!.removePrefix("#").toLong(16)
    Color(0xFF000000 or v)
}.getOrDefault(OmniColors().accent)

@Composable
fun OmniverseTheme(
    accent: Color = OmniColors().accent,
    publicBuild: Boolean = false,
    textScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val scale = if (textScale.isFinite() && textScale > 0f) textScale else 1f
    val colors = OmniColors(accent = accent)
    val scheme = darkColorScheme(
        primary = accent,
        background = colors.background,
        surface = colors.surface,
        onSurface = colors.textPrimary,
        onBackground = colors.textPrimary,
    )
    CompositionLocalProvider(
        LocalOmniColors provides colors,
        LocalOmniType provides OmniType().scaled(scale),
        LocalTextScale provides scale,
        LocalPublicBuild provides publicBuild,
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** Re-themes a subtree (e.g. a service-branded destination) without touching typography. */
@Composable
fun ProvideOmniColors(colors: OmniColors, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalOmniColors provides colors, content = content)
}
