package com.yodesla.omniverse.feature.home.phoneremote

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.yodesla.omniverse.designsystem.FocusCard
import com.yodesla.omniverse.designsystem.OmniSpacing
import com.yodesla.omniverse.designsystem.OmniTheme

/** Draws a QR matrix as a scannable black-on-white image (with the required 4-module quiet zone). */
@Composable
fun QrImage(matrix: QrMatrix, modifier: Modifier = Modifier) {
    val n = matrix.size
    val quiet = 4
    val total = n + 2 * quiet
    Canvas(modifier) {
        val cell = size.minDimension / total
        drawRect(Color.White, Offset.Zero, Size(size.width, size.height))
        val dark = Color.Black
        for (r in 0 until n) for (c in 0 until n) {
            if (matrix[r, c]) {
                drawRect(dark, Offset((c + quiet) * cell, (r + quiet) * cell), Size(cell, cell))
            }
        }
    }
}

@Composable
private fun TogglePill(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = OmniTheme.colors
    FocusCard(onClick = onClick, modifier = Modifier.height(48.dp)) {
        Row(Modifier.fillMaxSize().padding(horizontal = OmniSpacing.l), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(label, style = OmniTheme.type.body, color = if (selected) c.accent else c.textPrimary)
        }
    }
}

/**
 * The Settings > Phone remote panel: an Off/On toggle (default Off) and, while active, the pairing
 * QR, the short URL, the 6-digit code and a connected indicator. Stateless: takes the controller's
 * state and a toggle callback.
 */
@Composable
fun PhoneRemotePanel(state: PhoneRemoteUiState, onToggle: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val c = OmniTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(OmniSpacing.m)) {
        Text(
            "Use your phone as a remote: search your library and send play and D-pad commands to the TV. " +
                "It only works on your home network, needs the pairing code, and never shows your provider logins.",
            style = OmniTheme.type.caption, color = c.textSecondary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m)) {
            TogglePill("Off", selected = !state.enabled) { onToggle(false) }
            TogglePill("On", selected = state.enabled) { onToggle(true) }
        }
        state.error?.let { Text(it, style = OmniTheme.type.body, color = c.live) }
        if (state.active && state.url != null && state.code != null) {
            val qr = rememberQr(state.url)
            Row(horizontalArrangement = Arrangement.spacedBy(OmniSpacing.l), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                if (qr != null) {
                    Box(Modifier.size(220.dp).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(8.dp)) {
                        QrImage(qr, Modifier.fillMaxSize())
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(OmniSpacing.s)) {
                    Text("Open on your phone", style = OmniTheme.type.overline, color = c.accent)
                    Text(state.url!!, style = OmniTheme.type.body, color = c.textPrimary)
                    Text("Pairing code", style = OmniTheme.type.overline, color = c.accent)
                    Text(state.code!!, style = OmniTheme.type.title, color = c.textPrimary)
                    Text(
                        if (state.connected) "Phone connected" else "Waiting for your phone…",
                        style = OmniTheme.type.caption, color = if (state.connected) c.accent else c.textTertiary,
                    )
                }
            }
        } else if (state.enabled) {
            Spacer(Modifier.height(OmniSpacing.xs))
        }
    }
}

@Composable
private fun rememberQr(url: String): QrMatrix? = androidx.compose.runtime.remember(url) {
    runCatching { QrCode.encode(url) }.getOrNull()
}
