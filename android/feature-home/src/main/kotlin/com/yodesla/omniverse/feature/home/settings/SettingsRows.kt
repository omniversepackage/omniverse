package com.yodesla.omniverse.feature.home.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.yodesla.omniverse.designsystem.FocusCard
import com.yodesla.omniverse.designsystem.OmniSpacing
import com.yodesla.omniverse.designsystem.OmniSwitch
import com.yodesla.omniverse.designsystem.OmniTheme
import com.yodesla.omniverse.designsystem.nextSwitchState
import com.yodesla.omniverse.designsystem.requestFocusWhenReady
import kotlinx.coroutines.delay

/**
 * Task 84e: the uniform "setting row" - title, one-line secondary (max 2 lines), control on the
 * right. The WHOLE row is the focus target (glass highlight like the rest of the app); OK flips
 * a switch, opens a picker or runs an action. Long disclosures hide behind an info affordance:
 * the secondary text truncates to 2 lines, and holding focus for a second (or pressing Info)
 * opens the full text. Nothing is deleted - every disclosure stays reachable.
 */

/** Per-row focus requesters + last-focused memory, so D-pad focus is restored per category. */
class RowFocusRegistry {
    private val requesters = mutableStateMapOf<String, FocusRequester>()
    val lastFocused = mutableStateMapOf<SettingsCategory, String>()
    val firstKey = mutableStateMapOf<SettingsCategory, String>()

    fun requester(key: String): FocusRequester = requesters.getOrPut(key) { FocusRequester() }

    fun noteFocused(category: SettingsCategory, key: String) { lastFocused[category] = key }

    fun noteFirst(category: SettingsCategory, key: String) {
        if (!firstKey.containsKey(category)) firstKey[category] = key
    }

    /** Focus the row the viewer last used in this category, else its first row. */
    suspend fun focusReady(category: SettingsCategory): Boolean {
        val key = lastFocused[category] ?: firstKey[category] ?: return false
        return requester(key).requestFocusWhenReady()
    }
}

/** What a row needs from its pane: the category (focus memory) and the info-panel opener. */
class RowCtx internal constructor(
    val category: SettingsCategory,
    val registry: RowFocusRegistry,
    val onInfo: (title: String, body: String) -> Unit,
)

@Composable
fun OmniSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    secondary: String? = null,
    disclosure: String? = null,
    modifier: Modifier = Modifier,
    ctx: RowCtx? = null,
    key: String? = null,
) {
    SettingRow(title, secondary, disclosure, onClick = { onCheckedChange(nextSwitchState(checked)) }, modifier, ctx, key) {
        OmniSwitch(checked)
    }
}

@Composable
fun OmniPickerRow(
    title: String,
    value: String,
    onClick: () -> Unit,
    secondary: String? = null,
    disclosure: String? = null,
    modifier: Modifier = Modifier,
    ctx: RowCtx? = null,
    key: String? = null,
) {
    val c = OmniTheme.colors
    SettingRow(title, secondary, disclosure, onClick, modifier, ctx, key) {
        Text(value, style = OmniTheme.type.body, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(OmniSpacing.xs))
        Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = c.textTertiary, modifier = Modifier.size(18.dp))
    }
}

@Composable
fun OmniActionRow(
    title: String,
    label: String,
    onClick: () -> Unit,
    secondary: String? = null,
    disclosure: String? = null,
    destructive: Boolean = false,
    modifier: Modifier = Modifier,
    ctx: RowCtx? = null,
    key: String? = null,
) {
    SettingRow(title, secondary, disclosure, onClick, modifier, ctx, key) {
        RowPill(label, destructive = destructive, onClick = onClick)
    }
}

/** A row with no control: OK (or focus-hold / Info) opens the full disclosure text. */
@Composable
fun OmniTextRow(
    title: String,
    secondary: String? = null,
    disclosure: String? = null,
    modifier: Modifier = Modifier,
    ctx: RowCtx? = null,
    key: String? = null,
) {
    SettingRow(
        title, secondary, disclosure,
        onClick = { if (disclosure != null && ctx != null) ctx.onInfo(title, disclosure) },
        modifier, ctx, key,
    ) {}
}

@Composable
private fun SettingRow(
    title: String,
    secondary: String?,
    disclosure: String?,
    onClick: (() -> Unit)?,
    modifier: Modifier,
    ctx: RowCtx?,
    key: String?,
    control: @Composable RowScope.() -> Unit,
) {
    val c = OmniTheme.colors
    val t = OmniTheme.type
    var focused by remember { mutableStateOf(false) }
    var infoShown by remember(disclosure) { mutableStateOf(false) }
    val fr = if (ctx != null && key != null) ctx.registry.requester(key) else null
    LaunchedEffect(focused) {
        if (focused && disclosure != null && ctx != null && !infoShown) {
            delay(1_000)
            if (focused) {
                infoShown = true
                ctx.onInfo(title, disclosure)
            }
        }
    }
    FocusCard(
        onClick = onClick ?: {},
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .then(if (fr != null) Modifier.focusRequester(fr) else Modifier)
            .onKeyEvent { e -> disclosure != null && e.type == KeyEventType.KeyDown && e.key == Key.Info }
            .onFocusChanged {
                focused = it.hasFocus
                if (it.hasFocus && ctx != null && key != null) ctx.registry.noteFocused(ctx.category, key)
            },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = OmniSpacing.l, vertical = OmniSpacing.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m),
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = t.body, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val summary = secondary ?: disclosure
                if (summary != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            summary, style = t.caption, color = c.textSecondary,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (disclosure != null) {
                            Spacer(Modifier.width(OmniSpacing.s))
                            Icon(Icons.Outlined.Info, contentDescription = "More info", tint = c.textTertiary, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
            control()
        }
    }
}

/** The app's pill button (moved from the old Settings page): quiet at rest, accent text when
 *  selected, muted red for destructive confirmations. Used by the Sources cards. */
@Composable
fun RowPill(
    label: String,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    destructive: Boolean = false,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val c = OmniTheme.colors
    FocusCard(onClick = onClick, modifier = modifier.height(OmniSpacing.pill)) {
        Row(Modifier.fillMaxHeight().padding(horizontal = OmniSpacing.l), verticalAlignment = Alignment.CenterVertically) {
            if (selected) {
                // A gold check, not a text bullet: the app fonts have no reliable symbol glyphs.
                Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = c.accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(OmniSpacing.s))
            }
            Text(
                // Task 90: solid label, no dark halo - contrast via colour only (destructive = muted red).
                label, style = OmniTheme.type.body.copy(shadow = null),
                color = when { destructive || accent -> c.live; selected -> c.accent; else -> c.textPrimary },
            )
        }
    }
}

/** Focusable + clickable for D-pad (OK / Enter) without a ripple. */
@Composable
fun Modifier.clickableTv(onClick: () -> Unit): Modifier =
    this.clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick,
    )
