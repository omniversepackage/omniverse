package com.yodesla.omniverse.feature.home.profiles

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Text
import com.yodesla.omniverse.core.data.KidsLimits
import com.yodesla.omniverse.core.data.Profile
import com.yodesla.omniverse.core.data.ProfileRepository
import com.yodesla.omniverse.designsystem.CosmicBackdrop
import com.yodesla.omniverse.designsystem.OmniButton
import com.yodesla.omniverse.designsystem.OmniMotion
import com.yodesla.omniverse.designsystem.OmniSpacing
import com.yodesla.omniverse.designsystem.OmniTheme
import com.yodesla.omniverse.designsystem.requestFocusWhenReady
import com.yodesla.omniverse.designsystem.touchClick
import com.yodesla.omniverse.feature.home.R

/** Create or edit one profile: name, drawn avatar, Kids toggle, delete. */
@Composable
fun ProfileEditorRoute(viewModel: ProfilesViewModel, onExit: () -> Unit) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val c = OmniTheme.colors
    val nameFocus = remember { FocusRequester() }
    val editing = s.editing
    if (editing == null) {
        // Deleted or saved while this screen was open: nothing left to edit.
        LaunchedEffect(Unit) { onExit() }
        return
    }
    val draft = Profile(editing.id, s.nameDraft, s.avatarDraft, s.kidsDraft)
    CosmicBackdrop(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().widthIn(max = 900.dp).padding(start = OmniSpacing.tvSide, end = OmniSpacing.tvSide, top = OmniSpacing.xxl),
            verticalArrangement = Arrangement.spacedBy(OmniSpacing.l),
        ) {
            Text(stringResource(if (s.isNew) R.string.profiles_new_title else R.string.profiles_edit_title), style = OmniTheme.type.browseHeading, color = OmniTheme.colors.textPrimary)
            OutlinedTextField(
                value = s.nameDraft,
                onValueChange = viewModel::setName,
                label = { Text(stringResource(R.string.profiles_name_label), style = OmniTheme.type.caption, color = c.textSecondary) },
                singleLine = true,
                shape = RoundedCornerShape(OmniSpacing.pill),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = c.accent, unfocusedBorderColor = c.elevated, cursorColor = c.accent,
                    focusedContainerColor = c.surface, unfocusedContainerColor = c.surface,
                ),
                modifier = Modifier.width(460.dp).focusRequester(nameFocus),
            )
            Column(verticalArrangement = Arrangement.spacedBy(OmniSpacing.s)) {
                Text(stringResource(R.string.profiles_avatar_label), style = OmniTheme.type.body, color = c.textPrimary)
                for (i in 0 until ProfileRepository.AVATAR_COUNT) {
                    Card(
                        onClick = { viewModel.setAvatar(i) },
                        modifier = Modifier.size(64.dp).touchClick(onClick = { viewModel.setAvatar(i) }),
                        shape = CardDefaults.shape(shape = CircleShape),
                        scale = CardDefaults.scale(focusedScale = OmniMotion.FOCUS_SCALE),
                        colors = CardDefaults.colors(
                            containerColor = Color.Transparent,
                            focusedContainerColor = Color.Transparent,
                            pressedContainerColor = Color.Transparent,
                        ),
                        border = CardDefaults.border(border = Border.None, focusedBorder = Border.None, pressedBorder = Border.None),
                        glow = CardDefaults.glow(
                            focusedGlow = Glow(elevationColor = c.accent.copy(alpha = 0.55f), elevation = 18.dp),
                        ),
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            if (i == s.avatarDraft) ProfileAvatarCurrent(draft.copy(avatar = i), 52.dp)
                            else ProfileAvatar(draft.copy(avatar = i), 52.dp)
                        }
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(OmniSpacing.s)) {
                Text(stringResource(R.string.profiles_kids_label), style = OmniTheme.type.body, color = c.textPrimary)
                Row(horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m)) {
                    OmniButton(stringResource(R.string.metadata_lookup_off), onClick = { viewModel.setKids(false) }, primary = !s.kidsDraft)
                    OmniButton(stringResource(R.string.metadata_lookup_on), onClick = { viewModel.setKids(true) }, primary = s.kidsDraft)
                }
                Text(stringResource(R.string.profiles_kids_note), style = OmniTheme.type.caption, color = c.textTertiary)
            }
            if (s.kidsDraft) {
                KidsLimitsEditor(viewModel, s)
            }
            if (s.confirmDelete) {
                Column(verticalArrangement = Arrangement.spacedBy(OmniSpacing.s)) {
                    // Task 84b: deleting the last profile is not destructive - it becomes the Guest.
                    Text(
                        stringResource(if (s.items.size <= 1) R.string.profiles_delete_confirm_last else R.string.profiles_delete_confirm),
                        style = OmniTheme.type.body,
                        color = c.live,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m)) {
                        OmniButton(stringResource(R.string.profiles_delete_yes), onClick = { viewModel.deleteNow() }, primary = true)
                        OmniButton(stringResource(R.string.profiles_delete_no), onClick = viewModel::cancelDelete)
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m), verticalAlignment = Alignment.CenterVertically) {
                    OmniButton(stringResource(R.string.profiles_save), onClick = { viewModel.save(); onExit() }, primary = true)
                    if (!s.isNew) {
                        OmniButton(stringResource(R.string.profiles_delete), onClick = viewModel::askDelete)
                    }
                    Spacer(Modifier.width(OmniSpacing.s))
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { nameFocus.requestFocusWhenReady() } }
}

/**
 * Task 106: the daily allowance and bedtime window for a Kids profile. Only offered for Kids
 * profiles, because those are the profiles the players enforce limits on.
 */
@Composable
private fun KidsLimitsEditor(viewModel: ProfilesViewModel, s: ProfilesState) {
    val c = OmniTheme.colors
    val step = KidsLimits.BEDTIME_STEP_MINUTES
    val bedtimeOn = s.bedtimeStartDraft != null && s.bedtimeEndDraft != null
    Column(verticalArrangement = Arrangement.spacedBy(OmniSpacing.s)) {
        Text(stringResource(R.string.profiles_kids_limit_label), style = OmniTheme.type.body, color = c.textPrimary)
        Row(horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m)) {
            OmniButton(stringResource(R.string.profiles_limit_off), onClick = { viewModel.setDailyLimit(0) }, primary = s.limitDraft == 0)
            OmniButton(stringResource(R.string.profiles_limit_30), onClick = { viewModel.setDailyLimit(30) }, primary = s.limitDraft == 30)
            OmniButton(stringResource(R.string.profiles_limit_1h), onClick = { viewModel.setDailyLimit(60) }, primary = s.limitDraft == 60)
            OmniButton(stringResource(R.string.profiles_limit_2h), onClick = { viewModel.setDailyLimit(120) }, primary = s.limitDraft == 120)
            OmniButton(stringResource(R.string.profiles_limit_3h), onClick = { viewModel.setDailyLimit(180) }, primary = s.limitDraft == 180)
        }
        Text(stringResource(R.string.profiles_kids_bedtime_label), style = OmniTheme.type.body, color = c.textPrimary)
        Row(horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m), verticalAlignment = Alignment.CenterVertically) {
            OmniButton(stringResource(R.string.metadata_lookup_off), onClick = { viewModel.setBedtimeEnabled(false) }, primary = !bedtimeOn)
            OmniButton(stringResource(R.string.metadata_lookup_on), onClick = { viewModel.setBedtimeEnabled(true) }, primary = bedtimeOn)
            if (bedtimeOn) {
                OmniButton("◀", onClick = { viewModel.shiftBedtimeStart(-step) })
                Text(clockText(s.bedtimeStartDraft), style = OmniTheme.type.numeric, color = c.textPrimary)
                OmniButton("▶", onClick = { viewModel.shiftBedtimeStart(step) })
                Text("-", style = OmniTheme.type.numeric, color = c.textTertiary)
                OmniButton("◀", onClick = { viewModel.shiftBedtimeEnd(-step) })
                Text(clockText(s.bedtimeEndDraft), style = OmniTheme.type.numeric, color = c.textPrimary)
                OmniButton("▶", onClick = { viewModel.shiftBedtimeEnd(step) })
            }
        }
        if (bedtimeOn) {
            Text(
                stringResource(R.string.profiles_kids_limits_note_bedtime, clockText(s.bedtimeStartDraft), clockText(s.bedtimeEndDraft)),
                style = OmniTheme.type.caption, color = c.textTertiary,
            )
        } else {
            Text(stringResource(R.string.profiles_kids_limits_note), style = OmniTheme.type.caption, color = c.textTertiary)
        }
    }
}

/** A bedtime minute-of-day as a wall clock, e.g. 1200 -> "20:00". */
private fun clockText(minuteOfDay: Int?): String = minuteOfDay?.let {
    "${(it / 60).toString().padStart(2, '0')}:${(it % 60).toString().padStart(2, '0')}"
} ?: ""
