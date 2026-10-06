package com.yodesla.omniverse.feature.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.yodesla.omniverse.core.data.ChannelRow
import com.yodesla.omniverse.designsystem.LogoImage
import com.yodesla.omniverse.designsystem.OmniSpacing
import com.yodesla.omniverse.designsystem.OmniTheme
import com.yodesla.omniverse.designsystem.ProgressLine
import com.yodesla.omniverse.designsystem.channelTint
import com.yodesla.omniverse.designsystem.glassSurface

/** A full-height programme-information stage; no second stream is opened. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LivePreviewPane(
    row: ChannelRow?,
    nn: NowNextUi?,
    modifier: Modifier = Modifier,
    /** Still-playing channel (mini player): replaces the header + logo at the top of the pane. */
    video: (@Composable (Modifier) -> Unit)? = null,
) {
    val c = OmniTheme.colors
    val t = OmniTheme.type
    // EPG now/next can arrive during focus movement; fading the entire card on every
    // metadata update made the list feel sluggish and briefly hid the current channel.
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        // While the nav rail is open the pane can shrink to a sliver; its overlines and times
        // then wrap letter by letter. Show nothing rather than a garbled card (keep the
        // mini player, which must not lose its surface).
        if (video == null && maxWidth < 200.dp) return@BoxWithConstraints
        val channel = row
        val info = nn
        val tint = channel?.let { channelTint(it.name) } ?: c.surface
        Column(
            Modifier
                .fillMaxSize()
                .glassSurface(corner = 16.dp, tint = tint)
                .padding(OmniSpacing.l),
        ) {
            if (video != null) {
                video(Modifier.fillMaxWidth())
                Spacer(Modifier.height(OmniSpacing.m))
            } else Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(c.live))
                Spacer(Modifier.width(8.dp))
                Text("LIVE TV", style = t.overline, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.weight(1f))
                Text("ON THIS CHANNEL", style = t.overline, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (channel != null) {
                if (video == null) {
                    Spacer(Modifier.weight(0.8f))
                    Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                        LogoImage(channel.logoUrl, channel.name, Modifier.size(176.dp, 96.dp), plain = true)
                    }
                    Spacer(Modifier.height(OmniSpacing.l))
                } else {
                    Spacer(Modifier.height(OmniSpacing.m))
                }
                Text(
                    listOfNotNull(channel.number?.toString(), channel.name).joinToString("  ·  "),
                    style = t.body, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(OmniSpacing.s))
                Text(
                    info?.nowTitle ?: "No guide information",
                    style = t.headline, color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                if (info?.nowTimes != null) {
                    Spacer(Modifier.height(OmniSpacing.s))
                    Text("NOW  ·  ${info.nowTimes}", style = t.overline, color = c.accent)
                }
                if (info?.nowProgress != null) {
                    Spacer(Modifier.height(OmniSpacing.m))
                    ProgressLine(info.nowProgress, Modifier.fillMaxWidth())
                }
                if (info?.nextTitle != null) {
                    Spacer(Modifier.height(OmniSpacing.l))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(OmniSpacing.s)) {
                        Text("NEXT", style = t.overline, color = c.accent)
                        Text(
                            listOfNotNull(info.nextStart, info.nextTitle).joinToString("  ·  "),
                            style = t.body, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m),
                    maxLines = 2,
                ) {
                    Text("OK  Watch", style = t.caption, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Hold OK  Favorite", style = t.caption, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("→  Guide", style = t.caption, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else {
                Spacer(Modifier.weight(1f))
                Text("Choose a channel", style = t.headline, color = c.textPrimary)
                Spacer(Modifier.height(OmniSpacing.s))
                Text("See what’s on before switching.", style = t.body, color = c.textSecondary)
            }
        }
    }
}
