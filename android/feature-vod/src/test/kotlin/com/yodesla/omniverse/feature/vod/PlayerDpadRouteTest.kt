package com.yodesla.omniverse.feature.vod

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Task 114: the player root must hand every D-pad direction to the overlay control that holds focus.
 * The reported bug was that "Next episode ›" could never be highlighted: the Sleep timer button sits
 * immediately to its left and was missing from the root's Left/Right guard, so Right seeked +10 s
 * instead of moving focus onto Next.
 */
class PlayerDpadRouteTest {
    private val dirs = listOf(PlayerDirection.UP, PlayerDirection.DOWN, PlayerDirection.LEFT, PlayerDirection.RIGHT)

    @Test fun rightFromSleepReachesNextInsteadOfSeeking() {
        // Focus on Sleep timer (the button left of Next), overlay open, no skip prompt.
        assertEquals(
            PlayerDpadRoute.HAND_TO_CONTROL,
            routePlayerDpad(PlayerDirection.RIGHT, rowControlFocused = true, panelControlFocused = false, overlayVisible = true, skipButtonVisible = false),
        )
        assertEquals(
            PlayerDpadRoute.HAND_TO_CONTROL,
            routePlayerDpad(PlayerDirection.LEFT, rowControlFocused = true, panelControlFocused = false, overlayVisible = true, skipButtonVisible = false),
        )
    }

    @Test fun seekingNeedsPictureFocus() {
        assertEquals(
            PlayerDpadRoute.SEEK_FORWARD,
            routePlayerDpad(PlayerDirection.RIGHT, false, false, overlayVisible = true, skipButtonVisible = false),
        )
        assertEquals(
            PlayerDpadRoute.SEEK_BACK,
            routePlayerDpad(PlayerDirection.LEFT, false, false, overlayVisible = true, skipButtonVisible = false),
        )
        assertEquals(
            PlayerDpadRoute.SEEK_FORWARD,
            routePlayerDpad(PlayerDirection.RIGHT, false, false, overlayVisible = false, skipButtonVisible = false),
        )
    }

    @Test fun noDirectionIsSpentOnSeekingWhileAnyControlHasFocus() {
        for (d in dirs) {
            for (row in listOf(true, false)) {
                for (panel in listOf(true, false)) {
                    if (!row && !panel) continue
                    for (overlay in listOf(true, false)) {
                        for (skip in listOf(true, false)) {
                            assertNotEquals(PlayerDpadRoute.SEEK_FORWARD, routePlayerDpad(d, row, panel, overlay, skip), d.name)
                            assertNotEquals(PlayerDpadRoute.SEEK_BACK, routePlayerDpad(d, row, panel, overlay, skip), d.name)
                        }
                    }
                }
            }
        }
    }

    @Test fun copyChooserKeepsLeftAndRightOffTheSeekBar() {
        assertEquals(
            PlayerDpadRoute.HAND_TO_CONTROL,
            routePlayerDpad(PlayerDirection.RIGHT, false, true, overlayVisible = true, skipButtonVisible = true),
        )
        assertEquals(
            PlayerDpadRoute.HAND_TO_CONTROL,
            routePlayerDpad(PlayerDirection.LEFT, false, true, overlayVisible = true, skipButtonVisible = true),
        )
    }

    @Test fun downOnAFocusedControlStillOpensSubtitles() {
        assertEquals(
            PlayerDpadRoute.TRACKS,
            routePlayerDpad(PlayerDirection.DOWN, true, false, overlayVisible = true, skipButtonVisible = true),
        )
        assertEquals(
            PlayerDpadRoute.TRACKS,
            routePlayerDpad(PlayerDirection.DOWN, true, false, overlayVisible = false, skipButtonVisible = false),
        )
    }

    @Test fun downFromThePictureWalksIntoTheActionRow() {
        assertEquals(
            PlayerDpadRoute.FOCUS_SKIP,
            routePlayerDpad(PlayerDirection.DOWN, false, false, overlayVisible = true, skipButtonVisible = true),
        )
        assertEquals(
            PlayerDpadRoute.FOCUS_SKIP_POINTS,
            routePlayerDpad(PlayerDirection.DOWN, false, false, overlayVisible = true, skipButtonVisible = false),
        )
        assertEquals(
            PlayerDpadRoute.OVERLAY,
            routePlayerDpad(PlayerDirection.DOWN, false, false, overlayVisible = false, skipButtonVisible = true),
        )
    }

    @Test fun upLeavesTheRowButStillTogglesStatsFromThePicture() {
        assertEquals(
            PlayerDpadRoute.PICTURE,
            routePlayerDpad(PlayerDirection.UP, true, false, overlayVisible = true, skipButtonVisible = true),
        )
        assertEquals(
            PlayerDpadRoute.STATS,
            routePlayerDpad(PlayerDirection.UP, false, false, overlayVisible = true, skipButtonVisible = false),
        )
        assertEquals(
            PlayerDpadRoute.OVERLAY,
            routePlayerDpad(PlayerDirection.UP, false, false, overlayVisible = false, skipButtonVisible = false),
        )
    }
}
