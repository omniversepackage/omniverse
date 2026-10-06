package com.yodesla.omniverse.android.update

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yodesla.omniverse.designsystem.OmniButton
import com.yodesla.omniverse.designsystem.requestFocusWhenReady
import androidx.tv.material3.Text
import com.yodesla.omniverse.designsystem.OmniSpacing
import com.yodesla.omniverse.designsystem.OmniTheme
import com.yodesla.omniverse.designsystem.ProgressLine
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Dialog-style update prompt (PLAN.md P3.7): a centered card over a scrim, not a system dialog.
 * Renders nothing while [UpdateViewModel.state] is Hidden — the app overlays this on the main UI.
 */
@Composable
fun UpdatePrompt(vm: UpdateViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    if (state is UpdateUiState.Hidden) return
    // A focusable popup is its own window: the screen underneath (Home rows loading, a list
    // grabbing focus) can no longer pull the remote away from the buttons. Back = Later.
    androidx.compose.ui.window.Popup(
        alignment = Alignment.Center,
        onDismissRequest = vm::later,
        properties = androidx.compose.ui.window.PopupProperties(focusable = true),
    ) {
        UpdatePromptContent(state, vm::accept, vm::later, vm::retryInstall, vm::openSettings)
    }
}

@Composable
private fun UpdatePromptContent(
    state: UpdateUiState,
    onAccept: () -> Unit,
    onLater: () -> Unit,
    onRetryInstall: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val c = OmniTheme.colors
    val t = OmniTheme.type
    val updateNow = remember { FocusRequester() }
    val openSettings = remember { FocusRequester() }
    val install = remember { FocusRequester() }
    val cancel = remember { FocusRequester() }
    val retry = remember { FocusRequester() }
    Box(
        Modifier
            .fillMaxSize()
            .background(c.scrim),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .background(c.surface, RoundedCornerShape(OmniSpacing.cardCorner))
                .padding(OmniSpacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (val s = state) {
                is UpdateUiState.Available -> {
                    Text(getString(R.string.update_title), style = t.headline, color = c.textPrimary)
                    Spacer(Modifier.height(OmniSpacing.m))
                    Text(getString(R.string.update_version, s.manifest.versionName), style = t.body, color = c.accent)
                    if (s.manifest.notes.isNotBlank()) {
                        Spacer(Modifier.height(OmniSpacing.l))
                        Text(
                            s.manifest.notes,
                            style = t.body,
                            color = c.textSecondary,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.height(OmniSpacing.xl))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(OmniSpacing.l),
                    ) {
                        OmniButton(getString(R.string.update_now), onAccept, Modifier.focusRequester(updateNow).weight(1f), primary = true)
                        if (!s.manifest.mandatory) {
                            OmniButton(getString(R.string.update_later), onLater, Modifier.weight(1f))
                        }
                    }
                }

                is UpdateUiState.Downloading -> {
                    Text(getString(R.string.update_downloading), style = t.headline, color = c.textPrimary)
                    Spacer(Modifier.height(OmniSpacing.xl))
                    ProgressLine(s.progress, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(OmniSpacing.m))
                    Text(
                        getString(R.string.update_percent, (s.progress * 100).toInt()),
                        style = t.numeric,
                        color = c.textSecondary,
                    )
                    Spacer(Modifier.height(OmniSpacing.l))
                    // Something focusable, so D-pad presses don't drive the screen underneath.
                    OmniButton(getString(R.string.update_cancel), onLater, Modifier.focusRequester(cancel))
                }

                is UpdateUiState.NeedsPermission -> {
                    Text(getString(R.string.update_title), style = t.headline, color = c.textPrimary)
                    Spacer(Modifier.height(OmniSpacing.l))
                    Text(getString(R.string.update_needs_permission), style = t.body, color = c.textSecondary)
                    Spacer(Modifier.height(OmniSpacing.xl))
                    OmniButton(getString(R.string.update_open_settings), onOpenSettings, Modifier.focusRequester(openSettings), primary = true)
                }

                is UpdateUiState.ReadyToInstall -> {
                    Text(getString(R.string.update_title), style = t.headline, color = c.textPrimary)
                    Spacer(Modifier.height(OmniSpacing.xl))
                    OmniButton(getString(R.string.update_install), onRetryInstall, Modifier.focusRequester(install), primary = true)
                }

                is UpdateUiState.Error -> {
                    Text(getString(s.messageRes), style = t.body, color = c.live)
                    Spacer(Modifier.height(OmniSpacing.xl))
                    Row(horizontalArrangement = Arrangement.spacedBy(OmniSpacing.l)) {
                        OmniButton(getString(R.string.update_retry), onAccept, Modifier.focusRequester(retry), primary = true)
                        OmniButton(getString(R.string.update_close), onLater)
                    }
                }

                UpdateUiState.Hidden -> Unit
            }
        }
    }
    LaunchedEffect(state) {
        when (state) {
            is UpdateUiState.Available -> updateNow.requestFocusWhenReady()
            is UpdateUiState.Downloading -> cancel.requestFocusWhenReady()
            is UpdateUiState.NeedsPermission -> openSettings.requestFocusWhenReady()
            is UpdateUiState.ReadyToInstall -> install.requestFocusWhenReady()
            is UpdateUiState.Error -> retry.requestFocusWhenReady()
            else -> Unit
        }
    }
}

@Composable
private fun getString(@StringRes id: Int, vararg args: Any?): String =
    LocalContext.current.getString(id, *args)
