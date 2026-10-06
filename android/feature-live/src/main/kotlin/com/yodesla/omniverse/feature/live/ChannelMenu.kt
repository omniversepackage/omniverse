package com.yodesla.omniverse.feature.live

import com.yodesla.omniverse.designsystem.MenuAction

/** What a channel's hold-OK menu can offer right now (drives which rows appear and their labels). */
data class ChannelMenuState(
    val favorite: Boolean,
    val inMultiview: Boolean,
    /** A live/archived programme is on this channel now, so "Watch" applies. */
    val canWatch: Boolean,
    /** A future programme is coming, so "Remind me" applies. */
    val canRemind: Boolean,
    val reminded: Boolean,
)

/**
 * The hold-OK menu for a channel in the Guide or the Live list (Task 67): Watch, Favorites,
 * Multiview, and a reminder for a future programme. Hold OK used to silently toggle the favourite;
 * it now opens this menu instead. Rows that don't apply to the current state are omitted.
 */
fun channelMenuActions(
    state: ChannelMenuState,
    onWatch: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleMultiview: () -> Unit,
    onRemind: () -> Unit,
): List<MenuAction> = buildList {
    if (state.canWatch) add(MenuAction("Watch", onWatch))
    add(MenuAction(if (state.favorite) "Remove from Favorites" else "Add to Favorites", onToggleFavorite))
    add(MenuAction(if (state.inMultiview) "Remove from Multiview" else "Add to Multiview", onToggleMultiview))
    if (state.canRemind) add(MenuAction(if (state.reminded) "Reminder set" else "Remind me", onRemind))
}
