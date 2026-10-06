package com.yodesla.omniverse.designsystem

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * A service destination's look: its own stage (backdrop) and colour system, so a Netflix
 * category feels like Netflix, Disney+ like Disney+, and so on. Purely local styling over our
 * own UI (no third-party assets beyond the bundled category marks).
 */
private data class BrandLook(
    /** Base page colour. */
    val base: Color,
    /** Secondary stop of the stage gradient. */
    val baseLow: Color,
    /** Signature light source on the stage (radial glow). */
    val glow: Color,
    val glowCenter: Offset,
    val accent: Color,
    val surface: Color,
    val elevated: Color,
    val focusTop: Color,
    val focusBottom: Color,
    val textSecondary: Color = Color(0xFFB3B3B3),
)

private fun brandLook(brand: CategoryBrand): BrandLook = when (brand) {
    // Netflix: true black stage, single red light, red accents, neutral greys.
    CategoryBrand.NETFLIX -> BrandLook(
        base = Color(0xFF000000), baseLow = Color(0xFF141414), glow = Color(0x66E50914), glowCenter = Offset(0.05f, 0f),
        accent = Color(0xFFE50914), surface = Color(0xFF181818), elevated = Color(0xFF232323),
        focusTop = Color(0xF2541015), focusBottom = Color(0xE6181818),
    )
    // Disney+: deep navy with the soft blue "arc" light at the top, bright royal blue accent.
    CategoryBrand.DISNEY_PLUS -> BrandLook(
        base = Color(0xFF040714), baseLow = Color(0xFF1A1D29), glow = Color(0x803A6DF0), glowCenter = Offset(0.5f, -0.15f),
        accent = Color(0xFF0072D2), surface = Color(0xFF192133), elevated = Color(0xFF263048),
        focusTop = Color(0xF21E3F8A), focusBottom = Color(0xE6151C2E), textSecondary = Color(0xFFCACACA),
    )
    // Prime Video: slate-navy page and Prime's cyan.
    CategoryBrand.PRIME_VIDEO -> BrandLook(
        base = Color(0xFF0F171E), baseLow = Color(0xFF1B2530), glow = Color(0x5500A8E1), glowCenter = Offset(0.9f, 0f),
        accent = Color(0xFF00A8E1), surface = Color(0xFF1A242F), elevated = Color(0xFF25313D),
        focusTop = Color(0xF2135B7A), focusBottom = Color(0xE61A242F), textSecondary = Color(0xFFAAB7C4),
    )
    // Apple TV+: graphite black, white light, monochrome accents.
    CategoryBrand.APPLE_TV_PLUS -> BrandLook(
        base = Color(0xFF000000), baseLow = Color(0xFF1C1C1E), glow = Color(0x33FFFFFF), glowCenter = Offset(0.5f, -0.2f),
        accent = Color(0xFFF5F5F7), surface = Color(0xFF1C1C1E), elevated = Color(0xFF2C2C2E),
        focusTop = Color(0xF24A4A4E), focusBottom = Color(0xE62C2C2E), textSecondary = Color(0xFFA1A1A6),
    )
    // Hulu: near-black with Hulu green.
    CategoryBrand.HULU -> BrandLook(
        base = Color(0xFF0B0C0F), baseLow = Color(0xFF16181D), glow = Color(0x4D1CE783), glowCenter = Offset(0.1f, 0f),
        accent = Color(0xFF1CE783), surface = Color(0xFF181A20), elevated = Color(0xFF22252D),
        focusTop = Color(0xF2135E3A), focusBottom = Color(0xE6181A20), textSecondary = Color(0xFFB4B8C0),
    )
    // Max: midnight blue-to-violet stage with Max's electric blue.
    CategoryBrand.MAX -> BrandLook(
        base = Color(0xFF05061A), baseLow = Color(0xFF14123A), glow = Color(0x80002BE7), glowCenter = Offset(0.75f, -0.1f),
        accent = Color(0xFF4D7CFF), surface = Color(0xFF15173A), elevated = Color(0xFF20234F),
        focusTop = Color(0xF2263AA6), focusBottom = Color(0xE615173A), textSecondary = Color(0xFFBCC0E0),
    )
    // Paramount+: deep cobalt stage with the mountain-blue light from the top right.
    CategoryBrand.PARAMOUNT_PLUS -> BrandLook(
        base = Color(0xFF020A1F), baseLow = Color(0xFF0A1C4A), glow = Color(0x800064FF), glowCenter = Offset(0.85f, -0.15f),
        accent = Color(0xFF2E7BFF), surface = Color(0xFF0F1E44), elevated = Color(0xFF182B5C),
        focusTop = Color(0xF21F4FB8), focusBottom = Color(0xE60F1E44), textSecondary = Color(0xFFB9C6E6),
    )
    // Peacock: pure black, a warm gold light, white chrome (the colour lives in the dots).
    CategoryBrand.PEACOCK -> BrandLook(
        base = Color(0xFF000000), baseLow = Color(0xFF111111), glow = Color(0x40FCCC12), glowCenter = Offset(0.1f, -0.1f),
        accent = Color(0xFFFCCC12), surface = Color(0xFF161616), elevated = Color(0xFF222222),
        focusTop = Color(0xF25A4A12), focusBottom = Color(0xE6161616), textSecondary = Color(0xFFB8B8B8),
    )
    // Crunchyroll: charcoal page with Crunchyroll orange.
    CategoryBrand.CRUNCHYROLL -> BrandLook(
        base = Color(0xFF000000), baseLow = Color(0xFF141519), glow = Color(0x59F47521), glowCenter = Offset(0.05f, 0f),
        accent = Color(0xFFF47521), surface = Color(0xFF141519), elevated = Color(0xFF23252B),
        focusTop = Color(0xF2733512), focusBottom = Color(0xE6141519), textSecondary = Color(0xFFA0A0A0),
    )
}

/**
 * Page stage for a browse screen. Unrecognized categories keep the Omniverse cosmic backdrop
 * and colours; a service category swaps in that service's stage and colour system for every
 * child (focus glow, selection, progress, glass cards), cross-fading on change.
 */
@Composable
fun BrandStage(categoryName: String, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val brand = categoryBrand(categoryName)
    val base = OmniTheme.colors
    // ONLY the backdrop cross-fades. The content stays in one stable slot: putting it inside the
    // Crossfade recreated the whole screen on every brand change (Live TV: dwelling on "EN✦ Hulu"
    // rebuilt the page mid-scroll, showed both copies during the fade, and reset the category list
    // to the top — Kory, 2026-10-03).
    val colors = brand?.let { b ->
        val look = brandLook(b)
        base.copy(
            background = look.base, surface = look.surface, elevated = look.elevated,
            accent = look.accent, textSecondary = look.textSecondary,
            scrim = look.base.copy(alpha = 0.9f),
            glassTop = look.surface.copy(alpha = 0.86f), glassBottom = look.base.copy(alpha = 0.7f),
            glassFocusTop = look.focusTop, glassFocusBottom = look.focusBottom,
            glassBlueSheen = look.accent.copy(alpha = 0.10f), glassWineSheen = Color.Transparent,
        )
    } ?: base
    Box(modifier) {
        Crossfade(brand, animationSpec = tween(OmniMotion.BACKDROP_CROSSFADE_MS), label = "brandStage", modifier = Modifier.matchParentSize()) { b ->
            if (b == null) {
                CosmicBackdrop(Modifier.fillMaxSize()) {}
            } else {
                val look = brandLook(b)
                Box(
                    Modifier.fillMaxSize()
                        .background(Brush.verticalGradient(listOf(look.baseLow, look.base, look.base)))
                        .background(BrandGlow(look.glow, look.glowCenter)),
                )
            }
        }
        ProvideOmniColors(colors) {
            Box(Modifier.fillMaxSize(), content = content)
        }
    }
}

/** One large soft light placed in relative coordinates (works at any screen size). */
private class BrandGlow(private val color: Color, private val center: Offset) : androidx.compose.ui.graphics.ShaderBrush() {
    override fun createShader(size: androidx.compose.ui.geometry.Size): androidx.compose.ui.graphics.Shader =
        androidx.compose.ui.graphics.RadialGradientShader(
            center = Offset(size.width * center.x, size.height * center.y),
            radius = maxOf(size.width, size.height) * 0.75f,
            colors = listOf(color, color.copy(alpha = 0f)),
        )
}
