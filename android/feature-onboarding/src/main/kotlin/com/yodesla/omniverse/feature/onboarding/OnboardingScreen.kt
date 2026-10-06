package com.yodesla.omniverse.feature.onboarding
import com.yodesla.omniverse.designsystem.OmniButton
import com.yodesla.omniverse.designsystem.requestFocusWhenReady

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.Text
import com.yodesla.omniverse.core.brand.ExperienceMode
import com.yodesla.omniverse.core.model.SourceId
import com.yodesla.omniverse.designsystem.ErrorCard
import com.yodesla.omniverse.designsystem.FocusCard
import com.yodesla.omniverse.designsystem.LocalCompact
import com.yodesla.omniverse.designsystem.OmniSpacing
import com.yodesla.omniverse.designsystem.OmniTheme
import com.yodesla.omniverse.designsystem.SkeletonBox
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
private fun getString(@StringRes id: Int, vararg args: Any?): String = LocalContext.current.getString(id, *args)

@Composable
fun OnboardingRoute(
    viewModel: OnboardingViewModel,
    appName: String,
    onFinished: (SourceId) -> Unit,
    onboardingDisclaimer: String,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.step) {
        if (state.step == Step.DONE) {
            // Hold "You're all set" briefly: a nicer beat, and the OK key-release that triggered
            // "Start watching" is swallowed here instead of clicking a channel on the next screen.
            kotlinx.coroutines.delay(900)
            viewModel.activeSourceId?.let { onFinished(it) }
        }
    }
    Box(Modifier.fillMaxSize().background(OmniTheme.colors.background)) {
        when (state.step) {
            Step.WELCOME -> WelcomeScreen(appName, onboardingDisclaimer, state.mode, viewModel)
            Step.ADD_SOURCE -> AddSourceScreen(state, viewModel)
            Step.VALIDATING -> ValidatingScreen()
            Step.SYNCING -> SyncingScreen(state, viewModel)
            Step.DONE -> DoneScreen(appName)
        }
    }
}

// ------------------------------------------------- step 1

@Composable
private fun WelcomeScreen(appName: String, onboardingDisclaimer: String, mode: ExperienceMode, vm: OnboardingViewModel) {
    val c = OmniTheme.colors
    val compact = LocalCompact.current
    val first = remember { FocusRequester() }
    Column(
        Modifier.fillMaxSize().padding(horizontal = if (compact) 16.dp else OmniSpacing.tvSide, vertical = OmniSpacing.tvTopBottom),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(getString(R.string.onboarding_welcome_title, appName), style = OmniTheme.type.display, color = c.textPrimary)
        Spacer(Modifier.height(OmniSpacing.xl))
        if (compact) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(OmniSpacing.l)) {
                ModeCard(
                    R.string.onboarding_mode_simple,
                    R.string.onboarding_mode_simple_sub,
                    modifier = Modifier.fillMaxWidth().then(if (mode == ExperienceMode.SIMPLE) Modifier.focusRequester(first) else Modifier),
                ) { vm.chooseMode(ExperienceMode.SIMPLE) }
                ModeCard(
                    R.string.onboarding_mode_full,
                    R.string.onboarding_mode_full_sub,
                    modifier = Modifier.fillMaxWidth(),
                ) { vm.chooseMode(ExperienceMode.FULL) }
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(OmniSpacing.l)) {
                ModeCard(
                    R.string.onboarding_mode_simple,
                    R.string.onboarding_mode_simple_sub,
                    modifier = Modifier.weight(1f).then(if (mode == ExperienceMode.SIMPLE) Modifier.focusRequester(first) else Modifier),
                ) { vm.chooseMode(ExperienceMode.SIMPLE) }
                ModeCard(
                    R.string.onboarding_mode_full,
                    R.string.onboarding_mode_full_sub,
                    modifier = Modifier.weight(1f),
                ) { vm.chooseMode(ExperienceMode.FULL) }
            }
        }
        Spacer(Modifier.weight(1f))
        Text(onboardingDisclaimer, style = OmniTheme.type.caption, color = c.textTertiary)
    }
    LaunchedEffect(first) { first.requestFocusWhenReady() }
}

@Composable
private fun ModeCard(@StringRes title: Int, @StringRes sub: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusCard(onClick = onClick, modifier = modifier.heightIn(min = 160.dp)) {
        Column(
            Modifier.fillMaxSize().padding(OmniSpacing.l),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(getString(title), style = OmniTheme.type.title, color = OmniTheme.colors.textPrimary)
            Spacer(Modifier.height(OmniSpacing.s))
            Text(getString(sub), style = OmniTheme.type.caption, color = OmniTheme.colors.textSecondary)
        }
    }
}

// ------------------------------------------------- step 2

@Composable
private fun AddSourceScreen(state: OnboardingUiState, vm: OnboardingViewModel) {
    val c = OmniTheme.colors
    val t = OmniTheme.type
    val compact = LocalCompact.current
    val f = state.form
    val server = remember { FocusRequester() }
    val username = remember { FocusRequester() }
    val password = remember { FocusRequester() }
    val name = remember { FocusRequester() }
    val playlist = remember { FocusRequester() }
    val epg = remember { FocusRequester() }
    val cont = remember { FocusRequester() }
    val xtreamTab = remember { FocusRequester() }
    val m3uTab = remember { FocusRequester() }
    val plexTab = remember { FocusRequester() }
    val selectedTab = when (f.tab) {
        SourceTab.XTREAM -> xtreamTab
        SourceTab.M3U -> m3uTab
        SourceTab.PLEX -> plexTab
    }
    val firstField = if (f.tab == SourceTab.XTREAM) server else playlist

    Column(
        Modifier
            .fillMaxSize()
            // Four fields + error + button don't fit 1080p with TV margins: scroll, and focus
            // changes bring the focused field into view.
            .verticalScroll(rememberScrollState())
            .padding(horizontal = if (compact) 16.dp else OmniSpacing.tvSide, vertical = OmniSpacing.tvTopBottom),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            state.editingName?.let { getString(R.string.onboarding_relogin_title, it) }
                ?: getString(R.string.onboarding_add_source_title),
            style = t.headline, color = c.textPrimary,
        )
        Spacer(Modifier.height(OmniSpacing.l))
        Row(horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m)) {
            SourceTabPill(R.string.onboarding_tab_xtream, selected = f.tab == SourceTab.XTREAM, requester = xtreamTab) { vm.setTab(SourceTab.XTREAM) }
            if (vm.allowM3u) {
                SourceTabPill(R.string.onboarding_tab_m3u, selected = f.tab == SourceTab.M3U, requester = m3uTab) { vm.setTab(SourceTab.M3U) }
            }
            if (vm.allowPlex) {
                SourceTabPill(R.string.onboarding_tab_plex, selected = f.tab == SourceTab.PLEX, requester = plexTab) { vm.setTab(SourceTab.PLEX) }
            }
        }
        // "Set up from your phone": always visible, right under the tabs — except when the
        // user is already on a phone.
        if (!compact && f.tab != SourceTab.PLEX) state.phoneSetup?.let { ps ->
            Spacer(Modifier.height(OmniSpacing.m))
            Row(
                Modifier
                    .background(OmniTheme.colors.glass, androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
                    .padding(horizontal = OmniSpacing.l, vertical = OmniSpacing.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(getString(R.string.onboarding_phone_title).uppercase(), style = t.overline, color = c.accent)
                Spacer(Modifier.width(OmniSpacing.m))
                Text(getString(R.string.onboarding_phone_body, ps.address), style = t.body, color = c.textSecondary)
                Spacer(Modifier.width(OmniSpacing.m))
                Text(getString(R.string.onboarding_phone_code, ps.code), style = t.title, color = c.textPrimary)
            }
        }
        // Errors sit above the fields: the on-screen keyboard covers the lower half of a TV.
        state.error?.let { err ->
            Spacer(Modifier.height(OmniSpacing.m))
            Text(getString(err), style = t.body, color = c.live)
        }
        Spacer(Modifier.height(OmniSpacing.l))
        if (f.tab == SourceTab.PLEX) {
            PlexLinkPanel(state.plex, vm)
        } else Column(Modifier.widthIn(max = 720.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(OmniSpacing.s)) {
            if (f.tab == SourceTab.XTREAM) {
                OmniField(getString(R.string.onboarding_field_server), f.server, vm::onServerChange,
                    imeAction = ImeAction.Next, onIme = { runCatching { username.requestFocus() } }, focusRequester = server, upRequester = selectedTab)
                OmniField(getString(R.string.onboarding_field_username), f.username, vm::onUsernameChange,
                    imeAction = ImeAction.Next, onIme = { runCatching { password.requestFocus() } }, focusRequester = username)
                OmniField(getString(R.string.onboarding_field_password), f.password, vm::onPasswordChange,
                    imeAction = ImeAction.Next, onIme = { runCatching { name.requestFocus() } },
                    focusRequester = password,
                    trailing = {
                        // Documented exception (task 69): a trailing field affordance keeps tv-material's
                        // own IconButton focus treatment so it stays sized to the input slot; it is not a tile.
                        IconButton(onClick = { vm.togglePassword() }) {
                            Icon(
                                imageVector = if (f.passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = getString(if (f.passwordVisible) R.string.onboarding_hide_password else R.string.onboarding_show_password),
                            )
                        }
                    },
                    visualTransformation = if (f.passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                )
                // The eye inside the field can't take D-pad focus on TV; this button can.
                OmniButton(
                    getString(if (f.passwordVisible) R.string.onboarding_hide_password else R.string.onboarding_show_password),
                    { vm.togglePassword() },
                )
                OmniField(getString(R.string.onboarding_field_name), f.name, vm::onNameChange,
                    imeAction = ImeAction.Done, onIme = { vm.submit() }, focusRequester = name)
            } else {
                OmniField(getString(R.string.onboarding_field_playlist), f.m3uUrl, vm::onPlaylistChange,
                    imeAction = ImeAction.Next, onIme = { runCatching { epg.requestFocus() } }, focusRequester = playlist, upRequester = selectedTab)
                OmniField(getString(R.string.onboarding_field_epg), f.epgUrl, vm::onEpgChange,
                    imeAction = ImeAction.Next, onIme = { runCatching { name.requestFocus() } }, focusRequester = epg)
                OmniField(getString(R.string.onboarding_field_name), f.name, vm::onNameChange,
                    imeAction = ImeAction.Done, onIme = { vm.submit() }, focusRequester = name)
            }
            Spacer(Modifier.height(OmniSpacing.s))
            OmniButton(getString(R.string.onboarding_continue), { vm.submit() }, Modifier.focusRequester(cont), primary = true)
        }
    }
    // "Set up from your phone" runs only while this form is on screen.
    androidx.compose.runtime.DisposableEffect(Unit) {
        vm.startPhoneSetup()
        onDispose { vm.stopPhoneSetup() }
    }
    // After a rejected submit, land on Continue (keyboard closed) so the error stays readable.
    LaunchedEffect(f.tab) {
        if (f.tab == SourceTab.PLEX) selectedTab.requestFocusWhenReady()
        else if (state.error != null) cont.requestFocusWhenReady()
        else firstField.requestFocusWhenReady()
    }
}

@Composable
private fun SourceTabPill(@StringRes label: Int, selected: Boolean, requester: FocusRequester, onClick: () -> Unit) {
    FocusCard(onClick = onClick, modifier = Modifier.height(56.dp).focusRequester(requester)) {
        Box(Modifier.fillMaxHeight().padding(horizontal = OmniSpacing.l), contentAlignment = Alignment.Center) {
            Text(
                getString(label),
                style = OmniTheme.type.body,
                color = if (selected) OmniTheme.colors.accent else OmniTheme.colors.textPrimary,
            )
        }
    }
}

@Composable
private fun OmniField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    imeAction: ImeAction,
    onIme: () -> Unit,
    focusRequester: FocusRequester? = null,
    upRequester: FocusRequester? = null,
    trailing: (@Composable () -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    // Documented exception (task 69): text inputs keep material3's own focus treatment (border +
    // cursor); they are fields, not tiles, so they don't get the FocusCard scale/glow.
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        // tv-material Text doesn't read material3's content colour, so give it one explicitly.
        label = { Text(label, color = OmniTheme.colors.textSecondary) },
        singleLine = true,
        trailingIcon = trailing,
        visualTransformation = visualTransformation,
        keyboardOptions = KeyboardOptions(imeAction = imeAction),
        keyboardActions = KeyboardActions(onNext = { onIme() }, onDone = { onIme() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = OmniTheme.colors.accent,
            unfocusedBorderColor = OmniTheme.colors.elevated,
            cursorColor = OmniTheme.colors.accent,
            focusedContainerColor = OmniTheme.colors.surface,
            unfocusedContainerColor = OmniTheme.colors.surface,
            focusedTextColor = OmniTheme.colors.textPrimary,
            unfocusedTextColor = OmniTheme.colors.textPrimary,
            focusedLabelColor = OmniTheme.colors.accent,
            unfocusedLabelColor = OmniTheme.colors.textSecondary,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(upRequester?.let { target -> Modifier.focusProperties { up = target } } ?: Modifier)
            .then(upRequester?.let { target -> Modifier.onPreviewKeyEvent { event ->
                if (event.key == Key.DirectionUp && event.type == KeyEventType.KeyDown) {
                    target.requestFocus()
                    true
                } else false
            } } ?: Modifier)
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier),
        textStyle = OmniTheme.type.body,
    )
}

// ------------------------------------------------- step 3

@Composable
private fun ValidatingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(getString(R.string.onboarding_checking), style = OmniTheme.type.headline, color = OmniTheme.colors.textPrimary)
            Spacer(Modifier.height(OmniSpacing.l))
            SkeletonBox(Modifier.width(320.dp).height(6.dp))
        }
    }
}

// ------------------------------------------------- step 4

@Composable
private fun SyncingScreen(state: OnboardingUiState, vm: OnboardingViewModel) {
    val c = OmniTheme.colors
    val t = OmniTheme.type
    val p = state.progress
    val compact = LocalCompact.current
    val start = remember { FocusRequester() }
    Box(
        Modifier.fillMaxSize().padding(horizontal = if (compact) 16.dp else OmniSpacing.tvSide, vertical = OmniSpacing.tvTopBottom),
        contentAlignment = Alignment.Center,
    ) {
        if (state.error != null) {
            state.error?.let { err ->
                ErrorCard(
                    title = getString(R.string.onboarding_sync_error_title),
                    message = getString(err),
                    actionLabel = getString(R.string.onboarding_try_again),
                    onAction = { vm.retryFromSync() },
                )
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (state.plex.addedCount > 0) {
                    Text(getString(R.string.onboarding_plex_added_count, state.plex.addedCount), style = t.body, color = c.accent)
                    if (state.plex.skippedCount > 0) Text(getString(R.string.onboarding_plex_skipped_count, state.plex.skippedCount), style = t.caption, color = c.textSecondary)
                    Spacer(Modifier.height(OmniSpacing.m))
                }
                state.account?.let { acc ->
                    Text(accountLine(acc), style = t.body, color = c.accent)
                    Spacer(Modifier.height(OmniSpacing.l))
                }
                p?.let {
                    Text(getString(it.stageText), style = t.headline, color = c.textPrimary)
                    if (it.done > 0 && it.unit != null) {
                        Spacer(Modifier.height(OmniSpacing.s))
                        Text(
                            getString(
                                R.string.onboarding_count,
                                String.format(Locale.getDefault(), "%,d", it.done),
                                getString(it.unit),
                            ),
                            style = t.numeric,
                            color = c.textSecondary,
                        )
                    }
                    it.note?.let { n ->
                        Spacer(Modifier.height(OmniSpacing.m))
                        Text(getString(n), style = t.caption, color = c.textSecondary)
                    }
                    if (it.canStartWatching) {
                        Spacer(Modifier.height(OmniSpacing.l))
                        OmniButton(getString(R.string.onboarding_start_watching), { vm.startWatching() }, Modifier.focusRequester(start), primary = true)
                    }
                }
            }
        }
    }
    LaunchedEffect(p?.canStartWatching) {
        if (p?.canStartWatching == true) runCatching { start.requestFocus() }
    }
}

@Composable
private fun accountLine(acc: AccountUi): String {
    val until = acc.expiresAtMs?.let {
        getString(R.string.onboarding_account_until, DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)))
    } ?: getString(R.string.onboarding_account_no_expiry)
    val screens = acc.maxConnections?.let {
        getString(if (it == 1) R.string.onboarding_account_screens else R.string.onboarding_account_screens_many, it)
    }
    return if (screens != null) "$until · $screens" else until
}

// ------------------------------------------------- step 5

@Composable
private fun DoneScreen(appName: String) {
    val c = OmniTheme.colors
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(getString(R.string.onboarding_done_title), style = OmniTheme.type.display, color = c.textPrimary)
            Spacer(Modifier.height(OmniSpacing.m))
            Text(getString(R.string.onboarding_done_sub, appName), style = OmniTheme.type.body, color = c.textSecondary)
        }
    }
}

/** plex.tv/link: pick every reachable Plex server the viewer wants, including shared ones. */
@Composable
private fun PlexLinkPanel(plex: PlexLinkUi, vm: OnboardingViewModel) {
    val c = OmniTheme.colors
    val t = OmniTheme.type
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.widthIn(max = 720.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(OmniSpacing.m)) {
        when {
            plex.failed -> {
                Text(context.getString(R.string.onboarding_plex_failed), style = t.body, color = c.live)
                plex.detail?.let { Text(it, style = t.caption, color = c.textTertiary) }
                OmniButton(context.getString(R.string.onboarding_plex_retry), { vm.startPlexLink() }, primary = true)
            }
            plex.servers.isNotEmpty() -> {
                Text(context.getString(R.string.onboarding_plex_pick), style = t.title, color = c.textPrimary)
                Text(context.getString(R.string.onboarding_plex_pick_multiple), style = t.caption, color = c.textSecondary)
                plex.servers.forEach { s ->
                    val selected = s.config.machineId in plex.selectedMachineIds
                    OmniButton((if (selected) "✓  " else "○  ") + s.name, { vm.togglePlexServer(s) })
                }
                if (plex.selectedMachineIds.isNotEmpty()) {
                    OmniButton(context.getString(R.string.onboarding_plex_add_selected, plex.selectedMachineIds.size),
                        { vm.addSelectedPlexServers() }, primary = true)
                }
            }
            plex.code == null -> Text(context.getString(R.string.onboarding_plex_getting_code), style = t.body, color = c.textSecondary)
            else -> {
                Text(context.getString(R.string.onboarding_plex_step), style = t.body, color = c.textSecondary)
                Text("plex.tv/link", style = t.headline, color = c.accent)
                Text(plex.code.uppercase(), style = t.numeric.copy(fontSize = androidx.compose.ui.unit.TextUnit(64f, androidx.compose.ui.unit.TextUnitType.Sp), letterSpacing = androidx.compose.ui.unit.TextUnit(12f, androidx.compose.ui.unit.TextUnitType.Sp)), color = c.textPrimary)
                Text(context.getString(R.string.onboarding_plex_waiting), style = t.caption, color = c.textTertiary)
            }
        }
    }
}
