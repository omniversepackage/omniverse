package com.yodesla.omniverse.feature.live

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Task 67: the hold-OK menu's rows depend on the current state — Watch only when something is
 * playable, Remind only for a future programme, and the Favorites/Multiview labels flip with membership.
 */
class ChannelMenuTest {
    private fun labels(state: ChannelMenuState) =
        channelMenuActions(state, {}, {}, {}, {}).map { it.label }

    @Test fun fullStateShowsAllFourInOrder() {
        assertEquals(
            listOf("Watch", "Add to Favorites", "Add to Multiview", "Remind me"),
            labels(ChannelMenuState(favorite = false, inMultiview = false, canWatch = true, canRemind = true, reminded = false)),
        )
    }

    @Test fun watchRowOnlyWhenPlayable() {
        assertFalse(labels(ChannelMenuState(false, false, canWatch = false, canRemind = false, reminded = false)).contains("Watch"))
        assertTrue(labels(ChannelMenuState(false, false, canWatch = true, canRemind = false, reminded = false)).contains("Watch"))
    }

    @Test fun membershipFlipsTheLabels() {
        assertEquals(
            listOf("Remove from Favorites", "Remove from Multiview"),
            labels(ChannelMenuState(favorite = true, inMultiview = true, canWatch = false, canRemind = false, reminded = false)),
        )
    }

    @Test fun remindRowOnlyForFutureAndFlipsWhenAlreadySet() {
        assertFalse(labels(ChannelMenuState(false, false, canWatch = false, canRemind = false, reminded = false)).any { it.startsWith("Remind") })
        assertEquals(
            listOf("Remind me"),
            labels(ChannelMenuState(false, false, canWatch = false, canRemind = true, reminded = false)).filter { it.startsWith("Remind") },
        )
        assertEquals(
            listOf("Reminder set"),
            labels(ChannelMenuState(false, false, canWatch = false, canRemind = true, reminded = true)).filter { it.startsWith("Remind") },
        )
    }
}
