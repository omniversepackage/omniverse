package com.yodesla.omniverse.feature.live.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.yodesla.omniverse.designsystem.OmniTheme
import com.yodesla.omniverse.player.EngineSurface
import com.yodesla.omniverse.player.Media3ExoEngine

/**
 * The live channel keeps playing in a small 16:9 box while the user browses the Guide or the
 * channel list (Back from fullscreen). Not focusable: focus stays in the grid/list; picking any
 * channel (including this one) goes fullscreen on the same engine, so there's no reload.
 */
@Composable
fun MiniLivePlayer(engine: Media3ExoEngine, channel: BannerUi?, modifier: Modifier = Modifier) {
    val c = OmniTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .aspectRatio(16f / 9f)
            .clip(shape)
            .background(Color.Black)
            .border(1.dp, c.accent.copy(alpha = 0.45f), shape),
    ) {
        EngineSurface(engine, Modifier.fillMaxSize())
        Row(
            Modifier.align(Alignment.TopStart).padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.88f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(c.live))
            Text("LIVE", style = OmniTheme.type.overline, color = Color.White)
        }
        channel?.let { b ->
            Text(
                listOfNotNull(b.number?.toString(), b.name).joinToString("  "),
                style = OmniTheme.type.caption, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f), Color.Black.copy(alpha = 0.95f))))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}
