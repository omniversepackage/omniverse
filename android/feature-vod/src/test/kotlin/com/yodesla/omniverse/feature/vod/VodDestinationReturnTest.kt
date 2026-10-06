package com.yodesla.omniverse.feature.vod

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Task 116: the category panel stays hidden across a title-detail round-trip. The flag lives in the
 * ViewModel (state.destinationOpen), so returning from a detail is not a panel action and the same
 * open destination comes back with the panel still hidden; only Back / Left at the far-left edge
 * reveals it, and closing the anime library lands on the service page (still an open destination).
 */
class VodDestinationReturnTest {
    private val enter = VodBrowsePanelAction.EnterDestination
    private val reveal = VodBrowsePanelAction.RevealPanel
    private val closeLibrary = VodBrowsePanelAction.CloseLibrary

    @Test fun enteringADestinationHidesThePanel() {
        assertTrue(vodPanelHidden(false, enter))
        assertTrue(vodPanelHidden(true, enter)) // already open stays open
    }

    @Test fun revealingShowsThePanel() {
        assertFalse(vodPanelHidden(true, reveal))
        assertFalse(vodPanelHidden(false, reveal)) // already shown stays shown
    }

    @Test fun closingTheAnimeLibraryKeepsTheServiceDestinationOpen() {
        // Back from the library returns to the Crunchyroll page, which is still a full destination.
        assertTrue(vodPanelHidden(true, closeLibrary))
    }

    @Test fun onlyRevealFlipsThePanelBackToVisible() {
        // The whole point of the fix: nothing but an explicit reveal reopens the panel, so a detail
        // round-trip (no action) can never resurrect it.
        val sequence = listOf(enter, closeLibrary, enter, closeLibrary)
        var hidden = false
        for (a in sequence) hidden = vodPanelHidden(hidden, a)
        assertTrue(hidden)
        hidden = vodPanelHidden(hidden, reveal)
        assertFalse(hidden)
    }
}
