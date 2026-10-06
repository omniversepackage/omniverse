package com.yodesla.omniverse.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import coil3.compose.SubcomposeAsyncImage

/**
 * 2:3 poster tile with the signature focus glow. Missing/broken artwork falls back to a
 * typographic card (title on a muted gradient) instead of an empty grey box.
 */
@Composable
fun PosterCard(
    title: String,
    posterUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    progress: Float? = null,
    /** Hold OK: opens the title menu (Resume / Start over / Details / My List / Remove…). */
    onLongClick: (() -> Unit)? = null,
) {
    val c = OmniTheme.colors
    Column(modifier) {
        FocusCard(onClick = onClick, modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f), onLongClick = onLongClick, glass = false) {
            if (posterUrl.isNullOrBlank()) {
                TypographicPoster(title)
            } else {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    // Task 87: a card never needs the provider's full-resolution original. Cards
                    // are ≤ 220.dp, so TMDB artwork is requested at its poster variant; the
                    // request itself is already constraint-sized by Coil for decoding.
                    val model = remember(posterUrl, maxWidth) { tmdbPosterUrlForCard(posterUrl, maxWidth.value.toInt()) }
                    SubcomposeAsyncImage(
                        model = model,
                        contentDescription = title,
                        contentScale = ContentScale.Crop,
                        loading = { PosterPlaceholder(title) },
                        error = { TypographicPoster(title) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            if (progress != null && progress > 0f) {
                ProgressLine(progress, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(OmniSpacing.s))
            }
        }
        Spacer(Modifier.height(OmniSpacing.s))
        Text(title, style = OmniTheme.type.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (subtitle != null) Text(subtitle, style = OmniTheme.type.caption, color = c.textTertiary, maxLines = 1)
    }
}

/**
 * Task 87 loading placeholder: a calm solid tile carrying the title's first letter — never an
 * empty box, never a shimmer. The fill is the title's stable channel hue, darkened to sit close
 * to the card surface so the swap to artwork is quiet.
 */
@Composable
private fun PosterPlaceholder(title: String) {
    val c = OmniTheme.colors
    val letter = title.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() ?: '?'
    Box(
        Modifier.fillMaxSize().background(lerp(channelTint(title), Color.Black, 0.78f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(letter.toString(), style = OmniTheme.type.browseHero, color = c.textSecondary.copy(alpha = 0.55f))
    }
}

@Composable
private fun TypographicPoster(title: String) {
    val c = OmniTheme.colors
    val tint = channelTint(title)
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(lerp(tint, Color.Black, 0.52f), c.surface, c.background))),
    ) {
        Column(Modifier.fillMaxSize().padding(OmniSpacing.m), verticalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween) {
            Box(Modifier.width(28.dp).height(2.dp).background(c.accent))
            Text(title, style = OmniTheme.type.title, color = c.textPrimary, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
    }
}
