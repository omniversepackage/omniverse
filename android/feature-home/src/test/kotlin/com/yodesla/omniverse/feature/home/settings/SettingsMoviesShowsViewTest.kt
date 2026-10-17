package com.yodesla.omniverse.feature.home.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 203: the "Movies & Shows view" picker (Settings › Display, TV-only). The row must appear
 * in the pane contract in the order the pane actually emits it, and a search hit must land on it.
 */
class SettingsMoviesShowsViewTest {
    @Test
    fun rowIsBuiltInOnTvAndAbsentOnPhone() {
        assertTrue(SettingsRowKeys.MOVIES_SHOWS_VIEW in settingsRows(SettingsCategory.Display))
        assertTrue(SettingsRowKeys.MOVIES_SHOWS_VIEW !in settingsRows(SettingsCategory.Display, compact = true))
    }

    @Test
    fun paneKeyOrderMatchesThePaneEmissionOrder() {
        val keys = settingsPaneItemKeys(SettingsCategory.Display)
        assertEquals(
            listOf(
                SettingsRowKeys.TITLE, SettingsRowKeys.EXPERIENCE, SettingsRowKeys.HIDE_SPORTS_SCORES, SettingsRowKeys.TEXT_SIZE,
                SettingsRowKeys.MOVIES_SHOWS_VIEW, SettingsRowKeys.LEFT_MENU, SettingsRowKeys.MENU_WARNING, SettingsRowKeys.SCREENSAVER, SettingsRowKeys.END_SPACER,
            ),
            keys,
        )
        assertTrue(SettingsRowKeys.MOVIES_SHOWS_VIEW !in settingsPaneItemKeys(SettingsCategory.Display, compact = true))
    }

    @Test
    fun searchFindsTheRowAndJumpsToTheDisplayPaneRow() {
        val hits = searchSettings("movies shows view")
        assertEquals(SettingsRowKeys.MOVIES_SHOWS_VIEW, hits.first().rowKey)
        assertEquals(SettingsCategory.Display, hits.first().category)
        assertTrue(SettingsRowKeys.MOVIES_SHOWS_VIEW in searchSettings("horizontal").map { it.rowKey })
        val jump = settingsJumpIndex(settingsPaneItemKeys(SettingsCategory.Display), SettingsRowKeys.MOVIES_SHOWS_VIEW)
        assertTrue(jump >= 0)
        assertEquals(keysIndexOfTextSize() + 1, jump)
        // TV-only: on the phone build the row is not rendered, so the hit must be reported as a miss.
        assertEquals(-1, settingsJumpIndex(settingsPaneItemKeys(SettingsCategory.Display, compact = true), SettingsRowKeys.MOVIES_SHOWS_VIEW))
    }

    private fun keysIndexOfTextSize(): Int =
        settingsPaneItemKeys(SettingsCategory.Display).indexOf(SettingsRowKeys.TEXT_SIZE)
}
