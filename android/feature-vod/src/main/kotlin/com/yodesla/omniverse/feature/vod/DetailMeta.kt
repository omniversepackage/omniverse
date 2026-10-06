package com.yodesla.omniverse.feature.vod

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.yodesla.omniverse.designsystem.OmniSpacing
import com.yodesla.omniverse.designsystem.OmniTheme

/** "1h 41m" / "41m"; null when the runtime is unknown or under a minute. */
internal fun fmtRuntime(sec: Int?): String? {
    val total = sec ?: 0
    if (total < 60) return null
    val m = total / 60
    return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
}

/** Year · runtime · genre, then the rating badge, then the Rotten Tomatoes cues: one tidy row. */
@Composable
internal fun DetailMetaRow(
    year: Int?,
    runtimeSec: Int?,
    genre: String?,
    rating: Float?,
    rt: com.yodesla.omniverse.core.data.metadata.RtScores?,
    modifier: Modifier = Modifier,
    /** US certification from TMDB (task 84d); omitted from the row when null. */
    certification: String? = null,
) {
    val c = OmniTheme.colors
    val t = OmniTheme.type
    val text = listOfNotNull(year?.toString(), fmtRuntime(runtimeSec), genre?.takeIf { it.isNotBlank() }, certification?.takeIf { it.isNotBlank() }).joinToString("  ·  ")
    val scores = rt?.takeIf { !it.isEmpty }
    if (text.isEmpty() && rating == null && scores == null) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m), verticalAlignment = Alignment.CenterVertically) {
        if (text.isNotEmpty()) Text(text, style = t.body, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        rating?.let { RatingBadge(it) }
        scores?.let { RtBadges(it) }
    }
}

@Composable
private fun RatingBadge(rating: Float) {
    val c = OmniTheme.colors
    Box(Modifier.border(1.dp, c.textTertiary, RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 2.dp)) {
        Text("%.1f/10".format(rating), style = OmniTheme.type.caption, color = c.textSecondary)
    }
}
