package com.yodesla.omniverse.feature.vod

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.yodesla.omniverse.designsystem.OmniSpacing
import com.yodesla.omniverse.designsystem.OmniTheme

/**
 * Rotten Tomatoes critics + audience scores with the usual cues: a red tomato (fresh, 60%+) or
 * green splat (rotten) for critics, a popcorn bucket (upright 60%+, tipped over below) for audiences.
 */
@Composable
internal fun RtBadges(s: com.yodesla.omniverse.core.data.metadata.RtScores, modifier: Modifier = Modifier) {
    val t = OmniTheme.type
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        s.critics?.let { v ->
            androidx.compose.foundation.Canvas(Modifier.requiredSize(20.dp)) {
                val fresh = v >= 60
                val body = if (fresh) Color(0xFFFA320A) else Color(0xFF0AC855)
                drawCircle(body, radius = size.minDimension * 0.42f, center = center.copy(y = size.height * 0.56f))
                if (fresh) drawOval(Color(0xFF00912D), topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.3f, size.height * 0.04f),
                    size = androidx.compose.ui.geometry.Size(size.width * 0.4f, size.height * 0.22f))
            }
            Spacer(Modifier.width(6.dp))
            Text("$v%", style = t.body.copy(fontWeight = FontWeight.Bold), color = Color.White)
            Spacer(Modifier.width(OmniSpacing.m))
        }
        s.audience?.let { v ->
            androidx.compose.foundation.Canvas(Modifier.requiredSize(20.dp).graphicsLayer { rotationZ = if (v >= 60) 0f else -35f }) {
                val w = size.width; val h = size.height
                // Popcorn on top, red-and-white striped bucket below.
                drawCircle(Color(0xFFFFE9A8), radius = w * 0.2f, center = androidx.compose.ui.geometry.Offset(w * 0.32f, h * 0.3f))
                drawCircle(Color(0xFFFFE9A8), radius = w * 0.2f, center = androidx.compose.ui.geometry.Offset(w * 0.68f, h * 0.3f))
                drawCircle(Color(0xFFFFF4D0), radius = w * 0.2f, center = androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.2f))
                val bucket = androidx.compose.ui.graphics.Path().apply {
                    moveTo(w * 0.12f, h * 0.4f); lineTo(w * 0.88f, h * 0.4f); lineTo(w * 0.76f, h); lineTo(w * 0.24f, h); close()
                }
                drawPath(bucket, Color(0xFFFA320A))
                drawRect(Color.White, topLeft = androidx.compose.ui.geometry.Offset(w * 0.44f, h * 0.4f), size = androidx.compose.ui.geometry.Size(w * 0.12f, h * 0.6f))
            }
            Spacer(Modifier.width(6.dp))
            Text("$v%", style = t.body.copy(fontWeight = FontWeight.Bold), color = Color.White)
        }
    }
}
