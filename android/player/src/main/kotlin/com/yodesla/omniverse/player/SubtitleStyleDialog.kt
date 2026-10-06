package com.yodesla.omniverse.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import com.yodesla.omniverse.designsystem.OmniButton
import com.yodesla.omniverse.designsystem.OmniSpacing
import com.yodesla.omniverse.designsystem.OmniTheme
import com.yodesla.omniverse.designsystem.glassSurface
import kotlin.math.roundToInt

/**
 * Per-profile subtitle appearance editor (task 84j). Four dimensions, each a segmented row of
 * options; "Default" follows the system caption style for that dimension. [onChange] persists a
 * single key/token pair (the caller owns the settings store). A live preview reflects the choices.
 * Shared by Settings and the players, so it lives in :android:player.
 */
@Composable
fun SubtitleStyleDialog(
    size: String?,
    background: String?,
    color: String?,
    position: String?,
    onChange: (key: String, value: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = OmniTheme.colors
    val t = OmniTheme.type
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .widthIn(max = 760.dp)
                .padding(OmniSpacing.tvSide)
                .glassSurface(corner = 16.dp)
                .padding(OmniSpacing.l),
            verticalArrangement = Arrangement.spacedBy(OmniSpacing.m),
        ) {
            Text("Subtitle style", style = t.headline, color = c.textPrimary)
            SubtitlePreview(size, background, color, position)
            StyleDimensionRow("Size", SUBTITLE_SIZE_OPTIONS, size) { onChange(SUBTITLE_STYLE_SIZE, it) }
            StyleDimensionRow("Background", SUBTITLE_BACKGROUND_OPTIONS, background) { onChange(SUBTITLE_STYLE_BACKGROUND, it) }
            StyleDimensionRow("Text colour", SUBTITLE_COLOR_OPTIONS, color) { onChange(SUBTITLE_STYLE_COLOR, it) }
            StyleDimensionRow("Position", SUBTITLE_POSITION_OPTIONS, position) { onChange(SUBTITLE_STYLE_POSITION, it) }
            Row(horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m)) {
                OmniButton(
                    label = "Use system default",
                    onClick = {
                        onChange(SUBTITLE_STYLE_SIZE, SUBTITLE_STYLE_DEFAULT)
                        onChange(SUBTITLE_STYLE_BACKGROUND, SUBTITLE_STYLE_DEFAULT)
                        onChange(SUBTITLE_STYLE_COLOR, SUBTITLE_STYLE_DEFAULT)
                        onChange(SUBTITLE_STYLE_POSITION, SUBTITLE_STYLE_DEFAULT)
                    },
                )
                OmniButton(label = "Done", onClick = onDismiss, primary = true)
            }
        }
    }
}

@Composable
private fun StyleDimensionRow(
    label: String,
    options: List<StyleOption>,
    current: String?,
    onChange: (value: String) -> Unit,
) {
    val c = OmniTheme.colors
    val t = OmniTheme.type
    val currentToken = current?.trim()?.lowercase() ?: SUBTITLE_STYLE_DEFAULT
    Column(verticalArrangement = Arrangement.spacedBy(OmniSpacing.s)) {
        Text(label, style = t.overline, color = c.accent)
        Row(horizontalArrangement = Arrangement.spacedBy(OmniSpacing.s)) {
            options.forEach { option ->
                OmniButton(
                    label = option.label,
                    onClick = { onChange(option.value) },
                    primary = option.value == currentToken,
                )
            }
        }
    }
}

@Composable
private fun SubtitlePreview(
    size: String?,
    background: String?,
    color: String?,
    position: String?,
) {
    val spec = resolveSubtitleStyle(size, background, color, position)
    val fg = Color(spec?.textColorArgb ?: 0xFFFFFFFF.toInt())
    val box = Color(spec?.backgroundColorArgb ?: 0x99000000.toInt())
    val window = Color(spec?.windowColorArgb ?: 0x99000000.toInt())
    val fraction = spec?.fractionalTextSize ?: 0.0533f
    val padding = spec?.bottomPaddingFraction ?: 0.08f
    val height = 150.dp
    val fontSize = (fraction * 420f).roundToInt().sp

    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .background(window)
            .clip(RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.BottomCenter,
    ) {
        val placed = Modifier.padding(bottom = height * padding)
        if (box.alpha > 0f) {
            Text(
                "The quick brown fox",
                color = fg,
                fontSize = fontSize,
                modifier = placed
                    .clip(RoundedCornerShape(6.dp))
                    .background(box)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
        } else {
            Text("The quick brown fox", color = fg, fontSize = fontSize, modifier = placed)
        }
    }
}
