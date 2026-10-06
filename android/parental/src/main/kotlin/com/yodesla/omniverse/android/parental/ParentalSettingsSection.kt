package com.yodesla.omniverse.android.parental

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.yodesla.omniverse.designsystem.OmniButton
import com.yodesla.omniverse.designsystem.OmniSpacing
import com.yodesla.omniverse.designsystem.OmniTheme
import com.yodesla.omniverse.designsystem.requestFocusWhenReady
import kotlinx.coroutines.launch

data class LockableCategory(
    val kind: String,
    val sourceId: String,
    val id: String,
    val name: String,
)

private enum class PinFlow {
    None,
    NewFirst,
    NewSecond,
    ChangeCurrent,
    ChangeNew,
    ChangeConfirm,
    TurnOff,
    Unlock,
}

/**
 * Parental settings block. Without a PIN: a "Set a PIN" button that runs the PinPad
 * twice (mismatch shows an error and restarts). With a PIN: one toggle row per category
 * (adult-looking names pre-suggested), plus "Change PIN" and "Turn off", both requiring
 * the current PIN.
 */
@Composable
fun ParentalSettingsSection(
    controls: ParentalControls,
    categories: List<LockableCategory>,
) {
    val c = OmniTheme.colors
    val t = OmniTheme.type
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val enabled by controls.enabled.collectAsState(initial = false)
    val lockedKeys by controls.lockedKeys().collectAsState(initial = emptySet())
    val lockedOutUntil by controls.lockedOutUntil.collectAsState()
    var pinFlow by remember { mutableStateOf(PinFlow.None) }
    var firstPin by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }
    // Bumped on every finished entry so the pad starts empty for the next step or retry.
    var attempt by remember { mutableStateOf(0) }
    val unlocked by controls.unlockedForSession.collectAsState()
    var showAll by remember { mutableStateOf(false) }
    // The button a state change removes had focus; without a landing spot it falls to the nav rail.
    val landing = remember { FocusRequester() }
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(enabled, unlocked, showAll, pinFlow == PinFlow.None) {
        if (settled && pinFlow == PinFlow.None) landing.requestFocusWhenReady()
        settled = true
    }

    val pinTitle = when (pinFlow) {
        PinFlow.NewFirst, PinFlow.ChangeNew -> context.getString(R.string.parental_new_pin_title)
        PinFlow.NewSecond, PinFlow.ChangeConfirm -> context.getString(R.string.parental_confirm_pin_title)
        PinFlow.ChangeCurrent, PinFlow.TurnOff, PinFlow.Unlock -> context.getString(R.string.parental_current_pin_title)
        PinFlow.None -> ""
    }

    LaunchedEffect(lockedOutUntil) {
        if (lockedOutUntil != null) {
            pinFlow = PinFlow.None
            pinError = context.getString(R.string.parental_locked_out)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(OmniSpacing.m)) {
        Text(context.getString(R.string.parental_title), style = t.title, color = c.accent)
        if (lockedOutUntil != null) {
            Text(context.getString(R.string.parental_locked_out), style = t.body, color = c.live)
        }

        if (!enabled) {
            OmniButton(context.getString(R.string.parental_set_pin), {
                firstPin = ""
                pinError = null
                pinFlow = PinFlow.NewFirst
            }, Modifier.focusRequester(landing), primary = true)
        } else if (!unlocked) {
            // Managing locks (or seeing locked content) always needs the PIN.
            Text(context.getString(R.string.parental_hidden_hint), style = t.body, color = c.textSecondary)
            OmniButton(context.getString(R.string.parental_unlock), { pinError = null; pinFlow = PinFlow.Unlock }, Modifier.focusRequester(landing), primary = true)
        } else {
            // Suggested (adult-looking) and already-locked categories first; the rest on request, so a
            // provider with hundreds of categories doesn't flood Settings.
            val (top, rest) = categories.partition { "${it.kind}|${it.sourceId}|${it.id}" in lockedKeys || controls.suggestLock(it.name) }
            val shown = if (showAll) top + rest else top
            // After "Show all", land on the first newly revealed category.
            val landingIndex = if (showAll && rest.isNotEmpty()) top.size else 0
            shown.forEachIndexed { index, cat ->
                val key = "${cat.kind}|${cat.sourceId}|${cat.id}"
                val locked = key in lockedKeys
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(cat.name, style = t.body, color = c.textPrimary, maxLines = 1)
                        val kindLabel = when (cat.kind) {
                            "LIVE" -> context.getString(R.string.parental_kind_live)
                            "VOD" -> context.getString(R.string.parental_kind_vod)
                            "SERIES" -> context.getString(R.string.parental_kind_series)
                            else -> cat.kind
                        }
                        val caption = if (controls.suggestLock(cat.name)) {
                            "$kindLabel  ·  ${context.getString(R.string.parental_suggested)}"
                        } else {
                            kindLabel
                        }
                        Text(caption, style = t.caption, color = c.textSecondary, maxLines = 1)
                    }
                    OmniButton(
                        label = if (locked) context.getString(R.string.parental_locked) else context.getString(R.string.parental_open),
                        onClick = { controls.touch(); scope.launch { controls.setLocked(cat.kind, cat.sourceId, cat.id, !locked) } },
                        modifier = if (index == landingIndex) Modifier.focusRequester(landing) else Modifier,
                        primary = locked,
                    )
                }
            }
            if (!showAll && rest.isNotEmpty()) {
                OmniButton(context.getString(R.string.parental_show_all, rest.size), { showAll = true })
            }
            OmniButton(
                context.getString(R.string.parental_lock_again), { controls.lockAgain() },
                if (shown.isEmpty()) Modifier.focusRequester(landing) else Modifier,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(OmniSpacing.l),
            ) {
                OmniButton(context.getString(R.string.parental_change_pin), {
                    pinError = null
                    pinFlow = PinFlow.ChangeCurrent
                })
                OmniButton(context.getString(R.string.parental_turn_off), {
                    pinError = null
                    pinFlow = PinFlow.TurnOff
                })
            }
        }
    }

    if (pinFlow != PinFlow.None) androidx.compose.ui.window.Dialog(
        onDismissRequest = { pinFlow = PinFlow.None; pinError = null },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) { androidx.compose.runtime.key(pinFlow, attempt) {
        PinPad(
            title = pinTitle,
            error = pinError,
            onCancel = {
                pinFlow = PinFlow.None
                pinError = null
            },
            onDone = { entered ->
                attempt++
                when (pinFlow) {
                    PinFlow.NewFirst -> {
                        firstPin = entered
                        pinFlow = PinFlow.NewSecond
                    }
                    PinFlow.NewSecond -> {
                        if (entered == firstPin) {
                            scope.launch { controls.setPin(entered); pinFlow = PinFlow.None; pinError = null }
                        } else {
                            pinError = context.getString(R.string.parental_mismatch)
                            firstPin = ""
                            pinFlow = PinFlow.NewFirst
                        }
                    }
                    PinFlow.ChangeCurrent -> {
                        scope.launch {
                            if (controls.check(entered)) {
                                pinFlow = PinFlow.ChangeNew
                            } else {
                                pinError = context.getString(R.string.parental_wrong_pin)
                            }
                        }
                    }
                    PinFlow.ChangeNew -> {
                        firstPin = entered
                        pinFlow = PinFlow.ChangeConfirm
                    }
                    PinFlow.ChangeConfirm -> {
                        if (entered == firstPin) {
                            scope.launch { controls.setPin(entered); pinFlow = PinFlow.None; pinError = null }
                        } else {
                            pinError = context.getString(R.string.parental_mismatch)
                            firstPin = ""
                            pinFlow = PinFlow.ChangeNew
                        }
                    }
                    PinFlow.TurnOff -> {
                        scope.launch {
                            if (controls.clearPin(entered)) {
                                pinFlow = PinFlow.None
                                pinError = null
                            } else {
                                pinError = context.getString(R.string.parental_wrong_pin)
                            }
                        }
                    }
                    PinFlow.Unlock -> {
                        scope.launch {
                            if (controls.check(entered)) { pinFlow = PinFlow.None; pinError = null }
                            else pinError = context.getString(R.string.parental_wrong_pin)
                        }
                    }
                    PinFlow.None -> Unit
                }
            },
        )
    } }
}
