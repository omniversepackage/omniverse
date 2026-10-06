package com.yodesla.omniverse.feature.live.multiview

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import androidx.tv.material3.Text
import com.yodesla.omniverse.core.data.ChannelRow
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.designsystem.FocusCard
import com.yodesla.omniverse.designsystem.LogoImage
import com.yodesla.omniverse.designsystem.OmniButton
import com.yodesla.omniverse.designsystem.OmniSpacing
import com.yodesla.omniverse.designsystem.OmniTheme
import com.yodesla.omniverse.feature.live.R

/**
 * Full-screen channel picker opened from the Live list's Multiview card (Task 55, groups in Task 67).
 * Saved groups sit first as big cards ("Game night · 4 channels"): OK opens one directly, hold OK
 * renames or deletes it. Below them the current category's channels are tiles; OK toggles a channel
 * (max 4, order badges 1-4); "Save group" stores the current selection under a name; "Watch N
 * together" starts the grid. Back cancels the topmost overlay first. Selection rules live in
 * [MultiviewLayout] (unit-tested); group persistence lives in [MultiviewGroupStore].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MultiviewPicker(
    channels: LazyPagingItems<ChannelRow>,
    groups: List<MultiviewGroup>,
    notice: String?,
    onDismiss: () -> Unit,
    onConfirm: (List<ContentKey>) -> Unit,
    onOpenGroup: (List<ContentKey>) -> Unit,
    onSaveGroup: (String, List<ContentKey>) -> Unit,
    onRenameGroup: (Int, String) -> Unit,
    onDeleteGroup: (Int) -> Unit,
) {
    val c = OmniTheme.colors
    val context = LocalContext.current
    var selected by remember { mutableStateOf(emptyList<ContentKey>()) }
    var groupMenu by remember { mutableStateOf<Int?>(null) }
    var renameTarget by remember { mutableStateOf<Int?>(null) }
    var saveDialog by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }

    BackHandler {
        when {
            renameTarget != null -> renameTarget = null
            groupMenu != null -> groupMenu = null
            saveDialog -> saveDialog = false
            else -> onDismiss()
        }
    }
    LaunchedEffect(channels.itemCount) { if (channels.itemCount > 0) runCatching { first.requestFocus() } }
    val count = selected.size
    val ready = count >= MultiviewLayout.MIN_TILES

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(c.background.copy(alpha = 0.94f), c.background.copy(alpha = 0.98f)))),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = OmniSpacing.tvSide, vertical = OmniSpacing.m)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Multiview", style = OmniTheme.type.headline, color = c.textPrimary)
                    Text(
                        "Pick 2\u20134 channels  \u00b7  OK toggles  \u00b7  Back cancels",
                        style = OmniTheme.type.body, color = c.textSecondary,
                    )
                }
                OmniButton(
                    context.getString(R.string.multiview_save_group),
                    onClick = { if (ready) saveDialog = true },
                )
                Spacer(Modifier.width(OmniSpacing.s))
                OmniButton(
                    if (ready) "Watch $count together" else "Pick 2\u20134 channels",
                    onClick = { if (ready) onConfirm(selected) },
                    primary = true,
                )
            }
            if (notice != null) {
                Text(
                    notice, style = OmniTheme.type.body, color = c.live,
                    modifier = Modifier.padding(top = OmniSpacing.s), maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            if (groups.isNotEmpty()) {
                Spacer(Modifier.height(OmniSpacing.m))
                Text(context.getString(R.string.multiview_saved_groups), style = OmniTheme.type.title, color = c.textPrimary)
                Spacer(Modifier.height(OmniSpacing.s))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m)) {
                    itemsIndexed(groups, key = { i, g -> "group-$i-${g.name}" }) { index, group ->
                        GroupCard(
                            group = group,
                            onOpen = { onOpenGroup(group.channels) },
                            onMenu = { groupMenu = index },
                        )
                    }
                }
            }
            Spacer(Modifier.height(OmniSpacing.m))
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 200.dp),
                horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m),
                verticalArrangement = Arrangement.spacedBy(OmniSpacing.m),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(channels.itemCount, key = channels.itemKey { "${it.key.sourceId.value}/${it.key.remoteId.value}" }) { index ->
                    val row = channels[index] ?: return@items
                    val badge = selected.indexOfFirst { it == row.key }
                    FocusCard(
                        onClick = { selected = MultiviewLayout.toggled(selected, row.key) },
                        modifier = Modifier.height(92.dp).then(if (index == 0) Modifier.focusRequester(first) else Modifier),
                    ) {
                        Row(Modifier.fillMaxSize().padding(OmniSpacing.m), verticalAlignment = Alignment.CenterVertically) {
                            LogoImage(row.logoUrl, row.name, Modifier.size(56.dp, 34.dp))
                            Spacer(Modifier.width(OmniSpacing.s))
                            Text(
                                row.name, style = OmniTheme.type.title, color = c.textPrimary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                            )
                        }
                        if (badge >= 0) {
                            Box(
                                Modifier.align(Alignment.TopEnd).padding(8.dp).size(26.dp)
                                    .background(c.accent, RoundedCornerShape(13.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("${badge + 1}", style = OmniTheme.type.numeric, color = c.background)
                            }
                        }
                    }
                }
            }
        }

        val menuIndex = groupMenu
        if (menuIndex != null && menuIndex in groups.indices) {
            GroupMenuOverlay(
                group = groups[menuIndex],
                onRename = { renameTarget = menuIndex; groupMenu = null },
                onDelete = { onDeleteGroup(menuIndex); groupMenu = null },
                onDismiss = { groupMenu = null },
            )
        }
        val renameIndex = renameTarget
        if (renameIndex != null && renameIndex in groups.indices) {
            NameDialog(
                title = context.getString(R.string.multiview_rename),
                initial = groups[renameIndex].name,
                onSave = { onRenameGroup(renameIndex, it); renameTarget = null },
                onDismiss = { renameTarget = null },
            )
        }
        if (saveDialog) {
            NameDialog(
                title = context.getString(R.string.multiview_save_group),
                initial = MultiviewGroupStore.defaultNameFor(groups),
                onSave = { onSaveGroup(it, selected); saveDialog = false },
                onDismiss = { saveDialog = false },
            )
        }
    }
}

@Composable
private fun GroupCard(group: MultiviewGroup, onOpen: () -> Unit, onMenu: () -> Unit) {
    val c = OmniTheme.colors
    val context = LocalContext.current
    FocusCard(
        onClick = onOpen,
        onLongClick = onMenu,
        glass = false,
        modifier = Modifier.width(260.dp).height(104.dp),
    ) {
        Column(Modifier.fillMaxSize().padding(OmniSpacing.l), verticalArrangement = Arrangement.spacedBy(OmniSpacing.s)) {
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(c.accent))
            Text(
                group.name, style = OmniTheme.type.title, color = c.textPrimary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (group.channels.size == 1) context.getString(R.string.multiview_group_channel, group.name)
                else context.getString(R.string.multiview_group_channels, group.name, group.channels.size),
                style = OmniTheme.type.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun GroupMenuOverlay(group: MultiviewGroup, onRename: () -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val c = OmniTheme.colors
    val first = remember(group.name) { FocusRequester() }
    Box(
        Modifier.fillMaxSize().background(Color(0xCC03060C)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 460.dp).clip(RoundedCornerShape(22.dp)).background(c.elevated).padding(OmniSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(OmniSpacing.s),
        ) {
            Text(group.name, style = OmniTheme.type.headline, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            OmniButton(
                LocalContext.current.getString(R.string.multiview_rename),
                onClick = onRename,
                modifier = Modifier.fillMaxWidth().focusRequester(first),
            )
            OmniButton(
                LocalContext.current.getString(R.string.multiview_delete),
                onClick = onDelete,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    LaunchedEffect(group.name) { runCatching { first.requestFocus() } }
}

@Composable
private fun NameDialog(title: String, initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    val c = OmniTheme.colors
    var value by remember { mutableStateOf(initial) }
    val field = remember { FocusRequester() }
    BackHandler { onDismiss() }
    Box(
        Modifier.fillMaxSize().background(Color(0xCC03060C)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 520.dp).clip(RoundedCornerShape(22.dp)).background(c.elevated).padding(OmniSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(OmniSpacing.m),
        ) {
            Text(title, style = OmniTheme.type.headline, color = c.textPrimary)
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(LocalContext.current.getString(R.string.multiview_group_name), color = c.textSecondary) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSave(value.trim()) }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = c.accent,
                    unfocusedBorderColor = c.surface,
                    cursorColor = c.accent,
                    focusedContainerColor = c.surface,
                    unfocusedContainerColor = c.surface,
                    focusedTextColor = c.textPrimary,
                    unfocusedTextColor = c.textPrimary,
                    focusedLabelColor = c.accent,
                    unfocusedLabelColor = c.textSecondary,
                ),
                textStyle = OmniTheme.type.body,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).focusRequester(field),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m)) {
                OmniButton(
                    LocalContext.current.getString(R.string.multiview_save),
                    onClick = { onSave(value.trim()) },
                    primary = true,
                )
                OmniButton(LocalContext.current.getString(R.string.multiview_cancel), onClick = onDismiss)
            }
        }
    }
    LaunchedEffect(title) { runCatching { field.requestFocus() } }
}
