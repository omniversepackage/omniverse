package com.yodesla.omniverse.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Icon
import androidx.tv.material3.Text


/**
 * Task 84e: the compact glass picker that replaces rows of choice pills. One option per row,
 * a gold check on the current value, focus lands on the current value, D-pad Up/Down browses,
 * OK picks, Back dismisses.
 */
@Immutable
data class PickerOption(val value: String, val label: String, val secondary: String? = null)

@Composable
fun OmniPickerDialog(
    title: String,
    options: List<PickerOption>,
    selected: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = OmniTheme.colors
    val t = OmniTheme.type
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(c.scrim.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = 560.dp).padding(OmniSpacing.tvSide).glassSurface(corner = 16.dp)
                    .padding(OmniSpacing.l),
                verticalArrangement = Arrangement.spacedBy(OmniSpacing.xs),
            ) {
                Text(title, style = t.headline, color = c.textPrimary, modifier = Modifier.padding(bottom = OmniSpacing.s))
                LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(OmniSpacing.xs)) {
                    items(options, key = { it.value }) { opt ->
                        PickerRow(opt, selected = opt.value == selected, onClick = { onSelect(opt.value) })
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerRow(opt: PickerOption, selected: Boolean, onClick: () -> Unit) {
    val c = OmniTheme.colors
    val t = OmniTheme.type
    var focused by remember { mutableStateOf(false) }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (selected) fr.requestFocusWhenReady() }
    Box(
        Modifier.fillMaxWidth().height(56.dp)
            .focusRequester(fr)
            .onFocusChanged { focused = it.isFocused }
            .then(if (focused || selected) Modifier.glassSurface(corner = 26.dp, focused = focused, shadow = false) else Modifier)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(Modifier.fillMaxHeight().padding(horizontal = OmniSpacing.l), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                if (selected) Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = c.accent, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(OmniSpacing.s))
            Column(Modifier.weight(1f)) {
                Text(opt.label, style = t.body, color = if (selected) c.textPrimary else c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                opt.secondary?.let { Text(it, style = t.caption, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}
