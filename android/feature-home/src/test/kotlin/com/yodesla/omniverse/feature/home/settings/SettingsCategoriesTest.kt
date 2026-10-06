package com.yodesla.omniverse.feature.home.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsCategoriesTest {
    @Test
    fun defaultRailMatchesTheFeaturesThisBuildShips() {
        // No phone remote UI and no crash-log UI here, so those categories stay hidden.
        assertEquals(
            listOf(
                SettingsCategory.Sources, SettingsCategory.Playback, SettingsCategory.Display,
                SettingsCategory.ProfilesAndKids, SettingsCategory.Metadata, SettingsCategory.AboutAndUpdates,
            ),
            settingsCategories(),
        )
    }

    @Test
    fun slotBackedCategoriesAppearOnlyWhenTheAppProvidesContent() {
        assertEquals(
            listOf(
                SettingsCategory.Sources, SettingsCategory.Playback, SettingsCategory.Display,
                SettingsCategory.ProfilesAndKids, SettingsCategory.Metadata,
                SettingsCategory.PhoneRemote, SettingsCategory.BackupRestore, SettingsCategory.AboutAndUpdates,
            ),
            settingsCategories(hasPhoneRemote = true, hasBackup = true),
        )
    }

    @Test
    fun rowLevelFlagsNeverHideWholeCategories() {
        assertEquals(
            settingsCategories(),
            settingsCategories(compact = true, hasPlex = true, hasPin = true, guestMode = true),
        )
    }

    @Test
    fun tvOnlyRowsDisappearOnPhone() {
        assertEquals(true, settingsRows(SettingsCategory.Playback).contains(SettingsRowKeys.FRAME_RATE))
        assertEquals(false, settingsRows(SettingsCategory.Playback, compact = true).contains(SettingsRowKeys.FRAME_RATE))
        // Task 94: pre-loading is useful on phone too, so it is not a TV-only row.
        assertEquals(true, settingsRows(SettingsCategory.Playback).contains(SettingsRowKeys.PRELOAD_NEXT_CHANNEL))
        assertEquals(true, settingsRows(SettingsCategory.Playback, compact = true).contains(SettingsRowKeys.PRELOAD_NEXT_CHANNEL))
        // Task 95: spoiler protection belongs in Playback and works on phone too.
        assertEquals(true, settingsRows(SettingsCategory.Playback).contains(SettingsRowKeys.SPOILER_FREE))
        assertEquals(true, settingsRows(SettingsCategory.Playback, compact = true).contains(SettingsRowKeys.SPOILER_FREE))
        assertEquals(true, settingsRows(SettingsCategory.Playback).contains(SettingsRowKeys.SPOILER_FREE_HIDE_TITLES))
        // Task 107: the Back-from-search destination lives in Playback and works on phone too.
        assertEquals(true, settingsRows(SettingsCategory.Playback).contains(SettingsRowKeys.BACK_FROM_SEARCH))
        assertEquals(true, settingsRows(SettingsCategory.Playback, compact = true).contains(SettingsRowKeys.BACK_FROM_SEARCH))
        assertEquals(true, settingsRows(SettingsCategory.Display).contains(SettingsRowKeys.SCREENSAVER))
        assertEquals(false, settingsRows(SettingsCategory.Display, compact = true).contains(SettingsRowKeys.SCREENSAVER))
    }

    @Test
    fun guestModeSwapsTheProfileRows() {
        assertEquals(listOf(SettingsRowKeys.GUEST_NOTE, SettingsRowKeys.CREATE_PROFILE), settingsRows(SettingsCategory.ProfilesAndKids, guestMode = true))
        assertEquals(
            listOf(SettingsRowKeys.MANAGE, SettingsRowKeys.PICKER, SettingsRowKeys.PARENTAL),
            settingsRows(SettingsCategory.ProfilesAndKids, hasPin = true),
        )
    }

    @Test
    fun aboutAlwaysCarriesVersionAndLegalAndAddsUpdateOnlyWithTheSlot() {
        assertEquals(listOf(SettingsRowKeys.VERSION, SettingsRowKeys.LEGAL), settingsRows(SettingsCategory.AboutAndUpdates))
        assertEquals(
            listOf(SettingsRowKeys.VERSION, SettingsRowKeys.UPDATE_CHECK, SettingsRowKeys.LEGAL),
            settingsRows(SettingsCategory.AboutAndUpdates, hasUpdateCheck = true),
        )
        assertEquals(
            listOf(SettingsRowKeys.VERSION, SettingsRowKeys.UPDATE_CHECK, SettingsRowKeys.DIAGNOSTICS, SettingsRowKeys.LEGAL),
            settingsRows(SettingsCategory.AboutAndUpdates, hasUpdateCheck = true, hasDiagnostics = true),
        )
    }

    @Test
    fun simpleCategories() {
        assertEquals(
            listOf(
                SettingsRowKeys.CATEGORY_LANG,
                SettingsRowKeys.CATEGORY_LANG_UNTAGGED,
                SettingsRowKeys.CATEGORY_LANG_VOD,
                SettingsRowKeys.ADD_SOURCE,
            ),
            settingsRows(SettingsCategory.Sources),
        )
        assertEquals(listOf(SettingsRowKeys.WIKIDATA, SettingsRowKeys.RT), settingsRows(SettingsCategory.Metadata))
        assertEquals(emptyList<String>(), settingsRows(SettingsCategory.PhoneRemote))
        assertEquals(true, settingsRows(SettingsCategory.Display, hasCustomizeHome = true).contains(SettingsRowKeys.CUSTOMIZE_HOME))
        assertEquals(false, settingsRows(SettingsCategory.Display).contains(SettingsRowKeys.CUSTOMIZE_HOME))
    }
}
