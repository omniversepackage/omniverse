package com.yodesla.omniverse.designsystem

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text

/** A descriptive category identity using bundled service marks for recognized labels. */
@Composable
fun CategoryIdentity(name: String, selected: Boolean, modifier: Modifier = Modifier) {
    val brand = categoryBrand(name)
    val colors = OmniTheme.colors
    val label = brand?.let { categorySuffix(name, it) } ?: name
    Row(modifier.then(if (brand != null) Modifier.widthIn(max = 190.dp) else Modifier),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OmniSpacing.s)) {
        if (brand != null) {
            when (val mark = brandMark(brand, OmniTheme.publicBuild)) {
                is BrandMark.Text -> ServiceWordmarkText(mark.label, mark.color, mark.height)
                is BrandMark.Logo -> Box(
                    Modifier
                        .width(mark.boxW)
                        .height(mark.boxH)
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    colors.glassTop.copy(alpha = if (selected) 0.94f else 0.82f),
                                    colors.glassBottom.copy(alpha = if (selected) 0.88f else 0.76f),
                                ),
                            ),
                            RoundedCornerShape(OmniSpacing.cardCorner),
                        )
                        .border(
                            width = 1.dp,
                            brush = Brush.horizontalGradient(
                                listOf(
                                    mark.tint.copy(alpha = if (selected) 0.42f else 0.20f),
                                    colors.glassEdge,
                                    colors.glassGlint.copy(alpha = if (selected) 0.72f else 0.46f),
                                ),
                            ),
                            shape = RoundedCornerShape(OmniSpacing.cardCorner),
                        )
                        .padding(horizontal = OmniSpacing.xs, vertical = 2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(mark.res),
                        contentDescription = name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        if (label.isNotEmpty()) Text(
            label,
            modifier = if (brand != null) Modifier.weight(1f) else Modifier.widthIn(max = 165.dp),
            style = OmniTheme.type.body.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
            color = if (selected) colors.textPrimary else colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Accent color for a recognized brand category; null for unrecognized and special categories. */
fun categoryAccent(name: String): Color? = categoryBrand(name)?.let { brandIdentity(it).tint }

/** Quiet accent for a selected destination; original Omniverse chrome stays consistent. */
@Composable
fun CategoryDestinationAccent(name: String, modifier: Modifier = Modifier) {
    val tint = categoryAccent(name) ?: return
    Box(modifier.background(tint.copy(alpha = 0.74f), RoundedCornerShape(2.dp)))
}

/** Bundled single-color lockup + the existing chip/text accent for a brand. */
private data class BrandIdentity(val res: Int, val boxW: Dp, val boxH: Dp, val tint: Color)

/** The bundled mark already names the service; leave scarce TV-rail width for "Movies", "Shows", etc. */
private fun categorySuffix(name: String, brand: CategoryBrand): String {
    val prefix = when (brand) {
        CategoryBrand.NETFLIX -> Regex("^netflix\\b", RegexOption.IGNORE_CASE)
        CategoryBrand.DISNEY_PLUS -> Regex("^disney\\s*\\+", RegexOption.IGNORE_CASE)
        CategoryBrand.PRIME_VIDEO -> Regex("^prime\\s+video\\b", RegexOption.IGNORE_CASE)
        CategoryBrand.APPLE_TV_PLUS -> Regex("^apple\\s+tv\\s*\\+", RegexOption.IGNORE_CASE)
        CategoryBrand.HULU -> Regex("^hulu\\b", RegexOption.IGNORE_CASE)
        CategoryBrand.MAX -> Regex("^(?:hbo\\s+)?max\\b", RegexOption.IGNORE_CASE)
        CategoryBrand.PARAMOUNT_PLUS -> Regex("^paramount\\s*(?:\\+|plus\\b)?", RegexOption.IGNORE_CASE)
        CategoryBrand.PEACOCK -> Regex("^peacock\\b", RegexOption.IGNORE_CASE)
        CategoryBrand.CRUNCHYROLL -> Regex("^crunchyroll\\b", RegexOption.IGNORE_CASE)
    }
    return name.trim().replaceFirst(prefix, "").trim(' ', '-', '–', '|', ':', '·')
        .ifBlank { if (prefix.containsMatchIn(name.trim())) "" else name }
}

/** Real brand lockups live in res/drawable; tint keeps the original per-brand accent. */
private fun brandIdentity(brand: CategoryBrand): BrandIdentity = when (brand) {
    CategoryBrand.NETFLIX -> BrandIdentity(R.drawable.brand_category_netflix, 74.dp, 29.dp, Color(0xFFE50914))
    CategoryBrand.DISNEY_PLUS -> BrandIdentity(R.drawable.brand_category_disneyplus, 72.dp, 39.dp, Color(0xFF7CAAF6))
    CategoryBrand.PRIME_VIDEO -> BrandIdentity(R.drawable.brand_category_primevideo, 29.dp, 29.dp, Color(0xFF50B9E9))
    CategoryBrand.APPLE_TV_PLUS -> BrandIdentity(R.drawable.brand_category_appletv, 58.dp, 29.dp, Color(0xFFE6EAF0))
    CategoryBrand.HULU -> BrandIdentity(R.drawable.brand_category_hulu, 64.dp, 21.dp, Color(0xFF6CE4A4))
    CategoryBrand.MAX -> BrandIdentity(R.drawable.brand_category_max, 48.dp, 35.dp, Color(0xFFE5EAF3))
    CategoryBrand.PARAMOUNT_PLUS -> BrandIdentity(R.drawable.brand_category_paramountplus, 84.dp, 26.dp, Color(0xFF4F8BFF))
    CategoryBrand.PEACOCK -> BrandIdentity(R.drawable.brand_category_peacock, 76.dp, 26.dp, Color(0xFFFCCC12))
    CategoryBrand.CRUNCHYROLL -> BrandIdentity(R.drawable.brand_category_crunchyroll, 96.dp, 22.dp, Color(0xFFF47521))
}

/** What to draw for a brand's wordmark: the bundled logo, or a text mark (public builds). */
sealed interface BrandMark {
    /** Bundled single-color vector lockup + its intrinsic box. */
    data class Logo(val res: Int, val boxW: Dp, val boxH: Dp, val tint: Color) : BrandMark
    /** Service name set in the brand colour — used when the logo artwork can't ship. */
    data class Text(val label: String, val color: Color, val height: Dp) : BrandMark
}

/**
 * The single decision every wordmark surface makes: public builds never render the third-party
 * logo vectors, only a text mark in the same brand colour (colours and layout stay unchanged).
 */
fun brandMark(brand: CategoryBrand, publicBuild: Boolean): BrandMark {
    val identity = brandIdentity(brand)
    return if (publicBuild) BrandMark.Text(brandDisplayName(brand), identity.tint, identity.boxH)
    else BrandMark.Logo(identity.res, identity.boxW, identity.boxH, identity.tint)
}

/** The service's own name, as it appears in its wordmark (the public-build text mark). */
fun brandDisplayName(brand: CategoryBrand): String = when (brand) {
    CategoryBrand.NETFLIX -> "Netflix"
    CategoryBrand.DISNEY_PLUS -> "Disney+"
    CategoryBrand.PRIME_VIDEO -> "Prime Video"
    CategoryBrand.APPLE_TV_PLUS -> "Apple TV+"
    CategoryBrand.HULU -> "Hulu"
    CategoryBrand.MAX -> "Max"
    CategoryBrand.PARAMOUNT_PLUS -> "Paramount+"
    CategoryBrand.PEACOCK -> "Peacock"
    CategoryBrand.CRUNCHYROLL -> "Crunchyroll"
}

/** The brand name set bold in its colour, sized to the height the logo would have occupied. */
@Composable
private fun ServiceWordmarkText(label: String, color: Color, height: Dp, modifier: Modifier = Modifier) {
    val fontSize = with(LocalDensity.current) { height.toSp() }
    Text(
        label,
        modifier = modifier,
        style = OmniTheme.type.body.copy(fontWeight = FontWeight.Bold, fontSize = fontSize, lineHeight = fontSize),
        color = color,
        maxLines = 1,
    )
}

/** The service's wordmark at a given height, with no plate (for in-app chrome). */
@Composable
fun BrandWordmark(brand: CategoryBrand, height: Dp, modifier: Modifier = Modifier) {
    when (val mark = brandMark(brand, OmniTheme.publicBuild)) {
        is BrandMark.Text -> ServiceWordmarkText(mark.label, mark.color, height, modifier)
        is BrandMark.Logo -> Image(painterResource(mark.res), contentDescription = brand.name, contentScale = ContentScale.Fit,
            modifier = modifier.height(height).width(height * (mark.boxW / mark.boxH)))
    }
}

/**
 * Header lockup for a selected service destination: the bundled mark at hero size on its
 * tinted glass plate, followed by the category suffix ("Movies", "Kids"...). Renders
 * nothing for unrecognized categories so the plain heading stays as-is.
 */
@Composable
fun CategoryDestinationHeader(name: String, modifier: Modifier = Modifier, scale: Float = HEADER_SCALE) {
    val brand = categoryBrand(name) ?: return
    val identity = brandIdentity(brand)
    val label = categorySuffix(name, brand)
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m)) {
        if (OmniTheme.publicBuild) {
            ServiceWordmarkText(brandDisplayName(brand), identity.tint, identity.boxH * scale)
        } else {
            Box(
                Modifier
                    .width(identity.boxW * scale)
                    .height(identity.boxH * scale)
                    .background(identity.tint.copy(alpha = 0.18f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                contentAlignment = Alignment.Center,
            ) {
                Image(painterResource(identity.res), contentDescription = name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        }
        if (label.isNotEmpty()) Text(
            label,
            style = OmniTheme.type.title,
            color = identity.tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 280.dp),
        )
    }
}

private const val HEADER_SCALE = 1.35f

/** Persistent selection glow for a rail row: brand-tinted wash plus a leading edge bar. */
@Composable
fun CategorySelectionGlow(name: String, modifier: Modifier = Modifier) {
    val tint = categoryAccent(name) ?: OmniTheme.colors.accent
    Box(modifier.background(Brush.horizontalGradient(0f to tint.copy(alpha = 0.26f), 1f to tint.copy(alpha = 0.04f)))) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(tint.copy(alpha = 0.9f)))
    }
}
