package com.yodesla.omniverse.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text

/** One choice in [TrackPanel]. id == null means "Off" (subtitles). */
data class TrackOption(val id: String?, val label: String, val selected: Boolean)

/**
 * Player side panel: audio language + subtitles. Two columns of pills; the selected one is
 * accent-marked. Opens with focus on the current subtitle choice (what people change most).
 *
 * Task 84j adds an optional third column for subtitle display: a shortcut into the style editor
 * ([onOpenSubtitleStyle], which the caller uses to close this panel and open the dialog) and a
 * per-session delay stepper ([onSubtitleDelay], direction -1 earlier / +1 later) that leaves the
 * panel open so it can be nudged repeatedly. Live passes neither; VOD passes both.
 */
@Composable
fun TrackPanel(
    audio: List<TrackOption>,
    subtitles: List<TrackOption>,
    onAudio: (String?) -> Unit,
    onSubtitle: (String?) -> Unit,
    onDismiss: () -> Unit,
    subtitleStyleSummary: String? = null,
    onOpenSubtitleStyle: (() -> Unit)? = null,
    subtitleDelayLabel: String? = null,
    onSubtitleDelay: ((Int) -> Unit)? = null,
) {
    val c = OmniTheme.colors
    val first = remember { FocusRequester() }
    val subs = listOf(TrackOption(null, "Off", subtitles.none { it.selected })) + subtitles
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Row(
            Modifier.fillMaxHeight().background(c.background.copy(alpha = 0.94f), RoundedCornerShape(16.dp)).padding(OmniSpacing.xl),
            horizontalArrangement = Arrangement.spacedBy(OmniSpacing.xl),
        ) {
            Column {
                TrackColumn("Subtitles", subs, first) { onSubtitle(it); onDismiss() }
                if (subtitles.isEmpty()) {
                    Text("No subtitles in this stream", style = OmniTheme.type.caption, color = c.textTertiary, modifier = Modifier.padding(top = OmniSpacing.m))
                }
            }
            if (audio.size > 1) TrackColumn("Audio", audio, null) { onAudio(it); onDismiss() }
            if (onOpenSubtitleStyle != null || onSubtitleDelay != null) {
                Column(Modifier.width(320.dp)) {
                    Text("Subtitle display", style = OmniTheme.type.title, color = c.accent, modifier = Modifier.padding(bottom = OmniSpacing.m))
                    if (onOpenSubtitleStyle != null) {
                        OmniButton(label = "Style  ·  ${subtitleStyleSummary ?: "Default"}", onClick = onOpenSubtitleStyle)
                    }
                    if (onSubtitleDelay != null) {
                        Row(
                            Modifier.padding(top = OmniSpacing.m),
                            horizontalArrangement = Arrangement.spacedBy(OmniSpacing.s),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OmniButton(label = "-", onClick = { onSubtitleDelay(-1) })
                            Text(subtitleDelayLabel ?: "0 s", style = OmniTheme.type.body, color = c.textPrimary)
                            OmniButton(label = "+", onClick = { onSubtitleDelay(1) })
                        }
                        Text("Subtitle delay", style = OmniTheme.type.caption, color = c.textTertiary, modifier = Modifier.padding(top = OmniSpacing.s))
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { first.requestFocusWhenReady() }
}

@Composable
private fun TrackColumn(title: String, options: List<TrackOption>, focus: FocusRequester?, onPick: (String?) -> Unit) {
    val c = OmniTheme.colors
    Column(Modifier.width(320.dp)) {
        Text(title, style = OmniTheme.type.title, color = c.accent, modifier = Modifier.padding(bottom = OmniSpacing.m))
        val focusIndex = options.indexOfFirst { it.selected }.coerceAtLeast(0)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(OmniSpacing.s)) {
            items(options.size) { i ->
                val o = options[i]
                OmniButton(
                    label = (if (o.selected) "✓  " else "") + o.label,
                    onClick = { onPick(o.id) },
                    modifier = if (focus != null && i == focusIndex) Modifier.focusRequester(focus) else Modifier,
                    primary = o.selected,
                )
            }
        }
    }
}
