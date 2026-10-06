package com.yodesla.omniverse.designsystem

import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Star
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxWidth as fillW
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import coil3.compose.SubcomposeAsyncImage
import kotlinx.coroutines.delay
import java.util.Calendar
import java.util.Locale

// ---------------------------------------------------------------- Monogram / logos

private val MonogramPalette = listOf(
    Color(0xFF2B2F3A), Color(0xFF33302A), Color(0xFF2A3431), Color(0xFF362A30),
    Color(0xFF2C3036), Color(0xFF30342A), Color(0xFF2E2A36), Color(0xFF363029),
)

private val prefixNoise = Regex("^(\\[[^]]*]|\\([^)]*\\)|[A-Z]{2,3}\\s*[:|]\\s*)+")

/** Up to two initials from the first two words, ignoring country prefixes like "US:" or "[HD]". */
internal fun initials(name: String): String {
    val cleaned = name.replace(prefixNoise, "").trim()
    val words = cleaned.split(Regex("\\s+")).filter { w -> w.any { it.isLetterOrDigit() } }
    if (words.isEmpty()) return "?"
    return words.take(2).joinToString("") { w -> w.first { it.isLetterOrDigit() }.uppercase() }
}

internal fun paletteIndex(name: String): Int = Math.floorMod(name.hashCode(), MonogramPalette.size)

/** Cinematic hues (teal, ember, indigo, olive, burgundy, steel, amber, violet) for big artless frames. */
private val ChannelTints = listOf(
    Color(0xFF1F5563), Color(0xFF7A2E1A), Color(0xFF34307A), Color(0xFF4A5A28),
    Color(0xFF6B2238), Color(0xFF3A4A60), Color(0xFF7A5418), Color(0xFF52307A),
)

/** A stable colour per channel: tints the preview frame when there is no artwork. */
fun channelTint(name: String): Color = ChannelTints[Math.floorMod(name.hashCode(), ChannelTints.size)]

@Composable
fun Monogram(name: String, modifier: Modifier = Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(8.dp)).background(MonogramPalette[paletteIndex(name)]),
        contentAlignment = Alignment.Center,
    ) {
        Text(initials(name), style = OmniTheme.type.title, color = OmniTheme.colors.textPrimary, maxLines = 1)
    }
}

/** Channel logo on a subtle backdrop; placeholder/error/no-url → Monogram. Never blocks layout. */
@Composable
fun LogoImage(url: String?, name: String, modifier: Modifier = Modifier, plain: Boolean = false) {
    if (url.isNullOrBlank()) {
        Monogram(name, modifier)
        return
    }
    // plain = no tile behind the logo (it sits on artwork or a tinted frame).
    Box(if (plain) modifier else modifier.clip(RoundedCornerShape(8.dp)).background(OmniTheme.colors.elevated)) {
        SubcomposeAsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            loading = { Monogram(name, Modifier.fillMaxSize()) },
            error = { Monogram(name, Modifier.fillMaxSize()) },
            modifier = Modifier.fillMaxSize().padding(6.dp),
        )
    }
}

// ---------------------------------------------------------------- Progress / skeleton / error

@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier) {
    val c = OmniTheme.colors
    Box(modifier.height(3.dp).clip(RoundedCornerShape(2.dp)).background(c.textTertiary.copy(alpha = 0.3f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(c.accent))
    }
}

@Composable
fun SkeletonBox(modifier: Modifier = Modifier, shimmer: Boolean = true) {
    val base = OmniTheme.colors.surface
    if (!shimmer) {
        Box(modifier.clip(RoundedCornerShape(OmniSpacing.cardCorner)).background(base))
        return
    }
    val t by rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = -1f, targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1_200, easing = LinearEasing), RepeatMode.Restart), label = "x",
    )
    val hi = OmniTheme.colors.elevated
    Box(
        modifier.clip(RoundedCornerShape(OmniSpacing.cardCorner)).background(
            Brush.linearGradient(
                listOf(base, hi, base),
                start = androidx.compose.ui.geometry.Offset(t * 600f, 0f),
                end = androidx.compose.ui.geometry.Offset(t * 600f + 600f, 0f),
            ),
        ),
    )
}

@Composable
fun SkeletonChannelRow(modifier: Modifier = Modifier, shimmer: Boolean = LocalVisualTier.current == VisualTier.CINEMATIC) {
    SkeletonBox(modifier.fillW().height(CHANNEL_ROW_HEIGHT), shimmer)
}

@Composable
fun ErrorCard(
    title: String,
    message: String,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val c = OmniTheme.colors
    val focus = remember { FocusRequester() }
    Column(
        modifier.widthIn(max = 640.dp).clip(RoundedCornerShape(OmniSpacing.cardCorner)).background(c.elevated).padding(OmniSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(OmniSpacing.s),
    ) {
        Text(title, style = OmniTheme.type.headline, color = c.textPrimary, textAlign = TextAlign.Center)
        Text(message, style = OmniTheme.type.body, color = c.textSecondary, textAlign = TextAlign.Center)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(OmniSpacing.s))
            OmniButton(actionLabel, onAction, Modifier.focusRequester(focus), primary = true)
            LaunchedEffect(Unit) { focus.requestFocusWhenReady() }
        }
    }
}

// ---------------------------------------------------------------- Channel row

val CHANNEL_ROW_HEIGHT = 76.dp

@Immutable
data class ChannelRowUi(
    val id: String,
    val number: Int?,
    val name: String,
    val logoUrl: String?,
    val nowTitle: String?,
    val nowProgress: Float?,
    val nextTitle: String?,
    val nextStart: String?,
    val isFavorite: Boolean,
    val hasCatchup: Boolean,
    val isPlaying: Boolean,
)

@Composable
fun ChannelRow(
    ui: ChannelRowUi,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    /** Off when a preview pane beside the list already shows what's next. */
    showNext: Boolean = true,
) {
    val c = OmniTheme.colors
    val t = OmniTheme.type
    FocusCard(onClick = onClick, onLongClick = onLongClick, modifier = modifier.fillW().height(CHANNEL_ROW_HEIGHT)) {
        Row(Modifier.fillMaxSize().padding(horizontal = OmniSpacing.m), verticalAlignment = Alignment.CenterVertically) {
            Text(
                ui.number?.toString() ?: "", style = t.numeric, color = c.textTertiary,
                textAlign = TextAlign.End,
                modifier = Modifier.widthIn(min = if (showNext) 32.dp else 24.dp), maxLines = 1,
            )
            Spacer(Modifier.width(OmniSpacing.s))
            LogoImage(ui.logoUrl, ui.name, Modifier.size(if (showNext) 72.dp else 52.dp, if (showNext) 40.dp else 32.dp))
            Spacer(Modifier.width(if (showNext) OmniSpacing.m else OmniSpacing.s))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (ui.isPlaying) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(c.accent))
                        Spacer(Modifier.width(OmniSpacing.s))
                    }
                    Text(ui.name, style = t.title, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (ui.isFavorite) {
                        Spacer(Modifier.width(OmniSpacing.s))
                        androidx.tv.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Star, contentDescription = "Favourite", tint = c.accent, modifier = Modifier.size(16.dp))
                    }
                    if (ui.hasCatchup) {
                        Spacer(Modifier.width(OmniSpacing.s))
                        // The app fonts have no ⟲ glyph; a font fallback renders as a stray box on some boxes.
                        androidx.tv.material3.Icon(
                            androidx.compose.material.icons.Icons.Rounded.History, contentDescription = "Catch-up",
                            tint = c.textTertiary, modifier = Modifier.size(18.dp),
                        )
                    }
                }
                if (ui.nowTitle != null) {
                    Text(ui.nowTitle, style = t.body, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (ui.nowProgress != null) {
                    Spacer(Modifier.height(4.dp))
                    ProgressLine(ui.nowProgress, Modifier.fillW(0.7f))
                }
            }
            if (showNext && ui.nextTitle != null) {
                Spacer(Modifier.width(OmniSpacing.m))
                Text(
                    "Next · ${ui.nextStart ?: ""} · ${ui.nextTitle}", style = t.caption, color = c.textTertiary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(0.4f),
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Visual tier & clock

enum class VisualTier { CINEMATIC, LITE }

val LocalVisualTier = staticCompositionLocalOf { VisualTier.CINEMATIC }

/** Pure decision (the Android probe lives in the app module: rememberVisualTier()). */
fun decideTier(lowRam: Boolean, memoryClassMb: Int, sdk: Int): VisualTier =
    if (lowRam || memoryClassMb <= 192 || sdk < 28) VisualTier.LITE else VisualTier.CINEMATIC

/**
 * One time style for the whole app (clock, guide, banners). The app sets [use24h] from the
 * device's 12/24-hour setting at startup; everything else just calls [format].
 */
object ClockFormat {
    @Volatile var use24h: Boolean = false

    fun format(ms: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        val h = c.get(Calendar.HOUR_OF_DAY)
        val m = c.get(Calendar.MINUTE)
        return if (use24h) String.format(Locale.ROOT, "%02d:%02d", h, m)
        else String.format(Locale.ROOT, "%d:%02d %s", if (h % 12 == 0) 12 else h % 12, m, if (h < 12) "AM" else "PM")
    }
}

/** Current time in [ClockFormat], updated exactly at each minute boundary (no per-second polling). */
@Composable
fun rememberTimeText(pattern24h: Boolean = ClockFormat.use24h): String {
    fun now(): String = ClockFormat.format(System.currentTimeMillis())
    var text by remember { mutableStateOf(now()) }
    LaunchedEffect(pattern24h) {
        while (true) {
            val c = Calendar.getInstance()
            val msToNextMinute = 60_000L - (c.get(Calendar.SECOND) * 1_000L + c.get(Calendar.MILLISECOND))
            delay(msToNextMinute + 50)
            text = now()
        }
    }
    return text
}

/**
 * Task 69: "Hold OK for more options" — caption style, tertiary colour, fades out after 6 s.
 * The parent decides whether to render it at all (the app gates it to the first three launches
 * via the hint_hold_ok_seen setting); rendering it once per composition means once per screen session.
 */
@Composable
fun HoldOkHint(modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { delay(6_000); visible = false }
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        enter = androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.fadeOut(tween(600)),
        modifier = modifier,
    ) {
        Text("Hold OK for more options", style = OmniTheme.type.caption, color = OmniTheme.colors.textTertiary)
    }
}

/** First row of a section's category list: opens the app-wide Search without a trip to the rail. */
@Composable
fun SearchEntryCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = OmniTheme.colors
    FocusCard(onClick = onClick, modifier = modifier) {
        androidx.compose.foundation.layout.Row(
            Modifier.fillMaxSize().padding(horizontal = OmniSpacing.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(OmniSpacing.s),
        ) {
            androidx.tv.material3.Icon(androidx.compose.material.icons.Icons.Outlined.Search, contentDescription = null,
                tint = c.textSecondary, modifier = Modifier.size(20.dp))
            androidx.tv.material3.Text("Search", style = OmniTheme.type.body, color = c.textSecondary, maxLines = 1)
        }
    }
}
