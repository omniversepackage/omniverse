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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Text
import com.yodesla.omniverse.core.data.Profile
import com.yodesla.omniverse.designsystem.CosmicBackdrop
import com.yodesla.omniverse.designsystem.OmniButton
import com.yodesla.omniverse.designsystem.OmniMotion
import com.yodesla.omniverse.designsystem.OmniSpacing
import com.yodesla.omniverse.designsystem.OmniTheme
import com.yodesla.omniverse.designsystem.requestFocusWhenReady
import com.yodesla.omniverse.designsystem.touchClick
import com.yodesla.omniverse.feature.home.R

/** Startup / avatar-click screen: pick who is watching. */
@Composable
fun WhosWatchingRoute(
    viewModel: ProfilesViewModel,
    onSelect: (Profile) -> Unit,
    onManage: () -> Unit,
    onAdd: () -> Unit,
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val c = OmniTheme.colors
    val focus = remember { FocusRequester() }
    CosmicBackdrop(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = OmniSpacing.tvSide, vertical = OmniSpacing.xxl),
            verticalArrangement = Arrangement.spacedBy(OmniSpacing.l, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.profiles_whos_watching), style = OmniTheme.type.browseHero, color = c.textPrimary)
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(OmniSpacing.l),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items(s.items, key = { it.id }) { p ->
                    val isCurrent = p.id == s.currentId
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(OmniSpacing.s),
                    ) {
                        Card(
                            onClick = { onSelect(p) },
                            modifier = Modifier
                                .size(176.dp)
                                .then(if (isCurrent) Modifier.focusRequester(focus) else Modifier)
                                .touchClick(onClick = { onSelect(p) }),
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
                                if (isCurrent) ProfileAvatarCurrent(p, 164.dp)
                                else ProfileAvatar(p, 164.dp)
                            }
                        }
                        Text(p.name, style = OmniTheme.type.title, color = if (isCurrent) c.accent else c.textPrimary, maxLines = 1)
                        if (p.isKids) Text(stringResource(R.string.profiles_kids_tag).uppercase(), style = OmniTheme.type.overline, color = c.live)
                        else if (isCurrent) Text(stringResource(R.string.profiles_current).uppercase(), style = OmniTheme.type.overline, color = c.textTertiary)
                    }
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OmniButton(stringResource(R.string.profiles_add), onClick = onAdd)
                OmniButton(stringResource(R.string.profiles_manage), onClick = onManage)
            }
            Spacer(Modifier.height(OmniSpacing.m))
            Text(
                stringResource(R.string.profiles_picker_hint),
                style = OmniTheme.type.caption, color = c.textTertiary, textAlign = TextAlign.Center,
            )
        }
    }
    LaunchedEffect(s.items.size) { runCatching { focus.requestFocusWhenReady() } }
}
