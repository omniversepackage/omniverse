package com.yodesla.omniverse.designsystem

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * PLAN.md §8.2 — the ONLY place colors, type, spacing and motion values are defined.
 * Feature code reads them via OmniTheme.* — never literal values.
 */

@Immutable
data class OmniColors(
    /** "Midnight Cosmos": almost-black navy gives the atmosphere depth without looking blue. */
    val background: Color = Color(0xFF070B14),
    val surface: Color = Color(0xFF111927),
    val elevated: Color = Color(0xFF1B2535),
    /** Soft ice-white remains comfortable at TV distance. */
    val textPrimary: Color = Color(0xFFF1F3F8),
    val textSecondary: Color = Color(0xFFBBC4D1),
    /** Still >= 4.5:1 on background. */
    val textTertiary: Color = Color(0xFF9EAABC),
    /** Brand accent (champagne; from BrandConfig). Sparingly: focus glow, progress, now-marker. */
    val accent: Color = Color(0xFFD9B97A),
    val live: Color = Color(0xFFE5484D),
    val scrim: Color = Color(0xE6070B14),
    /** Frosted-glass fill and hairline borders for chrome that floats over artwork. */
    val glass: Color = Color(0x14FFFFFF),
    val hairline: Color = Color(0x0FFFFFFF),
    /** HaloWars2.com-inspired layered midnight glass (no runtime background blur on TV). */
    val glassTop: Color = Color(0xCC122032),
    val glassBottom: Color = Color(0x94101A2A),
    val glassFocusTop: Color = Color(0xE6215F84),
    val glassFocusBottom: Color = Color(0xC2101E31),
    val glassEdge: Color = Color(0x2EFFFFFF),
    val glassGlint: Color = Color(0x2EFFFFFF),
    val glassBlueSheen: Color = Color(0x183AC4F4),
    val glassWineSheen: Color = Color(0x207A243E),
)

/** Instrument Serif (SIL OFL 1.1, InstrumentSerif-OFL.txt): titles and headings only. */
val InstrumentSerif = FontFamily(
    Font(R.font.instrument_serif_regular, FontWeight.Normal),
    Font(R.font.instrument_serif_italic, FontWeight.Normal, androidx.compose.ui.text.font.FontStyle.Italic),
)

/** Manrope (SIL OFL 1.1, Manrope-OFL.txt). Static cuts of the variable font: minSdk 23. */
val Manrope = FontFamily(
    Font(R.font.manrope_regular, FontWeight.Normal),
    Font(R.font.manrope_medium, FontWeight.Medium),
    Font(R.font.manrope_semibold, FontWeight.SemiBold),
    Font(R.font.manrope_bold, FontWeight.Bold),
)

/** The serif's ascent/descent is generous: trim it so titles sit tight to the text around them. */
private val SerifTrim = androidx.compose.ui.text.style.LineHeightStyle(
    alignment = androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
    trim = androidx.compose.ui.text.style.LineHeightStyle.Trim.Both,
)

/*
 * Sizes are dp-based sp on a 960 dp-wide TV canvas (1080p = 2x). Premium TV apps use a calm
 * scale: big serif moments, small confident UI text. body 14 sp = 28 px at 1080p.
 */
@Immutable
data class OmniType(
    val family: FontFamily = Manrope,
    val serif: FontFamily = InstrumentSerif,
    /** Featured title over artwork (Home hero, detail page). */
    val hero: TextStyle = TextStyle(fontFamily = serif, fontWeight = FontWeight.Normal, fontSize = 64.sp, lineHeight = 62.sp, letterSpacing = (-0.5).sp, lineHeightStyle = SerifTrim),
    /** Precision-cut sans hero for browsing surfaces; tighter and more legible at TV distance. */
    val browseHero: TextStyle = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 40.sp, lineHeight = 44.sp, letterSpacing = (-1.0).sp),
    /** Screen titles ("Live", "Movies") and big programme names. */
    val display: TextStyle = TextStyle(fontFamily = serif, fontWeight = FontWeight.Normal, fontSize = 40.sp, lineHeight = 42.sp, lineHeightStyle = SerifTrim),
    val headline: TextStyle = TextStyle(fontFamily = serif, fontWeight = FontWeight.Normal, fontSize = 28.sp, lineHeight = 32.sp, lineHeightStyle = SerifTrim),
    val browseHeading: TextStyle = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.4).sp),
    /** Row headers, card titles, channel names. */
    val title: TextStyle = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    val body: TextStyle = TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp),
    /** Never go below this on TV (PLAN.md §8.2). Task 69: the floor is 14 sp, so the smallest styles meet it. */
    val caption: TextStyle = TextStyle(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    /** Small tracked caps above a title: "NEW THIS WEEK", "NOW · 8:00 – 9:45 PM". */
    val overline: TextStyle = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 2.2.sp),
    /** Times, channel numbers, durations: tabular figures so digits don't jiggle. */
    val numeric: TextStyle = TextStyle(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = 14.sp, fontFeatureSettings = "tnum"),
) {
    /**
     * Task 84: scales every text size (and its line height / tracking) by [scale]. Only text moves —
     * spacing, dp sizes and the density are untouched, so posters and layout keep their proportions.
     */
    fun scaled(scale: Float): OmniType = if (scale == 1f) this else OmniType(
        family = family, serif = serif,
        hero = hero.scaled(scale),
        browseHero = browseHero.scaled(scale),
        display = display.scaled(scale),
        headline = headline.scaled(scale),
        browseHeading = browseHeading.scaled(scale),
        title = title.scaled(scale),
        body = body.scaled(scale),
        caption = caption.scaled(scale),
        overline = overline.scaled(scale),
        numeric = numeric.scaled(scale),
    )
}

/** Scales a style's font size, line height and letter spacing; leaves an unspecified line height alone. */
private fun TextStyle.scaled(scale: Float): TextStyle = copy(
    fontSize = fontSize * scale,
    lineHeight = if (lineHeight == TextUnit.Unspecified) TextUnit.Unspecified else lineHeight * scale,
    letterSpacing = letterSpacing * scale,
)

object OmniSpacing {
    /** Overscan-safe TV margins. */
    val tvSide = 48.dp
    val tvTopBottom = 27.dp
    val xs = 4.dp
    val s = 8.dp
    val m = 16.dp
    val l = 24.dp
    val xl = 32.dp
    val xxl = 48.dp
    val cardCorner = 10.dp
    /** Buttons and pills. */
    val pill = 26.dp
}

object OmniMotion {
    /** Focus scale for cards (§8.2: 1.06). */
    const val FOCUS_SCALE = 1.06f
    /** Springs only — never linear tweens for focus moves. */
    fun <T> focusSpring() = spring<T>(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)
    const val BACKDROP_CROSSFADE_MS = 350
    const val BACKDROP_DEBOUNCE_MS = 250L
    const val SCREEN_FADE_MS = 220
}

val SingleLine = TextOverflow.Ellipsis
