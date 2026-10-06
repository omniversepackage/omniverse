package com.yodesla.omniverse.feature.home.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.yodesla.omniverse.core.data.Profile
import com.yodesla.omniverse.designsystem.CosmicBackdrop
import com.yodesla.omniverse.designsystem.FocusCard
import com.yodesla.omniverse.designsystem.OmniButton
import com.yodesla.omniverse.designsystem.OmniSpacing
import com.yodesla.omniverse.designsystem.OmniTheme
import com.yodesla.omniverse.designsystem.requestFocusWhenReady
import com.yodesla.omniverse.feature.home.R

/** Settings entry / "Manage" on the picker: edit, add, delete profiles. */
@Composable
fun ManageProfilesRoute(
    viewModel: ProfilesViewModel,
    onEdit: (Profile) -> Unit,
    onAdd: () -> Unit,
    onDone: () -> Unit,
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val c = OmniTheme.colors
    val first = remember { FocusRequester() }
    // Task 84b: the Guest is not a profile to edit - when it is all that is left the screen
    // becomes "no profiles" and the only move is to create one.
    val guestOnly = s.items.size == 1 && s.items.first().isGuest
    CosmicBackdrop(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxHeight().widthIn(max = 860.dp),
            contentPadding = PaddingValues(start = OmniSpacing.tvSide, end = OmniSpacing.tvSide, top = OmniSpacing.xxl, bottom = 200.dp),
            verticalArrangement = Arrangement.spacedBy(OmniSpacing.m),
        ) {
            item { Text(stringResource(R.string.profiles_manage_title), style = OmniTheme.type.browseHeading, color = c.textPrimary) }
            if (guestOnly) {
                item {
                    Text(
                        stringResource(R.string.profiles_guest_note),
                        style = OmniTheme.type.body, color = c.textSecondary,
                    )
                }
            }
            if (!guestOnly) items(s.items, key = { it.id }) { p ->
                val isCurrent = p.id == s.currentId
                FocusCard(
                    onClick = { onEdit(p) },
                    modifier = Modifier.fillMaxWidth().height(92.dp).then(if (p.id == s.items.firstOrNull()?.id) Modifier.focusRequester(first) else Modifier),
                ) {
                    Row(
                        Modifier.fillMaxSize().padding(horizontal = OmniSpacing.m),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m),
                    ) {
                        if (isCurrent) ProfileAvatarCurrent(p, 60.dp) else ProfileAvatar(p, 60.dp)
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = OmniTheme.type.title, color = c.textPrimary, maxLines = 1)
                            Text(
                                listOfNotNull(
                                    if (p.isKids) stringResource(R.string.profiles_kids_tag) else null,
                                    if (isCurrent) stringResource(R.string.profiles_current) else null,
                                ).joinToString("  ·  ").ifEmpty { " " },
                                style = OmniTheme.type.caption, color = if (p.isKids) c.live else c.textTertiary,
                            )
                        }
                        Text(stringResource(R.string.profiles_edit_hint), style = OmniTheme.type.caption, color = c.textTertiary)
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m)) {
                    OmniButton(
                        stringResource(R.string.profiles_add),
                        onClick = onAdd,
                        primary = true,
                        modifier = if (guestOnly) Modifier.focusRequester(first) else Modifier,
                    )
                    OmniButton(stringResource(R.string.profiles_done), onClick = onDone)
                }
            }
            item { Spacer(Modifier.height(OmniSpacing.l)) }
            item {
                Text(
                    stringResource(if (guestOnly) R.string.profiles_guest_manage_note else R.string.profiles_manage_note),
                    style = OmniTheme.type.caption, color = c.textTertiary,
                )
            }
        }
    }
    LaunchedEffect(s.items.size, guestOnly) { runCatching { first.requestFocusWhenReady() } }
}
